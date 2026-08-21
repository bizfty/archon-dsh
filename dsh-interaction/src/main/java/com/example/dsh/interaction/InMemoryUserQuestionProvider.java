package com.example.dsh.interaction;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 进程内问答应答者 — 问题挂起等待，由 answer() 完成。
 */
@Component
public class InMemoryUserQuestionProvider implements UserQuestionProvider {

    private final Map<String, UserQuestion> questions = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<String>> pending = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return "in-memory";
    }

    @Override
    public Optional<String> ask(UserQuestion question, long timeoutMs) {
        CompletableFuture<String> future = new CompletableFuture<>();
        questions.put(question.id(), question);
        pending.put(question.id(), future);
        try {
            return Optional.of(future.orTimeout(timeoutMs, TimeUnit.MILLISECONDS).get());
        } catch (java.util.concurrent.ExecutionException e) {
            return Optional.empty(); // 超时/异常 → 无应答
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } finally {
            questions.remove(question.id());
            pending.remove(question.id());
        }
    }

    public List<UserQuestion> pendingQuestions() {
        return List.copyOf(questions.values());
    }

    public void answer(String questionId, String answer) {
        CompletableFuture<String> future = pending.get(questionId);
        if (future != null) {
            future.complete(answer);
        }
    }
}
