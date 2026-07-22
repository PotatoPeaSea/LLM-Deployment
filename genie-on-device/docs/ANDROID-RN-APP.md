# Genie Chat (React Native) — three models, many chats

**Written:** 2026-07-21. **Source:** [`app-rn/`](../app-rn).
**Supersedes the UI of:** [ANDROID-APP.md](ANDROID-APP.md) (the Kotlin/Views app,
still in `android/`, still builds — it is the smaller reference implementation).

## What it is

A React Native front end over the same JNI/Genie C++ layer: Llama-3.2-1B,
Llama-3.2-3B and Qwen3-4B running on the Hexagon NPU, multiple persisted chats,
and per-chat brevity and reasoning toggles.

```
App.tsx  ── chats, settings, navigation
  └─ ChatScreen ── src/genie.ts ── NativeModules.Genie
                                     └─ GenieModule.kt   worker thread, events
                                        └─ ChatEngine.kt one resident dialog
                                           └─ libgeniebridge.so ── libGenie.so ── NPU
```

Everything below `GenieModule` is shared with the Kotlin app; React Native
replaced the View layer only.

## Verified on device (2026-07-21, QCS8550 `kalama`, Android 13)

| | measured |
|---|---|
| Llama 3.2 1B load | 1.25s |
| Llama 3.2 3B load (ctx 2048) | 2.07s |
| Qwen3-4B load (ctx 512) | 1.8–2.3s |
| Qwen3-4B staging (first run) | 3.0GB copy, ~1.6s |
| Qwen turn | 0.7–1.6s |
| Model switch | unload + load, no restart |

- **Qwen answers 137×24 = 3288 correctly**; Llama says "3,072". That difference
  is the whole argument for the model switcher.
- Chats survive an app restart, and reopening one re-primes the NPU from stored
  history (`priming chat … with 6 messages`).
- Occupancy on Qwen runs 67 → 105 → 134 of 512. The window is small enough that
  trim-and-re-prime is routine, not an edge case.

## The bug that cost the most: Qwen wouldn't load from external storage

Qwen3-4B failed with `Failed to map buffer of size 1006632960 … err 1002` while
Llama loaded fine, which made it look like a Qwen problem. It is not:

- Qwen loads fine via `genie-t2t-run` from `/data/local/tmp` — so not the
  device, the config, or DSP memory.
- Llama's shards are ~525MB; Qwen's largest is **968MB**.
- `getExternalFilesDir()` is **FUSE-backed**, and a large region of a FUSE file
  cannot be mapped into the DSP's SMMU. Copy the identical bundle to internal
  storage and it loads in 2.0s.

So external storage is a **delivery mechanism only**. `ModelStore.stage()`
copies a pushed bundle into `filesDir/models/<id>` on first load, reporting
percent to the UI, and every later load skips it. Budget the disk for two
copies of each bundle until you delete the pushed one.

**This also applies to the Kotlin app in `android/`**, which still loads
directly from external storage and will therefore fail on Qwen.

## Build and run

```bash
cd genie-on-device

./scripts/09_stage_qairt_for_app.sh app-rn/android    # QAIRT libs + headers
cd app-rn
npx react-native bundle --platform android --dev false --entry-file index.js \
  --bundle-output android/app/src/main/assets/index.android.bundle \
  --assets-dest android/app/src/main/res
cd android && ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

cd ../.. && ./scripts/10_push_app_model.sh llama_v3_2_1b_instruct_ctx4096 com.geniechatrn
./scripts/10_push_app_model.sh llama_v3_2_3b_instruct_ctx2048 com.geniechatrn
./scripts/10_push_app_model.sh qwen3_4b com.geniechatrn
```

Bundling the JS means the APK runs standalone — no Metro, no `adb reverse`,
which matters on a devkit that isn't always tethered. For UI iteration, run
`npx react-native start` and use the debug build instead.

