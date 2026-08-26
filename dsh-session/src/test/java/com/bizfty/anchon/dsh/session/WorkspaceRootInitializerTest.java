package com.bizfty.anchon.dsh.session;

import com.bizfty.anchon.dsh.core.model.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 固定工作区根初始化测试（H2 内存库）：
 * 应用上下文启动时（ApplicationRunner 自动执行）创建固定根目录并幂等注册
 * 默认工作区（对齐部署布局 /data/anchon/workspace）。
 */
@SpringBootTest(classes = WorkspaceRootInitializerTest.TestConfig.class)
class WorkspaceRootInitializerTest {

    @TempDir
    static Path tmp;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        // 覆盖 DSH_WORKSPACE_ROOT / DSH_CODE_ROOT → WorkspaceAutoConfiguration 绑定
        registry.add("DSH_WORKSPACE_ROOT", () -> tmp.resolve("ws-root").toString());
        registry.add("DSH_CODE_ROOT", () -> tmp.resolve("code-root").toString());
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableJpaRepositories(basePackageClasses = SessionRepository.class)
    @EntityScan(basePackageClasses = SessionEntity.class)
    @Import({WorkspaceService.class, WorkspaceRootInitializer.class, WorkspaceAutoConfiguration.class})
    static class TestConfig {
    }

    @Autowired
    private WorkspaceRootInitializer initializer;

    @Autowired
    private WorkspaceService workspaceService;

    @Test
    void bootAutoRegistersFixedRootAndRunIsIdempotent() throws Exception {
        Path root = tmp.resolve("ws-root");
        // 上下文加载时 ApplicationRunner 已自动执行：根目录已创建 + 默认工作区已注册
        assertTrue(Files.isDirectory(root), "启动时固定根目录必须被创建");
        List<Workspace> afterBoot = workspaceService.list();
        assertEquals(1, afterBoot.size(), "启动时应恰好注册一个默认工作区");
        Workspace ws = afterBoot.get(0);
        assertEquals(root.toRealPath().toString(), ws.path(), "path 必须是 realpath 规范化目录");
        assertEquals("默认工作区", ws.title());

        // 手动再次执行（等价于重启）：幂等，不重复注册
        initializer.run(null);
        assertEquals(1, workspaceService.list().size(), "重复执行不得重复注册");

        // 目录被删除后再次执行：重建目录且仍不重复注册
        Files.delete(root);
        assertTrue(Files.notExists(root));
        initializer.run(null);
        assertTrue(Files.isDirectory(root), "目录缺失时须重建");
        assertEquals(1, workspaceService.list().size(), "重建目录后仍不重复注册");
    }
}
