"""Tool catalog exposed to the on-device model.

Design constraints that shaped this file, both driven by the 1B/4-bit model and
the 4096-token context:

* **Schemas must be tiny.** The catalog is re-sent on every turn, so each tool
  costs its description in tokens forever. One compact line per tool (~15-25
  tokens); the whole catalog below is ~250 tokens of the 4096. That is the
  answer to "will all these tools fill up the context" -- the catalog is cheap,
  it's the RESULTS that are expensive, so every tool truncates its output
  (MAX_RESULT_CHARS) rather than returning whatever the device/web hands back.

* **Results are prose, not JSON.** A 1B model re-reading its own nested JSON
  tends to loop. Flat human-readable lines work better and cost fewer tokens.

* **Anything outward-facing or hard to reverse asks first.** Placing a call or
  sending an SMS goes through the injected `confirm` callback, which the agent
  wires to a terminal prompt. Model output is never trusted to authorize a
  real-world side effect on its own.
"""
from __future__ import annotations

import ast
import html
import operator
import re
import time
from dataclasses import dataclass
from datetime import datetime
from typing import Callable

from . import device

# Hard cap on what any single tool may inject into the context window. Web
# pages routinely exceed the entire context on their own.
MAX_RESULT_CHARS = 1200


@dataclass
class Tool:
    name: str
    signature: str  # e.g. "web_search(query)"
    description: str  # one short line -- this is context budget, keep it terse
    func: Callable[..., str]
    confirm: bool = False  # ask the human before running (side effects)


REGISTRY: dict[str, Tool] = {}


def tool(signature: str, description: str, confirm: bool = False):
    name = signature.split("(")[0]

    def decorator(func):
        REGISTRY[name] = Tool(name, signature, description, func, confirm)
        return func

    return decorator


def catalog() -> str:
    """The tool list as it appears in the system prompt."""
    return "\n".join(f"- {t.signature}: {t.description}" for t in REGISTRY.values())


def truncate(text: str, limit: int = MAX_RESULT_CHARS) -> str:
    text = text.strip()
    if len(text) <= limit:
        return text
    return text[:limit].rstrip() + f"\n...[truncated, {len(text) - limit} more chars]"


# --------------------------------------------------------------------------
# Local utilities
# --------------------------------------------------------------------------


@tool("get_time()", "Current date and time.")
def get_time() -> str:
    return datetime.now().strftime("%A %d %B %Y, %H:%M")


_MATH_OPS = {
    ast.Add: operator.add, ast.Sub: operator.sub, ast.Mult: operator.mul,
    ast.Div: operator.truediv, ast.Pow: operator.pow, ast.Mod: operator.mod,
    ast.FloorDiv: operator.floordiv, ast.USub: operator.neg, ast.UAdd: operator.pos,
}


def _eval_node(node):
    """Arithmetic-only AST walk. Never use eval() on model output."""
    if isinstance(node, ast.Constant) and isinstance(node.value, (int, float)):
        return node.value
    if isinstance(node, ast.BinOp) and type(node.op) in _MATH_OPS:
        return _MATH_OPS[type(node.op)](_eval_node(node.left), _eval_node(node.right))
    if isinstance(node, ast.UnaryOp) and type(node.op) in _MATH_OPS:
        return _MATH_OPS[type(node.op)](_eval_node(node.operand))
    raise ValueError("unsupported expression")


@tool("calculate(expression)", "Evaluate arithmetic, e.g. '12*(3+4)'.")
def calculate(expression: str) -> str:
    try:
        return str(_eval_node(ast.parse(str(expression), mode="eval").body))
    except Exception:
        return f"Could not evaluate {expression!r} (arithmetic only)."


@tool("device_status()", "Phone battery level and free storage.")
def device_status() -> str:
    battery = device.adb_shell("dumpsys battery 2>/dev/null || true", check=False)
    level = re.search(r"level:\s*(\d+)", battery)
    status = re.search(r"status:\s*(\d+)", battery)
    storage = device.adb_shell("df -h /data 2>/dev/null | tail -1", check=False).split()
    parts = [f"battery: {level.group(1)}%" if level else "battery: unknown"]
    if status and status.group(1) == "2":
        parts.append("(charging)")
    if len(storage) >= 4:
        parts.append(f"storage: {storage[3]} free of {storage[1]}")
    return " ".join(parts)


# --------------------------------------------------------------------------
# Contacts (read-only)
# --------------------------------------------------------------------------


@tool("find_contact(name)", "Look up a contact's phone number by name.")
def find_contact(name: str) -> str:
    out = device.adb_shell(
        "content query --uri content://com.android.contacts/data/phones "
        "--projection display_name:data1 2>/dev/null || true",
        check=False,
    )
    needle = str(name).strip().lower()
    matches = []
    for line in out.splitlines():
        display = re.search(r"display_name=([^,]*)", line)
        number = re.search(r"data1=(.*?)(?:,\s*\w+=|$)", line)
        if not display or not number:
            continue
        if needle in display.group(1).strip().lower():
            matches.append(f"{display.group(1).strip()}: {number.group(1).strip()}")
    if not matches:
        if "Row:" not in out:
            return (
                "No contacts are readable on this device (the contacts provider "
                "returned nothing -- the device may have no contacts app or data)."
            )
        return f"No contact matching {name!r}."
    return truncate("\n".join(dict.fromkeys(matches)))


# --------------------------------------------------------------------------
# Web
# --------------------------------------------------------------------------

