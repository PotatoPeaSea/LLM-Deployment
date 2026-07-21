#!/usr/bin/env python3
"""Chatbot + voice assistant on a Qualcomm NPU: Llama-3.2-1B-Instruct + Whisper.

All inference runs on the attached device's Hexagon NPU. This process is the
orchestrator: it holds the conversation and reaches the device over adb. The
board has no mic, so dictation is captured on this host and only the transcript
text crosses to the device.

  you (typed, or dictated with /talk)
     -> Whisper-Base on the NPU            (dictation only)
     -> Llama-3.2-1B-Instruct on the NPU   (genie-t2t-run)
     -> reply

Unlike 06_voice_assistant.py this keeps multi-turn conversation history, and
pushes the ~1.8GB model bundle ONCE at startup (skipped entirely if the device
already holds it) instead of once per turn -- ~2s/turn instead of ~7s+.

Usage:
  python3 07_chatbot.py                            # interactive chat
  python3 07_chatbot.py --talk                     # start in dictation mode
  python3 07_chatbot.py --ask "hello"              # one shot, then exit
  python3 07_chatbot.py --audio-file clip.wav      # dictate from a file, one shot

In-chat commands: /talk (dictate one turn), /reset, /quit

Tool calling (contacts, web, device actions) is implemented but OFF by default
-- see --tools and docs/CHATBOT.md for why it isn't trustworthy on a 1B/4-bit
model yet.
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))

from chatbot import device, dictation, tools  # noqa: E402
from chatbot.agent import Agent  # noqa: E402
from chatbot.llama import LlamaRuntime  # noqa: E402

REPO_ROOT = SCRIPT_DIR.parent
OUTPUT_DIR = REPO_ROOT / "workspace/output"
STAGE_ROOT = REPO_ROOT / "workspace/deploy"
SCRATCH_WAV = OUTPUT_DIR / "whisper_base/poc/mic_capture.wav"

DEFAULT_MODEL_ID = "llama_v3_2_1b_instruct_ctx4096"
DEFAULT_QAIRT_HOME = "/mnt/ssd/bryan/AI_SMART/qairt/2.47.0.260601"


def confirm_at_terminal(question: str) -> bool:
    """Gate for tools with real-world side effects (calls, SMS, launching apps).

    Defaults to NO on anything that isn't an explicit yes, including EOF -- a
    1B model's tool call is a suggestion, not an authorization.
    """
    try:
        answer = input(f"  [confirm] {question} [y/N] ").strip().lower()
    except EOFError:
        return False
    return answer in ("y", "yes")


def build_agent(args) -> Agent:
    runtime = LlamaRuntime(
        model_id=args.model_id,
        output_dir=OUTPUT_DIR,
        stage_root=STAGE_ROOT,
        qairt_home=args.qairt_home,
        hex_arch=args.hex_arch,
        target_os=args.target_os,
    )
    runtime.ensure_deployed()
    return Agent(
        runtime,
        context_length=args.context_length,
        confirm=confirm_at_terminal,
        enable_tools=args.tools,
    )


def dictate_turn(args) -> str | None:
    SCRATCH_WAV.parent.mkdir(parents=True, exist_ok=True)
    dictation.record_to_wav(SCRATCH_WAV)
    print("  transcribing on-device...")
    try:
        text = dictation.transcribe(SCRATCH_WAV, SCRIPT_DIR, args.image, args.target_os)
    except RuntimeError as exc:
        print(f"  transcription error: {exc}")
        return None
    if not text:
        print("  (heard nothing)")
        return None
    print(f"  you said: {text!r}")
    return text


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--model-id", default=DEFAULT_MODEL_ID)
    parser.add_argument("--qairt-home", default=DEFAULT_QAIRT_HOME)
    parser.add_argument("--hex-arch", default="v73")
    parser.add_argument("--image", default="genie-llm-toolchain:latest")
    parser.add_argument("--target-os", choices=sorted(device.TARGET_OS_PROFILES),
                        default=None, help="default: auto-detect the attached board")
    parser.add_argument("--context-length", type=int, default=4096,
                        help="must match the context length the bundle was exported with")
    parser.add_argument("--ask", help="run a single turn with this text, then exit")
    parser.add_argument("--talk", action="store_true", help="dictate the first turn")
    parser.add_argument("--audio-file",
                        help="transcribe this audio file as one turn, then exit "
                             "(any format ffmpeg reads) -- no mic needed")
    parser.add_argument("--tools", action="store_true",
                        help="enable tool calling (experimental; unreliable on "
                             "this 1B/4-bit model, see docs/CHATBOT.md)")
    args = parser.parse_args()

    if not device.device_available():
        print("No adb device attached. Connect the target board and retry.", file=sys.stderr)
        sys.exit(1)
    if args.target_os is None:
        args.target_os = device.detect_target_os()
        print(f"[device] detected target OS: {args.target_os}")

    agent = build_agent(args)

    if args.ask:
        print(agent.ask(args.ask))
        return

    if args.audio_file:
        if not Path(args.audio_file).is_file():
            print(f"No such file: {args.audio_file}", file=sys.stderr)
            sys.exit(1)
        SCRATCH_WAV.parent.mkdir(parents=True, exist_ok=True)
        dictation.convert_to_wav(args.audio_file, SCRATCH_WAV)
        print("  transcribing on-device...")
        text = dictation.transcribe(SCRATCH_WAV, SCRIPT_DIR, args.image, args.target_os)
        print(f"  you said: {text!r}")
        print(f"bot> {agent.ask(text)}")
        return

    print("\nChatbot ready (Llama-3.2-1B + Whisper on the NPU).")
    print(f"Tools: {'on (experimental)' if args.tools else 'off'}")
    print("Commands: /talk  /reset  /quit\n")

    pending_dictation = args.talk
    while True:
        try:
            if pending_dictation:
                pending_dictation = False
                user_text = dictate_turn(args)
                if not user_text:
                    continue
            else:
                user_text = input("you> ").strip()
        except (EOFError, KeyboardInterrupt):
            print("\nBye.")
            return

        if not user_text:
            continue
        if user_text in ("/quit", "/exit"):
            print("Bye.")
            return
        if user_text == "/talk":
            pending_dictation = True
            continue
        if user_text == "/reset":
            agent.history.clear()
            print("  (conversation cleared)")
            continue
        if user_text == "/tools":
            print(tools.catalog())
            continue

        try:
            print(f"bot> {agent.ask(user_text)}\n")
        except Exception as exc:
            print(f"  error: {exc}\n")


if __name__ == "__main__":
    main()
