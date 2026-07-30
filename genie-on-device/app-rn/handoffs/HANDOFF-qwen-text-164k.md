# Handoff: GenieX Qwen3.5-2B TEXT generation — FIXED (root cause: wrong wrapper class)

Date: 2026-07-24 (session 2, corrected). **Text generation now works at full
production config: 164K ctx, NPU, tool schemas attached.** Verified live on
device with real streamed replies. There is one narrower follow-up bug (a
content-dependent template crash, see bottom) but general chat is solid.

App: `genie-on-device/app-rn`, package `com.geniechatrn`, board QCS8550 (Hexagon
v73, 11.5GB), screen 720x1280. Memory: `geniex-android-sdk-rn-app.md`.

---

## Root cause

**GenieX ships two separate generation APIs, and the app was using the wrong
one for text.** `VlmWrapper` (image-capable, native `LlamaVlm::generate`) and
`LlmWrapper` (text-only, native `Llm::generate`) are backed by **different
native code paths** despite sharing the same GGUF and the same
`libgeniex_plugin_llama_cpp.so`. `GenieXEngine` used `VlmWrapper`
unconditionally — even for pure text turns with zero images — because it's a
superset of `LlmWrapper` and avoids a reload the first time a user attaches a
photo (see the removed comment in the old `ensureModel`).

`VlmWrapper`/`LlamaVlm::generate` is broken on this device/plugin build: it
SIGSEGVs (fault addr `0x0`) unconditionally, on literally every input tried —
prompt content, `nCtx`, compute unit (npu/cpu), plugin version (Maven 0.3.12
vs a local 0.3.16 build), KV-cache reset timing, sampler config. All of that
was exhaustively tested in the first pass of this session (see git history for
the previous version of this doc, or the corrected memory note in
`geniex-android-sdk-rn-app.md`) and wrongly concluded to be an unconditional
Qualcomm plugin bug.

**The user correctly pushed back**: text generation worked before image/VLM
support was added. That was the missing constraint. Building a minimal
diagnostic that called `LlmWrapper`/`Llm::generate` directly (bypassing
`GenieXEngine` entirely) confirmed it: real streamed tokens, clean completion,
no crash, on the identical GGUF, identical device, identical NPU backend.

## The fix

`GenieXEngine.kt` was rewritten to use `LlmWrapper` exclusively:
- `wrapper: VlmWrapper?` → `wrapper: LlmWrapper?`.
- `VlmCreateInput(...)` → `LlmCreateInput(name, gguf_path, tokenizer_path="",
  ModelConfig, "llama_cpp", computeUnit)`. Empty `tokenizer_path` is correct —
  the tokenizer lives inside the single-file GGUF.
- `VlmChatMessage(role, List<VlmContent>)` → the flatter `ChatMessage(role,
  content: String)`. Text-only messages only ever had one `VlmContent("text",
  ...)` part anyway, so this is a lossless simplification.
- `applyChatTemplate(messages, tools, thinking)` (VLM, 3 args) →
  `applyChatTemplate(messages, tools, thinking, addGenerationPrompt=true)`
  (LLM, 4 args — the VLM variant bakes `addGenerationPrompt` in, LLM's does
  not).
- Dropped `injectMediaPathsToConfig` (image-only) and `probeVision`/
  `visionReady` (the vision capability probe, now moot).
- **Images are declined outright** with a short note, rather than routed
  anywhere. `ModelStore`'s `supportsImages = true` on the `qwen3_5_2b` spec is
  now aspirational/unused for this model; the attach-button UI still shows
  (untouched — out of scope) but any attached image gets the same "not
  supported right now" message every attempt would have hit anyway (images
  were already broken by the clip.cpp `qwen3vl_merger` incompatibility from
  session 1 — this doesn't newly break anything).

## Verified on device (2026-07-24, full production config)

- `ModelStore.kt`: `declaredContextLength = 164000`, `computeUnit = "npu"` —
  **unchanged from before this investigation**, i.e. the real shipping config,
  not a reduced diagnostic one.
- Loaded in ~1.4–2.0s (HTP graph cache warm) up to the documented ~56s cold.
- **"Tell me a short joke"**: full system prompt (with tool-use instructions)
  + 5 tool schemas attached (756-token rendered prompt — the exact size that
  used to crash) → coherent reply, streamed, no crash. `prefill_speed=324
  tok/s, decoding_speed=15.5 tok/s`.
- **"What do you think of pizza"**: same config, different question → full
  multi-paragraph reply, 12.6s, no crash.
- Confirmed visually in-app: `Qwen3.5 2B · 164,000 ctx` shown in the composer
  footer, reply bubble renders normally.

---

## Follow-up bug (separate, narrower, NOT YET ROOT-CAUSED)

**Some specific questions crash `Llm::apply_chat_template` with a genuine,
uncaught C++ exception** — `libc++abi: terminating due to uncaught exception
of type std::invalid_argument: ... Jinja Exception: Unexpected message role.`
→ `SIGABRT`. This is a **different bug** from the SIGSEGV above: it's inside
`geniex::LlamaLlm::apply_chat_template` (not `::generate`), it's a real Jinja
template-engine exception (not a null deref), and — critically — it is
**content-dependent, not universal**: most chat turns work fine.

**Reproduced crashing:**
- "What time is it right now on this device"
- "What is the capital of France"
- "What is the weather like in general on Mars"

**Reproduced working (same system prompt, same tool schemas, same everything
else):**
- "Tell me a short joke"
- "What do you think of pizza"
- "ping" / "hello" (trivial baseline)

