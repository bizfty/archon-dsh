package com.bizfty.anchon.dsh.hostbridge.rpc;

import com.bizfty.anchon.dsh.hostbridge.json.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * unary RPC 服务门面：endpoint + body → HTTP 语义与信封 JSON。
 * 与 Spring 解耦（纯 POJO 可测），Controller 只是薄壳。
 *
 * <p>语义（contract-host-a2.md §2）：形状非法 400；endpoint 未认领 404；
 * 业务结果 200 + {@code server-response} 信封（失败时信封内 ok:false，HTTP 仍 200）。
 */
public final class RpcBridgeService {

    private final RpcDispatcher dispatcher;

    public RpcBridgeService(RpcDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    /** 结果三元组：HTTP 状态 + 响应体（可为 null）。 */
    public record BridgeResponse(int status, String body) {
    }

    public BridgeResponse handle(String endpoint, String body) {
        ClientRequest request;
        try {
            request = RpcCodec.parseRequest(body);
        } catch (RpcCodec.RpcEnvelopeException e) {
            return new BridgeResponse(400, e.getMessage());
        }
        JsonNode result = dispatcher.dispatch(request);
        if (result == null) {
            return new BridgeResponse(404, "not found");
        }
        return new BridgeResponse(200, RpcCodec.renderResponse(request.rpcId(), result));
    }

    /** 内置连通性端点：ping → {"pong":true}；echo → 原样回显 payload。 */
    public static void registerBuiltins(RpcDispatcher dispatcher) {
        dispatcher.register("ping", payload -> {
            ObjectNode value = Json.object();
            value.put("pong", true);
            return value;
        });
        dispatcher.register("echo", payload -> payload == null ? Json.object() : payload);
    }
}
