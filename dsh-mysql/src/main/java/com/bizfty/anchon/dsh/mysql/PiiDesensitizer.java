package com.bizfty.anchon.dsh.mysql;

import com.bizfty.anchon.dsh.mysql.properties.MysqlProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PII 脱敏器 — 对常见敏感字段进行遮掩。
 */
@Component
public class PiiDesensitizer {

    private static final Logger log = LoggerFactory.getLogger(PiiDesensitizer.class);

    private static final List<Rule> DEFAULT_RULES = List.of(
            new Rule("phone", Pattern.compile("^1[3-9]\\d{9}$"), "****${last4}", 4),
            new Rule("email", Pattern.compile("^[\\w.+-]+@[\\w-]+\\.[\\w.-]+$"), "***@${domain}", -1),
            new Rule("id_card", Pattern.compile("^\\d{17}[\\dXx]$"), "***********${last4}", 4),
            new Rule("bank_card", Pattern.compile("^\\d{16,19}$"), "****-****-****-${last4}", 4),
            new Rule("password", null, "******", -1),
            new Rule("token", null, "****", -1),
            new Rule("secret", null, "****", -1),
            new Rule("ssn", Pattern.compile("^\\d{3}-\\d{2}-\\d{4}$"), "***-**-****", -1)
    );

    public String desensitize(String columnName, Object value) {
        if (value == null) return null;
        String v = String.valueOf(value);
        if (v.isBlank()) return v;
        String lower = columnName == null ? "" : columnName.toLowerCase();
        for (Rule rule : DEFAULT_RULES) {
            if (matchesColumn(lower, rule)) {
                return applyRule(rule, v);
            }
        }
        return v;
    }

    public Map<String, Object> desensitizeRow(Map<String, Object> row) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Object> e : row.entrySet()) {
            out.put(e.getKey(), desensitize(e.getKey(), e.getValue()));
        }
        return out;
    }

    public List<Map<String, Object>> desensitizeRows(List<Map<String, Object>> rows) {
        return rows.stream().map(this::desensitizeRow).toList();
    }

    private boolean matchesColumn(String lower, Rule rule) {
        if (lower == null) return false;
        return lower.contains(rule.name);
    }

    private String applyRule(Rule rule, String value) {
        if (rule.name.equals("email")) {
            int at = value.indexOf('@');
            if (at < 0) return "***";
            return "***" + value.substring(at);
        }
        if (rule.name.equals("phone")) {
            if (value.length() <= 4) return "****";
            return "****" + value.substring(value.length() - 4);
        }
        if (rule.name.equals("id_card") || rule.name.equals("bank_card")) {
            if (value.length() <= 4) return "****";
            int start = Math.max(0, value.length() - 4);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < start; i++) sb.append('*');
            sb.append(value, start, value.length());
            return sb.toString();
        }
        return rule.replacement;
    }

    private record Rule(String name, Pattern pattern, String replacement, int lastKeep) {
    }
}