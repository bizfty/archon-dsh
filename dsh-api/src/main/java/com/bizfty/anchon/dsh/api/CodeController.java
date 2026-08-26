package com.bizfty.anchon.dsh.api;

import com.bizfty.anchon.dsh.fs.FsPathPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 在线代码开发（coder / self 场景）API。
 * <p>
 * 两种场景共用同一套接口（文件树 + 文件读写），由 {@code scene} 参数区分：
 * <ul>
 *   <li>{@code coder}（默认）：用户工作区。项目 = 根目录（{@code dsh.coder.root}，
 *       默认 {@code data/workspace/coder/project}）下的子目录，使用者在此目录下选择项目。</li>
 *   <li>{@code self}：自我完善。根目录固定为 {@code dsh.self.root}（默认应用工作目录，
 *       即 archon-dsh 源码目录），project 参数被忽略（固定为根本身），支持完整读写。</li>
 * </ul>
 * 设计取舍：不引入 JPA/版本库（dsh 定位轻量自包含），文件 = 项目内普通文件。
 * 所有路径经 FsPathPolicy 沙箱校验 + 项目内 startsWith 校验（防目录穿越）。
 *
 * <p><b>文件树展示（项目类型感知）</b>：
 * <ul>
 *   <li>自动检测项目类型（pom.xml → maven / build.gradle* → gradle / package.json → node / 其他 generic），
 *       随项目列表与树根节点返回 {@code projectType}。</li>
 *   <li>识别 Maven/Gradle 标准源码根：{@code src}、{@code src/main}、{@code src/test}、
 *       {@code src/(main|test)/&lt;lang&gt;}（java/kotlin/scala/groovy/resources…）作为
 *       <b>结构目录</b>保持可见（kind=structural / source-root），体现项目类型布局。</li>
 *   <li>源码根下的连续单子目录包链合并为一个 <b>package</b> 节点：短包名展示（name=最后一段），
 *       {@code package} 字段给完整点分路径（如 {@code com.bizfty.anchon.dsh.api}），
 *       避免深层包目录把树撑爆、Java 文件不可见。</li>
 *   <li>非源码根的普通单链目录合并为 <b>chain</b> 节点（短名 + {@code pathLabel} 完整路径）。</li>
 * </ul>
 *
 * <ul>
 *   <li>GET    /api/code/projects?scene=coder|self       列出项目</li>
 *   <li>POST   /api/code/projects?scene=coder            创建项目 {name}（self 场景拒绝）</li>
 *   <li>GET    /api/code/projects/{name}/tree?scene=…    文件树（递归，排除构建产物）</li>
 *   <li>GET    /api/code/files?project=&amp;path=&amp;scene=…   读文件</li>
 *   <li>PUT    /api/code/files?scene=…                   保存文件 {project, path, content}</li>
 *   <li>POST   /api/code/files?scene=…                   新建文件 {project, path}</li>
 *   <li>DELETE /api/code/files?project=&amp;path=&amp;scene=…  删除文件</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/code")
public class CodeController {

    /** coder 场景：用户工作区（根目录下项目列表）。 */
    public static final String SCENE_CODER = "coder";
    /** self 场景：archon-dsh 源码目录（自我完善）。 */
    public static final String SCENE_SELF = "self";
    /** self 场景文件树/读写使用的固定 project 名（project 参数被忽略）。 */
    public static final String SELF_PROJECT = ".";

    private static final long MAX_FILE_BYTES = 2 * 1024 * 1024;   // 单文件 2MB
    private static final int MAX_TREE_DEPTH = 10;

    /** 构建产物/隐藏目录（文件树不展示）。 */
    private static final Set<String> EXCLUDED_DIRS = Set.of(
            ".git", "target", "node_modules", "dist", "build",
            ".idea", ".history", "backup", "develop", "data", ".dsh-identity",
            "__pycache__", ".venv", "venv", ".next", "out"
    );

    /** 源码根语言目录：src/(main|test)/&lt;lang&gt;（java/kotlin/scala/groovy/resources…），作为结构目录保持可见。 */
    private static final Pattern SOURCE_ROOT_PATTERN = Pattern.compile("^src/(main|test)/[a-z0-9]+$");
    /** Java 源码根（包链用点分包名合并）。 */
    private static final Pattern JAVA_SOURCE_ROOT_PATTERN = Pattern.compile("^src/(main|test)/java$");

    @Value("${dsh.coder.root:data/workspace/coder/project}")
    private String coderRootProp;

    @Value("${dsh.self.root:${user.dir}}")
    private String selfRootProp;

    private Path root(String scene) {
        String rootProp = SCENE_SELF.equals(scene) ? selfRootProp : coderRootProp;
        return FsPathPolicy.normalize(rootProp, null);
    }

