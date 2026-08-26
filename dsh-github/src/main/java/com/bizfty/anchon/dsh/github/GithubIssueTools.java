package com.bizfty.anchon.dsh.github;

import com.bizfty.anchon.dsh.tool.AgentTool;
import com.bizfty.anchon.dsh.tool.Tool;
import com.bizfty.anchon.dsh.tool.ToolCall;
import com.bizfty.anchon.dsh.tool.ToolContext;
import com.bizfty.anchon.dsh.tool.ToolResult;
import com.bizfty.anchon.dsh.tool.ToolSchema;
import org.springframework.stereotype.Component;

/**
 * GitHub Issue 工具。
 */
public class GithubIssueTools {

    @Component
    @Tool(name = "github_list_issues", description = "列出仓库 issue。")
    public static class ListIssuesTool implements AgentTool {
        private final GithubIssueService service;

        public ListIssuesTool(GithubIssueService service) {
            this.service = service;
        }

        @Override
        public String name() {
            return "github_list_issues";
        }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder()
                    .name(name()).description("列出 issue")
                    .addParameter("owner", "string", "仓库所有者（可省略）")
                    .addParameter("repo", "string", "仓库名")
                    .addParameter("state", "string", "open/closed/all，默认 open")
                    .addParameter("labels", "string", "按标签过滤（逗号分隔）")
                    .addParameter("limit", "integer", "上限，默认 30")
                    .required("repo")
                    .build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            try {
                return ToolResult.success("列出 issue 完成",
                        service.listIssues(call.getString("owner"), call.getString("repo"),
                                call.getString("state", "open"), call.getString("labels"),
                                call.getInt("limit", 30)));
            } catch (Exception e) {
                return ToolResult.failure("列出 issue 失败: " + e.getMessage());
            }
        }
    }

    @Component
    @Tool(name = "github_get_issue", description = "获取单个 issue 详情。")
    public static class GetIssueTool implements AgentTool {
        private final GithubIssueService service;

        public GetIssueTool(GithubIssueService service) {
            this.service = service;
        }

        @Override
        public String name() {
            return "github_get_issue";
        }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder()
                    .name(name()).description("获取 issue")
                    .addParameter("owner", "string", "仓库所有者（可省略）")
                    .addParameter("repo", "string", "仓库名")
                    .addParameter("number", "integer", "issue 编号")
                    .required("repo", "number")
                    .build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            try {
                int number = call.getInt("number", 0);
                if (number == 0) return ToolResult.failure("缺少必要参数 number");
                return ToolResult.success("获取 issue 成功",
                        service.getIssue(call.getString("owner"), call.getString("repo"), number));
            } catch (Exception e) {
                return ToolResult.failure("获取 issue 失败: " + e.getMessage());
            }
        }
    }

    @Component
    @Tool(name = "github_create_issue", description = "创建新 issue。")
    public static class CreateIssueTool implements AgentTool {
        private final GithubIssueService service;

        public CreateIssueTool(GithubIssueService service) {
            this.service = service;
        }

        @Override
        public String name() {
            return "github_create_issue";
        }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder()
                    .name(name()).description("创建 issue")
                    .addParameter("owner", "string", "仓库所有者（可省略）")
                    .addParameter("repo", "string", "仓库名")
                    .addParameter("title", "string", "标题")
                    .addParameter("body", "string", "正文")
                    .addParameter("labels", "string", "标签（逗号分隔）")
                    .addParameter("assignees", "string", "分配人（逗号分隔）")
                    .required("repo", "title")
                    .build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            try {
                String repo = call.getString("repo");
                String title = call.getString("title");
                if (repo == null || title == null) return ToolResult.failure("缺少必要参数 repo/title");
                return ToolResult.success("创建 issue 成功",
                        service.createIssue(call.getString("owner"), repo, title,
                                call.getString("body"), call.getString("labels"), call.getString("assignees")));
            } catch (Exception e) {
                return ToolResult.failure("创建 issue 失败: " + e.getMessage());
            }
        }
    }

    @Component
    @Tool(name = "github_update_issue", description = "更新 issue 标题、正文或状态。")
    public static class UpdateIssueTool implements AgentTool {
        private final GithubIssueService service;

        public UpdateIssueTool(GithubIssueService service) {
            this.service = service;
        }

        @Override
        public String name() {
            return "github_update_issue";
        }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder()
                    .name(name()).description("更新 issue")
                    .addParameter("owner", "string", "仓库所有者（可省略）")
                    .addParameter("repo", "string", "仓库名")
                    .addParameter("number", "integer", "issue 编号")
                    .addParameter("title", "string", "新标题")
                    .addParameter("body", "string", "新正文")
                    .addParameter("state", "string", "open/closed")
                    .required("repo", "number")
                    .build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            try {
                int number = call.getInt("number", 0);
                if (number == 0) return ToolResult.failure("缺少必要参数 number");
                return ToolResult.success("更新 issue 成功",
                        service.updateIssue(call.getString("owner"), call.getString("repo"), number,
                                call.getString("title"), call.getString("body"), call.getString("state")));
            } catch (Exception e) {
                return ToolResult.failure("更新 issue 失败: " + e.getMessage());
            }
        }
    }

    @Component
    @Tool(name = "github_add_issue_comment", description = "为指定 issue 添加评论。")
    public static class AddIssueCommentTool implements AgentTool {
        private final GithubIssueService service;

        public AddIssueCommentTool(GithubIssueService service) {
            this.service = service;
        }

        @Override
        public String name() {
            return "github_add_issue_comment";
        }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder()
                    .name(name()).description("添加 issue 评论")
                    .addParameter("owner", "string", "仓库所有者（可省略）")
                    .addParameter("repo", "string", "仓库名")
                    .addParameter("number", "integer", "issue 编号")
                    .addParameter("body", "string", "评论内容")
                    .required("repo", "number", "body")
                    .build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            try {
                int number = call.getInt("number", 0);
                String body = call.getString("body");
                if (number == 0 || body == null) return ToolResult.failure("缺少必要参数 number/body");
                return ToolResult.success("评论添加成功",
                        service.addIssueComment(call.getString("owner"), call.getString("repo"), number, body));
            } catch (Exception e) {
                return ToolResult.failure("添加评论失败: " + e.getMessage());
            }
        }
    }

    @Component
    @Tool(name = "github_list_issue_comments", description = "列出 issue 的所有评论。")
    public static class ListIssueCommentsTool implements AgentTool {
        private final GithubIssueService service;

        public ListIssueCommentsTool(GithubIssueService service) {
            this.service = service;
        }

        @Override
        public String name() {
            return "github_list_issue_comments";
        }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder()
                    .name(name()).description("列出 issue 评论")
                    .addParameter("owner", "string", "仓库所有者（可省略）")
                    .addParameter("repo", "string", "仓库名")
                    .addParameter("number", "integer", "issue 编号")
                    .required("repo", "number")
                    .build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            try {
                int number = call.getInt("number", 0);
                if (number == 0) return ToolResult.failure("缺少必要参数 number");
                return ToolResult.success("列出评论完成",
                        service.listIssueComments(call.getString("owner"), call.getString("repo"), number));
            } catch (Exception e) {
                return ToolResult.failure("列出评论失败: " + e.getMessage());
            }
        }
    }
}