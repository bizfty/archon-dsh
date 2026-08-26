package com.bizfty.anchon.dsh.agent;

/**
 * Agent 循环被用户取消（前端「停止生成」）。
 * <p>
 * 与普通错误区分：HTTP/SSE 层据此发送 cancelled 语义事件
 * （TURN_ERROR error_type=cancelled / SSE error error_type=cancelled），
 * 前端提示「已停止生成」而非「任务失败」。
 */
public class AgentCancelledException extends AgentLoopException {

    public AgentCancelledException(String message) {
        super(message);
    }

    public AgentCancelledException(String message, Throwable cause) {
        super(message, cause);
    }
}
