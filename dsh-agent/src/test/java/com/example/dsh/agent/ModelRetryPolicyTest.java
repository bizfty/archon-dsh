package com.example.dsh.agent;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模型调用重试策略测试：瞬时失败重试成功、全败终局、不可重试立即上抛。
 */
class ModelRetryPolicyTest {

    @Test
    void retriesTransientFailuresThenSucceeds() {
        ModelRetryPolicy policy = new ModelRetryPolicy(3, 1, 10);
        AtomicInteger attempts = new AtomicInteger();
        String result = policy.executeWithRetry(() -> {
            int n = attempts.incrementAndGet();
            if (n < 3) {
                throw new IllegalStateException("rate limited " + n);
            }
            return "ok";
        });
        assertEquals("ok", result);
        assertEquals(3, attempts.get(), "应重试 2 次后成功");
    }

    @Test
    void givesUpAfterMaxAttempts() {
        ModelRetryPolicy policy = new ModelRetryPolicy(3, 1, 10);
        AtomicInteger attempts = new AtomicInteger();
        ModelRetryPolicy.ModelCallFailedException e = assertThrows(
                ModelRetryPolicy.ModelCallFailedException.class,
                () -> policy.executeWithRetry(() -> {
                    attempts.incrementAndGet();
                    throw new IllegalStateException("always fails");
                }));
        assertEquals(3, attempts.get());
        assertTrue(e.getMessage().contains("重试 3 次后放弃"));
    }

    @Test
    void nonRetryableErrorIsRethrownImmediately() {
        ModelRetryPolicy policy = new ModelRetryPolicy(3, 1, 10);
        AtomicInteger attempts = new AtomicInteger();
        assertThrows(ModelRetryPolicy.NonRetryableException.class,
                () -> policy.executeWithRetry(() -> {
                    attempts.incrementAndGet();
                    throw new ModelRetryPolicy.NonRetryableException("already streamed");
                }));
        assertEquals(1, attempts.get(), "不可重试错误不应进入退避重试");
    }

    @Test
    void maxAttemptsOneMeansNoRetry() {
        ModelRetryPolicy policy = new ModelRetryPolicy(1, 1, 10);
        AtomicInteger attempts = new AtomicInteger();
        assertThrows(ModelRetryPolicy.ModelCallFailedException.class,
                () -> policy.executeWithRetry(() -> {
                    attempts.incrementAndGet();
                    throw new IllegalStateException("x");
                }));
        assertEquals(1, attempts.get());
    }
}
