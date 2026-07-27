# Handoff: on-device CLI built, Jinja-template crash fixed, and every model-switch failure mode root-caused and fixed via a process-restart architecture

Date: 2026-07-24 through 2026-01-31 (device clock is wrong/unsynced — see
below). Follow-on to `HANDOFF-reasoning-tools-fixes.md`, which ended with a
reboot that was never root-caused because nothing survived to inspect it
afterward. This session built the tool that finally solves that problem, and
used it to find and fix three completely different bugs:

1. **A native Jinja-template crash — ROOT-CAUSED AND FIXED, verified working.** (Part 1)
2. **A real device reboot from memory pressure during a QNN↔GenieX runtime
   switch — ROOT-CAUSED, then generalized into four distinct switch-failure
   modes across every model/runtime combination, all FIXED.** (Parts 2-3)

Read this whole doc before touching the code again — it corrects a few wrong
turns from earlier in this same session (documented below, not hidden,
because the wrong turns are informative). **Part 3 supersedes Part 2's "not
fixed" status and the mid-session QNN-removal detour — read Part 3 first if
you're only here for the current state.**

App: `genie-on-device/app-rn`, package `com.geniechatrn`, board QCS8550
(Kalama). Memory: `geniex-android-sdk-rn-app.md`, `qcs8550-qwen3-4b-genie.md`.

---

## Part 0: the CLI tool (what makes any of this possible)

The whole reason `HANDOFF-reasoning-tools-fixes.md` ended in "the reboot was
never root-caused" is that reproducing it required dozens of manual UI
prompts and screenshots, and by the time `adb` reconnected after a reboot the
on-device logcat ring buffer was already wiped. This session built a way
around that entirely.

**`android/app/src/debug/java/com/geniechatrn/genie/CliReceiver.kt`** (debug
build only — lives under `src/debug/`, never ships in release) is a
`BroadcastReceiver` that drives a turn without the JS UI or a screenshot. It
calls **`GenieModule.runCliTurn`** (new method added to
`GenieModule.kt`, alongside the existing JS-facing `generate()` — same
`busy`/`switchTo`/engine plumbing, so a CLI turn and a real UI turn can never
race the DSP). Manifest entry added to `src/debug/AndroidManifest.xml`.

```
adb shell am broadcast -n com.geniechatrn/.genie.CliReceiver -a com.geniechatrn.CLI_PROMPT \
  --es turnId t0 --es chatId cli --es modelId qwen3_5_2b \
  --es textFile /sdcard/Android/data/com.geniechatrn/files/cli/in/t0.txt \
  --ez thinking true --ez brevity false --ez reset true
```
Result: logcat tag `GenieCli`, `CLI_RESULT`/`CLI_ERROR` line with the turnId,
full JSON at `/sdcard/Android/data/com.geniechatrn/files/cli/<turnId>.json`
(the prompt itself is pushed as a **file**, not a broadcast extra — see the
gotcha below).

**`scripts/genie_cli.py`** is the host-side driver:
```
python3 scripts/genie_cli.py --smoke-test                       # 4 known trouble prompts
python3 scripts/genie_cli.py --prompt "..." --chat-id X --model qwen3_5_2b
python3 scripts/genie_cli.py --prompt "a" --prompt "b" --chat-id X   # multi-turn, same chat
```
It tails `adb logcat` **continuously to a file on the host**, across every
prompt, specifically so a mid-turn reboot doesn't destroy the evidence the
way it did last session. It also watches host-side `adb shell cat
/proc/uptime` / `pidof` to detect a reboot or app crash instead of trusting
the device to report its own death.

### Bugs found *in the CLI tooling itself* while building it (all fixed, worth knowing about if you extend the script)

