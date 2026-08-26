package com.bizfty.anchon.dsh.github;

import com.bizfty.anchon.dsh.tool.AgentTool;
import com.bizfty.anchon.dsh.tool.Tool;
import com.bizfty.anchon.dsh.tool.ToolCall;
import com.bizfty.anchon.dsh.tool.ToolContext;
import com.bizfty.anchon.dsh.tool.ToolResult;
import com.bizfty.anchon.dsh.tool.ToolSchema;
import org.springframework.stereotype.Component;

/**
 * GitHub 元信息工具。
 */
public class GithubMetaTools {

    @Component
    @Tool(name = "github_get_current_user", description = "获取当前认证用户信息。")
    public static class GetCurrentUserTool implements AgentTool {
        private final GithubMetaService service;

        public GetCurrentUserTool(GithubMetaService service) {
            this.service = service;
        }

        @Override
        public String name() { return "github_get_current_user"; }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder().name(name()).description("获取当前用户").build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            try {
                return ToolResult.success("获取当前用户成功", service.getCurrentUser());
            } catch (Exception e) {
                return ToolResult.failure("获取当前用户失败: " + e.getMessage());
            }
        }
    }

    @Component
    @Tool(name = "github_get_rate_limit", description = "获取 GitHub API 速率限制。")
    public static class GetRateLimitTool implements AgentTool {
        private final GithubMetaService service;

        public GetRateLimitTool(GithubMetaService service) {
            this.service = service;
        }

        @Override
        public String name() { return "github_get_rate_limit"; }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder().name(name()).description("获取 rate limit").build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            try {
                return ToolResult.success("获取 rate limit 成功", service.getRateLimit());
            } catch (Exception e) {
                return ToolResult.failure("获取 rate limit 失败: " + e.getMessage());
            }
        }
    }

    @Component
    @Tool(name = "github_get_user", description = "获取指定用户信息。")
    public static class GetUserTool implements AgentTool {
        private final GithubMetaService service;

        public GetUserTool(GithubMetaService service) {
            this.service = service;
        }

        @Override
        public String name() { return "github_get_user"; }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder()
                    .name(name()).description("获取用户信息")
                    .addParameter("login", "string", "用户名")
                    .required("login")
                    .build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            try {
                String login = call.getString("login");
                if (login == null) return ToolResult.failure("缺少必要参数 login");
                return ToolResult.success("获取用户成功", service.getUserInfo(login));
            } catch (Exception e) {
                return ToolResult.failure("获取用户失败: " + e.getMessage());
            }
        }
    }
}