package com.bizfty.anchon.dsh;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

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
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:5432/agent",
        "spring.datasource.username=agent",
        "spring.datasource.password=agent@123",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.liquibase.enabled=true",
})
class LiquibaseMigrationTest {

    @Test
    void contextLoadsWithLiquibase() {
        // 上下文启动成功即证明 Liquibase 对既有库幂等执行通过（表存在 → MARK_RAN）。
        assertTrue(true);
    }
}
