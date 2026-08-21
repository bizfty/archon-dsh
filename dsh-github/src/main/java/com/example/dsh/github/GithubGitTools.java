package com.example.dsh.github;

import com.example.dsh.tool.AgentTool;
import com.example.dsh.tool.Tool;
import com.example.dsh.tool.ToolCall;
import com.example.dsh.tool.ToolContext;
import com.example.dsh.tool.ToolResult;
import com.example.dsh.tool.ToolSchema;
import org.springframework.stereotype.Component;

/**
 * GitHub Git 操作工具 — 分支/tag/commit。
 */
public class GithubGitTools {

    @Component
    @Tool(name = "github_list_branches", description = "列出仓库分支。")
    public static class ListBranchesTool implements AgentTool {
        private final GithubGitService service;

        public ListBranchesTool(GithubGitService service) {
            this.service = service;
        }

        @Override
        public String name() { return "github_list_branches"; }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder()
                    .name(name()).description("列出分支")
                    .addParameter("owner", "string", "仓库所有者（可省略）")
                    .addParameter("repo", "string", "仓库名")
                    .addParameter("limit", "integer", "上限，默认 50")
                    .required("repo")
                    .build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            try {
                return ToolResult.success("列出分支完成",
                        service.listBranches(call.getString("owner"), call.getString("repo"), call.getInt("limit", 50)));
            } catch (Exception e) {
                return ToolResult.failure("列出分支失败: " + e.getMessage());
            }
        }
    }

    @Component
    @Tool(name = "github_create_branch", description = "从指定 ref 创建新分支。")
    public static class CreateBranchTool implements AgentTool {
        private final GithubGitService service;

        public CreateBranchTool(GithubGitService service) {
            this.service = service;
        }

        @Override
        public String name() { return "github_create_branch"; }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder()
                    .name(name()).description("创建分支")
                    .addParameter("owner", "string", "仓库所有者（可省略）")
                    .addParameter("repo", "string", "仓库名")
                    .addParameter("branch", "string", "新分支名")
                    .addParameter("from_ref", "string", "源 ref，默认默认分支")
                    .required("repo", "branch")
                    .build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            try {
                String repo = call.getString("repo");
                String branch = call.getString("branch");
                if (repo == null || branch == null) return ToolResult.failure("缺少必要参数 repo/branch");
                return ToolResult.success("创建分支成功",
                        service.createBranch(call.getString("owner"), repo, branch, call.getString("from_ref")));
            } catch (Exception e) {
                return ToolResult.failure("创建分支失败: " + e.getMessage());
            }
        }
    }

    @Component
    @Tool(name = "github_list_tags", description = "列出仓库 tag。")
    public static class ListTagsTool implements AgentTool {
        private final GithubGitService service;

        public ListTagsTool(GithubGitService service) {
            this.service = service;
        }

        @Override
        public String name() { return "github_list_tags"; }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder()
                    .name(name()).description("列出 tag")
                    .addParameter("owner", "string", "仓库所有者（可省略）")
                    .addParameter("repo", "string", "仓库名")
                    .addParameter("limit", "integer", "上限，默认 50")
                    .required("repo")
                    .build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            try {
                return ToolResult.success("列出 tag 完成",
                        service.listTags(call.getString("owner"), call.getString("repo"), call.getInt("limit", 50)));
            } catch (Exception e) {
                return ToolResult.failure("列出 tag 失败: " + e.getMessage());
            }
        }
    }

    @Component
    @Tool(name = "github_list_commits", description = "列出 commit 历史。")
    public static class ListCommitsTool implements AgentTool {
        private final GithubGitService service;

        public ListCommitsTool(GithubGitService service) {
            this.service = service;
        }

        @Override
        public String name() { return "github_list_commits"; }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder()
                    .name(name()).description("列出 commits")
                    .addParameter("owner", "string", "仓库所有者（可省略）")
                    .addParameter("repo", "string", "仓库名")
                    .addParameter("ref", "string", "分支/tag，默认默认分支")
                    .addParameter("limit", "integer", "上限，默认 30")
                    .required("repo")
                    .build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            try {
                return ToolResult.success("列出 commits 完成",
                        service.listCommits(call.getString("owner"), call.getString("repo"),
                                call.getString("ref"), call.getInt("limit", 30)));
            } catch (Exception e) {
                return ToolResult.failure("列出 commits 失败: " + e.getMessage());
            }
        }
    }

    @Component
    @Tool(name = "github_get_commit", description = "获取单个 commit 详情。")
    public static class GetCommitTool implements AgentTool {
        private final GithubGitService service;

        public GetCommitTool(GithubGitService service) {
            this.service = service;
        }

        @Override
        public String name() { return "github_get_commit"; }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder()
                    .name(name()).description("获取 commit")
                    .addParameter("owner", "string", "仓库所有者（可省略）")
                    .addParameter("repo", "string", "仓库名")
                    .addParameter("sha", "string", "commit SHA")
                    .required("repo", "sha")
                    .build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            try {
                String sha = call.getString("sha");
                if (sha == null) return ToolResult.failure("缺少必要参数 sha");
                return ToolResult.success("获取 commit 成功",
                        service.getCommit(call.getString("owner"), call.getString("repo"), sha));
            } catch (Exception e) {
                return ToolResult.failure("获取 commit 失败: " + e.getMessage());
            }
        }
    }
}