package com.example.dsh.hooks;

import com.example.dsh.util.JsonUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Hook 配置加载 — Claude Code 风格 hooks.json（对应 DSH hooks/hook-protocol 的配置面）。
 * <pre>
 * {
 *   "hooks": {
 *     "PreToolUse":  [ { "matcher": "bash", "hooks": [ { "type": "command", "command": "..." } ] } ],
 *     "PostToolUse": [ ... ]
 *   }
 * }
 * </pre>
 * 无配置文件或未配置某事件 → 该事件无 hook（直通）。加载失败 fail loud（配置错误应暴露）。
 */
@Component
public class HookConfig {

    public static final String EVENT_PRE_TOOL_USE = "PreToolUse";
    public static final String EVENT_POST_TOOL_USE = "PostToolUse";

    private final JsonUtils jsonUtils;
    private final Map<String, List<HookEntry>> hooksByEvent = new LinkedHashMap<>();
    private final String configFile;

    public HookConfig(@Value("${dsh.hooks.config-file:./hooks.json}") String configFile) {
        this.jsonUtils = new JsonUtils();
        this.configFile = configFile;
        load();
    }

    private void load() {
        Path path = Paths.get(configFile);
        if (!Files.isRegularFile(path)) {
            return; // 无配置 → 无 hook（直通）
        }
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            Map<String, Object> root = jsonUtils.toMap(text);
            Object hooksObj = root.get("hooks");
            if (!(hooksObj instanceof Map<?, ?> hooks)) {
                throw new IllegalArgumentException("hooks.json 缺少顶层 hooks 对象");
            }
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) hooks).entrySet()) {
                String event = String.valueOf(entry.getKey());
                List<HookEntry> entries = parseEvent(entry.getValue());
                if (!entries.isEmpty()) {
                    hooksByEvent.put(event, entries);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("读取 hooks.json 失败: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<HookEntry> parseEvent(Object value) {
        List<HookEntry> result = new ArrayList<>();
        if (!(value instanceof List<?> blocks)) {
            return result;
        }
        for (Object block : blocks) {
            if (!(block instanceof Map<?, ?> blockMap)) {
                continue;
            }
            Map<String, Object> bm = (Map<String, Object>) blockMap;
            String matcher = String.valueOf(bm.getOrDefault("matcher", "*"));
            Object hooksObj = bm.get("hooks");
            if (!(hooksObj instanceof List<?> hookList)) {
                continue;
            }
            for (Object h : hookList) {
                if (h instanceof Map<?, ?> hookMap) {
                    Map<String, Object> hm = (Map<String, Object>) hookMap;
                    String type = String.valueOf(hm.getOrDefault("type", "command"));
                    String command = String.valueOf(hm.get("command"));
                    if (command != null && !command.isBlank()) {
                        result.add(new HookEntry(matcher, type, command));
                    }
                }
            }
        }
        return result;
    }

    /** 某事件下匹配工具名的 hook 命令。 */
    public List<String> commandsFor(String event, String toolName) {
        List<HookEntry> entries = hooksByEvent.getOrDefault(event, List.of());
        return entries.stream()
                .filter(e -> e.matches(toolName))
                .map(HookEntry::command)
                .toList();
    }

    /** 是否加载了任何 hook。 */
    public boolean hasAny() {
        return !hooksByEvent.isEmpty();
    }

    /** 配置条目：matcher + 命令。 */
    public record HookEntry(String matcher, String type, String command) {
        /** matcher 语义（对齐 Claude Code）：* 或空匹配所有；否则工具名以 matcher 开头。 */
        boolean matches(String toolName) {
            if (matcher == null || matcher.isBlank() || "*".equals(matcher)) {
                return true;
            }
            return toolName != null && (toolName.equals(matcher) || toolName.startsWith(matcher));
        }
    }
}
