/**
 * One user turn: build the transcript, stream the reply, resolve any tool calls.
 *
 * Ported from `GenieXEngine.generate` in the Android app, but simpler than its
 * ancestor, because moving to llama-server's OpenAI-compatible endpoint handed
 * three of the Android workarounds back to llama.cpp (see `llama.ts` for why):
 *
 *  - **Tool-call parsing** was hand-rolled scraping of `<tool_call>{…}` out of
 *    the raw completion. llama.cpp now returns a structured `tool_calls` array.
 *  - **Tool results** had to be smuggled back as a `user` turn wrapped in
 *    `<tool_response>`, because the template's `role == "tool"` branch used
 *    Jinja loop extensions that aborted GenieX's minja. They now go back as a
 *    real `tool` message; llama.cpp owns that branch.
 *  - **Reasoning** was scraped for `<think>` / `<|channel>thought` markers, with
 *    a per-model flag for whether the opening tag even appeared in the stream.
 *    llama.cpp separates it into `reasoning_content` before we see it.
 *
 * What did NOT change is the shape of the loop: re-render the whole transcript
 * every turn and let llama.cpp prefix-match its KV cache, cap the number of
 * tool round trips, and drop the tools on the final pass so the model has to
 * answer with what it has.
 */
import {LlamaServer, type ChatDelta, type ChatMessage, type ToolCallWire} from './llama';
import {spec} from './models';
import {schemaJson, type Tool} from './tools';
import {BatteryTool, DateTimeTool, DeviceInfoTool} from './devicetools';
import {WebSearchTool} from './websearch';

/**
 * How many times the model may call tools before it has to answer.
 *
 * Four covers "look up two things and combine them" while bounding the worst
 * case: every iteration is a full generation, so a runaway loop costs the user
 * tens of seconds of staring at a spinner.
 */
const MAX_TOOL_ITERATIONS = 4;

/** Reply ceiling. */
const MAX_NEW_TOKENS = 1024;

/** Registry. Order is the order the model sees them in. */
const TOOLS: Tool[] = [DateTimeTool, BatteryTool, DeviceInfoTool, WebSearchTool];

export type Role = 'user' | 'assistant' | 'system';
export type Message = {role: Role; content: string};

/** Progress pushed to the UI mid-turn — the wire shape of `Progress` in genie.ts. */
export type Progress = {
  answer: string;
  thoughts: string;
  hasThoughts: boolean;
  status: string;
};

export type TurnResult = Progress & {
  elapsedMs: number;
  contextUsed: number;
  contextLength: number;
  capped: boolean;
  toolsUsed: string[];
};

function statusFor(toolName: string): string {
  switch (toolName) {
    case WebSearchTool.name:
      return 'Searching the web…';
    case BatteryTool.name:
    case DeviceInfoTool.name:
      return 'Checking the device…';
    default:
      return 'Checking…';
  }
}

export class Engine {
  private readonly llama = new LlamaServer();

  get currentModelId(): string | null {
    return this.llama.currentModelId;
  }

  get contextLength(): number {
    return this.llama.contextLength;
  }

  async ensureModel(modelId: string): Promise<void> {
    await this.llama.ensureModel(modelId);
  }

  abort(): void {
    this.llama.abort();
  }

  /**
   * Forget the conversation.
   *
   * A no-op against llama-server on purpose: it prefix-matches every prompt
   * against its KV cache and re-prefills whatever diverges, so a "reset" is
   * just the next turn arriving with a different prefix. Kept as an endpoint
   * because the UI calls it when a chat is deleted, and because a future
   * backend might need it to mean something.
   */
  resetConversation(): void {}

  async close(): Promise<void> {
    await this.llama.close();
  }

