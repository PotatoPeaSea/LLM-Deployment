# Handoff: GenieX reasoning display + web_search + tool-call parsing — session ended in full revert, reboot still unexplained

Date: 2026-07-24 (session 3). Follow-on to `HANDOFF-qwen-text-164k.md` (which
got text generation itself working, committed as `2993d10`). **End state:
the working tree was reverted to `2993d10` in full — none of this session's
code changes remain applied.** That's a deliberate outcome, not an
interruption: the board kept rebooting even after undoing every candidate
cause this session found, so the safest thing to hand off is the last known
commit plus a complete account of what was tried, in case any of it is worth
re-attempting once the actual root cause is known. Read this whole doc before
touching the code again.

App: `genie-on-device/app-rn`, package `com.geniechatrn`, board QCS8550.
Memory: `geniex-android-sdk-rn-app.md`, `qcs8550-qwen3-4b-genie.md`.

---

## What this session aimed to do

Two things the user reported, both about Qwen3.5-2B (GenieX/GGUF runtime):
1. **Reasoning displayed as a normal reply** instead of collapsing into the
   app's existing "▸ Thoughts" disclosure (which already worked correctly for
   the other model, Qwen3-4B on QNN).
2. **`web_search` stalled for ~24s** on boards with no Wi-Fi before giving up,
   making the whole turn look frozen.

## What this session actually did

Fixed both, and in the process of verifying the reasoning fix live, found and
fixed two more real bugs in the same tool-calling code path (detailed below).
Then discovered that with tool calls now actually able to execute, the board
started rebooting whenever one did. Spent the rest of the session trying to
isolate that reboot: cut the context window, then reverted the two newest
fixes (tool-call parsing + the resulting streaming/splitter fix), then — when
the user reported it rebooted even after that revert — reverted everything
else too. **The reboot was never root-caused.** It is a genuine full device
reboot (`adb shell getprop ro.boot.bootreason` → `reboot`, confirmed via
`uptime` showing ~0-2 min after it happened), not an app-level crash, and by
the end of the session it was reproducing with essentially none of this
session's code active — see "Final finding" below.

---

## Bug 1 — web_search stalls ~24s with no network (fix written, then reverted with everything else)

**Symptom:** on a board with no Wi-Fi, asking the model to search the web
made the whole turn look frozen for up to ~24s before it gave up.

**Root cause:** `WebSearchTool.run()` always attempted up to three sequential
HTTP GETs (DuckDuckGo instant answer, then Wikipedia search, then Wikipedia
summary), each with an 8s `connectTimeout`/`readTimeout`. With no network,
every one hangs to its full timeout before failing.

**Fix that was written** (`WebSearchTool.kt`, `AndroidManifest.xml`):
`hasInternet(context)` checked `ConnectivityManager.activeNetwork` +
`NetworkCapabilities.NET_CAPABILITY_INTERNET`/`NET_CAPABILITY_VALIDATED`
up front and returned immediately with `"No internet connection on this
device right now -- can't search the web."` if there was no real connection.
Needed `android.permission.ACCESS_NETWORK_STATE` (normal permission, no
runtime prompt).

**Verified live (2026-07-24):** this board genuinely has no network —
`adb shell dumpsys connectivity` showed `Active default network: none`. Sent
"Please use web search to look up who invented the telephone"; logcat showed
the tool call resolve in ~13ms with the no-internet message, no timeout
stall. This part worked correctly every time it was tested, including in the
session's very last (still-rebooting) round — the network check itself was
never implicated in the reboot.

---

## Bug 2 — reasoning shown as a normal reply, not in the Thoughts dropdown (fix written, then reverted with everything else)

