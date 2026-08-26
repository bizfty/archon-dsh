package com.bizfty.anchon.dsh.api;

import com.bizfty.anchon.dsh.core.model.MessageRole;
import com.bizfty.anchon.dsh.core.model.SessionId;
import com.bizfty.anchon.dsh.core.model.SessionMessage;
import com.bizfty.anchon.dsh.session.SessionService;
import com.bizfty.anchon.dsh.util.JsonUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/sessions")
public class TrajectoryController {

    private final SessionService sessionService;
    private final JsonUtils jsonUtils;

    public TrajectoryController(SessionService sessionService, JsonUtils jsonUtils) {
        this.sessionService = sessionService;
        this.jsonUtils = jsonUtils;
    }

    @GetMapping("/trajectory")
    public TrajectoryDto trajectory(@RequestParam String sessionId) {
        List<SessionMessage> messages = sessionService.listMessages(SessionId.of(sessionId));
        return buildTrajectory(sessionId, messages);
    }

    private TrajectoryDto buildTrajectory(String sessionId, List<SessionMessage> messages) {
        List<TurnDto> turns = new ArrayList<>();
        List<StepDto> allSteps = new ArrayList<>();
        int turnIndex = 0;
        int stepCounter = 0;
        Instant firstAt = null;
        Instant lastAt = null;

        TurnBuilder currentTurn = null;

        for (SessionMessage m : messages) {
            if (firstAt == null && m.createdAt() != null) firstAt = m.createdAt();
            if (m.createdAt() != null) lastAt = m.createdAt();

            if (m.role() == MessageRole.USER) {
                if (currentTurn != null) {
                    turns.add(currentTurn.build(turnIndex));
                }
                turnIndex++;
                currentTurn = new TurnBuilder(turnIndex);
                stepCounter++;
                StepDto step = new StepDto(stepCounter, "user", null,
                        m.content(), null, null, m.createdAt());
                currentTurn.addStep(step);
                allSteps.add(step);
            } else if (m.role() == MessageRole.ASSISTANT) {
                if (currentTurn == null) {
                    currentTurn = new TurnBuilder(++turnIndex);
                }
                List<ToolCallDto> toolCalls = parseToolCalls(m.toolCallsJson());
                stepCounter++;
                StepDto step = new StepDto(stepCounter, "assistant", null,
                        m.content(), toolCalls.isEmpty() ? null : toolCalls, null, m.createdAt());
                currentTurn.addStep(step);
                allSteps.add(step);
                if (!toolCalls.isEmpty()) {
                    currentTurn.setHasToolCalls(true);
                }
            } else if (m.role() == MessageRole.TOOL) {
                if (currentTurn == null) {
                    currentTurn = new TurnBuilder(++turnIndex);
                }
                String truncated = m.content() != null && m.content().length() > 2000
                        ? m.content().substring(0, 2000) + "…"
                        : m.content();
                stepCounter++;
                StepDto step = new StepDto(stepCounter, "tool",
                        m.toolName(), truncated, null, m.toolCallId(), m.createdAt());
                currentTurn.addStep(step);
                allSteps.add(step);
            } else if (m.role() == MessageRole.SYSTEM) {
                stepCounter++;
                StepDto step = new StepDto(stepCounter, "system", null,
                        m.content(), null, null, m.createdAt());
                allSteps.add(step);
            }
        }
        if (currentTurn != null) {
            turns.add(currentTurn.build(turnIndex));
        }

        long totalTokens = estimateTokens(messages);
        int toolCallCount = countToolCalls(messages);

        return new TrajectoryDto(
                sessionId,
                firstAt, lastAt,
                turns, allSteps,
                messages.size(),
                totalTokens,
                toolCallCount,
                turns.size());
    }

    private long estimateTokens(List<SessionMessage> messages) {
        return messages.stream()
                .mapToLong(m -> m.content() == null ? 0 : m.content().length() / 4L)
                .sum();
    }

    private int countToolCalls(List<SessionMessage> messages) {
        int count = 0;
        for (SessionMessage m : messages) {
            if (m.role() == MessageRole.ASSISTANT && m.toolCallsJson() != null) {
                count += parseToolCalls(m.toolCallsJson()).size();
            }
        }
        return count;
    }

    private List<ToolCallDto> parseToolCalls(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<Map<String, Object>> list = jsonUtils.toList(json);
            return list.stream().map(m -> new ToolCallDto(
                    String.valueOf(m.getOrDefault("id", "")),
                    String.valueOf(m.getOrDefault("name", "")),
                    String.valueOf(m.getOrDefault("arguments", ""))
            )).toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    public record TrajectoryDto(
            String sessionId,
            Instant startedAt,
            Instant endedAt,
            List<TurnDto> turns,
            List<StepDto> steps,
            int totalMessages,
            long estimatedTokens,
            int totalToolCalls,
            int totalTurns) {}

    public record TurnDto(
            int turn,
            List<StepDto> steps,
            boolean hasToolCalls) {}

    public record StepDto(
            int step,
            String type,
            String toolName,
            String content,
            List<ToolCallDto> toolCalls,
            String toolCallId,
            Instant createdAt) {}

    public record ToolCallDto(
            String id,
            String name,
            String arguments) {}

    private static class TurnBuilder {
        private final int turn;
        private final List<StepDto> steps = new ArrayList<>();
        private boolean hasToolCalls = false;

        TurnBuilder(int turn) { this.turn = turn; }

        void addStep(StepDto step) { steps.add(step); }
        void setHasToolCalls(boolean v) { this.hasToolCalls = v; }

        TurnDto build(int fallback) {
            return new TurnDto(turn, List.copyOf(steps), hasToolCalls);
        }
    }
}