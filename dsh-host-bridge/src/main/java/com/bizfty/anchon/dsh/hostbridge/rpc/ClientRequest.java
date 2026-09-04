package com.bizfty.anchon.dsh.hostbridge.rpc;

import tools.jackson.databind.JsonNode;

/**
 * 官方 unary RPC 请求信封 {@code ClientRequest}（connection/src/rpc.ts）。
 * 严格形状：{type:'client-request', rpcId, method, payload}，多/缺字段一律拒绝。
 *
 * @param rpcId   客户端随机 correlation id，响应必须回显
 * @param method  与 URL 尾段一致的 endpoint（如 {@code settings.view}）
 * @param payload 任意 JSON
 */
public record ClientRequest(String rpcId, String method, JsonNode payload) {
    public static final String TYPE = "client-request";
}
