package com.bizfty.anchon.dsh.goal;

import com.bizfty.anchon.dsh.storage.StorageService;
import com.bizfty.anchon.dsh.util.JsonUtils;
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
 * <p>
 * 自动续轮：{@link #reserveNextRound} 由续轮驱动方（AgentLoopService）在 turn
 * 正常结束后调用 —— CAS 校验 active + 未超限后 roundsStarted+1，即"预留下一轮"
 * （对应 DSH goal-round-driver 的 admitted round 累加）。
 */
@Service
public class GoalService {

    private static final Logger log = LoggerFactory.getLogger(GoalService.class);
    private static final String CODE_PATTERN = "^[a-z0-9]+(-[a-z0-9]+)*$";

    /** 未显式指定时的默认轮数上限（对齐官方 dsh-goal 的 defaultMaxGoalRounds=256）。 */
    private static final int DEFAULT_MAX_GOAL_ROUNDS = 256;

    private final StorageService storage;
    private final JsonUtils jsonUtils;

    /**
     * 进程本地自动续行权限（对齐官方 activation，不持久化）：
     * 不在集合内 = armed（允许自动续轮）；在集合内 = disarmed（禁止自动续轮，
     * 需显式 resume 恢复）。
     */
    private final java.util.Set<String> disarmedSessions = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public GoalService(StorageService storage) {
        this.storage = storage;
        this.jsonUtils = new JsonUtils();
    }

    /** 会话的当前目标（无 → empty）。 */
    public Optional<Goal> current(String sessionId) {
        return storage.get(sessionNamespace(sessionId), com.bizfty.anchon.dsh.core.model.SessionSettings.KEY_GOAL)
                .map(v -> jsonUtils.fromJson(v, Goal.class));
    }

    /** 会话级设置命名空间（session.<id>）。 */
    private static String sessionNamespace(String sessionId) {
        return com.bizfty.anchon.dsh.core.model.SessionSettings.namespace(
                com.bizfty.anchon.dsh.core.model.SessionId.of(sessionId));
    }

    /**
     * 解除自动续行（对齐官方 {@code GoalService.disarm()}）：仅移除进程本地权限，
     * 不改变持久 phase/revision。后续需显式 resume 恢复 armed。
     */
    public void disarm(String sessionId) {
        disarmedSessions.add(sessionId);
        log.info("[Goal] 会话 {} 自动续行已解除（disarmed，需 resume 恢复）", sessionId);
    }

    /** 自动续行是否已启用（armed）：未解除且当前存在 active 目标。 */
    public boolean isArmed(String sessionId) {
        return !disarmedSessions.contains(sessionId)
                && current(sessionId).map(g -> Goal.PHASE_ACTIVE.equals(g.phase())).orElse(false);
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
                maxGoalRounds == null || maxGoalRounds <= 0 ? DEFAULT_MAX_GOAL_ROUNDS : maxGoalRounds,
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
            case "resume" -> {
                updated = withPhase(current, Goal.PHASE_ACTIVE);
                // resume = 用户授权恢复自动续行（对齐官方 activation：disarmed → armed）
                disarmedSessions.remove(sessionId);
            }
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

    /**
     * 自动续轮驱动：预留下一轮（CAS）。
     * <p>
     * 仅当 goal 为 active 且 {@code roundsStarted < maxGoalRounds} 时累加 roundsStarted
     * 并返回新 goal（对应官方 goal-round-driver 的 admitted round）；否则返回 empty。
     * 调用方必须携带当前精确的 goalId + revision（与 update 相同的 CAS 语义）。
     *
     * @return 预留成功 → 累加后的 goal；goal 不存在 / 非 active / 已超限 → empty
     */
    public Optional<Goal> reserveNextRound(String sessionId, String goalId, int revision) {
        Goal current = current(sessionId).orElse(null);
        if (current == null) {
            return Optional.empty();
        }
        if (!current.id().equals(goalId) || current.revision() != revision) {
            throw new IllegalArgumentException("goal_id/revision 不匹配（需复制当前精确值）");
        }
        if (!Goal.PHASE_ACTIVE.equals(current.phase())) {
            return Optional.empty();
        }
        if (current.roundsStarted() >= current.maxGoalRounds()) {
            return Optional.empty();
        }
        Goal advanced = new Goal(current.id(), current.sessionId(), current.objective(), current.phase(),
                current.blockedCode(), current.blockedReason(), current.maxGoalRounds(),
                current.roundsStarted() + 1, current.createdAt(), System.currentTimeMillis(),
                current.revision() + 1);
        save(sessionId, advanced);
        log.info("[Goal] 会话 {} 目标 {} 预留下一轮: rounds {} -> {} (rev {})",
                sessionId, advanced.id(), current.roundsStarted(), advanced.roundsStarted(), advanced.revision());
        return Optional.of(advanced);
    }

    /** 达到轮数上限时把 goal 置为 blocked（round-limit），对齐官方 goal-round-driver 的 round-limit 阻塞。 */
    public Goal blockRoundLimit(String sessionId, String goalId, int revision) {
        return update(sessionId, goalId, revision, "blocked", null, null,
                "round-limit", "Goal reached its configured limit of rounds.");
    }

    private void save(String sessionId, Goal goal) {
        storage.put(sessionNamespace(sessionId), com.bizfty.anchon.dsh.core.model.SessionSettings.KEY_GOAL,
                jsonUtils.toJson(goal));
    }
}
