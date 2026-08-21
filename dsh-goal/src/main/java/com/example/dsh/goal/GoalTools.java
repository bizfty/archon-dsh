package com.example.dsh.goal;

import com.example.dsh.tool.AgentTool;
import com.example.dsh.tool.ToolCall;
import com.example.dsh.tool.ToolContext;
import com.example.dsh.tool.ToolResult;
import com.example.dsh.tool.ToolSchema;
import org.springframework.stereotype.Component;

/**
 * 目标模型工具（对应 DSH goal/tool-goal）：create_goal / update_goal / get_goal。
 * <p>
 * update_goal 语义对齐 DSH CAS：必须携带精确的 goal_id + revision
 * （get_goal 可查当前精确值）；action = edit | pause | resume | complete | blocked。
 */
@Component
public class GoalTools {

    /** 创建持久化 same-session 目标（每会话至多一个）。 */
    @Component
    public static class CreateGoalTool implements AgentTool {
        private final GoalService goals;

        public CreateGoalTool(GoalService goals) {
            this.goals = goals;
        }

        @Override
        public String name() {
            return "create_goal";
        }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder()
                    .name(name())
                    .description("创建持久化同会话目标（可从直接请求推断意图；例行单轮工作不创建）。返回 goal_id 与 revision，后续 update_goal 需携带")
                    .addParameter("objective", "string", "具体完成目标")
                    .addParameter("maxGoalRounds", "integer", "自动延续轮数上限（可省略）")
                    .required("objective")
                    .build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            try {
                Goal goal = goals.create(context.sessionId().value(),
                        call.getString("objective"), call.getInt("maxGoalRounds", null));
                return ToolResult.success("已创建目标: id=" + goal.id() + " revision=" + goal.revision()
                        + " phase=" + goal.phase() + " maxGoalRounds=" + goal.maxGoalRounds());
            } catch (IllegalArgumentException e) {
                return ToolResult.failure(e.getMessage());
            }
        }
    }

    /** 更新目标（CAS）。 */
    @Component
    public static class UpdateGoalTool implements AgentTool {
        private final GoalService goals;

        public UpdateGoalTool(GoalService goals) {
            this.goals = goals;
        }

        @Override
        public String name() {
            return "update_goal";
        }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder()
                    .name(name())
                    .description("更新当前目标：edit/pause/resume/complete/blocked。必须复制 get_goal 返回的精确 goal_id 与 revision（CAS）")
                    .addParameter("goal_id", "string", "目标 id（精确复制）")
                    .addParameter("revision", "integer", "目标修订号（精确复制）")
                    .addParameter("action", "string", "edit|pause|resume|complete|blocked")
                    .addParameter("objective", "string", "edit 时的新目标文本")
                    .addParameter("maxGoalRounds", "integer", "edit 时的新轮数上限")
                    .addParameter("blockedCode", "string", "blocked 时的分类码（lower-kebab-case）")
                    .addParameter("blockedReason", "string", "blocked 时的具体阻塞说明")
                    .required("goal_id", "revision", "action")
                    .build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            try {
                Goal goal = goals.update(context.sessionId().value(),
                        call.getString("goal_id"),
                        call.getInt("revision", 0),
                        call.getString("action"),
                        call.getString("objective"),
                        call.getInt("maxGoalRounds", null),
                        call.getString("blockedCode"),
                        call.getString("blockedReason"));
                return ToolResult.success("目标已更新: phase=" + goal.phase() + " revision=" + goal.revision()
                        + (goal.blockedReason() == null ? "" : " blocked=" + goal.blockedReason()));
            } catch (IllegalArgumentException e) {
                return ToolResult.failure(e.getMessage());
            }
        }
    }

    /** 读取当前目标。 */
    @Component
    public static class GetGoalTool implements AgentTool {
        private final GoalService goals;

        public GetGoalTool(GoalService goals) {
            this.goals = goals;
        }

        @Override
        public String name() {
            return "get_goal";
        }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder()
                    .name(name())
                    .description("读取会话当前目标（含精确 goal_id/revision 供 update_goal CAS 使用）")
                    .build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            return goals.current(context.sessionId().value())
                    .map(g -> ToolResult.success("当前目标: id=" + g.id() + " revision=" + g.revision()
                            + " phase=" + g.phase() + " rounds=" + g.roundsStarted() + "/" + g.maxGoalRounds()
                            + " objective=" + g.objective()
                            + (g.blockedReason() == null ? "" : " blocked: " + g.blockedReason())))
                    .orElseGet(() -> ToolResult.success("会话无当前目标"));
        }
    }
}
