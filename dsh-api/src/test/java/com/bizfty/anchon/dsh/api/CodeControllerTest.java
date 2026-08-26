package com.bizfty.anchon.dsh.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.beans.factory.annotation.Value;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CodeController 场景测试：
 * coder（默认根目录 data/workspace/coder/project 下项目选择）、self（源码根目录完整读写）、
 * 越界防护、项目类型检测、源码根识别与包链合并（短包名展示）。
 */
class CodeControllerTest {

    @TempDir
    Path coderRoot;
    @TempDir
    Path selfRoot;

    private CodeController controller;

    @BeforeEach
    void setUp() {
        controller = new CodeController();
        ReflectionTestUtils.setField(controller, "coderRootProp", coderRoot.toString());
        ReflectionTestUtils.setField(controller, "selfRootProp", selfRoot.toString());
    }

    // ---- 树查找辅助 ----

    @SuppressWarnings("unchecked")
    private static Map<String, Object> findNode(Map<String, Object> root, String path) {
        if (root == null) return null;
        if (path.equals(root.get("path"))) return root;
        Object childrenObj = root.get("children");
        if (!(childrenObj instanceof List<?> children)) return null;
        for (Object c : children) {
            Map<String, Object> hit = findNode((Map<String, Object>) c, path);
            if (hit != null) return hit;
        }
        return null;
    }

    // ---- coder 场景 ----

    @Test
    void coderListProjectsInitiallyEmpty() throws Exception {
        List<Map<String, Object>> projects = controller.listProjects(CodeController.SCENE_CODER);
        assertNotNull(projects);
        assertTrue(projects.isEmpty(), "空根目录不应有项目");
    }

    @Test
    void coderCreateProjectThenList() throws Exception {
        ResponseEntity<Map<String, Object>> resp = controller.createProject(Map.of("name", "alpha"), CodeController.SCENE_CODER);
        assertTrue(resp.getStatusCode().is2xxSuccessful(), "创建项目应成功: " + resp.getBody());
        assertTrue(Files.isDirectory(coderRoot.resolve("alpha")));

        List<Map<String, Object>> projects = controller.listProjects(CodeController.SCENE_CODER);
        assertEquals(1, projects.size());
        assertEquals("alpha", projects.get(0).get("name"));
        assertEquals(coderRoot.toAbsolutePath().normalize().toString(), projects.get(0).get("root"),
                "项目应携带 coder 根目录绝对路径（供前端拼工作区路径）");
    }

    @Test
    void coderCreateProjectRejectsPathTraversalName() {
        assertThrows(IllegalArgumentException.class,
                () -> controller.createProject(Map.of("name", "../evil"), CodeController.SCENE_CODER));
        assertThrows(IllegalArgumentException.class,
                () -> controller.createProject(Map.of("name", "a/b"), CodeController.SCENE_CODER));
    }

    @Test
    void coderTreeExcludesBuildAndHiddenDirs() throws Exception {
        Files.createDirectories(coderRoot.resolve("proj/src/main/java"));
        Files.writeString(coderRoot.resolve("proj/src/main/java/A.java"), "class A {}");
        Files.createDirectories(coderRoot.resolve("proj/target"));
        Files.writeString(coderRoot.resolve("proj/target/x.class"), "x");
        Files.createDirectories(coderRoot.resolve("proj/.hidden"));
        Files.writeString(coderRoot.resolve("proj/.hidden/s.txt"), "s");

        ResponseEntity<Map<String, Object>> resp = controller.getTree("proj", CodeController.SCENE_CODER);
        assertTrue(resp.getStatusCode().is2xxSuccessful());
        Map<String, Object> root = resp.getBody();
        assertEquals("proj", root.get("name"));
        List<Map<String, Object>> children = (List<Map<String, Object>>) root.get("children");
        List<String> names = children.stream().map(n -> (String) n.get("name")).toList();
        assertTrue(names.contains("src"), "应包含 src: " + names);
        assertFalse(names.contains("target"), "应排除 target: " + names);
        assertFalse(names.contains(".hidden"), "应排除隐藏目录: " + names);
    }

