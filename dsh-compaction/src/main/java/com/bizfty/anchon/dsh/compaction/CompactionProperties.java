package com.bizfty.anchon.dsh.compaction;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 压缩配置（对应 DSH compaction 的 token 压力参数）。
 */
@Component
public class CompactionProperties {

    private final boolean enabled;
    private final long tokenThreshold;
    private final int keepTailMessages;
    private final int maxSummaryCharacters;
    private final boolean pruneOldResultsEnabled;

    /** 兼容构造（P2 前调用点/测试）：prune-old-results 默认关。 */
    public CompactionProperties(boolean enabled, long tokenThreshold,
                                int keepTailMessages, int maxSummaryCharacters) {
        this(enabled, tokenThreshold, keepTailMessages, maxSummaryCharacters, false);
    }

    @Autowired
    public CompactionProperties(
            @Value("${dsh.compaction.enabled:true}") boolean enabled,
            @Value("${dsh.compaction.token-threshold:8000}") long tokenThreshold,
            @Value("${dsh.compaction.keep-tail-messages:40}") int keepTailMessages,
            @Value("${dsh.compaction.max-summary-characters:2000}") int maxSummaryCharacters,
            @Value("${dsh.compaction.prune-old-results.enabled:false}") boolean pruneOldResultsEnabled) {
        this.enabled = enabled;
        this.tokenThreshold = tokenThreshold;
        this.keepTailMessages = Math.max(10, keepTailMessages);
        this.maxSummaryCharacters = maxSummaryCharacters;
        this.pruneOldResultsEnabled = pruneOldResultsEnabled;
    }

    /** durable 旧工具结果修剪开关（P2-②，默认关：改动投影语义，先经真实会话验证）。 */
    public boolean pruneOldResultsEnabled() {
        return pruneOldResultsEnabled;
    }

    public boolean enabled() {
        return enabled;
    }

    public long tokenThreshold() {
        return tokenThreshold;
    }

    public int keepTailMessages() {
        return keepTailMessages;
    }

    public int maxSummaryCharacters() {
        return maxSummaryCharacters;
    }
}
