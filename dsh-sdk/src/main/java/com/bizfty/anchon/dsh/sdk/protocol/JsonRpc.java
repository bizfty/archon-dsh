package com.bizfty.anchon.dsh.sdk.protocol;

/**
 * JSON-RPC 2.0 消息（行分隔；对应 DSH sdk/protocol）。
 */
public final class JsonRpc {

    private JsonRpc() {
    }

    public static final String VERSION = "2.0";

    /** 请求。 */
    public record Request(String jsonrpc, String id, String method, Object params) {
    }

    /** 通知（无 id，不期待响应）。 */
    public record Notification(String jsonrpc, String method, Object params) {
    }

    /** 响应（result 与 error 二选一）。 */
    public record Response(String jsonrpc, String id, Object result, Error error) {

        public static Response ok(String id, Object result) {
            return new Response(VERSION, id, result, null);
        }

        public static Response fail(String id, int code, String message, Object data) {
            return new Response(VERSION, id, null, new Error(code, message, data));
        }
    }

    public record Error(int code, String message, Object data) {
    }

    /** 标准错误码（JSON-RPC 2.0 规范）。 */
    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;
}
