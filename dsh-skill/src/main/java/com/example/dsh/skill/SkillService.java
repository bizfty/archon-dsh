package com.example.dsh.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 技能服务 — 从目录加载 SKILL.md（对应 DSH skill/skill-filesystem）。
 * <p>
 * 扫描 {@code copilot.skills.directory}（默认 ./skills）+ classpath 的 skills 目录
 * 与工作区 ./skills；frontmatter 用 snakeyaml 解析。
 */
@Service
public class SkillService {

    private static final Logger log = LoggerFactory.getLogger(SkillService.class);

    private final Map<String, Skill> skills = new ConcurrentHashMap<>();
    private final List<String> directories;

    public SkillService(@Value("${copilot.skills.directory:./skills}") String skillsDirectory) {
        this.directories = resolveDirectories(skillsDirectory);
        reload();
    }

    public List<Skill> list() {
        return List.copyOf(skills.values());
    }

    public Skill get(String name) {
        return skills.get(name);
    }

    public boolean has(String name) {
        return skills.containsKey(name);
    }

    public void reload() {
        Map<String, Skill> found = new LinkedHashMap<>();
        for (String dir : directories) {
            Path root = Paths.get(dir);
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().equals("SKILL.md"))
                        .forEach(p -> {
                            try {
                                Skill skill = parseSkill(p);
                                if (skill != null) {
                                    found.put(skill.name(), skill);
                                }
                            } catch (IOException e) {
                                log.warn("[Skills] 解析失败: {} — {}", p, e.getMessage());
                            }
                        });
            } catch (IOException e) {
                log.warn("[Skills] 扫描失败: {} — {}", dir, e.getMessage());
            }
        }
        skills.clear();
        skills.putAll(found);
        log.info("[Skills] 加载 {} 个技能: {}", skills.size(), skills.keySet());
    }

    private Skill parseSkill(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        if (!text.startsWith("---")) {
            return null; // 非 frontmatter 文件跳过
        }
        int end = text.indexOf("---", 3);
        if (end < 0) {
            return null;
        }
        String front = text.substring(3, end).trim();
        String body = text.substring(end + 3).trim();
        Map<String, Object> meta = parseFrontMatter(front);
        String name = meta.get("name") == null ? null : String.valueOf(meta.get("name"));
        if (name == null || name.isBlank()) {
            name = file.getParent() == null ? file.getFileName().toString() : file.getParent().getFileName().toString();
        }
        String description = meta.get("description") == null ? "" : String.valueOf(meta.get("description"));
        return new Skill(name, description, file.getParent() == null ? "" : file.getParent().toString(), meta, body);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseFrontMatter(String front) {
        try {
            Object parsed = new Yaml().load(front);
            if (parsed instanceof Map) {
                return (Map<String, Object>) parsed;
            }
        } catch (RuntimeException e) {
            log.warn("[Skills] frontmatter YAML 解析失败: {}", e.getMessage());
        }
        return Map.of();
    }

    private List<String> resolveDirectories(String configured) {
        List<String> dirs = new ArrayList<>();
        if (configured != null && !configured.isBlank()) {
            if (configured.startsWith("classpath:")) {
                String path = configured.substring("classpath:".length());
                Path external = Paths.get("src/main/resources" + path);
                if (Files.isDirectory(external)) {
                    dirs.add(external.toString());
                }
                Path target = Paths.get("target/classes" + path);
                if (Files.isDirectory(target)) {
                    dirs.add(target.toString());
                }
            } else {
                dirs.add(configured);
            }
        }
        Path custom = Paths.get("./skills");
        if (Files.isDirectory(custom)) {
            dirs.add(custom.toString());
        }
        return dirs;
    }
}
