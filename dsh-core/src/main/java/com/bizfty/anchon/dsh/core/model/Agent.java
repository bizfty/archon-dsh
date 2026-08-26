package com.bizfty.anchon.dsh.core.model;

import java.util.List;

/**
 * Agent — 代理配置（persona 行 + 工具可见性 + LLM 凭据引用）。
 * <p>
 * 对应 DSH core/agent 的 Agent 配置面：provider/model/systemPrompt/cwd +
 * enabled/disabledTools（对应 tools.restrict 的可见性过滤，非安全边界）。
 * 无状态；每轮从配置解析。
 * <p>
 * {@code credentialRef} 是该 agent 的 LLM API key 凭据引用（如 {@code env:MY_KEY}
 * 或经 CredentialService 存储的 key）— 配置里只落引用不落密钥；agent-loop 每次
 * 调用前解析（每操作解析），无请求级覆盖时生效。
 */
public record Agent(
        String id,
        String name,
        String provider,
        String model,
        String systemPrompt,
        String cwd,
        List<String> enabledTools,
        List<String> disabledTools,
        String credentialRef) {

    /** 便捷构造：不限工具、无凭据引用。 */
    public Agent(String id, String name, String provider, String model,
                 String systemPrompt, String cwd) {
        this(id, name, provider, model, systemPrompt, cwd, List.of(), List.of(), null);
    }

    /**
     * 工具是否对本 agent 可见（对应 DSH tools.restrict 的可见性面）：
     * disabled 命中即不可见；enabled 非空时仅名单内可见。
     */
    public boolean isToolVisible(String toolName) {
        if (disabledTools != null && disabledTools.contains(toolName)) {
            return false;
        }
        return enabledTools == null || enabledTools.isEmpty() || enabledTools.contains(toolName);
    }
}