**Root cause, confirmed by extracting the actual GGUF metadata**
(`workspace/gguf/qwen3_5_2b/Qwen3.5-2B-Q4_0.gguf`, key
`tokenizer.chat_template`, 7816 chars): when `add_generation_prompt` is true,
the template ends with
```jinja
{%- if add_generation_prompt %}
    {{- '<|im_start|>assistant\n' }}
    {%- if enable_thinking is defined and enable_thinking is true %}
        {{- '<think>\n' }}
    {%- else %}
        {{- '<think>\n\n</think>\n\n' }}
    {%- endif %}
{%- endif %}
```
i.e. **the opening `<think>\n` is baked into the PROMPT itself**, not left
for the model to generate — different from Qwen3's official template and from
what `ChatEngine`/`ChatTemplate.Qwen3` does for the QNN model. Because of
this, the token stream (`LlmStreamResult.Token` — confirmed via `javap` on
the SDK's `classes.jar`: bare `text: String`, no separate reasoning field)
never contained a literal `<think>` opening tag, so `ReasoningSplitter`
(which keys off that literal string) never split anything and the whole raw
stream landed in the visible answer.

**Fix that was written** (`GenieXEngine.kt`, `runOnce`): seed the local `raw`
accumulator with `"<think>\n"` when `thinking` is on, mirroring what the
prompt already committed to.

**Verified live (2026-07-24):** "What is 17 times 24" with Reasoning on →
clean "▾ Thoughts" disclosure containing the reasoning, separate answer
bubble "17 × 24 = 408". Screenshot confirmed visually on-device. This is the
"reasoning bubble working ok" checkpoint referenced in this session's
mid-point revert (superseded by the full revert at the very end — see
"Final finding").

---

## Bug 3 — tool calls silently dropped: wrong format assumed (fix written, then reverted with everything else)

Found while testing Bug 2: asked "What is 17 times 24" with Reasoning on:
model correctly reasoned *"I don't need to use any tools"* and then, on the
very next line, emitted a tool call anyway (small-model self-contradiction,
not itself a bug) — but the visible bubble showed **only** the reasoning
text, no answer, no "used web_search" tag, and no tool ever actually ran.

**Root cause:** `Tools.kt`'s doc comment and `parseCalls()` assumed Qwen3.5's
template renders tool calls Hermes-style: `<tool_call>{"name": "...",
"arguments": {...}}</tool_call>`. **That's wrong for this GGUF.** Its actual
embedded template (same extraction as Bug 2) instructs — and the model
produces, confirmed in logcat — a completely different, Llama-3.1-style tag
format:
```
<tool_call>
<function=web_search>
<parameter=query>
who invented the telephone
</parameter>
</function>
</tool_call>
```
`JSONObject(...)` on that body throws, `parseCalls()` silently returns no
calls (by design — "tolerant on purpose"), and `GenieXEngine.generate()`
treats the whole thing as unparseable prose. **Tool calling had effectively
never worked for this model** since the switch to `LlmWrapper` (commit
`2993d10`) — every attempted call was silently swallowed, and no one had
noticed because a silently-dropped tool call just looks like "the model
answered from its own knowledge instead," not like an error.

**Fix that was written** (`Tools.kt`): `parseCall()` tried JSON first (cheap
`startsWith("{")` check, kept as a defensive fallback) and otherwise parsed
the real `<function=name><parameter=x>value</parameter></function>` form via
regex (`DOT_MATCHES_ALL` so multi-line parameter values still match).

**Verified live (2026-07-24):** same "invented the telephone" prompt →
logcat: `tool web_search({"query":"who invented the telephone"}) -> No
internet connection...`, and the UI showed `26.3s · used web search` under
the final answer. **This was the first time in the app's history a GenieX
tool call executed end-to-end.** Every previous "tool calling verified" note
in `HANDOFF-qwen35-2b.md` predates the `LlmWrapper` switch and doesn't apply
to this code path.

---

## Bug 4 — multi-turn tool loop garbles/leaks `<think>` tags (fix written, then reverted with everything else)

Found immediately after fixing Bug 3: with the tool call now actually
executing, the turn does reasoning → tool call → reasoning again (two
separate `runOnce` generations in one user turn). The result rendered with a
literal `</think>` leaking as visible text at the top of the answer bubble.

**Root cause A** (`GenieXEngine.runOnce`): the streaming-delta logic
```kotlin
val raw = StringBuilder(...)
var emitted = alreadyEmitted   // <- previous iteration's TOTAL visible length
...
if (clean.length > emitted.length) sink.onToken(clean.substring(emitted.length))
```
compares `clean` (this iteration's own text, always restarting from empty
each `runOnce` call) against `emitted`, seeded on iteration 2+ from
`alreadyEmitted` — the *previous* iteration's cumulative visible length.
Those two strings are unrelated; `clean.substring(emitted.length)` sliced
into iteration 2's own text at a foreign offset, and whatever landed there
(often mid-tag) got sent to the UI. This bug predates this session — Bug 2's
fix just gave it a constant 8-char shift, which happened to make the
corruption land exactly on `</think>` instead of somewhere else silently.

**Root cause B** (`ChatTemplate.kt`, `ReasoningSplitter`): even with A fixed,
the splitter only ever looked at the *first* `<think>...</think>` pair. A
reasoning → tool-call → reasoning turn produces two pairs concatenated back
to back; the second pair's literal tags would leak into `answer`.

**Fix that was written:** removed the `alreadyEmitted` parameter (`emitted`
starts at `""` every `runOnce` call — correct because the caller's
`TokenSink`/`ReasoningSplitter` in `GenieModule` already accumulates every
call's fragments additively). Generalized `ReasoningSplitter` to walk every
`<think>...</think>` pair, not just the first.

**Status: code-verified (compiled clean, logic checked by hand against the
exact transcript from the Bug 3 repro), never confirmed live.** The board
started rebooting on nearly every subsequent attempt before a clean
before/after screenshot could be captured.

---

## The reboot investigation, in order

1. With Bugs 3+4 live, tool calls actually executed for the first time — and
   the board started rebooting almost every time one did.
2. **Hypothesis 1: OOM at 164K ctx + reasoning + a second generation
   round-trip.** User cut `declaredContextLength` for `qwen3_5_2b` 164K →
   128K directly on-device to test this. **Reboot reproduced at 128K too.**
   Hypothesis weakened — the user's own read was that this doesn't look like
   plain OOM.
3. **Hypothesis 2: something specific to Bugs 3/4's code (the actual
   tool-execution round-trip: a second `applyChatTemplate`/
   `generateStreamFlow` call with a `<tool_response>`-wrapped `user` message
   injected mid-transcript, or the streaming-delta rewrite).** Reverted Bugs
   3 and 4 back to their pre-session form (tool calls silently dropped again,
   `alreadyEmitted` restored, single-block `ReasoningSplitter` restored),
   keeping Bugs 1 and 2. Rebuilt, reinstalled, relaunched.
4. **User reported it rebooted again anyway.** Confirmed via
   `adb shell getprop ro.boot.bootreason` → `reboot` and `adb shell uptime`
   showing ~2 minutes since boot, right after the report — a genuine full
   device reboot, not an app-level crash (no tombstone survives a reboot to
   inspect after the fact; the logcat ring buffer is wiped too). Hypothesis 2
   weakened just as much as Hypothesis 1.

## Final finding

**The reboot was reproducing with Bugs 3 and 4 already reverted** — i.e.
with tool calls silently dropped again and no second `generate()` round-trip
happening. That rules out both leading hypotheses from this session (context
size, and the specific tool-loop code this session touched) as the *sole*
cause, though neither can be fully cleared without a tombstone from the exact
moment of a reboot (never captured — every attempt this session ended with
the buffer already wiped by the time `adb` reconnected). Given that, and
given the user's direct instruction, the whole working tree was reverted to
`2993d10` — including Bugs 1 and 2, which were never themselves implicated in
any reboot. This is the safe, fully-known-good baseline to resume from.

**What's actually still true and worth carrying forward, even though none of
it is applied right now:**
- Bug 1's root cause and fix are solid and independently verified — reapply
  whenever convenient, it was never near the reboot.
- Bug 2's root cause (the GGUF's prompt-side `<think>\n` priming) and fix are
  solid and independently verified live — but note it was *also* present
  during at least one of this session's reboots, so it cannot be fully
  cleared either, even though nothing about it looks causally connected to a
  full device reboot.
