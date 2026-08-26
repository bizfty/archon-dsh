package com.bizfty.anchon.dsh.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 固定工作区根初始化（部署布局：{@code /data/anchon/workspace}）。
 * <p>
 * 启动时：
 * <ol>
 *   <li>确保根目录存在（不存在则创建，mkdir -p）；</li>
 *   <li>校验可写；</li>
 *   <li>realpath 规范化后幂等注册为默认工作区（同目录重复启动收敛同一工作区）。</li>
 * </ol>
 * 失败仅 WARN 降级（不阻塞启动）：前端保留空态引导/手动添加工作区流程兜底。
 * 可用 {@code DSH_WORKSPACE_INIT_ENABLED=false} 关闭自动初始化。
 */
@Component
public class WorkspaceRootInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceRootInitializer.class);

    private final WorkspaceProperties properties;
    private final WorkspaceService workspaceService;

    public WorkspaceRootInitializer(WorkspaceProperties properties, WorkspaceService workspaceService) {
        this.properties = properties;
        this.workspaceService = workspaceService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isInitEnabled()) {
            log.info("固定工作区根自动初始化已关闭（DSH_WORKSPACE_INIT_ENABLED=false），跳过");
            return;
        }
        String raw = properties.getRoot();
        try {
            Path root = Path.of(raw);
            Files.createDirectories(root);
            if (!Files.isDirectory(root) || !Files.isWritable(root)) {
                log.error("固定工作区根不可用（非目录或不可写）: {} —— 请检查 {} 权限；可先手动添加工作区", root, root);
                return;
            }
            String canonical = WorkspaceService.canonicalizeDir(root.toString());
            com.bizfty.anchon.dsh.core.model.Workspace ws = workspaceService.create(canonical, "默认工作区");
            log.info("固定工作区根就绪: {} (id={}, title={})", ws.path(), ws.id().value(), ws.title());
        } catch (Exception e) {
            log.error("固定工作区根初始化失败（{}），可手动通过界面添加工作区: {}", raw, e.toString());
        }
    }
}
