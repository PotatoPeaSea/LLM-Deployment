"""adb plumbing shared by the chatbot's runtime and its device-facing tools.

Everything here shells out to the host's `adb`, matching the rest of this repo
(05_deploy_and_run.sh, qnn_device.py) -- there is no on-device agent process.

The same QCS8550 silicon has been seen running two different OSes (Android and
Qualcomm's Ubuntu "qti-distro" BSP), which need different staging paths because
the Linux BSP mounts /data noexec. See 05_deploy_and_run.sh's comments and
docs/HANDOFF-whisper-llama-voice-assistant.md §8.
"""
from __future__ import annotations

import shlex
import subprocess

# Per-OS QAIRT runtime build + a device dir we're allowed to exec binaries from.
TARGET_OS_PROFILES = {
    "android": {"runtime_dir": "aarch64-android", "base_dir": "/data/local/tmp"},
    "linux": {"runtime_dir": "aarch64-oe-linux-gcc11.2", "base_dir": "/dev/shm"},
}


class AdbError(RuntimeError):
    pass


def adb(*args: str, timeout: int = 120, check: bool = True) -> str:
    """Run an adb command, returning stdout."""
    proc = subprocess.run(
        ["adb", *args], capture_output=True, text=True, timeout=timeout
    )
    if check and proc.returncode != 0:
        raise AdbError(
            f"adb {' '.join(args)} failed (rc={proc.returncode}):\n"
            f"{proc.stdout}\n{proc.stderr}"
        )
    return proc.stdout


def adb_shell(command: str, timeout: int = 120, check: bool = True) -> str:
    """Run a shell command on the device.

    `command` is passed as a single argument so the DEVICE's shell parses it --
    that's what lets callers use pipes/redirection. Any value interpolated into
    it from model output or user data must go through shlex.quote first.
    """
    return adb("shell", command, timeout=timeout, check=check)


def quote(value: str) -> str:
    """Quote a value for safe interpolation into an adb_shell command string.

    Tool arguments come from LLM output, so they are untrusted input to the
    device shell -- always wrap them in this rather than f-stringing them raw.
    """
    return shlex.quote(value)


def device_available() -> bool:
    try:
        out = adb("devices", timeout=15)
    except (AdbError, FileNotFoundError, subprocess.TimeoutExpired):
        return False
    # First line is the "List of devices attached" header.
    return any(line.split("\t")[-1].strip() == "device" for line in out.splitlines()[1:])


def detect_target_os() -> str:
    """Return 'android' or 'linux' for the attached board.

    Android has no /etc/os-release; the Qualcomm Linux BSP reports Ubuntu.
    """
    out = adb_shell("cat /etc/os-release 2>/dev/null || true", timeout=20, check=False)
    return "linux" if "ubuntu" in out.lower() or "ID=" in out else "android"
