package com.bizfty.anchon.dsh.api;

import com.bizfty.anchon.dsh.agent.AgentLoopException;
import com.bizfty.anchon.dsh.llm.LlmAuthException;
import com.bizfty.anchon.dsh.session.SessionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 全局异常处理。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(SessionService.SessionNotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(SessionService.SessionNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "not_found", "message", e.getMessage()));
    }

    @ExceptionHandler(LlmAuthException.class)
    public ResponseEntity<Map<String, Object>> authError(LlmAuthException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of(
                        "error", "auth_failed",
                        "message", "LLM 认证失败，请检查 API Key 是否有效",
                        "detail", e.getMessage()
                ));
    }

    @ExceptionHandler(AgentLoopException.class)
    public ResponseEntity<Map<String, Object>> loopError(AgentLoopException e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "agent_loop", "message", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest()
                .body(Map.of("error", "bad_request", "message", e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> generic(Exception e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "internal", "message", e.getMessage()));
    }
}