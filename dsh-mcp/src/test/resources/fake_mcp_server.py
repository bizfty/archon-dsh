#!/usr/bin/env python3
"""最小假 MCP 服务器 — 行分隔 JSON-RPC over stdio，用于 dsh-mcp 集成测试。"""
import json
import sys

TOOLS = [{
    "name": "py_echo",
    "description": "回显文本",
    "inputSchema": {"type": "object", "properties": {"text": {"type": "string", "description": "文本"}},
                    "required": ["text"]},
}]


def respond(obj):
    sys.stdout.write(json.dumps(obj) + "\n")
    sys.stdout.flush()


for line in sys.stdin:
    line = line.strip()
    if not line:
        continue
    try:
        msg = json.loads(line)
    except Exception:
        continue
    method = msg.get("method")
    msg_id = msg.get("id")
    if method == "initialize":
        respond({"jsonrpc": "2.0", "id": msg_id,
                 "result": {"protocolVersion": "2025-03-26", "capabilities": {"tools": {}},
                            "serverInfo": {"name": "fake-mcp", "version": "1.0"}}})
    elif method == "notifications/initialized":
        pass
    elif method == "ping":
        respond({"jsonrpc": "2.0", "id": msg_id, "result": {}})
    elif method == "tools/list":
        respond({"jsonrpc": "2.0", "id": msg_id, "result": {"tools": TOOLS}})
    elif method == "tools/call":
        params = msg.get("params", {}) or {}
        args = params.get("arguments", {}) or {}
        text = args.get("text", "")
        respond({"jsonrpc": "2.0", "id": msg_id,
                 "result": {"content": [{"type": "text", "text": "py-echo:" + text}], "isError": False}})
    elif msg_id is not None:
        respond({"jsonrpc": "2.0", "id": msg_id, "error": {"code": -32601, "message": "unknown method"}})
