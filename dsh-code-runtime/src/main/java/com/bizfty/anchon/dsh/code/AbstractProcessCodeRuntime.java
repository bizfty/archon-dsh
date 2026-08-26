package com.bizfty.anchon.dsh.code;

import com.bizfty.anchon.dsh.tool.ToolContext;
import com.bizfty.anchon.dsh.tool.ToolExecutionPipeline;
import com.bizfty.anchon.dsh.tool.ToolResult;
import com.bizfty.anchon.dsh.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 进程式代码运行时基类 — 子进程执行 + 行分隔 JSON-RPC 回环调用管线工具。
 * <p>
 * shim 脚本向本端发送 {@code {"id","name","args"}} 工具请求，本端经
 * {@link ToolExecutionPipeline} 执行后回写 {@code {"id","result"|"error"}}；
 * 程序结束输出 {@code __DSH_RESULT__<json>} 标记（logs/result/error）。
 */
public abstract class AbstractProcessCodeRuntime implements CodeRuntime {

    private static final Logger log = LoggerFactory.getLogger(AbstractProcessCodeRuntime.class);

    private static final String RESULT_MARKER = "__DSH_RESULT__";

    protected final ToolExecutionPipeline pipeline;
    protected final JsonUtils jsonUtils;
    protected final long defaultTimeoutMs;
    protected final long maxOutputBytes;
    private final Path shimFile;

    protected AbstractProcessCodeRuntime(ToolExecutionPipeline pipeline,
                                         long defaultTimeoutMs, long maxOutputBytes) {
        this.pipeline = pipeline;
        this.jsonUtils = new JsonUtils();
        this.defaultTimeoutMs = defaultTimeoutMs;
        this.maxOutputBytes = maxOutputBytes;
        this.shimFile = extractShim();
    }

    /** 可执行文件路径（node / python3）。 */
    protected abstract String executable();

    /**
     * 回写一次工具调用结果：成功 → {"result": ...}；失败 → {"error": ...}（程序侧 reject，
     * 对应 DSH "失败结果 reject 为 ToolCallError"）。
     */
    protected abstract void writeToolResult(java.io.Writer stdin, Object id, ToolResult result)
            throws java.io.IOException;

    /** shim 资源名（classpath）。 */
    protected abstract String shimResource();

    @Override
    public CodeRunResult run(String code, ToolContext context, long timeoutMs) {
        long effectiveTimeout = timeoutMs > 0 ? timeoutMs : defaultTimeoutMs;
        Path codeFile = null;
        try {
            codeFile = Files.createTempFile("run_code_", language().equals("python") ? ".py" : ".js");
            Files.writeString(codeFile, code, StandardCharsets.UTF_8);
            ProcessBuilder pb = new ProcessBuilder(executable(), shimFile.toString(), codeFile.toString());
            Process process = pb.start();
            BufferedReader stdout = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            Writer stdin = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);

            List<String> logs = new ArrayList<>();
            Object result = null;
            String error = null;
            boolean finished = false;
            long deadline = System.currentTimeMillis() + effectiveTimeout;
            StringBuilder resultLine = new StringBuilder();

            Thread reader = Thread.startVirtualThread(() -> {
                try {
                    String l;
                    while ((l = stdout.readLine()) != null) {
                        if (l.startsWith(RESULT_MARKER)) {
                            synchronized (resultLine) {
                                resultLine.append(l);
                                resultLine.notifyAll();
                            }
                            return;
                        }
                        handleToolRequest(l, stdin, context);
                    }
                } catch (IOException ignored) {
                    // 进程退出
                }
            });

            synchronized (resultLine) {
                while (!finished && System.currentTimeMillis() < deadline) {
                    if (resultLine.length() > 0) {
                        String payload = resultLine.toString().substring(RESULT_MARKER.length());
                        Map<String, Object> map = jsonUtils.toMap(payload);
                        @SuppressWarnings("unchecked")
                        List<String> l = (List<String>) map.getOrDefault("logs", List.of());
                        logs.addAll(l);
                        result = map.get("result");
                        error = map.get("error") == null ? null : String.valueOf(map.get("error"));
                        finished = true;
                        break;
                    }
                    try {
                        resultLine.wait(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            if (!finished) {
                process.destroyForcibly();
                return new CodeRunResult(logs, null, "run_code 超时（>" + effectiveTimeout + " ms）");
            }
            process.waitFor(2, TimeUnit.SECONDS);
            reader.join(1000);
            return new CodeRunResult(logs, result, error);
        } catch (IOException e) {
            return new CodeRunResult(List.of(), null, "run_code 启动失败: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CodeRunResult(List.of(), null, "run_code 被中断");
        } finally {
            if (codeFile != null) {
                try {
                    Files.deleteIfExists(codeFile);
                } catch (IOException ignored) {
                }
            }
        }
    }

    /** 处理一行工具调用请求并回写响应。 */
    private void handleToolRequest(String line, Writer stdin, ToolContext context) {
        try {
            Map<String, Object> request = jsonUtils.toMap(line);
            Object id = request.get("id");
            String name = request.get("name") == null ? null : String.valueOf(request.get("name"));
            Object args = request.get("args");
            String argsJson = args == null ? "{}" : jsonUtils.toJson(args);
            ToolResult result = pipeline.execute(name, argsJson, context);
            writeToolResult(stdin, id, result);
        } catch (Exception e) {
            try {
                Object id = jsonUtils.toMap(line).get("id");
                writeLine(stdin, jsonUtils.toJson(Map.of("id", id, "error", "工具执行异常: " + e.getMessage())));
            } catch (Exception ignored) {
            }
        }
    }

    protected void writeLine(Writer stdin, String line) throws IOException {
        stdin.write(line + "\n");
        stdin.flush();
    }

    /** 提取 shim 脚本到临时目录（类路径资源 → 文件）。 */
    private Path extractShim() {
        try {
            Path dir = Files.createTempDirectory("dsh-code-runtime");
            Path shim = dir.resolve(shimResource());
            try (var in = getClass().getResourceAsStream("/" + shimResource())) {
                if (in == null) {
                    throw new IllegalStateException("缺少 shim 资源: " + shimResource());
                }
                Files.copy(in, shim, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            return shim;
        } catch (IOException e) {
            throw new IllegalStateException("无法提取 shim: " + e.getMessage(), e);
        }
    }
}
