/**
 * Supervises `llama-server` and talks to it.
 *
 * This replaces the GenieX SDK's `LlmWrapper` (see `GenieXEngine.kt` in the
 * Android app).
 *
 * ## Why the OpenAI-compatible endpoint, and not /apply-template + /completion
 *
 * The Android engine rendered the prompt itself (`applyChatTemplate`), then
 * scraped Hermes-style `<tool_call>{"name":…}</tool_call>` back out of the raw
 * completion by hand. Ported literally, that does not work against upstream
 * llama.cpp, and the reason is worth recording:
 *
 * llama.cpp does not just paste a `tools` array into the model's own template.
 * Its chat layer picks a tool-call *format* for the model and injects matching
 * instructions — for this GGUF it chose an XML form,
 * `<tool_call><function=name><parameter=query>…`, which the model followed
 * exactly and the JSON parser could not read. `--jinja` does not change this;
 * it is llama.cpp's chat handler, not a template quirk.
 *
 * Since llama.cpp chooses the format, llama.cpp should also parse it — and it
 * will, via `/v1/chat/completions`, which hands back a structured `tool_calls`
 * array and `finish_reason: "tool_calls"` regardless of which convention it
 * picked. Rendering and parsing then cannot disagree, which is exactly the
 * failure mode above.
 *
 * Two Android workarounds are retired by this move, as the plan anticipated:
 * tool results go back as a proper `role: "tool"` message (llama.cpp owns that
 * template branch now), and reasoning arrives pre-split as `reasoning_content`
 * instead of being scraped for `<think>` markers.
 *
 * ## Model switching is a process restart
 *
 * On Android, switching models had to restart the entire app, because every
 * in-process unload+reload raced the Hexagon/FastRPC teardown and failed four
 * different ways (`GenieModule.kt` `switchToOrRestart` documents all four).
 * Here the model lives in a separate process, so a switch is just SIGTERM and
 * respawn: the DSP session goes away with the process, which is the same
 * "let a first load be a first load" property that fixed it on Android — and
 * it costs the UI nothing, since the UI is not in this process.
 */
import {spawn, type ChildProcess} from 'node:child_process';
import {setTimeout as sleep} from 'node:timers/promises';
import {dirname, join, resolve} from 'node:path';
import {ggufPath, spec} from './models';

/** Root of the deployed llama.cpp package (`pkg-sysroot`). */
const LLAMA_ROOT = process.env.LLAMA_ROOT ?? resolve(dirname(process.argv[1] ?? '.'), '..', 'llama');
const LLAMA_BIN = process.env.LLAMA_BIN ?? join(LLAMA_ROOT, 'bin', 'llama-server');
const LLAMA_LIB = process.env.LLAMA_LIB ?? join(LLAMA_ROOT, 'lib');

/**
 * Compute device. `none` means CPU; `HTP0` is the Hexagon NPU.
 *
 * **CPU is the default, and that is a measurement, not an oversight.** Measured
 * on this board (QCS8550, Hexagon v73, 4 HVX threads, 8MB VTCM) with
 * `llama-bench` on Qwen3.5-2B-Q4_0:
 *
 *   | test          | CPU (8 threads) | HTP0 (NPU) |
 *   |---------------|-----------------|------------|
 *   | prefill pp64  |  101.5 t/s      | 164.5 t/s  |
 *   | decode  tg32  |   21.6 t/s      |   7.0 t/s  |
 *
 * The NPU wins prefill by ~1.6x and loses decode by ~3x. A chat app is
 * decode-bound — decode is the part the user watches happen — so CPU is the
 * better default here despite this being an NPU project. llama.cpp's Hexagon
 * backend still describes itself as experimental, and this is the shape of that.
 *
 * Set `LLAMA_DEVICE=HTP0` to switch; it is genuinely the better choice for a
 * prefill-heavy workload (long documents, short answers). Re-measure with
 * `llama-bench` before changing the default — see docs/UBUNTU-BOARD.md.
 */
const DEVICE = process.env.LLAMA_DEVICE ?? 'none';

/** CPU threads, when running on CPU. The board has 8 cores. */
const CPU_THREADS = Number(process.env.LLAMA_THREADS ?? 8);

/** Port llama-server listens on, loopback only. Not the app-server's port. */
const LLAMA_PORT = Number(process.env.LLAMA_PORT ?? 8081);
const BASE = `http://127.0.0.1:${LLAMA_PORT}`;

/**
 * How long to wait for a model to become resident.
 *
 * Generous because this covers the first mmap of a multi-GB GGUF plus the
 * compute session coming up, on a board whose first-ever load is the slowest.
 */
