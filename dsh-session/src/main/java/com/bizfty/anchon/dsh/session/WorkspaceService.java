package com.bizfty.anchon.dsh.session;

import com.bizfty.anchon.dsh.core.model.Session;
import com.bizfty.anchon.dsh.core.model.Workspace;
import com.bizfty.anchon.dsh.core.model.WorkspaceId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 工作区服务 — Workspace 的 CRUD 与会话连接（对应官方 workspaces service）。
 * <p>
 * 对齐官方 connectWorkspace 语义：
 * <ul>
 *   <li>同一工作区（cwd == workspace.path）下已存在 <b>blank 会话</b>（无任何消息）
 *       时直接复用，否则新建；保证每个目录最多一个空白会话。</li>
 *   <li>会话的 cwd 恒等于所属工作区的 path（Session 领域不变式）。</li>
 * </ul>
 * 删除工作区只移除分组视图，不级联删除会话（官方语义：workspace 是分组，会话独立）。
 */
@Service
public class WorkspaceService {

    private final WorkspaceRepository workspaceRepository;
    private final SessionRepository sessionRepository;
    private final SessionMessageRepository messageRepository;

    public WorkspaceService(WorkspaceRepository workspaceRepository,
                            SessionRepository sessionRepository,
                            SessionMessageRepository messageRepository) {
        this.workspaceRepository = workspaceRepository;
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
    }

    /** 规范化绝对路径（不解析符号链接，允许目录尚不存在）——显示/兜底用。 */
    public static String normalizePath(String raw) {
        Path p = Paths.get(raw == null ? "" : raw).toAbsolutePath().normalize();
        return p.toString();
    }

    /**
     * realpath 规范化（对齐官方 workspace.create）：解析符号链接并校验目录
     * 必须存在且为目录。幂等查找基于 canonical path——同目录不同写法（含
     * 符号链接、..、尾斜杠）收敛为同一工作区。
     *
     * @throws IllegalArgumentException 目录不存在/不可访问或不是目录
     */
    public static String canonicalizeDir(String raw) {
        Path p = Paths.get(raw == null ? "" : raw).toAbsolutePath().normalize();
        Path real;
        try {
            real = p.toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException("目录不存在或不可访问: " + p, e);
        }
        if (!Files.isDirectory(real)) {
            throw new IllegalArgumentException("不是目录: " + real);
        }
        return real.toString();
    }

    /**
     * 注册工作区：规范化路径 + 幂等（同路径已存在时返回既有，不重复创建）。
     *
     * @param path  主机目录（绝对或相对，相对按服务进程 cwd 解析）
     * @param title 显示名（可为 null，前端以目录 basename 兜底）
     * @return 新建或既有工作区
     */
    @Transactional
    public Workspace create(String path, String title) {
        String canonical = canonicalizeDir(path);
        Optional<WorkspaceEntity> existing = workspaceRepository.findByPath(canonical);
        if (existing.isPresent()) {
            return existing.get().toDomain();
        }
        Instant now = Instant.now();
        Workspace workspace = new Workspace(
                WorkspaceId.of("ws_" + UUID.randomUUID()),
                canonical, title, now, now);
        workspaceRepository.save(WorkspaceEntity.from(workspace));
        return workspace;
    }

    @Transactional(readOnly = true)
    public List<Workspace> list() {
        return workspaceRepository.findAll().stream().map(WorkspaceEntity::toDomain).toList();
    }

    @Transactional(readOnly = true)
    public Workspace get(WorkspaceId id) {
        return workspaceRepository.findById(id.value())
                .map(WorkspaceEntity::toDomain)
                .orElseThrow(() -> new WorkspaceNotFoundException(id));
    }

    @Transactional
    public Workspace rename(WorkspaceId id, String title) {
        WorkspaceEntity entity = workspaceRepository.findById(id.value())
                .orElseThrow(() -> new WorkspaceNotFoundException(id));
        entity.setTitle(title);
        entity.setUpdatedAt(Instant.now());
        return workspaceRepository.save(entity).toDomain();
    }

    /** 删除工作区（仅移除分组记录；其下会话保留，cwd 不变）。 */
    @Transactional
    public void delete(WorkspaceId id) {
        if (!workspaceRepository.existsById(id.value())) {
            throw new WorkspaceNotFoundException(id);
        }
        workspaceRepository.deleteById(id.value());
    }

    /**
     * 连接工作区：复用该目录下已存在的 blank 会话（无消息），否则新建一个
     * cwd=workspace.path 的会话。保证每个目录最多一个空白会话。
     *
     * @param workspaceId 目标工作区
     * @return 复用或新建的会话
     */
    @Transactional
    public Session connectWorkspace(WorkspaceId workspaceId) {
        Workspace workspace = get(workspaceId);
        Optional<Session> blank = findBlankByCwd(workspace.path());
        if (blank.isPresent()) {
            return blank.get();
        }
        Instant now = Instant.now();
        com.bizfty.anchon.dsh.core.model.SessionId sessionId =
                com.bizfty.anchon.dsh.core.model.SessionId.of("sess_" + UUID.randomUUID());
        // 无标题会话默认以 session id 为标题：侧边栏/API 可直接识别（对齐用户诉求）
        Session session = new Session(sessionId, sessionId.value(), null, workspace.path(), now, now);
        sessionRepository.save(SessionEntity.from(session));
        return session;
    }

    /** 查找指定 cwd 下的 blank 会话（无消息）。 */
    private Optional<Session> findBlankByCwd(String cwd) {
        return sessionRepository.findByCwd(cwd).stream()
                .filter(e -> messageRepository.countBySessionId(e.getId()) == 0)
                .map(SessionEntity::toDomain)
                .findFirst();
    }

    /** 工作区不存在。 */
    public static final class WorkspaceNotFoundException extends RuntimeException {
        public WorkspaceNotFoundException(WorkspaceId id) {
            super("工作区不存在: " + id);
        }
    }
}
