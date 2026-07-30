# GenieChat on a Qualcomm Ubuntu board

Running the [`app-rn`](../app-rn) chat app on a **QCS8550 board running Ubuntu
22.04** instead of Android. Same chipset as the Android target, same models,
same UI code — a different operating system underneath, which turns out to
change almost everything below the React layer and almost nothing above it.

For the Android app see [ANDROID-RN-APP.md](ANDROID-RN-APP.md) and
[USAGE.md](USAGE.md). This file is the Linux target only.

---

## 1. What this is

```
workstation                                board (QCS8550, Ubuntu 22.04)
-----------                                -----------------------------
browser  ──adb forward 8080──────────────>  app-server (Node 20)
                                              │  supervises
search relay  <──adb reverse 8079─────────    │
   │                                          v
   └─> Exa (api.exa.ai)                    llama-server (llama.cpp)
                                              │
                                              └─> CPU, or Hexagon v73 NPU
```

Three processes. `llama-server` is stock upstream llama.cpp. The **app-server**
is the Android app's Kotlin engine ported to TypeScript — model registry, tool
loop, streaming. The **UI is the same React Native code as the Android app**,
compiled for the browser with react-native-web.

**Scope:** GGUF models only (`qwen3_5_2b`, `gemma4_e2b`). The GENIE/QNN
context-binary runtime, the cloud export pipeline, image input, and the
contacts/calendar tools are all Android-only and not part of this target.

## 2. Why the closed-source SDK was not needed

The Android app gets its GGUF runtime from `com.qualcomm.qti:geniex-android`, a
closed AAR. There is no Linux build of it — and there did not need to be. Its
NPU path is just upstream llama.cpp's Hexagon backend; `app-rn/android/app/build.gradle`
says so outright ("its NPU path is the llama_cpp plugin (libggml-hexagon +
libggml-htp-v73), no QNN library at all"), and the AAR's own native libs confirm
it — `libggml-hexagon.so`, `libggml-htp-v73.so`, `libllama.so`.

Upstream llama.cpp ships an `arm64-linux-snapdragon-release` CMake preset that
builds exactly those. So the AAR is replaced by stock llama.cpp, and the NPU is
still reachable.

## 3. Quick start

```bash
cd genie-on-device/app-rn
bash scripts/deploy-linux.sh --models gguf     # build, push, launch
node scripts/search-relay.mjs 8079             # in another terminal, for web_search
```

`web_search` needs an Exa API key on the workstation: set `EXA_API_KEY` in the
environment, or drop an `EXA_API_KEY = ...` line in a gitignored `secrets.txt`
at the repo root and the relay will pick it up automatically.

Then open **http://127.0.0.1:8080**.

The script is a genuine one-command path from a bare clone: it clones and
cross-compiles llama.cpp, builds the sysroot, compiles the server, bundles the
UI, fetches a Node runtime, pushes all of it plus the models, sets up both adb
tunnels, and starts the app. `--help` lists the options; `--skip-llama` avoids
the slow part once it is built.

Board-side controls:

```bash
adb shell /opt/geniechat/geniechat.sh status    # or stop | restart
adb shell tail -f /tmp/geniechat.log
```

## 4. Measurements

### 4.1 CPU beats the NPU for chat

`llama-bench`, Qwen3.5-2B-Q4_0, on the board:

| | CPU (8 threads) | HTP0 (Hexagon v73) |
|---|---|---|
| prefill `pp64` | 101.5 t/s | **164.5 t/s** |
| decode `tg32` | **21.6 t/s** | 7.0 t/s |

The NPU wins prefill by ~1.6x and loses decode by ~3x. A chat app is
decode-bound — decode is the part the user sits and watches — so **the default
is CPU**, set in `server/llama.ts`. This is a real result on an NPU project and
worth restating plainly: for this workload, on this silicon, with llama.cpp's
current (self-described experimental) Hexagon backend, the CPU is the better
device.

`LLAMA_DEVICE=HTP0`, or `deploy-linux.sh --device HTP0`, switches it. The NPU is
the right choice for a prefill-heavy workload — long documents, short answers.
`GGML_HEXAGON_VERBOSE=1` confirms ops are really dispatching to the DSP:

```
ggml-hex: Hexagon Arch version v73
ggml-hex: HTP0 hwinfo: threads 4, hvx 4, hmx 1, vtcm 8 MB
```

### 4.2 Context length

Set to **8192** for both models in `server/models.ts`, deliberately
conservative. The Android app's 164000 does not carry over: that number was
measured against the GenieX AAR's llama.cpp on Android, where the wall was the
DSP mapping limit (176K loaded, 192K failed in `fastrpc_mmap`). This is a
different build on a different OS, and on CPU the constraint is host RAM
(11.4GB total) rather than the DSP at all. Raise it and measure.

## 5. Three facts about this board that shaped the design

**`/data` is mounted `noexec`.** Binaries cannot run from there. So `/opt/llama`
and `/opt/node` live on `/` (~11GB free) while models live on `/data` (181GB
free) — models are only ever mmapped for reading, which `noexec` permits. A
binary pushed to `/data` fails with a bare `Permission denied` that looks like a
`chmod` problem and is not.

**The board has no network interface.** `ip -br addr` shows loopback and tunnel
devices only — no ethernet, no wifi, no default route. Consequences:

- Nothing can be installed on it, so `deploy-linux.sh` pushes a Node 20 tarball
  rather than using a package manager.
- The browser reaches the UI via `adb forward` (host → board).
- `web_search` reaches the internet via `adb reverse` (board → host) into
  `scripts/search-relay.mjs`, which runs on the workstation and does the Exa
  lookup there (also where the `EXA_API_KEY` lives — it never crosses the
  link). Only the query string crosses the link. Without the relay running,
  `web_search` returns a sentence saying so and the model answers from its
  weights.

