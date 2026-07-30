/**
 * The model, as seen from JS — web build.
 *
 * Stands in for `genie.ts` when the bundler targets react-native-web. Metro and
 * webpack both resolve `.web.ts` ahead of `.ts`, so **nothing above this file
 * changes between the two targets**: `App.tsx`, both screens and every
 * component import from `'./genie'` and get the native module on Android and
 * this HTTP client in the browser.
 *
 * The exported surface is deliberately identical to `genie.ts`'s, down to the
 * `Progress` shape, because that is what makes the swap invisible. Where the
 * Android version turns `GenieToken` events into a per-call subscription, this
 * one turns an SSE stream into the same thing.
 *
 * Requests are same-origin: the app-server on the board serves this bundle and
 * the API. The board has no network interface, so the browser reaches both
 * through `adb forward tcp:8080 tcp:8080` from the workstation.
 */
import type {ToolCall} from './store';

export type {ToolCall};

export type ModelInfo = {
  id: string;
  name: string;
  note: string;
  supportsReasoning: boolean;
  /** VLM: the composer offers an attach button for these. Always false here. */
  supportsImages: boolean;
  /** Can call the on-device tools (device info, web search). */
  supportsTools: boolean;
  /** 'GENIE' (QNN context binaries) or 'GENIEX' (GGUF via llama.cpp). */
  runtime: 'GENIE' | 'GENIEX';
  installed: boolean;
  path: string;
};

export type Role = 'user' | 'assistant';

export type WireMessage = {role: Role; content: string};

export type Progress = {
  answer: string;
  thoughts: string;
  hasThoughts: boolean;
  /**
   * What the model is doing between generations — "Searching the web…".
   * Out of band on purpose: a tool round trip produces no tokens, so without
   * this the UI would sit silent for seconds mid-turn.
   */
  status?: string;
  /**
   * Every tool call this turn, with arguments and result. Grows mid-turn: a
   * call appears when it starts and gains its result when it returns.
   */
  toolCalls?: ToolCall[];
};

export type GenerateResult = Progress & {
  elapsedMs: number;
  /** Tokens held after this turn, and the model's window. */
  contextUsed: number;
  contextLength: number;
  /** The reply stopped at the token ceiling rather than at end-of-sequence. */
  capped: boolean;
  /** Names of any tools the model called while answering. */
  toolsUsed: string[];
};

async function postJson<T>(path: string, body: unknown): Promise<T> {
  const response = await fetch(path, {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify(body),
  });
  if (!response.ok) {
    const detail = await response.text().catch(() => '');
    throw new Error(`${path} failed (${response.status}): ${detail || response.statusText}`);
  }
  return response.json() as Promise<T>;
}

export const listModels = async (): Promise<ModelInfo[]> => {
  const response = await fetch('/api/models');
  if (!response.ok) {
    throw new Error(`Could not reach the app-server (${response.status}).`);
  }
  return response.json();
};

/**
 * Make a model resident.
 *
 * `onStaging` never fires on this backend and the parameter is kept only so the
 * call sites shared with Android compile unchanged: staging was an Android
 * storage workaround (copying a GGUF off FUSE before the DSP could map it), and
 * here llama.cpp mmaps the file where it lies. Loading is still slow the first
 * time — it is just not divisible into reportable progress.
 */
export function loadModel(
  modelId: string,
  _onStaging?: (percent: number) => void,
): Promise<{modelId: string; contextLength: number; loadMs: number}> {
  return postJson('/api/load', {modelId});
}

export const stop = (): Promise<void> => postJson('/api/stop', {});

export const resetConversation = (): Promise<void> => postJson('/api/reset', {});

/**
 * No permission model on Linux — the tools that needed grants (contacts,
 * calendar) do not exist in this build. Resolving immediately keeps
 * `ChatScreen`'s "ask when a tool-capable chat opens" call site unchanged.
 */
export const requestToolPermissions = (): Promise<void> => Promise.resolve();

/** No image support on this backend; `Composer` already handles a null. */
export const pickImage = (): Promise<string | null> => Promise.resolve(null);

/**
 * One turn. `history` is everything before this turn, oldest first.
 *
 * The server streams SSE frames tagged `progress`, `done` or `error`. A stream
 * that ends without a `done` is a dropped connection, not a finished reply —
 * a real case when the browser is talking through an adb tunnel — so that is
 * reported as an error rather than silently resolving with a partial answer.
 */
export async function generate(
  args: {
    chatId: string;
    modelId: string;
    history: WireMessage[];
    text: string;
    /** Accepted and ignored: this backend has no vision model. */
    imagePaths?: string[];
    brevity: boolean;
    thinking: boolean;
  },
  onProgress: (progress: Progress) => void,
): Promise<GenerateResult> {
  const response = await fetch('/api/generate', {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({
      chatId: args.chatId,
      modelId: args.modelId,
      history: args.history,
      text: args.text,
      brevity: args.brevity,
      thinking: args.thinking,
    }),
  });

  if (!response.ok) {
    const detail = await response.text().catch(() => '');
    throw new Error(
      response.status === 409
        ? 'A reply is already being generated.'
        : `Generation failed (${response.status}): ${detail || response.statusText}`,
    );
  }
  if (!response.body) {
    throw new Error('Streaming is not supported by this browser.');
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let result: GenerateResult | null = null;
  let failure: string | null = null;

  for (;;) {
    const {done, value} = await reader.read();
    if (done) {
      break;
    }
    buffer += decoder.decode(value, {stream: true});

    // Frames are "data: {...}\n\n"; a chunk can split one, so only consume
    // complete frames and keep the remainder buffered.
    let split: number;
    while ((split = buffer.indexOf('\n\n')) >= 0) {
      const frame = buffer.slice(0, split);
      buffer = buffer.slice(split + 2);
      if (!frame.startsWith('data: ')) {
        continue;
      }
      let event: Record<string, unknown>;
      try {
        event = JSON.parse(frame.slice(6));
      } catch {
        continue;
      }

      if (event.type === 'progress') {
        onProgress({
          answer: String(event.answer ?? ''),
          thoughts: String(event.thoughts ?? ''),
          hasThoughts: Boolean(event.hasThoughts),
          status: String(event.status ?? ''),
          toolCalls: Array.isArray(event.toolCalls)
            ? (event.toolCalls as ToolCall[])
            : [],
        });
      } else if (event.type === 'done') {
        result = event as unknown as GenerateResult;
      } else if (event.type === 'error') {
        failure = String(event.message ?? 'generation failed');
      }
    }
  }

  if (failure) {
    throw new Error(failure);
  }
  if (!result) {
    throw new Error('The connection to the app-server dropped mid-reply.');
  }
  return result;
}
