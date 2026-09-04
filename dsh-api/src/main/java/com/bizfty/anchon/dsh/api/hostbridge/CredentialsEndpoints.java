package com.bizfty.anchon.dsh.api.hostbridge;

import com.bizfty.anchon.dsh.credentials.CredentialRef;
import com.bizfty.anchon.dsh.credentials.CredentialService;
import com.bizfty.anchon.dsh.hostbridge.json.Json;
import com.bizfty.anchon.dsh.hostbridge.rpc.RpcArgs;
import com.bizfty.anchon.dsh.hostbridge.rpc.RpcDispatcher;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.regex.Pattern;

/**
 * 官方 {@code credentials} Remote ns 的 archon 映射（contract-remote-java.md §2.3）。
 * describe(refs[]) / set(ref,value) / unset(ref)。ref 名为 {@code provider:key}
 * （缺省 provider 视为 env，与 archon CredentialRef.parse 对齐）；读路径永不回值。
 */
final class CredentialsEndpoints {

    private static final Pattern REF_PATTERN = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");
    private static final int MAX_DESCRIBE_REFS = 64;

    private CredentialsEndpoints() {
    }

    static void register(RpcDispatcher dispatcher, CredentialService credentials) {
        dispatcher.register("credentials/describe", payload -> {
            ObjectNode args = RpcArgs.args(payload);
            JsonNode refs = args.get("refs");
            if (refs == null || !refs.isArray() || refs.isEmpty()
                    || refs.size() > MAX_DESCRIBE_REFS) {
                throw new RpcDispatcher.RpcHandlerException("gateway/bad-request",
                        "credentials/describe expects 1..64 refs", Json.object());
            }
            ObjectNode out = Json.object();
            for (JsonNode refNode : refs) {
                String name = refNode.asText();
                if (!REF_PATTERN.matcher(name).matches()) {
                    throw new RpcDispatcher.RpcHandlerException("gateway/bad-request",
                            "invalid credential ref " + name, Json.object());
                }
                CredentialRef ref = CredentialRef.parse(name);
                ObjectNode info = out.putObject(name);
                info.put("configured", credentials.resolve(ref).isPresent());
                info.put("writable", true);
                if (!"env".equals(ref.provider())) {
                    info.put("source", ref.provider());
                }
            }
            return out;
        });

        dispatcher.register("credentials/set", payload -> {
            ObjectNode args = RpcArgs.args(payload);
            String ref = text(args, "ref");
            String value = text(args, "value");
            requireRef(ref);
            if (value == null || value.isEmpty()) {
                throw new RpcDispatcher.RpcHandlerException("gateway/bad-request",
                        "credentials/set expects a non-empty value", Json.object());
            }
            try {
                credentials.set(CredentialRef.parse(ref), value);
            } catch (RuntimeException e) {
                throw rejected(ref, e);
            }
            ObjectNode out = Json.object();
            out.put("set", true);
            return out;
        });

        dispatcher.register("credentials/unset", payload -> {
            ObjectNode args = RpcArgs.args(payload);
            String ref = text(args, "ref");
            requireRef(ref);
            try {
                credentials.unset(CredentialRef.parse(ref));
            } catch (RuntimeException e) {
                throw rejected(ref, e);
            }
            ObjectNode out = Json.object();
            out.put("unset", true);
            return out;
        });
    }

    private static String text(ObjectNode args, String key) {
        JsonNode node = args.get(key);
        return node == null || node.isNull() ? null : node.asText();
    }

    private static void requireRef(String ref) {
        if (ref == null || !REF_PATTERN.matcher(ref).matches()) {
            throw new RpcDispatcher.RpcHandlerException("gateway/bad-request",
                    "invalid credential ref", Json.object());
        }
    }

    /** provider 拒绝 → credential/rejected（details 只含 ref，绝不带值）。 */
    private static RpcDispatcher.RpcHandlerException rejected(String ref, RuntimeException cause) {
        ObjectNode details = Json.object();
        details.put("ref", ref);
        return new RpcDispatcher.RpcHandlerException("credential/rejected",
                String.valueOf(cause.getMessage()), details);
    }
}
