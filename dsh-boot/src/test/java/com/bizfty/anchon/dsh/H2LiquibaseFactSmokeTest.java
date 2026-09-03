package com.bizfty.anchon.dsh;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * M2 H2 Liquibase 冒烟：0007-session-fact.yaml 变更集在 H2 上真实可执行
 * （建表 + UK(session_id,seq) + idx）。只用 DataSource+Liquibase 最小装配，
 * ddl-auto=none 排除 Hibernate 建表，确保断言来自 Liquibase 而非实体映射。
 */
@SpringBootTest(classes = H2LiquibaseFactSmokeTest.MinimalConfig.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:dsh_fact_lb;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.liquibase.enabled=true",
        "spring.liquibase.change-log=classpath:db/changelog/changelogs/0007-session-fact.yaml",
})
class H2LiquibaseFactSmokeTest {

    @Configuration
    @EnableAutoConfiguration
    static class MinimalConfig {
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void factChangesetAppliesOnH2() {
        Integer tableCount = jdbc.queryForObject(
                "select count(*) from information_schema.tables where table_name = 'ANCHON_SESSION_FACT'",
                Integer.class);
        assertEquals(1, tableCount, "0007 应在 H2 建出 anchon_session_fact");

        Integer ukCount = jdbc.queryForObject(
                "select count(*) from information_schema.table_constraints" +
                        " where table_name = 'ANCHON_SESSION_FACT' and constraint_name = 'UK_ANCHON_FACT_SESSION_SEQ'",
                Integer.class);
        assertEquals(1, ukCount, "0007 应建 UK(session_id,seq) = uk_anchon_fact_session_seq");

        Integer idxCount = jdbc.queryForObject(
                "select count(*) from information_schema.indexes" +
                        " where table_name = 'ANCHON_SESSION_FACT' and index_name = 'IDX_ANCHON_FACT_SESSION'",
                Integer.class);
        assertEquals(1, idxCount, "0007 应建 idx_anchon_fact_session");
    }
}
