package com.bizfty.anchon.dsh.api;

import com.bizfty.anchon.dsh.fs.FsPathPolicy;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 文件读取 API — 供前端"点击工具行路径查看文件"（对应官方 read-row 的可打开路径）。
 * 只读：返回文件内容（截断保护）与行数，不改任何文件。
 */
@RestController
@RequestMapping("/api/files")
public class FileController {

    private static final long MAX_BYTES = 512 * 1024;

    /** 读取文件（供工具行路径点击查看）。 */
    @GetMapping("/read")
    public Map<String, Object> read(@RequestParam String path) {
        Path resolved = FsPathPolicy.normalize(path, null);
        Map<String, Object> view = new LinkedHashMap<>();
        if (!Files.exists(resolved)) {
            view.put("error", "文件不存在: " + resolved);
            return view;
        }
        if (Files.isDirectory(resolved)) {
            view.put("error", "是目录，不是文件: " + resolved);
            return view;
        }
        try {
            String text = FsPathPolicy.readText(resolved, MAX_BYTES);
            view.put("path", resolved.toString());
            view.put("content", text);
            view.put("lines", text.lines().count());
            return view;
        } catch (IOException e) {
            view.put("error", "读取失败: " + e.getMessage());
            return view;
        }
    }
}
