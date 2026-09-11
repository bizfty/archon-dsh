package com.bizfty.anchon.dsh.plantask;

import com.bizfty.anchon.dsh.session.WorkspaceService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 计划任务 Worker 引导 — 启动时登记所有既有工作区、回收孤儿任务并启动认领调度循环，
 * 使各工作区空闲 Worker 自动认领并执行 queued 任务。
 * <p>
 * 孤儿回收：进程重启后，此前 claimed/running/waiting_question 的任务其执行者已不存在，
 * 若不回收将永久悬挂（状态假 running）。此处按工作区调用
 * {@link TaskService#recoverOrphans(String)} 回置 queued 供重新认领。
 * 可用 {@code dsh.plantask.recover-on-startup=false} 关闭（默认开启）。
 */
@Component
public class PlanTaskWorkerBootstrap {

    private static final Logger log = LoggerFactory.getLogger(PlanTaskWorkerBootstrap.class);

    private final WorkspaceService workspaceService;
    private final PlanTaskWorkerManager workerManager;
    private final TaskService taskService;
    private final boolean recoverOnStartup;

    public PlanTaskWorkerBootstrap(WorkspaceService workspaceService,
                                   PlanTaskWorkerManager workerManager,
                                   TaskService taskService,
                                   @Value("${dsh.plantask.recover-on-startup:true}") boolean recoverOnStartup) {
        this.workspaceService = workspaceService;
        this.workerManager = workerManager;
        this.taskService = taskService;
        this.recoverOnStartup = recoverOnStartup;
    }

    @PostConstruct
    public void onStartup() {
        try {
            var workspaces = workspaceService.list();
            int recovered = 0;
            for (var ws : workspaces) {
                String workspaceId = ws.id().value();
                workerManager.register(workspaceId);
                if (recoverOnStartup) {
                    try {
                        recovered += taskService.recoverOrphans(workspaceId);
                    } catch (Exception e) {
                        log.warn("[PlanTask] 工作区 {} 孤儿回收异常: {}", workspaceId, e.getMessage());
                    }
                }
            }
            workerManager.scheduleLoop();
            log.info("[PlanTask] Worker 引导完成，登记 {} 个工作区，回收 {} 个孤儿任务",
                    workspaces.size(), recovered);
        } catch (Exception e) {
            log.warn("[PlanTask] Worker 引导异常（可能无数据源就绪）: {}", e.getMessage());
        }
    }
}
