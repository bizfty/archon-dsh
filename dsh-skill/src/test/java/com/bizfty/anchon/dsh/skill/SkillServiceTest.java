package com.bizfty.anchon.dsh.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SkillService 热加载测试：验证目录中 SKILL.md 的新增/修改/删除会被
 * {@link SkillService#refreshIfChanged()} 检测并自动重载（无需重启）。
 */
class SkillServiceTest {

    @TempDir
    Path tempDir;

    private SkillService newService() {
        // 用临时目录作为 copilot.skills.directory（绝对路径，非 classpath）
        return new SkillService(tempDir.toString());
    }

    private void writeSkill(Path dir, String name, String description, String body) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"),
                "---\nname: " + name + "\ndescription: " + description + "\n---\n\n" + body);
    }

    @Test
    void loadsExistingSkillsAtStartup() throws Exception {
        writeSkill(tempDir.resolve("alpha"), "alpha", "技能 A", "正文 A");
        writeSkill(tempDir.resolve("beta"), "beta", "技能 B", "正文 B");

        SkillService service = newService();

        assertNotNull(service.get("alpha"));
        assertNotNull(service.get("beta"));
        assertEquals("正文 B", service.get("beta").body());
    }

    @Test
    void refreshDetectsNewSkill() throws Exception {
        SkillService service = newService();
        assertTrue(!service.has("new-skill"));

        // 新增一个技能后，指纹变化 → refreshIfChanged 自动加载
        writeSkill(tempDir.resolve("new-skill"), "new-skill", "新技能", "正文");
        service.refreshIfChanged();

        assertNotNull(service.get("new-skill"));
    }

    @Test
    void refreshDetectsModifiedSkill() throws Exception {
        writeSkill(tempDir.resolve("edit-me"), "edit-me", "原始描述", "原始正文");
        SkillService service = newService();
        assertEquals("原始正文", service.get("edit-me").body());

        // 修改正文（内容更长，指纹变化）→ 自动重载为新正文
        writeSkill(tempDir.resolve("edit-me"), "edit-me", "原始描述",
                "修改后的正文内容，比原来更长以改变 size");
        service.refreshIfChanged();

        assertEquals("修改后的正文内容，比原来更长以改变 size", service.get("edit-me").body());
    }

    @Test
    void refreshDetectsDeletedSkill() throws Exception {
        writeSkill(tempDir.resolve("doomed"), "doomed", "将被删除", "正文");
        SkillService service = newService();
        assertNotNull(service.get("doomed"));

        Files.delete(tempDir.resolve("doomed").resolve("SKILL.md"));
        Files.delete(tempDir.resolve("doomed"));
        service.refreshIfChanged();

        assertNull(service.get("doomed"));
    }

    @Test
    void refreshWithoutChangesKeepsSkills() throws Exception {
        writeSkill(tempDir.resolve("stable"), "stable", "稳定技能", "正文");
        SkillService service = newService();

        // 无变化时刷新不丢技能
        service.refreshIfChanged();
        service.refreshIfChanged();

        assertNotNull(service.get("stable"));
    }

    @Test
    void reloadIsIdempotent() throws Exception {
        writeSkill(tempDir.resolve("one"), "one", "技能一", "正文");
        SkillService service = newService();

        service.reload();
        assertNotNull(service.get("one"));
    }

    @Test
    void nonFrontmatterFileIsSkipped() throws Exception {
        Files.createDirectories(tempDir.resolve("plain"));
        Files.writeString(tempDir.resolve("plain").resolve("SKILL.md"), "# 没有 frontmatter 的文件");

        SkillService service = newService();
        assertFalse(service.has("plain"));
    }
}
