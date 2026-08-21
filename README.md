# anchon — DSH 能力复刻（Spring AI + Spring Boot）

参考 [`external/deepseek`](external/deepseek)（DeepSeek Harness，TypeScript）的能力面，
用 **Spring AI 2.0 + Spring Boot 4.1.0** 在 Java 中复刻的可运行 agent harness。
[`external/javaai`](external/javaai) 提供 Spring AI 用法的单应用参考实现。

**状态**：40 个构建单元、83 个模型可用工具、268 个单元测试 + 5 个真实 DeepSeek API 集成测试，
`mvn -o clean install` 全绿；核心目标（agent-loop 闭环 + 工具管线 + 会话 + 提示组装 +
SSE/OpenAI 兼容 + 目标能力清单 + 用户 profile 管理）已完成并全程真实 API 验证。

## 架构

```
┌─ 接入面 ──────────────────────────────────────────────────────────────┐
│ dsh-api  REST/SSE/OpenAI 兼容/审批/问答/反馈/凭据/设置/检索  ·  dsh-sdk JSON-RPC stdio │
├─ 控制面 ──────────────────────────────────────────────────────────────┤
│ dsh-agent  AgentLoop（手动 model→tool→model：并行/重试/限制/压缩/作用域/标题） │
│ dsh-interaction 审批(fail-closed+SSE联动)/ask-user/权限预设  ·  dsh-guard 重复提醒 │
│ dsh-compaction 历史压缩  ·  dsh-sandbox 沙箱门控  ·  dsh-schedule 提醒   │
├─ 能力 ────────────────────────────────────────────────────────────────┤
│ dsh-fs dsh-shell(+持久终端) dsh-skill dsh-plan dsh-todo dsh-subagent   │
│ dsh-search(搜索/抓取) dsh-jobs(后台任务) dsh-context dsh-code-runtime(Code Mode)│
│ dsh-browser(Playwright) dsh-github dsh-postgres dsh-mysql dsh-mcp dsh-lsp│
├─ 数据面 ──────────────────────────────────────────────────────────────┤
│ dsh-session(JPA) dsh-storage(JSON文件) dsh-settings dsh-credentials    │
│ dsh-identity dsh-feedback dsh-llm dsh-tool(管线) dsh-core dsh-util     │
└───────────────────────────────────────────────────────────────────────┘
```

## 模块（40 个）

| 模块 | 职责 | 对应 DSH 组 |
|---|---|---|
| `dsh-util` | Jackson 3 JSON 工具 | `util/*` |
| `dsh-core` | 会话/消息/Agent 模型、事件总线、system-prompt 有序段组装 | `core/session` `core/system-prompt` |
| `dsh-tool` | 工具注册表 + 执行管线（门控/超时/后处理/事件）+ ToolCallback 适配 | `core/tools` |
| `dsh-todo`/`dsh-plan` | `todo_write`（storage 持久化）· 计划模式 + `exit_plan_mode` | `todo/*` `plan/*` |
| `dsh-fs` | `read_file`/`write_file`/`glob`/`grep` + 沙箱路径策略 | `fs/*` |
| `dsh-shell` | `bash`/`bash_persistent`（长驻 shell）+ 受管环境变量 | `shell/*` `subprocess/*` `terminal/*` |
| `dsh-skill` | SKILL.md 技能 + 目录段 | `skill/*` |
| `dsh-session` | JPA 持久化 + 消息投影 + 关键词检索 + 标题 | `session/*` |
| `dsh-llm` | LlmGateway + DeepSeek(OpenAI 兼容) 适配 | `llm/*` |
| `dsh-agent` | AgentLoop（并行/重试/限制/压缩/作用域/标题/调优） | `core/agent-loop` |
| `dsh-interaction` | 审批(fail-closed)/ask-user/权限预设 | `interaction/*` |
| `dsh-guard`/`dsh-compaction`/`dsh-sandbox`/`dsh-schedule` | 守卫/压缩/沙箱/提醒 | `guard/*` `compaction/*` `sandbox/*` `schedule/*` |
| `dsh-subagent` | 子代理（深度守卫 + 持久子会话） | `subagent/*` |
| `dsh-context`/`dsh-jobs` | 指令/时间上下文 · 后台任务 | `context/*` `jobs/*` |
| `dsh-mcp` | MCP 客户端桥（stdio/SSE + 凭据头） | `mcp/*` |
| `dsh-sdk` | JSON-RPC stdio 服务器（另一进程驱动） | `sdk/*` |
| `dsh-credentials`/`dsh-storage`/`dsh-settings`/`dsh-identity`/`dsh-feedback` | 凭据/存储/设置/身份/反馈 | `credentials/*` `storage/*` `settings/*` `identity/*` `feedback/*` |
| `dsh-lsp`/`dsh-code-runtime` | LSP 缝 · Code Mode（JS/Python 双语言） | `lsp/*` `code-runtime/*` |
| `dsh-user` | 用户 profile 管理：注册/登录(token)/LLM 配置/API key(凭据缝) | `user/*` |
| `dsh-goal` | 持久化 same-session 目标：create/update/get 工具 + CAS + prompt 注入 | `goal/*` |
| `dsh-hooks` | 外部 shell hook 桥：hooks.json（PreToolUse/PostToolUse）→ 工具管线门控 | `hooks/*` |
| `dsh-workflow` | `workflow` 工具：模型编写 JS 编排脚本扇出 subagent 并返回最终值 | `workflow/*` |
| `dsh-web` | **前端 UI（TypeScript）**：对话/SSE 流式/目标视图，布局参考 javaai 与 deepseek；Maven 构建经 exec 插件执行 tsc，产物输出到 dsh-boot 静态资源 | `web/*` |
| `dsh-browser`/`dsh-github`/`dsh-postgres`/`dsh-mysql`/`dsh-search` | 浏览器/GitHub/DB/联网能力 | `browser/*` `github/*` `postgres/*` `mysql/*` `web/*` |
| `dsh-api`/`dsh-boot` | REST/SSE/OpenAI 兼容 + 可运行应用 | `api/gateway` `boot/*` |

