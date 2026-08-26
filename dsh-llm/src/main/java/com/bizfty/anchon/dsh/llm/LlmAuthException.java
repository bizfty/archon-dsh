package com.bizfty.anchon.dsh.llm;

/**
 * LLM 认证异常 — API key 无效或过期时抛出。
 * <p>
 * 不可重试，直接上抛到 API 层返回 401。
 */
public class LlmAuthException extends RuntimeException {

    public LlmAuthException(String message) {
        super(message);
    }

    public LlmAuthException(String message, Throwable cause) {
        super(message, cause);
    }
}