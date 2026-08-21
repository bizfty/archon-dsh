package com.example.dsh.goal;

import com.example.dsh.storage.StorageService;
import com.example.dsh.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * 目标服务 — 每会话至多一个当前目标（对应 DSH goal 的 ctx.goals 服务）。
 * <p>
 * 持久化：经 StorageService（命名空间 "goals"，键 = sessionId）JSON 落盘；
 * 生命周期 create → active；update 携带 action（edit/pause/resume/complete/blocked）
 * 且必须 CAS（精确 id + revision），每次变更 revision+1。
 * <p>
 * blocked 语义（对齐 DSH fold 校验）：code 为 lower-kebab-case 分类码，
 * reason 非空说明；edit 至少改 objective 或 maxGoalRounds 之一。
 */
@Service
public class GoalService {

    private static final Logger log = LoggerFactory.getLogger(GoalService.class);
    private static final String NAMESPACE = "goals";
    private static final String CODE_PATTERN = "^[a-z0-9]+(-[a-z0-9]+)*$";

    private final StorageService storage;
    private final JsonUtils jsonUtils;

    public GoalService(StorageService storage) {
        this.storage = storage;
        this.jsonUtils = new JsonUtils();
    }

    /** 会话的当前目标（无 → empty）。 */
    public Optional<Goal> current(String sessionId) {
        return storage.get(NAMESPACE, sessionId).map(v -> jsonUtils.fromJson(v, Goal.class));
    }

    /** 创建目标（会话已有目标则拒绝）。 */
    public Goal create(String sessionId, String objective, Integer maxGoalRounds) {
        if (objective == null || objective.isBlank()) {
            throw new IllegalArgumentException("objective 不能为空");
        }
        if (current(sessionId).isPresent()) {
            throw new IllegalArgumentException("会话已有当前目标（goal_id/revision 见 get_goal）");
        }
        long now = System.currentTimeMillis();
        Goal goal = new Goal(
                "goal-" + UUID.randomUUID().toString().substring(0, 8),
                sessionId,
                objective.trim(),
                Goal.PHASE_ACTIVE,
                null, null,
                maxGoalRounds == null || maxGoalRounds <= 0 ? 20 : maxGoalRounds,
                0, now, now, 1);
        save(sessionId, goal);
        log.info("[Goal] 会话 {} 创建目标 {}（phase=active）", sessionId, goal.id());
        return goal;
    }

    /**
     * 更新目标（CAS：goalId + revision 必须与当前精确一致）。
     *
     * @param action edit | pause | resume | complete | blocked
     */
    public Goal update(String sessionId, String goalId, int revision, String action,
                       String objective, Integer maxGoalRounds,
                       String blockedCode, String blockedReason) {
        Goal current = current(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("会话无当前目标"));
        if (!current.id().equals(goalId) || current.revision() != revision) {
            throw new IllegalArgumentException("goal_id/revision 不匹配（需复制当前精确值）");
        }
        Goal updated;
        switch (action) {
            case "edit" -> updated = edit(current, objective, maxGoalRounds);
            case "pause" -> updated = withPhase(current, Goal.PHASE_PAUSED);
            case "resume" -> updated = withPhase(current, Goal.PHASE_ACTIVE);
            case "complete" -> updated = withPhase(current, Goal.PHASE_COMPLETE);
            case "blocked" -> updated = blocked(current, blockedCode, blockedReason);
            default -> throw new IllegalArgumentException("未知 action: " + action);
        }
        save(sessionId, updated);
        log.info("[Goal] 会话 {} 目标 {} → {} (rev {})", sessionId, updated.id(), action, updated.revision());
        return updated;
    }

    private Goal edit(Goal current, String objective, Integer maxGoalRounds) {
        if ((objective == null || objective.isBlank()) && (maxGoalRounds == null || maxGoalRounds <= 0)) {
            throw new IllegalArgumentException("edit 至少修改 objective 或 maxGoalRounds 之一");
        }
        return new Goal(current.id(), current.sessionId(),
                objective == null || objective.isBlank() ? current.objective() : objective.trim(),
                current.phase(), current.blockedCode(), current.blockedReason(),
                maxGoalRounds == null || maxGoalRounds <= 0 ? current.maxGoalRounds() : maxGoalRounds,
                current.roundsStarted(), current.createdAt(), System.currentTimeMillis(),
                current.revision() + 1);
    }

    private Goal withPhase(Goal current, String phase) {
        return new Goal(current.id(), current.sessionId(), current.objective(), phase,
                Goal.PHASE_BLOCKED.equals(phase) ? current.blockedCode() : null,
                Goal.PHASE_BLOCKED.equals(phase) ? current.blockedReason() : null,
                current.maxGoalRounds(), current.roundsStarted(), current.createdAt(),
                System.currentTimeMillis(), current.revision() + 1);
    }

    private Goal blocked(Goal current, String code, String reason) {
        if (code == null || !code.matches(CODE_PATTERN)) {
            throw new IllegalArgumentException("blocked code 必须为 lower-kebab-case: " + code);
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("blocked reason 不能为空");
        }
        return new Goal(current.id(), current.sessionId(), current.objective(), Goal.PHASE_BLOCKED,
                code, reason.trim(), current.maxGoalRounds(), current.roundsStarted(),
                current.createdAt(), System.currentTimeMillis(), current.revision() + 1);
    }

    private void save(String sessionId, Goal goal) {
        storage.put(NAMESPACE, sessionId, jsonUtils.toJson(goal));
    }
}