_TAG_RE = re.compile(r"<(script|style)[^>]*>.*?</\1>", re.S | re.I)
_STRIP_RE = re.compile(r"<[^>]+>")
_WS_RE = re.compile(r"\n\s*\n+")
# A browser UA is required, not cosmetic: DuckDuckGo answers self-identifying
# agents with an HTTP 202 bot-challenge page containing zero results, which
# looks exactly like "no results found" rather than like an error.
USER_AGENT = (
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/125.0 Safari/537.36"
)


def _html_to_text(raw: str) -> str:
    raw = _TAG_RE.sub(" ", raw)
    text = _STRIP_RE.sub(" ", raw)
    text = html.unescape(text)
    text = re.sub(r"[ \t]+", " ", text)
    return _WS_RE.sub("\n\n", text).strip()


@tool("web_search(query)", "Search the web; returns top result titles and snippets.")
def web_search(query: str) -> str:
    import requests  # imported lazily so device-only usage needs no network deps

    try:
        resp = requests.post(
            "https://html.duckduckgo.com/html/",
            data={"q": str(query)},
            headers={"User-Agent": USER_AGENT},
            timeout=20,
        )
        resp.raise_for_status()
    except Exception as exc:
        return f"Web search failed: {exc}"

    results = []
    for block in re.findall(
        r'<a[^>]*class="result__a"[^>]*href="([^"]+)"[^>]*>(.*?)</a>(.*?)(?=<a[^>]*class="result__a"|$)',
        resp.text, re.S,
    )[:4]:
        url, title, tail = block
        snippet = re.search(r'class="result__snippet"[^>]*>(.*?)</a>', tail, re.S)
        entry = f"{_html_to_text(title)} -- {html.unescape(url)}"
        if snippet:
            entry += f"\n  {_html_to_text(snippet.group(1))}"
        results.append(entry)
    if not results:
        return f"No results for {query!r}."
    return truncate("\n".join(results))


@tool("web_fetch(url)", "Fetch a web page and return its readable text.")
def web_fetch(url: str) -> str:
    import requests

    url = str(url).strip()
    if not url.startswith(("http://", "https://")):
        url = "https://" + url
    try:
        resp = requests.get(url, headers={"User-Agent": USER_AGENT}, timeout=25)
        resp.raise_for_status()
    except Exception as exc:
        return f"Could not fetch {url}: {exc}"
    return truncate(_html_to_text(resp.text))


# --------------------------------------------------------------------------
# Device actions (side effects -- these confirm first)
# --------------------------------------------------------------------------


@tool("list_apps(filter)", "List installed app package names matching a word.")
def list_apps(filter: str = "") -> str:
    out = device.adb_shell("pm list packages 2>/dev/null || true", check=False)
    packages = [line.split(":", 1)[-1].strip() for line in out.splitlines() if ":" in line]
    needle = str(filter).strip().lower()
    if needle:
        packages = [p for p in packages if needle in p.lower()]
    if not packages:
        return f"No installed packages matching {filter!r}."
    return truncate("\n".join(sorted(packages)[:25]))


@tool("launch_app(package)", "Open an installed app by package name.", confirm=True)
def launch_app(package: str) -> str:
    pkg = device.quote(str(package).strip())
    out = device.adb_shell(
        f"monkey -p {pkg} -c android.intent.category.LAUNCHER 1 2>&1", check=False
    )
    if "No activities found" in out or "Error" in out:
        return f"Could not launch {package}: {truncate(out, 200)}"
    return f"Launched {package}."


@tool("send_sms(number, message)", "Open the SMS composer prefilled to a number.", confirm=True)
def send_sms(number: str, message: str) -> str:
    # Deliberately opens the composer rather than sending silently: actually
    # transmitting requires being the default SMS app, and a message leaving
    # the device on a 1B model's say-so should stay a human decision. The user
    # taps send.
    num = device.quote(f"sms:{str(number).strip()}")
    body = device.quote(str(message))
    device.adb_shell(
        f"am start -a android.intent.action.SENDTO -d {num} --es sms_body {body} 2>&1",
        check=False,
    )
    return f"Opened the SMS composer to {number} with the message ready to send."


@tool("call(number)", "Place a phone call.", confirm=True)
def call(number: str) -> str:
    tel = device.quote(f"tel:{str(number).strip()}")
    device.adb_shell(f"am start -a android.intent.action.CALL -d {tel} 2>&1", check=False)
    return f"Calling {number}."


@tool("set_timer(seconds, label)", "Start a countdown timer on the phone.", confirm=True)
def set_timer(seconds: int, label: str = "timer") -> str:
    try:
        secs = int(float(seconds))
    except (TypeError, ValueError):
        return f"{seconds!r} is not a number of seconds."
    device.adb_shell(
        "am start -a android.intent.action.SET_TIMER "
        f"--ei android.intent.extra.alarm.LENGTH {secs} "
        f"--es android.intent.extra.alarm.MESSAGE {device.quote(str(label))} "
        "--ez android.intent.extra.alarm.SKIP_UI true 2>&1",
        check=False,
    )
    return f"Timer set for {secs} seconds ({label})."


@tool("screenshot()", "Capture the phone screen to a file on this host.", confirm=True)
def screenshot() -> str:
    remote = "/sdcard/chatbot_shot.png"
    local = f"/tmp/chatbot_shot_{int(time.time())}.png"
    device.adb_shell(f"screencap -p {remote}", check=False)
    try:
        device.adb("pull", remote, local, timeout=60)
    except device.AdbError as exc:
        return f"Screenshot failed: {exc}"
    return f"Screenshot saved to {local} on the host."