  /**
   * Run one turn and stream the visible reply through `onProgress`.
   *
   * `history` is everything before this turn, oldest first. The WHOLE
   * transcript is re-rendered every turn, and that is deliberate and cheap:
   * llama.cpp prefix-matches the new prompt against the KV cache and only
   * prefills the tail, so passing the full history costs about the same as an
   * incremental turn while being far harder to get wrong.
   */
  async generate(args: {
    chatId: string;
    modelId: string;
    history: Message[];
    text: string;
    brevity: boolean;
    thinking: boolean;
    onProgress: (progress: Progress) => void;
  }): Promise<TurnResult> {
    const {modelId, history, text, brevity, thinking, onProgress} = args;
    const t0 = Date.now();
    await this.ensureModel(modelId);

    const s = spec(modelId);

    // Accumulated across every generation in this turn, so a reply that spans a
    // tool round trip keeps growing one bubble instead of restarting it.
    let answer = '';
    let thoughts = '';
    let status = '';
    const push = () =>
      onProgress({
        answer: answer.trim(),
        thoughts: thoughts.trim(),
        hasThoughts: thoughts.trim().length > 0,
        status,
      });

    const system = s.systemPrompt + (brevity ? s.brevityClause : '');
    const messages: ChatMessage[] = [{role: 'system', content: system}];
    for (const message of history) {
      messages.push({role: message.role, content: message.content});
    }
    messages.push({role: 'user', content: text});

    const tools = s.supportsTools ? schemaJson(TOOLS) : null;
    const used: string[] = [];

    for (let iteration = 0; iteration < MAX_TOOL_ITERATIONS; iteration++) {
      const isLast = iteration === MAX_TOOL_ITERATIONS - 1;

      const onDelta = (delta: ChatDelta) => {
        if (delta.content) {
          answer += delta.content;
        }
        if (delta.reasoning) {
          thoughts += delta.reasoning;
        }
        push();
      };

      const result = await this.llama.chatStream(
        messages,
        // On the final permitted pass, drop the tools so the model has no
        // choice but to answer with what it already gathered.
        isLast ? null : tools,
        thinking && s.supportsReasoning,
        MAX_NEW_TOKENS,
        onDelta,
      );

      if (result.toolCalls.length === 0) {
        return this.finish(answer, thoughts, used, t0, s.contextLength);
      }

      // Keep the model's own tool-call turn in the transcript: the template
      // pairs each result with the call that asked for it, and omitting it
      // makes the results look unmotivated.
      messages.push({
        role: 'assistant',
        content: result.content,
        tool_calls: result.toolCalls,
      });

      for (const call of result.toolCalls) {
        status = statusFor(call.function.name);
        push();
        const output = await this.execute(call);
        used.push(call.function.name);
        console.log(
          `[engine] tool ${call.function.name}(${call.function.arguments}) -> ${output.slice(0, 120)}`,
        );
        messages.push({role: 'tool', content: output, tool_call_id: call.id});
      }
      status = '';
      push();
    }

    return this.finish(answer, thoughts, used, t0, s.contextLength);
  }

  private finish(
    answer: string,
    thoughts: string,
    used: string[],
    t0: number,
    contextLength: number,
  ): TurnResult {
    let body = answer.trim();
    const thinking = thoughts.trim();
    // A small reasoning model sometimes reasons and then stops without writing
    // a separate answer. At the end of a turn that means the reasoning *is* the
    // reply, so promote it rather than leaving the user an empty bubble with
    // the answer hidden behind a disclosure.
    const promoted = !body && thinking;
    if (promoted) {
      body = thinking;
    }
    return {
      answer: body || "I wasn't able to answer that.",
      thoughts: promoted ? '' : thinking,
      hasThoughts: !promoted && thinking.length > 0,
      status: '',
      elapsedMs: Date.now() - t0,
      // llama-server manages the KV cache itself and the window is large; the
      // Android context arithmetic (trim, re-prime, reserve-for-reply) has no
      // equivalent here. Reported as 0 so the UI's field stays populated.
      contextUsed: 0,
      contextLength,
      capped: false,
      toolsUsed: used,
    };
  }

  /**
   * Run one tool. A tool must never take the turn down with it: a dead network
   * or a missing sysfs node becomes a sentence the model can relay.
   */
  private async execute(call: ToolCallWire): Promise<string> {
    const tool = TOOLS.find(t => t.name === call.function.name);
    if (!tool) {
      return `No such tool: ${call.function.name}.`;
    }
    let args: Record<string, unknown> = {};
    try {
      args = call.function.arguments ? JSON.parse(call.function.arguments) : {};
    } catch {
      // A 2B at 4-bit will occasionally emit arguments that aren't valid JSON.
      // Running the tool with no arguments produces a usable error message;
      // failing the turn does not.
      return `The arguments for ${call.function.name} were not valid JSON.`;
    }
    try {
      return await tool.run(args);
    } catch (e) {
      console.warn(`[engine] tool ${call.function.name} failed`, e);
      return `The ${call.function.name} tool failed: ${(e as Error).message ?? 'unknown error'}.`;
    }
  }
}
