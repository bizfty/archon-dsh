package com.bizfty.anchon.dsh.settings;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Schemastery envelope 翻译测试：SettingDescriptor 树 → 官方 schema.toJSON() 引用图。
 *
 * <p>覆盖：uid/refs 图完整性与根 uid=0；扁平多键聚合；number/integer 的 type 归一与
 * min/max/step 元数据；object 递归 dict；array inner；enum → union+const；secret →
 * meta.role；visibleWhen → meta.visibleWhen；default 语义（object/array 空容器、标量默认值）。
 */
class SchemasteryEnvelopeTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> ref(Map<String, Object> envelope, int uid) {
        Map<String, Object> refs = (Map<String, Object>) envelope.get("refs");
        Object node = refs.get(Integer.toString(uid));
        assertNotNull(node, "refs[" + uid + "] missing");
        return (Map<String, Object>) node;
    }

    private static Map<String, Object> meta(Map<String, Object> node) {
        Object meta = node.get("meta");
        assertInstanceOf(Map.class, meta, "node meta should be a map");
        return (Map<String, Object>) meta;
    }

    @Test
    void flatLeavesAggregateIntoObjectRoot() {
        Map<String, Object> envelope = SchemasteryEnvelope.toEnvelope(List.of(
                SettingDescriptor.leaf("temperature", SettingDescriptor.Types.NUMBER,
                        "温度", "模型采样温度", 0.7, null, 0.0, 2.0, 0.1),
                SettingDescriptor.leaf("max-steps", SettingDescriptor.Types.INTEGER,
                        "最大步数", "单 turn 步数上限", 64, null, 1.0, 10000.0, 1.0),
                SettingDescriptor.leaf("theme", SettingDescriptor.Types.STRING,
                        "主题", "界面主题", "dark", null, null, null, null)));

        assertEquals(0, envelope.get("uid"));
        Map<String, Object> root = ref(envelope, 0);
        assertEquals("object", root.get("type"));
        assertEquals(Map.of(), meta(root).get("default"));

        Map<String, Object> dict = (Map<String, Object>) root.get("dict");
        assertEquals(List.of("temperature", "max-steps", "theme"), List.copyOf(dict.keySet()));
        // 子 uid 自 1 起按注册序
        assertEquals(1, dict.get("temperature"));
        assertEquals(2, dict.get("max-steps"));
        assertEquals(3, dict.get("theme"));

        Map<String, Object> temp = ref(envelope, 1);
        assertEquals("number", temp.get("type"));
        Map<String, Object> tempMeta = meta(temp);
        assertEquals("温度", tempMeta.get("label"));
        assertEquals("模型采样温度", tempMeta.get("description"));
        assertEquals(0.7, tempMeta.get("default"));
        assertEquals(0.0, tempMeta.get("min"));
        assertEquals(2.0, tempMeta.get("max"));
        assertEquals(0.1, tempMeta.get("step"));

        Map<String, Object> steps = ref(envelope, 2);
        assertEquals("number", steps.get("type"), "integer 归一为 schemastery number（无独立 resolver）");
        assertEquals(64, meta(steps).get("default"));
        assertEquals(1.0, meta(steps).get("step"));
        // string 叶不携带数值边界
        assertNull(meta(ref(envelope, 3)).get("min"));
        // 恰好 4 个 refs（root + 3 叶），无悬挂
        assertEquals(4, ((Map<?, ?>) envelope.get("refs")).size());
    }

    @Test
    void groupListEnumSecretVisibleWhenTranslate() {
        SettingDescriptor theme = SettingDescriptor.choice("theme", "主题", "亮/暗/跟随",
                "light", List.of("light", "dark", "system"));
        SettingDescriptor profile = SettingDescriptor.group("profile", "用户档案", "个人信息",
                SettingDescriptor.leaf("name", SettingDescriptor.Types.STRING, "昵称", "显示名", "archon"),
                SettingDescriptor.withSecret(SettingDescriptor.leaf(
                        "token", SettingDescriptor.Types.STRING, "令牌", "访问令牌（write-only）", null)));
        SettingDescriptor prompts = SettingDescriptor.withVisible(
                SettingDescriptor.list("prompts", "提示集", "自定义提示列表",
                        SettingDescriptor.leaf("prompt", SettingDescriptor.Types.OBJECT, "提示", "单条提示", null)),
                "theme", "dark");
        // 全形态：object root dict 里顶层 enum/object/array 三键
        Map<String, Object> envelope = SchemasteryEnvelope.toEnvelope(List.of(theme, profile, prompts));

        Map<String, Object> root = ref(envelope, 0);
        Map<String, Object> dict = (Map<String, Object>) root.get("dict");
        assertEquals(List.of("theme", "profile", "prompts"), List.copyOf(dict.keySet()));

        // enum → union + 两个 const；union 的 label/description 挂自身 meta
        int themeUid = (Integer) dict.get("theme");
        Map<String, Object> union = ref(envelope, themeUid);
        assertEquals("union", union.get("type"));
        assertEquals("主题", meta(union).get("label"));
        List<Integer> unionList = (List<Integer>) union.get("list");
        assertEquals(3, unionList.size());
        Map<String, Object> first = ref(envelope, unionList.get(0));
        assertEquals("const", first.get("type"));
        assertEquals("light", first.get("value"));
        assertEquals("dark", ref(envelope, unionList.get(1)).get("value"));

        // object 递归 dict + secret 叶 role + 标量默认
        int profileUid = (Integer) dict.get("profile");
        Map<String, Object> profileNode = ref(envelope, profileUid);
        assertEquals("object", profileNode.get("type"));
        Map<String, Object> profileDict = (Map<String, Object>) profileNode.get("dict");
        assertEquals(List.of("name", "token"), List.copyOf(profileDict.keySet()));
        int nameUid = (Integer) profileDict.get("name");
        assertEquals("archon", meta(ref(envelope, nameUid)).get("default"));
        int tokenUid = (Integer) profileDict.get("token");
        assertEquals("secret", meta(ref(envelope, tokenUid)).get("role"));

        // array inner + visibleWhen 上抛到该节点 meta
        int promptsUid = (Integer) dict.get("prompts");
        Map<String, Object> promptsNode = ref(envelope, promptsUid);
        assertEquals("array", promptsNode.get("type"));
        assertEquals(List.of(), meta(promptsNode).get("default"));
        Map<String, Object> visible = (Map<String, Object>) meta(promptsNode).get("visibleWhen");
        assertEquals("theme", visible.get("key"));
        assertEquals("dark", visible.get("equals"));
        int innerUid = (Integer) promptsNode.get("inner");
        Map<String, Object> itemNode = ref(envelope, innerUid);
        assertEquals("object", itemNode.get("type"));
        assertEquals(Map.of(), meta(itemNode).get("default"));
    }

    @Test
    void everyRefIsReachableFromRoot() {
        SettingDescriptor tree = SettingDescriptor.group("g", "组", "组描述",
                SettingDescriptor.leaf("a", SettingDescriptor.Types.BOOLEAN, "A", "布尔", true),
                SettingDescriptor.list("l", "列表", "数组", SettingDescriptor.leaf(
                        "x", SettingDescriptor.Types.STRING, "X", "字符串", null)),
                SettingDescriptor.choice("m", "模式", "枚举", "on", List.of("on", "off")));
        Map<String, Object> envelope = SchemasteryEnvelope.toEnvelope(List.of(tree));

        Map<String, Object> root = ref(envelope, 0);
        Map<String, Object> dict = (Map<String, Object>) root.get("dict");
        int gUid = (Integer) dict.get("g");
        Map<String, Object> gNode = ref(envelope, gUid);
        Map<String, Object> gDict = (Map<String, Object>) gNode.get("dict");
        Map<String, Object> refs = (Map<String, Object>) envelope.get("refs");

        // BFS 自 uid 0 出发，断言可达集 == refs 全集（无悬挂引用、无孤儿节点）
        java.util.List<Integer> seen = new java.util.ArrayList<>();
        java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
        queue.add(0);
        while (!queue.isEmpty()) {
            int cur = queue.poll();
            if (seen.contains(cur)) continue;
            seen.add(cur);
            Map<String, Object> body = ref(envelope, cur);
            if (body.get("dict") instanceof Map<?, ?> bodyDict) {
                for (Object child : bodyDict.values()) queue.add(((Number) child).intValue());
            }
            if (body.get("inner") instanceof Number inner) queue.add(inner.intValue());
            if (body.get("list") instanceof List<?> list) {
                for (Object child : list) {
                    if (child instanceof Number num) queue.add(num.intValue());
                }
            }
        }
        assertEquals(refs.size(), seen.size(), "refs 数量应等于可达节点数（无悬挂/无孤儿）");
        assertEquals(0, seen.get(0));
        assertTrue(seen.contains(gUid));
        assertTrue(seen.contains(gDict.get("a")));
        assertTrue(seen.contains(gDict.get("l")));
        assertTrue(seen.contains(gDict.get("m")));
        Map<String, Object> listNode = ref(envelope, (Integer) gDict.get("l"));
        assertTrue(seen.contains(listNode.get("inner")));
        Map<String, Object> unionNode = ref(envelope, (Integer) gDict.get("m"));
        for (Object uid : (List<?>) unionNode.get("list")) {
            assertTrue(seen.contains(uid));
        }
    }
}
