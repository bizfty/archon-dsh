package com.bizfty.anchon.dsh.storage;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JpaStorageBackend 测试（H2 内存）：CRUD 与命名空间键枚举。
 * Boot 4 无 @DataJpaTest，按项目惯例用 @SpringBootTest + 最小 JPA 配置。
 */
@SpringBootTest(classes = JpaStorageBackendTest.TestConfig.class)
class JpaStorageBackendTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableJpaRepositories(basePackageClasses = AnchonKvRepository.class)
    @EntityScan(basePackageClasses = AnchonKvEntity.class)
    static class TestConfig {
    }

    @Autowired
    private AnchonKvRepository repository;

    private JpaStorageBackend backend() {
        return new JpaStorageBackend(repository);
    }

    @Test
    void putGetRoundTrip() {
        JpaStorageBackend backend = backend();
        backend.put("goals", "sess_rt", "{\"phase\":\"active\"}");

        Optional<String> value = backend.get("goals", "sess_rt");
        assertTrue(value.isPresent());
        assertEquals("{\"phase\":\"active\"}", value.get());
    }

    @Test
    void putOverwritesSameKey() {
        JpaStorageBackend backend = backend();
        backend.put("goals", "sess_ow", "v1");
        backend.put("goals", "sess_ow", "v2");
        assertEquals("v2", backend.get("goals", "sess_ow").orElse(""));
    }

    @Test
    void deleteRemovesKey() {
        JpaStorageBackend backend = backend();
        backend.put("goals", "sess_del", "v");
        backend.delete("goals", "sess_del");
        assertTrue(backend.get("goals", "sess_del").isEmpty());
    }

    @Test
    void keysListsNamespaceKeysOnly() {
        JpaStorageBackend backend = backend();
        // 清空目标命名空间，避免与其他测试共享 H2 内存库导致残留
        for (String old : backend.keys("goals")) {
            backend.delete("goals", old);
        }
        backend.put("goals", "sess_a", "1");
        backend.put("goals", "sess_b", "2");
        backend.put("plan", "sess_c", "3");

        List<String> keys = backend.keys("goals");
        assertEquals(2, keys.size());
        assertTrue(keys.containsAll(List.of("sess_a", "sess_b")));
    }

    @Test
    void missingKeyReturnsEmpty() {
        JpaStorageBackend backend = backend();
        assertTrue(backend.get("users", "nobody").isEmpty());
    }
}
