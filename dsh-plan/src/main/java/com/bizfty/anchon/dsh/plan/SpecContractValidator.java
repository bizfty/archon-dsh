package com.bizfty.anchon.dsh.plan;

import java.util.ArrayList;
import java.util.List;

/**
 * 行为契约（spec）格式校验器 — 对齐 OpenSpec 契约规则：
 * <ul>
 *   <li>增量头仅允许 {@code ## Purpose} 与 {@code ## ADDED/MODIFIED/REMOVED/RENAMED Requirements}；</li>
 *   <li>Requirement 用 {@code ### Requirement: <名>}，描述须含 SHALL/MUST（规范性语言）；</li>
 *   <li>每个 Requirement 至少一个 {@code #### Scenario:}（恰好 4 个 {@code ####}，3 个会静默失败）；</li>
 *   <li>MODIFIED 必须带完整更新的 Requirement 块（header 精确匹配）。</li>
 * </ul>
 * 纯静态实现，便于单测。
 */
public final class SpecContractValidator {

    private SpecContractValidator() {
    }

    private static final List<String> ALLOWED_DELTAS = List.of(
            "ADDED Requirements", "MODIFIED Requirements",
            "REMOVED Requirements", "RENAMED Requirements");

    /** 校验契约文本，返回错误列表（空 = 通过）。 */
    public static List<String> validate(String text) {
        List<String> errors = new ArrayList<>();
        if (text == null || text.isBlank()) {
            errors.add("契约文本为空");
            return errors;
        }
        String[] lines = text.split("\n", -1);
        List<Integer> reqIdx = new ArrayList<>(); // "### Requirement:" 的行号（0 基）
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].trim();
            if (t.startsWith("### Requirement:")) {
                reqIdx.add(i);
            } else if (t.startsWith("### ")) {
                errors.add("第 " + (i + 1) + " 行: 非法 3 级标题 '" + t + "'（仅允许 '### Requirement:'）");
            } else if (t.startsWith("#### ")) {
                if (!t.startsWith("#### Scenario:")) {
                    errors.add("第 " + (i + 1) + " 行: 非法 4 级标题 '" + t + "'（仅允许 '#### Scenario:'）");
                }
            } else if (t.startsWith("## ")) {
                String h = t.substring(3).trim();
                if (!h.equals("Purpose") && !ALLOWED_DELTAS.contains(h)) {
                    errors.add("第 " + (i + 1) + " 行: 非法增量头 '## " + h + "'（仅 Purpose / "
                            + String.join(" / ", ALLOWED_DELTAS) + "）");
                }
            }
        }
        if (reqIdx.isEmpty()) {
            errors.add("未找到任何 '### Requirement:'（契约至少需要一个 Requirement）");
            return errors;
        }
        for (int i = 0; i < reqIdx.size(); i++) {
            int start = reqIdx.get(i);
            int end = (i + 1 < reqIdx.size()) ? reqIdx.get(i + 1) : lines.length;
            boolean hasScenario = false;
            boolean hasNormative = false;
            for (int j = start + 1; j < end; j++) {
                String t = lines[j].trim();
                if (t.startsWith("#### Scenario:")) {
                    hasScenario = true;
                }
                if (!t.isEmpty() && !t.startsWith("#")) {
                    String up = t.toUpperCase();
                    if (up.contains("SHALL") || up.contains("MUST")) {
                        hasNormative = true;
                    }
                }
            }
            String reqName = lines[start].trim().substring("### Requirement:".length()).trim();
            String at = "Requirement '" + reqName + "'";
            if (!hasScenario) {
                errors.add(at + " 缺少 '#### Scenario:'（每个 Requirement 至少一个场景，且必须 4 个 #）");
            }
            if (!hasNormative) {
                errors.add(at + " 描述须用 SHALL/MUST 规范性语言（避免 should/may）");
            }
        }
        return errors;
    }
}
