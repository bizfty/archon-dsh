package com.bizfty.anchon.dsh.session;

import com.bizfty.anchon.dsh.core.model.Session;
import com.bizfty.anchon.dsh.core.model.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工作区服务测试（H2 内存库）：realpath 规范化 / 幂等 / 目录校验 / connect 复用。
 * <p>
 * 对齐官方 workspace.create 语义：路径必须存在且为目录；同目录不同写法
 * （含符号链接、..、尾斜杠）收敛为同一工作区（idempotent resolve）。
 */
@SpringBootTest(classes = WorkspaceServiceTest.TestConfig.class)
class WorkspaceServiceTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableJpaRepositories(basePackageClasses = SessionRepository.class)
    @EntityScan(basePackageClasses = SessionEntity.class)
    @Import({WorkspaceService.class, SessionService.class})
    static class TestConfig {
    }

    @org.springframework.beans.factory.annotation.Autowired
    private WorkspaceService workspaceService;

    @org.springframework.beans.factory.annotation.Autowired
    private SessionService sessionService;

    @TempDir
    Path tmp;

    @Test
    void createRealpathNormalizesAndIsIdempotent() throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("proj/sub"));
        // 不同写法（尾斜杠 / .. 归一）收敛到同一 canonical path → 同一工作区
        Workspace a = workspaceService.create(dir.toString() + "/", null);
        Workspace b = workspaceService.create(dir.resolve("..").resolve("sub").toString(), null);
        assertEquals(a.id(), b.id(), "同目录不同写法必须幂等解析到同一工作区");
        assertEquals(dir.toRealPath().toString(), a.path(), "path 必须是 realpath 规范化后的绝对目录");
    }

    @Test
    void createRejectsMissingPathAndFile() throws Exception {
        Path missing = tmp.resolve("no-such-dir");
        assertThrows(IllegalArgumentException.class,
                () -> workspaceService.create(missing.toString(), null), "不存在的目录必须拒绝");

        Path file = tmp.resolve("a-file.txt");
        Files.writeString(file, "x");
        assertThrows(IllegalArgumentException.class,
                () -> workspaceService.create(file.toString(), null), "文件路径必须拒绝");
    }

    @Test
    void listRenameDelete() throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("ws-a"));
        Workspace w = workspaceService.create(dir.toString(), "我的项目");
        assertEquals("我的项目", w.title());

        List<Workspace> all = workspaceService.list();
        assertTrue(all.stream().anyMatch(x -> x.id().equals(w.id())));

        workspaceService.rename(w.id(), "新标题");
        assertEquals("新标题", workspaceService.get(w.id()).title());

        workspaceService.delete(w.id());
        assertFalse(workspaceService.list().stream().anyMatch(x -> x.id().equals(w.id())));
    }

    @Test
    void connectReusesBlankSessionAndCreatesWhenNeeded() throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("ws-conn"));
        Workspace w = workspaceService.create(dir.toString(), null);

        Session first = workspaceService.connectWorkspace(w.id());
        assertNotNull(first);
        assertEquals(w.path(), first.cwd(), "会话 cwd 恒等于工作区 path");

        Session second = workspaceService.connectWorkspace(w.id());
        assertEquals(first.id(), second.id(), "无消息的 blank 会话必须复用（每目录最多一个空白会话）");

        // 非空白会话不复用：追加消息后再 connect 应新建
        sessionService.append(first.id(), com.bizfty.anchon.dsh.core.model.MessageRole.USER,
                "你好", null, null, null);
        Session third = workspaceService.connectWorkspace(w.id());
        assertFalse(third.id().equals(first.id()), "有消息的会话不复用");
    }

}

