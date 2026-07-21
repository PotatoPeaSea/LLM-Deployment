#!/usr/bin/env python3
"""Press-Enter-to-talk voice assistant.

The target device (QCS8550) has no mic, so audio is captured HERE, on this
host, via arecord -- only the transcript text (not audio) ever crosses to the
target. Pipeline per turn:

  this host's mic --arecord--> 16kHz mono WAV
      --> Whisper-Base encoder/decoder on the QCS8550 NPU (§4)   --> transcript
      --> Llama-3.2-1B-Instruct on the QCS8550 NPU (§1, genie-t2t-run) --> answer

Python (not bash, unlike 00-05) because it needs an interactive input()
start/stop loop and to parse two subprocesses' stdout per turn -- see
docs/HANDOFF-whisper-llama-voice-assistant.md §7 item 1.

Usage: python3 06_voice_assistant.py [--qairt-home PATH] [--hex-arch v73]
       python3 06_voice_assistant.py --audio-file recording.wav   # no mic needed
"""
from __future__ import annotations

import argparse
import re
import signal
import subprocess
import sys
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parent
SCRATCH_WAV = REPO_ROOT / "workspace/output/whisper_base/poc/mic_capture.wav"

LLAMA_MODEL_ID = "llama_v3_2_1b_instruct_ctx2048"  # verified working on-device, see handoff §1
DEFAULT_QAIRT_HOME = "/mnt/ssd/bryan/AI_SMART/qairt/2.47.0.260601"

# Same QCS8550 silicon, two boards seen so far: Android, and Qualcomm's
# Ubuntu-based "qti-distro" Linux BSP (confirmed on a QCS8550 HDK) -- these
# need different QAIRT runtime binaries (bionic vs glibc) AND different
# on-device staging paths, since the Linux BSP mounts /data noexec (see
# 05_deploy_and_run.sh and qnn_device.py for the details).
WHISPER_DEVICE_DIR_BY_OS = {
    "android": "/data/local/tmp/whisper_poc",
    "linux": "/dev/shm/whisper_poc",
}


