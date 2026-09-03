package com.bizfty.anchon.dsh.session;

import com.bizfty.anchon.dsh.core.model.MessageRole;
import com.bizfty.anchon.dsh.core.model.Session;
import com.bizfty.anchon.dsh.core.model.SessionId;
import com.bizfty.anchon.dsh.core.model.SessionMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * M2 基线探针（对照 design §6.5/#5）：记录改造前 {@link SessionService#append} 写路径
 * 的每 op 耗时与行数（H2 内存库）。只做软性指标输出，不做时序断言（避免 CI 抖动）；
 * m2-5 用同测试重跑作为"同量级"对照。输出行形如：
 *   [BASELINE] append path metrics: avgMs=.. p50Ms=.. ops=.. messageRows=.. sessionVersion=..
 */
@SpringBootTest(classes = SessionServiceTest.TestConfig.class)
class AppendPathBaselineProbeTest {

    @Autowired
    private SessionService sessionService;
    @Autowired
    private SessionMessageRepository messageRepository;

    @Test
    void recordAppendBaselineMetrics() {
        Session session = sessionService.createSession("基线", "deepseek-chat", "/workspace");
        SessionId id = session.id();

        // 预热（JIT/连接池/seq 分配路径）
        for (int i = 0; i < 5; i++) {
            sessionService.append(id, MessageRole.USER, "warmup-" + i, null, null, null);
        }
        // 测量批次
        int ops = 30;
        long[] samples = new long[ops];
        long t0 = System.nanoTime();
        for (int i = 0; i < ops; i++) {
            long s = System.nanoTime();
            sessionService.append(id, MessageRole.USER, "m-" + i, null, null, null);
            samples[i] = System.nanoTime() - s;
        }
        long totalMs = (System.nanoTime() - t0) / 1_000_000;
        java.util.Arrays.sort(samples);
        long p50Ms = samples[ops / 2] / 1_000_000;

        List<SessionMessage> messages = sessionService.listMessages(id);
        long rows = messageRepository.countBySessionId(id.value());
        long lastSeq = messages.get(messages.size() - 1).seq();

        // 不变量断言（非时序）：行数 == seq == 预热+测量条数
        assertEquals(5 + ops, messages.size());
        assertEquals(5 + ops, rows);
        assertEquals((long) (5 + ops), lastSeq);

        System.out.println("[BASELINE] append path metrics: avgMs=" + String.format("%.2f", totalMs / (double) ops)
                + " totalMs=" + totalMs + " p50Ms=" + p50Ms + " ops=" + ops
                + " messageRows=" + rows + " sessionVersion=" + lastSeq);
    }
}
