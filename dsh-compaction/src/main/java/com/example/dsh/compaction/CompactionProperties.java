package com.example.dsh.compaction;

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

    public CompactionProperties(
            @Value("${dsh.compaction.enabled:true}") boolean enabled,
            @Value("${dsh.compaction.token-threshold:8000}") long tokenThreshold,
            @Value("${dsh.compaction.keep-tail-messages:40}") int keepTailMessages,
            @Value("${dsh.compaction.max-summary-characters:2000}") int maxSummaryCharacters) {
        this.enabled = enabled;
        this.tokenThreshold = tokenThreshold;
        this.keepTailMessages = Math.max(10, keepTailMessages);
        this.maxSummaryCharacters = maxSummaryCharacters;
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
