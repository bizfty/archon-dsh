package com.bizfty.anchon.dsh.hostbridge.web;

import com.bizfty.anchon.dsh.hostbridge.json.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 官方 client 静态产物清单与 boot 注入（对齐 packages/client/modules/src/index.ts
 * {@code bootInjections} / {@code compose} 语义）。
 *
 * <ul>
 *   <li>扫描 {@code pluginsRoot}（仓库 packages/client）下每个声明
 *       {@code dsh.client} 且有 {@code lib/client.js} 产物的 workspace 包 → entry 表
 *       （id=包名、url=/plugins/&lt;id&gt;/client.js、inject/immediately 取自 package.json）。</li>
 *   <li>{@link #renderIndex} 把 dist/index.html 变形为官方 served 形态：head 后插
 *       ModuleLoader queue 内联 script 与 bootstrap script-src，其后插
 *       {@code __DSH_BOOT__} global（graph），尾注 {@code __DSH_BOOT_READY__} resolve。</li>
 * </ul>
 */
public final class OfficialWebAssets {

    /** 引导包（其 client.js 先于一切注册，queue.create 由此自举）。 */
    public static final String CLIENT_MODULES_ID = "@deepseek-ai/dsh-client-modules";
    private static final String BOOT_KEY = "__DSH_BOOT__";
    private static final String READY_MARKUP =
            "<script>(globalThis.__DSH_BOOT_READY__ ??= Promise.withResolvers()).resolve()</script>";

    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Map<String, Path> bundles = new LinkedHashMap<>(); // id → client.js
    private final String revision;

    public OfficialWebAssets(Path pluginsRoot) {
        scan(pluginsRoot);
        this.revision = Integer.toHexString(System.identityHashCode(entries));
    }

    /** 一个插件行（package.json dsh.client + 产物路径）。 */
    public record Entry(String id, String url, List<String> inject, boolean immediately) {
    }

    /** 产物全路径（bundle 伺服用）。 */
    public Path bundlePath(String id) {
        return bundles.get(id);
    }

    public List<Entry> entries() {
        return List.copyOf(entries.values());
    }

    public String revision() {
        return revision;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /**
     * 递归扫描 workspace 根（external/deepseek/packages）下所有含
     * {@code lib/client.js} 产物的包：声明 {@code dsh.client} 的插件行读
     * inject/immediately；无声明的底层行（connection/runtime/locale 等被插件
     * external require）也收录且 immediately=true（boot 先全部注册，create 时
     * factory 的 require 才不 miss）。跳过 node_modules/tests 等目录。
     */
    private void scan(Path packagesRoot) {
        if (packagesRoot == null || !Files.isDirectory(packagesRoot)) {
            return;
        }
        try {
            List<Path> collected = new ArrayList<>();
            walk(packagesRoot, collected, 0);
            collected.sort(Path::compareTo);
            for (Path pkgDir : collected) {
                Path clientJs = pkgDir.resolve("lib").resolve("client.js");
                if (!Files.isRegularFile(clientJs)) continue;
                JsonNode meta = null;
                Path pkgJson = pkgDir.resolve("package.json");
                if (Files.isRegularFile(pkgJson)) {
                    meta = Json.parse(Files.readString(pkgJson, StandardCharsets.UTF_8));
                }
                // id：package.json name，或从 bundle 头部 __ModuleLoader__.load({id:…}) 提取
                String id = meta == null ? null : meta.path("name").asText(null);
                if (id == null || id.isEmpty()) {
                    String head = Files.readString(clientJs, StandardCharsets.UTF_8);
                    int at = head.indexOf("id: \"");
                    if (at == -1) at = head.indexOf("id:\"");
                    if (at != -1) {
                        int q = head.indexOf('"', at);
                        if (q != -1) {
                            int end = head.indexOf('"', q + 1);
                            if (end != -1) id = head.substring(q + 1, end);
                        }
                    }
                }
                if (id == null || id.isEmpty()) continue;
                boolean hasClientDecl = meta != null && meta.path("dsh").has("client");
                List<String> inject = new ArrayList<>();
                if (hasClientDecl) {
                    JsonNode injectNode = meta.path("dsh").path("client").path("inject");
                    if (injectNode.isArray()) {
                        for (JsonNode one : injectNode) inject.add(one.asText());
                    }
                }
                boolean immediately = !hasClientDecl
                        || meta.path("dsh").path("client").path("immediately").asBoolean(false);
                entries.put(id, new Entry(id, "/plugins/" + id + "/client.js", inject, immediately));
                bundles.put(id, clientJs);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 深度优先收集候选包目录（含 package.json 或 lib/client.js，后者覆盖
     * runtime 这类无清单但产物在场的包）；跳过 node_modules/.git/tests/lib/target。
     */
    private void walk(Path dir, List<Path> found, int depth) throws IOException {
        if (depth > 6) return;
        try (Stream<Path> children = Files.list(dir)) {
            for (Path child : children.sorted().toList()) {
                String name = child.getFileName().toString();
                if (name.equals("node_modules") || name.equals(".git") || name.equals("tests")
                        || name.equals("lib") || name.equals("target") || name.startsWith(".")) {
                    continue;
                }
                if (!Files.isDirectory(child)) continue;
                if (Files.isRegularFile(child.resolve("package.json"))
                        || Files.isRegularFile(child.resolve("lib").resolve("client.js"))) {
                    found.add(child);
                    continue; // 包目录不再下钻
                }
                walk(child, found, depth + 1);
            }
        }
    }

    /** __DSH_BOOT__ 值（graph）：{rev, entries:[{id,url,rev?,inject,immediately?}]}。 */
    public ObjectNode manifestJson() {
        ObjectNode graph = Json.object();
        graph.put("rev", revision);
        ArrayNode rows = graph.putArray("entries");
        for (Entry entry : entries.values()) {
            ObjectNode row = rows.addObject();
            row.put("id", entry.id());
            row.put("url", entry.url());
            row.put("rev", revision);
            ArrayNode inject = row.putArray("inject");
            for (String name : entry.inject()) inject.add(name);
            if (entry.immediately()) row.put("immediately", true);
        }
        return graph;
    }

    /**
     * 把原始 index.html 渲染为 served 形态（injections.ts {@code renderIndexInjections}
     * 的 Java 等价：head rows 紧跟 {@code <head>}，ready 尾注在 body 末尾）。
     *
     * <p>默认注入 {@code __DSH_BOOT__} + ready（8/18 起的 dist：client-modules 自装
     * {@code __ModuleLoader__}，预装 queue 会触发 "double boot"）。
     *
     * @param html      静态 dist/index.html
     * @param queueBoot 源码 HEAD 产物模式：预装 ModuleLoader queue + bootstrap script-src
     * @return 注入后的 html
     */
    public String renderIndex(String html, boolean queueBoot) {
        StringBuilder head = new StringBuilder();
        if (queueBoot) {
            // 1) ModuleLoader queue 内联 script（bootInjections 的 queue 常量；create 从
            //    pendingQueue 取 CLIENT_MODULES_ID 注册自举 createClientModuleSystem）。
            head.append("<script>").append(queueScript()).append("</script>");
            // 2) bootstrap 阻塞 script-src：client-modules 先注册进 queue
            head.append("<script src=\"/plugins/").append(CLIENT_MODULES_ID).append("/client.js\"></script>");
        }
        // 3) __DSH_BOOT__ global（值 JSON 转义 < 防跳出 script 元素）
        head.append("<script>globalThis[").append(jsString(BOOT_KEY)).append("] = ")
                .append(Json.write(manifestJson()).replace("<", "\\u003c"))
                .append("</script>");

        String out = spliceAfterHead(html, head.toString());
        return spliceBeforeBodyEnd(out, READY_MARKUP);
    }

    /** 默认：只注入 __DSH_BOOT__ + ready（当前伺服 dist 的 boot 面）。 */
    public String renderIndex(String html) {
        return renderIndex(html, false);
    }

    /** bootInjections 的 queue 常量文本（window.__ModuleLoader__ queue facade）。 */
    static String queueScript() {
        return "(globalThis.__ModuleLoader__ ??= {mode:\"queue\",pendingQueue:[],"
                + "load(registration){this.pendingQueue.push(registration)},"
                + "create(options){if(this.mode!==\"queue\")throw new Error(\"client-modules: create called after module-system boot\");"
                + "const pending=this.pendingQueue;"
                + "const index=pending.findIndex(r=>r.id===" + jsString(CLIENT_MODULES_ID) + ");"
                + "const registration=pending[index];if(registration===undefined)throw new Error(\"client-modules: HTML did not preload "
                + CLIENT_MODULES_ID + "/client.js\");"
                + "pending.splice(index,1);"
                + "const exports=registration.factory(specifier=>{throw new Error(\"client-modules: "
                + CLIENT_MODULES_ID + "/client.js requested external \\\"\"+specifier+\"\\\" before the module system existed\")});"
                + "if(typeof exports!==\"object\"||exports===null||typeof exports.createClientModuleSystem!==\"function\"){"
                + "throw new Error(\"client-modules: bootstrap bundle did not export the bootstrap module face\")}"
                + "return exports.createClientModuleSystem(this,{id:registration.id,exports},options)}"
                + "})";
    }

    private static String jsString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String spliceAfterHead(String html, String markup) {
        int open = html.toLowerCase().indexOf("<head");
        if (open == -1) return markup + html;
        int close = html.indexOf('>', open);
        return html.substring(0, close + 1) + markup + html.substring(close + 1);
    }

    private static String spliceBeforeBodyEnd(String html, String markup) {
        int close = html.toLowerCase().lastIndexOf("</body>");
        if (close == -1) return html + markup;
        return html.substring(0, close) + markup + html.substring(close);
    }
}
