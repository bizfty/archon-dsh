package com.example.dsh.core.event;

/**
 * 会话事件类型 — 会话日志与实时事件词汇。
 * <p>
 * 对应 DSH session/event 的 surface 事件类型；任何模型可见输入都必须有对应事件
 * （Model-visible ⟺ logged 不变式）。
 */
public enum SessionEventType {
    /** 会话创建 */
    SESSION_CREATED,
    /** 会话销毁 */
    SESSION_DISPOSED,
    /** turn 开始（用户消息进入） */
    TURN_START,
    /** turn 结束（含 stop reason） */
    TURN_END,
    /** step 开始（一次模型请求） */
    STEP_START,
    /** 模型请求发出（含模型/温度等 header 信息） */
    MODEL_REQUEST,
    /** 用户消息落日志 */
    USER_MESSAGE,
    /** assistant 文本增量（流式） */
    ASSISTANT_TOKEN,
    /** assistant 消息锚点（含工具调用） */
    ASSISTANT_MESSAGE,
    /** 工具调用被模型发出 */
    TOOL_CALL,
    /** 工具执行结果落日志 */
    TOOL_RESULT,
    /** 工具被拒绝/门控 */
    TOOL_DENIED,
    /** 工具执行异常 */
    TOOL_ERROR,
    /** 工具执行超时 */
    TOOL_TIMEOUT,
    /** 工具执行需要人工审批（已发出请求，等待应答） */
    APPROVAL_REQUESTED,
    /** 工具执行需要用户问答（ask_user_question 已发出，等待应答） */
    QUESTION_REQUESTED,
    /** 消息反馈记录（不进模型上下文） */
    FEEDBACK,
    /** 会话标题刷新 */
    TITLE_UPDATED
}
