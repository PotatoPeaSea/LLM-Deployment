/**
 * Does the chat screen actually behave?
 *
 * `smoke-test.js` proves the bundle mounts. It says nothing about the two things
 * that only exist on this target and can only be checked by driving them:
 *
 *  - **Enter sends.** react-native-web hands `onSubmitEditing` only to
 *    single-line inputs, so the multiline composer reads the key itself. That is
 *    exactly the kind of code that keeps compiling after it stops working.
 *  - **A turn typed mid-reply is queued, not lost**, and goes out on its own
 *    once the model is free.
 *  - **Tool calls are auditable**: name, arguments and result appear behind the
 *    disclosure, filled in while the calls are still running.
 *
 * The board reaches the app through an adb tunnel and a browser, which is not a
 * thing CI can click. So: load the real bundle into a real DOM, stand in for the
 * app-server with a fetch that streams SSE frames on command, and drive the
 * composer with real DOM events.
 *
 *     node web/interaction-test.js
 *
 * Run against `web/dist/bundle.js`, so build first.
 */
const {readFileSync} = require('node:fs');
const {join} = require('node:path');
const {JSDOM, VirtualConsole} = require('jsdom');

const BUNDLE = join(__dirname, 'dist', 'bundle.js');
const HTML = join(__dirname, 'dist', 'index.html');

const MODEL = {
  id: 'qwen3_5_2b',
  name: 'Qwen3.5 2B',
  note: 'Reasoning, uses tools',
  supportsReasoning: true,
  supportsImages: false,
  supportsTools: true,
  runtime: 'GENIEX',
  installed: true,
  path: '/data/models/qwen3_5_2b/Qwen3.5-2B-Q4_0.gguf',
};

const CONTEXT_LENGTH = 32768;

/** A chat already open, so the test starts on the screen it is testing. */
const CHAT = {
  id: 'chat-under-test',
  title: 'Test chat',
  modelId: MODEL.id,
  createdAt: Date.now(),
  updatedAt: Date.now(),
  messages: [],
};

const failures = [];
const check = (ok, what, detail) => {
  if (ok) {
    console.log(`  ok   ${what}`);
  } else {
    console.error(`  FAIL ${what}${detail ? ` — ${detail}` : ''}`);
    failures.push(what);
  }
};

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

/** Poll until `predicate` holds. Returns whether it ever did. */
async function waitFor(predicate, timeoutMs = 4000) {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    if (predicate()) {
      return true;
    }
    if (Date.now() > deadline) {
      return false;
    }
    await sleep(25);
  }
}

/**
 * A generation the test drives frame by frame, in the shape `genie.web.ts`
 * consumes: `response.body.getReader()` yielding encoded `data: {...}\n\n`.
 */
function sseStream() {
  const encoder = new TextEncoder();
  const pending = [];
  let waiting = null;
  let closed = false;

  const deliver = value => {
    if (waiting) {
      const resolve = waiting;
      waiting = null;
      resolve(value);
    } else if (!value.done) {
      pending.push(value);
    }
  };

  return {
    response: {
      ok: true,
      status: 200,
      body: {
        getReader: () => ({
          read: () => {
            if (pending.length) {
              return Promise.resolve(pending.shift());
            }
            if (closed) {
              return Promise.resolve({done: true, value: undefined});
            }
            return new Promise(resolve => {
              waiting = resolve;
            });
          },
        }),
      },
    },
    push(event) {
      deliver({
        done: false,
        value: encoder.encode(`data: ${JSON.stringify(event)}\n\n`),
      });
    },
    close() {
      closed = true;
      deliver({done: true, value: undefined});
    },
  };
}

