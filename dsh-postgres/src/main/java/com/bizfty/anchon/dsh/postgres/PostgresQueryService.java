package com.bizfty.anchon.dsh.postgres;

import com.bizfty.anchon.dsh.postgres.properties.PostgresProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * PostgreSQL 只读查询服务 + 索引建议。
 */
@Service
public class PostgresQueryService {

    private static final Logger log = LoggerFactory.getLogger(PostgresQueryService.class);

    private static final Pattern SELECT_ONLY = Pattern.compile("^\\s*(SELECT|WITH)\\s+", Pattern.CASE_INSENSITIVE);

    private final PostgresConnectionManager connManager;
    private final PostgresProperties properties;

    public PostgresQueryService(PostgresConnectionManager connManager, PostgresProperties properties) {
        this.connManager = connManager;
        this.properties = properties;
    }

    public Map<String, Object> query(String sql) {
        if (sql == null || sql.isBlank()) return failure("SQL 不能为空");
        if (sql.length() > properties.getMaxQueryLength()) return failure("SQL 超过最大长度");
        sql = sql.strip();
        if (!SELECT_ONLY.matcher(sql).find()) return failure("仅允许 SELECT / WITH 查询");
        try (Connection conn = connManager.getConnection()) {
            conn.setReadOnly(true);
            try (Statement stmt = conn.createStatement()) {
                stmt.setQueryTimeout(properties.getQueryTimeoutSeconds());
                stmt.setMaxRows(properties.getMaxRows());
                long start = System.currentTimeMillis();
                try (ResultSet rs = stmt.executeQuery(sql)) {
                    ResultSetMetaData md = rs.getMetaData();
                    int cols = md.getColumnCount();
                    List<String> columns = new ArrayList<>(cols);
                    for (int i = 1; i <= cols; i++) columns.add(md.getColumnLabel(i));
                    List<Map<String, Object>> rows = new ArrayList<>();
                    int rowCount = 0;
                    int limit = properties.getMaxRows();
                    while (rs.next() && rowCount < limit) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 0; i < columns.size(); i++) {
                            row.put(columns.get(i), rs.getObject(i + 1));
                        }
                        rows.add(row);
                        rowCount++;
                    }
                    long elapsed = System.currentTimeMillis() - start;
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("columns", columns);
                    result.put("rows", rows);
                    result.put("row_count", rows.size());
                    result.put("truncated", rowCount >= limit);
                    result.put("elapsed_ms", elapsed);
                    return result;
                }
            }
        } catch (SQLException e) {
            log.warn("Postgres query failed: {}", e.getMessage());
            return failure("PostgreSQL 查询失败: " + e.getMessage());
        }
    }

    /**
     * 基于 EXPLAIN ANALYZE 的索引建议。
     */
    public Map<String, Object> suggestIndexes(String sql) {
        if (sql == null || sql.isBlank()) return failure("SQL 不能为空");
        if (!SELECT_ONLY.matcher(sql.strip()).find()) return failure("仅允许 SELECT / WITH 查询");
        String explainSql = "EXPLAIN (ANALYZE false, FORMAT JSON) " + sql.strip();
        try (Connection conn = connManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(explainSql)) {
            StringBuilder plan = new StringBuilder();
            while (rs.next()) plan.append(rs.getString(1));
            String planStr = plan.toString();
            List<String> suggestions = new ArrayList<>();
            if (planStr.contains("Seq Scan") || planStr.contains("Full Scan")) {
                suggestions.add("检测到顺序扫描，建议添加合适的索引");
            }
            if (planStr.contains("Using only filter") || planStr.contains("Filter:")) {
                suggestions.add("WHERE 子句过滤过多，考虑添加复合索引覆盖 WHERE 条件");
            }
            if (planStr.contains("Sort Method:")) {
                suggestions.add("存在排序操作，若 ORDER BY 字段高频查询可考虑索引");
            }
            if (planStr.contains("Hash Join") || planStr.contains("Nested Loop")) {
                suggestions.add("存在 JOIN，考虑在 JOIN 键上建立索引以避免嵌套循环");
            }
            if (suggestions.isEmpty()) {
                suggestions.add("未检测到明显瓶颈，查询计划看起来健康");
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("plan", planStr);
            result.put("suggestions", suggestions);
            result.put("sql", sql);
            return result;
        } catch (SQLException e) {
            log.warn("Postgres index suggestion failed: {}", e.getMessage());
            return failure("索引建议失败: " + e.getMessage());
        }
    }

    /**
     * 查询表结构。
     */
    public Map<String, Object> describe(String schema, String table) {
        if (table == null || table.isBlank()) return failure("缺少参数 table");
        if (!isSafeIdentifier(table)) return failure("非法表名: " + table);
        String s = (schema == null || schema.isBlank()) ? "public" : schema;
        if (!isSafeIdentifier(s)) return failure("非法 schema: " + s);
        String sql = "SELECT column_name, data_type, is_nullable, column_default " +
                "FROM information_schema.columns WHERE table_schema = '" + s + "' AND table_name = '" + table + "' ORDER BY ordinal_position";
        return query(sql);
    }

    private boolean isSafeIdentifier(String s) {
        return s != null && s.matches("^[a-zA-Z_][a-zA-Z0-9_]{0,63}$");
    }

    private Map<String, Object> failure(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", message);
        m.put("ok", false);
        return m;
    }
}