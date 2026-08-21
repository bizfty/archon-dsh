package com.example.dsh.mysql;

import com.example.dsh.mysql.properties.MysqlProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
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
 * MySQL 只读查询服务 — 强制 SELECT-only，带白名单与超时。
 */
@Service
public class MysqlQueryService {

    private static final Logger log = LoggerFactory.getLogger(MysqlQueryService.class);

    private static final Pattern SELECT_ONLY = Pattern.compile("^\\s*SELECT\\s+", Pattern.CASE_INSENSITIVE);
    private static final Pattern WITH_SELECT = Pattern.compile("^\\s*WITH\\s+.*SELECT\\s+", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private final MysqlConnectionManager connManager;
    private final MysqlProperties properties;
    private final PiiDesensitizer desensitizer;

    public MysqlQueryService(MysqlConnectionManager connManager, MysqlProperties properties,
                             PiiDesensitizer desensitizer) {
        this.connManager = connManager;
        this.properties = properties;
        this.desensitizer = desensitizer;
    }

    /**
     * 执行只读 SQL 查询，返回最多 maxRows 行的结果集。
     */
    public Map<String, Object> query(String sql, boolean desensitize) {
        if (sql == null || sql.isBlank()) {
            return failure("SQL 不能为空");
        }
        if (sql.length() > properties.getMaxQueryLength()) {
            return failure("SQL 超过最大长度 " + properties.getMaxQueryLength());
        }
        sql = sql.strip();
        if (!isReadOnly(sql)) {
            return failure("仅允许 SELECT / WITH ... SELECT 查询");
        }
        String allowed = properties.getAllowedDatabases();
        if (allowed != null && !allowed.isBlank()) {
            validateDatabaseAccess(sql, List.of(allowed.split(",")));
        }
        try (Connection conn = connManager.getConnection()) {
            conn.setReadOnly(true);
            int timeout = properties.getQueryTimeoutSeconds();
            try (Statement stmt = conn.createStatement()) {
                stmt.setQueryTimeout(timeout);
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
                            Object val = rs.getObject(i + 1);
                            row.put(columns.get(i), val);
                        }
                        rows.add(row);
                        rowCount++;
                    }
                    boolean truncated = rowCount >= limit;
                    if (desensitize && properties.isPiiDesensitize()) {
                        rows = desensitizer.desensitizeRows(rows);
                    }
                    long elapsed = System.currentTimeMillis() - start;
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("columns", columns);
                    result.put("rows", rows);
                    result.put("row_count", rows.size());
                    result.put("truncated", truncated);
                    result.put("elapsed_ms", elapsed);
                    result.put("sql", sql);
                    return result;
                }
            }
        } catch (SQLException e) {
            log.warn("MySQL query failed: {}", e.getMessage());
            return failure("MySQL 查询失败: " + e.getMessage());
        }
    }

    /**
     * 获取表结构。
     */
    public Map<String, Object> describe(String table) {
        if (table == null || table.isBlank()) return failure("缺少参数 table");
        if (!isSafeIdentifier(table)) return failure("非法表名: " + table);
        String sql = "DESCRIBE " + table;
        return query(sql, false);
    }

    private boolean isReadOnly(String sql) {
        return SELECT_ONLY.matcher(sql).find() || WITH_SELECT.matcher(sql).find();
    }

    private boolean isSafeIdentifier(String s) {
        return s != null && s.matches("^[a-zA-Z_][a-zA-Z0-9_]{0,63}$");
    }

    private void validateDatabaseAccess(String sql, List<String> allowed) {
        String lower = sql.toLowerCase();
        for (String db : allowed) {
            if (lower.contains("`" + db.trim() + "`.") || lower.contains(db.trim() + ".")) {
                return;
            }
        }
    }

    private Map<String, Object> failure(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", message);
        m.put("ok", false);
        return m;
    }
}