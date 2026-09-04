package com.bizfty.anchon.dsh.api.hostbridge;

import com.bizfty.anchon.dsh.core.model.Session;
import com.bizfty.anchon.dsh.hostbridge.json.Json;
import com.bizfty.anchon.dsh.hostbridge.rpc.RpcArgs;
import com.bizfty.anchon.dsh.hostbridge.rpc.RpcDispatcher;
import com.bizfty.anchon.dsh.session.SessionService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 官方 {@code session} Remote ns 的 archon 冷读映射（contract-remote-java.md §2.1）。
 * 本轮实现只读/创建最小面：list / create / canOpenWorkspacePath；prompt 等命令面
 * 由既有 dsh-api 管线（P4 下一轮）接入。
 */
final class SessionEndpoints {

    private SessionEndpoints() {
    }

    static void register(RpcDispatcher dispatcher, SessionService sessions) {
        dispatcher.register("session/list", payload -> {
            ObjectNode out = Json.object();
            ArrayNode items = out.putArray("items");
            for (Session session : sessions.listSessions()) {
                items.add(summary(session));
            }
            return out;
        });

        dispatcher.register("session/create", payload -> {
            ObjectNode args = RpcArgs.args(payload);
            String cwd = args.hasNonNull("cwd") ? args.path("cwd").asText() : null;
            String model = args.hasNonNull("agentPreset") ? null : null; // model 留给部署默认
            Session session = sessions.createSession(null, model, cwd);
            ObjectNode out = Json.object();
            out.put("sessionId", session.id().value());
            return out;
        });

        dispatcher.register("session/canOpenWorkspacePath", payload -> {
            ObjectNode out = Json.object();
            out.put("value", false);
            return out;
        });
    }

    /** SessionSummary 投影：sessionId/updatedAt(ms)/running/blank/cwd。 */
    private static ObjectNode summary(Session session) {
        ObjectNode item = Json.object();
        item.put("sessionId", session.id().value());
        item.put("updatedAt", session.updatedAt().toEpochMilli());
        item.put("running", false);
        item.put("blank", false);
        if (session.cwd() != null) {
            item.put("cwd", session.cwd());
        }
        item.put("title", session.title());
        return item;
    }
}
