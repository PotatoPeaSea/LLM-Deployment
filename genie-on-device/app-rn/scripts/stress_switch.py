#!/usr/bin/env python3
"""Does repeated model switching accumulate a DSP-memory leak, or is a
switch-time crash (HANDOFF-cli-tool-and-crash-rootcause.md) a pure
async-teardown race that never gets worse?

Those two hypotheses call for different fixes. A pure race means shrinking
the KV buffer (declaredContextLength) helps every switch equally, forever. A
real leak means shrinking the buffer only buys more switches before the same
crash -- a long session doing many switches would eventually hit it
regardless of buffer size. A single crash is consistent with either, because
it's one data point.

Also generic across WHICH TWO MODELS: originally built for QNN<->GenieX
(cross-runtime, different closed-source backends sharing the DSP), it takes
--model-a/--model-b so the same harness can test GenieX<->GenieX (same
runtime, different GGUF) in isolation -- e.g. to tell whether a crash is
specific to two different vendor backends racing for the DSP, or a broader
"any unload+reload races the DSP" issue that would show up even within one
backend.

This forces many switch cycles back-to-back IN THE SAME APP PROCESS --
deliberately never force-stopping between cycles, because that would tear
down and reallocate everything cleanly and hide any accumulation. Each cycle
is a genuinely fresh chat on each model (the highest-risk transition, not
just any switch).

Reuses genie_cli.py's turn-runner, logcat tail, and reboot/crash detection --
it's the same broadcast-and-watch mechanism, just driving a specific
sequence instead of a flat prompt list.

Usage:
  python3 stress_switch.py --cycles 20
  python3 stress_switch.py --cycles 40 --timeout 120
  python3 stress_switch.py --model-a qwen3_5_2b --model-b gemma4_e2b --cycles 15
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

# Short and known-safe (see SMOKE_TEST_PROMPTS) -- the point here is the
# SWITCH, not any particular prompt content.
PROMPT = "Write two sentences about cats"


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--model-a", default="qwen3_4b", help="First model in each cycle.")
    ap.add_argument("--model-b", default="qwen3_5_2b", help="Second model in each cycle.")
    ap.add_argument("--cycles", type=int, default=20, help="model-a->model-b switch cycles to run.")
    ap.add_argument("--timeout", type=float, default=90.0, help="Per-turn timeout in seconds.")
    ap.add_argument("--serial", help="adb -s <serial>, needed if more than one device is attached.")
    ap.add_argument("--out-dir", help="Where to write logcat.txt + report.json (default: a fresh temp dir).")
    args = ap.parse_args()
    model_a, model_b = args.model_a, args.model_b

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
    log(f"{args.cycles} cycles, each: fresh {model_a} chat, then fresh {model_b} chat")

    tail = LogTail(args.serial, logcat_path)
    tail.start()
    launch_app(args.serial)
    time.sleep(4)

    # Each cycle re-touches BOTH switch directions -- a leak, if real, has no
    # reason to be one-directional.
    cycles: list[dict] = []
    # A model switch is now EXPECTED to restart the whole process
    # (GenieModule.switchToOrRestart -- in-process switching was found
    # unreliable in every combination; see HANDOFF-cli-tool-and-crash-
    # rootcause.md). run_one_turn reports that as "app_crash" (a pid change
    # is a pid change) even though it is intentional, so app_crash/reboot on
    # its own no longer means the switch FAILED -- it means a restart
    # happened, which must then be resumed and checked for real: does the
    # SAME logical turn actually complete once the fresh process comes up?
    # RESUME_ATTEMPTS bounds retries against a genuinely broken switch
    # (e.g. a real, non-restart crash loop) rather than looping forever.
    RESUME_ATTEMPTS = 3
    reboots = 0
    restarts = 0
    for i in range(args.cycles):
        cycle_t0 = time.time()
        entry = {"cycle": i, "turns": []}

        for model in (model_a, model_b):
            chat_id = f"stress_{model}_{i}"  # unique per cycle -- always a FRESH chat
            outcome = None
            for attempt in range(RESUME_ATTEMPTS):
                turn_id = f"c{i}_{model}_a{attempt}"
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
                restarted = "(restart)" in detail
                log(f"  cycle {i} {model} attempt {attempt}: {outcome.status} ({outcome.elapsed_s:.1f}s)" + (f" :: {detail}" if detail else ""))

                if outcome.status == "reboot":
                    reboots += 1
                    log(f"  *** genuine REBOOT (not the intentional restart) at cycle {i}, {model} (reboot #{reboots}) ***")
                    if not wait_for_device_back(args.serial, log):
                        log("device did not come back in time -- stopping")
                        entry["cycle_elapsed_s"] = time.time() - cycle_t0
                        cycles.append(entry)
                        _write_report(report_path, cycles, reboots, restarts, args.cycles)
                        log(f"stopped early after {len(cycles)}/{args.cycles} cycles, {reboots} reboot(s), {restarts} restart(s)")
                        log(f"full logcat: {logcat_path}")
                        log(f"report: {report_path}")
                        sys.exit(1)
                    tail.stop()
                    tail = LogTail(args.serial, logcat_path)
                    tail.start()
                    launch_app(args.serial)
                    time.sleep(4)
                    continue  # resume the same logical turn
                elif outcome.status == "app_crash":
                    if restarted:
                        restarts += 1
                    else:
                        log(f"  *** unexpected app_crash (not a switch restart) at cycle {i}, {model} ***")
                    launch_app(args.serial)
                    time.sleep(4)
                    continue  # resume the same logical turn
                else:
                    break  # ok, error, or timeout -- terminal for this model this cycle

            # The resolved outcome for this switch: what the logical turn
            # ended up as after however many restart-resumes it took.
            entry.setdefault("switches", []).append({
                "model": model, "final_status": outcome.status, "attempts": attempt + 1,
            })

        entry["cycle_elapsed_s"] = time.time() - cycle_t0
        cycles.append(entry)

    _write_report(report_path, cycles, reboots, restarts, args.cycles)
    tail.stop()

    switches = [s for c in cycles for s in c.get("switches", [])]
    resolved_ok = sum(1 for s in switches if s["final_status"] == "ok")
    genuinely_failed = [s for s in switches if s["final_status"] != "ok"]
    log(
        f"done: {len(cycles)}/{args.cycles} cycles, {resolved_ok}/{len(switches)} switches "
        f"resolved ok, {restarts} restart(s) along the way, {reboots} genuine reboot(s)",
    )
    if genuinely_failed:
        log(f"  {len(genuinely_failed)} switch(es) never resolved ok within {RESUME_ATTEMPTS} resume attempts:")
        for s in genuinely_failed:
            log(f"    cycle switch to {s['model']}: stuck at {s['final_status']} after {s['attempts']} attempt(s)")
    log("if failures clustered in later cycles rather than spread evenly, that's the leak signature --")
    log("check report.json's per-cycle elapsed_s and status for a trend, not just the final count.")
    log(f"full logcat: {logcat_path}")
    log(f"report: {report_path}")
    sys.exit(1 if genuinely_failed or reboots else 0)


def _write_report(
    report_path: Path, cycles: list[dict], reboots: int, restarts: int, planned_cycles: int,
) -> None:
    report_path.write_text(json.dumps({
        "planned_cycles": planned_cycles,
        "completed_cycles": len(cycles),
        "reboots": reboots,
        "restarts": restarts,
        "cycles": cycles,
    }, indent=2))


if __name__ == "__main__":
    main()