- Bug 3's root cause (wrong tool-call tag format assumed) is real and
  confirmed via on-device logcat, independent of the reboot question — tool
  calling is currently, and has apparently always been, non-functional for
  this model on this runtime.
- Bug 4's root causes (both A and B) are real, mechanical bugs, confirmed by
  hand against an actual captured transcript — independent of whether they
  have anything to do with the reboot.

**Recommended next step for whoever picks this up:** before re-applying any
of these fixes, get a tombstone or full kernel log (`dmesg` if accessible, or
`adb logcat -b all -G 16M` piped to a file continuously in the background,
started *before* the triggering turn) spanning the exact moment of a reboot.
Without that, every fix in this doc is a plausible-but-unconfirmed lead, not
a diagnosis — this session tried to bisect by reverting code and never
actually caught the failure in the act. Prior handoffs
(`HANDOFF-qwen-text-164k.md`, `HANDOFF-qwen35-2b.md`) already document this
board rebooting unpredictably under memory/NPU load independent of app code;
this session's reboots may simply be more of that, now happening to
correlate with reasoning/tool-use turns because those are the heaviest
generations this app runs — or may be something new. Undetermined.

---

## Reference material (still accurate, kept for whoever re-attempts these fixes)

### How the GGUF template was extracted
No `gguf` Python package on this host and no `pip3`. Hand-rolled parser reads
the GGUF header directly (magic/version/tensor_count/kv_count, then walks
key-value pairs by type tag) and pulls the `tokenizer.chat_template` string
key. Wrote it to `/tmp/qwen35_chat_template.jinja` (7816 chars) and read it
directly — that's how Bugs 2 and 3's root causes were confirmed, not
guesswork. `grep -aoc` on the raw `.gguf` file for literal substrings
(`<think>`, `reasoning_content`) is a fast sanity check before doing the full
parse. The `LlmStreamResult` field check was `javap -p -c` on the AAR's
`classes.jar` extracted from `~/.gradle/caches/.../geniex-android-0.3.12.aar`.

