package com.example.dsh.storage;

import com.example.dsh.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * JSON 文件后端 — 每个命名空间一个 JSON 文件（原子写：临时文件 + 移动）。
 * <p>
 * 持久化后端须排在内存后端之前（StorageService 写入第一个后端），
 * 否则 `put` 只落内存、重启即丢。
 */
@Component
@Order(0)
public class JsonFileStorageBackend implements StorageBackend {

    private static final Logger log = LoggerFactory.getLogger(JsonFileStorageBackend.class);

    private final Path root;
    private final JsonUtils jsonUtils;

    public JsonFileStorageBackend(@Value("${dsh.storage.dir:./data}") String rootDir) {
        this.root = Paths.get(rootDir);
        this.jsonUtils = new JsonUtils();
    }

    @Override
    public String name() {
        return "json";
    }

    @Override
    public Optional<String> get(String namespace, String key) {
        return Optional.ofNullable(load(namespace).get(key));
    }

    @Override
    public void put(String namespace, String key, String value) {
        Map<String, String> data = load(namespace);
        data.put(key, value);
        save(namespace, data);
    }

    @Override
    public void delete(String namespace, String key) {
        Map<String, String> data = load(namespace);
        if (data.remove(key) != null) {
            save(namespace, data);
        }
    }

    @Override
    public List<String> keys(String namespace) {
        return new ArrayList<>(load(namespace).keySet());
    }

    private Path fileOf(String namespace) {
        return root.resolve(namespace + ".json");
    }

    private Map<String, String> load(String namespace) {
        Path file = fileOf(namespace);
        if (!Files.isRegularFile(file)) {
            return new LinkedHashMap<>();
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = jsonUtils.toMap(text);
            Map<String, String> result = new LinkedHashMap<>();
            raw.forEach((k, v) -> result.put(k, String.valueOf(v)));
            return result;
        } catch (Exception e) {
            log.warn("[Storage] 读取 {} 失败（按空处理）: {}", namespace, e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private void save(String namespace, Map<String, String> data) {
        try {
            Files.createDirectories(root);
            Path file = fileOf(namespace);
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, jsonUtils.toPrettyJson(data), StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new StorageException("写入存储失败: " + namespace + " — " + e.getMessage(), e);
        }
    }

    /** 存储异常（fail loud）。 */
    public static final class StorageException extends RuntimeException {
        public StorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
