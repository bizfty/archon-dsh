package com.bizfty.anchon.dsh.skill;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 classpath 内置技能（src/test/resources/skills/**）能被 SkillService 扫描到，
 * 不依赖启动时的工作目录（回归：dsh-boot/src/main/resources/skills 下技能启动未加载的问题）。
 */
class SkillServiceClasspathTest {

    /** 构造一个指向 classpath 技能目录的服务（新仓库实现：classpath*:/skills 模式）。 */
    private SkillService newService() {
        return new SkillService("classpath*:/skills");
    }

    @Test
    void loadsClasspathSkills() {
        SkillService service = newService();

        assertTrue(service.has("classpath-demo"),
                "应能从 classpath 加载 src/test/resources/skills/classpath-demo/SKILL.md，实际: " + service.list());
    }

    @Test
    void classpathSkillBodyIsRead() {
        SkillService service = newService();
        Skill skill = service.get("classpath-demo");
        assertNotNull(skill);
        assertEquals("classpath 示例技能", skill.description());
        assertTrue(skill.body().contains("示例正文"));
    }
}
