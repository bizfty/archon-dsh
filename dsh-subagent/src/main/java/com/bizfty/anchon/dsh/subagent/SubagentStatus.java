package com.bizfty.anchon.dsh.subagent;

/**
 * 子代理状态（对应 DSH subagent 的进程内 Activation 状态面）。
 */
public enum SubagentStatus {
    /** 正在执行委托 turn。 */
    RUNNING,
    /** 委托 turn 完成（可 continuable 继续）。 */
    DONE,
    /** 委托失败。 */
    FAILED
}
