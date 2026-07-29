/**
 * Chat persistence.
 *
 * Chats live here, not in native: the KV cache holds exactly one conversation
 * and is wiped whenever the model is swapped, so JS has to be the durable
 * record. Native is told a chat's history whenever the cache needs rebuilding.
 *
 * A chat is pinned to the model it was created with. Mixing two models'
 * output inside one transcript would make the history misleading — and the
 * history is what gets replayed into the model on the next rebuild.
 */
import AsyncStorage from '@react-native-async-storage/async-storage';

const CHATS_KEY = 'genie.chats.v1';
const SETTINGS_KEY = 'genie.settings.v1';

/**
 * One tool call the model made, kept for the disclosure under the reply.
 *
 * Persisted with the message rather than recomputed: the call is the only
 * record of *why* a reply says what it says — a web search whose query was
 * nothing like the question explains a wrong answer that the answer alone
 * cannot. Mirrors `ToolCall` in the server's `engine.ts`, which fills it in.
 */
export type ToolCall = {
  name: string;
  /** Arguments verbatim as the model emitted them: a JSON string, usually. */
  arguments: string;
  /** What the tool handed back. Empty while the call is still running. */
  result: string;
  /** Wall time of the call. Absent while it is still running. */
  ms?: number;
  /** False when the tool failed or refused; `result` is the message either way. */
  ok?: boolean;
};

export type Message = {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  /** Model reasoning, kept out of `content` so it never re-enters the prompt. */
  thoughts?: string;
  elapsedMs?: number;
  /**
   * Absolute paths of images attached to a user turn.
   *
   * Only the turn they were sent on carries them: re-sending every past image
   * on every rebuild would re-encode each one through the vision projector, and
   * the model has already described them in the transcript.
   */
  images?: string[];
  /** Tools the model called for this reply, for the "used web_search" note. */
  toolsUsed?: string[];
  /** The same calls in full — name, arguments, result — for the disclosure. */
  toolCalls?: ToolCall[];
};

export type Chat = {
  id: string;
  title: string;
  modelId: string;
  createdAt: number;
  updatedAt: number;
  messages: Message[];
};

export type Settings = {
  brevity: boolean;
  thinking: boolean;
  lastModelId: string | null;
  /**
   * The open chat, persisted so a process restart (see GenieModule.kt --
   * switching NPU models restarts the whole app, since in-process switching
   * is unreliable on this device) resumes into the same chat instead of
   * dropping the user at the chat list.
   */
  lastOpenChatId: string | null;
};

export const DEFAULT_SETTINGS: Settings = {
  brevity: true,
  thinking: false,
  lastModelId: null,
  lastOpenChatId: null,
};

export const newId = (): string =>
  `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;

/** First line of the opening message, which is a better label than "Chat 3". */
export function deriveTitle(text: string): string {
  const line = text.trim().split('\n')[0].trim();
  if (!line) {
    return 'New chat';
  }
  return line.length > 38 ? `${line.slice(0, 38)}…` : line;
}

export async function loadChats(): Promise<Chat[]> {
  try {
    const raw = await AsyncStorage.getItem(CHATS_KEY);
    const chats: Chat[] = raw ? JSON.parse(raw) : [];
    return chats.sort((a, b) => b.updatedAt - a.updatedAt);
  } catch {
    // Corrupt storage should cost the user their history, not the app.
    return [];
  }
}

export async function saveChats(chats: Chat[]): Promise<void> {
  await AsyncStorage.setItem(CHATS_KEY, JSON.stringify(chats));
}

export async function loadSettings(): Promise<Settings> {
  try {
    const raw = await AsyncStorage.getItem(SETTINGS_KEY);
    return raw ? {...DEFAULT_SETTINGS, ...JSON.parse(raw)} : DEFAULT_SETTINGS;
  } catch {
    return DEFAULT_SETTINGS;
  }
}

export async function saveSettings(settings: Settings): Promise<void> {
  await AsyncStorage.setItem(SETTINGS_KEY, JSON.stringify(settings));
}

/** What native needs: roles and content only, no thoughts, no ids. */
export const toWire = (messages: Message[]) =>
  messages.map(m => ({role: m.role, content: m.content}));
