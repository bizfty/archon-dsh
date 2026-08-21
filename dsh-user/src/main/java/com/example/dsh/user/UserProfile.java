package com.example.dsh.user;

import java.time.Instant;

/**
 * 用户 profile（不含密码哈希暴露；LLM API key 存于 CredentialService 缝）。
 */
public record UserProfile(
        String id,
        String username,
        String passwordHash,
        String llmProvider,
        String llmModel,
        Instant createdAt) {

    /** 对外视图（不含 hash）。 */
    public UserView view() {
        return new UserView(id, username, llmProvider, llmModel, createdAt);
    }

    public record UserView(String id, String username, String llmProvider, String llmModel, Instant createdAt) {
    }
}
