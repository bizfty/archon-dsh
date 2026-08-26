package com.bizfty.anchon.dsh.api;

import com.bizfty.anchon.dsh.core.model.MessageRole;
import com.bizfty.anchon.dsh.core.model.SessionId;
import com.bizfty.anchon.dsh.session.SessionService;
import com.bizfty.anchon.dsh.skill.Skill;
import com.bizfty.anchon.dsh.skill.SkillService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/skills")
public class SkillController {

    private final SkillService skillService;
    private final SessionService sessionService;

    public SkillController(SkillService skillService, SessionService sessionService) {
        this.skillService = skillService;
        this.sessionService = sessionService;
    }

    @GetMapping
    public List<SkillSummary> listSkills() {
        return skillService.list().stream()
                .map(s -> new SkillSummary(s.name(), s.description(), s.tools()))
                .toList();
    }

    /** 手动热加载：重新扫描全部技能目录（文件系统技能新增/修改/删除即时生效）。 */
    @PostMapping("/reload")
    public Map<String, Object> reload() {
        skillService.reload();
        List<SkillSummary> skills = skillService.list().stream()
                .map(s -> new SkillSummary(s.name(), s.description(), s.tools()))
                .toList();
        return Map.of("reloaded", true, "count", skills.size());
    }

    @GetMapping("/{name}")
    public ResponseEntity<SkillDetail> getSkill(@PathVariable String name) {
        Skill skill = skillService.get(name);
        if (skill == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(new SkillDetail(
                skill.name(), skill.description(), skill.tools(), skill.basePath(),
                skill.body()));
    }

    @PostMapping("/{name}/execute")
    public ResponseEntity<Map<String, Object>> executeSkill(
            @PathVariable String name,
            @RequestBody(required = false) ExecuteRequest request) {
        Skill skill = skillService.get(name);
        if (skill == null) {
            return ResponseEntity.notFound().build();
        }
        String sessionId = request == null ? null : request.sessionId();
        if (sessionId == null || sessionId.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "sessionId 不能为空"));
        }
        String userMessage = request == null ? null : request.userMessage();
        if (userMessage == null || userMessage.isBlank()) {
            userMessage = "请按 " + skill.name() + " 技能的指令执行任务。";
        }

        SessionId id = SessionId.of(sessionId);
        String systemHint = """
                [技能：%s]
                %s
                请按以上技能指令执行用户请求。
                """.formatted(skill.name(), skill.body());
        sessionService.append(id, MessageRole.SYSTEM, systemHint, null, null, null);
        sessionService.append(id, MessageRole.USER, userMessage, null, null, null);

        return ResponseEntity.ok(Map.of(
                "skill", name,
                "systemMessage", systemHint,
                "userMessage", userMessage,
                "status", "injected"
        ));
    }

    public record SkillSummary(String name, String description, String tools) {
    }

    public record SkillDetail(
            String name, String description, String tools,
            String basePath, String body) {
    }

    public record ExecuteRequest(String sessionId, String userMessage) {
    }
}