    @Test
    void coderFileRoundTrip() throws Exception {
        controller.createProject(Map.of("name", "proj"), CodeController.SCENE_CODER);

        // 保存
        ResponseEntity<Map<String, Object>> save = controller.saveFile(
                Map.of("project", "proj", "path", "src/Hello.java", "content", "class Hello {}"),
                CodeController.SCENE_CODER);
        assertTrue(save.getStatusCode().is2xxSuccessful(), "保存失败: " + save.getBody());
        assertTrue(Files.exists(coderRoot.resolve("proj/src/Hello.java")));

        // 读取
        Map<String, Object> read = controller.readFile("proj", "src/Hello.java", CodeController.SCENE_CODER);
        assertEquals("class Hello {}", read.get("content"));
        assertEquals(1L, read.get("lines"));

        // 新建（不覆盖）
        ResponseEntity<Map<String, Object>> create = controller.createFile(
                Map.of("project", "proj", "path", "src/B.java"), CodeController.SCENE_CODER);
        assertTrue(create.getStatusCode().is2xxSuccessful());
        ResponseEntity<Map<String, Object>> dup = controller.createFile(
                Map.of("project", "proj", "path", "src/B.java"), CodeController.SCENE_CODER);
        assertTrue(dup.getStatusCode().is4xxClientError(), "重复新建应失败");

        // 删除
        ResponseEntity<Map<String, Object>> del = controller.deleteFile("proj", "src/B.java", CodeController.SCENE_CODER);
        assertTrue(del.getStatusCode().is2xxSuccessful());
        assertFalse(Files.exists(coderRoot.resolve("proj/src/B.java")));
    }

    @Test
    void coderPathEscapeRejected() throws Exception {
        controller.createProject(Map.of("name", "proj"), CodeController.SCENE_CODER);
        assertThrows(IllegalArgumentException.class,
                () -> controller.readFile("proj", "../secret.txt", CodeController.SCENE_CODER));
        assertThrows(IllegalArgumentException.class,
                () -> controller.saveFile(Map.of("project", "proj", "path", "a/../../x", "content", "x"),
                        CodeController.SCENE_CODER));
    }

    @Test
    void coderRootDefaultValuePointsToNewWorkspace() throws Exception {
        Field f = CodeController.class.getDeclaredField("coderRootProp");
        Value v = f.getAnnotation(Value.class);
        assertNotNull(v, "coderRootProp 应有 @Value 注解");
        assertEquals("${dsh.coder.root:data/workspace/coder/project}", v.value(),
                "coder 默认根目录应为 data/workspace/coder/project");
    }

    // ---- 项目类型检测 ----

    @Test
    void detectProjectTypeByMarkerFile() throws Exception {
        // maven
        Path proj = coderRoot.resolve("mvn-proj");
        Files.createDirectories(proj);
        Files.writeString(proj.resolve("pom.xml"), "<project/>");
        List<Map<String, Object>> projects = controller.listProjects(CodeController.SCENE_CODER);
        Map<String, Object> mvn = projects.stream().filter(p -> p.get("name").equals("mvn-proj")).findFirst().orElseThrow();
        assertEquals("maven", mvn.get("projectType"));

        // node
        Path nodeProj = coderRoot.resolve("node-proj");
        Files.createDirectories(nodeProj);
        Files.writeString(nodeProj.resolve("package.json"), "{}");
        Map<String, Object> node = controller.listProjects(CodeController.SCENE_CODER).stream()
                .filter(p -> p.get("name").equals("node-proj")).findFirst().orElseThrow();
        assertEquals("node", node.get("projectType"));

        // generic
        Path genProj = coderRoot.resolve("gen-proj");
        Files.createDirectories(genProj);
        Map<String, Object> gen = controller.listProjects(CodeController.SCENE_CODER).stream()
                .filter(p -> p.get("name").equals("gen-proj")).findFirst().orElseThrow();
        assertEquals("generic", gen.get("projectType"));
    }

    // ---- 包链合并（项目类型感知的文件树）----

