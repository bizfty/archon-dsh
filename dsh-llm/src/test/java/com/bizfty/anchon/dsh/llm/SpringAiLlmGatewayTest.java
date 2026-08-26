package com.bizfty.anchon.dsh.llm;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.mock;

/**
 * 按用户 API key 路由的网关测试：key 非空时构建独立 ChatModel 并按 key 缓存复用。
 */
class SpringAiLlmGatewayTest {

    private SpringAiLlmGateway gateway(ChatModel defaultModel) {
        return new SpringAiLlmGateway(defaultModel, "deepseek-chat", "https://api.deepseek.com");
    }

    /** 通过反射调用私有 modelFor(key)：构建模型不触网（客户端惰性），可安全验证缓存语义。 */
    private ChatModel modelFor(SpringAiLlmGateway gateway, String key) throws Exception {
        Method m = SpringAiLlmGateway.class.getDeclaredMethod("modelFor", String.class);
        m.setAccessible(true);
        return (ChatModel) m.invoke(gateway, key);
    }

    @Test
    void nullOrBlankKeyFallsBackToDefaultModel() throws Exception {
        SpringAiLlmGateway gateway = gateway(mock(ChatModel.class));
        ChatModel m1 = modelFor(gateway, null);
        ChatModel m2 = modelFor(gateway, "   ");
        assertEquals(0, gateway.keyedModelCount());
        assertEquals(m1, m2); // 都回落到默认模型（同一实例）
    }

    @Test
    void keyedModelsBuiltLazilyAndCachedPerKey() throws Exception {
        SpringAiLlmGateway gateway = gateway(mock(ChatModel.class));
        ChatModel a1 = modelFor(gateway, "sk-user-a");
        ChatModel a2 = modelFor(gateway, "sk-user-a");
        assertEquals(a1, a2, "同一 key 应复用同一模型");
        assertEquals(1, gateway.keyedModelCount());

        ChatModel b1 = modelFor(gateway, "sk-user-b");
        assertEquals(2, gateway.keyedModelCount());
        assertNotEquals(a1, b1, "不同 key 应构建不同模型");
    }

    @Test
    void keyedModelIsNotTheDefaultModel() throws Exception {
        ChatModel defaultModel = mock(ChatModel.class);
        SpringAiLlmGateway gateway = gateway(defaultModel);
        ChatModel keyed = modelFor(gateway, "sk-user-a");
        assertNotEquals(defaultModel, keyed);
    }
}