**What was ruled out this session:**
- **Not about the `"tool"` role or tool-call loop.** The crash happens on the
  turn's very **first** `applyChatTemplate` call — `message_count: 2`
  (system + user only), confirmed by dumping every message's `role`/`content`
  from Kotlin right before the call (both logged correctly: `role='system'`,
  `role='user'`). No tool has been called yet when it crashes.
- **Not about tools being attached.** Reproduces identically with
  `toolsJson = null` (tools completely omitted from the call).
- **Not about the word "What"** or interrogative phrasing — "What do you
  think of pizza" (also starts with "What") works fine.
- **The role strings really are correct** at the JNI call boundary (see
  above) — whatever the native Jinja engine sees, it isn't what the app sent.

**Working theory, unconfirmed:** the GGUF's actual embedded chat template
(extracted via a small hand-rolled GGUF-metadata parser, python snippet in
this session's scratchpad if still around — or re-extract with `strings` /
manual KV parsing, key `tokenizer.chat_template`) is a large (~7800 char)
Jinja2 template using features a minimal engine may not fully support:
macros (`render_content`), namespaces, `messages[::-1]` negative-step
slicing, and `loop.previtem`/`loop.nextitem` (Jinja2-only loop extensions).
llama.cpp's bundled **minja** (a minimal from-scratch Jinja implementation,
not real Jinja2) is known to support only a subset of Jinja2. The pattern —
some content triggers it, most doesn't, always at the same `raise_exception`
call site regardless of which branch *should* have matched — is consistent
with a minja parser/evaluator bug that's sensitive to something structural
about the rendered token stream (possibly length- or content-adjacent, not
truly semantic), not a real "wrong role" condition.

**Next steps for whoever picks this up:**
1. Get the raw minja parse/exec trace (may need a debug build of the plugin,
   or bisect the template itself — trim the template down section by section
   in a standalone `llama-cli --chat-template-file` test to find what specific
   Jinja construct minja mishandles).
2. Try swapping `tokenizer.chat_template` in the GGUF for a simpler
   hand-written template (no macros/namespaces/loop extensions) as a
   workaround, since GenieXEngine already doesn't rely on GGUF-embedded
   chat_template being anything specific.
3. This is orthogonal to the images/VlmWrapper issue — do not conflate the
   two when reporting to Qualcomm.

---

## Gotchas / how to run

- **No system node/adb niceties on this host.** A portable node was found each
  prior session in a since-expired scratchpad. This session used
  `/home/smart/.vscode-server/cli/servers/<version>/server/node` (bundled with
  the VS Code Remote server) as a portable node — works fine for Gradle/Metro,
  no npm needed since `node_modules/.bin/react-native` is already installed.
- **Capture the crash with a big buffer.** `adb logcat -G 16M` first, or the
  ~10-56s load spews verbose `GenieXSdk` repack lines that rotate the tombstone
  out before you can dump it.
- **`adb install -r` clears `adb reverse`** → re-set it and relaunch, or you
  get a stale JS bundle / bogus `Genie.generate got N arguments` errors.
- **This board reboots under memory stress, unpredictably** — happened twice
  this session, always during/around an NPU model load. `adb` drops with "no
  devices/emulators found"; just wait (`until adb get-state >/dev/null 2>&1;
  do sleep 3; done`) and relaunch once it's back. Not related to either bug
  above.
- **This board also has an unrelated, harmless crash-loop**: the camera
  provider service (`vendor.qti.camera.provider-service_64`,
  `CamX::HwEnvironment`) SIGABRTs on a ~5s cycle constantly in the background.
  It floods any untargeted `adb logcat`/tombstone watch — always filter to
  `com.geniechatrn`'s pid or grep for `GenieXSdk`/`GenieXEngine`/
  `geniechatrn` specifically, never a bare `DEBUG:F` tag.
- **`adb shell input text` truncates at the first space** unless you encode
  spaces as `%s` (e.g. `input text "hello%sworld"`), or the keyboard's
  predictive-text bar can eat a plain-space multi-word `input text` call.
- **The app's "pending" bubble does not indicate `generate()` was actually
  called.** If a UI tap misses (e.g. lands on the launcher after a
  background/foreground bounce, common right after `adb install -r`), the
  chat shows a permanently-stuck spinner with no native activity at all in
  logcat. Always confirm with `adb logcat -d | grep -i GenieXSdk` (or
  `GenieXEngine`) that a real `apply_chat_template`/`generate` call happened
  before concluding a test passed or failed.
- Build: `cd android && env PATH="<node-dir>:$PATH" ./gradlew :app:assembleDebug`,
  `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
- Model already on-device from prior sessions; re-push if needed with
  `scripts/11_push_gguf_model.sh qwen3_5_2b com.geniechatrn`.
- **Inspecting the SDK surface**: `javap -p -c -classpath <extracted classes.jar>
  com.geniex.sdk.<LlmWrapper|VlmWrapper>` and `com.geniex.sdk.bean.*` — the AAR's
  `classes.jar` is the source of truth, not the web docs. Use `-c` to
  disassemble and read `$default` constructor bytecode for actual default
  values (Kotlin default-arg bitmask trick).

## Status

| Item | State |
|---|---|
| GenieX 2B text generation, 164K ctx, NPU, tools attached | ✅ **FIXED** — verified live, multiple prompts |
| Image attachment | ⛔ Not supported (VlmWrapper path is broken; deliberately unused now). Already broken pre-existing (clip.cpp `qwen3vl_merger` incompatibility) — no regression, just no longer silently routed through a crashing path either |
| Certain content crashes `apply_chat_template` (Jinja "Unexpected message role") | 🟡 **New, separate, narrower bug** — not root-caused, see above. Most chat works; a few specific prompts don't |
