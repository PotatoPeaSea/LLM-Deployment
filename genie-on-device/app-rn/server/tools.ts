/**
 * The tools the on-device model is allowed to call.
 *
 * Ported from `Tools.kt`, minus most of it. The Android version also owned the
 * call *convention* — it built Hermes-style `<tool_call>{…}</tool_call>` into
 * the prompt and scraped the same shape back out of the completion. None of
 * that survives here: llama-server picks the tool-call format for the model and
 * parses it back into a structured array, so all that is left on our side is
 * describing the tools and running them. See `llama.ts` for why that division
 * is the right one.
 *
 * Two other differences from Android:
 *
 *  - **No permission model.** `Tool.permission` and `hasPermission` are gone
 *    with the Contacts and Calendar tools they existed for. Nothing left here
 *    needs a grant.
 *  - **`run` is async.** `web_search` does real HTTP; on Android it blocked a
 *    worker thread, here it returns a promise.
 *
 * Every tool is READ-ONLY. Nothing here can send the user's data anywhere —
 * `web_search` transmits only the query string the model chose, which is the
 * one place anything leaves the box.
 */

export type Tool = {
  name: string;
  /** JSON Schema, in the shape the `tools` array expects under `function`. */
  schema(): Record<string, unknown>;
  run(args: Record<string, unknown>): Promise<string>;
};

/** Convenience for the tools whose schema takes no arguments. */
export function noArgs(): Record<string, unknown> {
  return {type: 'object', properties: {}, required: []};
}

export function functionSchema(
  name: string,
  description: string,
  parameters: Record<string, unknown>,
): Record<string, unknown> {
  return {name, description, parameters};
}

/**
 * The `tools` argument for `/v1/chat/completions`: an array of
 * `{"type":"function","function":{name, description, parameters}}`.
 */
export function schemaJson(tools: Tool[]): Record<string, unknown>[] {
  return tools.map(tool => ({type: 'function', function: tool.schema()}));
}
