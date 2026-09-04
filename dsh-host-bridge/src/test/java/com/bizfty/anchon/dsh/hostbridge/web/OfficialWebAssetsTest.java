package com.bizfty.anchon.dsh.hostbridge.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 官方产物扫描与 boot 注入（manifest/ModuleLoader queue/ready）单测。
 */
class OfficialWebAssetsTest {

    @TempDir
    Path tmp;

    private Path writePlugin(String dir, String name, String inject, boolean immediately) throws IOException {
        Path pkg = tmp.resolve(dir);
        Files.createDirectories(pkg.resolve("lib"));
        String meta = "{\"name\":\"" + name + "\",\"dsh\":{\"client\":{\"platform\":\"web\""
                + (inject == null ? "" : ",\"inject\":[\"" + inject + "\"]")
                + (immediately ? ",\"immediately\":true" : "") + "}}}";
        Files.writeString(pkg.resolve("package.json"), meta);
        Files.writeString(pkg.resolve("lib").resolve("client.js"), "window.__ModuleLoader__.load({id:" + name + "})");
        return pkg;
    }

    @Test
    void scansDshClientPackagesWithBundles() throws IOException {
        writePlugin("ui-settings", "@deepseek-ai/dsh-client-ui-settings", null, false);
        writePlugin("ui-session", "@deepseek-ai/dsh-client-ui-session", "@deepseek-ai/dsh-client-ui-settings", true);
        // 无 dsh.client 声明的包被跳过
        Files.writeString(tmp.resolve("package.json"),
                "{\"name\":\"@deepseek-ai/dsh-plain\"}");
        Files.createDirectories(tmp.resolve("plain/lib"));
        Files.writeString(tmp.resolve("plain/lib/client.js"), "x");

        OfficialWebAssets assets = new OfficialWebAssets(tmp);
        assertEquals(2, assets.entries().size());
        assertNotNull(assets.bundlePath("@deepseek-ai/dsh-client-ui-settings"));
        OfficialWebAssets.Entry session = assets.entries().stream()
                .filter(e -> e.id().equals("@deepseek-ai/dsh-client-ui-session")).findFirst().orElseThrow();
        assertEquals("/plugins/@deepseek-ai/dsh-client-ui-session/client.js", session.url());
        assertTrue(session.immediately());
        assertEquals(1, session.inject().size());

        var manifest = assets.manifestJson();
        assertEquals(2, manifest.path("entries").size());
        tools.jackson.databind.JsonNode settingsRow = null;
        for (tools.jackson.databind.JsonNode row : manifest.path("entries")) {
            if (row.path("id").asText().equals("@deepseek-ai/dsh-client-ui-settings")) {
                settingsRow = row;
                break;
            }
        }
        assertNotNull(settingsRow);
        assertFalse(settingsRow.has("immediately"));
    }

    @Test
    void renderIndexDefaultInjectsBootGlobalAndReadyWithoutQueue() {
        OfficialWebAssets assets = new OfficialWebAssets(tmp);
        String html = "<!doctype html><html><head><title>t</title></head>"
                + "<body><div id=\"root\"></div></body></html>";
        String out = assets.renderIndex(html);

        // 默认（当前 dist boot 面）：只注入 __DSH_BOOT__ + ready，不预装 __ModuleLoader__
        assertFalse(out.contains("__ModuleLoader__"), out);
        assertTrue(out.contains("globalThis[\"__DSH_BOOT__\"]"), out);
        assertTrue(out.contains("__DSH_BOOT_READY__"), out);
        assertTrue(out.indexOf("__DSH_BOOT_READY__") < out.indexOf("</body>"));
        assertTrue(out.indexOf("<div id=\"root\"></div>") < out.indexOf("__DSH_BOOT_READY__"));
    }

    @Test
    void renderIndexQueueModeInjectsLoaderFacade() {
        OfficialWebAssets assets = new OfficialWebAssets(tmp);
        String html = "<!doctype html><html><head><title>t</title></head>"
                + "<body><div id=\"root\"></div></body></html>";
        String out = assets.renderIndex(html, true);

        assertTrue(out.contains("__ModuleLoader__"), out);
        assertTrue(out.contains("@deepseek-ai/dsh-client-modules/client.js"), out);
        assertTrue(out.indexOf("__ModuleLoader__") > out.indexOf("<head>"));
    }
}