Toolchain (installed into `/mnt/ssd/bryan/AI_SMART/tools`): **Node 20.18.1** and
**NDK 26.1.10909125**. The repo's other NDK (21.4) is too old for RN 0.74.

> `adb shell pm clear` wipes the app's *external* files dir too, so it deletes
> the pushed bundles. Re-push after clearing.

## Models

Declared in `ModelStore.MODELS`; context length is read from each bundle's
`genie_config.json` rather than declared, so the two can't drift.

| id | template | reasoning | context | bundle | load |
|---|---|---|---|---|---|
| `llama_v3_2_1b_instruct_ctx4096` | Llama-3.x | no | 4096 | 1.3GB | 1.25s |
| `llama_v3_2_3b_instruct_ctx2048` | Llama-3.x | no | 2048 | 2.5GB | 2.07s |
| `qwen3_4b` | Qwen3 ChatML | yes | 512 | 3.0GB | 1.8-2.3s |

**Llama-3.2-3B at ctx2048 fits this QCS8550** — verified 2026-07-21, loads in
2.07s with no `err 1002`, and generated ~200 tokens in 26.6s (~7.5 tok/s, versus
the 1B's ~2s turns). Its largest shard is **1.12GB**, bigger than the Qwen shard
that fails from FUSE storage, so it depends on internal staging too.

Exporting it needed two deviations from `04_export_model.sh`: the 3B **rejects
`--skip-inferencing`** (which that script hardcodes), and `meta-llama` is a
gated repo, so `HF_TOKEN` must be set. The invocation that worked:

```bash
export HF_TOKEN=...
source scripts/00_env.sh
docker_run "${IMAGE_NAME}:latest" qai-hub-models export llama_v3_2_3b_instruct \
  --runtime geniex_qairt --chipset qualcomm-qcs8550-proxy --skip-profiling \
  --context-lengths 2048 --output-dir /workspace/output/llama_v3_2_3b_instruct_ctx2048
```

Qwen is the **ctx512** export deliberately — the longer exports don't fit this
QCS8550. Adding a model is a row here plus a `ChatTemplate`.

### One model at a time

Only one fits in DSP memory, so **a chat is pinned to the model it started
with**, and opening a chat may swap what's resident. The KV cache holds exactly
one conversation, which is also why chat history lives in JS (AsyncStorage) and
is replayed into native whenever the cache is rebuilt: new chat, model switch,
or context overflow.

## Context management

The rule: **the rendered prompt must never exceed `contextLength − reserve`.**
Genie does not refuse an over-long prompt — it silently truncates the reply
(`WARNING_CONTEXT_EXCEEDED`) — so nothing downstream will catch a mistake here.

Four mechanisms, in the order they act:

1. **Scaled reserve.** `reserveForReply()` is `contextLength / {6 brief, 4 plain,
   2 reasoning}`, clamped to 96–512. It was a flat 256, which was written for
   Llama's 4096 (6% of the window) and silently cost Qwen at ctx512 **half of
   its window**. Qwen now reserves 96–128 and has roughly double the usable
   history. Reasoning still gets half, because `<think>` and the answer both
   land in the KV cache.
2. **Shorter system prompts, per model.** Qwen's was 45 tokens — 9% of its
   window before anyone said anything. It is now 4 (`"You are a helpful
   assistant."`, brevity clause `" Be brief."`). Llama keeps the longer one; a
   1B needs more steering and has the room.
3. **Trim, and truncate the newest turn.** `fitToContext` drops whole oldest
   turns, and — new — truncates the newest message if it alone would blow the
   budget, keeping its head and tail with a `…[trimmed…]…` marker. Previously
   the newest was kept whole, so one long paste could build a prompt bigger
   than the window.
4. **A hard token ceiling.** `GenieDialog_setMaxNumTokens` caps generated tokens
   per turn. This is the backstop against a model that never emits EOS.

### The loop is real

Asked to explain the NPU, Qwen3-4B restated *"The NPU is a specialized
processing unit"* three times and kept going. Two changes address it:

- The **token cap** stops it — measured, occupancy held at 158/512 instead of
  running to the window.
- A **repetition penalty** makes it rarer. The exported sampler has no penalties
  at all; `ModelStore.buildConfigJson` now injects
  `sampler.token-penalty { penalize-last-n: 64, repetition-penalty: 1.15 }`.
  With it, the same prompt produced varied, non-repeating prose.

The status line shows `context 160/512`, warns past 75%, and says **"reply
stopped at the length limit"** when a reply was cut short — otherwise a capped
answer is just a mysterious mid-sentence stop.

> Detecting "was it capped?" by comparing occupancy to prompt size **does not
> work**: occupancy is Genie's exact count while the prompt size is the
> `chars/3.2` over-estimate, so the difference understates what was generated
> and missed real cut-offs (`generated~106` against `maxNew=128` for a reply
> that had visibly stopped mid-list). It now judges the text — a reply not
> ending in terminal punctuation was cut short.

### Tested off-device

`ChatEngineContextTest` (7 tests, `./gradlew testDebugUnitTest`) pins the
arithmetic: trimming keeps the newest turn and starts on a user message, an
oversized paste is truncated rather than overflowing, reasoning leaves less
history, and **every window size (512/1024/2048/4096) × both toggles stays
inside its budget**. The context math is pure (`ChatEngine.Companion`) precisely
so it can be tested without a board — the overflow branch is tedious to reach by
typing, which is why it went unverified for a while.

## Toggles

**Brief answers** (default on) appends one clause to the system prompt. It was
previously hardcoded — inherited from the voice assistant, where one or two
sentences is right because the reply is spoken. Off lets the model run long.

**Reasoning** (Qwen only) is not a magic word in the user's message; it is what
follows the assistant header. Left open, Qwen emits `<think>…</think>` first;
primed with an *empty* think block it answers directly — exactly what the
official template does for `enable_thinking=false`.

`ReasoningSplitter` separates the two *as they stream*, so the answer appears
while reasoning stays behind a disclosure. One wrinkle worth knowing:

> Qwen3-4B at ctx512 often reasons and then stops, never closing `</think>` and
> never writing a separate answer. Mid-stream that is indistinguishable from
> "still thinking", so `finish()` promotes the reasoning to be the answer once
> generation ends. Without it the user gets an empty bubble with the reply
> hidden behind a disclosure — which is what the first build did.

Reasoning spends the 512-token window fast; expect trims.

## Files

| Path | Role |
|---|---|
| `app-rn/App.tsx` | chats, settings, navigation |
| `app-rn/src/screens/ChatScreen.tsx` | one conversation, streaming, sheet |
| `app-rn/src/screens/ChatListScreen.tsx` | chat list, new/delete |
| `app-rn/src/genie.ts` | native module wrapper, event→callback plumbing |
| `app-rn/src/store.ts` | AsyncStorage chats + settings |
| `app-rn/src/components/` | Bubble (with Thoughts), Composer, Sheet |
| `…/genie/GenieModule.kt` | RN bridge: worker thread, token + staging events |
| `…/genie/ChatEngine.kt` | resident dialog, priming, context budget |
| `…/genie/ChatTemplate.kt` | Llama-3.x + Qwen3 templates, ReasoningSplitter |
| `…/genie/ModelStore.kt` | model registry, internal staging, config rewrite |
| `…/cpp/genie_bridge.cpp` | JNI ↔ Genie C API (shared with the Kotlin app) |

## Still open

- **Dictation.** Unchanged from ANDROID-APP.md: needs a native QNN runner, a
  log-mel front end, the KV-threaded decode loop, and the Whisper detokenizer.
- Answer quality is the models' own. Qwen3-4B at w4a16/ctx512 refused "name two
  primes under 10" outright in testing.
- The Kotlin app needs the internal-staging fix before it can load Qwen.
- No way to delete a staged bundle from inside the app; it's ~3GB of internal
  storage per model.
