package com.bizfty.anchon.dsh.compaction;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 工具输出转存配置（对应 DSH spill/spill-policy 的 maxInlineBytes）。
 * <p>
 * max-inline-bytes = 模型可见面的内联上限（UTF-8 字节）；超限的纯文本工具结果
 * 转存到会话级文件，行内替换为 预览 + 定位符。
 */
@Component
public class SpillProperties {

    private final boolean enabled;
    private final int maxInlineBytes;
    private final String dir;

    public SpillProperties(
            @Value("${dsh.spill.enabled:true}") boolean enabled,
            @Value("${dsh.spill.max-inline-bytes:8192}") int maxInlineBytes,
            @Value("${dsh.spill.dir:./data/spill}") String dir) {
        if (maxInlineBytes < 0) {
            throw new IllegalArgumentException("max-inline-bytes 不能为负: " + maxInlineBytes);
        }
        if (dir == null || dir.isBlank()) {
            throw new IllegalArgumentException("spill dir 不能为空");
        }
        this.enabled = enabled;
        this.maxInlineBytes = maxInlineBytes;
        this.dir = dir;
    }

    public boolean enabled() {
        return enabled;
    }

    public int maxInlineBytes() {
        return maxInlineBytes;
    }

    public String dir() {
        return dir;
    }
}