## 快速开始

```bash
mvn -o clean install                                   # 全量构建 + 测试（268 单测）；dsh-web 经 exec 插件自动执行 vite build（Vue3+Element Plus）
OPENAI_API_KEY=sk-xxx mvn -o -pl dsh-boot spring-boot:run    # 启动（H2 内存库）
# 或：mvn -o -pl dsh-boot package && java -jar dsh-boot/target/dsh-boot-0.0.1-SNAPSHOT.jar
```

- **前端（Vue 3 SFC + Vite + Element Plus）**：启动后浏览器访问 `http://localhost:8080/`
  （会话列表 + SSE 流式对话 + 目标视图）；源码在 `dsh-web/src/main/webapp/`，
  `npm install` 装依赖（联网一次，之后离线可用）→ `npm run build`（vite）产物进 **dsh-web jar**，
  dsh-boot 经 Maven 依赖引入，Spring Boot 从 classpath 服务（无需拷贝文件进 dsh-boot）
- 真实 API 集成测试（key 门控，无 key 自跳过）：`DEEPSEEK_API_KEY=sk-xxx mvn -o -pl dsh-boot test -Dtest=DeepSeekE2ETest`
- 自动化驱动（JSON-RPC stdio）：`java -jar ... --dsh.sdk.stdin-server.enabled=true`
- 完整演示命令见 [docs/DEMO.md](docs/DEMO.md)

## 接口

| 端点 | 说明 |
|---|---|
| `POST /api/sessions` · `GET /api/sessions` | 会话 CRUD |
| `POST /api/sessions/{id}/chat` · `/chat/stream`(SSE) | 对话（JSON / 流式：message/tool/approval_requested/done 事件）；消息为 `/compact` 时触发手动压缩（不经过模型） |
| `POST /v1/chat/completions` | OpenAI 兼容（JSON + SSE）；带 `X-Auth-Token` 时：未指定 model 回退用户 profile 模型、用户配置了 LLM key 则按用户 key 路由 |
| `GET /api/interactions/approvals/pending` + `POST .../approve\|reject` | 审批应答 |
| `GET /api/interactions/questions/pending` + `POST .../answer` | 问答应答 |
| `POST/GET /api/feedback` · `GET/PUT /api/settings/{ns}/{key}` · `GET/POST/DELETE /api/credentials` | 反馈 / 设置 / 凭据 |
| `POST /api/users/register\|login\|logout` · `GET /api/users/me` · `PUT /api/users/me/llm` | 用户注册/登录/登出/我的 profile/LLM 配置（`X-Auth-Token`；可选 `dsh.api.auth.enabled=true` 开启全站鉴权） |
| `GET/POST/PUT /api/goals` · `GET /api/sessions/query?keyword=` · `GET /healthz` | 目标（查询/创建/更新）· 会话检索 / 健康检查 |

配置（环境变量）：`OPENAI_API_KEY` / `OPENAI_BASE_URL`（默认 https://api.deepseek.com）/
`OPENAI_MODEL`（默认 deepseek-chat）/ `DSH_DB_URL` / `DSH_AGENT_MODEL`；
属性：`dsh.storage.dir`（默认 `./data`，用户/凭据等 JSON 持久化目录）、
`dsh.api.auth.enabled`（默认 false，true 时全站要求 `X-Auth-Token`）、
`dsh.hooks.config-file`（默认 `./hooks.json`，Claude Code 风格外部 hook 配置）。

## 文档

- [DSH → Java 映射文档](docs/DSH_JAVA_MAPPING.md) — 模块映射、设计决策、真实 e2e 记录、路线图
- [能力映射草稿](docs/capability-map-draft.md) — DSH 全部能力组逐包分析
- [真实 API 演示](docs/DEMO.md) — 从建会话到各能力工具的完整演示