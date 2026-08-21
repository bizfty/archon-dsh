package com.example.dsh.skill;

import java.util.Map;

/**
 * 技能 — 一个 SKILL.md（frontmatter + 正文）。
 */
public record Skill(
        String name,
        String description,
        String basePath,
        Map<String, Object> frontMatter,
        String body) {

    public String tools() {
        Object tools = frontMatter.get("tools");
        return tools == null ? "" : String.valueOf(tools);
    }
}
