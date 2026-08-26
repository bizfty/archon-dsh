package com.bizfty.anchon.dsh.session;

/**
 * 工作区根目录配置（部署布局固定化）。
 * <p>
 * 生产部署约定：
 * <ul>
 *   <li>{@code /data/anchon/workspace} — 固定工作区根：应用启动时自动创建并注册为默认工作区，
 *       前端冷启动自动连接，用户零操作即可使用（对齐官方「先选工作目录」语义的固定目录形态）。</li>
 *   <li>{@code /data/anchon/code} — 应用源码/代码根：目录浏览的快捷入口之一（可作工作区采纳来源）。</li>
 * </ul>
 * 均可通过环境变量覆盖：{@code DSH_WORKSPACE_ROOT} / {@code DSH_CODE_ROOT}。
 * <p>
 * 注意：{@code dsh.*} 前缀在 harness 环境可能被系统属性污染（见 AgentProperties），
 * 故此处不使用类级 {@code @ConfigurationProperties}，而由配置类以 {@code @Value}
 * 直接绑定环境变量（默认值内置），yml 中的 {@code dsh.workspace.*} 仅作文档化。
 */
public class WorkspaceProperties {

    /** 固定工作区根（realpath 规范化前的原始配置值）。 */
    private final String root;

    /** 应用源码根（目录浏览快捷入口）。 */
    private final String codeRoot;

    /** 启动时是否自动初始化固定工作区（默认 true；false 可关闭，保留手动添加流程）。 */
    private final boolean initEnabled;

    public WorkspaceProperties(String root, String codeRoot, boolean initEnabled) {
        this.root = root;
        this.codeRoot = codeRoot;
        this.initEnabled = initEnabled;
    }

    public String getRoot() {
        return root;
    }

    public String getCodeRoot() {
        return codeRoot;
    }

    public boolean isInitEnabled() {
        return initEnabled;
    }
}
