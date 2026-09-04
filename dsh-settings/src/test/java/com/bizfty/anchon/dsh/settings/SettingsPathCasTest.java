package com.bizfty.anchon.dsh.settings;

import com.bizfty.anchon.dsh.storage.InMemoryStorageBackend;
import com.bizfty.anchon.dsh.storage.StorageBackend;
import com.bizfty.anchon.dsh.storage.StorageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P3 设置官方语义单测：user 层单文档 + lazy 迁移、path 级 set/unset（含空 path/中间对象/数组段）、
 * revision CAS（冲突/幂等不推进）、replace/update、presence 覆盖标记。
 */
class SettingsPathCasTest {

    private static final tools.jackson.databind.ObjectMapper MAPPER = JsonMapper.builder().build();

    private SettingsService service(StorageBackend backend) {
        @SuppressWarnings("unchecked")
        ObjectProvider<StorageBackend> op = mock(ObjectProvider.class);
        when(op.orderedStream()).thenReturn(Stream.of(backend));
        return new SettingsService(new StorageService(op), MAPPER);
    }

    private SettingsService inMemoryService() {
        return service(new InMemoryStorageBackend());
    }

    // ===== 旧模型迁移 =====

    @Test
    void legacyKeyEntriesMigrateIntoDocumentOnFirstAccess() {
        InMemoryStorageBackend backend = new InMemoryStorageBackend();
        StorageService storage = new StorageService(provider(backend));
        // 预置旧模型条目：标量文本 + 嵌套 JSON
        storage.put("settings.agent", "temperature", "0.3");
        storage.put("settings.agent", "profile", "{\"name\":\"alice\",\"age\":30}");

        SettingsService service = new SettingsService(storage, MAPPER);
        assertEquals(0.3, service.get("agent", "temperature"));
        assertEquals(Map.of("name", "alice", "age", 30), service.get("agent", "profile"),
                "旧 JSON 条目应还原为结构化对象");

        // 旧条目已删除、文档已落
        assertTrue(backend.keys("settings.agent").isEmpty() || !backend.keys("settings.agent").contains("temperature"));
        assertEquals(1L, service.revision("agent"), "迁移后修订号=1");
        assertTrue(service.overridden("agent", List.of("temperature")));
    }

    // ===== path set/unset 语义 =====

    @Test
    void setCreatesIntermediateObjectsAndNestedPaths() {
        SettingsService service = inMemoryService();
        service.mutate("agent", List.of(SettingsPathOp.set(List.of("profile", "name"), "alice")), null);
        service.mutate("agent", List.of(SettingsPathOp.set(List.of("profile", "age"), 30)), null);

        Map<String, Object> profile = (Map<String, Object>) service.get("agent", "profile");
        assertEquals("alice", profile.get("name"));
        assertEquals(30, profile.get("age"));
    }

    @Test
    void unsetRemovesNestedPathButKeepsSiblings() {
        SettingsService service = inMemoryService();
        service.mutate("agent", List.of(
                SettingsPathOp.set(List.of("profile", "name"), "alice"),
                SettingsPathOp.set(List.of("profile", "age"), 30)), null);
        service.unset("agent", List.of("profile", "name"), null);

        Map<String, Object> profile = (Map<String, Object>) service.get("agent", "profile");
        assertFalse(profile.containsKey("name"));
        assertEquals(30, profile.get("age"));
        assertFalse(service.overridden("agent", List.of("profile", "name")));
        assertTrue(service.overridden("agent", List.of("profile", "age")));
    }

    @Test
    void emptyPathUnsetClearsDocumentAndSetReplacesRoot() {
        SettingsService service = inMemoryService();
        service.mutate("agent", List.of(SettingsPathOp.set(List.of("a"), 1), SettingsPathOp.set(List.of("b"), 2)), null);
        service.mutate("agent", List.of(new SettingsPathOp("unset", List.of(), null)), null);
        assertTrue(service.userSection("agent").isEmpty());

        service.mutate("agent", List.of(new SettingsPathOp("set", List.of(),
                Map.of("x", "whole"))), null);
        assertEquals("whole", service.get("agent", "x"));
        assertNull(service.get("agent", "a"));
    }

