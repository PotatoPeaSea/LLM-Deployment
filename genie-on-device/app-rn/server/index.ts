/**
 * The app-server: the HTTP face of the model, and the static host for the UI.
 *
 * This is `GenieModule.kt`'s job — serialise generation, stream progress, keep
 * exactly one model resident — with the React Native bridge replaced by HTTP.
 * The endpoint set is deliberately shaped so that `src/genie.web.ts` is as thin
 * as the `src/genie.ts` it stands in for:
 *
 *   Genie.listModels()          GET  /api/models
 *   Genie.loadModel(id)         POST /api/load
 *   Genie.generate(...)         POST /api/generate   (SSE)
 *   Genie.stop()                POST /api/stop
 *   Genie.resetConversation()   POST /api/reset
 *
 * Streaming is SSE rather than a resolved promise for the same reason Android
 * used events: a promise settles once, and the whole point is showing the reply
 * as it is generated.
 *
 * ## One turn at a time
 *
 * `busy` is the direct equivalent of `GenieModule`'s `AtomicBoolean`. There is
 * one llama-server holding one KV cache, so overlapping turns would interleave
 * two conversations into it. A second request is rejected rather than queued —
 * the UI already disables the composer while a turn is running, so a 409 here
 * means a bug or a second browser tab, and failing loudly is right.
 *
 * ## Why serving the UI from here at all
 *
 * The board has no network interface (loopback only). The browser reaches this
 * server through `adb forward tcp:8080 tcp:8080` from the workstation, so the
 * UI and the API must share an origin — a separate static host would need a
 * second tunnel and CORS for no benefit.
 */
import {createReadStream, existsSync, statSync} from 'node:fs';
import {createServer, type IncomingMessage, type ServerResponse} from 'node:http';
import {extname, join, normalize, resolve} from 'node:path';
import {Engine, type Message, type Progress} from './engine';
import {inventory, spec} from './models';

const PORT = Number(process.env.PORT ?? 8080);
/** Built react-native-web bundle. Served at `/`. */
const WEB_ROOT = resolve(process.env.WEB_ROOT ?? join(__dirname, '..', 'web'));

const engine = new Engine();
let busy = false;

const MIME: Record<string, string> = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
};

function sendJson(res: ServerResponse, status: number, body: unknown): void {
  const text = JSON.stringify(body);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(text),
  });
  res.end(text);
}

async function readJson(req: IncomingMessage): Promise<Record<string, unknown>> {
  const chunks: Buffer[] = [];
  for await (const chunk of req) {
    chunks.push(chunk as Buffer);
  }
  const raw = Buffer.concat(chunks).toString('utf8');
  return raw ? JSON.parse(raw) : {};
}

/**
 * Serve a file out of WEB_ROOT, falling back to index.html so the single-page
 * app survives a reload on any path.
 */
function serveStatic(req: IncomingMessage, res: ServerResponse): void {
  const url = new URL(req.url ?? '/', 'http://localhost');
  // normalize + prefix check: a request for /../../etc/passwd must not escape.
  const candidate = resolve(join(WEB_ROOT, normalize(url.pathname)));
  const path =
    candidate.startsWith(WEB_ROOT) && existsSync(candidate) && statSync(candidate).isFile()
      ? candidate
      : join(WEB_ROOT, 'index.html');

  if (!existsSync(path)) {
    res.writeHead(404, {'Content-Type': 'text/plain'});
    res.end(
      'UI bundle not found. Build it with `npm run build:web` and deploy it, ' +
        'or set WEB_ROOT.\n',
    );
    return;
  }
  res.writeHead(200, {'Content-Type': MIME[extname(path)] ?? 'application/octet-stream'});
  createReadStream(path).pipe(res);
}

/**
 * One turn, streamed as SSE.
 *
 * Frames are `{type: 'progress'|'done'|'error', ...}`. The client cannot
 * distinguish a finished stream from a dropped connection otherwise, and on a
 * board reached through an adb tunnel, dropped connections are a real case.
 */
