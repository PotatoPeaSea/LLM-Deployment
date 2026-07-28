/**
 * Web-search relay. Runs on the WORKSTATION, not the board.
 *
 * The board has no network interface at all — `ip -br addr` on it shows only
 * loopback and tunnel devices. Its single link to the world is the USB cable
 * carrying adb. So `web_search` cannot reach DuckDuckGo or Wikipedia directly,
 * and this closes the gap:
 *
 *     board: web_search -> http://127.0.0.1:8079/search?q=...
 *                          |
 *                          |  adb reverse tcp:8079 tcp:8079
 *                          v
 *     host:  this relay   -> api.duckduckgo.com, en.wikipedia.org
 *
 * `adb reverse` makes a port on the *host* appear on the *board's* loopback,
 * which is the opposite of `adb forward` and exactly what is needed here.
 * `deploy-linux.sh` sets both up.
 *
 * Only the query string the model chose crosses this link. The relay does not
 * see, log, or forward any chat content — it takes `q` and returns text.
 *
 *     node scripts/search-relay.mjs [port]
 *
 * Deliberately dependency-free and deliberately bound to 127.0.0.1: this is a
 * developer convenience on a workstation, not a service, and it should not be
 * reachable from the wider network.
 */
import {createServer} from 'node:http';
import {searchDirect} from '../server/dist/websearch.js';

const PORT = Number(process.argv[2] ?? process.env.RELAY_PORT ?? 8079);

// The relay is the side that actually has internet; the shared implementation
// checks this to decide between "do the lookups" and "ask a relay".
process.env.GENIE_SEARCH_DIRECT = '1';

const server = createServer(async (req, res) => {
  const url = new URL(req.url ?? '/', 'http://127.0.0.1');

  if (url.pathname !== '/search') {
    res.writeHead(404, {'Content-Type': 'text/plain; charset=utf-8'});
    res.end('usage: GET /search?q=...\n');
    return;
  }

  const query = (url.searchParams.get('q') ?? '').trim();
  if (!query) {
    res.writeHead(400, {'Content-Type': 'text/plain; charset=utf-8'});
    res.end('No query given.\n');
    return;
  }

  console.log(`[relay] ${new Date().toISOString()} q=${JSON.stringify(query)}`);
  try {
    const answer = await searchDirect(query);
    res.writeHead(200, {'Content-Type': 'text/plain; charset=utf-8'});
    res.end(answer);
  } catch (e) {
    // The board-side tool turns any failure into a sentence the model relays,
    // so a 502 here is informative rather than fatal to the turn.
    console.warn('[relay] lookup failed', e);
    res.writeHead(502, {'Content-Type': 'text/plain; charset=utf-8'});
    res.end(`Search failed: ${e?.message ?? 'unknown error'}\n`);
  }
});

server.listen(PORT, '127.0.0.1', () => {
  console.log(`[relay] listening on http://127.0.0.1:${PORT}`);
  console.log(`[relay] expose it to the board with: adb reverse tcp:${PORT} tcp:${PORT}`);
});
