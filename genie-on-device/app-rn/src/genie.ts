/**
 * The NPU, as seen from JS.
 *
 * Everything here is a thin pass-through to the native GenieModule; the only
 * logic is turning the stream of `GenieToken` events into a per-call
 * subscription, so a caller can `await generate(...)` and still watch the reply
 * arrive.
 */
import {NativeEventEmitter, NativeModules} from 'react-native';

const {Genie} = NativeModules;

if (!Genie) {
  throw new Error(
    'Native Genie module missing. This app only runs on the device build ' +
      '(the NPU has no simulator).',
  );
}

const emitter = new NativeEventEmitter(Genie);

export type ModelInfo = {
  id: string;
  name: string;
  note: string;
  supportsReasoning: boolean;
  installed: boolean;
  path: string;
};

export type Role = 'user' | 'assistant';

export type WireMessage = {role: Role; content: string};

export type Progress = {
  answer: string;
  thoughts: string;
  hasThoughts: boolean;
};

export type GenerateResult = Progress & {
  elapsedMs: number;
  /** Tokens Genie is holding after this turn, and the model's window. */
  contextUsed: number;
  contextLength: number;
  /** The reply stopped at the token ceiling rather than at end-of-sequence. */
  capped: boolean;
};

export const listModels = (): Promise<ModelInfo[]> => Genie.listModels();

/**
 * Make a model resident. The first load of a model also copies it from the
 * pushed directory into internal storage — GBs, so `onStaging` reports percent.
 * Later loads skip straight to mapping it onto the NPU.
 */
export function loadModel(
  modelId: string,
  onStaging?: (percent: number) => void,
): Promise<{modelId: string; contextLength: number; loadMs: number}> {
  const subscription = emitter.addListener(
    'GenieStaging',
    (event: {modelId: string; percent: number}) => {
      if (event.modelId === modelId) {
        onStaging?.(event.percent);
      }
    },
  );
  return Genie.loadModel(modelId).finally(() => subscription.remove());
}

export const stop = (): Promise<void> => Genie.stop();

export const resetConversation = (): Promise<void> => Genie.resetConversation();

/**
 * One turn. `history` is everything before this turn, oldest first — native
 * only reads it when the KV cache has to be rebuilt, but it must always be
 * accurate, because that rebuild can happen on any turn.
 */
export function generate(
  args: {
    chatId: string;
    modelId: string;
    history: WireMessage[];
    text: string;
    brevity: boolean;
    thinking: boolean;
  },
  onProgress: (progress: Progress) => void,
): Promise<GenerateResult> {
  const subscription = emitter.addListener(
    'GenieToken',
    (event: Progress & {chatId: string}) => {
      // Events are process-wide; a stale one from an aborted turn must not
      // repaint a chat the user has already moved on from.
      if (event.chatId !== args.chatId) {
        return;
      }
      onProgress({
        answer: event.answer,
        thoughts: event.thoughts,
        hasThoughts: event.hasThoughts,
      });
    },
  );

  return Genie.generate(
    args.chatId,
    args.modelId,
    args.history,
    args.text,
    args.brevity,
    args.thinking,
  ).finally(() => subscription.remove());
}
