package com.bizfty.anchon.dsh;

import com.bizfty.anchon.dsh.skill.SkillService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归测试：dsh-boot/src/main/resources/skills 下的内置技能必须在启动时被加载
 * （classpath 技能扫描，不依赖启动时的工作目录）。
 */
@SpringBootTest
class BuiltinSkillsLoadedTest {

    @Autowired
    private SkillService skillService;

    @Test
    void builtinClasspathSkillIsLoaded() {
        System.out.println("LOADED SKILLS: " + skillService.list().stream()
                .map(s -> s.name() + " (" + s.basePath() + ")").toList());
        assertTrue(skillService.has("demo-plan-review"),
                "内置技能 demo-plan-review 应通过 classpath 扫描加载");
        assertNotNull(skillService.get("demo-plan-review").body());
    }
}
