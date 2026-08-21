package com.example.dsh.hooks;

import com.example.dsh.tool.ToolCall;
import com.example.dsh.tool.ToolContext;
import com.example.dsh.tool.ToolPostProcessor;
import com.example.dsh.tool.ToolPreExecuteGate;
import com.example.dsh.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Hook 桥 — 把外部 shell hook 映射到工具管线（对应 DSH hooks/hooks-claude-code 的桥面）。
 * <ul>
 *   <li>{@link HookGate}：PreToolUse — 任一匹配 hook block 则拒绝调用（deny 单调）；</li>
 *   <li>{@link HookPostProcessor}：PostToolUse — best-effort 执行匹配 hook（结果仅记录）。</li>
 * </ul>
 */
public class HookBridge {

    /** PreToolUse 门（order=10，审批门 0 之后、执行之前）。 */
    @Component
    public static class HookGate implements ToolPreExecuteGate {
        private static final Logger log = LoggerFactory.getLogger(HookGate.class);
        private final HookConfig config;
        private final HookRunner runner;

        public HookGate(HookConfig config, HookRunner runner) {
            this.config = config;
            this.runner = runner;
        }

        @Override
        public int order() {
            return 10;
        }

        @Override
        public Optional<String> check(ToolCall call, ToolContext context) {
            List<String> commands = config.commandsFor(HookConfig.EVENT_PRE_TOOL_USE, call.name());
            if (commands.isEmpty()) {
                return Optional.empty();
            }
            for (String command : commands) {
                Optional<String> denial = runner.run(HookConfig.EVENT_PRE_TOOL_USE, call.name(),
                        call.arguments(), context.sessionId() == null ? null : context.sessionId().value(),
                        context.cwd(), command);
                if (denial.isPresent()) {
                    log.info("[Hooks] PreToolUse block: {} → {}", call.name(), denial.get());
                    return denial;
                }
            }
            return Optional.empty();
        }
    }

    /** PostToolUse 后处理（best-effort，结果仅记录）。 */
    @Component
    public static class HookPostProcessor implements ToolPostProcessor {
        private static final Logger log = LoggerFactory.getLogger(HookPostProcessor.class);
        private final HookConfig config;
        private final HookRunner runner;

        public HookPostProcessor(HookConfig config, HookRunner runner) {
            this.config = config;
            this.runner = runner;
        }

        @Override
        public int order() {
            return 90;
        }

        @Override
        public ToolResult process(ToolCall call, ToolContext context, ToolResult result) {
            List<String> commands = config.commandsFor(HookConfig.EVENT_POST_TOOL_USE, call.name());
            for (String command : commands) {
                try {
                    runner.run(HookConfig.EVENT_POST_TOOL_USE, call.name(), call.arguments(),
                            context.sessionId() == null ? null : context.sessionId().value(),
                            context.cwd(), command);
                } catch (RuntimeException e) {
                    log.warn("[Hooks] PostToolUse 异常（忽略）: {}", e.getMessage());
                }
            }
            return result;
        }
    }
}