const READY_TIMEOUT_MS = 180_000;

/** A chat turn as llama-server wants it. `tool` turns also carry an id. */
export type ChatMessage = {
  role: 'system' | 'user' | 'assistant' | 'tool';
  content: string;
  tool_calls?: ToolCallWire[];
  tool_call_id?: string;
};

export type ToolCallWire = {
  id: string;
  type: 'function';
  function: {name: string; arguments: string};
};

/** What one generation produced. */
export type ChatResult = {
  /** The visible reply, reasoning already excluded. */
  content: string;
  /** The model's thinking, as llama.cpp separated it. */
  reasoning: string;
  toolCalls: ToolCallWire[];
};

export type ChatDelta = {content?: string; reasoning?: string};

function log(...args: unknown[]) {
  console.log('[llama]', ...args);
}

export class LlamaServer {
  private child: ChildProcess | null = null;
  private loadedModelId: string | null = null;
  /** Aborts the in-flight completion, for `stop()`. */
  private inflight: AbortController | null = null;

  get currentModelId(): string | null {
    return this.loadedModelId;
  }

  get contextLength(): number {
    return this.loadedModelId ? spec(this.loadedModelId).contextLength : 0;
  }

  /**
   * Make `modelId` resident, restarting llama-server if a different model is
   * loaded. No-ops when it is already the loaded model, which is what lets the
   * UI call it on every chat open without thinking about it.
   */
  async ensureModel(modelId: string): Promise<void> {
    if (this.loadedModelId === modelId && this.child && !this.child.killed) {
      return;
    }
    await this.close();

    const s = spec(modelId);
    const args = [
      '-m', ggufPath(modelId),
      '--device', DEVICE,
      '-c', String(s.contextLength),
      '--host', '127.0.0.1',
      '--port', String(LLAMA_PORT),
      // The app-server serves the UI; llama-server's own is dead weight here.
      '--no-webui',
      // Use the GGUF's own chat template rather than a built-in guess. Required
      // for tool calling to be wired up at all.
      '--jinja',
      // Offloading only means anything with an accelerator device. On CPU,
      // passing -ngl also trips llama.cpp's "failed to fit params to free
      // device memory" path, so leave it off entirely.
      ...(DEVICE === 'none' ? ['-t', String(CPU_THREADS)] : ['-ngl', '99']),
    ];

    log(`starting ${modelId} on ${DEVICE} (ctx ${s.contextLength})`);
    const t0 = Date.now();
    this.child = spawn(LLAMA_BIN, args, {
      env: {
        ...process.env,
        // Both are required by the Hexagon backend: LD_LIBRARY_PATH for the
        // CPU-side libggml-hexagon.so, ADSP_LIBRARY_PATH for the DSP-side
        // libggml-htp-v73.so, which FastRPC loads by *path* on the DSP.
        LD_LIBRARY_PATH: `${LLAMA_LIB}:${process.env.LD_LIBRARY_PATH ?? ''}`,
        ADSP_LIBRARY_PATH: `${LLAMA_LIB};${process.env.ADSP_LIBRARY_PATH ?? ''}`,
      },
      stdio: ['ignore', 'pipe', 'pipe'],
    });

    // llama-server logs to stderr; forward it so a load failure is visible in
    // the app-server's own log rather than silently swallowed.
    this.child.stdout?.on('data', d => process.stdout.write(`[llama-server] ${d}`));
    this.child.stderr?.on('data', d => process.stderr.write(`[llama-server] ${d}`));

    const child = this.child;
    let exited: string | null = null;
    child.on('exit', (code, signal) => {
      exited = `llama-server exited (code ${code}, signal ${signal})`;
      if (this.child === child) {
        this.child = null;
        this.loadedModelId = null;
      }
    });

    await this.waitUntilReady(() => exited);
    this.loadedModelId = modelId;
    log(`loaded ${modelId} in ${Date.now() - t0}ms`);
  }

  /** Poll /health until the model is resident, or the process dies, or we time out. */
  private async waitUntilReady(exitReason: () => string | null): Promise<void> {
    const deadline = Date.now() + READY_TIMEOUT_MS;
    for (;;) {
      const reason = exitReason();
      if (reason) {
        throw new Error(`${reason} before becoming ready — see the log above`);
      }
      try {
        const response = await fetch(`${BASE}/health`);
        if (response.ok) {
          return;
        }
        // 503 while the model is still loading is normal, not an error.
      } catch {
        // Connection refused while it is still binding the port; keep waiting.
      }
      if (Date.now() > deadline) {
        throw new Error(`llama-server did not become ready within ${READY_TIMEOUT_MS}ms`);
      }
      await sleep(250);
    }
  }