    @Test
    void mavenTreeCollapsesDeepPackageChain() throws Exception {
        Path proj = coderRoot.resolve("mvn-app");
        Files.createDirectories(proj.resolve("src/main/java/com/bizfty/anchon/dsh/api"));
        Files.writeString(proj.resolve("src/main/java/com/bizfty/anchon/dsh/api/CodeController.java"), "class CodeController {}");
        Files.writeString(proj.resolve("src/main/java/com/bizfty/anchon/dsh/api/CodeService.java"), "class CodeService {}");
        Files.createDirectories(proj.resolve("src/main/resources"));
        Files.writeString(proj.resolve("src/main/resources/application.yml"), "server:\n  port: 8080\n");
        Files.writeString(proj.resolve("pom.xml"), "<project/>");

        ResponseEntity<Map<String, Object>> resp = controller.getTree("mvn-app", CodeController.SCENE_CODER);
        assertTrue(resp.getStatusCode().is2xxSuccessful());
        Map<String, Object> root = resp.getBody();
        assertEquals("maven", root.get("projectType"));

        // 结构目录保持可见：src → main → java（source-root）
        Map<String, Object> src = findNode(root, "src");
        assertNotNull(src, "src 结构目录应可见");
        assertEquals("structural", src.get("kind"));
        Map<String, Object> java = findNode(root, "src/main/java");
        assertNotNull(java, "src/main/java 源码根应可见");
        assertEquals("source-root", java.get("kind"));

        // 深层包链合并为一个 package 节点：短包名 api + 完整点分路径
        Map<String, Object> pkg = findNode(root, "src/main/java/com/bizfty/anchon/dsh/api");
        assertNotNull(pkg, "包链应合并为 package 节点");
        assertEquals("package", pkg.get("kind"));
        assertEquals("api", pkg.get("name"), "短包名展示（最后一段）");
        assertEquals("com.bizfty.anchon.dsh.api", pkg.get("package"), "完整点分包路径");
        List<Map<String, Object>> pkgChildren = (List<Map<String, Object>>) pkg.get("children");
        assertEquals(2, pkgChildren.size(), "包下应直接可见 java 文件");
        assertEquals("CodeController.java", pkgChildren.get(0).get("name"));

        // resources 源码根：直接可见配置文件
        Map<String, Object> res = findNode(root, "src/main/resources");
        assertNotNull(res, "src/main/resources 源码根应可见");
        assertTrue(((List<?>) res.get("children")).stream().anyMatch(c -> "application.yml".equals(((Map<?, ?>) c).get("name"))));
    }

    @Test
    void testSourceRootUsesPackageCollapse() throws Exception {
        Path proj = coderRoot.resolve("test-app");
        Files.createDirectories(proj.resolve("src/test/java/com/example/util"));
        Files.writeString(proj.resolve("src/test/java/com/example/util/HelperTest.java"), "class HelperTest {}");

        ResponseEntity<Map<String, Object>> resp = controller.getTree("test-app", CodeController.SCENE_CODER);
        assertTrue(resp.getStatusCode().is2xxSuccessful());
        Map<String, Object> root = resp.getBody();

        Map<String, Object> pkg = findNode(root, "src/test/java/com/example/util");
        assertNotNull(pkg);
        assertEquals("package", pkg.get("kind"));
        assertEquals("com.example.util", pkg.get("package"));
        assertEquals("util", pkg.get("name"), "短包名 = 最后一段");
    }

    @Test
    void genericDeepChainCollapsesToChainNode() throws Exception {
        Path proj = coderRoot.resolve("generic-app");
        Files.createDirectories(proj.resolve("a/b/c/d"));
        Files.writeString(proj.resolve("a/b/c/d/readme.txt"), "hi");

        ResponseEntity<Map<String, Object>> resp = controller.getTree("generic-app", CodeController.SCENE_CODER);
        assertTrue(resp.getStatusCode().is2xxSuccessful());
        Map<String, Object> root = resp.getBody();
        assertEquals("generic", root.get("projectType"));

        Map<String, Object> chain = findNode(root, "a/b/c/d");
        assertNotNull(chain, "普通单链目录应合并为 chain 节点");
        assertEquals("chain", chain.get("kind"));
        assertEquals("d", chain.get("name"), "短名 = 最后一段");
        assertEquals("a/b/c/d", chain.get("pathLabel"), "完整路径标签");
        assertTrue(((List<?>) chain.get("children")).stream().anyMatch(c -> "readme.txt".equals(((Map<?, ?>) c).get("name"))));
    }

