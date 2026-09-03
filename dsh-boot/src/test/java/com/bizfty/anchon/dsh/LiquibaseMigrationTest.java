package com.bizfty.anchon.dsh;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Liquibase 迁移验证（连真实 PostgreSQL）：上下文启动时 Liquibase 执行
 * db/changelog/db.changelog-master.yaml。
 * <p>
 * 幂等验证：changeset 带 preConditions(MARK_RAN) — 表已存在则跳过。
 * 需真实库：运行命令带
 * {@code -Dspring.datasource.url=jdbc:postgresql://localhost:5432/agent
 * -Dspring.datasource.username=agent -Dspring.datasource.password=agent@123}，
 * 或设置 DB_URL/DB_USERNAME/DB_PASSWORD 环境变量。
 * 默认按 test 配置（H2 + liquibase.enabled=false）跳过，因此本测试显式声明属性。
 */
@Disabled("需要真实 PostgreSQL 实例，启用时加 -Dspring.profiles.active=liquibase")
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:5432/agent",
        "spring.datasource.username=agent",
        "spring.datasource.password=agent@123",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.liquibase.enabled=true",
})
class LiquibaseMigrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void contextLoadsWithLiquibase() {
        // 上下文启动成功即证明 Liquibase 对既有库幂等执行通过（表存在 → MARK_RAN）。
        assertTrue(true);
    }

    @Test
    void factSchemaExists() {
        // M2: anchon_session_fact 表 + UK(session_id,seq) 存在（0007 变更集）。
        Integer tableCount = jdbc.queryForObject(
                "select count(*) from information_schema.tables where table_name = 'anchon_session_fact'",
                Integer.class);
        assertEquals(1, tableCount, "anchon_session_fact 表应存在");

        Integer ukCount = jdbc.queryForObject(
                "select count(*) from information_schema.table_constraints" +
                        " where table_name = 'anchon_session_fact' and constraint_name = 'uk_anchon_fact_session_seq'",
                Integer.class);
        assertEquals(1, ukCount, "uk_anchon_fact_session_seq 唯一约束应存在");
    }

    @Test
    void surfaceSchemaExists() {
        // M8: anchon_session_surface 表 + UK(session_id,gen) 存在（0008 变更集）。
        Integer tableCount = jdbc.queryForObject(
                "select count(*) from information_schema.tables where table_name = 'anchon_session_surface'",
                Integer.class);
        assertEquals(1, tableCount, "anchon_session_surface 表应存在");

        Integer ukCount = jdbc.queryForObject(
                "select count(*) from information_schema.table_constraints" +
                        " where table_name = 'anchon_session_surface' and constraint_name = 'uk_anchon_surface_session_gen'",
                Integer.class);
        assertEquals(1, ukCount, "uk_anchon_surface_session_gen 唯一约束应存在");
    }
}
