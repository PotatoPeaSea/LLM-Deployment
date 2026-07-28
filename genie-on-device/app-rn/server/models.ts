/**
 * Which models the Linux app-server knows about, and where their weights live.
 *
 * Ported from the Android app's `ModelStore.kt`, cut down to the GENIEX
 * (GGUF/llama.cpp) half. The three GENIE models are gone: they are QNN context
 * binaries produced by a cloud export, and nothing in this server can load one.
 *
 * Two things from the Kotlin version are deliberately absent:
 *
 *  - **`stage()`**. On Android the pushed bundle had to be copied from external
 *    storage to internal before it could load at all, because a large region of
 *    a FUSE-backed file cannot be mapped into the DSP's SMMU (ModelStore.kt's
 *    `internalModelsRoot` doc has the measurements). That is an Android storage
 *    quirk. Here the GGUF sits on plain ext4 at `MODELS_ROOT` and llama.cpp
 *    mmaps it directly, so there is nothing to stage and no progress to report.
 *  - **`Runtime`**. With the QNN half dropped there is one runtime, so a field
 *    naming it would never be read. The JS `ModelInfo.runtime` still reports
 *    'GENIEX' for wire compatibility with the Android app's UI, which shows it.
 */
import {existsSync} from 'node:fs';
import {join} from 'node:path';

/** Where GGUFs live on the board. `/data` is the big partition (181GB free). */
export const MODELS_ROOT = process.env.GENIE_MODELS_ROOT ?? '/data/models';

export type ModelSpec = {
  id: string;
  displayName: string;
  note: string;
  /** GGUF filename, relative to `MODELS_ROOT/<id>/`. */
  ggufFile: string;
  /**
   * Whether the model has a thinking mode to turn on.
   *
   * Note what is NOT here: the Android spec also carried `thinkOpen`,
   * `thinkClose` and `thinkOpenInStream`, the markers a hand-rolled splitter
   * used to pull reasoning back out of the reply — Qwen's `<think>` versus
   * Gemma 4's `<|channel>thought`, and whether the opening tag even reached
   * the stream. llama-server separates reasoning into `reasoning_content`
   * before this server sees a token, so there is nothing left to configure.
   * If a model's reasoning ever leaks into its visible answer, that is the
   * signal those fields (and a splitter) need to come back for it.
   */
  supportsReasoning: boolean;
  systemPrompt: string;
  /** Appended when the brevity toggle is on. */
  brevityClause: string;
  supportsTools: boolean;
  /**
   * Context window.
   *
   * A GGUF has no baked-in ceiling, so this is OUR choice, bounded by what the
   * Hexagon DSP can actually map. **The Android numbers do not carry over**:
   * 164000 was measured against the GenieX AAR's llama.cpp on Android
   * (176K loaded, 192K failed in `fastrpc_mmap`), and this is a different
   * build on a different OS. These start deliberately small and are meant to
   * be raised once M0's measurements are in — see docs/UBUNTU-BOARD.md.
   */
  contextLength: number;
};

/**
 * Registry. Order is the order the UI lists them in.
 *
 * Neither model declares images. On Android the VLM path SIGSEGV'd
 * unconditionally (GenieXEngine.kt's class doc), and this server does not
 * implement image input at all, so the capability is simply not offered.
 */
export const MODELS: ModelSpec[] = [
  {
    id: 'qwen3_5_2b',
    displayName: 'Qwen3.5 2B',
    note: 'Reasoning, uses tools',
    ggufFile: 'Qwen3.5-2B-Q4_0.gguf',
    supportsReasoning: true,
    systemPrompt:
      'You are a helpful assistant running entirely on this device. ' +
      'You can call tools to answer questions about the device and the web. ' +
      'Call a tool only when it is actually needed, and answer directly otherwise.',
    brevityClause: ' Be brief.',
    supportsTools: true,
    contextLength: 8192,
  },
  {
    id: 'gemma4_e2b',
    displayName: 'Gemma 4 E2B',
    note: 'Second GGUF model, text only',
    ggufFile: 'gemma-4-E2B-it-Q4_0.gguf',
    supportsReasoning: true,
    systemPrompt: 'You are a helpful assistant running entirely on this device.',
    brevityClause: ' Be brief.',
    // Tools are off here on purpose, exactly as on Android: the tool prompt is
    // written around Qwen's Hermes-style <tool_call> convention, and Gemma is
    // carried mainly to exercise model switching.
    supportsTools: false,
    contextLength: 8192,
  },
];

export const DEFAULT_MODEL_ID = MODELS[0].id;

export function spec(modelId: string): ModelSpec {
  return MODELS.find(m => m.id === modelId) ?? MODELS[0];
}

/** Absolute path to a model's GGUF, whether or not it exists yet. */
export function ggufPath(modelId: string): string {
  const s = spec(modelId);
  return join(MODELS_ROOT, s.id, s.ggufFile);
}

/** A model is usable if its GGUF is actually on disk. */
export function isInstalled(modelId: string): boolean {
  return existsSync(ggufPath(modelId));
}

/** Every known model plus whether its weights are present, for `GET /api/models`. */
export function inventory() {
  return MODELS.map(m => ({
    id: m.id,
    name: m.displayName,
    note: m.note,
    supportsReasoning: m.supportsReasoning,
    // No image support on this runtime -- see the MODELS doc above.
    supportsImages: false,
    supportsTools: m.supportsTools,
    // The Android UI renders this string; kept so the shared screens don't care
    // which backend they are talking to.
    runtime: 'GENIEX' as const,
    installed: isInstalled(m.id),
    path: ggufPath(m.id),
  }));
}
