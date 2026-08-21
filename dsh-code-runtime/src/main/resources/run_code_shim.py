#!/usr/bin/env python3
# run_code 运行时 shim (Python) — 加载模型写的 Python 程序，提供 tools 绑定（行分隔 JSON-RPC over stdio）
import asyncio
import io
import json
import os
import sys
import threading

CODE_FILE = sys.argv[1]
with open(CODE_FILE, encoding="utf-8") as f:
    CODE = f.read()

# 协议写入走原始 fd，避免与程序 stdout 捕获冲突
REAL_STDOUT = os.fdopen(os.dup(1), "w", encoding="utf-8")
PROTOCOL_FD = os.dup(1)


def proto(obj):
    os.write(PROTOCOL_FD, (json.dumps(obj) + "\n").encode("utf-8"))


loop = asyncio.new_event_loop()
pending = {}
next_id = [1]


def reader_thread():
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            msg = json.loads(line)
        except Exception:
            continue
        rid = msg.get("id")
        if rid is not None and rid in pending:
            fut = pending.pop(rid)
            if msg.get("error"):
                loop.call_soon_threadsafe(fut.set_exception, RuntimeError(msg["error"]))
            else:
                loop.call_soon_threadsafe(fut.set_result, msg.get("result"))


threading.Thread(target=reader_thread, daemon=True).start()


async def request(name, args):
    rid = next_id[0]
    next_id[0] += 1
    fut = loop.create_future()
    pending[rid] = fut
    proto({"id": rid, "name": name, "args": args or {}})
    return await fut


class Tools:
    def __getattr__(self, name):
        def call(args=None):
            return request(name, args or {})
        return call


class LogCapture:
    def __init__(self):
        self.buffer = io.StringIO()

    def write(self, s):
        self.buffer.write(s)
        REAL_STDOUT.write(s)
        return len(s)

    def flush(self):
        REAL_STDOUT.flush()


logs_capture = LogCapture()
orig_stdout = sys.stdout
sys.stdout = logs_capture


def finish(payload, exit_code):
    logs_capture.flush()
    os.write(PROTOCOL_FD, ("__DSH_RESULT__" + json.dumps(payload) + "\n").encode("utf-8"))
    try:
        os.close(PROTOCOL_FD)
    except Exception:
        pass
    # os._exit 跳过一切异常处理/finally，避免二次 finish 崩溃
    os._exit(exit_code)


async def main_wrapper():
    ns = {"tools": Tools()}
    exec("async def __run():\n" + "\n".join("    " + ln for ln in CODE.splitlines()), ns)
    return await ns["__run"]()


try:
    result = loop.run_until_complete(main_wrapper())
    finish({"logs": logs_capture.buffer.getvalue().splitlines(), "result": result}, 0)
except Exception as e:  # 程序异常作为错误返回；SystemExit/KeyboardInterrupt 不拦截
    finish({"logs": logs_capture.buffer.getvalue().splitlines(), "error": str(e)}, 1)
