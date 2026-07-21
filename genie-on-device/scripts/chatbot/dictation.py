"""Speech input, reusing the already-working on-device Whisper-Base pipeline.

The target board has no mic, so audio is captured on THIS host and only the
transcript text crosses to the device -- identical to 06_voice_assistant.py §7,
which this module is a refactor of. Transcription itself still runs on the
device's NPU (encoder + autoregressive decoder via qnn-net-run).
"""
from __future__ import annotations

import signal
import subprocess
from pathlib import Path

WHISPER_DEVICE_DIR_BY_OS = {
    "android": "/data/local/tmp/whisper_poc",
    "linux": "/dev/shm/whisper_poc",
}


def record_to_wav(path: Path) -> None:
    """Press-Enter-to-start, press-Enter-to-stop capture via arecord."""
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
    """Normalize any ffmpeg-readable audio into the 16kHz mono s16le Whisper wants."""
    result = subprocess.run(
        ["ffmpeg", "-y", "-i", src_path, "-ac", "1", "-ar", "16000",
         "-acodec", "pcm_s16le", str(dst_path)],
        capture_output=True, text=True,
    )
    if result.returncode != 0:
        raise RuntimeError(f"ffmpeg failed to convert {src_path}:\n{result.stderr}")


def transcribe(wav_path: Path, script_dir: Path, image: str, target_os: str) -> str:
    """Run Whisper-Base on the device's NPU and return the transcript.

    Shelled through bash so it reuses 00_env.sh's docker_run_networked rather
    than re-deriving the container's mounts here (they'd drift). The container
    needs both qai_hub_models (image-only) and the host's adb server, which
    --network host provides.
    """
    device_dir = WHISPER_DEVICE_DIR_BY_OS[target_os]
    docker_cmd = (
        f"source {script_dir}/00_env.sh && WHISPER_DEVICE_DIR={device_dir} "
        f"docker_run_networked {image} "
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
