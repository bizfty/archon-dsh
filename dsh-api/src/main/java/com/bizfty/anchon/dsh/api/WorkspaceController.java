package com.bizfty.anchon.dsh.api;

import com.bizfty.anchon.dsh.api.dto.WorkspaceDto;
import com.bizfty.anchon.dsh.core.model.Session;
import com.bizfty.anchon.dsh.core.model.SessionId;
import com.bizfty.anchon.dsh.core.model.Workspace;
import com.bizfty.anchon.dsh.core.model.WorkspaceId;
import com.bizfty.anchon.dsh.session.SessionRepository;
import com.bizfty.anchon.dsh.session.WorkspaceService;
import com.bizfty.anchon.dsh.settings.SettingsService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 工作区 API — Workspace CRUD + connect（对齐官方 workspaces service）。
 * <p>
 * 设计要点（源自官方 deepseek-harness）：
 * <ul>
 *   <li>先选工作目录（Workspace），会话挂其下，cwd 恒等于 workspace.path；</li>
 *   <li>connectWorkspace：复用该目录已有 blank 会话（无消息），否则新建；</li>
 *   <li>recentWorkspaceId 记忆最近工作区（跨重启保留，经 SettingsService 持久化）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/workspaces")
public class WorkspaceController {

    private static final String NS = "workspace";

    private final WorkspaceService workspaceService;
    private final SessionRepository sessionRepository;
    private final SettingsService settings;

    public WorkspaceController(WorkspaceService workspaceService,
                               SessionRepository sessionRepository,
                               SettingsService settings) {
        this.workspaceService = workspaceService;
        this.sessionRepository = sessionRepository;
        this.settings = settings;
    }

    @GetMapping
    public List<WorkspaceDto> list() {
        return workspaceService.list().stream().map(this::toDto).toList();
    }

    @PostMapping
    public ResponseEntity<WorkspaceDto> create(@RequestBody CreateWorkspaceRequest request) {
        if (request == null || request.path() == null || request.path().isBlank()) {
            throw new IllegalArgumentException("path 不能为空");
        }
        Workspace workspace = workspaceService.create(request.path(), request.title());
        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(workspace));
    }

    @GetMapping("/{workspaceId}")
    public WorkspaceDto get(@PathVariable String workspaceId) {
        return toDto(workspaceService.get(WorkspaceId.of(workspaceId)));
    }

    @PatchMapping("/{workspaceId}")
    public WorkspaceDto update(@PathVariable String workspaceId, @RequestBody UpdateWorkspaceRequest request) {
        WorkspaceId id = WorkspaceId.of(workspaceId);
        Workspace workspace = workspaceService.get(id);
        if (request.title() != null) {
            workspace = workspaceService.rename(id, request.title());
        }
        return toDto(workspace);
    }

    @DeleteMapping("/{workspaceId}")
    public ResponseEntity<Void> delete(@PathVariable String workspaceId) {
        WorkspaceId id = WorkspaceId.of(workspaceId);
        workspaceService.delete(id);
        // 若删除的是最近工作区，清除 recent 记忆
        String recent = settings.getString(NS, "recent", null);
        if (recent != null && recent.equals(id.value())) {
            settings.set(NS, "recent", "");
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * 连接工作区：复用该目录下已有 blank 会话（无消息），否则新建；并记录为最近工作区。
     *
     * @return 复用或新建的会话（完整 DTO，前端直接采纳）
     */
    @PostMapping("/{workspaceId}/connect")
    public Map<String, Object> connect(@PathVariable String workspaceId) {
        WorkspaceId id = WorkspaceId.of(workspaceId);
        Session session = workspaceService.connectWorkspace(id);
        settings.set(NS, "recent", id.value());
        return Map.of("session", toSessionDto(session));
    }

    @GetMapping("/recent")
    public Map<String, Object> recent() {
        String recent = settings.getString(NS, "recent", null);
        if (recent == null || recent.isBlank()) {
            return Map.of();
        }
        return Map.of("workspace_id", recent);
    }

    @PutMapping("/recent")
    public Map<String, Object> setRecent(@RequestBody SetRecentRequest request) {
        if (request == null || request.workspaceId() == null || request.workspaceId().isBlank()) {
            throw new IllegalArgumentException("workspace_id 不能为空");
        }
        settings.set(NS, "recent", request.workspaceId());
        return Map.of("workspace_id", request.workspaceId());
    }

    private WorkspaceDto toDto(Workspace w) {
        List<String> sessionIds = sessionRepository.findByCwd(w.path()).stream()
                .map(e -> e.getId())
                .toList();
        return new WorkspaceDto(w.id().value(), w.path(), w.title(), sessionIds,
                w.createdAt(), w.updatedAt());
    }

    private com.bizfty.anchon.dsh.api.dto.SessionDto toSessionDto(Session s) {
        return new com.bizfty.anchon.dsh.api.dto.SessionDto(
                s.id().value(), s.title(), s.model(), s.cwd(),
                "active", s.createdAt(), s.updatedAt());
    }

    public record CreateWorkspaceRequest(String path, String title) {
    }

    public record UpdateWorkspaceRequest(String title) {
    }

    public record SetRecentRequest(String workspaceId) {
    }
}
