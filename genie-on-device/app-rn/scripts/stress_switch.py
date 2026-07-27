#!/usr/bin/env python3
"""Does repeated QNN<->GenieX model switching accumulate a DSP-memory leak,
or is the switch-time reboot (HANDOFF-cli-tool-and-crash-rootcause.md, Part 2)
a pure async-teardown race that never gets worse?

Those two hypotheses call for different fixes. A pure race means shrinking
the GenieX KV buffer (declaredContextLength) helps every switch equally,
forever. A real leak means shrinking the buffer only buys more switches
before the same crash -- a long session doing many switches would eventually
hit it regardless of buffer size. The single reboot caught so far is
consistent with either, because it was one data point.

This forces many switch cycles back-to-back IN THE SAME APP PROCESS --
deliberately never force-stopping between cycles, because that would tear
down and reallocate everything cleanly and hide any accumulation. Each cycle
is a genuinely fresh chat on each model (the exact precondition that
triggered the original reboot), so every cycle repeats the highest-risk
transition, not just any switch.

Reuses genie_cli.py's turn-runner, logcat tail, and reboot/crash detection --
it's the same broadcast-and-watch mechanism, just driving a specific
sequence instead of a flat prompt list.

Usage:
  python3 stress_switch.py --cycles 20
  python3 stress_switch.py --cycles 40 --timeout 120
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from genie_cli import (  # noqa: E402
    LogTail,
    launch_app,
    run_one_turn,
    wait_for_device_back,
    adb,
)

QNN_MODEL = "qwen3_4b"
GENIEX_MODEL = "qwen3_5_2b"
# Short and known-safe on both models (see SMOKE_TEST_PROMPTS) -- the point
# here is the SWITCH, not any particular prompt content.
PROMPT = "Write two sentences about cats"


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--cycles", type=int, default=20, help="QNN->GenieX switch cycles to run.")
    ap.add_argument("--timeout", type=float, default=90.0, help="Per-turn timeout in seconds.")
    ap.add_argument("--serial", help="adb -s <serial>, needed if more than one device is attached.")
    ap.add_argument("--out-dir", help="Where to write logcat.txt + report.json (default: a fresh temp dir).")
    args = ap.parse_args()

    if adb(["get-state"], serial=args.serial, timeout=10).returncode != 0:
        raise SystemExit("No adb device attached (or more than one attached -- pass --serial).")

    import tempfile
    out_dir = Path(args.out_dir) if args.out_dir else Path(tempfile.mkdtemp(prefix="stress_switch_"))
    out_dir.mkdir(parents=True, exist_ok=True)
    logcat_path = out_dir / "logcat.txt"
    report_path = out_dir / "report.json"

    def log(msg: str) -> None:
        print(f"[stress_switch] {msg}", flush=True)

    log(f"run artifacts -> {out_dir}")
    log(f"{args.cycles} cycles, each: fresh {QNN_MODEL} chat, then fresh {GENIEX_MODEL} chat")

    tail = LogTail(args.serial, logcat_path)
    tail.start()
    launch_app(args.serial)
    time.sleep(4)

    # Each cycle re-touches BOTH switch directions (QNN->GenieX is the one that
    # rebooted; GenieX->QNN is included too since it shares switchTo/settle
    # and a leak, if real, has no reason to be one-directional).
    cycles: list[dict] = []
    reboots = 0
    for i in range(args.cycles):
        cycle_t0 = time.time()
        entry = {"cycle": i, "turns": []}

        for model in (QNN_MODEL, GENIEX_MODEL):
            turn_id = f"c{i}_{model}"
            chat_id = f"stress_{model}_{i}"  # unique per cycle -- always a FRESH chat
            if not tail.is_alive():
                tail.stop()
                tail = LogTail(args.serial, logcat_path)
                tail.start()

            outcome = run_one_turn(
                args.serial, turn_id, PROMPT, model, chat_id,
                thinking=False, brevity=True, reset=True,
                tail=tail, timeout_s=args.timeout,
            )
            entry["turns"].append(vars(outcome))
            detail = outcome.detail.splitlines()[0] if outcome.detail else ""
            log(f"  cycle {i} {model}: {outcome.status} ({outcome.elapsed_s:.1f}s)" + (f" :: {detail}" if detail else ""))

            if outcome.status == "reboot":
                reboots += 1
                log(f"  *** REBOOT at cycle {i}, switching to {model} (reboot #{reboots}) ***")
                if not wait_for_device_back(args.serial, log):
                    log("device did not come back in time -- stopping")
                    entry["cycle_elapsed_s"] = time.time() - cycle_t0
                    cycles.append(entry)
                    _write_report(report_path, cycles, reboots, args.cycles)
                    log(f"stopped early after {len(cycles)}/{args.cycles} cycles, {reboots} reboot(s)")
                    log(f"full logcat: {logcat_path}")
                    log(f"report: {report_path}")
                    sys.exit(1)
                tail.stop()
                tail = LogTail(args.serial, logcat_path)
                tail.start()
                launch_app(args.serial)
                time.sleep(4)
            elif outcome.status == "app_crash":
                launch_app(args.serial)
                time.sleep(4)

        entry["cycle_elapsed_s"] = time.time() - cycle_t0
        cycles.append(entry)

    _write_report(report_path, cycles, reboots, args.cycles)
    tail.stop()

    ok = sum(1 for c in cycles for t in c["turns"] if t["status"] == "ok")
    total_turns = sum(len(c["turns"]) for c in cycles)
    log(f"done: {len(cycles)}/{args.cycles} cycles, {ok}/{total_turns} turns ok, {reboots} reboot(s)")
    log("if failures clustered in later cycles rather than spread evenly, that's the leak signature --")
    log("check report.json's per-cycle elapsed_s and status for a trend, not just the final count.")
    log(f"full logcat: {logcat_path}")
    log(f"report: {report_path}")
    sys.exit(1 if reboots else 0)


def _write_report(report_path: Path, cycles: list[dict], reboots: int, planned_cycles: int) -> None:
    report_path.write_text(json.dumps({
        "planned_cycles": planned_cycles,
        "completed_cycles": len(cycles),
        "reboots": reboots,
        "cycles": cycles,
    }, indent=2))


if __name__ == "__main__":
    main()
