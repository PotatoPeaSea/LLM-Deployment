#!/usr/bin/env python3
"""Drive GenieChatRN's debug build from the host, without the JS UI or a screenshot.

Why this exists: see ../HANDOFF-reasoning-tools-fixes.md. Some of this app's
bugs are a full device reboot, not just an app-level crash, and reproducing
one by hand -- typing a prompt, screenshotting, typing the next -- is slow
and the on-device logcat ring buffer is wiped by the time adb reconnects
after a reboot, which is exactly why the last debugging session never got a
tombstone. This script:

  1. Tails `adb logcat` continuously to a file on THIS host, across every
     prompt, so a mid-turn reboot doesn't destroy the evidence.
  2. Fires one prompt at a time at CliReceiver (a debug-build-only
     BroadcastReceiver, see android/app/src/debug/java/.../CliReceiver.kt)
     which calls the exact same GenieModule/ChatEngine/GenieXEngine path the
     real UI does.
  3. Watches the HOST side for the device dropping off adb / uptime
     resetting (reboot) or the app's pid changing without a reboot (app
     crash), instead of relying on the app to report its own death.
  4. On a reboot or crash, waits for the device to come back, relaunches the
     app, and (by default) keeps going through the remaining prompts, so one
     run tells you which of many prompts are the actual triggers.

Requires the debug variant installed (`cd android && ./gradlew installDebug`)
-- the CliReceiver/manifest entry only exist in that build type.

Usage:
  python3 genie_cli.py --prompt "What is 17 times 24"
  python3 genie_cli.py --prompts-file prompts.txt
  python3 genie_cli.py --smoke-test                    # built-in trouble prompts
  python3 genie_cli.py --smoke-test --model qwen3_5_2b --timeout 240

prompts.txt is one prompt per line ('#' comments and blank lines skipped), or
a .json file containing a list of strings.
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import tempfile
import threading
import time
from dataclasses import dataclass
from pathlib import Path
from queue import Empty, Queue
from typing import Optional

PKG = "com.geniechatrn"
ACTIVITY = f"{PKG}/.MainActivity"
ACTION = "com.geniechatrn.CLI_PROMPT"
DEVICE_FILES_DIR = f"/sdcard/Android/data/{PKG}/files/cli"
DEFAULT_MODEL = "qwen3_5_2b"

FATAL_MARKERS = ("FATAL EXCEPTION", "Fatal signal", "*** *** *** ***", "AndroidRuntime: FATAL")

# The specific prompts HANDOFF-reasoning-tools-fixes.md and its predecessors
# implicated: the reboot trigger ("essay about the telephone"), the tool-call
# path (web_search with and without network), and the separately-documented
# Jinja SIGABRT ("capital of France"). Good default battery for "does the
# known-good baseline actually survive all of these."
SMOKE_TEST_PROMPTS = [
    "What is 17 times 24",
    "Please use web search to look up who invented the telephone",
    "Write an essay about the telephone",
    "Search the web for the capital of France",
]


def adb(args: list[str], serial: Optional[str] = None, timeout: Optional[float] = None):
    cmd = ["adb"] + (["-s", serial] if serial else []) + args
    return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)


def adb_shell(cmd_str: str, serial: Optional[str] = None, timeout: float = 15):
    return adb(["shell", cmd_str], serial=serial, timeout=timeout)


def device_uptime(serial: Optional[str]) -> Optional[float]:
    try:
        r = adb_shell("cat /proc/uptime", serial, timeout=10)
    except subprocess.TimeoutExpired:
        return None
    if r.returncode != 0 or not r.stdout.strip():
        return None
    try:
        return float(r.stdout.split()[0])
    except (ValueError, IndexError):
        return None


def device_pid(serial: Optional[str]) -> Optional[str]:
    try:
        r = adb_shell(f"pidof {PKG}", serial, timeout=10)
    except subprocess.TimeoutExpired:
        return None
    return r.stdout.strip() or None


def boot_reason(serial: Optional[str]) -> str:
    try:
        r = adb_shell("getprop ro.boot.bootreason", serial, timeout=10)
    except subprocess.TimeoutExpired:
        return "?"
    return r.stdout.strip()


def rebooted_since(pre_uptime: Optional[float], serial: Optional[str]) -> bool:
    """True if the device is unreachable or its uptime went backwards.

    A single failed `adb shell` call is NOT enough to call this a reboot --
    under heavy native/DSP load (e.g. a retrying `Llm.create`) adb itself can
    stall for a beat without the device actually going anywhere. Only commit
    to "unreachable" after three failures in a row (~3s), which a genuine
    reboot trivially clears and a transient hiccup does not.
    """
    for attempt in range(3):
        cur = device_uptime(serial)
        if cur is not None:
            return pre_uptime is not None and cur < pre_uptime
        if attempt < 2:
            time.sleep(1.0)
    return True
    return pre_uptime is not None and cur < pre_uptime


class LogTail:
    """Continuously tails `adb logcat` to a host-side file AND a live queue.

    Runs across every prompt in the batch (not restarted per-turn) so a
    reboot mid-turn is captured up to the exact last line instead of being
    lost with the on-device ring buffer.
    """

    def __init__(self, serial: Optional[str], out_path: Path):
        self.serial = serial
        self.out_path = out_path
        self.queue: "Queue[str]" = Queue()
        self._proc: Optional[subprocess.Popen] = None
        self._fh = out_path.open("a", buffering=1)

    def start(self) -> None:
        adb(["logcat", "-c"], serial=self.serial, timeout=10)
        cmd = ["adb"] + (["-s", self.serial] if self.serial else []) + ["logcat", "-v", "time"]
        self._proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        threading.Thread(target=self._pump, daemon=True).start()

    def _pump(self) -> None:
        assert self._proc is not None and self._proc.stdout is not None
        for line in self._proc.stdout:
            self._fh.write(line)
            self.queue.put(line)
        # adb logcat exiting on its own almost always means the device
        # dropped off adb (reboot), not a normal condition.
        self.queue.put("__LOGCAT_EOF__\n")

    def is_alive(self) -> bool:
        return self._proc is not None and self._proc.poll() is None

    def stop(self) -> None:
        if self._proc and self._proc.poll() is None:
            self._proc.terminate()
        self._fh.close()


def wait_for_device_back(serial: Optional[str], log, timeout: float = 240) -> bool:
    """Wait for the device to reboot and come back.

    The device coming back is only half of this: after a real reboot, this
    HOST's adb server can lose track of the USB device entirely and never
    rescan on its own (`adb get-state` just hangs/fails indefinitely even
    though the device finished booting minutes ago -- confirmed by hand once,
    `adb kill-server` was what actually fixed it). So this restarts the local
    adb server periodically while waiting, not just the single
    `wait-for-device` call.
    """
    log(f"waiting for device to come back (timeout {timeout:.0f}s)...")
    t0 = time.time()
    last_server_restart = t0
    while time.time() - t0 < timeout:
        try:
            r = adb_shell("getprop sys.boot_completed", serial, timeout=10)
            if r.stdout.strip() == "1":
                log(f"device back after {time.time() - t0:.0f}s (bootreason={boot_reason(serial)})")
                return True
        except subprocess.TimeoutExpired:
            pass
        if time.time() - last_server_restart > 15:
            last_server_restart = time.time()
            try:
                subprocess.run(["adb", "kill-server"], capture_output=True, timeout=10)
            except subprocess.TimeoutExpired:
                pass
        time.sleep(2)
    return False


@dataclass
class TurnOutcome:
    turn_id: str
    prompt: str
    status: str  # ok, error, timeout, app_crash, reboot
    elapsed_s: float
    detail: str = ""
    result: Optional[dict] = None


def launch_app(serial: Optional[str]) -> None:
    adb_shell(f"am start -n {ACTIVITY}", serial=serial, timeout=15)


def read_result_json(serial: Optional[str], turn_id: str) -> Optional[dict]:
    r = adb_shell(f"cat {DEVICE_FILES_DIR}/{turn_id}.json", serial, timeout=10)
    if r.returncode != 0 or not r.stdout.strip():
        return None
    try:
        return json.loads(r.stdout)
    except json.JSONDecodeError:
        return None


def run_one_turn(
    serial: Optional[str],
    turn_id: str,
    prompt: str,
    model: str,
    chat_id: str,
    thinking: bool,
    brevity: bool,
    reset: bool,
    tail: LogTail,
    timeout_s: float,
) -> TurnOutcome:
    t0 = time.time()
    pre_uptime = device_uptime(serial)
    pre_pid = device_pid(serial)

    # The prompt goes over as a PUSHED FILE, not a broadcast extra: an
    # essay-length, quoted, or unicode prompt is exactly the kind of thing
    # that gets mangled by `adb shell am broadcast`'s argv join. See
    # CliReceiver's textFile handling.
    with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False) as f:
        f.write(prompt)
        local_path = f.name
    remote_path = f"{DEVICE_FILES_DIR}/in/{turn_id}.txt"
    adb_shell(f"mkdir -p {DEVICE_FILES_DIR}/in", serial, timeout=10)
    push = adb(["push", local_path, remote_path], serial=serial, timeout=30)
    Path(local_path).unlink(missing_ok=True)
    if push.returncode != 0:
        return TurnOutcome(turn_id, prompt, "error", time.time() - t0, f"adb push failed: {push.stderr.strip()}")

    bcast = adb(
        [
            # -n (explicit component), not just -a: an action-only broadcast
            # is IMPLICIT, and Android 8+ silently drops implicit broadcasts
            # to an app that isn't in the foreground ("Background execution
            # not allowed" in logcat) -- which is exactly what a multi-prompt
            # unattended run looks like after the first turn or two.
            "shell", "am", "broadcast", "-n", f"{PKG}/.genie.CliReceiver", "-a", ACTION,
            "--es", "turnId", turn_id,
            "--es", "chatId", chat_id,
            "--es", "modelId", model,
            "--es", "textFile", remote_path,
            "--ez", "thinking", "true" if thinking else "false",
            "--ez", "brevity", "true" if brevity else "false",
            "--ez", "reset", "true" if reset else "false",
        ],
        serial=serial,
        timeout=15,
    )
    if bcast.returncode != 0:
        return TurnOutcome(turn_id, prompt, "error", time.time() - t0, f"broadcast failed: {bcast.stderr.strip()}")

    marker = re.compile(rf"CLI_(RESULT|ERROR) turnId={re.escape(turn_id)}\b")
    # This device's logcat is extremely noisy (SDM/graphics spam) even when
    # idle -- tens of thousands of lines can pile up in the queue between two
    # real events. Scope the "fatal" grep to OUR pid so an unrelated, totally
    # normal recurring crash on this board (vendor.qti.camera.provider-service
    # SIGABRTs periodically all by itself) doesn't get misreported as our
    # app's failure.
    pid_marker = re.compile(rf"\(\s*{re.escape(pre_pid)}\)") if pre_pid else None
    fatal_lines: list[str] = []
    deadline = time.time() + timeout_s
    # Liveness (adb shell round-trip) is throttled, NOT checked once per
    # drained line: with a large backlog the queue.get() below returns
    # near-instantly thousands of times in a row, and one real adb round-trip
    # per line turned what was actually a <25s reply into an apparent 200s+
    # stall the one time this bug was live (see HANDOFF... investigation
    # notes) -- the deadline kept expiring on backlog-draining speed, not on
    # anything the device was doing.
    last_liveness_check = 0.0
    liveness_interval = 2.0
    while time.time() < deadline:
        try:
            line = tail.queue.get(timeout=1.0)
        except Empty:
            line = None

        if line == "__LOGCAT_EOF__\n":
            break  # logcat died -- almost certainly a reboot; confirmed below.

        if line:
            if any(marker_str in line for marker_str in FATAL_MARKERS):
                if pid_marker is None or pid_marker.search(line):
                    fatal_lines.append(line.rstrip())
            m = marker.search(line)
            if m:
                elapsed = time.time() - t0
                result = read_result_json(serial, turn_id)
                status = "ok" if m.group(1) == "RESULT" else "error"
                return TurnOutcome(
                    turn_id, prompt, status, elapsed,
                    "\n".join(fatal_lines) if status == "error" else "", result,
                )

        now = time.time()
        if now - last_liveness_check >= liveness_interval:
            last_liveness_check = now
            if rebooted_since(pre_uptime, serial):
                return TurnOutcome(
                    turn_id, prompt, "reboot", time.time() - t0,
                    "\n".join(fatal_lines) or "device uptime reset / adb unreachable",
                )

    # Timed out or logcat EOF'd. Figure out which.
    if rebooted_since(pre_uptime, serial):
        return TurnOutcome(
            turn_id, prompt, "reboot", time.time() - t0,
            "\n".join(fatal_lines) or "device uptime reset / adb unreachable",
        )

    cur_pid = device_pid(serial)
    if pre_pid and cur_pid != pre_pid:
        return TurnOutcome(
            turn_id, prompt, "app_crash", time.time() - t0,
            "\n".join(fatal_lines) or f"pid changed {pre_pid} -> {cur_pid}",
        )

    return TurnOutcome(turn_id, prompt, "timeout", time.time() - t0, "\n".join(fatal_lines))


def load_prompts(args) -> list[str]:
    if args.smoke_test:
        return list(SMOKE_TEST_PROMPTS)
    prompts: list[str] = list(args.prompt or [])
    if args.prompts_file:
        p = Path(args.prompts_file)
        if p.suffix == ".json":
            data = json.loads(p.read_text())
            prompts += [d["text"] if isinstance(d, dict) else d for d in data]
        else:
            prompts += [
                line.strip() for line in p.read_text().splitlines()
                if line.strip() and not line.strip().startswith("#")
            ]
    if not prompts:
        raise SystemExit("No prompts given -- use --prompt, --prompts-file, or --smoke-test.")
    return prompts


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--prompt", action="append", help="A prompt to send; repeatable.")
    ap.add_argument("--prompts-file", help="One prompt per line (# comments ok), or a .json list.")
    ap.add_argument("--smoke-test", action="store_true", help="Use the built-in battery of known trouble prompts.")
    ap.add_argument("--model", default=DEFAULT_MODEL)
    ap.add_argument("--chat-id", default="cli")
    ap.add_argument("--thinking", action=argparse.BooleanOptionalAction, default=True)
    ap.add_argument("--brevity", action=argparse.BooleanOptionalAction, default=False)
    ap.add_argument(
        "--reset-first", action=argparse.BooleanOptionalAction, default=True,
        help="Clear --chat-id's CLI-side history before the first prompt (default: on).",
    )
    ap.add_argument("--timeout", type=float, default=180.0, help="Per-turn timeout in seconds.")
    ap.add_argument(
        "--stop-on-failure", action="store_true",
        help="Stop at the first crash/reboot/timeout instead of relaunching and continuing.",
    )
    ap.add_argument("--serial", help="adb -s <serial>, needed if more than one device is attached.")
    ap.add_argument(
        "--launch", action=argparse.BooleanOptionalAction, default=True,
        help="am start the app before the first prompt (default: on).",
    )
    ap.add_argument("--out-dir", help="Where to write logcat.txt + report.json (default: a fresh temp dir).")
    args = ap.parse_args()

    prompts = load_prompts(args)

    if adb(["get-state"], serial=args.serial, timeout=10).returncode != 0:
        raise SystemExit("No adb device attached (or more than one attached -- pass --serial).")

    out_dir = Path(args.out_dir) if args.out_dir else Path(tempfile.mkdtemp(prefix="genie_cli_"))
    out_dir.mkdir(parents=True, exist_ok=True)
    logcat_path = out_dir / "logcat.txt"
    report_path = out_dir / "report.json"

    def log(msg: str) -> None:
        print(f"[genie_cli] {msg}", flush=True)

    log(f"run artifacts -> {out_dir}")
    adb_shell(f"mkdir -p {DEVICE_FILES_DIR}", args.serial, timeout=10)

    tail = LogTail(args.serial, logcat_path)
    tail.start()

    if args.launch:
        launch_app(args.serial)
        time.sleep(4)  # let the RN bridge come up before the first broadcast

    outcomes: list[TurnOutcome] = []
    for i, prompt in enumerate(prompts):
        turn_id = f"t{i}"
        reset = args.reset_first and i == 0
        log(f"[{i + 1}/{len(prompts)}] turnId={turn_id} reset={reset} :: {prompt[:80]!r}")

        if not tail.is_alive():
            tail.stop()
            tail = LogTail(args.serial, logcat_path)
            tail.start()

        outcome = run_one_turn(
            args.serial, turn_id, prompt, args.model, args.chat_id,
            args.thinking, args.brevity, reset, tail, args.timeout,
        )
        outcomes.append(outcome)
        first_detail = outcome.detail.splitlines()[0] if outcome.detail else ""
        log(f"  -> {outcome.status} ({outcome.elapsed_s:.1f}s)" + (f" :: {first_detail}" if first_detail else ""))

        if outcome.status in ("reboot", "app_crash"):
            if args.stop_on_failure:
                log("stopping (--stop-on-failure)")
                break
            if outcome.status == "reboot":
                if not wait_for_device_back(args.serial, log):
                    log("device did not come back in time -- stopping")
                    break
                tail.stop()
                tail = LogTail(args.serial, logcat_path)
                tail.start()
            launch_app(args.serial)
            time.sleep(4)
        elif outcome.status == "timeout" and args.stop_on_failure:
            log("stopping (--stop-on-failure)")
            break

    tail.stop()

    report = {
        "model": args.model,
        "chat_id": args.chat_id,
        "prompts_total": len(prompts),
        "outcomes": [vars(o) for o in outcomes],
    }
    report_path.write_text(json.dumps(report, indent=2))

    ok = sum(1 for o in outcomes if o.status == "ok")
    bad = [o for o in outcomes if o.status != "ok"]
    log(f"done: {ok}/{len(outcomes)} ok, {len(bad)} failed")
    for o in bad:
        first_detail = o.detail.splitlines()[0] if o.detail else ""
        log(f"  FAIL[{o.status}] {o.turn_id}: {o.prompt[:60]!r} -- {first_detail}")
    log(f"full logcat: {logcat_path}")
    log(f"report: {report_path}")
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
