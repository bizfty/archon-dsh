package com.example.dsh.compaction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 工具输出转存（对应 DSH spill 系列：spill-local 存储 + spill-policy 决策）。
 * <p>
 * 语义：工具结果的 UTF-8 字节数超过 {@code maxInlineBytes} 时，完整内容保存到
 * 会话级文件（{@code <dir>/<sessionId>/<toolName>_<callId>.txt}），模型可见面与
 * 会话日志替换为 有界预览 + 定位符（含 read_file 取回指引）；转存文件保留全文，
 * 模型可按需读取。
 * <p>
 * 刻意收窄（对齐 DSH）：
 * <ul>
 *   <li>跳过 {@code read_file}（避免 read → spill → read 循环）；</li>
 *   <li>best-effort：保存失败/定位符超预算时保留原行内内容，绝不把成功变失败；</li>
 *   <li>替换产物（预览 + 换行 + 定位符）永不超过 maxInlineBytes。</li>
 * </ul>
 */
@Component
public class SpillService {

    private static final Logger log = LoggerFactory.getLogger(SpillService.class);

    /** 定位符文案中的工具名（读文件工具，避免转存循环）。 */
    public static final String READ_TOOL = "read_file";

    private final SpillProperties properties;

    public SpillService(SpillProperties properties) {
        this.properties = properties;
    }

    /**
     * 评估并转存：超限 → 保存全文并返回 预览+定位符 替换文本；否则原样返回。
     *
     * @param sessionId 所属会话（转存文件按会话分组）
     * @param toolName  产生结果的工具名
     * @param callId    模型签发的调用 id
     * @param content   工具结果（JSON 文本）
     */
    public String maybeSpill(String sessionId, String toolName, String callId, String content) {
        if (content == null || content.isEmpty()) {
            return content;
        }
        if (!properties.enabled() || READ_TOOL.equals(toolName)) {
            return content;
        }
        int totalBytes = utf8Bytes(content);
        if (totalBytes <= properties.maxInlineBytes()) {
            return content;
        }
        String locator;
        try {
            locator = save(sessionId, toolName, callId, content);
        } catch (IOException e) {
            // best-effort：保存失败保留原行内内容
            log.warn("[Spill] 转存失败 tool={} session={}: {}；保留原内容", toolName, sessionId, e.getMessage());
            return content;
        }
        String notice = spillNotice(totalBytes, locator);
        // 预留定位符字节成本（含 3 个 "\n\n" 连接符 6 字节），预览预算 = 上限 - 预留
        int reserve = utf8Bytes(notice) + 6;
        int budget = Math.max(0, properties.maxInlineBytes() - reserve);
        int headBytes = (int) Math.ceil(budget / 2.0);
        int tailBytes = (int) Math.floor(budget / 2.0);
        String previewHead = headByUtf8Bytes(content, headBytes);
        String previewTail = tailByUtf8Bytes(content, tailBytes);
        String replaced = previewHead.isEmpty()
                ? notice
                : previewHead + "\n\n" + previewTail + "\n\n" + notice;
        if (utf8Bytes(replaced) > properties.maxInlineBytes()) {
            // 定位符单独就超预算（极小上限或超长转存路径）：保留原内容
            log.warn("[Spill] 定位符超预算，保留原内容 tool={}", toolName);
            return content;
        }
        log.info("[Spill] tool={} session={} 转存 {} bytes → {}", toolName, sessionId, totalBytes, locator);
        return replaced;
    }

    private String save(String sessionId, String toolName, String callId, String content) throws IOException {
        Path sessionDir = Paths.get(properties.dir(), safe(sessionId));
        Files.createDirectories(sessionDir);
        String safeName = safe(toolName) + "_" + safe(callId) + ".txt";
        Path file = sessionDir.resolve(safeName);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file.toAbsolutePath().toString();
    }

    /** 定位符文案（含字节省略数与取回指引）。 */
    private String spillNotice(int omittedBytes, String locator) {
        return "(" + omittedBytes + " bytes 已省略。完整输出已转存: " + locator
                + "。可用 " + READ_TOOL + " 读取完整内容。)";
    }

    private static String safe(String value) {
        return value == null ? "unknown"
                : value.replaceAll("[^a-zA-Z0-9_.-]", "_");
    }

    private static int utf8Bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    /** 按 UTF-8 字节取头（不截断多字节字符）。 */
    static String headByUtf8Bytes(String text, int maxBytes) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return text;
        }
        int end = Math.max(0, maxBytes);
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) {
            end--;
        }
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }

    /** 按 UTF-8 字节取尾（不截断多字节字符）。 */
    static String tailByUtf8Bytes(String text, int maxBytes) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return text;
        }
        int start = Math.max(0, bytes.length - maxBytes);
        while (start < bytes.length && (bytes[start] & 0xC0) == 0x80) {
            start++;
        }
        return new String(bytes, start, bytes.length - start, StandardCharsets.UTF_8);
    }
}
