package com.bizfty.anchon.dsh.hostbridge.rpc;

import com.bizfty.anchon.dsh.hostbridge.json.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * unary RPC 信封编解码（rpc.ts / rpc-host.ts 语义）。
 *
 * <p>请求解析执行 exact-key 校验（官方 parse 纪律）；响应按
 * {@code ServerResponse {type:'server-response', rpcId, result}} 渲染，
 * result 为 {@code {ok:true,value}} 或 {@code {ok:false,error:{code,message,details}}}。
 */
public final class RpcCodec {

    private static final Set<String> REQUEST_KEYS = Set.of("type", "rpcId", "method", "payload");
    private static final Set<String> OK_KEYS = Set.of("ok", "value");
    private static final Set<String> ERROR_KEYS = Set.of("ok", "error");
    private static final Set<String> FAILURE_KEYS = Set.of("code", "message", "details");
    private static final Pattern ENDPOINT_PATTERN = Pattern.compile("[A-Za-z0-9_$.-]+(/[A-Za-z0-9_$.-]+)*");
    private static final Pattern RPC_ID_PATTERN = Pattern.compile("^[\\x21-\\x7E]{1,128}$");

    private RpcCodec() {
    }

    /**
     * 解析请求信封文本。
     *
     * @throws RpcEnvelopeException 信封形状非法（调用方回 400）
     */
    public static ClientRequest parseRequest(String body) {
        ObjectNode node = Json.parseObject(body);
        if (node == null || !exactKeys(node, REQUEST_KEYS)) {
            throw new RpcEnvelopeException("connection: invalid client-request envelope");
        }
        if (!ClientRequest.TYPE.equals(node.path("type").asText())) {
            throw new RpcEnvelopeException("connection: invalid client-request type");
        }
        String rpcId = node.path("rpcId").asText();
        String method = node.path("method").asText();
        JsonNode payload = node.get("payload");
        if (rpcId.isEmpty() || !RPC_ID_PATTERN.matcher(rpcId).matches()
                || method.isEmpty() || !ENDPOINT_PATTERN.matcher(method).matches()) {
            throw new RpcEnvelopeException("connection: invalid client-request rpcId or method");
        }
        return new ClientRequest(rpcId, method, payload);
    }

    /** result = {ok:true, value}。 */
    public static JsonNode okResult(JsonNode value) {
        ObjectNode result = Json.object();
        result.put("ok", true);
        result.set("value", value == null ? Json.object() : value);
        return result;
    }

    /** result = {ok:false, error:{code,message,details}}。 */
    public static JsonNode errorResult(String code, String message, JsonNode details) {
        ObjectNode result = Json.object();
        result.put("ok", false);
        ObjectNode error = Json.object();
        error.put("code", code);
        error.put("message", message);
        error.set("details", details == null ? Json.object() : details);
        result.set("error", error);
        return result;
    }

    /** 完整 {@code server-response} 文本。 */
    public static String renderResponse(String rpcId, JsonNode result) {
        ObjectNode response = Json.object();
        response.put("type", "server-response");
        response.put("rpcId", rpcId);
        response.set("result", result);
        return Json.write(response);
    }

    /** exact-key 形状断言（多余字段即拒绝，对齐官方 parse 纪律）。 */
    public static boolean exactKeys(ObjectNode node, Set<String> allowed) {
        if (node.size() != allowed.size()) {
            return false;
        }
        for (var entry : node.properties()) {
            if (!allowed.contains(entry.getKey())) {
                return false;
            }
        }
        return true;
    }

    /** 信封形状非法信号（HTTP 400）。 */
    public static final class RpcEnvelopeException extends RuntimeException {
        public RpcEnvelopeException(String message) {
            super(message);
        }
    }
}