  /**
   * One generation, streamed.
   *
   * `onDelta` receives content and reasoning fragments as they arrive, already
   * separated by llama.cpp. Tool calls are accumulated and returned whole —
   * there is nothing useful to show the user mid-call, and the arguments JSON
   * is only valid once complete.
   */
  async chatStream(
    messages: ChatMessage[],
    tools: Record<string, unknown>[] | null,
    thinking: boolean,
    maxTokens: number,
    onDelta: (delta: ChatDelta) => void,
  ): Promise<ChatResult> {
    const controller = new AbortController();
    this.inflight = controller;

    const body: Record<string, unknown> = {
      messages,
      stream: true,
      max_tokens: maxTokens,
      // Qwen gates reasoning on this; templates that don't know the key ignore it.
      chat_template_kwargs: {enable_thinking: thinking},
    };
    if (tools && tools.length) {
      body.tools = tools;
    }

    let content = '';
    let reasoning = '';
    // Streamed tool calls arrive in pieces keyed by index; arguments in
    // particular are concatenated across deltas.
    const calls = new Map<number, ToolCallWire>();

    try {
      const response = await fetch(`${BASE}/v1/chat/completions`, {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        signal: controller.signal,
        body: JSON.stringify(body),
      });
      if (!response.ok || !response.body) {
        throw new Error(
          `/v1/chat/completions failed: ${response.status} ${await response.text()}`,
        );
      }

      // SSE frames are "data: {...}\n\n", but a chunk can split one in half, so
      // hold a buffer and only consume complete frames.
      let buffer = '';
      const decoder = new TextDecoder();
      for await (const chunk of response.body as unknown as AsyncIterable<Uint8Array>) {
        buffer += decoder.decode(chunk, {stream: true});
        let split: number;
        while ((split = buffer.indexOf('\n\n')) >= 0) {
          const frame = buffer.slice(0, split);
          buffer = buffer.slice(split + 2);
          for (const line of frame.split('\n')) {
            if (!line.startsWith('data: ')) {
              continue;
            }
            const payload = line.slice(6).trim();
            if (payload === '[DONE]') {
              continue;
            }
            try {
              const delta = JSON.parse(payload)?.choices?.[0]?.delta;
              if (!delta) {
                continue;
              }
              if (typeof delta.content === 'string' && delta.content) {
                content += delta.content;
                onDelta({content: delta.content});
              }
              if (typeof delta.reasoning_content === 'string' && delta.reasoning_content) {
                reasoning += delta.reasoning_content;
                onDelta({reasoning: delta.reasoning_content});
              }
              for (const call of delta.tool_calls ?? []) {
                const index = Number(call.index ?? 0);
                const existing = calls.get(index) ?? {
                  id: '',
                  type: 'function' as const,
                  function: {name: '', arguments: ''},
                };
                if (call.id) {
                  existing.id = call.id;
                }
                if (call.function?.name) {
                  existing.function.name = call.function.name;
                }
                if (call.function?.arguments) {
                  existing.function.arguments += call.function.arguments;
                }
                calls.set(index, existing);
              }
            } catch {
              // A frame we can't parse is not worth failing the turn over.
            }
          }
        }
      }
    } catch (e) {
      // An abort is a user pressing stop, not a failure: whatever was streamed
      // so far is the answer.
      if (!controller.signal.aborted) {
        throw e;
      }
    } finally {
      if (this.inflight === controller) {
        this.inflight = null;
      }
    }

    return {
      content,
      reasoning,
      toolCalls: [...calls.entries()].sort((a, b) => a[0] - b[0]).map(([, c]) => c),
    };
  }

  /** Stop the in-flight generation. Safe to call at any time. */
  abort(): void {
    this.inflight?.abort();
  }

  /**
   * Stop llama-server and wait for it to actually be gone.
   *
   * The wait matters: the next model's load must not begin while this process
   * still holds a compute session, which is the whole reason Android needed a
   * process restart here.
   */
  async close(): Promise<void> {
    const child = this.child;
    this.child = null;
    this.loadedModelId = null;
    if (!child || child.killed) {
      return;
    }

    this.abort();
    await new Promise<void>(done => {
      const timer = setTimeout(() => {
        child.kill('SIGKILL');
        done();
      }, 10_000);
      child.once('exit', () => {
        clearTimeout(timer);
        done();
      });
      child.kill('SIGTERM');
    });
  }
}
