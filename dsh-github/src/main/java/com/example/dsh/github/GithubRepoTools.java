package com.example.dsh.github;

import com.example.dsh.tool.AgentTool;
import com.example.dsh.tool.Tool;
import com.example.dsh.tool.ToolCall;
import com.example.dsh.tool.ToolContext;
import com.example.dsh.tool.ToolResult;
import com.example.dsh.tool.ToolSchema;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * GitHub 仓库工具 — search / get / list / readme / contents / file_content / git_tree。
 */
public class GithubRepoTools {

    @Component
    @Tool(name = "github_search_repositories", description = "搜索 GitHub 仓库（按关键字）。")
    public static class SearchRepositoriesTool implements AgentTool {
        private final GithubService service;

        public SearchRepositoriesTool(GithubService service) {
            this.service = service;
        }

        @Override
        public String name() {
            return "github_search_repositories";
        }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder()
                    .name(name())
                    .description("搜索仓库")
                    .addParameter("query", "string", "搜索关键字（可带 language:xxx 等限定）")
                    .addParameter("limit", "integer", "返回上限，默认 30")
                    .required("query")
                    .build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            String query = call.getString("query");
            int limit = call.getInt("limit", 30);
            if (query == null || query.isBlank()) return ToolResult.failure("缺少必要参数 query");
            try {
                Map<String, Object> data = service.searchRepositories(query, limit);
                return ToolResult.success("搜索完成", data);
            } catch (Exception e) {
                return ToolResult.failure("搜索失败: " + e.getMessage());
            }
        }
    }

    @Component
    @Tool(name = "github_get_repository", description = "获取仓库详情。")
    public static class GetRepositoryTool implements AgentTool {
        private final GithubService service;

        public GetRepositoryTool(GithubService service) {
            this.service = service;
        }

        @Override
        public String name() {
            return "github_get_repository";
        }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder()
                    .name(name())
                    .description("获取仓库详情")
                    .addParameter("owner", "string", "仓库所有者（可省略，使用默认）")
                    .addParameter("repo", "string", "仓库名")
                    .required("repo")
                    .build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            String owner = call.getString("owner");
            String repo = call.getString("repo");
            if (repo == null) return ToolResult.failure("缺少必要参数 repo");
            try {
                return ToolResult.success("获取仓库详情成功", service.getRepository(owner, repo));
            } catch (Exception e) {
                return ToolResult.failure("获取仓库详情失败: " + e.getMessage());
            }
        }
    }

    @Component
    @Tool(name = "github_list_repositories", description = "列出指定用户或组织的仓库。")
    public static class ListRepositoriesTool implements AgentTool {
        private final GithubService service;

        public ListRepositoriesTool(GithubService service) {
            this.service = service;
        }

        @Override
        public String name() {
            return "github_list_repositories";
        }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder()
                    .name(name())
                    .description("列出仓库")
                    .addParameter("owner", "string", "用户或组织名")
                    .addParameter("type", "string", "user/org, 默认 user")
                    .addParameter("limit", "integer", "上限，默认 30")
                    .required("owner")
                    .build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            String owner = call.getString("owner");
            String type = call.getString("type", "user");
            int limit = call.getInt("limit", 30);
            if (owner == null) return ToolResult.failure("缺少必要参数 owner");
            try {
                return ToolResult.success("列出仓库完成", service.listRepositories(owner, type, limit));
            } catch (Exception e) {
                return ToolResult.failure("列出仓库失败: " + e.getMessage());
            }
        }
    }

    @Component
    @Tool(name = "github_get_readme", description = "获取仓库 README 内容。")
    public static class GetReadmeTool implements AgentTool {
        private final GithubService service;

        public GetReadmeTool(GithubService service) {
            this.service = service;
        }

        @Override
        public String name() {
            return "github_get_readme";
        }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder()
                    .name(name())
                    .description("获取 README")
                    .addParameter("owner", "string", "仓库所有者（可省略）")
                    .addParameter("repo", "string", "仓库名")
                    .required("repo")
                    .build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            String owner = call.getString("owner");
            String repo = call.getString("repo");
            if (repo == null) return ToolResult.failure("缺少必要参数 repo");
            try {
                return ToolResult.success("获取 README 成功", service.getReadme(owner, repo));
            } catch (Exception e) {
                return ToolResult.failure("获取 README 失败: " + e.getMessage());
            }
        }
    }

    @Component
    @Tool(name = "github_get_contents", description = "列出仓库指定路径下的文件/目录。")
    public static class GetContentsTool implements AgentTool {
        private final GithubService service;

        public GetContentsTool(GithubService service) {
            this.service = service;
        }

        @Override
        public String name() {
            return "github_get_contents";
        }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder()
                    .name(name())
                    .description("列出目录")
                    .addParameter("owner", "string", "仓库所有者（可省略）")
                    .addParameter("repo", "string", "仓库名")
                    .addParameter("path", "string", "目录路径（默认根）")
                    .addParameter("ref", "string", "分支/tag/commit，默认默认分支")
                    .required("repo")
                    .build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            String owner = call.getString("owner");
            String repo = call.getString("repo");
            String path = call.getString("path");
            String ref = call.getString("ref");
            if (repo == null) return ToolResult.failure("缺少必要参数 repo");
            try {
                return ToolResult.success("列出目录成功", service.getContents(owner, repo, path, ref));
            } catch (Exception e) {
                return ToolResult.failure("列出目录失败: " + e.getMessage());
            }
        }
    }

    @Component
    @Tool(name = "github_get_file_content", description = "获取仓库指定文件的内容。")
    public static class GetFileContentTool implements AgentTool {
        private final GithubService service;

        public GetFileContentTool(GithubService service) {
            this.service = service;
        }

        @Override
        public String name() {
            return "github_get_file_content";
        }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder()
                    .name(name())
                    .description("获取文件内容")
                    .addParameter("owner", "string", "仓库所有者（可省略）")
                    .addParameter("repo", "string", "仓库名")
                    .addParameter("path", "string", "文件路径")
                    .addParameter("ref", "string", "分支/tag/commit")
                    .required("repo", "path")
                    .build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            String owner = call.getString("owner");
            String repo = call.getString("repo");
            String path = call.getString("path");
            String ref = call.getString("ref");
            if (repo == null || path == null) return ToolResult.failure("缺少必要参数 repo/path");
            try {
                return ToolResult.success("获取文件成功", service.getFileContent(owner, repo, path, ref));
            } catch (Exception e) {
                return ToolResult.failure("获取文件失败: " + e.getMessage());
            }
        }
    }

    @Component
    @Tool(name = "github_get_git_tree", description = "递归列出仓库文件树。")
    public static class GetGitTreeTool implements AgentTool {
        private final GithubService service;

        public GetGitTreeTool(GithubService service) {
            this.service = service;
        }

        @Override
        public String name() {
            return "github_get_git_tree";
        }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder()
                    .name(name())
                    .description("获取 Git 树")
                    .addParameter("owner", "string", "仓库所有者（可省略）")
                    .addParameter("repo", "string", "仓库名")
                    .addParameter("branch", "string", "分支（默认默认分支）")
                    .addParameter("recursive", "boolean", "是否递归（默认 true）")
                    .required("repo")
                    .build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            String owner = call.getString("owner");
            String repo = call.getString("repo");
            String branch = call.getString("branch");
            boolean recursive = call.getBool("recursive", true);
            if (repo == null) return ToolResult.failure("缺少必要参数 repo");
            try {
                return ToolResult.success("获取树成功", service.getGitTree(owner, repo, branch, recursive));
            } catch (Exception e) {
                return ToolResult.failure("获取树失败: " + e.getMessage());
            }
        }
    }
}