### Gotchas encountered again this session (see prior handoffs for the rest)
- Stale-JS-bundle red screen (`Genie.generate got 8 arguments, expected 9`)
  after every reboot/`adb install -r`: `adb reverse tcp:8081 tcp:8081` then
  reload (RELOAD button or `R,R`) — not a real bug, just re-set the reverse.
- The board rebooting drops `adb` entirely for a while; poll
  `adb get-state`/`adb shell getprop sys.boot_completed` rather than
  guessing a sleep duration.
- A different, unrelated, already-documented crash exists too: certain
  prompt content (not tool-related) crashes `apply_chat_template` with an
  uncaught Jinja `std::invalid_argument: Unexpected message role` → SIGABRT
  — see `HANDOFF-qwen-text-164k.md`'s "Follow-up bug" section. "Search the
  web for the capital of France" hits it; that's a documented, separate,
  content-dependent bug, not this session's reboot.

## Status
| Item | State |
|---|---|
| Working tree | ⏪ **Reverted to commit `2993d10`** — no session changes applied |
| Bug 1 — web_search stalls with no network | 📝 root cause + fix documented above, verified live, NOT currently applied |
| Bug 2 — reasoning leaks into visible reply | 📝 root cause + fix documented above, verified live, NOT currently applied |
| Bug 3 — tool calls silently dropped (wrong format assumed) | 📝 root cause + fix documented above, verified live, NOT currently applied |
| Bug 4 — multi-turn tool loop garbles/leaks `<think>` tags | 📝 root cause + fix documented above, code-verified only, NOT currently applied |
| Board reboot when reasoning/tools are used | 🔴 **UNRESOLVED** — reproduced with and without Bugs 3/4 applied; not root-caused; needs a tombstone/kernel log captured live across the actual failure, not more code bisection |
