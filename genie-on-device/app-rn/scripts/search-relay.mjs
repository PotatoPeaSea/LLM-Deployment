/**
 * Web-search relay. Runs on the WORKSTATION, not the board.
 *
 * The board has no network interface at all — `ip -br addr` on it shows only
 * loopback and tunnel devices. Its single link to the world is the USB cable
 * carrying adb. So `web_search` cannot reach the Exa API directly, and this
 * closes the gap:
 *
 *     board: web_search -> http://127.0.0.1:8079/search?q=...
 *                          |
 *                          |  adb reverse tcp:8079 tcp:8079
 *                          v
 *     host:  this relay   -> api.exa.ai
 *
 * `adb reverse` makes a port on the *host* appear on the *board's* loopback,
 * which is the opposite of `adb forward` and exactly what is needed here.
 * `deploy-linux.sh` sets both up.
 *
 * This is also the right place for the Exa API key: it lives here, on the
 * workstation, and never needs to cross the USB link to the board. It comes
 * from the `EXA_API_KEY` environment variable, or — for convenience, so it
 * does not have to be exported by hand every session — from an `EXA_API_KEY`
 * line in a gitignored `secrets.txt` at the repo root.
 *
 * Only the query string the model chose crosses the board<->host link. The
 * relay does not see, log, or forward any chat content — it takes `q` and
 * returns text.
 *
 *     node scripts/search-relay.mjs [port]
 *
 * Deliberately dependency-free and deliberately bound to 127.0.0.1: this is a
 * developer convenience on a workstation, not a service, and it should not be
 * reachable from the wider network.
 */
import {createServer} from 'node:http';
import {existsSync, readFileSync} from 'node:fs';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {searchDirect} from '../server/dist/websearch.js';

const PORT = Number(process.argv[2] ?? process.env.RELAY_PORT ?? 8079);

// The relay is the side that actually has internet; the shared implementation
// checks this to decide between "do the lookups" and "ask a relay".
process.env.GENIE_SEARCH_DIRECT = '1';

if (!process.env.EXA_API_KEY) {
  let dir = dirname(fileURLToPath(import.meta.url));
  for (let i = 0; i < 8; i++) {
    const candidate = join(dir, 'secrets.txt');
    if (existsSync(candidate)) {
      const line = readFileSync(candidate, 'utf8')
        .split('\n')
        .find(l => l.trim().startsWith('EXA_API_KEY'));
      const value = line?.split('=')[1]?.trim();
      if (value) {
        process.env.EXA_API_KEY = value;
      }
      break;
    }
    const parent = dirname(dir);
    if (parent === dir) {
      break;
    }
    dir = parent;
  }
}

if (!process.env.EXA_API_KEY) {
  console.warn(
    '[relay] no EXA_API_KEY found (env or secrets.txt) — web_search will ' +
      'report itself unconfigured',
  );
}

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
