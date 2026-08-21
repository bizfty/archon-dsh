package com.example.dsh.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JSON 文件存储后端测试：写入/读取/删除/键列表/重启持久化。
 */
class JsonFileStorageBackendTest {

    @Test
    void roundTripAndKeys(@TempDir Path dir) {
        JsonFileStorageBackend backend = new JsonFileStorageBackend(dir.toString());
        backend.put("todo", "sess_1", "[\"a\",\"b\"]");
        backend.put("todo", "sess_2", "[]");
        backend.put("settings.agent", "temperature", "0.7");

        assertEquals("[\"a\",\"b\"]", backend.get("todo", "sess_1").orElse(""));
        assertTrue(backend.keys("todo").containsAll(List.of("sess_1", "sess_2")));
        assertTrue(Files.exists(dir.resolve("todo.json")));
    }

    @Test
    void deleteRemovesKey(@TempDir Path dir) {
        JsonFileStorageBackend backend = new JsonFileStorageBackend(dir.toString());
        backend.put("ns", "k", "v");
        backend.delete("ns", "k");
        assertTrue(backend.get("ns", "k").isEmpty());
    }

    @Test
    void persistsAcrossInstances(@TempDir Path dir) {
        JsonFileStorageBackend first = new JsonFileStorageBackend(dir.toString());
        first.put("plan", "sess_9", "{\"active\":true}");
        // 模拟重启：新实例读同一目录
        JsonFileStorageBackend second = new JsonFileStorageBackend(dir.toString());
        assertEquals("{\"active\":true}", second.get("plan", "sess_9").orElse(""));
    }

    @Test
    void missingNamespaceYieldsEmpty(@TempDir Path dir) {
        JsonFileStorageBackend backend = new JsonFileStorageBackend(dir.toString());
        assertTrue(backend.get("nope", "k").isEmpty());
        assertTrue(backend.keys("nope").isEmpty());
    }
}
