package com.bizfty.anchon.dsh.identity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 匿名用户 id 测试：生成/持久化/复用。
 */
class AnonymousUserIdTest {

    @Test
    void generatesAndPersists(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("id.txt");
        AnonymousUserId first = new AnonymousUserId(file.toString());
        first.init();
        String id1 = first.get();
        assertTrue(id1.startsWith("anon_"));
        assertTrue(Files.exists(file), "id 应持久化到文件");
        assertEquals(id1, Files.readString(file).trim());
    }

    @Test
    void reusesExistingId(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("id.txt");
        Files.writeString(file, "anon_fixed");
        AnonymousUserId service = new AnonymousUserId(file.toString());
        service.init();
        assertEquals("anon_fixed", service.get());
    }

    @Test
    void unreadablePathFallsBackToRandom() {
        AnonymousUserId service = new AnonymousUserId("/proc/1/nonexistent-dir/id.txt");
        service.init();
        assertFalse(service.get().isBlank());
    }
}
