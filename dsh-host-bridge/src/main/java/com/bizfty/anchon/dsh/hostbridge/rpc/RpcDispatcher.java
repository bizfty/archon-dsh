package com.bizfty.anchon.dsh.hostbridge.rpc;

import tools.jackson.databind.JsonNode;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 共享 /api 通道的 endpoint 分发器（rpc-host.ts interceptor 语义）。
 * endpoint 以 URL 尾段（单段，字符集 {@code [A-Za-z0-9_$.-]+}）为键；未注册返回 null（→ HTTP 404）。
 */
public final class RpcDispatcher {

    /** 一个 unary endpoint 处理器：payload → 业务 value（抛 RpcHandlerException 表示业务失败）。 */
    public interface EndpointHandler {
        JsonNode handle(JsonNode payload);
    }

    private final Map<String, EndpointHandler> handlers = new ConcurrentHashMap<>();

    public void register(String endpoint, EndpointHandler handler) {
        if (handlers.putIfAbsent(endpoint, handler) != null) {
            throw new IllegalStateException("hostbridge: duplicate RPC endpoint " + endpoint);
        }
    }

    /** 已注册的 endpoint（共享通道单 interceptor 语义下的枚举，供测试/审计）。 */
    public boolean has(String endpoint) {
        return handlers.containsKey(endpoint);
    }

    /**
     * 分发一个请求。
     *
     * @return null = endpoint 未认领（404）；否则为 result JsonNode（ok/error 已定型）
     */
    public JsonNode dispatch(ClientRequest request) {
        EndpointHandler handler = handlers.get(request.method());
        if (handler == null) {
            return null;
        }
        try {
            JsonNode value = handler.handle(request.payload());
            return RpcCodec.okResult(value);
        } catch (RpcHandlerException e) {
            return RpcCodec.errorResult(e.code(), e.getMessage(), e.details());
        } catch (RuntimeException e) {
            return RpcCodec.errorResult("gateway/internal", String.valueOf(e.getMessage()), null);
        }
    }

    /** 业务失败信号（信封 result.ok=false，HTTP 仍 200）。 */
    public static final class RpcHandlerException extends RuntimeException {
        private final String code;
        private final JsonNode details;

        public RpcHandlerException(String code, String message, JsonNode details) {
            super(message);
            this.code = code;
            this.details = details;
        }

        public String code() {
            return code;
        }

        public JsonNode details() {
            return details;
        }
    }
}