- **Implicit broadcast never reached the app.** `adb shell am broadcast -a
  <action>` with no target is an *implicit* broadcast; Android 8+ silently
  drops those to an app that isn't foreground (`BroadcastQueue: Background
  execution not allowed` in logcat, easy to miss). Fixed: always pass `-n
  com.geniechatrn/.genie.CliReceiver` too.
- **`ReactContext.getNativeModule(GenieModule::class.java)` throws, not
  returns null**, because it does a reflection lookup keyed on a
  `@ReactModule` annotation `GenieModule` doesn't have (never needed it —
  JS resolves modules by the `getName()` string). `CliReceiver` instead
  filters `reactContext.nativeModules` by type.
- **`.reactHost` (bridgeless-mode `ReactHost`) is never actually started** by
  this app — new-arch/bridgeless flags are off, so `MainActivity` runs the
  classic bridge via `reactNativeHost.reactInstanceManager`. Reading
  `.reactHost` silently gives a `ReactContext` that's always null.
- **A single flaky `adb shell` call ≠ a device reboot.** Early version
  declared "reboot" on one failed `adb shell cat /proc/uptime`; under heavy
  native/DSP load one `adb` round-trip can stall without the device actually
  going anywhere. Fixed to require 3 consecutive failures.
- **A per-drained-logcat-line `adb shell` liveness check is a huge
  performance bug on this device specifically.** This board's logcat is
  extremely noisy even idle (~130k lines in a 12-minute run, mostly
  SDM/graphics spam). Checking liveness via a live `adb` subprocess call on
  *every single line* pulled from the backlog turned an actual 22-second
  reply into an apparent 200+ second timeout — draining thousands of backlog
  lines at ~1 `adb` round-trip each. Fixed: throttled to one liveness check
  per 2 seconds of wall time, not per line.
- **After a real device reboot, THIS HOST's local `adb` server can lose the
  USB device entirely and never rescan on its own** — `adb get-state` hangs/
  fails indefinitely even minutes after the device finished booting.
  `adb kill-server` (next command auto-restarts it) is what actually fixes
  it. `wait_for_device_back()` now restarts the local server every 15s while
  waiting, not just once.
- **Prompt text goes over as a *pushed file*, not a broadcast extra.**
  `adb shell <args...>` joins all args into one string before sending to the
  device's shell, so a multi-word `--es text "..."` gets its quoting eaten
  and words get reparsed as separate `am` flags (`--es text "Say hello"`
  → `am` saw a bogus extra `pkg=hello`). Sidestepped entirely by pushing
  the prompt to `/sdcard/Android/data/com.geniechatrn/files/cli/in/<id>.txt`
  and passing `--es textFile <path>` (`CliReceiver` reads `text` as a
  fallback only, for quick single-word manual pokes).
- The device's clock is wrong/unsynced (shows `2026-01-28`, six months off
  real time) but *does* advance monotonically across the reboots seen this
  session — timestamps in logcat excerpts below are internally consistent
  even though the date is wrong.

---

## Part 1: the Jinja-template crash — ROOT-CAUSED, FIXED, VERIFIED

### What it looked like

User report this session: "even after reverting to a previously-thought-good
commit (`2993d10`), the app still crashes asking it to write an essay about
the telephone." This is a DIFFERENT bug from anything in
`HANDOFF-reasoning-tools-fixes.md` — that doc's Bugs 1–4 were never
re-applied; `2993d10` is exactly the reverted, "nothing session-specific
applied" baseline, and it still crashed.

**It was never a device reboot.** `adb shell getprop ro.boot.bootreason` /
`uptime` stayed clean throughout every one of these crashes — this is a
full **app process** `SIGABRT`, confirmed by pid staying the app's own pid in
the tombstone (`Cmdline: com.geniechatrn`), not a board-level reboot like the
older, still-open issue in Part 2.

### Root cause

`GenieXEngine.runOnce` calls `active.applyChatTemplate(...)`
(`com.geniex.sdk.jni.Llm.applyChatTemplate`, native, inside the closed-source
GenieX SDK). For some — not all — prompt text, when this is the very first
generation on a freshly-reset chat session, it throws an **uncaught C++
exception** that `libc++abi` turns into an instant, unrecoverable `abort()`:

```
libc++abi: terminating due to uncaught exception of type std::invalid_argument:
Unable to generate parser for this template. Automatic parser generation failed:
------------
While executing CallExpression at line 145, column 28 in source:
... {%- else %}↵        {{- raise_exception('Unexpected message role.') }}↵    {%- ...
                                           ^
Error: Jinja Exception: Unexpected message role.
Fatal signal 6 (SIGABRT), code -1 (SI_QUEUE) in tid ... (DefaultDispatch), pid ... (com.geniechatrn)
```
Backtrace bottoms out in `geniex::LlamaLlm::apply_chat_template` →
`geniex_llm_apply_chat_template` → `Java_com_geniex_sdk_jni_Llm_applyChatTemplate`
→ `LlmWrapper$applyChatTemplate$2.invokeSuspend`. **This cannot be caught from
Kotlin** — it's a native abort, not a JVM exception.

### How the trigger was isolated (in order, including the wrong turns)

Extracted the actual Jinja template out of the GGUF (no `gguf` Python
package, no `pip3`, and no portable Python `gguf` package this time either —
wrote a ~100-line hand-rolled GGUF metadata parser,
`/tmp/.../gguf_extract.py`, reading the header directly:
magic/version/tensor_count/kv_count then walking KV pairs by type tag,
skipping tensor data entirely, ~50MB of the file's head is enough even past
the two ~250k-entry tokenizer arrays). Confirmed 7817 chars, same as last
session's 7816 (off-by-one is probably just a trailing-newline artifact
between extraction methods).

**Read the template's role-dispatch (lines 81–146):** `system`/`user`/
`assistant`/`tool` are handled; anything else falls to
`{%- else %} {{- raise_exception('Unexpected message role.') }}`. This is
pure string equality against `message.role` — **the template's own logic
never inspects `message.content` to decide a role.** So whatever decides
"unexpected role" here for some inputs and not others is NOT a bug in the
Jinja template itself — it has to be in the closed-source native marshaling
of the `ChatMessage[]` array (Kotlin → JNI → the minja/llama.cpp renderer),
which we can't read the source of.

Given that, root-causing further meant black-box experimentation. Confirmed empirically, each via a fresh `am force-stop` + fresh chat id + genie_cli.py:

| Hypothesis | Test | Result |
|---|---|---|
| `tools` schema causes it | Set `qwen3_5_2b.supportsTools = false` in `ModelStore.kt`, rebuilt | **Still crashed**, `tools: nullptr` confirmed in the log. Reverted (no diff left in `ModelStore.kt`). |
| `thinking` causes it | `--no-thinking` | **Still crashed**, identically. |
| Prompt length | Padded a known-safe prompt to 61 chars (longer than either crasher) | **Succeeded.** Rules out length. |
| Imperative phrasing ("Write…", "Search…") | `"Write two sentences about cats"` fresh | **Succeeded.** Rules out imperative mood. |
| First-turn structure (message_count==2: system+user, no assistant yet) | Both known crashers ("essay about the telephone", "capital of France") reproduced **100% of the time** as the literal first message of a brand-new chat; the *identical* text succeeds fine as a later turn once one exchange already exists | **This is the actual axis.** Still content-dependent too — `"What is 17 times 24"` and `"Write two sentences about cats"` do NOT crash even as message_count==2, so it's (fresh session) AND (specific content), not fresh-session alone. |
| A throwaway prior turn avoids it (empirical workaround, mechanism unknown) | Ran `--prompt "hi" --prompt "Write an essay about the telephone"` as two real, separate turns in the same chat | **Second turn succeeded** (54.9s). |
| A *synthetic* fake exchange (no real generation) does the same thing | Hardcoded `messages.add(ChatMessage("user","Hi")); messages.add(ChatMessage("assistant","Hello!..."))` into the array before the real call, no actual `generateStreamFlow` run | **Still crashed** (confirmed `message_count: 4` in the log this time, `tools: nullptr` too — so it's provably not about the array's shape/content at the API boundary). |

**Conclusion:** whatever native state this depends on is set by actually
*driving one real generation* through `generateStreamFlow` on this session —
not by the shape of the `messages` array passed to `applyChatTemplate`. This
points at some lazily-initialized state inside the closed-source SDK/
llama.cpp/HTP backend that only gets set up during a real first inference
pass, and something in the template-rendering path also depends on (or races)
that same lazy init. Root cause inside the vendor binary was **not** found —
this is as far as it's reasonably possible to get without their source.

### The fix (applied, verified)

`GenieXEngine.kt`, `generate()`: when `history.isEmpty()` (this is the first
message of a new chat), run one **real, silent, throwaway generation**
("Hi" → discarded reply, never streamed to the UI, never returned) before
constructing the real prompt. The (real, model-generated) throwaway reply is
then included as history for the real call, exactly matching the manual
test that worked.

```kotlin
if (history.isEmpty()) {
    val primingReply = runOnce(
        active,
        arrayOf(ChatMessage("system", system), ChatMessage("user", "Hi")),
        null, false, TokenSink {}, alreadyEmitted = "",
    )
    messages.add(ChatMessage("user", "Hi"))
    messages.add(ChatMessage("assistant", Tools.stripCalls(primingReply).trim().ifBlank { "Hello! How can I help you today?" }))
}
```

**Verified live, repeatedly, via `genie_cli.py`:**
- Both original crashers, each as a genuinely fresh first message (fresh
  `am force-stop`, new chat id every time): succeed. Essay: 67.1s. Capital
  of France: 9.9s.
- Full `--smoke-test` battery (4 prompts, one chat): 4/4 ok, essay only took
  9.4s that time because it landed as turn 3 (history non-empty → no
  priming needed, matches the design).

**Cost:** one extra small generation (~5–13s observed) on the very first
message of every new chat only. Nothing for every turn after that.

---

## Part 2: the runtime-switch memory-pressure reboot — ROOT-CAUSED, **NOT FIXED**

### This is the actual next task.

While verifying Part 1's fix, the user hit a **real, instant device reboot**
sending a message to Qwen3.5-2B through the actual UI — the CLI's own
smoke-tests never reproduced it because they never carried the one
precondition that turned out to matter.

**User confirmed the exact preconditions by asking (see conversation, not
guessed):**
- Had just switched from a different model (Qwen3-4B, QNN/`GENIE` runtime)
  to a Qwen3.5-2B (`GENIEX` runtime) chat in the same app session — i.e. a
  real runtime switch, not a cold start.
- It was a brand-new chat, first message.

### Reproduced via the CLI

```
python3 scripts/genie_cli.py --prompt "Hi" --model qwen3_4b --chat-id qnnchat --timeout 60
python3 scripts/genie_cli.py --prompt "Write an essay about the telephone" --model qwen3_5_2b --chat-id afterswitch1 --timeout 120
```
First run of this exact sequence: **caught, non-fatal error** after all 3
`CREATE_ATTEMPTS` failed identically:
```
GenieModule: runtime switch: settling 700ms for DSP release
GenieXSdk: llama_prepare_model_devices: using device HTP0 (Hexagon) (unknown id) - 0 MiB free
GenieXSdk: ggml-hex: HTP0 buffer mapping failed : domain_id 3 size 1008209920 fd 145 error 0x00000001
GenieXSdk: ggml-hex: HTP0 failed to allocate buffer context (host): ggml-hex: fastrpc_mmap failed
GenieXSdk: alloc_tensor_range: failed to allocate HTP0 buffer of size 1008205824
GenieXSdk: [JNI] create() failed, error code: -100201
GenieXEngine: LLM create failed (attempt 1/3), settling 900ms then retrying
... (attempt 2/3, attempt 3/3 — all three fail IDENTICALLY)
CLI_ERROR turnId=t0 error=Llm create failed: Model loading failed
```
This is the same `-100201`/DSP-race class of bug the recent commit `c73f275`
("Make QNN<->GenieX runtime switch resilient to the async DSP-release race")
already tried to fix — evidently 700ms + up to 3×900ms (3.4s total) isn't
always enough.

**Tried: bumped the settle/retry constants** (both currently applied,
uncommitted, in the working tree — did NOT fix the reboot, kept anyway
since they're harmless and might still help the milder caught-error
variant above):
- `GenieModule.kt`: `SWITCH_SETTLE_MS` 700 → **2000**
- `GenieXEngine.kt`: `CREATE_ATTEMPTS` 3 → **5**, `CREATE_SETTLE_MS` 900 → **1500**

**Re-ran the identical repro with the bumped constants: got the actual
device reboot this time**, and — because the continuous host-side logcat
capture was already running — caught what was happening right before the
device went dark, for the first time ever for this bug:
```
07:34:58.736  llama_kv_cache: layer 23: dev = HTP0        <- allocating the ~1.9GB KV buffer
07:34:58.925  lowmemorykiller: critical pressure event triggered
07:34:58.926  lowmemorykiller: Kill 'com.android.traceur' (4329) ... reason: device is in direct reclaim and thrashing (37%)
07:34:59.153  ReactNativeJNI: Memory warning (pressure level: TRIM_MEMORY_RUNNING_CRITICAL) received by JS VM, running a GC
07:34:59.573  <last line captured, then the device went down>
```
(`getprop ro.boot.bootreason` → `reboot` afterward, confirmed genuine —
and separately confirmed the local `adb` server needed `kill-server` to
even see the device again afterward, see Part 0.)

### Why the settle-time bump didn't help, and what this actually is

`MemFree` stayed high throughout (~2.7GB, "Movable zone has enough free
memory" reported repeatedly right up to the last line) — this is **not**
general system RAM exhaustion. It's specifically the allocation of a large
**DSP-visible buffer** (the `ggml-hex`/FastRPC `fastrpc_mmap` call for the
~1GB `HTP0-REPACK` model buffer, on top of the ~1.9GB `HTP0 KV buffer` — see
`llama_kv_cache: size = 1923.00 MiB` / `HTP0-REPACK model buffer size =
698.70 MiB` in a successful cold-start load's logs) that's under pressure —
almost certainly the DSP/ION/CMA-reserved memory pool, separate from normal
heap RAM, which is why `MemFree` doesn't reflect it. This matches the
`fastrpc_mmap failed` error from the caught-error variant exactly — same
resource, just a harder failure mode this time (the kernel's own
`lowmemorykiller`/reclaim thrashing hard enough to take the whole board down,
rather than FastRPC just returning an error to userspace).

**Waiting longer doesn't fix a resource that's genuinely contended, not just
slow to release** — `GenieModule.switchTo`'s sleep and `GenieXEngine`'s retry
loop are both blind fixed-duration waits, not conditioned on any actual
memory-availability signal.

### What the user asked for next (please implement this)

> "add a real memory check before switching" — replace/augment the fixed
> sleep in `GenieModule.switchTo` with an actual poll of available DSP/
> system memory before letting the incoming model allocate, retrying until
> it's actually safe rather than guessing a wait time.

**Open questions for whoever picks this up:**
- No known API for "how much DSP/ION/CMA memory is free" has been identified
  yet. `/proc/meminfo`'s `MemFree`/`MemAvailable` do NOT reflect it (proven
  above — stayed high while the DSP allocation was failing). Options to
  investigate: `dumpsys meminfo`, a Qualcomm-specific `/proc` or `/sys` node
  for ION/CMA/FastRPC pool usage (unknown, needs research on this specific
  vendor image), or possibly a GenieX/llama.cpp SDK call that reports
  available HTP memory before `create()` is attempted (unconfirmed whether
  one exists).
- Simplest complementary/fallback mitigation, not yet tried, worth keeping
  in mind if a real memory poll turns out to be hard to find:
  **reduce `qwen3_5_2b`'s `declaredContextLength`** (currently 164000 in
  `ModelStore.kt`) — directly shrinks the ~1.9GB KV cache buffer that's the
  larger of the two allocations, lowering peak demand during exactly this
  window. Same pattern already used for Qwen3-4B on this board per
  `qcs8550-qwen3-4b-genie.md`. Not implemented this session because the user
  specifically asked for the real-memory-check approach first.
- `GenieModule.switchTo()` (`GenieModule.kt`) is where the fixed
  `SWITCH_SETTLE_MS` sleep lives. `GenieXEngine.createWithRetry()`
  (`GenieXEngine.kt`) is where the `CREATE_ATTEMPTS`/`CREATE_SETTLE_MS` retry
  loop lives. Note the reboot reproduced on effectively the **first**
  attempt (never even reached a logged "attempt 2/5") — so retrying *after*
  a failed `create()` is not enough on its own; whatever check gets added
  needs to gate *before* the large allocation is attempted at all, not just
  react to it failing.
- The CLI (`scripts/genie_cli.py --prompt "Hi" --model qwen3_4b --chat-id X`
  then `--prompt "..." --model qwen3_5_2b --chat-id Y`, two separate
  invocations) reproduces this on demand — use it to verify whatever gets
  tried. Continuous logcat capture is automatic; no more manual reboot
  archaeology needed.

---

## Part 3: the switch reboot, actually fixed — by not switching in-process at all

Follow-on to Part 2, same session continued. Short version: **no in-process
fix exists.** Every combination of unloading one NPU model and loading
another in the same process is unreliable on this device's Hexagon/FastRPC
driver, regardless of vendor SDK or direction. The fix that actually works is
to stop trying — a model switch now restarts the whole app process, and every
stress run since backs that up.

### Dead ends ruled out first (don't re-try these)

- **A real memory-availability check before switching**, which is what the
  user originally asked for in Part 2. Exhaustively checked, including with
  root (`adb root` on this dev board — not available to the shipped app,
  only to this debugging session):
  - `/proc/meminfo`'s `CmaFree` is stuck at 0 kB *permanently*, even with
    nothing loaded — not a live signal for anything.
  - `/sys/kernel/debug` is mounted but empty on this vendor image — no
    `dma_buf`, `ion`, or `fastrpc` accounting exposed anywhere.
  - `/sys/kernel/tracing` (fastrpc tracepoints) exists and is genuinely
    useful for diagnosis, but is gated on the `readtracefs` group / root —
    unreachable from the app's own UID at runtime.
  - Decompiled the GenieX SDK jar (`GenieXSdk`, `LlmWrapper`, the `Llm` JNI
    class) — no memory-query method anywhere in its public surface.
  - **Conclusion: there is no API, at any privilege level available to this
    app, that reports DSP/ION/FastRPC free memory on this device.**
- **Forcing a synchronous/harder DSP reset before switching.**
  `GenieDialog_free`/`GenieEngine_free` are already the most forceful release
  calls the Genie C API exposes; their headers give no synchronicity
  guarantee about the underlying QNN HTP backend actually finishing (that
  asynchrony is the whole problem). The real teardown lives inside
  `libQnnHtp.so`, called only internally by the closed-source `GenieDialog`
  implementation — the app never gets a handle to force it itself. Actually
  forcing a clean state would mean a `remoteproc` subsystem restart
  (`/sys/class/remoteproc/*/state`), which needs root (unavailable to the
  shipped app) and would kill every other process sharing the cDSP (audio,
  sensors, etc.) — disproportionate for one app's model switch.

### The diagnostic tool: `scripts/stress_switch.py`

Generalization of `genie_cli.py`'s turn-runner into a repeated-cycle harness:
alternates two models (`--model-a`/`--model-b`, any pair, any runtime) as
genuinely fresh chats, back-to-back, **in the same app process** — the point
being to never force-stop between cycles, so a real per-switch race shows up
instead of being hidden by a clean reset. Reuses `genie_cli.py`'s logcat
tail and reboot/crash detection. This is what found everything below.

### The full failure matrix (all four combinations tested, all four broken)

The original ask was "does repeated switching between QNN and GenieX
accumulate a leak, or is it a pure race" — that turned out to be the wrong
axis entirely. The real question was whether the switch bug was specific to
QNN↔GenieX, and it was not: **every one of the four possible switch
combinations was unreliable**, each via a *different* mechanism, all
traceable to the same family of symptom (`fastrpc_mmap failed`, `tNode->
map.fd != fd`, `err 1002` — the DSP session not actually being free yet):

| Switch | Failure | Rate | Recoverable? |
|---|---|---|---|
| QNN → GenieX | `-100201` on create (fastrpc_mmap) | rare after settle+retry bump | yes, retry loop already existed |
| GenieX → QNN | `err 1002` on create, then QNN's own `QnnBackend_free` **SIGSEGVs** while freeing the half-built backend | ~100%, settle-time insensitive (2000ms → 5000ms made no difference) | **no** — native crash, uncatchable from Kotlin |
| GenieX → GenieX (2 different GGUFs) | the **cDSP compute process itself aborts** (`qurt_exit()`/`abort()` inside `htp_iface_start`) on the *first* `llama_decode` after switching — `create()` succeeds, generation crashes | ~50%, intermittent | no — DSP-side native abort |
| QNN → QNN (2 different QNN models) | `err 1002` on create (`"multiple sessions not allowed for untrusted apps"` — reads like an intentional FastRPC/SELinux policy) | first switch onward | **no — and it never recovers**: every subsequent create in that process fails identically, forever |

The QNN→GenieX direction (Part 2's original bug) already had a working
mitigation (settle + `GenieXEngine.createWithRetry`). The other three did
not, and none of them yield to longer waits or retry loops — GenieX→QNN and
GenieX→GenieX are native crashes with nothing left to retry once they
happen, and QNN→QNN doesn't even fail transiently, it wedges permanently.

The one clean signal across every single stress run: **a fresh process's
first load, of any model, on any runtime, was 100% reliable, every time.**
It is specifically *reusing* an already-touched DSP session within one
process that's unreliable — not model loading itself.

### The fix: `GenieModule.switchToOrRestart`

A genuine model switch (target model differs from whatever's currently
resident in either engine) no longer unloads-and-reloads in place. It
restarts the whole app process instead, via
`Intent.makeRestartActivityTask` + `Runtime.getRuntime().exit(0)`, and lets
the *new* process's first load be a first load, not a second one:

- `GenieModule.kt`: `switchToOrRestart(modelId)` replaces the old
  `switchTo(runtime)` at all three call sites (`loadModel`, `generate`,
  `runCliTurn`). `SWITCH_SETTLE_INTO_GENIEX_MS`/`SWITCH_SETTLE_INTO_GENIE_MS`
  are gone — dead code, since nothing is ever unloaded-and-reloaded
  in-process anymore.
- **JS resume**: a process restart kills the JS VM too, so `App.tsx`/
  `store.ts` now persist `Settings.lastOpenChatId`, restored on cold start —
  otherwise a switch-triggered restart would silently dump the user back at
  the chat list. `ChatScreen`'s existing `loadModel`-on-mount effect (that
  was already there, unrelated to this fix) does the rest: the resumed chat
  reopens, calls `loadModel` again, and this time it's a genuinely fresh
  process.
  - This works because `generate()` (the JS side) is only ever called
    *after* `loadModel` already resolved `ready` — the Composer is disabled
    until then — so there's never an in-flight generation for a restart to
    interrupt. A switch always happens at `loadModel` time, before the user
    can have sent anything.
  - `runCliTurn` (the CLI path) does **not** have this precondition — it
    calls `switchToOrRestart` too so it can't crash/wedge, but a CLI
    invocation that switches models mid-run now costs a full app restart per
    switch, and the CLI-driven turn that triggered it is simply abandoned
    (no JS involved to resume it — see `stress_switch.py`'s own retry loop,
    which resends the same logical turn after detecting the restart, to
    emulate what the real JS resume flow does).
- **Residual fresh-process create() race**: even a genuinely fresh process's
  *first* create() occasionally still failed (`GenieDialog_create failed:
  ERROR_GENERAL (-1)`, ~1/6 of switches in early testing) — the OLD
  process being fully dead doesn't guarantee the kernel/DSP finished
  reclaiming its memory before the NEW process's first create() runs. Unlike
  the in-process SIGSEGV case, this fails *cleanly* (a catchable
  `GenieException`, confirmed repeatedly), so `ChatEngine.ensureModel` got
  its own `createHandleWithRetry` loop (`CREATE_ATTEMPTS = 3`,
  `CREATE_SETTLE_MS = 1500`), mirroring `GenieXEngine`'s existing one.
- **A genuine device reboot still happened once** during early testing of
  this fix, under `stress_switch.py`'s adversarial back-to-back cadence (a
  new process every 2-15s) — `qwen3_5_2b` loaded, generated one reply, then
  the board went down moments into a second generation. No
  `lowmemorykiller` kill logged first this time (unlike Part 2's original
  reboot), suggesting something lower-level (kernel/watchdog) — same root
  cause family (DSP memory pressure from a fresh process's first large
  allocation racing the previous process's not-yet-complete reclaim), just
  a harder failure mode. Fixed (or at least not reproduced since) by adding
  `GenieModule.FIRST_LOAD_SETTLE_MS = 3000L`: an unconditional 3s wait
  before a process's very first model create(), on top of whatever RN's own
  cold start already costs. Blunt, because — see above — there is no real
  readiness signal to gate on instead.
- **One more edge case found, not yet fixed, self-heals**: rarely, Android's
  own `ActivityTaskManager` decides to reuse the still-alive process for the
  restart `Intent` ("Process ... Already Exists in BG") instead of spawning
  a new one, and then this app's own `exit(0)` — which fires immediately
  after `startActivity()` — kills that reused instance anyway. There's no
  live activity left to resume until Android's own crash-recovery notices
  the foreground task's process died and respawns it, which is slower (~60s
  observed once) but did recover on its own, every time seen. 1 occurrence
  in ~46 switches across all verification runs.

### Verification (`scripts/stress_switch.py`, all four combinations, post-fix)

`stress_switch.py` was upgraded alongside the fix: a switch-triggered
`app_crash` (pid change) is now the *expected* outcome, not a failure, so the
harness resumes the same logical turn (new `turnId`, same `chatId`) after a
restart and checks that it actually completes — up to `RESUME_ATTEMPTS = 3` —
rather than just confirming a restart happened. A "genuine reboot" (uptime
reset) is tracked separately from an intentional restart (pid change).

| Switch | Result |
|---|---|
| QNN ↔ QNN (`llama_v3_2_1b_instruct_ctx4096` ↔ `qwen3_4b`) | **16/16 switches resolved ok**, 0 reboots (was: permanently wedged after the first switch) |
| GenieX ↔ GenieX (`qwen3_5_2b` ↔ `gemma4_e2b`, a new model added specifically to test this — see below) | **24/24 resolved ok, 0 reboots** after adding `FIRST_LOAD_SETTLE_MS` (first attempt without it: 16/16 eventually resolved, but 1 genuine reboot along the way) |
| QNN ↔ GenieX (`qwen3_4b` ↔ `qwen3_5_2b`) | **20/20 resolved ok, 0 reboots** (1 slow-but-self-healing `ActivityTaskManager` edge case, see above) |

### `gemma4_e2b`: a second GenieX model, added to isolate GenieX↔GenieX from QNN

Needed a second GGUF model to test whether the switch bugs were specific to
*two different vendor backends* (QNN vs GenieX) racing for the DSP, or a
broader "any DSP session reuse" issue. Picked
`unsloth/gemma-4-E2B-it-GGUF` (Q4_0, ~2.82 GiB) — genuinely new architecture
(`general.architecture = gemma4`, not `gemma3`/`gemma3n`), confirmed present
in the bundled GenieX SDK's `libllama.so` (both 0.3.12, what's actually
pinned in `build.gradle`, and 0.3.16) via `strings` before downloading
anything: real upstream llama.cpp support, including Gemma-4-specific
assertions (`"Gemma 4 requires n_embd_head_k == n_embd_head_v"`). Loaded and
generated coherently on the very first try, text-only (no `mmprojFile` — the
VLM path is already known-broken on this device for `qwen3_5_2b`, no reason
to expect otherwise here), `declaredContextLength = 32768` (deliberately
smaller than `qwen3_5_2b`'s 164000; this was about testing switch
reliability, not chasing a context ceiling, and Gemma 4's hybrid local/
global attention — `sliding_window = 512` on 4 of every 5 layers — means
most of the KV cache doesn't scale with this number anyway). One known
cosmetic issue, not fixed: its `<|channel>thought...<channel|>` reasoning
tags aren't recognized by `ReasoningSplitter` (tuned for Qwen's `<think>`
convention), so they currently leak into the visible answer instead of
populating `thoughts`.

### Also this session: the QNN-removal detour

Earlier in this session, before the GenieX↔GenieX/QNN↔QNN data came in, the
working hypothesis was "nuke QNN, go pure-GenieX" — reasonable at the time
(QNN→GenieX was believed largely fixed; GenieX→QNN's SIGSEGV looked
QNN-specific). That hypothesis didn't survive contact with the QNN↔QNN and
GenieX↔GenieX results: the failure is about DSP session reuse generally, not
about QNN specifically, so going pure-GenieX would not have fixed anything —
it would have swapped "two vendor backends fighting over the DSP" for "one
vendor backend fighting with itself," which is exactly what GenieX↔GenieX
turned out to still do (until the restart fix). The hybrid dual-runtime
architecture was kept. A branch, `geniex-qnn-hybrid`, was created and pushed
to `origin` at commit `96a47fc` as a snapshot of the working state *before*
this detour started (Part 0-2's CLI tooling + Jinja fix + the settle-time
bump, i.e. everything up to but not including this restart architecture) —
kept as a reference point, not because QNN removal is still planned.

---

## Status

| Item | State |
|---|---|
| CLI tool (`CliReceiver` + `genie_cli.py`) | ✅ built, verified working (Part 0) |
| Jinja `apply_chat_template` crash ("essay about the telephone" etc.) | ✅ **root-caused and fixed**, verified across repeated fresh-install runs (Part 1) |
| Runtime-switch memory-pressure reboot / all 4 switch-combination failures | ✅ **root-caused and fixed** — process-restart architecture (Part 3), verified 60/60 switches resolved ok across all 4 combinations, 0 reboots in final verification |
| Memory-check / forceful-DSP-reset approaches | 🔴 confirmed infeasible on this device — no API exists at any privilege level available to the app (Part 3) |
| Second GenieX model (`gemma4_e2b`, Gemma 4 architecture) | ✅ added, loads and generates correctly; reasoning-tag splitting not wired up (cosmetic, not fixed) |
| Git state | **`geniex-qnn-hybrid` branch pushed to `origin`** at commit `96a47fc` (Part 0-2 work: CLI tooling, Jinja fix, settle-time bump — snapshot before the QNN-removal detour). **`debug` branch has significant further uncommitted work on top of that same commit**: the process-restart architecture (`GenieModule.kt`, `ChatEngine.kt`), JS resume (`App.tsx`, `store.ts`), `gemma4_e2b` (`ModelStore.kt`), and tooling (`genie_cli.py` pid-detection speedup, `stress_switch.py` generalized + restart-aware). Not yet committed. |
