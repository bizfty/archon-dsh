package com.bizfty.anchon.dsh.llm;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MODEL_REQUEST / MODEL_RESPONSE 载荷工厂单测：
 * roles 顺序、消息形状、toolCalls/toolResponses、usage/finishReason、options 白名单、
 * 不含敏感字段。
 */
class ModelCallEventPayloadsTest {

    @Test
    void requestPayloadKeepsRolesAndOrder() {
        List<Message> messages = List.of(
                new SystemMessage("sys"),
                new UserMessage("hi"),
                new AssistantMessage("工具来了"),
                ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("c1", "echo", "{\"ok\":true}")))
                .build());
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model("deepseek-chat")
                .temperature(0.7)
                .topP(0.9)
                .maxTokens(2048)
                .frequencyPenalty(0.1)
                .presencePenalty(0.2)
                .build();

        Map<String, Object> payload = ModelCallEventPayloads.requestPayload(
                "deepseek-chat", messages, options, ModelCallEventPayloads.CALL_SITE_AGENT_TURN);

        assertEquals("deepseek-chat", payload.get("model"));
        assertEquals(ModelCallEventPayloads.CALL_SITE_AGENT_TURN, payload.get("callSite"));
        List<?> serialized = assertInstanceOf(List.class, payload.get("messages"));
        assertEquals(4, serialized.size());
        assertEquals("system", ((Map<?, ?>) serialized.get(0)).get("role"));
        assertEquals("user", ((Map<?, ?>) serialized.get(1)).get("role"));
        assertEquals("assistant", ((Map<?, ?>) serialized.get(2)).get("role"));
        assertEquals("tool", ((Map<?, ?>) serialized.get(3)).get("role"));
        // toolResponses 形状
        Map<?, ?> toolMsg = (Map<?, ?>) serialized.get(3);
        List<?> responses = assertInstanceOf(List.class, toolMsg.get("toolResponses"));
        Map<?, ?> r0 = (Map<?, ?>) responses.get(0);
        assertEquals("c1", r0.get("id"));
        assertEquals("echo", r0.get("name"));
    }

    @Test
    void requestPayloadWhiteListsOptionsAndOmitsSecrets() {
        List<Message> messages = List.of(new UserMessage("hi"));
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model("deepseek-chat")
                .temperature(0.3)
                .apiKey("sk-secret-123") // 敏感字段不得泄漏
                .build();

        Map<String, Object> payload = ModelCallEventPayloads.requestPayload(
                "deepseek-chat", messages, options, ModelCallEventPayloads.CALL_SITE_SESSION_TITLE);

        assertNull(payload.get("apiKey"));
        assertNull(payload.get("toolCallbacks"));
        assertFalse(payload.toString().contains("sk-secret-123"));
        Map<?, ?> opts = (Map<?, ?>) payload.get("options");
        assertEquals(0.3, ((Number) opts.get("temperature")).doubleValue());
        assertFalse(opts.containsKey("model"), "model 在载荷顶层而非 options 内");
    }

    @Test
    void responsePayloadCarriesTextFinishReasonToolCallsAndUsage() {
        List<AssistantMessage.ToolCall> toolCalls = List.of(
                new AssistantMessage.ToolCall("c1", "function", "echo", "{\"text\":\"hi\"}"));
        Usage usage = new Usage() {
            @Override
            public Integer getPromptTokens() {
                return 120;
            }

            @Override
            public Integer getCompletionTokens() {
                return 30;
            }

            @Override
            public Integer getTotalTokens() {
                return 150;
            }

            @Override
            public Object getNativeUsage() {
                return null;
            }
        };

        Map<String, Object> payload = ModelCallEventPayloads.responsePayload(
                "deepseek-chat", "tool_calls", "查一下", toolCalls, usage,
                ModelCallEventPayloads.CALL_SITE_AGENT_TURN);

        assertEquals("deepseek-chat", payload.get("model"));
        assertEquals(ModelCallEventPayloads.CALL_SITE_AGENT_TURN, payload.get("callSite"));
        assertEquals("tool_calls", payload.get("finishReason"));
        assertEquals("查一下", payload.get("text"));
        List<?> calls = assertInstanceOf(List.class, payload.get("toolCalls"));
        assertEquals(1, calls.size());
        Map<?, ?> c0 = (Map<?, ?>) calls.get(0);
        assertEquals("c1", c0.get("id"));
        assertEquals("echo", c0.get("name"));
        Map<?, ?> u = assertInstanceOf(Map.class, payload.get("usage"));
        assertEquals(120, ((Number) u.get("promptTokens")).intValue());
        assertEquals(150, ((Number) u.get("totalTokens")).intValue());
    }

    @Test
    void responsePayloadOmitsNullOptionals() {
        Map<String, Object> payload = ModelCallEventPayloads.responsePayload(
                "deepseek-chat", null, null, null, null, ModelCallEventPayloads.CALL_SITE_COMPACTION);
        assertNull(payload.get("finishReason"));
        assertNull(payload.get("text"));
        assertNull(payload.get("toolCalls"));
        assertNull(payload.get("usage"));
        assertNotNull(payload.get("callSite"));
    }

    @Test
    void requestPayloadCarriesRequestSeriesWhenProvided() {
        List<Message> messages = List.of(new UserMessage("hi"));
        OpenAiChatOptions options = OpenAiChatOptions.builder().model("deepseek-chat").build();
        var series = new ModelCallEventPayloads.RequestSeriesInfo(
                "s-run-1-1", ModelCallEventPayloads.SERIES_REASON_INITIAL, true, 1);

        Map<String, Object> payload = ModelCallEventPayloads.requestPayload(
                "deepseek-chat", messages, options, ModelCallEventPayloads.CALL_SITE_AGENT_TURN, series);

        Map<?, ?> rs = assertInstanceOf(Map.class, payload.get("requestSeries"));
        assertEquals("s-run-1-1", rs.get("seriesId"));
        assertEquals("initial", rs.get("reason"));
        assertEquals(Boolean.TRUE, rs.get("startsSeries"));
        assertEquals(1, ((Number) rs.get("stepInSeries")).intValue());
    }

    @Test
    void requestPayloadOmitsRequestSeriesWhenNull() {
        Map<String, Object> payload = ModelCallEventPayloads.requestPayload(
                "deepseek-chat", List.of(new UserMessage("hi")),
                OpenAiChatOptions.builder().model("deepseek-chat").build(),
                ModelCallEventPayloads.CALL_SITE_SESSION_TITLE, null);
        assertNull(payload.get("requestSeries"));
    }

    @Test
    void seriesInfoToMapRoundTrips() {
        var series = new ModelCallEventPayloads.RequestSeriesInfo(
                "s-run-9-2", ModelCallEventPayloads.SERIES_REASON_SERIES, true, 3);
        Map<String, Object> m = series.toMap();
        assertEquals("s-run-9-2", m.get("seriesId"));
        assertEquals("series", m.get("reason"));
        assertEquals(Boolean.TRUE, m.get("startsSeries"));
        assertEquals(3, ((Number) m.get("stepInSeries")).intValue());
    }
}
