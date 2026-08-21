// run_code 运行时 shim — 加载模型写的 JS 程序，提供 tools 绑定（行分隔 JSON-RPC over stdio）
const fs = require('fs');
const readline = require('readline');

const codeFile = process.argv[2];
const code = fs.readFileSync(codeFile, 'utf8');

const rl = readline.createInterface({ input: process.stdin });
const pending = new Map();
let nextId = 1;

rl.on('line', (line) => {
  if (!line) return;
  let msg;
  try {
    msg = JSON.parse(line);
  } catch (e) {
    return;
  }
  if (msg.id !== undefined && pending.has(msg.id)) {
    const { resolve, reject } = pending.get(msg.id);
    pending.delete(msg.id);
    if (msg.error) reject(new Error(msg.error));
    else resolve(msg.result);
  }
});

function callTool(name, args) {
  return new Promise((resolve, reject) => {
    const id = nextId++;
    pending.set(id, { resolve, reject });
    process.stdout.write(JSON.stringify({ id, name, args: args || {} }) + '\n');
  });
}

const tools = new Proxy({}, {
  get: (_, name) => (args) => callTool(name, args || {})
});

const logs = [];
const origLog = console.log;
console.log = (...a) => { logs.push(a.map(String).join(' ')); };

function finish(payload, exitCode) {
  process.stdout.write('__DSH_RESULT__' + JSON.stringify(payload) + '\n');
  process.exit(exitCode);
}

(async () => {
  const fn = new Function('tools', 'console', 'return (async () => { ' + code + '\n })()');
  const result = await fn(tools, console);
  finish({ logs, result: result === undefined ? null : result }, 0);
})().catch((e) => {
  finish({ logs, error: String((e && e.message) || e) }, 1);
});