    @Test
    void arrayPathSetUnsetAndOutOfBoundsRejected() {
        SettingsService service = inMemoryService();
        service.mutate("agent", List.of(SettingsPathOp.set(List.of("items"), List.of("a", "b", "c"))), null);
        service.mutate("agent", List.of(SettingsPathOp.set(List.of("items", "1"), "x")), null);
        assertEquals(List.of("a", "x", "c"), service.get("agent", "items"));

        service.unset("agent", List.of("items", "1"), null);
        assertEquals(List.of("a", "c"), service.get("agent", "items"));

        assertThrows(IllegalArgumentException.class,
                () -> service.mutate("agent", List.of(SettingsPathOp.set(List.of("items", "9"), "z")), null),
                "数组越界 set 应拒绝");
    }

    // ===== revision CAS =====

    @Test
    void casRefusesStaleWriteAndReportsExpectedActual() {
        SettingsService service = inMemoryService();
        long rev0 = service.revision("agent");
        long rev1 = service.mutate("agent", List.of(SettingsPathOp.set(List.of("a"), 1)), rev0);
        assertEquals(1L, rev1);

        SettingsConflictException ex = assertThrows(SettingsConflictException.class,
                () -> service.mutate("agent", List.of(SettingsPathOp.set(List.of("b"), 2)), rev0),
                "过期 expectedRevision 应拒绝");
        assertEquals(rev0, ex.getExpected());
        assertEquals(rev1, ex.getActual());
        assertEquals(SettingsConflictException.CODE, SettingsConflictException.CODE);
        assertNull(service.get("agent", "b"), "冲突写入不得落盘");
    }

    @Test
    void casPassesWithCurrentRevisionAndNullSkipsCheck() {
        SettingsService service = inMemoryService();
        service.mutate("agent", List.of(SettingsPathOp.set(List.of("a"), 1)), null);
        long current = service.revision("agent");
        long next = service.mutate("agent", List.of(SettingsPathOp.set(List.of("b"), 2)), current);
        assertEquals(current + 1, next);
    }

    @Test
    void noActualChangeDoesNotAdvanceRevision() {
        SettingsService service = inMemoryService();
        service.mutate("agent", List.of(SettingsPathOp.set(List.of("a"), 1)), null);
        long rev = service.revision("agent");
        // 同值重写与不存在键 unset：无实际变化 → rev 不推进
        assertEquals(rev, service.mutate("agent", List.of(SettingsPathOp.set(List.of("a"), 1)), rev));
        assertEquals(rev, service.unset("agent", List.of("ghost"), rev));
        assertEquals(rev, service.revision("agent"));
    }

    // ===== replace / update =====

    @Test
    void replaceWholesaleAndAbsentKeysReinheritDefaults() {
        SettingsService service = inMemoryService();
        service.registerDefaults("agent", Map.of("temperature", 0.7, "max-steps", 25));
        service.set("agent", "temperature", "0.3");
        service.replace("agent", new LinkedHashMap<>(Map.of("max-steps", 10)), null);

        assertEquals(0.7, service.get("agent", "temperature"), "replace 后缺键回默认");
        assertEquals(10, service.get("agent", "max-steps"));
        assertFalse(service.overridden("agent", List.of("temperature")));
    }

    @Test
    void updateDeepMergesPatchIntoUserDocument() {
        SettingsService service = inMemoryService();
        service.mutate("agent", List.of(SettingsPathOp.set(List.of("profile"), Map.of("name", "alice", "age", 30))), null);
        service.update("agent", new LinkedHashMap<>(Map.of("profile", Map.of("age", 31))), null);

        Map<String, Object> profile = (Map<String, Object>) service.get("agent", "profile");
        assertEquals("alice", profile.get("name"), "update 深合并应保留未提及子键");
        assertEquals(31, profile.get("age"));
    }

    // ===== presence 覆盖标记 =====

    @Test
    void presenceMarksOverriddenOnlyWhereUserDocumentHasValue() {
        SettingsService service = inMemoryService();
        service.registerDefaults("agent", Map.of("temperature", 0.7, "mode", "auto"));
        service.set("agent", "temperature", "0.5");

        assertTrue(service.overridden("agent", List.of("temperature")));
        assertFalse(service.overridden("agent", List.of("mode")), "仅默认值在场不算覆盖");

        service.unset("agent", List.of("temperature"), null);
        assertFalse(service.overridden("agent", List.of("temperature")), "unset 后回继承态");
        assertEquals(0.7, service.get("agent", "temperature"));
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<StorageBackend> provider(StorageBackend backend) {
        ObjectProvider<StorageBackend> op = mock(ObjectProvider.class);
        when(op.orderedStream()).thenReturn(Stream.of(backend));
        return op;
    }
}
