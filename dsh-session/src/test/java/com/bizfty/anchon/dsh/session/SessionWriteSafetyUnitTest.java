package com.bizfty.anchon.dsh.session;

import com.bizfty.anchon.dsh.core.model.SessionId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SessionService 乐观锁包装单元测试（P2-③ 写安全）：会话行并发更新冲突
 * 被包装为 {@link SessionService.SessionConcurrentModificationException}，
 * 不静默覆盖。乐观锁实际冲突收敛由 SessionServiceTest 集成用例覆盖。
 */
class SessionWriteSafetyUnitTest {

    private SessionRepository sessionRepository;
    private SessionMessageRepository messageRepository;
    private SessionFactStore factStore;
    private SessionService service;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(SessionRepository.class);
        messageRepository = mock(SessionMessageRepository.class);
        factStore = mock(SessionFactStore.class);
        service = new SessionService(sessionRepository, messageRepository, factStore);
    }

    private SessionEntity entity(String id) {
        SessionEntity e = new SessionEntity();
        e.setId(id);
        e.setTitle("t");
        e.setCreatedAt(Instant.now());
        e.setUpdatedAt(Instant.now());
        return e;
    }

    private void stubConflict() {
        when(sessionRepository.saveAndFlush(any())).thenThrow(
                new OptimisticLockingFailureException("模拟多实例写冲突"));
    }

    @Test
    void updateTitleWrapsOptimisticConflictAsDomainException() {
        when(sessionRepository.findById("sess_x")).thenReturn(Optional.of(entity("sess_x")));
        stubConflict();
        assertThrows(SessionService.SessionConcurrentModificationException.class,
                () -> service.updateTitle(SessionId.of("sess_x"), "新标题"));
    }

    @Test
    void updateModelWrapsOptimisticConflictAsDomainException() {
        when(sessionRepository.findById("sess_x")).thenReturn(Optional.of(entity("sess_x")));
        stubConflict();
        assertThrows(SessionService.SessionConcurrentModificationException.class,
                () -> service.updateModel(SessionId.of("sess_x"), "m2"));
    }

    @Test
    void appendPropagatesStoreConflictAsDomainException() {
        // M2：append 委托 SessionFactStore（同事务 fact+投影+会话行乐观锁）；冲突类型契约不变。
        when(factStore.append(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new SessionService.SessionConcurrentModificationException("sess_x", null));
        assertThrows(SessionService.SessionConcurrentModificationException.class,
                () -> service.append(SessionId.of("sess_x"),
                        com.bizfty.anchon.dsh.core.model.MessageRole.USER, "hi", null, null, null));
    }
}
