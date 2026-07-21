"""The conversation loop.

**Tools are OFF by default** (`--tools` opts in). Llama-3.2-1B at 4-bit does
not call tools reliably enough to build on yet -- measured on-device, it
hallucinated tool names, invented tool results, and was extremely sensitive to
system-prompt length. The plain chat path below is solid, so that is the
default; the tool machinery is kept intact behind the flag for a later pass.
See docs/CHATBOT.md "Tool calling" for exactly what was measured.

With tools enabled, `parse_tool_call` accepts every syntax shape the model was
actually observed to emit rather than demanding one canonical form, and
anything unparseable is treated as ordinary prose -- a wrong guess about intent
is worse than a slightly odd reply.
"""
from __future__ import annotations

import json
import re
from dataclasses import dataclass
from typing import Callable

from . import tools
from .prompt import Message, TOOL_RESULT_ROLE, fit_to_context, render

CHAT_SYSTEM_PROMPT = (
    "You are a helpful voice assistant running on a phone. "
    "Answer in one or two short, natural sentences."
)

# Kept deliberately SHORT. Measured on-device (Llama-3.2-1B, w4): a ~2000-char
# system prompt full of prose rules made the model ignore tools entirely and
# hallucinate answers, while this ~800-char version calls them correctly. At
# 4-bit, instruction-following degrades fast with system-prompt length --
# spend the budget on few-shot turns below instead, which work far better.
TOOL_SYSTEM_PROMPT = """You are a voice assistant on an Android phone. Keep answers short.

Tools:
{catalog}

To use a tool, reply with ONLY: TOOL: name {{"arg": "value"}}
Use a tool for phone data, live information, and arithmetic.
Otherwise answer directly with no TOOL line."""

# Few-shot as REAL conversation turns, not text inside the system prompt.
# Writing them as "User: ... You: ..." lines inside the system prompt made the
# model echo a literal "You:" prefix into its replies; as actual turns it
# doesn't. The first exchange also demonstrates the ipython result hand-back,
# which is what teaches it to answer FROM a tool result instead of re-calling.
FEWSHOT = [
    Message("user", "how much battery do I have?"),
    Message("assistant", 'TOOL: device_status {}'),
    Message(TOOL_RESULT_ROLE, "battery: 80% storage: 12G free of 64G"),
    Message("assistant", "You have 80% battery left."),
    Message("user", "what is 20 times 13?"),
    Message("assistant", 'TOOL: calculate {"expression": "20*13"}'),
    Message(TOOL_RESULT_ROLE, "260"),
    Message("assistant", "That's 260."),
    Message("user", "what is the capital of France?"),
    Message("assistant", "Paris."),
]

# Cap tool hops per user turn. A 1B model that misreads a tool result will
# happily re-call the same tool forever; this bounds the damage.
MAX_TOOL_HOPS = 3


@dataclass
class ToolCall:
    name: str
    args: dict


def _coerce_args(name: str, args) -> dict:
    """Map whatever the model produced onto the tool's real parameter names.

    Small models very often emit a bare scalar ({"query": ...} becomes just
    "weather") or use a synonym for the parameter. Rather than fail, bind a
    single unnamed value to the tool's first parameter.
    """
    tool = tools.REGISTRY[name]
    params = [
        p.strip().split("=")[0]
        for p in tool.signature.split("(", 1)[1].rstrip(")").split(",")
        if p.strip()
    ]
    if isinstance(args, dict):
        if not params:
            return {}
        # Exact matches win; anything left over binds positionally.
        bound = {k: v for k, v in args.items() if k in params}
        leftovers = [v for k, v in args.items() if k not in params]
        for param in params:
            if param not in bound and leftovers:
                bound[param] = leftovers.pop(0)
        return bound
    if args in (None, ""):
        return {}
    return {params[0]: args} if params else {}


def parse_tool_call(text: str) -> ToolCall | None:
    """Recognise a tool call in model output, or return None for plain prose.

    Accepts, in priority order:
      TOOL: name {"a": 1}          -- the format the system prompt asks for
      {"tool": "name", "args": {}} -- generic JSON, incl. "name"/"parameters"
      <|python_tag|>name(a="1")    -- Llama 3.x's own tool syntax
      name({"a": 1}) / name(a=1)   -- bare call on a line by itself
    """
    text = text.strip()

    # 1. The requested format.
    match = re.search(r"TOOL:\s*([A-Za-z_]\w*)\s*(\{.*\})?", text, re.S)
    if match and match.group(1) in tools.REGISTRY:
        raw = match.group(2)
        try:
            args = json.loads(raw) if raw else {}
        except json.JSONDecodeError:
            args = {}
        return ToolCall(match.group(1), _coerce_args(match.group(1), args))

    # 2. Generic JSON object naming a tool.
    for blob in re.findall(r"\{.*?\}(?=\s*$|\s*\n)|\{.*\}", text, re.S):
        try:
            data = json.loads(blob)
        except json.JSONDecodeError:
            continue
        if not isinstance(data, dict):
            continue
        name = data.get("tool") or data.get("name") or data.get("function")
        if isinstance(name, str) and name in tools.REGISTRY:
            args = data.get("args", data.get("parameters", data.get("arguments", {})))
            return ToolCall(name, _coerce_args(name, args))

    # 3/4. Llama's python_tag syntax, or a bare single-line call.
    match = re.search(r"(?:<\|python_tag\|>)?\b([A-Za-z_]\w*)\((.*)\)", text, re.S)
    if match and match.group(1) in tools.REGISTRY:
        name, inner = match.group(1), match.group(2).strip()
        if not inner:
            return ToolCall(name, {})
        try:  # name({"a": 1})
            return ToolCall(name, _coerce_args(name, json.loads(inner)))
        except json.JSONDecodeError:
            pass
        kwargs = dict(re.findall(r'(\w+)\s*=\s*["\']?([^,"\']*)["\']?', inner))
        if kwargs:
            return ToolCall(name, _coerce_args(name, kwargs))
        return ToolCall(name, _coerce_args(name, inner.strip("\"'")))

    return None


