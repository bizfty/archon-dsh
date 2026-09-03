package com.bizfty.anchon.dsh.session;

import com.bizfty.anchon.dsh.core.model.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SessionSurfaceEntity/Repository（M8）集成测试：H2 下 Hibernate 建表（ddl-auto=create-drop）
 * 与实体映射一致；(sessionId,gen) UK 兜底（JDBC 直插验证）、gen 升序读取、deleteBySessionId。
 */
@SpringBootTest(classes = SessionSurfaceRepositoryTest.TestConfig.class)
class SessionSurfaceRepositoryTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableJpaRepositories(basePackageClasses = SessionSurfaceRepository.class)
    @EntityScan(basePackageClasses = SessionSurfaceEntity.class)
    @Import({SessionService.class, SessionFactStore.class})
    static class TestConfig {
    }

    @Autowired
    private SessionService sessionService;
    @Autowired
    private SessionSurfaceRepository repository;
    @Autowired
    private JdbcTemplate jdbc;

    private String sid;

    @BeforeEach
    void newSession() {
        Session s = sessionService.createSession("surface-repo", "deepseek-chat", "/workspace");
        sid = s.id().value();
    }

    private void save(long gen, SurfaceOp op, long from, Long to, String replacement) {
        repository.saveAndFlush(SessionSurfaceEntity.of("surface_" + gen + "_" + from, sid, gen, op, from, to,
                replacement, "{\"reason\":\"test\"}", Instant.now()));
    }

    @Test
    void persistsAndReadsByGenAsc() {
        save(2, SurfaceOp.REPLACE_RANGE, 5, 8L, "fold");
        save(1, SurfaceOp.REPLACE_HEAD, 1, 10L, "摘要");
        save(3, SurfaceOp.APPEND_VIEW, 5, 8L, null);

        List<SessionSurfaceEntity> rows = repository.findBySessionIdOrderByGenAsc(sid);
        assertEquals(3, rows.size());
        assertEquals(1, rows.get(0).getGen());
        assertEquals(SurfaceOp.REPLACE_HEAD, rows.get(0).toInstruction().op());
        assertEquals(2, rows.get(1).getGen());
        assertEquals(3, rows.get(2).getGen());
        assertEquals(10L, rows.get(0).getRangeTo());
        assertEquals(8L, rows.get(1).getRangeTo());
        assertEquals(3, repository.countBySessionId(sid));
    }

    @Test
    void uniqueSessionGenIsDbEnforced() {
        // 绕过 JPA merge/缓存，直接 JDBC 插两条同 (session_id, gen)：UK 应拒绝第二条。
        jdbc.update("insert into anchon_session_surface (id, session_id, gen, op, range_from, range_to, created_at)" +
                " values (?, ?, ?, ?, ?, ?, ?)",
                "raw_1", sid, 1L, "REPLACE_HEAD", 1L, 5L, Instant.now());
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("insert into anchon_session_surface (id, session_id, gen, op, range_from, range_to, created_at)" +
                        " values (?, ?, ?, ?, ?, ?, ?)",
                        "raw_2", sid, 1L, "REPLACE_HEAD", 1L, 6L, Instant.now()));
        assertEquals(1, repository.countBySessionId(sid));
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void deleteBySessionIdClearsInstructions() {
        save(1, SurfaceOp.REPLACE_HEAD, 1, 3L, null);
        save(2, SurfaceOp.APPEND_VIEW, 1, 3L, null);
        assertEquals(2, repository.countBySessionId(sid));
        repository.deleteBySessionId(sid);
        assertEquals(0, repository.countBySessionId(sid));
    }

    @Test
    void uniqueConstraintIsMaterialized() {
        // H2 标识符大写存储；真实库（PostgreSQL + 0008 变更集）同约束 uk_anchon_surface_session_gen。
        Integer count = jdbc.queryForObject(
                "select count(*) from information_schema.table_constraints" +
                        " where upper(table_name) = 'ANCHON_SESSION_SURFACE'" +
                        " and constraint_name = 'UK_ANCHON_SURFACE_SESSION_GEN'",
                Integer.class);
        assertTrue(count != null && count == 1, "应物化 uk_anchon_surface_session_gen，count=" + count);
    }
}
