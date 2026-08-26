package com.bizfty.anchon.dsh.github;

import com.bizfty.anchon.dsh.tool.AgentTool;
import com.bizfty.anchon.dsh.tool.Tool;
import com.bizfty.anchon.dsh.tool.ToolCall;
import com.bizfty.anchon.dsh.tool.ToolContext;
import com.bizfty.anchon.dsh.tool.ToolResult;
import com.bizfty.anchon.dsh.tool.ToolSchema;
import org.springframework.stereotype.Component;

/**
 * GitHub PR 工具。
 */
public class GithubPullRequestTools {

    @Component
    @Tool(name = "github_list_pull_requests", description = "列出仓库 PR。")
    public static class ListPullRequestsTool implements AgentTool {
        private final GithubPullRequestService service;

        public ListPullRequestsTool(GithubPullRequestService service) {
            this.service = service;
        }

        @Override
        public String name() { return "github_list_pull_requests"; }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder()
                    .name(name()).description("列出 PR")
                    .addParameter("owner", "string", "仓库所有者（可省略）")
                    .addParameter("repo", "string", "仓库名")
                    .addParameter("state", "string", "open/closed/merged/all, 默认 open")
                    .addParameter("limit", "integer", "上限，默认 30")
                    .required("repo")
                    .build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            try {
                return ToolResult.success("列出 PR 完成",
                        service.listPullRequests(call.getString("owner"), call.getString("repo"),
                                call.getString("state", "open"), call.getInt("limit", 30)));
            } catch (Exception e) {
                return ToolResult.failure("列出 PR 失败: " + e.getMessage());
            }
        }
    }

    @Component
    @Tool(name = "github_get_pull_request", description = "获取单个 PR 详情。")
    public static class GetPullRequestTool implements AgentTool {
        private final GithubPullRequestService service;

        public GetPullRequestTool(GithubPullRequestService service) {
            this.service = service;
        }

        @Override
        public String name() { return "github_get_pull_request"; }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder()
                    .name(name()).description("获取 PR")
                    .addParameter("owner", "string", "仓库所有者（可省略）")
                    .addParameter("repo", "string", "仓库名")
                    .addParameter("number", "integer", "PR 编号")
                    .required("repo", "number")
                    .build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            try {
                int number = call.getInt("number", 0);
                if (number == 0) return ToolResult.failure("缺少必要参数 number");
                return ToolResult.success("获取 PR 成功",
                        service.getPullRequest(call.getString("owner"), call.getString("repo"), number));
            } catch (Exception e) {
                return ToolResult.failure("获取 PR 失败: " + e.getMessage());
            }
        }
    }

    @Component
    @Tool(name = "github_create_pull_request", description = "创建新 PR。")
    public static class CreatePullRequestTool implements AgentTool {
        private final GithubPullRequestService service;

        public CreatePullRequestTool(GithubPullRequestService service) {
            this.service = service;
        }

        @Override
        public String name() { return "github_create_pull_request"; }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder()
                    .name(name()).description("创建 PR")
                    .addParameter("owner", "string", "仓库所有者（可省略）")
                    .addParameter("repo", "string", "仓库名")
                    .addParameter("title", "string", "标题")
                    .addParameter("head", "string", "源分支")
                    .addParameter("base", "string", "目标分支")
                    .addParameter("body", "string", "正文")
                    .addParameter("draft", "boolean", "是否草稿，默认 false")
                    .required("repo", "title", "head", "base")
                    .build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            try {
                String repo = call.getString("repo");
                String title = call.getString("title");
                String head = call.getString("head");
                String base = call.getString("base");
                if (repo == null || title == null || head == null || base == null) {
                    return ToolResult.failure("缺少必要参数 repo/title/head/base");
                }
                return ToolResult.success("创建 PR 成功",
                        service.createPullRequest(call.getString("owner"), repo, title, head, base,
                                call.getString("body"), call.getBool("draft", false)));
            } catch (Exception e) {
                return ToolResult.failure("创建 PR 失败: " + e.getMessage());
            }
        }
    }

    @Component
    @Tool(name = "github_merge_pull_request", description = "合并指定 PR。")
    public static class MergePullRequestTool implements AgentTool {
        private final GithubPullRequestService service;

        public MergePullRequestTool(GithubPullRequestService service) {
            this.service = service;
        }

        @Override
        public String name() { return "github_merge_pull_request"; }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder()
                    .name(name()).description("合并 PR")
                    .addParameter("owner", "string", "仓库所有者（可省略）")
                    .addParameter("repo", "string", "仓库名")
                    .addParameter("number", "integer", "PR 编号")
                    .addParameter("commit_message", "string", "合并提交信息")
                    .addParameter("merge_method", "string", "merge/squash/rebase")
                    .required("repo", "number")
                    .build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            try {
                int number = call.getInt("number", 0);
                if (number == 0) return ToolResult.failure("缺少必要参数 number");
                return ToolResult.success("合并 PR 成功",
                        service.mergePullRequest(call.getString("owner"), call.getString("repo"), number,
                                call.getString("commit_message"), call.getString("merge_method")));
            } catch (Exception e) {
                return ToolResult.failure("合并 PR 失败: " + e.getMessage());
            }
        }
    }

    @Component
    @Tool(name = "github_get_pull_request_diff", description = "获取 PR diff。")
    public static class GetPullRequestDiffTool implements AgentTool {
        private final GithubPullRequestService service;

        public GetPullRequestDiffTool(GithubPullRequestService service) {
            this.service = service;
        }

        @Override
        public String name() { return "github_get_pull_request_diff"; }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder()
                    .name(name()).description("获取 PR diff")
                    .addParameter("owner", "string", "仓库所有者（可省略）")
                    .addParameter("repo", "string", "仓库名")
                    .addParameter("number", "integer", "PR 编号")
                    .required("repo", "number")
                    .build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            try {
                int number = call.getInt("number", 0);
                if (number == 0) return ToolResult.failure("缺少必要参数 number");
                return ToolResult.success("获取 diff 成功",
                        service.getPullRequestDiff(call.getString("owner"), call.getString("repo"), number));
            } catch (Exception e) {
                return ToolResult.failure("获取 diff 失败: " + e.getMessage());
            }
        }
    }

    @Component
    @Tool(name = "github_add_pull_request_comment", description = "为 PR 添加评论。")
    public static class AddPullRequestCommentTool implements AgentTool {
        private final GithubPullRequestService service;

        public AddPullRequestCommentTool(GithubPullRequestService service) {
            this.service = service;
        }

        @Override
        public String name() { return "github_add_pull_request_comment"; }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder()
                    .name(name()).description("添加 PR 评论")
                    .addParameter("owner", "string", "仓库所有者（可省略）")
                    .addParameter("repo", "string", "仓库名")
                    .addParameter("number", "integer", "PR 编号")
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
                return ToolResult.success("PR 评论添加成功",
                        service.addPullRequestComment(call.getString("owner"), call.getString("repo"), number, body));
            } catch (Exception e) {
                return ToolResult.failure("添加 PR 评论失败: " + e.getMessage());
            }
        }
    }
}