def attempted_tool_name(text: str) -> str | None:
    """Name of a tool the model tried to call that doesn't exist.

    Observed on real hardware: the 1B model answers "capital of France" with
    `TOOL: osmdroid info "Paris"`. Without this, that raw line is shown to the
    user as if it were prose. Detecting the attempt lets the agent correct the
    model instead, which it recovers from reliably.
    """
    match = re.search(r"TOOL:\s*([A-Za-z_]\w*)", text)
    if match and match.group(1) not in tools.REGISTRY:
        return match.group(1)
    return None


class Agent:
    def __init__(
        self,
        runtime,
        context_length: int,
        confirm: Callable[[str], bool],
        log: Callable[[str], None] = print,
        enable_tools: bool = False,
    ) -> None:
        self.runtime = runtime
        self.context_length = context_length
        self.confirm = confirm
        self.log = log
        self.enable_tools = enable_tools
        if enable_tools:
            self.system = Message("system", TOOL_SYSTEM_PROMPT.format(catalog=tools.catalog()))
            self.pinned = FEWSHOT
        else:
            self.system = Message("system", CHAT_SYSTEM_PROMPT)
            self.pinned = []
        self.history: list[Message] = []

    def _generate(self) -> str:
        messages = fit_to_context(
            self.system, self.history, self.context_length, pinned=self.pinned
        )
        return self.runtime.generate(render(messages))

    def _run_tool(self, call: ToolCall) -> str:
        tool = tools.REGISTRY[call.name]
        pretty = ", ".join(f"{k}={v!r}" for k, v in call.args.items())
        self.log(f"  [tool] {call.name}({pretty})")

        if tool.confirm and not self.confirm(f"Allow {call.name}({pretty})?"):
            return "The user declined to run this tool. Tell them it was cancelled."
        try:
            return tools.truncate(str(tool.func(**call.args)))
        except TypeError as exc:  # wrong/missing args from the model
            return f"Tool call was malformed ({exc}). Try different arguments or answer directly."
        except Exception as exc:
            return f"Tool failed: {exc}"

    def ask(self, user_text: str) -> str:
        """Run one user turn to completion, including any tool hops."""
        self.history.append(Message("user", user_text))

        if not self.enable_tools:
            reply = self._generate()
            self.history.append(Message("assistant", reply))
            return reply

        for _ in range(MAX_TOOL_HOPS):
            reply = self._generate()
            call = parse_tool_call(reply)
            if call is None:
                bogus = attempted_tool_name(reply)
                if bogus is not None:
                    self.log(f"  [tool] '{bogus}' does not exist -- correcting the model")
                    self.history.append(Message("assistant", reply))
                    self.history.append(Message(
                        TOOL_RESULT_ROLE,
                        f"There is no tool called '{bogus}'. The only tools are: "
                        f"{', '.join(tools.REGISTRY)}. If none of them fit, "
                        f"answer the user directly in plain words.",
                    ))
                    continue
                self.history.append(Message("assistant", reply))
                return reply
            # Keep the model's own tool-call turn in history so it can see what
            # it already tried -- without it, small models re-issue the same call.
            self.history.append(Message("assistant", reply))
            self.history.append(Message(TOOL_RESULT_ROLE, self._run_tool(call)))

        # Out of hops: force a plain answer from what's already gathered.
        self.history.append(
            Message(TOOL_RESULT_ROLE, "No more tool calls allowed. Answer the user now in plain words.")
        )
        reply = self._generate()
        if parse_tool_call(reply) is not None:
            # Still looping. Don't show raw tool-call syntax to the user -- say
            # plainly that it didn't get there rather than emitting gibberish.
            reply = ("I wasn't able to work that out -- I kept trying to look "
                     "things up without settling on an answer. Try rephrasing?")
        self.history.append(Message("assistant", reply))
        return reply
