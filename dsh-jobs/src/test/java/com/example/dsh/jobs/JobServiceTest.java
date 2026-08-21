package com.example.dsh.jobs;

import com.example.dsh.core.model.SessionId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 后台任务服务测试：完成/失败/终止/所有者隔离/等待超时。
 */
class JobServiceTest {

    private final SessionId ownerA = SessionId.of("sess_a");
    private final SessionId ownerB = SessionId.of("sess_b");

    @Test
    void backgroundEchoCompletes() {
        JobService service = new JobService(1_048_576);
        JobHandle job = service.start("bash", "echo hello-background", null, ownerA);
        assertEquals(JobStatus.RUNNING, job.status());
        var done = service.waitFor(ownerA, job.id(), 10_000);
        assertTrue(done.isPresent(), "任务应在超时前完成");
        assertEquals(JobStatus.DONE, done.get().status());
        assertEquals(0, done.get().exitCode());
        assertTrue(done.get().output().contains("hello-background"));
    }

    @Test
    void nonzeroExitIsFailed() {
        JobService service = new JobService(1_048_576);
        JobHandle job = service.start("bash", "exit 3", null, ownerA);
        var done = service.waitFor(ownerA, job.id(), 10_000);
        assertTrue(done.isPresent());
        assertEquals(JobStatus.FAILED, done.get().status());
        assertEquals(3, done.get().exitCode());
    }

    @Test
    void killTerminatesRunningJob() throws Exception {
        JobService service = new JobService(1_048_576);
        JobHandle job = service.start("bash", "sleep 60", null, ownerA);
        Thread.sleep(300); // 确保进程已启动
        assertTrue(service.kill(ownerA, job.id()));
        var killed = service.waitFor(ownerA, job.id(), 10_000);
        assertTrue(killed.isPresent());
        assertEquals(JobStatus.KILLED, killed.get().status());
    }

    @Test
    void ownerIsolation() {
        JobService service = new JobService(1_048_576);
        JobHandle job = service.start("bash", "sleep 1", null, ownerA);
        // 其他会话不可见
        assertTrue(service.get(ownerB, job.id()).isEmpty());
        assertTrue(service.get(ownerA, job.id()).isPresent());
        assertFalse(service.kill(ownerB, job.id()));
        // 等待 A 完成清理
        service.waitFor(ownerA, job.id(), 10_000);
    }

    @Test
    void waitForTimesOutForLongJob() {
        JobService service = new JobService(1_048_576);
        JobHandle job = service.start("bash", "sleep 60", null, ownerA);
        assertTrue(service.waitFor(ownerA, job.id(), 200).isEmpty(), "短超时应返回 empty");
        service.kill(ownerA, job.id());
    }

    @Test
    void listReturnsOwnedJobs() {
        JobService service = new JobService(1_048_576);
        service.start("bash", "sleep 1", null, ownerA);
        service.start("bash", "sleep 1", null, ownerA);
        service.start("bash", "sleep 1", null, ownerB);
        assertEquals(2, service.list(ownerA).size());
        assertEquals(1, service.list(ownerB).size());
        // 清理
        for (JobHandle h : service.list(ownerA)) {
            service.kill(ownerA, h.id());
        }
        service.kill(ownerB, service.list(ownerB).get(0).id());
    }

    @Test
    void injectsManagedEnvIntoChildProcess() {
        JobService service = new JobService(1_048_576);
        JobHandle job = service.start("bash", "echo E=$DSH_JOB_TEST", null, ownerA,
                java.util.Map.of("DSH_JOB_TEST", "env-value"));
        var done = service.waitFor(ownerA, job.id(), 10_000);
        assertTrue(done.isPresent());
        assertTrue(done.get().output().contains("E=env-value"), "应注入受管环境变量: " + done.get().output());
    }

    @Test
    void jobWaitToolReturnsCompletedOutput() {
        JobService service = new JobService(1_048_576);
        JobWaitTool tool = new JobWaitTool(service);
        JobHandle job = service.start("bash", "echo done-output", null, ownerA);

        var result = tool.execute(new com.example.dsh.tool.ToolCall("c1", "job_wait",
                java.util.Map.of("job_id", job.id(), "timeout_ms", 10_000)),
                com.example.dsh.tool.ToolContext.builder().sessionId(ownerA).build());

        assertTrue(result.success(), "等待应成功: " + result.message());
        assertTrue(result.message().contains("done-output"));
        assertEquals("DONE", result.data().get("status"));
    }
}
