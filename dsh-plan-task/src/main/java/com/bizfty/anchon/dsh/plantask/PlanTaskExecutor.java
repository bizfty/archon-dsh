package com.bizfty.anchon.dsh.plantask;

/**
 * 计划任务执行器 — 领取后实际执行一个任务（对应 DSH plan-task 的执行者）。
 * <p>
 * execute 在当前调用线程同步运行（可能因 ask_user_question 阻塞等待用户应答，
 * 应答后继续）；返回执行结果。实现方负责把承载会话记录到任务的 executorSessionId
 * （供任务级提问查询/应答关联使用）。
 */
public interface PlanTaskExecutor {

    /** 执行结果。 */
    record ExecutionOutcome(String status, String result, String error) {

        public static ExecutionOutcome done(String result) {
            return new ExecutionOutcome(PlanTask.STATUS_DONE, result, null);
        }

        public static ExecutionOutcome failed(String error) {
            return new ExecutionOutcome(PlanTask.STATUS_FAILED, null, error);
        }
    }

    /**
     * 执行认领后的任务。
     *
     * @param taskId      任务 id（供 executor 记录会话/状态）
     * @param workspaceId 工作区 id
     * @param prompt      任务指令
     * @return 执行结果
     */
    ExecutionOutcome execute(String workspaceId, String taskId, String prompt);
}