def record_to_wav(path: Path) -> None:
    input("Press ENTER to start recording...")
    proc = subprocess.Popen(
        ["arecord", "-D", "default", "-f", "S16_LE", "-r", "16000", "-c", "1", str(path)],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    input("Recording... press ENTER to stop.")
    proc.send_signal(signal.SIGINT)  # arecord finalizes the WAV header on SIGINT
    proc.wait()


def convert_to_wav(src_path: str, dst_path: Path) -> None:
    """Normalize an arbitrary input audio file (any format ffmpeg can read --
    wav/mp3/m4a/etc, any sample rate/channel count) into 16kHz mono s16le,
    matching what arecord produces directly."""
    result = subprocess.run(
        ["ffmpeg", "-y", "-i", src_path, "-ac", "1", "-ar", "16000", "-acodec", "pcm_s16le", str(dst_path)],
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        raise RuntimeError(f"ffmpeg failed to convert {src_path}:\n{result.stderr}")


def transcribe(wav_path: Path, image: str, target_os: str) -> str:
    # Whisper's adapters need qai_hub_models (only installed in the image)
    # AND adb reaching the on-device NPU -- docker_run_networked (00_env.sh)
    # shares the host's network namespace so the container's adb client can
    # reach the host's already-authenticated adb server. Shelled through
    # bash to reuse that function instead of re-deriving its mounts here.
    # WHISPER_DEVICE_DIR tells qnn_device.py which board's pre-staged
    # runtime+model files to use (see workspace/deploy/whisper_poc* staging).
    device_dir = WHISPER_DEVICE_DIR_BY_OS[target_os]
    docker_cmd = (
        f"source {SCRIPT_DIR}/00_env.sh && WHISPER_DEVICE_DIR={device_dir} docker_run_networked {image} "
        f"python3 /workspace/output/whisper_base/poc/run_whisper_decode.py "
        f"/workspace/output/whisper_base/poc/{wav_path.name}"
    )
    result = subprocess.run(["bash", "-c", docker_cmd], capture_output=True, text=True)
    if result.returncode != 0:
        raise RuntimeError(f"Whisper transcription failed:\n{result.stdout}\n{result.stderr}")
    for line in result.stdout.splitlines():
        if line.startswith("TRANSCRIPT: "):
            return line[len("TRANSCRIPT: "):].strip()
    raise RuntimeError(f"No TRANSCRIPT line in Whisper output:\n{result.stdout}")


def ask_llama(prompt: str, qairt_home: str, hex_arch: str, target_os: str) -> str:
    # Re-pushes the ~1.7GB Llama bundle every call (~7s on Android, longer on
    # the Linux board's /dev/shm push) -- fine for now, see
    # docs/HANDOFF-whisper-llama-voice-assistant.md §8 item 4 if this needs
    # to get faster later (skip the bundle push, only refresh prompt.txt,
    # when the on-device copy is already current).
    cmd = [str(SCRIPT_DIR / "05_deploy_and_run.sh"), LLAMA_MODEL_ID, qairt_home, hex_arch, prompt, target_os]
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        raise RuntimeError(f"Llama run failed:\n{result.stdout}\n{result.stderr}")
    match = re.search(r"\[BEGIN\]:\s*(.*?)\[END\]", result.stdout, re.DOTALL)
    if not match:
        raise RuntimeError(f"No [BEGIN]/[END] answer found:\n{result.stdout}")
    return match.group(1).strip()


def run_turn(wav_path: Path, image: str, qairt_home: str, hex_arch: str, target_os: str) -> None:
    print("Transcribing (on-device NPU)...")
    try:
        transcript = transcribe(wav_path, image, target_os)
    except RuntimeError as exc:
        print(f"  transcription error: {exc}\n")
        return
    if not transcript:
        print("  (heard nothing, try again)\n")
        return
    print(f"  you said: {transcript!r}")

    print("Asking Llama-3.2-1B...")
    try:
        answer = ask_llama(transcript, qairt_home, hex_arch, target_os)
    except RuntimeError as exc:
        print(f"  llama error: {exc}\n")
        return
    print(f"  answer: {answer}\n")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--qairt-home", default=DEFAULT_QAIRT_HOME)
    parser.add_argument("--hex-arch", default="v73")
    parser.add_argument("--image", default="genie-llm-toolchain:latest")
    parser.add_argument(
        "--target-os",
        choices=sorted(WHISPER_DEVICE_DIR_BY_OS),
        default="android",
        help="OS running on the connected QCS8550 board -- 'android' or "
             "Qualcomm's Ubuntu-based Linux BSP ('linux'). Same silicon, "
             "different runtime binaries and staging paths (see "
             "05_deploy_and_run.sh's comments).",
    )
    parser.add_argument(
        "--audio-file",
        help="Skip mic recording and transcribe this audio file instead "
             "(any format ffmpeg can read: wav/mp3/m4a/etc, any sample "
             "rate/channel count). Runs a single turn and exits -- for "
             "when this host's mic isn't available.",
    )
    args = parser.parse_args()

    SCRATCH_WAV.parent.mkdir(parents=True, exist_ok=True)

    if args.audio_file:
        if not Path(args.audio_file).is_file():
            print(f"No such file: {args.audio_file}", file=sys.stderr)
            sys.exit(1)
        print(f"Using provided audio file: {args.audio_file}")
        convert_to_wav(args.audio_file, SCRATCH_WAV)
        run_turn(SCRATCH_WAV, args.image, args.qairt_home, args.hex_arch, args.target_os)
        return

    print("Voice assistant ready (Whisper-Base + Llama-3.2-1B on QCS8550 NPU). Ctrl+C to quit.\n")
    while True:
        try:
            record_to_wav(SCRATCH_WAV)
        except (KeyboardInterrupt, EOFError):
            print("\nBye.")
            return
        run_turn(SCRATCH_WAV, args.image, args.qairt_home, args.hex_arch, args.target_os)


if __name__ == "__main__":
    main()
