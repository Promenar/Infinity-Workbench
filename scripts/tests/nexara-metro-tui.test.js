'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { EventEmitter, Readable } = require('stream');

const {
  VERSION,
  HELP_TEXT,
  DEFAULT_TAG,
  ArgError,
  parseArgs,
  parseLogcatLine,
  formatLine,
  renderEvent,
  dispatch,
} = require('../nexara-metro-tui.js');

function makeWritable() {
  const chunks = [];
  const w = {
    write: (s) => {
      chunks.push(typeof s === 'string' ? s : String(s));
      return true;
    },
    isTTY: false,
  };
  Object.defineProperty(w, 'captured', {
    get: () => chunks.join(''),
  });
  return w;
}

function makeFakeChild() {
  const child = new EventEmitter();
  child.stdout = new EventEmitter();
  child.stderr = new EventEmitter();
  child.kill = () => true;
  return child;
}

function makeInput(chunks) {
  return new Readable({
    read() {
      for (const c of chunks) this.push(c);
      this.push(null);
    },
  });
}

const ANSI_RE = /\x1b\[/;

test('VERSION 与 HELP_TEXT 可用', () => {
  assert.ok(typeof VERSION === 'string' && VERSION.length > 0);
  assert.ok(HELP_TEXT.includes('--help'));
  assert.ok(HELP_TEXT.includes('--version'));
  assert.ok(HELP_TEXT.includes('--stdin'));
  assert.ok(HELP_TEXT.includes('--serial'));
  assert.ok(HELP_TEXT.includes('--tag'));
});

test('parseArgs: --help 不依赖 adb 且 action=help', () => {
  const r = parseArgs(['--help'], { isTTY: false });
  assert.equal(r.action, 'help');
  assert.equal(r.useColor, false);
});

test('parseArgs: --version 不依赖 adb 且 action=version', () => {
  const r = parseArgs(['--version'], { isTTY: false });
  assert.equal(r.action, 'version');
});

test('parseArgs: --stdin action=stdin', () => {
  const r = parseArgs(['--stdin'], { isTTY: false });
  assert.equal(r.action, 'stdin');
  assert.equal(r.stdin, true);
});

test('parseArgs: 默认模式 action=default 且 tag 默认值', () => {
  const r = parseArgs([], { isTTY: false });
  assert.equal(r.action, 'default');
  assert.equal(r.tag, DEFAULT_TAG);
  assert.equal(r.serial, undefined);
  assert.equal(r.useColor, false);
});

test('parseArgs: --serial / --tag 透传', () => {
  const r = parseArgs(['--serial', 'emulator-5554', '--tag', 'MYTAG'], {
    isTTY: false,
  });
  assert.equal(r.serial, 'emulator-5554');
  assert.equal(r.tag, 'MYTAG');
  assert.equal(r.action, 'default');
});

test('parseArgs: --serial= 与 --tag= 等号写法', () => {
  const r = parseArgs(['--serial=emulator-1', '--tag=TAG2'], { isTTY: false });
  assert.equal(r.serial, 'emulator-1');
  assert.equal(r.tag, 'TAG2');
});

test('parseArgs: TTY 时默认启用 color，--no-color 关闭', () => {
  assert.equal(parseArgs([], { isTTY: true }).useColor, true);
  assert.equal(parseArgs(['--no-color'], { isTTY: true }).useColor, false);
  assert.equal(parseArgs([], { isTTY: false }).useColor, false);
});

test('parseArgs: --serial 缺参数抛 ArgError', () => {
  assert.throws(() => parseArgs(['--serial'], { isTTY: false }), ArgError);
});

test('parseArgs: --tag 缺参数抛 ArgError', () => {
  assert.throws(() => parseArgs(['--tag'], { isTTY: false }), ArgError);
});

test('parseArgs: --serial= 空值抛 ArgError', () => {
  assert.throws(() => parseArgs(['--serial='], { isTTY: false }), ArgError);
});

test('parseArgs: 未知参数抛 ArgError 且消息中文', () => {
  try {
    parseArgs(['--bogus'], { isTTY: false });
    assert.fail('应抛错');
  } catch (e) {
    assert.ok(e instanceof ArgError);
    assert.ok(e.message.includes('未知参数'));
    assert.ok(e.message.includes('--bogus'));
  }
});

test('parseLogcatLine: 识别完整事件', () => {
  const line = '07-13 10:00:00.123 EVENT_START|LOG|{"message":"hi"}|EVENT_END';
  const p = parseLogcatLine(line);
  assert.ok(p);
  assert.equal(p.event, 'LOG');
  assert.equal(p.data.message, 'hi');
  assert.equal(p.time, '07-13 10:00:00.123');
});

test('parseLogcatLine: 非 结构化行返回 null', () => {
  assert.equal(parseLogcatLine('some random adb noise'), null);
  assert.equal(parseLogcatLine(''), null);
  assert.equal(parseLogcatLine('EVENT_START|LOG|{"message":"x"}'), null);
});

test('parseLogcatLine: 缺 EVENT_END 返回 null', () => {
  assert.equal(parseLogcatLine('EVENT_START|LOG|{}'), null);
});

test('formatLine: LOG 事件渲染且不崩溃', () => {
  const out = formatLine('EVENT_START|LOG|{"message":"hello"}|EVENT_END', {
    useColor: false,
  });
  assert.ok(out.lines.length > 0);
  assert.ok(out.lines.join(' ').includes('hello'));
  assert.ok(out.lines.join(' ').includes('[LOG]'));
  assert.equal(out.inline, false);
});

test('formatLine: 畸形 JSON 回退为 message 且不崩溃', () => {
  const out = formatLine('EVENT_START|LOG|not-a-json|EVENT_END', {
    useColor: false,
  });
  const text = out.lines.join('\n');
  assert.ok(text.includes('not-a-json'));
});

test('formatLine: CONTEXT_ASSEMBLY 完整字段', () => {
  const payload = {
    model: 'gpt-test',
    temperature: 0.7,
    enableWebSearch: true,
    enableKnowledgeSearch: false,
    enableMemorySearch: true,
    messageCount: 5,
    inputChars: 1234,
  };
  const out = formatLine(`EVENT_START|CONTEXT_ASSEMBLY|${JSON.stringify(payload)}|EVENT_END`, {
    useColor: false,
  });
  const text = out.lines.join('\n');
  assert.ok(text.includes('gpt-test'));
  assert.ok(text.includes('0.7'));
  assert.ok(text.includes('messages_count'));
  assert.ok(text.includes('5'));
  assert.ok(text.includes('input_chars'));
  assert.ok(text.includes('1234'));
  assert.ok(text.includes('[CONTEXT ASSEMBLY]'));
});

test('formatLine: CONTEXT_ASSEMBLY 缺字段不崩溃', () => {
  const out = formatLine('EVENT_START|CONTEXT_ASSEMBLY|{}|EVENT_END', {
    useColor: false,
  });
  const text = out.lines.join('\n');
  assert.ok(text.includes('[CONTEXT ASSEMBLY]'));
  assert.ok(text.includes('?'));
  assert.ok(text.includes('ready'));
});

test('formatLine: HTTP_REQUEST', () => {
  const out = formatLine(
    'EVENT_START|HTTP_REQUEST|{"url":"https://api.example.com/v1","method":"POST"}|EVENT_END',
    { useColor: false }
  );
  const text = out.lines.join('\n');
  assert.ok(text.includes('POST'));
  assert.ok(text.includes('https://api.example.com/v1'));
  assert.ok(text.includes('[HTTP REQUEST]'));
});

test('formatLine: HTTP_STREAM_CHUNK inline 且含 CPS', () => {
  const out = formatLine(
    'EVENT_START|HTTP_STREAM_CHUNK|{"totalBytes":128,"totalTokens":10,"cps":42}|EVENT_END',
    { useColor: false }
  );
  const text = out.lines.join('\n');
  assert.ok(text.includes('128'));
  assert.ok(text.includes('CPS'));
  assert.ok(text.includes('42'));
  assert.equal(out.inline, true);
});

test('formatLine: HTTP_RESPONSE 缺字段不崩溃', () => {
  const out = formatLine('EVENT_START|HTTP_RESPONSE|{}|EVENT_END', {
    useColor: false,
  });
  const text = out.lines.join('\n');
  assert.ok(text.includes('[HTTP RESPONSE]'));
  assert.ok(text.includes('Code: ?'));
});

test('formatLine: HTTP_RESPONSE 含 code', () => {
  const out = formatLine(
    'EVENT_START|HTTP_RESPONSE|{"code":200,"elapsedMs":15,"bytes":2048}|EVENT_END',
    { useColor: false }
  );
  const text = out.lines.join('\n');
  assert.ok(text.includes('200'));
  assert.ok(text.includes('elapsed'));
  assert.ok(text.includes('15'));
  assert.ok(text.includes('bytes'));
});

test('formatLine: LLM_COMPLETE', () => {
  const out = formatLine(
    'EVENT_START|LLM_COMPLETE|{"model":"gpt-test","status":"completed"}|EVENT_END',
    { useColor: false }
  );
  const text = out.lines.join('\n');
  assert.ok(text.includes('gpt-test'));
  assert.ok(text.includes('completed'));
  assert.ok(text.includes('[GEN COMPLETED]'));
});

test('formatLine: ERROR 完整字段（含 stacktrace）', () => {
  const payload = {
    tag: 'ChatViewModel',
    error: 'boom',
    stacktrace: 'at foo()\nat bar()',
  };
  const out = formatLine(`EVENT_START|ERROR|${JSON.stringify(payload)}|EVENT_END`, {
    useColor: false,
  });
  const text = out.lines.join('\n');
  assert.ok(text.includes('ChatViewModel'));
  assert.ok(text.includes('boom'));
  assert.ok(text.includes('at foo()'));
  assert.ok(text.includes('[ERROR DETECTED'));
});

test('formatLine: ERROR 缺字段不崩溃且显示兜底文案', () => {
  const out = formatLine('EVENT_START|ERROR|{}|EVENT_END', { useColor: false });
  const text = out.lines.join('\n');
  assert.ok(text.includes('未知'));
  assert.ok(text.includes('无信息'));
});

test('formatLine: ERROR 兼容旧 message/stacktrace 字段', () => {
  const payload = { message: 'legacy msg', stacktrace: 'at x()' };
  const out = formatLine(`EVENT_START|ERROR|${JSON.stringify(payload)}|EVENT_END`, {
    useColor: false,
  });
  const text = out.lines.join('\n');
  assert.ok(text.includes('legacy msg'));
  assert.ok(text.includes('at x()'));
});

test('formatLine: RAG 各子类不崩溃', () => {
  const cases = [
    { message: 'Extracting chunk 1/5', expect: ['Chunk 1/5', '[KG EXTRACT]'] },
    { message: 'returned error: oops', expect: ['oops'] },
    { message: 'TIMEOUT occurred', expect: ['[KG TIMEOUT]'] },
    { message: 'EMPTY result', expect: ['[KG EMPTY]'] },
    { message: 'LLM response received: ok', expect: ['[KG LLM]'] },
    { message: 'Parse result {...}', expect: ['[KG PARSE]'] },
    { message: 'random note', expect: ['[LOG]'] },
  ];
  for (const c of cases) {
    const out = formatLine(`EVENT_START|RAG|${JSON.stringify({ message: c.message })}|EVENT_END`, {
      useColor: false,
    });
    const text = out.lines.join('\n');
    for (const e of c.expect) {
      assert.ok(text.includes(e), `RAG "${c.message}" 应包含 "${e}"，实际：${text}`);
    }
  }
});

test('formatLine: RAG 缺 message 不崩溃', () => {
  const out = formatLine('EVENT_START|RAG|{}|EVENT_END', { useColor: false });
  const text = out.lines.join('\n');
  assert.ok(text.includes('[LOG]'));
});

test('formatLine: DB_QUERY 兼容真实 operation/argumentCount', () => {
  const out = formatLine(
    'EVENT_START|DB_QUERY|{"operation":"SELECT","argumentCount":3}|EVENT_END',
    { useColor: false }
  );
  const text = out.lines.join('\n');
  assert.ok(text.includes('SELECT'));
  assert.ok(text.includes('args'));
  assert.ok(text.includes('3'));
  assert.ok(text.includes('[ROOM SQL]'));
});

test('formatLine: DB_QUERY 兼容旧 sql/bindArgs', () => {
  const out = formatLine(
    'EVENT_START|DB_QUERY|{"sql":"SELECT * FROM sessions","bindArgs":[1,"a"]}|EVENT_END',
    { useColor: false }
  );
  const text = out.lines.join('\n');
  assert.ok(text.includes('SELECT * FROM sessions'));
  assert.ok(text.includes('[1,"a"]'));
});

test('formatLine: DB_QUERY 缺字段不崩溃', () => {
  const out = formatLine('EVENT_START|DB_QUERY|{}|EVENT_END', { useColor: false });
  const text = out.lines.join('\n');
  assert.ok(text.includes('[ROOM SQL]'));
  assert.ok(text.includes('?'));
});

test('formatLine: 未知事件不崩溃', () => {
  const out = formatLine(
    'EVENT_START|WEIRD_EVENT|{"message":"something"}|EVENT_END',
    { useColor: false }
  );
  const text = out.lines.join('\n');
  assert.ok(text.includes('[WEIRD_EVENT]'));
  assert.ok(text.includes('something'));
});

test('formatLine: 未知事件空 data 不崩溃', () => {
  const out = formatLine('EVENT_START|WEIRD||EVENT_END', { useColor: false });
  const text = out.lines.join('\n');
  assert.ok(text.includes('[WEIRD]'));
});

test('formatLine: 非 TTY 输出不含 ANSI 且不含 emoji', () => {
  const out = formatLine(
    'EVENT_START|LOG|{"message":"plain"}|EVENT_END',
    { useColor: false }
  );
  const text = out.lines.join('\n');
  assert.equal(ANSI_RE.test(text), false, `不应含 ANSI：${JSON.stringify(text)}`);
  assert.equal(/[\u{1F300}-\u{1FAFF}\u{2600}-\u{27BF}]/u.test(text), false, '不应含 emoji');
});

test('formatLine: TTY 输出含 ANSI', () => {
  const out = formatLine(
    'EVENT_START|LOG|{"message":"colorful"}|EVENT_END',
    { useColor: true }
  );
  const text = out.lines.join('\n');
  assert.ok(ANSI_RE.test(text), '应含 ANSI 转义');
});

test('renderEvent: data 为非对象时不崩溃', () => {
  const out = renderEvent('LOG', null, '10:00:00', false);
  assert.ok(Array.isArray(out.lines));
  assert.equal(out.inline, false);
});

test('dispatch: --help 不启动 adb', async () => {
  let spawned = 0;
  const spawnFn = () => {
    spawned++;
    return makeFakeChild();
  };
  const stdout = makeWritable();
  const code = await dispatch('help', { useColor: false }, { spawnFn, stdout });
  assert.equal(code, 0);
  assert.equal(spawned, 0, 'help 不应启动 adb');
  assert.ok(stdout.captured.includes('--help'));
  assert.ok(stdout.captured.includes('用法'));
});

test('dispatch: --version 不启动 adb', async () => {
  let spawned = 0;
  const spawnFn = () => {
    spawned++;
    return makeFakeChild();
  };
  const stdout = makeWritable();
  const code = await dispatch('version', { useColor: false }, { spawnFn, stdout });
  assert.equal(code, 0);
  assert.equal(spawned, 0, 'version 不应启动 adb');
  assert.ok(stdout.captured.includes(VERSION));
});

test('dispatch: stdin 模式解析多种事件', async () => {
  const fixture = [
    'EVENT_START|LOG|{"message":"start"}|EVENT_END',
    'EVENT_START|CONTEXT_ASSEMBLY|{"model":"m","temperature":0.5,"enableWebSearch":true,"enableKnowledgeSearch":false,"enableMemorySearch":true,"messageCount":2,"inputChars":50}|EVENT_END',
    'EVENT_START|HTTP_REQUEST|{"url":"https://x","method":"POST"}|EVENT_END',
    'EVENT_START|HTTP_RESPONSE|{"code":200}|EVENT_END',
    'EVENT_START|ERROR|{"tag":"T","error":"e"}|EVENT_END',
    'EVENT_START|DB_QUERY|{"operation":"SELECT","argumentCount":1}|EVENT_END',
    'EVENT_START|UNKNOWN_EVT|{"message":"zzz"}|EVENT_END',
    'EVENT_START|LOG|not-json|EVENT_END',
    'EVENT_START|CONTEXT_ASSEMBLY|{}|EVENT_END',
    'EVENT_START|ERROR|{}|EVENT_END',
    'raw non-structured line',
  ].join('\n');
  const input = makeInput([fixture]);
  const stdout = makeWritable();
  const code = await dispatch('stdin', { useColor: false }, { input, stdout });
  assert.equal(code, 0);
  const text = stdout.captured;
  assert.ok(text.includes('start'));
  assert.ok(text.includes('m'));
  assert.ok(text.includes('POST'));
  assert.ok(text.includes('Code: 200'));
  assert.ok(text.includes('T'));
  assert.ok(text.includes('SELECT'));
  assert.ok(text.includes('[UNKNOWN_EVT]'));
  assert.ok(text.includes('not-json'));
  assert.ok(text.includes('ready'));
  assert.ok(text.includes('无信息'));
});

test('dispatch: stdin 模式无 ANSI/emoji（非 TTY）', async () => {
  const fixture = 'EVENT_START|LOG|{"message":"x"}|EVENT_END\n';
  const input = makeInput([fixture]);
  const stdout = makeWritable();
  await dispatch('stdin', { useColor: false }, { input, stdout });
  assert.equal(ANSI_RE.test(stdout.captured), false);
  assert.equal(
    /[\u{1F300}-\u{1FAFF}\u{2600}-\u{27BF}]/u.test(stdout.captured),
    false
  );
});

test('dispatch: adb ENOENT 返回 127 并中文提示', async () => {
  const child = makeFakeChild();
  const spawnFn = () => child;
  const stdout = makeWritable();
  const stderr = makeWritable();
  const p = dispatch('default', { useColor: false, tag: DEFAULT_TAG }, { spawnFn, stdout, stderr });
  child.emit('error', Object.assign(new Error('spawn adb ENOENT'), { code: 'ENOENT' }));
  const code = await p;
  assert.equal(code, 127);
  assert.ok(stderr.captured.includes('未找到 adb'));
  assert.ok(stderr.captured.includes('adb devices'));
});

test('dispatch: adb 异常退出 返回 126 并中文提示', async () => {
  const child = makeFakeChild();
  const spawnFn = () => child;
  const stdout = makeWritable();
  const stderr = makeWritable();
  const p = dispatch('default', { useColor: false, tag: DEFAULT_TAG }, { spawnFn, stdout, stderr });
  child.stderr.emit('data', Buffer.from('adb: some fatal error'));
  child.emit('exit', 1, null);
  const code = await p;
  assert.equal(code, 126);
  assert.ok(stderr.captured.includes('异常退出'));
  assert.ok(stderr.captured.includes('some fatal error'));
});

test('dispatch: adb 无设备 返回 126 并中文提示', async () => {
  const child = makeFakeChild();
  let killed = false;
  child.kill = () => {
    killed = true;
    return true;
  };
  const spawnFn = () => child;
  const stdout = makeWritable();
  const stderr = makeWritable();
  const p = dispatch('default', { useColor: false, tag: DEFAULT_TAG }, { spawnFn, stdout, stderr });
  child.stderr.emit('data', Buffer.from('error: no devices/emulators found'));
  const code = await p;
  assert.equal(code, 126);
  assert.ok(stderr.captured.includes('未检测到'));
  assert.ok(stderr.captured.includes('adb devices'));
  assert.equal(killed, true);
});

test('dispatch: adb 正常退出 返回 0', async () => {
  const child = makeFakeChild();
  const spawnFn = () => child;
  const stdout = makeWritable();
  const stderr = makeWritable();
  const p = dispatch('default', { useColor: false, tag: DEFAULT_TAG }, { spawnFn, stdout, stderr });
  child.emit('exit', 0, null);
  const code = await p;
  assert.equal(code, 0);
});

test('dispatch: adb stdout 流式解析事件', async () => {
  const child = makeFakeChild();
  const spawnFn = () => child;
  const stdout = makeWritable();
  const stderr = makeWritable();
  const p = dispatch('default', { useColor: false, tag: DEFAULT_TAG }, { spawnFn, stdout, stderr });
  child.stdout.emit('data', Buffer.from('EVENT_START|LOG|{"message":"streamed"}|EVENT_END\n'));
  child.emit('exit', 0, null);
  const code = await p;
  assert.equal(code, 0);
  assert.ok(stdout.captured.includes('streamed'));
});

test('dispatch: adb 被信号终止 返回 126', async () => {
  const child = makeFakeChild();
  const spawnFn = () => child;
  const stdout = makeWritable();
  const stderr = makeWritable();
  const p = dispatch('default', { useColor: false, tag: DEFAULT_TAG }, { spawnFn, stdout, stderr });
  child.emit('exit', null, 'SIGTERM');
  const code = await p;
  assert.equal(code, 126);
  assert.ok(stderr.captured.includes('SIGTERM'));
});

test('dispatch: --serial 与 --tag 透传到 adb 命令', async () => {
  let capturedCmd;
  let capturedArgs;
  const child = makeFakeChild();
  const spawnFn = (cmd, args) => {
    capturedCmd = cmd;
    capturedArgs = args;
    return child;
  };
  const p = dispatch(
    'default',
    { useColor: false, serial: 'emulator-5554', tag: 'MYTAG' },
    { spawnFn, stdout: makeWritable(), stderr: makeWritable() }
  );
  child.emit('exit', 0, null);
  await p;
  assert.equal(capturedCmd, 'adb');
  assert.deepEqual(capturedArgs, ['-s', 'emulator-5554', 'logcat', '-s', 'MYTAG']);
});

test('dispatch: 默认 tag 透传', async () => {
  let capturedArgs;
  const child = makeFakeChild();
  const spawnFn = (_cmd, args) => {
    capturedArgs = args;
    return child;
  };
  const p = dispatch(
    'default',
    { useColor: false, tag: DEFAULT_TAG },
    { spawnFn, stdout: makeWritable(), stderr: makeWritable() }
  );
  child.emit('exit', 0, null);
  await p;
  assert.deepEqual(capturedArgs, ['logcat', '-s', DEFAULT_TAG]);
});
