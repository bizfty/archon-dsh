package com.bizfty.anchon.dsh.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 会话读模型开关（M2-5，design §4.5）：{@code dsh.session.read-model=table|fact-replay}。
 * <ul>
 *   <li>{@code table}（默认）：读投影缓存 {@code anchon_session_message}（存量兼容，读路径不变）；</li>
 *   <li>{@code fact-replay}（仅验证用，不默认）：读路径改为从真相 {@code anchon_session_fact}
 *       直接读（只读重放），用于 M2 §6.4 的 fact/table 读一致性对照，不做运行时写侧承载
 *       （修剪/压缩等依赖投影行 id 的写侧流程须保持默认 table 模式）。</li>
 * </ul>
 * <p>
 * 绑定约定：优先读环境变量 {@code DSH_SESSION_READ_MODEL}，其次读 yml 文档化属性
 * {@code dsh.session.read-model}（本仓库因 harness 环境 {@code dsh.*} 前缀污染问题
 * 不采用类级 {@code @ConfigurationProperties}，见 {@link WorkspaceProperties} 注释），
 * 最后默认 {@code table}。未知值按 table 处理（fail-safe，不让读路径不可用）。
 */
@Component
public class SessionReadModel {

    private static final Logger log = LoggerFactory.getLogger(SessionReadModel.class);

    public static final String MODE_TABLE = "table";
    public static final String MODE_FACT_REPLAY = "fact-replay";

    private final boolean factReplay;

    public SessionReadModel(
            @Value("${DSH_SESSION_READ_MODEL:${dsh.session.read-model:table}}") String mode) {
        String normalized = mode == null ? MODE_TABLE : mode.trim().toLowerCase();
        if (!MODE_TABLE.equals(normalized) && !MODE_FACT_REPLAY.equals(normalized)) {
            log.warn("未知 dsh.session.read-model={}，按 {} 处理（读投影缓存）", mode, MODE_TABLE);
            normalized = MODE_TABLE;
        }
        this.factReplay = MODE_FACT_REPLAY.equals(normalized);
        log.info("dsh.session.read-model = {}", factReplay ? MODE_FACT_REPLAY : MODE_TABLE);
    }

    /** 是否启用 fact-replay 验证读（false=默认 table 读投影）。 */
    public boolean isFactReplay() {
        return factReplay;
    }
}
