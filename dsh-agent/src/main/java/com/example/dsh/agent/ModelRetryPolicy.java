package com.example.dsh.agent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 模型调用重试策略（对应 DSH llm-retry 的 bounded 指数退避，挂在 turn 级恢复点）。
 * <p>
 * 语义：仅对"尚未产生任何模型可见输出"的调用重试（流式已发 token 不重试 —
 * 重试会开新 turn，与 DSH 一致）；最终失败为终局（turn 结束）。
 */
@Component
public class ModelRetryPolicy {

    private final int maxAttempts;
    private final long baseBackoffMs;
    private final long maxBackoffMs;

    public ModelRetryPolicy(@Value("${dsh.agent.retry.max-attempts:3}") int maxAttempts,
                            @Value("${dsh.agent.retry.base-backoff-ms:500}") long baseBackoffMs,
                            @Value("${dsh.agent.retry.max-backoff-ms:5000}") long maxBackoffMs) {
        this.maxAttempts = Math.max(1, maxAttempts);
        this.baseBackoffMs = Math.max(0, baseBackoffMs);
        this.maxBackoffMs = Math.max(baseBackoffMs, maxBackoffMs);
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    /**
     * 带重试执行：失败按指数退避重试（500ms 起、10% jitter、上限 maxBackoffMs）。
     *
     * @param task 模型调用任务（非流式）
     * @return 成功结果
     * @throws ModelCallFailedException 全部尝试失败
     */
    public <T> T executeWithRetry(Retryable<T> task) {
        Throwable last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return task.run();
            } catch (NonRetryableException e) {
                throw e;
            } catch (Exception e) {
                last = e;
                if (attempt < maxAttempts) {
                    backoff(attempt);
                }
            }
        }
        throw new ModelCallFailedException("模型调用失败（重试 " + maxAttempts + " 次后放弃）: "
                + (last == null ? "" : last.getMessage()), last);
    }

    private void backoff(int attempt) {
        long backoff = Math.min(maxBackoffMs, baseBackoffMs * (1L << (attempt - 1)));
        // 10% jitter
        long jitter = (long) (backoff * 0.1 * Math.random());
        try {
            Thread.sleep(backoff + jitter);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @FunctionalInterface
    public interface Retryable<T> {
        T run();
    }

    /** 不可重试错误（如流式已发出 token）— 立即上抛，不进入退避。 */
    public static final class NonRetryableException extends RuntimeException {
        public NonRetryableException(String message) {
            super(message);
        }

        public NonRetryableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 全部重试失败（终局，turn 结束）。 */
    public static final class ModelCallFailedException extends RuntimeException {
        public ModelCallFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
