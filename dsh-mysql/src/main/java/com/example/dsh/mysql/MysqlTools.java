package com.example.dsh.mysql;

import com.example.dsh.tool.AgentTool;
import com.example.dsh.tool.Tool;
import com.example.dsh.tool.ToolCall;
import com.example.dsh.tool.ToolContext;
import com.example.dsh.tool.ToolResult;
import com.example.dsh.tool.ToolSchema;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * MySQL AgentTool 实现。
 */
public class MysqlTools {

    @Component
    @Tool(name = "mysql_query", description = "执行只读 SELECT / WITH 查询，返回结果集。")
    public static class QueryTool implements AgentTool {
        private final MysqlQueryService service;

        public QueryTool(MysqlQueryService service) {
            this.service = service;
        }

        @Override
        public String name() { return "mysql_query"; }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder()
                    .name(name()).description("执行只读 SQL 查询")
                    .addParameter("sql", "string", "SELECT 或 WITH...SELECT 语句")
                    .addParameter("desensitize", "boolean", "是否启用 PII 脱敏，默认 true")
                    .required("sql")
                    .build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            String sql = call.getString("sql");
            if (sql == null || sql.isBlank()) return ToolResult.failure("缺少必要参数 sql");
            boolean desensitize = call.getBool("desensitize", true);
            try {
                Map<String, Object> data = service.query(sql, desensitize);
                Object err = data.get("error");
                if (err != null) {
                    return ToolResult.failure(String.valueOf(err), data);
                }
                return ToolResult.success("MySQL 查询完成", data);
            } catch (Exception e) {
                return ToolResult.failure("MySQL 查询失败: " + e.getMessage());
            }
        }
    }

    @Component
    @Tool(name = "mysql_describe_table", description = "获取指定表结构（字段名/类型/可空/键）。")
    public static class DescribeTool implements AgentTool {
        private final MysqlQueryService service;

        public DescribeTool(MysqlQueryService service) {
            this.service = service;
        }

        @Override
        public String name() { return "mysql_describe_table"; }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder()
                    .name(name()).description("获取表结构")
                    .addParameter("table", "string", "表名")
                    .required("table")
                    .build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            String table = call.getString("table");
            if (table == null || table.isBlank()) return ToolResult.failure("缺少必要参数 table");
            try {
                Map<String, Object> data = service.describe(table);
                Object err = data.get("error");
                if (err != null) return ToolResult.failure(String.valueOf(err), data);
                return ToolResult.success("表结构获取完成: " + table, data);
            } catch (Exception e) {
                return ToolResult.failure("获取表结构失败: " + e.getMessage());
            }
        }
    }
}