    @Test
    void branchedPackageDoesNotMergeAcrossBranch() throws Exception {
        Path proj = coderRoot.resolve("branch-app");
        Files.createDirectories(proj.resolve("src/main/java/com/bizfty/anchon/dsh/api"));
        Files.createDirectories(proj.resolve("src/main/java/com/bizfty/anchon/dsh/core"));
        Files.writeString(proj.resolve("src/main/java/com/bizfty/anchon/dsh/api/A.java"), "class A {}");
        Files.writeString(proj.resolve("src/main/java/com/bizfty/anchon/dsh/core/C.java"), "class C {}");

        ResponseEntity<Map<String, Object>> resp = controller.getTree("branch-app", CodeController.SCENE_CODER);
        assertTrue(resp.getStatusCode().is2xxSuccessful());
        Map<String, Object> root = resp.getBody();

        // com/bizfty/anchon/dsh 仍是单链（各只有 1 个子目录），合并为一个 package 节点
        Map<String, Object> dsh = findNode(root, "src/main/java/com/bizfty/anchon/dsh");
        assertNotNull(dsh, "dsh 包节点应合并存在");
        assertEquals("package", dsh.get("kind"));
        assertEquals("com.bizfty.anchon.dsh", dsh.get("package"));

        // dsh 下出现分支（api / core），各自保持为独立 package 节点
        Map<String, Object> api = findNode(root, "src/main/java/com/bizfty/anchon/dsh/api");
        assertNotNull(api);
        assertEquals("package", api.get("kind"));
        assertEquals("api", api.get("name"));
        Map<String, Object> core = findNode(root, "src/main/java/com/bizfty/anchon/dsh/core");
        assertNotNull(core);
        assertEquals("core", core.get("name"));
    }

    // ---- self 场景 ----

    @Test
    void selfListProjectsReturnsSingleRoot() throws Exception {
        List<Map<String, Object>> projects = controller.listProjects(CodeController.SCENE_SELF);
        assertEquals(1, projects.size());
        assertEquals(CodeController.SELF_PROJECT, projects.get(0).get("name"));
        assertEquals(selfRoot.getFileName().toString(), projects.get(0).get("displayName"));
        assertEquals(selfRoot.toAbsolutePath().normalize().toString(), projects.get(0).get("root"),
                "self 项目应携带源码根绝对路径");
    }

    @Test
    void selfTreeReturnsSourceRoot() throws Exception {
        Files.createDirectories(selfRoot.resolve("dsh-core/src/main/java"));
        Files.writeString(selfRoot.resolve("dsh-core/src/main/java/X.java"), "class X {}");

        ResponseEntity<Map<String, Object>> resp = controller.getTree(CodeController.SELF_PROJECT, CodeController.SCENE_SELF);
        assertTrue(resp.getStatusCode().is2xxSuccessful());
        Map<String, Object> root = resp.getBody();
        assertEquals(selfRoot.getFileName().toString(), root.get("name"));
        assertTrue(((List<?>) root.get("children")).size() >= 1, "源码根应有子节点");
    }

    @Test
    void selfMultiModuleTreeShowsPackageNodes() throws Exception {
        // 多模块项目：src 在各模块下，包链合并逻辑同样生效
        Files.createDirectories(selfRoot.resolve("dsh-api/src/main/java/com/bizfty/anchon/dsh/api"));
        Files.writeString(selfRoot.resolve("dsh-api/src/main/java/com/bizfty/anchon/dsh/api/CodeController.java"), "class CodeController {}");
        Files.writeString(selfRoot.resolve("pom.xml"), "<project/>");

        ResponseEntity<Map<String, Object>> resp = controller.getTree(CodeController.SELF_PROJECT, CodeController.SCENE_SELF);
        assertTrue(resp.getStatusCode().is2xxSuccessful());
        Map<String, Object> root = resp.getBody();
        assertEquals("maven", root.get("projectType"), "self 根项目类型应被识别");

        Map<String, Object> pkg = findNode(root, "dsh-api/src/main/java/com/bizfty/anchon/dsh/api");
        assertNotNull(pkg, "多模块下包链应合并");
        assertEquals("package", pkg.get("kind"));
        assertEquals("com.bizfty.anchon.dsh.api", pkg.get("package"));
        assertTrue(((List<?>) pkg.get("children")).stream().anyMatch(c -> "CodeController.java".equals(((Map<?, ?>) c).get("name"))));
    }

