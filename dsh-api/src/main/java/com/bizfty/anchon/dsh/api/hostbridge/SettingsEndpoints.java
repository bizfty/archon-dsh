package com.bizfty.anchon.dsh.api.hostbridge;

import com.bizfty.anchon.dsh.hostbridge.json.Json;
import com.bizfty.anchon.dsh.hostbridge.mux.MuxSessionRegistry;
import com.bizfty.anchon.dsh.hostbridge.rpc.RpcArgs;
import com.bizfty.anchon.dsh.hostbridge.rpc.RpcCodec;
import com.bizfty.anchon.dsh.hostbridge.rpc.RpcDispatcher;
import com.bizfty.anchon.dsh.settings.SettingDescriptor;
import com.bizfty.anchon.dsh.settings.SettingsConflictException;
import com.bizfty.anchon.dsh.settings.SettingsPathOp;
import com.bizfty.anchon.dsh.settings.SettingsRedactor;
import com.bizfty.anchon.dsh.settings.SettingsService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 官方 {@code settings} Remote ns 的 archon 映射（contract-remote-java.md §2.2）。
 * 端点（endpoint = ns/method，payload = {@code {args}} 封装）：
 * describe / update / replace / mutate；写成功经 registry 广播
 * {@code settings/document-updated(ns, revision)} 事件（allowlist 下行）。
 *
 * <p>schema 字段：archon SettingDescriptor 树 → schemastery schema JSON 的精确
 * 翻译（P4+）未做，先发合法空表单 {@code {type:'object',fields:{}}} 保设置页不崩。
 */
final class SettingsEndpoints {

    private SettingsEndpoints() {
    }

    static void register(RpcDispatcher dispatcher, SettingsService settings, MuxSessionRegistry events) {
        dispatcher.register("settings/describe", payload -> {
            ObjectNode out = Json.object();
            out.put("writable", true);
            out.put("hasDocument", false);
            ArrayNode namespaces = out.putArray("namespaces");
            for (String ns : settings.describedNamespaces()) {
                namespaces.add(namespaceView(settings, ns));
            }
            return out;
        });

        dispatcher.register("settings/update", payload -> write(settings, events, payload, "update"));
        dispatcher.register("settings/replace", payload -> write(settings, events, payload, "replace"));
        dispatcher.register("settings/mutate", payload -> write(settings, events, payload, "mutate"));
    }

    /** 一个命名空间的 redacted view（SettingsNamespaceView 形状）。 */
    private static ObjectNode namespaceView(SettingsService settings, String ns) {
        List<SettingDescriptor> descriptors = settings.describe(ns);
        ObjectNode view = Json.object();
        view.put("ns", ns);
        // schema：合法空 object（schemastery 兼容占位；精确翻译见 TODO）
        ObjectNode schema = Json.object();
        schema.put("type", "object");
        schema.set("fields", Json.object());
        view.set("schema", schema);

        Map<String, Object> resolved = settings.all(ns);
        SettingsRedactor.Result redacted = SettingsRedactor.redact(descriptors, resolved);
        view.set("value", Json.value(redacted.value()));
        view.set("user", Json.value(SettingsRedactor.redact(descriptors, settings.userSection(ns)).value()));
        view.put("applies", settings.applies(ns));

        ArrayNode secrets = view.putArray("secrets");
        for (SettingsRedactor.Secret secret : redacted.secrets()) {
            ObjectNode slot = secrets.addObject();
            slot.put("set", secret.set());
            slot.set("path", Json.value(secret.path()));
        }
        view.put("revision", settings.revision(ns));
        return view;
    }

    /** update/replace/mutate 共写路径；SettingsConflictException → settings/conflict。 */
    private static ObjectNode write(SettingsService settings, MuxSessionRegistry events,
                                    JsonNode payload, String mode) {
        ObjectNode args = RpcArgs.args(payload);
        String ns = text(args, "ns");
        if (ns == null || ns.isEmpty()) {
            throw new RpcDispatcher.RpcHandlerException("gateway/bad-request",
                    "invalid payload for settings." + mode, Json.object());
        }
        Long expected = args.hasNonNull("expectedRevision") ? args.path("expectedRevision").asLong() : null;
        JsonNode input = args.get(mode.equals("mutate") ? "ops" : (mode.equals("update") ? "patch" : "section"));
        if (input == null) {
            throw new RpcDispatcher.RpcHandlerException("gateway/bad-request",
                    "invalid payload for settings." + mode, Json.object());
        }
        long revision;
        try {
            revision = switch (mode) {
                case "update" -> settings.update(ns, toMap(input), expected);
                case "replace" -> settings.replace(ns, toMap(input), expected);
                case "mutate" -> settings.mutate(ns, toOps(input), expected);
                default -> throw new IllegalStateException(mode);
            };
        } catch (SettingsConflictException e) {
            ObjectNode details = Json.object();
            details.put("ns", e.getNamespace());
            details.put("expected", e.getExpected());
            details.put("actual", e.getActual());
            throw new RpcDispatcher.RpcHandlerException("settings/conflict", e.getMessage(), details);
        }
        events.emitAll("settings/document-updated", Json.array(ns, revision));
        return namespaceView(settings, ns);
    }

    private static String text(ObjectNode args, String key) {
        JsonNode node = args.get(key);
        return node == null || node.isNull() ? null : node.asText();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new RpcDispatcher.RpcHandlerException("gateway/bad-request",
                    "expected an object", Json.object());
        }
        return (Map<String, Object>) Json.toJava(node);
    }

    private static List<SettingsPathOp> toOps(JsonNode array) {
        if (array == null || !array.isArray()) {
            throw new RpcDispatcher.RpcHandlerException("gateway/bad-request",
                    "expected an ops array", Json.object());
        }
        List<SettingsPathOp> ops = new ArrayList<>();
        for (JsonNode op : array) {
            List<String> path = new ArrayList<>();
            for (JsonNode seg : op.path("path")) {
                path.add(seg.asText());
            }
            JsonNode value = op.get("value");
            ops.add(new SettingsPathOp(op.path("op").asText(), path,
                    value == null ? null : Json.toJava(value)));
        }
        return ops;
    }
}
