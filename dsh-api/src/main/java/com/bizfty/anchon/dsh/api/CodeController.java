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
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 在线代码开发（coder 场景）API — 对应 javaai 的 CodeController 轻量实现。
 * <p>
 * 设计取舍：不引入 JPA/版本库（dsh 定位轻量自包含），项目 = 根目录下的子目录，
 * 文件 = 项目内普通文件。所有路径经 FsPathPolicy 沙箱校验（防目录穿越）。
 *
 * <ul>
 *   <li>GET    /api/code/projects                 列出项目</li>
 *   <li>POST   /api/code/projects                 创建项目 {name}</li>
 *   <li>GET    /api/code/projects/{name}/tree     文件树（递归，排除构建产物）</li>
 *   <li>GET    /api/code/files?project=&amp;path=  读文件</li>
 *   <li>PUT    /api/code/files                    保存文件 {project, path, content}</li>
 *   <li>POST   /api/code/files                    新建文件 {project, path}</li>
 *   <li>DELETE /api/code/files?project=&amp;path=  删除文件</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/code")
public class CodeController {

    private static final long MAX_FILE_BYTES = 2 * 1024 * 1024;   // 单文件 2MB
    private static final int MAX_TREE_DEPTH = 6;

    /** 构建产物/隐藏目录（文件树不展示）。 */
    private static final Set<String> EXCLUDED_DIRS = Set.of(
            ".git", "target", "node_modules", "dist", "build",
            ".idea", ".history", "backup", "develop", "data", ".dsh-identity",
            "__pycache__", ".venv", "venv", ".next", "out"
    );

    @Value("${dsh.coder.root:data/coder-workspace}")
    private String rootProp;

    private Path root() {
        return FsPathPolicy.normalize(rootProp, null);
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

    /** project + relPath → 沙箱内绝对路径（越界抛异常）。 */
    private Path resolve(String project, String relPath) throws IOException {
        String p = checkProjectName(project);
        Path root = root();
        Path projDir = root.resolve(p).normalize().toAbsolutePath();
        String rel = relPath == null ? "" : relPath;
        Path target = projDir.resolve(rel).normalize().toAbsolutePath();
        if (!target.startsWith(projDir)) {
            throw new IllegalArgumentException("路径越界: " + relPath);
        }
        Files.createDirectories(projDir); // 项目目录不存在时自动创建（写场景）
        return target;
    }

    private static Map<String, Object> err(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", msg);
        return m;
    }

    // ---- 项目管理 ----

    /** 列出根目录下的项目（子目录），附带文件数。 */
    @GetMapping("/projects")
    public List<Map<String, Object>> listProjects() throws IOException {
        Path root = root();
        Files.createDirectories(root);
        List<Map<String, Object>> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(root)) {
            for (Path p : s.sorted(Comparator.comparing(a -> a.getFileName().toString())).toList()) {
                if (!Files.isDirectory(p)) continue;
                String name = p.getFileName().toString();
                if (name.startsWith(".")) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", name);
                m.put("fileCount", countFiles(p, 0));
                out.add(m);
            }
        }
        return out;
    }

    /** 创建项目（根目录下建子目录）。 */
    @PostMapping("/projects")
    public ResponseEntity<Map<String, Object>> createProject(@RequestBody Map<String, String> req) {
        String name = checkProjectName(req.get("name"));
        try {
            Path dir = resolve(name, "");
            Files.createDirectories(dir);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("path", dir.toString());
            return ResponseEntity.ok(m);
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(err("创建失败: " + e.getMessage()));
        }
    }

    /** 项目文件树（递归，排除构建产物，限深度）。 */
    @GetMapping("/projects/{name}/tree")
    public ResponseEntity<Map<String, Object>> getTree(@PathVariable String name) {
        try {
            Path dir = resolve(name, "");
            if (!Files.isDirectory(dir)) {
                return ResponseEntity.badRequest().body(err("项目不存在: " + name));
            }
            Map<String, Object> rootNode = new LinkedHashMap<>();
            rootNode.put("name", dir.getFileName().toString());
            rootNode.put("path", "");
            rootNode.put("type", "dir");
            rootNode.put("children", buildTree(dir, 0));
            return ResponseEntity.ok(rootNode);
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(err("扫描失败: " + e.getMessage()));
        }
    }

    private List<Map<String, Object>> buildTree(Path dir, int depth) throws IOException {
        if (depth >= MAX_TREE_DEPTH) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.sorted(Comparator.comparing(a -> a.getFileName().toString())).toList()) {
                String name = p.getFileName().toString();
                boolean isDir = Files.isDirectory(p);
                if (isDir && (name.startsWith(".") || EXCLUDED_DIRS.contains(name))) continue;
                Map<String, Object> node = new LinkedHashMap<>();
                node.put("name", name);
                node.put("path", relPath(dir, p));
                node.put("type", isDir ? "dir" : "file");
                if (isDir) {
                    node.put("children", buildTree(p, depth + 1));
                } else {
                    node.put("size", safeSize(p));
                }
                out.add(node);
            }
        }
        return out;
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
    public Map<String, Object> readFile(@RequestParam String project, @RequestParam String path) {
        try {
            Path f = resolve(project, path);
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
    public ResponseEntity<Map<String, Object>> saveFile(@RequestBody Map<String, Object> req) {
        String project = (String) req.get("project");
        String path = (String) req.get("path");
        Object contentObj = req.get("content");
        String content = contentObj == null ? "" : contentObj.toString();
        try {
            if (content.getBytes(StandardCharsets.UTF_8).length > MAX_FILE_BYTES) {
                return ResponseEntity.badRequest().body(err("内容过大（>2MB）"));
            }
            Path f = resolve(project, path);
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
    public ResponseEntity<Map<String, Object>> createFile(@RequestBody Map<String, String> req) {
        String project = req.get("project");
        String path = req.get("path");
        try {
            Path f = resolve(project, path);
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
    public ResponseEntity<Map<String, Object>> deleteFile(@RequestParam String project, @RequestParam String path) {
        try {
            Path f = resolve(project, path);
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
