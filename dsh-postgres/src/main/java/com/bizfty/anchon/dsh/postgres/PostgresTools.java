package com.bizfty.anchon.dsh.postgres;

import com.bizfty.anchon.dsh.tool.AgentTool;
import com.bizfty.anchon.dsh.tool.Tool;
import com.bizfty.anchon.dsh.tool.ToolCall;
import com.bizfty.anchon.dsh.tool.ToolContext;
import com.bizfty.anchon.dsh.tool.ToolResult;
import com.bizfty.anchon.dsh.tool.ToolSchema;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * PostgreSQL AgentTool 实现。
 */
public class PostgresTools {

    @Component
    @Tool(name = "postgres_query", description = "执行只读 SELECT / WITH 查询，返回结果集。")
    public static class QueryTool implements AgentTool {
        private final PostgresQueryService service;

        public QueryTool(PostgresQueryService service) {
            this.service = service;
        }

        @Override
        public String name() { return "postgres_query"; }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder()
                    .name(name()).description("执行只读 SQL 查询")
                    .addParameter("sql", "string", "SELECT 或 WITH...SELECT 语句")
                    .required("sql")
                    .build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            String sql = call.getString("sql");
            if (sql == null || sql.isBlank()) return ToolResult.failure("缺少必要参数 sql");
            try {
                Map<String, Object> data = service.query(sql);
                Object err = data.get("error");
                if (err != null) return ToolResult.failure(String.valueOf(err), data);
                return ToolResult.success("PostgreSQL 查询完成", data);
            } catch (Exception e) {
                return ToolResult.failure("PostgreSQL 查询失败: " + e.getMessage());
            }
        }
    }

    @Component
    @Tool(name = "postgres_suggest_index", description = "基于 EXPLAIN 分析查询计划并给出索引建议。")
    public static class SuggestIndexTool implements AgentTool {
        private final PostgresQueryService service;

        public SuggestIndexTool(PostgresQueryService service) {
            this.service = service;
        }

        @Override
        public String name() { return "postgres_suggest_index"; }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder()
                    .name(name()).description("索引建议")
                    .addParameter("sql", "string", "待优化的 SELECT / WITH 查询")
                    .required("sql")
                    .build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            String sql = call.getString("sql");
            if (sql == null || sql.isBlank()) return ToolResult.failure("缺少必要参数 sql");
            try {
                Map<String, Object> data = service.suggestIndexes(sql);
                Object err = data.get("error");
                if (err != null) return ToolResult.failure(String.valueOf(err), data);
                return ToolResult.success("索引建议生成完成", data);
            } catch (Exception e) {
                return ToolResult.failure("索引建议失败: " + e.getMessage());
            }
        }
    }

    @Component
    @Tool(name = "postgres_describe_table", description = "获取指定 schema 下表结构。")
    public static class DescribeTool implements AgentTool {
        private final PostgresQueryService service;

        public DescribeTool(PostgresQueryService service) {
            this.service = service;
        }

        @Override
        public String name() { return "postgres_describe_table"; }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder()
                    .name(name()).description("获取表结构")
                    .addParameter("schema", "string", "schema 名，默认 public")
                    .addParameter("table", "string", "表名")
                    .required("table")
                    .build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            String table = call.getString("table");
            if (table == null || table.isBlank()) return ToolResult.failure("缺少必要参数 table");
            try {
                Map<String, Object> data = service.describe(call.getString("schema"), table);
                Object err = data.get("error");
                if (err != null) return ToolResult.failure(String.valueOf(err), data);
                return ToolResult.success("表结构获取完成: " + table, data);
            } catch (Exception e) {
                return ToolResult.failure("获取表结构失败: " + e.getMessage());
            }
        }
    }
}