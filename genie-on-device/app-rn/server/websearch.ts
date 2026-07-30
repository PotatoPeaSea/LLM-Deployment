/**
 * The one tool that leaves the device.
 *
 * Ported from `WebSearchTool.kt`, keeping its two keyless endpoints and the
 * order they are tried in:
 *
 *   1. DuckDuckGo's Instant Answer API — good at definitions, people, places,
 *      "what is X"; returns nothing at all for many ordinary queries.
 *   2. Wikipedia's REST summary — the fallback, and the reason (1)'s blind
 *      spots are survivable.
 *
 * Neither is a real web index, so this answers encyclopaedic questions well and
 * "what happened today" poorly. That tradeoff is stated in the schema because
 * the schema is what steers the model.
 *
 * ## Why there is a relay
 *
 * The Android app could just open a socket. **This board has no network at
 * all** — `ip -br addr` shows only loopback and tunnel devices, no ethernet, no
 * wifi, no default route. Its only link to the world is the USB cable carrying
 * adb. So there are three modes, picked by environment:
 *
 *   - `GENIE_SEARCH_DIRECT=1` — hit the two APIs directly. For a board that
 *     does have a network; also how the host-side relay itself runs.
 *   - `GENIE_SEARCH_RELAY=http://127.0.0.1:<port>` — ask a relay on the other
 *     end of an `adb reverse` tunnel, which runs on the workstation and does
 *     the lookups there. This is the normal on-board configuration; see
 *     `scripts/search-relay.mjs` and `scripts/deploy-linux.sh`.
 *   - neither set — report that search is unavailable. A sentence the model can
 *     relay is the correct failure here, not an exception.
 *
 * Only the query string the model chose is transmitted, in every mode. No chat
 * history, no device identifiers.
 */
import {functionSchema, type Tool} from './tools';

const TIMEOUT_MS = 8000;
const MAX_CHARS = 1200;

const USER_AGENT = 'GenieChatLinux/1.0 (on-device assistant)';

/** One GET with a timeout, returning the body or null. Never throws. */
async function get(url: string): Promise<string | null> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), TIMEOUT_MS);
  try {
    const response = await fetch(url, {
      signal: controller.signal,
      headers: {
        // Both APIs are friendlier to a request that identifies itself, and
        // Wikipedia's policy asks for it outright.
        'User-Agent': USER_AGENT,
        Accept: 'application/json',
      },
    });
    if (!response.ok) {
      return null;
    }
    return (await response.text()).slice(0, MAX_CHARS * 8);
  } catch {
    return null;
  } finally {
    clearTimeout(timer);
  }
}

const enc = encodeURIComponent;

/** DuckDuckGo Instant Answer. Returns null when it has nothing useful. */
async function instantAnswer(query: string): Promise<string | null> {
  const body = await get(
    `https://api.duckduckgo.com/?q=${enc(query)}&format=json&no_html=1&skip_disambig=1`,
  );
  if (!body) {
    return null;
  }
  try {
    const json = JSON.parse(body);

    const abstract = String(json.AbstractText ?? '').trim();
    if (abstract) {
      const source = String(json.AbstractSource ?? '').trim();
      return source ? `${abstract} (source: ${source})` : abstract;
    }
    const answer = String(json.Answer ?? '').trim();
    if (answer) {
      return answer;
    }

    // RelatedTopics is the last resort: a list of one-line blurbs.
    const topics: unknown[] = Array.isArray(json.RelatedTopics) ? json.RelatedTopics : [];
    const lines = topics
      .slice(0, 3)
      .map(t => String((t as {Text?: unknown})?.Text ?? '').trim())
      .filter(Boolean)
      .map(t => `- ${t}`);
    return lines.length ? lines.join('\n') : null;
  } catch {
    return null;
  }
}

/** Wikipedia REST summary for the best-matching article title. */
async function wikipediaSummary(query: string): Promise<string | null> {
  const searchBody = await get(
    'https://en.wikipedia.org/w/api.php?action=query&list=search' +
      `&srsearch=${enc(query)}&srlimit=1&format=json`,
  );
  if (!searchBody) {
    return null;
  }
  let title = '';
  try {
    title = String(JSON.parse(searchBody)?.query?.search?.[0]?.title ?? '').trim();
  } catch {
    return null;
  }
  if (!title) {
    return null;
  }

  const summaryBody = await get(
    `https://en.wikipedia.org/api/rest_v1/page/summary/${enc(title)}`,
  );
  if (!summaryBody) {
    return null;
  }
  try {
    const extract = String(JSON.parse(summaryBody)?.extract ?? '').trim();
    return extract ? `${extract} (source: Wikipedia, "${title}")` : null;
  } catch {
    return null;
  }
}

/**
 * Do the lookups here, against the real internet. Exported because the
 * host-side relay is this same function behind an HTTP endpoint.
 */
export async function searchDirect(query: string): Promise<string> {
  return (
    (await instantAnswer(query)) ??
    (await wikipediaSummary(query)) ??
    `No result found for "${query}".`
  );
}

/** Ask the relay on the far end of the adb-reverse tunnel. */
async function searchViaRelay(relay: string, query: string): Promise<string> {
  const body = await get(`${relay.replace(/\/$/, '')}/search?q=${enc(query)}`);
  if (body === null) {
    return (
      'Web search is temporarily unreachable (the relay on the host is not ' +
      'responding). Answer from what you already know, and say that you could ' +
      'not look it up.'
    );
  }
  return body.slice(0, MAX_CHARS);
}

export const WebSearchTool: Tool = {
  name: 'web_search',

  schema: () =>
    functionSchema(
      'web_search',
      'Search the web for factual and encyclopaedic information: ' +
        'definitions, people, places, organisations, science, history. ' +
        'Use it when the answer is a fact you do not know or that may have ' +
        'changed. It is weak at breaking news and live data.',
      {
        type: 'object',
        properties: {
          query: {type: 'string', description: 'The search query.'},
        },
        required: ['query'],
      },
    ),

  async run(args) {
    const query = String(args.query ?? '').trim();
    if (!query) {
      return 'No query given.';
    }

    if (process.env.GENIE_SEARCH_DIRECT === '1') {
      return searchDirect(query);
    }
    const relay = process.env.GENIE_SEARCH_RELAY;
    if (relay) {
      return searchViaRelay(relay, query);
    }
    return (
      'Web search is not available on this device: it has no network ' +
      'connection. Answer from what you already know, and say that you could ' +
      'not look it up.'
    );
  },
};
