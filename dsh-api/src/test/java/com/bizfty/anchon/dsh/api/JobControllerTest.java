package com.bizfty.anchon.dsh.api;

import com.bizfty.anchon.dsh.core.model.SessionId;
import com.bizfty.anchon.dsh.jobs.JobService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 后台任务端点测试：启动后台任务 → 列表可见（running）→ kill → 状态 killed。
 */
class JobControllerTest {

    @Test
    void listShowsRunningThenKillSettlesIt() throws Exception {
        JobService service = new JobService(1024 * 1024);
        JobController controller = new JobController(service);
        SessionId owner = SessionId.of("sess_jobs");

        CountDownLatch held = new CountDownLatch(1);
        service.start("bash", "sleep 30; echo done", null, owner);
        held.countDown();

        // 列表：至少一条 running
        List<JobController.JobDto> running = controller.listJobs(owner.value());
        assertFalse(running.isEmpty(), "应至少有一个后台任务");
        JobController.JobDto job = running.get(0);
        assertEquals("bash", job.kind());
        assertEquals("running", job.status());

        // kill → 状态变为 killed
        Map<String, Object> result = controller.killJob(owner.value(), job.id());
        assertEquals(Boolean.TRUE, result.get("ok"));
        boolean settled = awaitSettled(controller, owner.value(), job.id());
        assertTrue(settled, "kill 后任务应尽快转为非 running");
    }

    @Test
    void killUnknownJobReturnsNotOk() {
        JobService service = new JobService(1024 * 1024);
        JobController controller = new JobController(service);
        Map<String, Object> result = controller.killJob("sess_none", "bash_000");
        assertEquals(Boolean.FALSE, result.get("ok"));
    }

    private boolean awaitSettled(JobController controller, String sessionId, String jobId)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            var job = controller.listJobs(sessionId).stream()
                    .filter(j -> j.id().equals(jobId))
                    .findFirst();
            if (job.isPresent() && !"running".equals(job.get().status())) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }
}