async function main() {
  const errors = [];
  const virtualConsole = new VirtualConsole();
  virtualConsole.on('jsdomError', e => errors.push(e));

  const dom = new JSDOM(readFileSync(HTML, 'utf8'), {
    runScripts: 'outside-only',
    pretendToBeVisual: true,
    url: 'http://127.0.0.1:8080/',
    virtualConsole,
  });
  const win = dom.window;
  const doc = win.document;

  // AsyncStorage is localStorage on this target, so seeding it is how the app
  // gets told which chat to resume into (see Settings.lastOpenChatId).
  win.localStorage.setItem('genie.chats.v1', JSON.stringify([CHAT]));
  win.localStorage.setItem(
    'genie.settings.v1',
    JSON.stringify({
      brevity: true,
      thinking: false,
      lastModelId: MODEL.id,
      lastOpenChatId: CHAT.id,
    }),
  );

  // jsdom does not provide these, and the SSE reader in genie.web.ts needs them.
  win.TextEncoder = TextEncoder;
  win.TextDecoder = TextDecoder;
  win.matchMedia =
    win.matchMedia ||
    (() => ({
      matches: false,
      addListener() {},
      removeListener() {},
      addEventListener() {},
      removeEventListener() {},
    }));

  /** Every /api/generate the app has started, newest last. */
  const generates = [];

  win.fetch = (url, init) => {
    const path = String(url);
    const json = body => Promise.resolve({ok: true, status: 200, json: () => Promise.resolve(body), text: () => Promise.resolve('')});

    if (path.endsWith('/api/models')) {
      return json([MODEL]);
    }
    if (path.endsWith('/api/load')) {
      return json({modelId: MODEL.id, contextLength: CONTEXT_LENGTH, loadMs: 42});
    }
    if (path.endsWith('/api/generate')) {
      const stream = sseStream();
      generates.push({request: JSON.parse(init.body), stream});
      return Promise.resolve(stream.response);
    }
    return json({ok: true});
  };

  win.eval(readFileSync(BUNDLE, 'utf8'));

  const root = () => doc.getElementById('root');
  const text = () => (root() ? root().textContent : '');

  /**
   * The innermost element whose text contains `needle` — the label of the
   * control, not one of the boxes around it. Document order puts ancestors
   * first, so the last of the equally-short matches is the deepest, and since
   * DOM events bubble, that is the one worth dispatching on.
   */
  const find = needle => {
    const hits = [...doc.querySelectorAll('div')].filter(el =>
      el.textContent.includes(needle),
    );
    if (!hits.length) {
      return null;
    }
    const shortest = Math.min(...hits.map(el => el.textContent.length));
    return hits.filter(el => el.textContent.length === shortest).pop();
  };

  /**
   * A press. `click` and not mousedown/mouseup: react-native-web's responder
   * system wants pointer events jsdom does not implement, but Pressable also
   * accepts a plain click (that is what makes it keyboard-accessible).
   */
  const press = el =>
    el.dispatchEvent(new win.MouseEvent('click', {bubbles: true, cancelable: true, button: 0}));

  /**
   * Type into the composer. React tracks the last value it wrote, so the value
   * has to go through the native setter or the change event is swallowed.
   */
  const type = value => {
    const input = doc.querySelector('textarea');
    const setter = Object.getOwnPropertyDescriptor(
      win.HTMLTextAreaElement.prototype,
      'value',
    ).set;
    setter.call(input, value);
    input.dispatchEvent(new win.Event('input', {bubbles: true}));
    return input;
  };

  const enter = (shiftKey = false) =>
    doc.querySelector('textarea').dispatchEvent(
      new win.KeyboardEvent('keydown', {
        key: 'Enter',
        shiftKey,
        bubbles: true,
        cancelable: true,
      }),
    );

  // ---- the model comes up and the composer is live -------------------------
  const mounted = await waitFor(() => doc.querySelector('textarea') != null);
  check(mounted, 'the open chat renders a composer');
  if (!mounted) {
    return;
  }
  await waitFor(() => text().includes('ctx'));

  // ---- Shift+Enter is a newline, not a send -------------------------------
  console.log('Shift+Enter');
  type('draft line');
  enter(true);
  await sleep(150);
  check(generates.length === 0, 'Shift+Enter does not send', `${generates.length} generations`);
  check(
    doc.querySelector('textarea').value === 'draft line',
    'Shift+Enter leaves the draft in the box',
    JSON.stringify(doc.querySelector('textarea').value),
  );

  // ---- Enter sends --------------------------------------------------------
  console.log('Enter to send');
  type('what is the weather');
  enter();
  const sent = await waitFor(() => generates.length === 1);
  check(sent, 'Enter starts a generation');
  if (!sent) {
    return;
  }
  check(
    generates[0].request.text === 'what is the weather',
    'the sent turn carries what was typed',
    JSON.stringify(generates[0].request.text),
  );
  check(doc.querySelector('textarea').value === '', 'sending clears the box');
  check(text().includes('what is the weather'), 'the user turn is in the transcript');

  // ---- a tool call shows up while it is still running ---------------------
  console.log('tool-call disclosure');
  const call = {name: 'web_search', arguments: '{"query":"kaohsiung weather today"}', result: ''};
  generates[0].stream.push({
    type: 'progress',
    answer: '',
    thoughts: '',
    hasThoughts: false,
    status: 'Searching the web…',
    toolCalls: [call],
  });
  check(
    await waitFor(() => text().includes('1 tool call · running')),
    'a call in flight is disclosed as running',
    JSON.stringify(text().slice(-160)),
  );
  check(text().includes('Searching the web'), 'the status line says what it is doing');

  const finished = {...call, result: 'Kaohsiung: 31°C, humid, light rain later.', ms: 940, ok: true};
  generates[0].stream.push({
    type: 'progress',
    answer: 'It is 31°C in Kaohsiung.',
    thoughts: '',
    hasThoughts: false,
    status: '',
    toolCalls: [finished],
  });
  const settled = await waitFor(
    () => text().includes('1 tool call') && !text().includes('running'),
  );
  check(settled, 'a finished call stops reading as running');

  // The detail is behind a disclosure: closed until asked for.
  check(
    !text().includes('kaohsiung weather today'),
    'the arguments stay hidden until the disclosure is opened',
  );
  const toggle = find('1 tool call');
  check(toggle != null, 'the disclosure is a control that can be pressed');
  if (toggle) {
    press(toggle);
    const opened = await waitFor(() => text().includes('kaohsiung weather today'));
    check(opened, 'opening it shows the arguments the model actually sent', JSON.stringify(text().slice(-200)));
    check(text().includes('Kaohsiung: 31°C'), 'and what the tool handed back');
    check(text().includes('0.9s'), 'and how long the call took');
    press(find('1 tool call'));
    check(
      await waitFor(() => !text().includes('kaohsiung weather today')),
      'and it closes again',
    );
  }

  // ---- a turn typed mid-reply is queued ----------------------------------
  console.log('queueing');
  type('and tomorrow?');
  enter();
  const queued = await waitFor(() => text().includes('Queued'));
  check(queued, 'a turn typed while the model is busy is queued', JSON.stringify(text().slice(-200)));
  check(generates.length === 1, 'queueing does not start a second generation');
  check(text().includes('and tomorrow?'), 'the queued turn is shown');
  check(doc.querySelector('textarea').value === '', 'queueing clears the box');

  // ---- and goes out by itself when the reply lands -----------------------
  generates[0].stream.push({
    type: 'done',
    answer: 'It is 31°C in Kaohsiung.',
    thoughts: '',
    hasThoughts: false,
    status: '',
    elapsedMs: 4200,
    contextUsed: 0,
    contextLength: CONTEXT_LENGTH,
    capped: false,
    toolsUsed: ['web_search'],
    toolCalls: [finished],
  });
  generates[0].stream.close();

  const drained = await waitFor(() => generates.length === 2);
  check(drained, 'the queued turn is sent once the model is free');
  if (drained) {
    check(
      generates[1].request.text === 'and tomorrow?',
      'it is sent verbatim',
      JSON.stringify(generates[1].request.text),
    );
    check(
      generates[1].request.history.length === 2 &&
        generates[1].request.history[1].content === 'It is 31°C in Kaohsiung.',
      'and it carries the finished reply as history',
      JSON.stringify(generates[1].request.history),
    );
    check(!text().includes('Queued'), 'the queue indicator clears once drained');
  }

  // ---- a queued turn can be taken back -----------------------------------
  console.log('unqueueing');
  type('never mind');
  enter();
  const showed = await waitFor(() => text().includes('never mind'));
  check(showed, 'a second queued turn shows up while the drained one runs');
  if (showed) {
    press(find('never mind'));
    check(
      await waitFor(() => !text().includes('never mind')),
      'pressing a queued turn removes it',
    );
  }

  // Finish the second reply: nothing more should go out, because the only thing
  // left in the queue was taken back.
  generates[1].stream.push({
    type: 'done',
    answer: 'Rain, probably.',
    thoughts: '',
    hasThoughts: false,
    status: '',
    elapsedMs: 2100,
    contextUsed: 0,
    contextLength: CONTEXT_LENGTH,
    capped: false,
    toolsUsed: [],
    toolCalls: [],
  });
  generates[1].stream.close();
  await sleep(300);
  check(generates.length === 2, 'a removed turn is never sent', `${generates.length} generations`);

  // A render-time exception is the failure this whole file exists to catch.
  check(errors.length === 0, 'nothing threw while rendering', errors.map(e => e.detail ?? e.message).join('; '));
}

main()
  .then(() => {
    if (failures.length) {
      console.error(`\nFAIL: ${failures.length} check(s) failed.`);
      process.exit(1);
    }
    console.log('\nPASS: composer, queueing and the tool-call disclosure all behave.');
    process.exit(0);
  })
  .catch(e => {
    console.error('FAIL:', e);
    process.exit(1);
  });