    @Test
    void selfFileFullReadWrite() throws Exception {
        // 保存（覆盖写）
        ResponseEntity<Map<String, Object>> save = controller.saveFile(
                Map.of("project", CodeController.SELF_PROJECT, "path", "dsh-api/self-tmp.txt", "content", "hi"),
                CodeController.SCENE_SELF);
        assertTrue(save.getStatusCode().is2xxSuccessful(), "self 保存失败: " + save.getBody());
        assertEquals("hi", Files.readString(selfRoot.resolve("dsh-api/self-tmp.txt")));

        // 读取（project 参数被忽略，传任意值都落到 self 根）
        Map<String, Object> read = controller.readFile("whatever", "dsh-api/self-tmp.txt", CodeController.SCENE_SELF);
        assertEquals("hi", read.get("content"));

        // 新建
        ResponseEntity<Map<String, Object>> create = controller.createFile(
                Map.of("project", CodeController.SELF_PROJECT, "path", "dsh-api/self-new.txt"),
                CodeController.SCENE_SELF);
        assertTrue(create.getStatusCode().is2xxSuccessful());

        // 删除
        ResponseEntity<Map<String, Object>> del = controller.deleteFile(
                CodeController.SELF_PROJECT, "dsh-api/self-new.txt", CodeController.SCENE_SELF);
        assertTrue(del.getStatusCode().is2xxSuccessful());
        assertFalse(Files.exists(selfRoot.resolve("dsh-api/self-new.txt")));
        // 清理
        Files.deleteIfExists(selfRoot.resolve("dsh-api/self-tmp.txt"));
    }

    @Test
    void selfCreateProjectRejected() {
        ResponseEntity<Map<String, Object>> resp = controller.createProject(
                Map.of("name", "should-not-exist"), CodeController.SCENE_SELF);
        assertTrue(resp.getStatusCode().is4xxClientError(), "self 场景应拒绝创建项目");
        assertTrue(((String) resp.getBody().get("error")).contains("不支持"));
        assertFalse(Files.exists(selfRoot.resolve("should-not-exist")));
    }

    @Test
    void selfPathEscapeRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> controller.readFile(CodeController.SELF_PROJECT, "../outside.txt", CodeController.SCENE_SELF));
    }

    @Test
    void selfRootDefaultValueIsUserDir() throws Exception {
        Field f = CodeController.class.getDeclaredField("selfRootProp");
        Value v = f.getAnnotation(Value.class);
        assertNotNull(v, "selfRootProp 应有 @Value 注解");
        assertEquals("${dsh.self.root:${user.dir}}", v.value(),
                "self 默认根目录应为应用工作目录（archon-dsh 源码目录）");
    }

    // ---- 兼容性：深层文件在旧 MAX_TREE_DEPTH=6 下不可见，现在合并后可见 ----

    @Test
    void deepJavaFileVisibleAfterPackageCollapse() throws Exception {
        // 旧实现 MAX_TREE_DEPTH=6 时 src/main/java/com/bizfty/anchon/dsh/api/X.java 深度 8 被截断
        Path proj = coderRoot.resolve("deep-app");
        Files.createDirectories(proj.resolve("src/main/java/com/bizfty/anchon/dsh/api"));
        Files.writeString(proj.resolve("src/main/java/com/bizfty/anchon/dsh/api/X.java"), "class X {}");

        ResponseEntity<Map<String, Object>> resp = controller.getTree("deep-app", CodeController.SCENE_CODER);
        assertTrue(resp.getStatusCode().is2xxSuccessful());
        Map<String, Object> root = resp.getBody();
        Map<String, Object> pkg = findNode(root, "src/main/java/com/bizfty/anchon/dsh/api");
        assertNotNull(pkg, "深层包应合并可见");
        // 文件在包节点 children 下（旧实现深度 8 > MAX_TREE_DEPTH=6 被截断，Java 文件不可见）
        assertNotNull(findNode(root, "src/main/java/com/bizfty/anchon/dsh/api/X.java"), "深层 Java 文件应可见");
        assertTrue(((List<?>) pkg.get("children")).stream().anyMatch(c -> "X.java".equals(((Map<?, ?>) c).get("name"))));
    }
}
