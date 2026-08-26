package com.bizfty.anchon.dsh.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

@Service
public class SkillService {

    private static final Logger log = LoggerFactory.getLogger(SkillService.class);

    private final Map<String, Skill> skills = new ConcurrentHashMap<>();
    private final String skillsLocation;
    private final PathMatchingResourcePatternResolver resourceResolver;
    /** 上次扫描的文件系统指纹（SKILL.md 绝对路径 → 修改时间+大小），热加载变更检测用。 */
    private volatile Map<String, FileStamp> lastStamps = Map.of();

    public SkillService(@Value("${copilot.skills.directory:classpath*:/skills}") String skillsDirectory) {
        this.skillsLocation = skillsDirectory;
        this.resourceResolver = new PathMatchingResourcePatternResolver();
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

        if (isClasspathPattern(skillsLocation)) {
            loadFromClasspath(found, skillsLocation);
        } else {
            loadFromFileSystem(found, Paths.get(skillsLocation));
        }

        Path custom = Paths.get("./skills");
        if (Files.isDirectory(custom)) {
            loadFromFileSystem(found, custom);
        }

        skills.clear();
        skills.putAll(found);
        lastStamps = stampTree();
        log.info("[Skills] 加载 {} 个技能: {}", skills.size(), skills.keySet());
    }

    /**
     * 热加载轮询：定时扫描文件系统技能目录，指纹变化时自动 reload。
     * 间隔可配（{@code copilot.skills.refresh-ms}，默认 3000ms）。
     * classpath 内置技能在应用运行期不变，不参与指纹检测。
     */
    @Scheduled(fixedDelayString = "${copilot.skills.refresh-ms:3000}")
    public void refreshIfChanged() {
        try {
            Map<String, FileStamp> current = stampTree();
            if (!current.equals(lastStamps)) {
                log.info("[Skills] 检测到目录变化（{} 个指纹 → {} 个），热加载", lastStamps.size(), current.size());
                reload();
            }
        } catch (RuntimeException e) {
            log.warn("[Skills] 热加载扫描失败: {}", e.getMessage());
        }
    }

    /** 扫描文件系统技能目录，返回 SKILL.md 路径 → 指纹 的快照（新增/删除/修改都可识别）。 */
    private Map<String, FileStamp> stampTree() {
        Map<String, FileStamp> stamps = new LinkedHashMap<>();
        if (!isClasspathPattern(skillsLocation)) {
            collectStamps(Paths.get(skillsLocation), stamps);
        }
        Path custom = Paths.get("./skills");
        if (Files.isDirectory(custom)) {
            collectStamps(custom, stamps);
        }
        return stamps;
    }

    private void collectStamps(Path root, Map<String, FileStamp> stamps) {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals("SKILL.md"))
                    .forEach(p -> {
                        try {
                            BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class);
                            stamps.put(p.toAbsolutePath().normalize().toString(),
                                    new FileStamp(attrs.lastModifiedTime().toMillis(), attrs.size()));
                        } catch (IOException e) {
                            log.warn("[Skills] 指纹读取失败: {} — {}", p, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.warn("[Skills] 指纹扫描失败: {} — {}", root, e.getMessage());
        }
    }

    /** 文件指纹（最后修改时间毫秒 + 字节大小）。 */
    private record FileStamp(long lastModifiedMillis, long size) {
    }

    private boolean isClasspathPattern(String location) {
        return location.startsWith("classpath:") || location.startsWith("classpath*:");
    }

    private void loadFromClasspath(Map<String, Skill> found, String location) {
        String pattern = buildClasspathPattern(location);
        try {
            Resource[] resources = resourceResolver.getResources(pattern);
            for (Resource resource : resources) {
                if (resource.isReadable()) {
                    try {
                        Skill skill = parseSkillFromResource(resource);
                        if (skill != null) {
                            found.put(skill.name(), skill);
                        }
                    } catch (IOException e) {
                        log.warn("[Skills] 解析失败: {} — {}", resource.getDescription(), e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            log.warn("[Skills] classpath 扫描失败: {} — {}", pattern, e.getMessage());
        }
    }

    private String buildClasspathPattern(String location) {
        String base = location.endsWith("/") ? location.substring(0, location.length() - 1) : location;
        return base + "/**/SKILL.md";
    }

    private void loadFromFileSystem(Map<String, Skill> found, Path root) {
        if (!Files.isDirectory(root)) {
            return;
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
            log.warn("[Skills] 扫描失败: {} — {}", root, e.getMessage());
        }
    }

    private Skill parseSkill(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        return parseSkillText(text, file.getParent() == null ? "" : file.getParent().toString());
    }

    private Skill parseSkillFromResource(Resource resource) throws IOException {
        String text;
        try (InputStream is = resource.getInputStream()) {
            text = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
        String basePath = resolveBasePath(resource);
        return parseSkillText(text, basePath);
    }

    private Skill parseSkillText(String text, String basePath) {
        if (!text.startsWith("---")) {
            return null;
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
            name = extractSkillNameFromPath(basePath);
        }
        String description = meta.get("description") == null ? "" : String.valueOf(meta.get("description"));
        return new Skill(name, description, basePath, meta, body);
    }

    private String resolveBasePath(Resource resource) {
        try {
            URI uri = resource.getURI();
            String path = uri.getPath();
            int idx = path.lastIndexOf("/SKILL.md");
            if (idx >= 0) {
                return path.substring(0, idx);
            }
            return path;
        } catch (IOException e) {
            return resource.getDescription();
        }
    }

    private String extractSkillNameFromPath(String basePath) {
        int lastSep = Math.max(basePath.lastIndexOf('/'), basePath.lastIndexOf('\\'));
        if (lastSep >= 0 && lastSep < basePath.length() - 1) {
            return basePath.substring(lastSep + 1);
        }
        return basePath;
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
}