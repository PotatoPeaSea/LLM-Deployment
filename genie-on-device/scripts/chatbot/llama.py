"""On-device Llama-3.2-1B-Instruct runtime for the chatbot.

Difference from 05_deploy_and_run.sh, and the reason this exists: that script
re-pushes the whole ~1.7GB bundle on every single invocation (~7s/turn, see
docs/HANDOFF-whisper-llama-voice-assistant.md §9 item 4). A chatbot runs many
turns, so this class splits the work in two:

  ensure_deployed()  -- stage + push the bundle ONCE, guarded by a fingerprint
                        marker file on the device, so restarting the chatbot
                        against an already-current device skips the push too.
  generate()         -- per turn, push only prompt.txt (a few KB) and re-run
                        genie-t2t-run over adb.

genie-t2t-run is a one-shot binary with no session state, so multi-turn
conversation works by rendering the entire chat transcript into the prompt each
turn (see prompt.py). That means TTFT grows with history length -- the context
budgeting in agent.py is what keeps it bounded.
"""
from __future__ import annotations

import hashlib
import re
import shutil
import tempfile
from pathlib import Path

from . import device

ANSWER_RE = re.compile(r"\[BEGIN\]:\s*(.*?)\[END\]", re.DOTALL)


class LlamaRuntime:
    def __init__(
        self,
        model_id: str,
        output_dir: Path,
        stage_root: Path,
        qairt_home: str,
        hex_arch: str = "v73",
        target_os: str = "android",
    ) -> None:
        self.model_id = model_id
        self.qairt_home = Path(qairt_home)
        self.hex_arch = hex_arch
        self.target_os = target_os
        profile = device.TARGET_OS_PROFILES[target_os]
        self.runtime_dir_name = profile["runtime_dir"]
        self.device_dir = f"{profile['base_dir']}/genie_{model_id}"
        self.stage_dir = stage_root / model_id
        self.bundle_dir = self._find_bundle(output_dir / model_id)
        self._deployed = False

    @staticmethod
    def _find_bundle(parent: Path) -> Path:
        """The export writes a nested, device-named subdir under the model dir."""
        if (parent / "genie_config.json").is_file():
            return parent
        for child in sorted(parent.glob("*geniex*")):
            if (child / "genie_config.json").is_file():
                return child
        raise FileNotFoundError(
            f"No genie_config.json under {parent} -- export the model first "
            f"(see docs/HANDOFF-whisper-llama-voice-assistant.md §1)."
        )

    # --- deployment ------------------------------------------------------

    def _stage(self) -> None:
        """Assemble bundle + QAIRT runtime libs + binary + DSP skels, flat."""
        runtime_lib = self.qairt_home / "lib" / self.runtime_dir_name
        runtime_bin = self.qairt_home / "bin" / self.runtime_dir_name
        dsp_lib = self.qairt_home / "lib" / f"hexagon-{self.hex_arch}" / "unsigned"
        for path in (runtime_lib, runtime_bin / "genie-t2t-run", dsp_lib):
            if not path.exists():
                raise FileNotFoundError(f"Missing QAIRT component: {path}")

        if self.stage_dir.exists():
            shutil.rmtree(self.stage_dir)
        (self.stage_dir / "dsp").mkdir(parents=True)
        shutil.copytree(self.bundle_dir, self.stage_dir, dirs_exist_ok=True)
        for so in runtime_lib.glob("*.so"):
            shutil.copy2(so, self.stage_dir)
        shutil.copy2(runtime_bin / "genie-t2t-run", self.stage_dir)
        for so in dsp_lib.glob("*.so"):
            shutil.copy2(so, self.stage_dir / "dsp")

    def _fingerprint(self) -> str:
        """Identify this exact staged bundle so we can skip redundant pushes.

        Hashes relative paths + sizes + mtimes rather than file contents -- the
        bundle is ~1.7GB and content-hashing it every startup would cost more
        than it saves. Any re-export or QAIRT swap changes sizes/mtimes.
        """
        digest = hashlib.sha256()
        for path in sorted(self.stage_dir.rglob("*")):
            if path.is_file():
                stat = path.stat()
                rel = path.relative_to(self.stage_dir)
                digest.update(f"{rel}:{stat.st_size}:{int(stat.st_mtime)}\n".encode())
        return digest.hexdigest()[:32]

    def ensure_deployed(self, log=print) -> None:
        """Push the bundle only if the device isn't already holding this exact one."""
        if self._deployed:
            return
        self._stage()
        fingerprint = self._fingerprint()
        marker = f"{self.device_dir}/.chatbot_fingerprint"

        current = device.adb_shell(
            f"cat {marker} 2>/dev/null || true", timeout=30, check=False
        ).strip()
        if current == fingerprint:
            log(f"[llama] device already has this bundle ({fingerprint[:8]}), skipping push")
            self._deployed = True
            return

        size = sum(f.stat().st_size for f in self.stage_dir.rglob("*") if f.is_file())
        log(f"[llama] pushing bundle to {self.device_dir} ({size / 1e9:.2f} GB, one time)...")
        device.adb_shell(f"rm -rf {self.device_dir}; mkdir -p {self.device_dir}", timeout=120)
        device.adb("push", f"{self.stage_dir}/.", f"{self.device_dir}/", timeout=1800)
        device.adb_shell(f"chmod 755 {self.device_dir}/genie-t2t-run", timeout=30)
        # Write the marker only after everything else landed, so an interrupted
        # push is never mistaken for a complete one on the next run.
        device.adb_shell(f"echo {device.quote(fingerprint)} > {marker}", timeout=30)
        log("[llama] bundle deployed")
        self._deployed = True

    # --- inference -------------------------------------------------------

    def generate(self, prompt: str, timeout: int = 300) -> str:
        """Run one fully-rendered prompt through genie-t2t-run on the NPU."""
        if not self._deployed:
            self.ensure_deployed()

        # --prompt_file (rather than -p) avoids all shell quoting/newline pain;
        # the chat template is newline-sensitive.
        with tempfile.TemporaryDirectory() as tmp:
            local_prompt = Path(tmp) / "prompt.txt"
            local_prompt.write_text(prompt, encoding="utf-8")
            device.adb("push", str(local_prompt), f"{self.device_dir}/prompt.txt", timeout=60)

        out = device.adb_shell(
            f"cd {self.device_dir} && "
            f"export LD_LIBRARY_PATH={self.device_dir}:$LD_LIBRARY_PATH && "
            f"export ADSP_LIBRARY_PATH={self.device_dir}/dsp && "
            f"./genie-t2t-run -c genie_config.json --prompt_file prompt.txt",
            timeout=timeout,
        )
        match = ANSWER_RE.search(out)
        if not match:
            raise RuntimeError(f"No [BEGIN]/[END] in genie-t2t-run output:\n{out}")
        return match.group(1).strip()
