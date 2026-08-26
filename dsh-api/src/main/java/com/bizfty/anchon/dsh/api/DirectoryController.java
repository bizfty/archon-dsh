package com.bizfty.anchon.dsh.api;

import com.bizfty.anchon.dsh.session.WorkspaceProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 目录浏览 API — 对齐官方 directory-picker browse 能力（in-app 目录树浏览）。
 * <p>
 * 只列<b>子目录</b>（含指向目录的符号链接），name-sorted；面包屑（crumbs）
 * 从文件系统根到当前目录逐级可跳。缺省 path = 固定工作区根
 * （{@code dsh.workspace.root}，默认 /data/anchon/workspace）；roots 端点
 * 暴露部署布局的固定根（工作区根 + 代码根 + home），供前端目录浏览器做快捷入口。
 * 浏览失败（不可读、非目录）以 400 返回错误，由前端对话框内提示。
 */
@RestController
@RequestMapping("/api/dirs")
public class DirectoryController {

    /** 单层完整结果上限；超出截断尾部并置 truncated=true（对齐官方 complete-result bound）。 */
    private static final int MAX_ENTRIES = 1000;

    private final WorkspaceProperties workspaceProperties;

    public DirectoryController(WorkspaceProperties workspaceProperties) {
        this.workspaceProperties = workspaceProperties;
    }

    /** 部署布局固定根：workspaceRoot（固定工作区根）/ codeRoot（应用源码根）/ home。 */
    @GetMapping("/roots")
    public Map<String, Object> roots() {
        return Map.of(
                "workspaceRoot", workspaceProperties.getRoot(),
                "codeRoot", workspaceProperties.getCodeRoot(),
                "home", System.getProperty("user.home"));
    }

    /** 列出一层目录（缺省 path = 固定工作区根，不可用时回退 home）。 */
    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String path) {
        String home = System.getProperty("user.home");
        String fallback = isDir(workspaceProperties.getRoot()) ? workspaceProperties.getRoot() : home;
        String target = (path == null || path.isBlank()) ? fallback : path;
        Path dir = Paths.get(target).toAbsolutePath().normalize();
        if (!Files.exists(dir)) {
            return err("目录不存在: " + dir);
        }
        if (!Files.isDirectory(dir)) {
            return err("不是目录: " + dir);
        }

        List<Map<String, Object>> entries = new ArrayList<>();
        boolean truncated = false;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path child : stream) {
                if (!Files.isDirectory(child)) {
                    continue; // 只列目录（官方语义：entries = 直接子目录）
                }
                entries.add(entry(child.getFileName().toString(), child.toString()));
                if (entries.size() > MAX_ENTRIES) {
                    truncated = true;
                    entries.subList(MAX_ENTRIES, entries.size()).clear();
                    break;
                }
            }
        } catch (IOException e) {
            return err("读取目录失败: " + e.getMessage());
        }
        entries.sort(Comparator.comparing(m -> (String) m.get("name")));

        return Map.of(
                "path", dir.toString(),
                "home", home,
                "crumbs", crumbs(dir),
                "entries", entries,
                "truncated", truncated);
    }

    /** 在父目录下新建子目录。 */
    @PostMapping
    public Map<String, Object> create(@RequestBody CreateDirectoryRequest request) {
        if (request == null || request.path() == null || request.path().isBlank()
                || request.name() == null || request.name().isBlank()) {
            return err("path 与 name 不能为空");
        }
        String name = request.name();
        if (name.contains("/") || name.contains("\\") || ".".equals(name) || "..".equals(name)) {
            return err("非法目录名: " + name);
        }
        Path parent = Paths.get(request.path()).toAbsolutePath().normalize();
        if (!Files.exists(parent) || !Files.isDirectory(parent)) {
            return err("父目录不存在或不是目录: " + parent);
        }
        Path child = parent.resolve(name);
        if (Files.exists(child)) {
            return err("directory-exists: " + child);
        }
        try {
            Files.createDirectory(child);
        } catch (IOException e) {
            return err("directory-create-failed: " + e.getMessage());
        }
        return Map.of("path", child.toString());
    }

    private boolean isDir(String raw) {
        try {
            return Files.isDirectory(Paths.get(raw));
        } catch (Exception e) {
            return false;
        }
    }

    /** 祖先链：从文件系统根到当前目录（含），每级为可跳转 crumb。 */
    private List<Map<String, Object>> crumbs(Path dir) {
        List<Map<String, Object>> crumbs = new ArrayList<>();
        Path root = dir.getRoot();
        Path cur = dir;
        List<Path> chain = new ArrayList<>();
        while (cur != null && !cur.equals(root)) {
            chain.add(0, cur);
            cur = cur.getParent();
        }
        if (root != null) {
            crumbs.add(entry(root.toString(), root.toString()));
        }
        for (Path p : chain) {
            crumbs.add(entry(p.getFileName() == null ? p.toString() : p.getFileName().toString(), p.toString()));
        }
        return crumbs;
    }

    private Map<String, Object> entry(String name, String path) {
        return Map.of(
                "name", name,
                "path", path,
                "hidden", name.startsWith("."));
    }

    private Map<String, Object> err(String message) {
        return Map.of("error", message);
    }

    public record CreateDirectoryRequest(String path, String name) {
    }
}
