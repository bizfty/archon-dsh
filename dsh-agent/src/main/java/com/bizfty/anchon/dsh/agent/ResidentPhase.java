package com.bizfty.anchon.dsh.agent;

/**
 * 常驻 agent Phase 状态机（M4，design-resident-agent.md §3/§4.4）。
 * <ul>
 *   <li>{@code IDLE}：对象存在但无任务（保留身份/上下文，可显式 release）；</li>
 *   <li>{@code QUEUED}：Inbox 非空等待消费（同会话后续 chat 排队）；</li>
 *   <li>{@code RUNNING}：消费中（模型/tool step）；</li>
 *   <li>{@code ABORTED}：被取消后停驻（可 resume/新 turn 自动续）；</li>
 *   <li>{@code ERROR}：turn 抛异常（TURN_ERROR 已发布；下次 submit 自动回 RUNNING/QUEUED）。</li>
 * </ul>
 */
public enum ResidentPhase {
    IDLE, QUEUED, RUNNING, ABORTED, ERROR
}
