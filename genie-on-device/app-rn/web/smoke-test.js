/**
 * Does the built web bundle actually render?
 *
 * `npm run build:web` succeeding only proves the bundle compiled. It does not
 * prove that react-native-web can mount these screens, that `.web.ts` resolution
 * picked up `genie.web.ts` instead of the native `genie.ts` (which throws on
 * import when `NativeModules.Genie` is missing — a failure that looks exactly
 * like a blank page), or that the first render survives.
 *
 * So: load the real bundle into a real DOM, point `fetch` at a fake app-server,
 * and assert that something recognisable appears in the tree. This is the check
 * that catches "deployed fine, shows a white screen".
 *
 *     node web/smoke-test.js
 *
 * Run against `web/dist/bundle.js`, so build first.
 */
const {readFileSync} = require('node:fs');
const {join} = require('node:path');
const {JSDOM, VirtualConsole} = require('jsdom');

const BUNDLE = join(__dirname, 'dist', 'bundle.js');
const HTML = join(__dirname, 'dist', 'index.html');

/** Stand-in for the board, so the app has models to list and settings to load. */
const MODELS = [
  {
    id: 'qwen3_5_2b',
    name: 'Qwen3.5 2B',
    note: 'Reasoning, uses tools',
    supportsReasoning: true,
    supportsImages: false,
    supportsTools: true,
    runtime: 'GENIEX',
    installed: true,
    path: '/data/models/qwen3_5_2b/Qwen3.5-2B-Q4_0.gguf',
  },
];

function fakeFetch(url) {
  const path = String(url);
  if (path.endsWith('/api/models')) {
    return Promise.resolve({
      ok: true,
      status: 200,
      json: () => Promise.resolve(MODELS),
    });
  }
  return Promise.resolve({
    ok: true,
    status: 200,
    json: () => Promise.resolve({}),
    text: () => Promise.resolve(''),
  });
}

async function main() {
  const errors = [];
  const virtualConsole = new VirtualConsole();
  // Collect page errors rather than letting jsdom print and swallow them: an
  // exception during render is the exact thing this test exists to catch.
  virtualConsole.on('jsdomError', e => errors.push(e));
  virtualConsole.on('error', (...args) => errors.push(new Error(args.join(' '))));

  const dom = new JSDOM(readFileSync(HTML, 'utf8'), {
    runScripts: 'outside-only',
    pretendToBeVisual: true,
    url: 'http://127.0.0.1:8080/',
    virtualConsole,
  });

  dom.window.fetch = fakeFetch;
  dom.window.matchMedia =
    dom.window.matchMedia ||
    (() => ({matches: false, addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {}}));

  dom.window.eval(readFileSync(BUNDLE, 'utf8'));

  // Let the mount effects (loadChats / loadSettings / listModels) settle.
  await new Promise(resolve => setTimeout(resolve, 1500));

  const root = dom.window.document.getElementById('root');
  const text = root ? root.textContent : '';
  const html = root ? root.innerHTML : '';

  if (errors.length) {
    console.error('FAIL: the bundle raised errors while rendering:');
    for (const e of errors) {
      console.error('  ', e.detail ?? e.message ?? e);
    }
    process.exit(1);
  }
  if (!html || html.length < 100) {
    console.error('FAIL: #root is empty — the app mounted nothing.');
    console.error('   innerHTML:', JSON.stringify(html).slice(0, 300));
    process.exit(1);
  }
  // The chat list is the first screen, and its empty state is this sentence.
  if (!text.includes('No chats yet')) {
    console.error('FAIL: rendered, but the chat list empty state is missing.');
    console.error('   text:', JSON.stringify(text).slice(0, 300));
    process.exit(1);
  }

  console.log('PASS: bundle mounted and rendered the chat list.');
  console.log(`   #root innerHTML: ${html.length} bytes`);
  console.log(`   visible text: ${JSON.stringify(text.slice(0, 120))}`);
}

main().catch(e => {
  console.error('FAIL:', e);
  process.exit(1);
});
