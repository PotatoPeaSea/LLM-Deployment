# USAGE — running the on-device chatbot & voice assistant

Practical operator guide for `scripts/07_chatbot.py`. For *why* things are built
the way they are (and what was measured), see [CHATBOT.md](CHATBOT.md); for how
the model bundles were produced, see
[HANDOFF-whisper-llama-voice-assistant.md](HANDOFF-whisper-llama-voice-assistant.md).

Everything here runs from the `genie-on-device/` directory on the **host**. The
host orchestrates; both models run on the device's NPU.

---

## 1. Prerequisites

| Requirement | Check | If missing |
|---|---|---|
| Device attached over adb | `adb devices` shows a line ending in `device` | replug / `adb kill-server && adb devices` |
| QAIRT SDK on the host | `ls /mnt/ssd/bryan/AI_SMART/qairt/2.47.0.260601` | pass `--qairt-home <path>` |
| Llama bundle exported | `ls workspace/output/ \| grep llama` | see [CHATBOT.md](CHATBOT.md) / handoff §1 |
| Whisper staged on device | `adb shell ls /data/local/tmp/whisper_poc` | run step 2 below (**dictation only**) |
| Host mic | `arecord -l` lists a capture device | use `--audio-file` instead |
| `ffmpeg` on host | `ffmpeg -version` | only needed for `--audio-file` |

Typed chat needs only the first three rows.

---

## 2. One-time setup per board

The Llama bundle is pushed automatically by the chatbot on first run. Whisper is
**not** — it uses a different runtime (`qnn-net-run`, not `genie-t2t-run`) and
needs staging once per board:

```bash
./scripts/08_stage_whisper.sh /mnt/ssd/bryan/AI_SMART/qairt/2.47.0.260601 v73 android
```
Arguments are `<qairt_sdk_path> <hexagon_arch> <target_os>`; all have defaults.
Use `linux` as the last argument for Qualcomm's Ubuntu BSP board. It prints
`OK: qnn-net-run executes on device` when it worked — if it doesn't, dictation
will fail later, so don't skip that line.

Swapping to a different physical board? Re-run this. Staging does **not** carry
over, and a board with no Whisper staged fails only when you first try to dictate.

---

## 3. Running it

```bash
python3 scripts/07_chatbot.py                          # interactive chat
python3 scripts/07_chatbot.py --talk                   # open in dictation mode
python3 scripts/07_chatbot.py --ask "hello"            # single turn, then exit
python3 scripts/07_chatbot.py --audio-file clip.wav    # one dictated turn from a file
```

First run pushes ~1.8GB to the device (tens of seconds). Every later run prints
`device already has this bundle ... skipping push` and starts immediately.

### In-chat commands

| Command | Effect |
|---|---|
| `/talk` | dictate the next turn instead of typing it |
| `/reset` | clear conversation history (keeps the model loaded) |
| `/tools` | print the tool catalog |
| `/quit`, `/exit` | exit (Ctrl-C and Ctrl-D also work) |

### Dictation flow

`/talk` (or `--talk`) prompts `Press ENTER to start recording...`, records from
the **host** mic, and stops on a second ENTER. There is no voice-activity
detection — recording is manual start/stop. Audio never leaves the host; only
the transcript text is sent to the device.

---

## 4. Options

| Flag | Default | Notes |
|---|---|---|
| `--model-id` | `llama_v3_2_1b_instruct_ctx4096` | must name a directory under `workspace/output/` |
| `--context-length` | `4096` | **must match** what the bundle was exported with |
| `--qairt-home` | `/mnt/ssd/bryan/AI_SMART/qairt/2.47.0.260601` | QAIRT SDK on the host |
| `--hex-arch` | `v73` | `v73` = QCS8550 |
| `--target-os` | auto-detected | `android` or `linux`; force it if detection is wrong |
| `--image` | `genie-llm-toolchain:latest` | docker image used for Whisper only |
| `--ask TEXT` | — | one turn, print the reply, exit |
| `--talk` | off | start by dictating |
| `--audio-file PATH` | — | one dictated turn from a file (any format ffmpeg reads) |
| `--tools` | off | enable tool calling — **experimental**, see below |

`--model-id` and `--context-length` must agree. Passing a 4096 context length
against a bundle exported at 2048 does not error at startup — it just lets the
prompt grow past what the bundle can hold. The defaults (ctx4096) are verified
working on the QCS8550; the ctx2048 bundle remains as a fallback:

```bash
python3 scripts/07_chatbot.py --model-id llama_v3_2_1b_instruct_ctx2048 --context-length 2048
```

---

## 5. Tool calling (experimental, off by default)

```bash
python3 scripts/07_chatbot.py --tools
```

Enables 12 tools: `get_time`, `calculate`, `device_status`, `find_contact`,
`web_search`, `web_fetch`, `list_apps`, `launch_app`, `send_sms`, `call`,
`set_timer`, `screenshot`. Web tools use the **host's** network; device tools go
over adb.

Anything with a side effect (`launch_app`, `send_sms`, `call`, `set_timer`,
`screenshot`) prompts for confirmation in the terminal first and **defaults to
no** — a bare ENTER declines. `send_sms` deliberately opens a prefilled composer
rather than sending; you still tap send.

**It is not reliable on this 1B/4-bit model** — it invents tool names and
fabricates results. Treat every tool-assisted answer as unverified. The measured
details are in [CHATBOT.md](CHATBOT.md#tool-calling--implemented-but-off-by-default).

On the QCS8550 HDK devkit specifically: no telephony features and no contacts
data, so `call`, `send_sms` and `find_contact` have nothing real to act on.

---

## 6. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `No adb device attached.` | `adb devices`; replug, or `adb kill-server` |
| `No genie_config.json under ...` | that `--model-id` isn't exported; check `ls workspace/output/` |
| `Missing QAIRT component: ...` | wrong `--qairt-home`, or wrong `--hex-arch`/`--target-os` pair |
| `Could not create context from binary ... err 1002` | bundle too big for DSP memory — use a **smaller context length** |
| Dictation fails, Whisper errors | Whisper not staged on this board → run step 2 |
| `arecord: command not found` / no capture device | no host mic → use `--audio-file` |
| Answers are confidently wrong | expected: it's a 1B 4-bit model with a hot sampler (`temp 0.8`); don't trust facts |
| Replies contain `TOOL:` text | only with `--tools`; the model emitted a bogus tool name |
| Re-pushes the 1.8GB bundle every run | fingerprint changed — re-staging or a re-export updates file mtimes |
| Slow first turn only | one-time bundle push; later runs skip it |

Re-run the offline checks after changing anything in `scripts/chatbot/`:

```bash
python3 tests/test_chatbot.py     # no device required
```

---

## 7. Known limitations

- **No VAD** — dictation is manual start/stop, and Whisper's input window is a
  fixed 30s, so long utterances need chunking that isn't implemented.
- **Dictation latency** — ~0.7s per decode step, because each step shells out to
  `qnn-net-run` and reloads the context binary. Typed turns are ~1.6–2.8s.
- **No mic on the target** — audio is always captured host-side.
- **Tool calling unreliable** — see §5.
- **History trimming is approximate** — token counts are estimated at
  `chars / 3.2` (deliberately over-estimating), not tokenized.
