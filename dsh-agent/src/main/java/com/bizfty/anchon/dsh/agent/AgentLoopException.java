package com.bizfty.anchon.dsh.agent;

/**
 * Agent 循环异常。
 */
public class AgentLoopException extends RuntimeException {

    public AgentLoopException(String message) {
        super(message);
    }

    public AgentLoopException(String message, Throwable cause) {
        super(message, cause);
    }
}
