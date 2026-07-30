/**
 * The one tool that leaves the device.
 *
 * Primarily the Exa Answer API (`POST /answer`): a real, current web index
 * plus synthesis, returning one sourced answer instead of a list of links for
 * the model to digest. Falls back to the original keyless pair — DuckDuckGo's
 * Instant Answer API, then Wikipedia's REST summary — when `EXA_API_KEY`
 * isn't set, or when Exa doesn't have an answer. The fallback only ever
 * covers encyclopaedic "what is X" questions and returns nothing for
 * ordinary or current-events queries, which is the whole reason Exa is
 * first choice; it exists so the tool still does *something* useful
 * keyless. See `scripts/search-relay.mjs` for where `EXA_API_KEY` is
 * expected to live, since it never needs to reach the board.
 *
 * ## Why there is a relay
 *
 * The Android app could just open a socket. **This board has no network at
 * all** — `ip -br addr` shows only loopback and tunnel devices, no ethernet, no
 * wifi, no default route. Its only link to the world is the USB cable carrying
 * adb. So there are three modes, picked by environment:
 *
 *   - `GENIE_SEARCH_DIRECT=1` — call Exa directly. For a board that does have
 *     a network; also how the host-side relay itself runs.
 *   - `GENIE_SEARCH_RELAY=http://127.0.0.1:<port>` — ask a relay on the other
 *     end of an `adb reverse` tunnel, which runs on the workstation and does
 *     the lookup there (and holds the Exa API key). This is the normal
 *     on-board configuration; see `scripts/search-relay.mjs` and
 *     `scripts/deploy-linux.sh`.
 *   - neither set — report that search is unavailable. A sentence the model can
 *     relay is the correct failure here, not an exception.
 *
 * Only the query string the model chose is transmitted, in every mode. No chat
 * history, no device identifiers.
 */
import {functionSchema, type Tool} from './tools';

const TIMEOUT_MS = 12000;
const MAX_CHARS = 1200;

const EXA_ANSWER_URL = 'https://api.exa.ai/answer';

/**
 * One POST with a timeout, returning the parsed JSON body or null. Never
 * throws. Unlike `get()`, the body is not pre-truncated: it still needs to be
 * parsed as JSON, and slicing raw text before parsing risks cutting it off
 * mid-structure.
 */
async function postJson(
  url: string,
  body: Record<string, unknown>,
  headers: Record<string, string>,
): Promise<unknown | null> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), TIMEOUT_MS);
  try {
    const response = await fetch(url, {
      method: 'POST',
      signal: controller.signal,
      headers: {'Content-Type': 'application/json', Accept: 'application/json', ...headers},
      body: JSON.stringify(body),
    });
    if (!response.ok) {
      return null;
    }
    return await response.json();
  } catch {
    return null;
  } finally {
    clearTimeout(timer);
  }
}

/** One GET with a timeout, returning the body or null. Never throws. */
async function get(url: string): Promise<string | null> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), TIMEOUT_MS);
  try {
    const response = await fetch(url, {
      signal: controller.signal,
      headers: {
        'User-Agent': 'GenieChatLinux/1.0 (on-device assistant)',
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

/** Keyless fallback, used when Exa is not configured or comes up empty. */
async function keylessFallback(query: string): Promise<string | null> {
  return (await instantAnswer(query)) ?? (await wikipediaSummary(query));
}

/** Exa's Answer API: a synthesized, sourced answer over Exa's live web index. */
async function exaAnswer(query: string): Promise<string | null> {
  const apiKey = process.env.EXA_API_KEY;
  if (!apiKey) {
    return null;
  }
  // No `text: true`: that would have Exa attach each citation's full page
  // text, ballooning the response for no benefit here — only the synthesized
  // `answer` and the citation titles are used.
  const json = (await postJson(EXA_ANSWER_URL, {query}, {'x-api-key': apiKey})) as
    | {answer?: unknown; citations?: unknown}
    | null;
  if (!json) {
    return null;
  }
  const answer = String(json.answer ?? '').trim();
  if (!answer) {
    return null;
  }
  const citations: unknown[] = Array.isArray(json.citations) ? json.citations : [];
  const sources = citations
    .slice(0, 3)
    .map(c => String((c as {title?: unknown; url?: unknown})?.title ?? (c as {url?: unknown})?.url ?? '').trim())
    .filter(Boolean);
  const result = sources.length ? `${answer} (sources: ${sources.join('; ')})` : answer;
  return result.slice(0, MAX_CHARS);
}

/**
 * Do the lookup here, against the real internet. Exported because the
 * host-side relay is this same function behind an HTTP endpoint.
 */
export async function searchDirect(query: string): Promise<string> {
  return (
    (await exaAnswer(query)) ?? (await keylessFallback(query)) ?? `No result found for "${query}".`
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
      'Search the web for factual and current information: definitions, ' +
        'people, places, organisations, news, prices, and anything else you ' +
        "do not know or that may have changed. Backed by a real, live web " +
        'index, so it is useful for recent events as well as encyclopaedic ' +
        'questions.',
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
