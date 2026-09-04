package com.bizfty.anchon.dsh.hostbridge.rpc;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Remote 信封 payload 解包（官方 gateway 语义：payload 必须为
 * {@code {args: <plain-object>}}——"exactly one plain-object args field"，
 * 见 gateway.host.spec.ts）。所有 typert 方法参数按名装入 args
 * （agent 序列化为 {@code agentId}；request 对象整体入 {@code args.request}）。
 */
public final class RpcArgs {

    private RpcArgs() {
    }

    /**
     * 从 unary payload 取 args 对象。
     *
     * @param payload 信封 payload（可为 null）
     * @return args ObjectNode
     * @throws RpcCodec.RpcEnvelopeException payload 形状非法（非 {@code {args:{…}}}）
     */
    public static ObjectNode args(JsonNode payload) {
        if (payload == null || !payload.isObject() || payload.size() != 1) {
            throw new RpcCodec.RpcEnvelopeException("gateway: requires exactly one plain-object args field");
        }
        JsonNode args = payload.get("args");
        if (args == null || !args.isObject()) {
            throw new RpcCodec.RpcEnvelopeException("gateway: requires exactly one plain-object args field");
        }
        return (ObjectNode) args;
    }
}
