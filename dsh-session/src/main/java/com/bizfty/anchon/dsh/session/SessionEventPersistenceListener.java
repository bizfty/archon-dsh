package com.bizfty.anchon.dsh.session;

import com.bizfty.anchon.dsh.core.event.SessionEvent;
import com.bizfty.anchon.dsh.core.event.SessionEventBus;
import com.bizfty.anchon.dsh.core.event.SessionEventType;
import com.bizfty.anchon.dsh.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 执行过程事件持久化监听器 — 把会话执行事件写入 {@code anchon_session_event}。
 * <p>
 * 持久化 turn 级执行过程信息（turn/step/模型请求/工具调用/工具结果/消息/错误），
 * 默认跳过逐 token 的 {@code ASSISTANT_TOKEN}（避免海量小行）；
 * 通过 {@code dsh.event-log.include-tokens=true} 可开启完整 token 回放。
 * <p>
 * 事件总线是同步 observe-only 分发且监听器异常隔离，本监听器失败不影响 agent loop。
 */
@Component
public class SessionEventPersistenceListener {

    private static final Logger log = LoggerFactory.getLogger(SessionEventPersistenceListener.class);

    /** 默认持久化的执行事件类型（排除 ASSISTANT_TOKEN）。 */
    private static final Set<SessionEventType> PERSISTED = EnumSet.of(
            SessionEventType.TURN_START,
            SessionEventType.TURN_END,
            SessionEventType.TURN_ERROR,
            SessionEventType.STEP_START,
            SessionEventType.MODEL_REQUEST,
            SessionEventType.USER_MESSAGE,
            SessionEventType.ASSISTANT_MESSAGE,
            SessionEventType.TOOL_CALL,
            SessionEventType.TOOL_RESULT,
            SessionEventType.TOOL_DENIED,
            SessionEventType.TOOL_ERROR,
            SessionEventType.TOOL_TIMEOUT,
            SessionEventType.APPROVAL_REQUESTED,
            SessionEventType.QUESTION_REQUESTED);

    private final SessionEventRepository repository;
    private final JsonUtils jsonUtils;
    private final boolean includeTokens;
    private final Runnable disposer;

    public SessionEventPersistenceListener(SessionEventBus eventBus,
                                           SessionEventRepository repository,
                                           JsonUtils jsonUtils,
                                           @Value("${dsh.event-log.include-tokens:false}") boolean includeTokens) {
        this.repository = repository;
        this.jsonUtils = jsonUtils;
        this.includeTokens = includeTokens;
        // order 低：持久化优先于 UI 推送，保证落库先行（监听器异常隔离，不影响其它）
        this.disposer = eventBus.addListener(new com.bizfty.anchon.dsh.core.event.SessionEventListener() {
            @Override
            public int order() {
                return -200;
            }

            @Override
            public void onEvent(SessionEvent event) {
                persist(event);
            }
        });
    }

    /** 事件 → 落库（默认集合 + 可选 token）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persist(SessionEvent event) {
        if (!includeTokens && event.type() == SessionEventType.ASSISTANT_TOKEN) {
            return;
        }
        if (!includeTokens && !PERSISTED.contains(event.type())) {
            return;
        }
        String payloadJson = jsonUtils.toJson(event.payload() == null ? Map.of() : event.payload());
        repository.save(SessionEventEntity.from(event, payloadJson));
    }

    /** 注册监听器，返回 disposer（供销毁）。 */
    public Runnable disposer() {
        return disposer;
    }
}
