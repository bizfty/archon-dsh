package com.bizfty.anchon.dsh.shell;

import com.bizfty.anchon.dsh.core.model.SessionId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 持久 shell 测试：cwd/export/函数跨调用保留、会话隔离。
 */
class PersistentShellManagerTest {

    private final SessionId sessionA = SessionId.of("sess_ps_a");
    private final SessionId sessionB = SessionId.of("sess_ps_b");

    @Test
    void cwdPersistsAcrossCalls() {
        PersistentShellManager manager = new PersistentShellManager(1_048_576, 30_000);
        manager.execute(sessionA, "cd /tmp", 10_000);
        var result = manager.execute(sessionA, "pwd", 10_000);
        assertTrue(result.output().contains("/tmp"), "cwd 应跨调用保留: " + result.output());
        manager.close(sessionA);
    }

    @Test
    void exportPersistsAcrossCalls() {
        PersistentShellManager manager = new PersistentShellManager(1_048_576, 30_000);
        manager.execute(sessionA, "export MY_VAR=hello-persist", 10_000);
        var result = manager.execute(sessionA, "echo $MY_VAR", 10_000);
        assertTrue(result.output().contains("hello-persist"), "export 应跨调用保留: " + result.output());
        manager.close(sessionA);
    }

    @Test
    void functionPersistsAcrossCalls() {
        PersistentShellManager manager = new PersistentShellManager(1_048_576, 30_000);
        manager.execute(sessionA, "greet() { echo hi-$1; }", 10_000);
        var result = manager.execute(sessionA, "greet world", 10_000);
        assertTrue(result.output().contains("hi-world"), "函数应跨调用保留: " + result.output());
        manager.close(sessionA);
    }

    @Test
    void sessionsAreIsolated() {
        PersistentShellManager manager = new PersistentShellManager(1_048_576, 30_000);
        manager.execute(sessionA, "cd /tmp", 10_000);
        var b = manager.execute(sessionB, "pwd", 10_000);
        assertTrue(!b.output().contains("/tmp"), "不同会话不应共享 shell 状态: " + b.output());
        manager.close(sessionA);
        manager.close(sessionB);
    }
}