**The build needs a matching sysroot.** The Snapdragon toolchain image is Debian
trixie; its glibc headers redirect `strtol`/`sscanf` to `__isoc23_*` symbols
that exist only in glibc ≥ 2.38. The board is Ubuntu 22.04 with glibc **2.35**,
so a stock build links cleanly and then dies at startup with ``version
`GLIBC_2.38' not found``. Pinning `-std=gnu17` does *not* fix it, because the
preset passes `-D_GNU_SOURCE`, which itself implies `_ISOC23_SOURCE`. The fix is
to compile against real 22.04 headers: `deploy-linux.sh` builds an arm64 jammy
sysroot (via qemu/binfmt) and passes `--sysroot`. It then re-checks the output
with `readelf` so the problem cannot quietly return.

## 6. What the port simplified

Moving to llama-server's OpenAI-compatible endpoint handed three hard-won
Android workarounds back to llama.cpp. All three are gone from this codebase:

| Android had to | Here |
|---|---|
| Scrape `<tool_call>{…}</tool_call>` out of the raw completion by hand | `/v1/chat/completions` returns a structured `tool_calls` array |
| Send tool results as a `user` turn wrapped in `<tool_response>`, because the template's `role=="tool"` branch aborted GenieX's minja | Results go back as real `tool` messages |
| Scrape `<think>` / `<\|channel>thought` markers out of the stream, with a per-model flag for whether the opening tag even appeared | llama.cpp separates `reasoning_content` before we see a token |
| Restart the whole app process to switch models, because in-process reload raced FastRPC teardown four different ways | Switching restarts `llama-server`, a separate process |

One thing that did *not* port cleanly is worth knowing: llama.cpp does not paste
your `tools` array into the model's own template. Its chat layer picks a
tool-call **format** and injects matching instructions — for this GGUF an XML
form, `<tool_call><function=name><parameter=query>…`. A literal port of the
Android JSON scraper therefore parsed nothing and leaked raw XML into the chat
bubble. `--jinja` does not change this. Since llama.cpp chooses the format,
llama.cpp also parses it: that is the whole argument for using the OAI endpoint.

## 7. Layout

```
app-rn/
  src/                 UI — shared verbatim with the Android app
  src/genie.web.ts     HTTP/SSE client; replaces genie.ts on the web target
  src/confirm.*.ts     Alert shim (react-native-web has no working Alert)
  server/              the app-server (TypeScript -> dist/)
  web/                 webpack config, entry point, render + interaction tests
  scripts/deploy-linux.sh
  scripts/search-relay.mjs
  scripts/board/geniechat.sh   start/stop/status, as it runs on the board
```

The UI is shared through bundler extension resolution: webpack tries `.web.ts`
before `.ts`, so `App.tsx` and both screens import `'./genie'` and get the
native module on Android and the HTTP client in the browser, with no
conditionals in either. The Android build never sees `server/` or `web/`.

### What the browser target does differently

Three things, all in the shared components, because a keyboard and a mouse are
not a touchscreen:

- **Enter sends, Shift+Enter is a newline.** Web only: on a phone the return key
  is the only way to get a second line. react-native-web hands `onSubmitEditing`
  to single-line inputs only, so `Composer` reads the key itself and has to
  `preventDefault` the newline it was pressed instead of.
- **Typing is never blocked.** A turn written while a reply is generating — or
  while the model is still loading — is queued, shown as a chip above the
  composer, and sent on its own when the model comes free. Press the chip to take
  it back. `ChatScreen` owns the queue; `Composer` only reports the submit,
  since only the screen knows whether the model is free.
- **Tool calls are auditable.** Every call's name, arguments and result sit
  behind a disclosure under the reply, filled in live as the calls run. The
  server sends them in the SSE `progress` frames (`Progress.toolCalls`), which is
  why this works here and not on Android — `GenieModule` still reports only the
  tool *names*.

## 8. Verifying

```bash
cd genie-on-device/app-rn
npm run test:web                          # builds the bundle and renders it in jsdom
npm --prefix server run build             # typechecks the server
curl http://127.0.0.1:8080/api/models     # through the adb tunnel
```

`npm run test:web` is the one that catches a white screen: it loads the real
bundle into a real DOM and asserts the chat list rendered. A build that compiles
but resolves `genie.ts` instead of `genie.web.ts` throws on import and shows a
blank page — this test fails instead.

It then runs `web/interaction-test.js`, which drives that same DOM with real
events against a fake app-server: Enter sends and Shift+Enter does not, a turn
typed mid-reply is queued and goes out when the reply lands, and a tool call's
arguments and result appear behind the disclosure. These are browser-only
behaviours — the Android build has neither a hardware Enter key nor this
composer state — so nothing else in the repo covers them.

To confirm the Android app is unaffected:

```bash
export JAVA_HOME=/mnt/ssd/bryan/AI_SMART/tools/jdk17
export PATH=/mnt/ssd/bryan/AI_SMART/tools/node20/bin:$PATH
cd android && ./gradlew assembleDebug
```

## 9. Known gaps

- **The rendered UI has not been reviewed in a real browser.** It mounts and
  renders correctly under jsdom, and the API is verified end to end, but nobody
  has looked at it on a screen. Layout and touch behaviour are unconfirmed.
- **Tool calls depend on the model's judgement.** Asked "who was Ada Lovelace",
  Qwen3.5-2B answered from weights (with errors) rather than searching; asked to
  "search the web for the population of Reykjavik" it called `web_search`
  correctly. Same caveat as the Android build.
- **Context is set low (8192)** pending measurement — see §4.2.
- **No systemd unit.** The app-server is started by `geniechat.sh`, which does
  not survive a board reboot.
