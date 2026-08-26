package com.bizfty.anchon.dsh.compaction;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具输出转存测试：小结果不动、超限转存+预览定位符、read_file 跳过、
 * 关闭时跳过、保存失败保留原内容、字节安全截断。
 */
class SpillServiceTest {

    private SpillService service(String dir, boolean enabled, int cap) {
        return new SpillService(new SpillProperties(enabled, cap, dir));
    }

    @Test
    void smallResultUnchanged(@TempDir Path dir) {
        SpillService service = service(dir.toString(), true, 8192);
        String content = "{\"ok\":true}";
        assertEquals(content, service.maybeSpill("s1", "bash", "call_1", content));
        // 无转存文件
        try (var stream = Files.list(dir)) {
            assertEquals(0, stream.count());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void oversizedResultSpilledWithPreviewAndLocator(@TempDir Path dir) throws Exception {
        SpillService service = service(dir.toString(), true, 300);
        String content = "HEADHEAD".repeat(40) + "TAILTAIL"; // 328 bytes, > 300
        String replaced = service.maybeSpill("s_abc", "bash", "call_x", content);

        assertFalse(replaced.contains("HEADHEAD".repeat(40)), "不应保留全文");
        assertTrue(replaced.contains("完整输出已转存"), "应含定位符文案");
        assertTrue(replaced.contains("bash_call_x.txt"), "定位符指向转存文件");
        assertTrue(replaced.contains("可用 read_file 读取"), "应含取回指引");
        assertTrue(replaced.startsWith("HEADHEAD"), "保留头");
        assertTrue(replaced.contains("TAILTAIL"), "保留尾");
        // 替换产物不超过上限
        assertTrue(replaced.getBytes(StandardCharsets.UTF_8).length <= 300,
                "替换产物不得超过 maxInlineBytes");

        // 转存文件 = 完整原文
        Path file = dir.resolve("s_abc").resolve("bash_call_x.txt");
        assertTrue(Files.exists(file));
        assertEquals(content, Files.readString(file));
    }

    @Test
    void readFileToolSkipped(@TempDir Path dir) {
        SpillService service = service(dir.toString(), true, 10);
        String huge = "x".repeat(5000);
        assertEquals(huge, service.maybeSpill("s1", "read_file", "call_1", huge),
                "read_file 跳过转存（避免 read→spill→read 循环）");
    }

    @Test
    void disabledLeavesContentUntouched(@TempDir Path dir) {
        SpillService service = service(dir.toString(), false, 10);
        String huge = "y".repeat(5000);
        assertEquals(huge, service.maybeSpill("s1", "bash", "call_1", huge));
    }

    @Test
    void saveFailureKeepsOriginalInline(@TempDir Path dir) throws Exception {
        // dir 指向一个普通文件 → createDirectories 失败 → best-effort 保留原内容
        Path file = dir.resolve("not-a-dir");
        Files.writeString(file, "occupied");
        SpillService service = service(file.toString(), true, 10);
        String huge = "z".repeat(5000);
        assertEquals(huge, service.maybeSpill("s1", "bash", "call_1", huge),
                "保存失败必须保留原内容（绝不把成功变失败）");
    }

    @Test
    void utf8ByteSplittingIsCharSafe() {
        // 中文/emoji 多字节字符：按 UTF-8 字节截断不得截出半个字符
        String text = "你好世界😀😀😀".repeat(50);
        String head = SpillService.headByUtf8Bytes(text, 7);
        assertTrue(text.startsWith(head), "头是原文前缀");
        String tail = SpillService.tailByUtf8Bytes(text, 7);
        assertTrue(text.endsWith(tail), "尾是原文后缀");
        // 拼接可还原
        assertEquals(text, head + text.substring(head.length()));
    }

    @Test
    void configValidation() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new SpillProperties(true, -1, "./data/spill"), "max-inline-bytes 不能为负");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new SpillProperties(true, 100, "  "), "dir 不能为空");
    }
}