    private static boolean isSelf(String scene) {
        return SCENE_SELF.equals(scene);
    }

    // ---- 内部工具 ----

    /** 项目名必须为合法目录名（不含路径分隔符 / 穿越）。 */
    private static String checkProjectName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("项目名不能为空");
        }
        String n = name.trim();
        if (n.contains("/") || n.contains("\\") || n.contains("..") || n.contains(":")) {
            throw new IllegalArgumentException("项目名不合法: " + n);
        }
        return n;
    }

    /**
     * scene + project + relPath → 沙箱内绝对路径（越界抛异常）。
     * coder 场景 base = root/project；self 场景 base = root（project 忽略）。
     */
    private Path resolve(String scene, String project, String relPath) throws IOException {
        Path root = root(scene);
        boolean self = isSelf(scene);
        Path base = self ? root : root.resolve(checkProjectName(project)).normalize().toAbsolutePath();
        String rel = relPath == null ? "" : relPath;
        Path target = base.resolve(rel).normalize().toAbsolutePath();
        if (!target.startsWith(base)) {
            throw new IllegalArgumentException("路径越界: " + relPath);
        }
        if (!self) {
            Files.createDirectories(base); // coder 项目目录不存在时自动创建（写场景）
        }
        return target;
    }

    private static Map<String, Object> err(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", msg);
        return m;
    }

    /** 项目类型：maven / gradle / node / generic。 */
    private static String detectProjectType(Path dir) {
        if (Files.isRegularFile(dir.resolve("pom.xml"))) return "maven";
        if (Files.isRegularFile(dir.resolve("build.gradle")) || Files.isRegularFile(dir.resolve("build.gradle.kts"))
                || Files.isRegularFile(dir.resolve("settings.gradle")) || Files.isRegularFile(dir.resolve("settings.gradle.kts"))) {
            return "gradle";
        }
        if (Files.isRegularFile(dir.resolve("package.json"))) return "node";
        return "generic";
    }

    // ---- 结构目录 / 源码根 / 包路径识别（模式匹配，不依赖预扫描，多模块项目同样适用）----

    /** 是否为 Maven/Gradle 结构目录：src、src/main、src/test、src/(main|test)/&lt;lang&gt;。
     *  支持任意前缀（多模块 dsh-api/src/main/java 同样适用）；恰好这三级，包目录不算。 */
    private static boolean isStructuralRel(String rel) {
        return Pattern.compile("(^|/)src$").matcher(rel).find()
                || Pattern.compile("(^|/)src/(main|test)$").matcher(rel).find()
                || Pattern.compile("(^|/)src/(main|test)/[a-z0-9]+$").matcher(rel).find();
    }

    /** rel 是否为 Java 源码根本身（…/src/main/java / …/src/test/java）。 */
    private static boolean isJavaSourceRootRel(String rel) {
        return Pattern.compile("(^|/)src/(main|test)/java$").matcher(rel).find();
    }

    /** rel 是否落在 Java 源码根之下（…/src/main/java/… 或 …/src/test/java/…）。 */
    private static boolean isUnderJavaSourceRoot(String rel) {
        return Pattern.compile("(^|/)src/(main|test)/java/").matcher(rel).find();
    }

    /** 从合并路径剥离 Java 源码根前缀，得到点分包名：src/main/java/com/foo/Bar → com.foo.Bar。 */
    private static String packageOf(String rel) {
        java.util.regex.Matcher m = Pattern.compile("(^|/)src/(main|test)/java/").matcher(rel);
        if (!m.find()) return rel.replace('/', '.');
        return rel.substring(m.end()).replace('/', '.');
    }

    /** 路径最后一段（短名）。 */
    private static String lastName(String rel) {
        int i = rel.lastIndexOf('/');
        return i < 0 ? rel : rel.substring(i + 1);
    }

    // ---- 项目管理 ----

    /** 列出项目。coder：根目录下的子目录；self：返回单一根项目（源码目录本身）。 */
    @GetMapping("/projects")
    public List<Map<String, Object>> listProjects(
            @RequestParam(defaultValue = SCENE_CODER) String scene) throws IOException {
        Path root = root(scene);
        Files.createDirectories(root);
        List<Map<String, Object>> out = new ArrayList<>();
        if (isSelf(scene)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", SELF_PROJECT);
            m.put("displayName", root.getFileName().toString());
            m.put("fileCount", countFiles(root, 0));
            m.put("projectType", detectProjectType(root));
            out.add(m);
            return out;
        }
        try (Stream<Path> s = Files.list(root)) {
            for (Path p : s.sorted(Comparator.comparing(a -> a.getFileName().toString())).toList()) {
                if (!Files.isDirectory(p)) continue;
                String name = p.getFileName().toString();
                if (name.startsWith(".")) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", name);
                m.put("fileCount", countFiles(p, 0));
                m.put("projectType", detectProjectType(p));
                out.add(m);
            }
        }
        return out;
    }

    /** 创建项目（coder 根目录下建子目录；self 场景拒绝）。 */
    @PostMapping("/projects")
    public ResponseEntity<Map<String, Object>> createProject(
            @RequestBody Map<String, String> req,
            @RequestParam(defaultValue = SCENE_CODER) String scene) {
        if (isSelf(scene)) {
            return ResponseEntity.badRequest().body(err("自我完善场景不支持创建项目"));
        }
        String name = checkProjectName(req.get("name"));
        try {
            Path dir = resolve(scene, name, "");
            Files.createDirectories(dir);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("path", dir.toString());
            return ResponseEntity.ok(m);
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(err("创建失败: " + e.getMessage()));
        }
    }

    /** 项目文件树（递归，排除构建产物，源码根下包链合并为短包名节点）。self 场景 name 忽略（固定根）。 */
    @GetMapping("/projects/{name}/tree")
    public ResponseEntity<Map<String, Object>> getTree(
            @PathVariable String name,
            @RequestParam(defaultValue = SCENE_CODER) String scene) {
        try {
            Path dir = resolve(scene, name, "");
            if (!Files.isDirectory(dir)) {
                return ResponseEntity.badRequest().body(err("项目不存在: " + name));
            }
            Map<String, Object> rootNode = new LinkedHashMap<>();
            rootNode.put("name", dir.getFileName().toString());
            rootNode.put("path", "");
            rootNode.put("type", "dir");
            rootNode.put("projectType", detectProjectType(dir));
            rootNode.put("children", buildTree(dir, dir, 0));
            return ResponseEntity.ok(rootNode);
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(err("扫描失败: " + e.getMessage()));
        }
    }

    // ---- 树构建（项目类型感知 + 单链合并）----

    private List<Map<String, Object>> buildTree(Path dir, Path root, int depth) throws IOException {
        if (depth >= MAX_TREE_DEPTH) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Path d : listVisibleDirs(dir)) {
            out.add(buildDirNode(d, root, depth));
        }
        for (Path f : listVisibleFiles(dir)) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("name", f.getFileName().toString());
            node.put("path", relPath(root, f));
            node.put("type", "file");
            node.put("size", safeSize(f));
            out.add(node);
        }
        return out;
    }

    /**
     * 目录节点：结构目录 / 源码根保持单节点可见；源码根下的连续单子目录包链合并为
     * package 节点（短包名 + 完整点分路径）；普通单链目录合并为 chain 节点（短名 + 完整路径）。
     */
    private Map<String, Object> buildDirNode(Path d, Path root, int depth) throws IOException {
        String rel = relPath(root, d);
        boolean structural = isStructuralRel(rel);

        // 单链下钻：非结构目录且只有 1 个子目录、0 个文件时，与子目录合并
        List<String> chain = new ArrayList<>();
        Path cur = d;
        while (!structural) {
            List<Path> dirs = listVisibleDirs(cur);
            List<Path> files = listVisibleFiles(cur);
            if (dirs.size() == 1 && files.isEmpty()) {
                chain.add(cur.getFileName().toString());
                cur = dirs.get(0);
            } else {
                break;
            }
        }
        String mergedRel = relPath(root, cur);

        // 发生链合并，或目录本身落在 Java 源码根下（单层包目录）→ 包语义节点
        if (!chain.isEmpty() || isUnderJavaSourceRoot(mergedRel)) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("type", "dir");
            node.put("path", mergedRel);
            node.put("children", buildTree(cur, root, depth + 1));
            if (isUnderJavaSourceRoot(mergedRel)) {
                // 包节点：短包名展示（name=最后一段），package=完整点分路径
                node.put("kind", "package");
                node.put("name", lastName(mergedRel));
                node.put("package", packageOf(mergedRel));
            } else {
                node.put("kind", "chain");
                node.put("name", lastName(mergedRel));
                node.put("pathLabel", mergedRel);
            }
            return node;
        }

        // 普通节点
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("name", d.getFileName().toString());
        node.put("path", rel);
        node.put("type", "dir");
        if (isJavaSourceRootRel(rel)) {
            node.put("kind", "source-root");
        } else if (structural) {
            node.put("kind", "structural");
        } else {
            node.put("kind", "dir");
        }
        node.put("children", buildTree(d, root, depth + 1));
        return node;
    }

    private static List<Path> listVisibleDirs(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(Files::isDirectory)
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return !n.startsWith(".") && !EXCLUDED_DIRS.contains(n);
                    })
                    .sorted(Comparator.comparing(a -> a.getFileName().toString()))
                    .toList();
        }
    }

    private static List<Path> listVisibleFiles(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> !Files.isDirectory(p))
                    .sorted(Comparator.comparing(a -> a.getFileName().toString()))
                    .toList();
        }
    }

    private static String relPath(Path base, Path target) {
        return base.relativize(target).toString().replace('\\', '/');
    }

    private static long safeSize(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return -1;
        }
    }

    private long countFiles(Path dir, int depth) {
        if (depth >= MAX_TREE_DEPTH) return 0;
        long n = 0;
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.toList()) {
                String name = p.getFileName().toString();
                if (Files.isDirectory(p)) {
                    if (name.startsWith(".") || EXCLUDED_DIRS.contains(name)) continue;
                    n += countFiles(p, depth + 1);
                } else {
                    n++;
                }
            }
        } catch (IOException e) {
            return n;
        }
        return n;
    }

    // ---- 文件读写 ----

    /** 读文件（内容 + 行数）。 */
    @GetMapping("/files")
    public Map<String, Object> readFile(
            @RequestParam String project,
            @RequestParam String path,
            @RequestParam(defaultValue = SCENE_CODER) String scene) {
        try {
            Path f = resolve(scene, project, path);
            if (!Files.exists(f)) return err("文件不存在: " + path);
            if (Files.isDirectory(f)) return err("是目录: " + path);
            if (Files.size(f) > MAX_FILE_BYTES) return err("文件过大（>2MB）: " + path);
            String content = Files.readString(f, StandardCharsets.UTF_8);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", path);
            m.put("content", content);
            m.put("lines", content.lines().count());
            m.put("size", Files.size(f));
            return m;
        } catch (IOException e) {
            return err("读取失败: " + e.getMessage());
        }
    }

    /** 保存文件（覆盖写，自动建父目录）。 */
    @PutMapping("/files")
    public ResponseEntity<Map<String, Object>> saveFile(
            @RequestBody Map<String, Object> req,
            @RequestParam(defaultValue = SCENE_CODER) String scene) {
        String project = (String) req.get("project");
        String path = (String) req.get("path");
        Object contentObj = req.get("content");
        String content = contentObj == null ? "" : contentObj.toString();
        try {
            if (content.getBytes(StandardCharsets.UTF_8).length > MAX_FILE_BYTES) {
                return ResponseEntity.badRequest().body(err("内容过大（>2MB）"));
            }
            Path f = resolve(scene, project, path);
            if (Files.isDirectory(f)) return ResponseEntity.badRequest().body(err("是目录: " + path));
            if (f.getParent() != null) Files.createDirectories(f.getParent());
            Files.writeString(f, content, StandardCharsets.UTF_8);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", path);
            m.put("size", Files.size(f));
            return ResponseEntity.ok(m);
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(err("保存失败: " + e.getMessage()));
        }
    }

    /** 新建文件（不覆盖已存在文件）。 */
    @PostMapping("/files")
    public ResponseEntity<Map<String, Object>> createFile(
            @RequestBody Map<String, String> req,
            @RequestParam(defaultValue = SCENE_CODER) String scene) {
        String project = req.get("project");
        String path = req.get("path");
        try {
            Path f = resolve(scene, project, path);
            if (Files.exists(f)) return ResponseEntity.badRequest().body(err("文件已存在: " + path));
            if (f.getParent() != null) Files.createDirectories(f.getParent());
            Files.writeString(f, "", StandardCharsets.UTF_8);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", path);
            return ResponseEntity.ok(m);
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(err("创建失败: " + e.getMessage()));
        }
    }

    /** 删除文件（也支持删除空目录）。 */
    @DeleteMapping("/files")
    public ResponseEntity<Map<String, Object>> deleteFile(
            @RequestParam String project,
            @RequestParam String path,
            @RequestParam(defaultValue = SCENE_CODER) String scene) {
        try {
            Path f = resolve(scene, project, path);
            if (!Files.exists(f)) return ResponseEntity.badRequest().body(err("不存在: " + path));
            if (Files.isDirectory(f)) {
                try (Stream<Path> s = Files.list(f)) {
                    if (s.findAny().isPresent()) {
                        return ResponseEntity.badRequest().body(err("目录非空，仅允许删除空目录: " + path));
                    }
                }
                Files.delete(f);
            } else {
                Files.delete(f);
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("deleted", path);
            return ResponseEntity.ok(m);
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(err("删除失败: " + e.getMessage()));
        }
    }
}
