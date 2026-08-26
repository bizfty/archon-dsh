package com.bizfty.anchon.dsh.session;

import com.bizfty.anchon.dsh.core.model.MessageRole;
import com.bizfty.anchon.dsh.core.model.Session;
import com.bizfty.anchon.dsh.core.model.SessionId;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话检索测试（H2）：关键词搜索、大小写不敏感、排序。
 */
@SpringBootTest(classes = SessionQueryServiceTest.TestConfig.class)
class SessionQueryServiceTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableJpaRepositories(basePackageClasses = SessionRepository.class)
    @EntityScan(basePackageClasses = SessionEntity.class)
    @Import({SessionService.class, SessionQueryService.class})
    static class TestConfig {
    }

    @org.springframework.beans.factory.annotation.Autowired
    private SessionService sessionService;

    @org.springframework.beans.factory.annotation.Autowired
    private SessionQueryService queryService;

    @Test
    void searchesByKeywordCaseInsensitive() {
        Session s1 = sessionService.createSession("t1", "deepseek-chat", null);
        sessionService.append(s1.id(), MessageRole.USER, "帮我写一个 Spring 报告", null, null, null);
        sessionService.append(s1.id(), MessageRole.ASSISTANT, "好的，报告如下", null, null, null);

        Session s2 = sessionService.createSession("t2", "deepseek-chat", null);
        sessionService.append(s2.id(), MessageRole.USER, "你好", null, null, null);

        List<SessionQueryService.SearchHit> hits = queryService.search("spring", 10);
        assertEquals(1, hits.size(), "只有 s1 命中 spring");
        assertEquals("帮我写一个 Spring 报告", hits.get(0).content());
        assertEquals(s1.id().value(), hits.get(0).sessionId());
    }

    @Test
    void blankKeywordReturnsEmpty() {
        assertTrue(queryService.search("", 10).isEmpty());
        assertTrue(queryService.search("  ", 10).isEmpty());
    }

    @Test
    void respectsLimit() {
        Session s = sessionService.createSession("t", "deepseek-chat", null);
        for (int i = 0; i < 5; i++) {
            sessionService.append(s.id(), MessageRole.USER, "keyword-消息-" + i, null, null, null);
        }
        assertEquals(3, queryService.search("keyword", 3).size());
        assertEquals(5, queryService.search("keyword", 100).size());
    }
}