async function handleGenerate(req: IncomingMessage, res: ServerResponse): Promise<void> {
  if (busy) {
    sendJson(res, 409, {error: 'A generation is already running'});
    return;
  }
  busy = true;

  let body: Record<string, unknown>;
  try {
    body = await readJson(req);
  } catch (e) {
    busy = false;
    sendJson(res, 400, {error: `Bad JSON: ${(e as Error).message}`});
    return;
  }

  res.writeHead(200, {
    'Content-Type': 'text/event-stream; charset=utf-8',
    'Cache-Control': 'no-cache',
    Connection: 'keep-alive',
    // The tunnel and any proxy in between must not buffer a token stream.
    'X-Accel-Buffering': 'no',
  });

  const send = (payload: unknown) => res.write(`data: ${JSON.stringify(payload)}\n\n`);

  // A browser tab closing mid-turn should stop the generation, not leave the
  // model running for a reply nobody will read.
  let aborted = false;
  req.on('close', () => {
    if (!res.writableEnded) {
      aborted = true;
      engine.abort();
    }
  });

  try {
    const result = await engine.generate({
      chatId: String(body.chatId ?? 'default'),
      modelId: String(body.modelId ?? ''),
      history: Array.isArray(body.history) ? (body.history as Message[]) : [],
      text: String(body.text ?? ''),
      brevity: Boolean(body.brevity),
      thinking: Boolean(body.thinking),
      onProgress: (progress: Progress) => send({type: 'progress', ...progress}),
    });
    if (!aborted) {
      send({type: 'done', ...result});
    }
  } catch (e) {
    console.error('[api] generate failed', e);
    if (!aborted) {
      send({type: 'error', message: (e as Error).message ?? 'generation failed'});
    }
  } finally {
    busy = false;
    res.end();
  }
}

const server = createServer((req, res) => {
  const path = new URL(req.url ?? '/', 'http://localhost').pathname;
  const method = req.method ?? 'GET';

  const route = async () => {
    if (path === '/api/models' && method === 'GET') {
      return sendJson(res, 200, inventory());
    }

    if (path === '/api/load' && method === 'POST') {
      const {modelId} = await readJson(req);
      const id = String(modelId ?? '');
      const t0 = Date.now();
      await engine.ensureModel(id);
      return sendJson(res, 200, {
        modelId: id,
        contextLength: spec(id).contextLength,
        loadMs: Date.now() - t0,
      });
    }

    if (path === '/api/generate' && method === 'POST') {
      return handleGenerate(req, res);
    }

    if (path === '/api/stop' && method === 'POST') {
      engine.abort();
      return sendJson(res, 200, {ok: true});
    }

    if (path === '/api/reset' && method === 'POST') {
      engine.resetConversation();
      return sendJson(res, 200, {ok: true});
    }

    if (path === '/api/health' && method === 'GET') {
      return sendJson(res, 200, {ok: true, model: engine.currentModelId, busy});
    }

    return serveStatic(req, res);
  };

  route().catch(e => {
    console.error('[api] request failed', e);
    if (!res.headersSent) {
      sendJson(res, 500, {error: (e as Error).message ?? 'internal error'});
    } else {
      res.end();
    }
  });
});

server.listen(PORT, '127.0.0.1', () => {
  console.log(`[api] listening on http://127.0.0.1:${PORT}`);
  console.log(`[api] serving UI from ${WEB_ROOT}`);
  console.log('[api] reach it from the workstation with: adb forward tcp:8080 tcp:8080');
});

/** Take llama-server down with us; an orphan would hold the model and the DSP. */
for (const signal of ['SIGINT', 'SIGTERM'] as const) {
  process.on(signal, () => {
    console.log(`[api] ${signal}, shutting down`);
    void engine.close().finally(() => process.exit(0));
  });
}
