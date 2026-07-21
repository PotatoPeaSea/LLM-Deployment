# Chatbot + voice assistant on the QCS8550 NPU

**Written:** 2026-07-21. **Entry point:** `scripts/07_chatbot.py`.
**Builds on:** [HANDOFF-whisper-llama-voice-assistant.md](HANDOFF-whisper-llama-voice-assistant.md)
(that doc covers how the Llama and Whisper bundles were produced and deployed;
this one covers the chat application built on top of them).

## What it is

A multi-turn chatbot you talk to by typing or by dictating. Both models run on
the device's Hexagon NPU; this host is only an orchestrator that holds the
conversation and talks to the device over `adb`.

```
you (typed, or dictated with /talk)
   -> Whisper-Base on the NPU           (dictation only, qnn-net-run)
   -> Llama-3.2-1B-Instruct on the NPU  (genie-t2t-run)
   -> reply
```

The board has no mic, so audio is captured on **this host** (`arecord`) and only
the transcript text ever crosses to the device.

### How it differs from `06_voice_assistant.py`

| | `06_voice_assistant.py` | `07_chatbot.py` |
|---|---|---|
| Conversation | one-shot, no memory | multi-turn history with context budgeting |
| Bundle push | full ~1.8GB every turn | once at startup, skipped if device is current |
| Turn latency | ~7s + ASR | **~1.6-2.8s** typed |
| Input | mic only | typed, `/talk`, or `--audio-file` |

## Quick start

> Full operator guide — every flag, per-board setup, troubleshooting — is in
> **[USAGE.md](USAGE.md)**. This section is just enough to get a reply.

```bash
cd genie-on-device

# One time per board: stage the Whisper runtime (the Genie/Llama bundle is
# pushed automatically by the chatbot itself).
./scripts/08_stage_whisper.sh /mnt/ssd/bryan/AI_SMART/qairt/2.47.0.260601 v73 android

python3 scripts/07_chatbot.py                       # interactive chat
python3 scripts/07_chatbot.py --talk                # start by dictating
python3 scripts/07_chatbot.py --ask "hello"         # one shot
python3 scripts/07_chatbot.py --audio-file clip.wav # dictate from a file (no mic)
```
In-chat commands: `/talk` (dictate one turn), `/reset`, `/quit`.

Target OS is auto-detected (`--target-os` to force). Both boards seen so far are
QCS8550 but one runs Android and one Qualcomm's Ubuntu BSP, which need different
runtime binaries and staging paths — see the handoff §8.

## Verified on device (2026-07-21, Android QCS8550, `kalama`, Android 13)

- Multi-turn memory: told it "My name is Bryan", answered "What is my name?"
  correctly **four turns later** — history threading works (at both ctx2048 and
  ctx4096).
- Latency **1.6–3.6s/turn** typed, after a one-time 1.8GB push. A restart against
  an already-current device skips the push entirely (fingerprint match).
- Voice path end-to-end: WAV → Whisper on NPU → `'Hello, who are you?'` →
  Llama on NPU → coherent spoken-style answer.
- Offline regression suite: `python3 tests/test_chatbot.py` (no device needed).

## Context budgeting

`prompt.py` renders the Llama-3.x chat template and keeps the prompt inside the
context window. Turns are dropped from the **oldest** end and only at turn
boundaries, so a tool result never outlives the request that produced it.
The system prompt and few-shot examples are pinned and never evicted.

Token counting deliberately **over**-estimates (`chars / 3.2`) because the
`tokenizers` package isn't installed on this host: overshooting wastes a little
context, undershooting truncates generation mid-sentence.

Measured: the full 12-tool catalog costs **~392 tokens of 4096 (10%)** — the
catalog is cheap. Tool *results* are what blow up context, so every tool
truncates its output to 1200 chars (a fetched web page is otherwise larger than
the entire window).

## Tool calling — implemented, but OFF by default

`--tools` enables it. It is off because it is **not trustworthy on
Llama-3.2-1B at 4-bit**. What was actually measured on-device:

