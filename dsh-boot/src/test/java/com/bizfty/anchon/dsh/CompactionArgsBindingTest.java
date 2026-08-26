package com.bizfty.anchon.dsh;

import com.bizfty.anchon.dsh.compaction.CompactionProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 命令行参数 → 组件属性绑定测试（防止 @Value 因参数未生效而回落默认值，
 * 曾导致压缩阈值/keep-tail 配置无效）。
 */
@SpringBootTest(args = {
        "--dsh.compaction.token-threshold=600",
        "--dsh.compaction.keep-tail-messages=2"})
class CompactionArgsBindingTest {

    @Autowired
    private CompactionProperties properties;

    @Test
    void commandLineArgsBindToValueProperties() {
        assertEquals(600L, properties.tokenThreshold(),
                "命令行 --dsh.compaction.token-threshold=600 应绑定到 @Value");
        assertEquals(10, properties.keepTailMessages(),
                "命令行 keep-tail-messages=2 被构造器下限夹取到 10（Math.max(10, …)）");
        assertEquals(true, properties.enabled());
    }
}