1. With a long (~2000 char) system prompt full of rules, the model ignored tools
   entirely and **hallucinated answers** — it reported the battery as 30/36/37%
   (real value 72%) and computed 137×24 as 3258/3276 (correct: 3288).
2. The system prompt *was* being honoured — a "reply only with BANANA" probe
   returned `BANANA`. The problem is prompt **length**: at 4-bit,
   instruction-following degrades sharply as the system prompt grows.
3. Shortened to ~800 chars, it called tools correctly (`TOOL: device_status {}`).
4. Few-shot examples written as `User:/You:` lines *inside* the system prompt
   made it echo a literal `You:` prefix. As real conversation turns, it doesn't
   — hence `FEWSHOT` in `agent.py`.
5. It still invents tool names (`TOOL: osmdroid info "Paris"` for "capital of
   France"). `attempted_tool_name()` catches that and corrects the model rather
   than showing raw tool syntax to the user.
6. The default sampler is hot (`temp 0.8, top-k 40`), which is why the
   hallucinated battery figure differed on every run. Greedy decoding did not
   rescue tool-calling on its own.

The machinery that *is* built and tested: 12 tools (contacts, web search/fetch,
device status, apps, SMS/call/timer/screenshot, clock, calculator), a lenient
parser accepting every syntax shape the model was observed to emit, a hop cap,
and a terminal confirmation gate on every side-effecting tool (defaults to *no*).

**Board caveat:** this HDK devkit reports **no telephony features and no
contacts data**, so `call`, `send_sms` and `find_contact` have nothing real to
act on here even once tool-calling is reliable.

### If you pick this back up
The realistic paths, in order of expected payoff:
1. Use a bigger model for the tool-deciding step (Qwen3-4B is already deployed
   here) and keep the 1B for chat.
2. Constrain decoding to the tool grammar instead of hoping the model complies.
3. Route obvious intents (battery, timer, contacts) with a host-side classifier
   and use the LLM only to phrase the result.

## Files

| Path | Role |
|---|---|
| `scripts/07_chatbot.py` | entry point / CLI |
| `scripts/chatbot/llama.py` | deploy-once + per-turn generate on the NPU |
| `scripts/chatbot/prompt.py` | chat template, token estimate, context trimming |
| `scripts/chatbot/agent.py` | conversation loop, tool parsing/dispatch |
| `scripts/chatbot/tools.py` | the 12 tools |
| `scripts/chatbot/dictation.py` | mic capture + on-device Whisper |
| `scripts/chatbot/device.py` | adb plumbing, target-OS profiles |
| `scripts/08_stage_whisper.sh` | stage the Whisper runtime on a fresh board |
| `tests/test_chatbot.py` | offline regression suite |

## Context length 4096 — exported and confirmed working

`llama_v3_2_1b_instruct_ctx4096` was exported for this work (3 compile jobs +
1 link job on AI Hub, ~35 min) and **loads and runs cleanly on the QCS8550** —
no `err 1002`. It is the script default.

DSP allocation scales roughly linearly with context length for this model, and
4096 is comfortably inside the budget:

| context | `Allocated total size` |
|---|---|
| 512 | 50.8 MB |
| 2048 | 101.5 MB |
| **4096** | **169.2 MB** |

Verified at 4096: 4-turn conversation with history retained (recalled a name
given in turn 1), 2.1–3.6s/turn, and the full voice path (WAV → Whisper → Llama).
Whether 8192 also fits is untested.

Bundle size is unchanged at 1.3GB — quantized weights don't depend on context
length, only the runtime KV-cache allocation does.

**Answer quality is the 1B model's own limit, not a harness one.** At 4096 it
still recalled "Bryan" four turns later but mangled the substance — rendering
"NPU deployment" as "Network Process Deployment" and inventing "Hexagonal
Distributed Processing" for Hexagon DSP. Longer context buys conversational
headroom, not accuracy.

## Open items

- No VAD — dictation is manual start/stop (handoff §9 item 5).
- Whisper still costs ~0.7s per decode step because each step shells out to
  `qnn-net-run`, reloading the context binary (handoff §9 item 6).
