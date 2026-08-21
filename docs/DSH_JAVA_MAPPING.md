# DSH → Java (Spring AI + Spring Boot) 复刻映射文档

> 目标：参考 [`external/deepseek`](../external/deepseek)（DeepSeek Harness，TypeScript）的能力面，
> 用 **Spring AI 2.0 + Spring Boot 4.1.0** 在 Java 中复刻一个可运行的 agent harness。
> 详细逐组分析见 [capability-map-draft.md](capability-map-draft.md)（子代理产出，含每包的
> 职责/扩展点/Java 映射建议与移植要点）。

## 1. 现状

- 17 个 Maven 模块全部可编译，核心闭环（agent-loop + 工具管线 + 会话持久化 + SSE/OpenAI 兼容 API）
  + 控制面能力（审批/问答/重复提醒/历史压缩）已实现并有单元测试覆盖。
- 模块划分镜像 DSH 能力组：

| Maven 模块 | 对应 DSH 组 | 状态 | 说明 |
|---|---|---|---|
| `dsh-util` | `util/*` | ✅ 已实现 | Jackson 3 封装（Boot 4 默认） |
| `dsh-core` | `core/session` `core/system-prompt` 事件面 | ✅ 已实现 | 会话/消息/Agent 模型、会话事件总线、system-prompt 有序段组装 |
| `dsh-tool` | `core/tools` | ✅ 已实现 | AgentTool SPI + ToolRegistry + ToolExecutionPipeline（pre-execute 门 → 执行[含超时] → post-execute → 事件） |
| `dsh-todo` | `todo/tool-todo` | ✅ 已实现 | `todo_write` 整表替换 |
| `dsh-plan` | `plan/plan-mode` | ✅ 已实现 | 计划模式状态 + `exit_plan_mode` + prompt 段 |
| `dsh-fs` | `fs/*` | ✅ 已实现 | `read_file`/`write_file`/`glob`/`grep` + 路径策略 |
| `dsh-shell` | `shell/*` `subprocess/*` | ✅ 已实现 | `bash` 每次新进程 + 超时杀进程树 |
| `dsh-skill` | `skill/*` | ✅ 已实现 | SKILL.md 扫描/frontmatter/`skill` 工具/目录段 |
| `dsh-session` | `session/session-persistence` `session-query` | ✅ 已实现（第十四轮增强） | JPA 持久化 + 消息投影（seq 单调）+ **会话关键词检索**（SessionQueryService，大小写不敏感，REST `GET /api/sessions/query`） |
| `dsh-agent`（标题） | `session/session-title-llm` | ✅ 已实现（第十四轮） | 首轮用户消息后辅助调用生成会话标题并持久化（失败忽略、可禁用、超长截断） |
| `dsh-jobs`（增强） | `jobs/tool-jobs` | ✅ 已实现（第十四轮） | 新增 `job_wait` 工具：等待后台任务完成并返回输出（start→status→wait 闭环） |
| `dsh-llm` | `llm/*` | ✅ 已实现（第一轮 + 第十八轮按用户 key 路由） | LlmGateway 抽象 + Spring AI ChatModel 适配（DeepSeek 兼容端点）；**按用户 API key 路由**：`call/stream(..., apiKey)` 非空 key 时用同 base-url 构建独立 OpenAiChatModel 并按 key 缓存（对应 DSH 每 agent/会话可配 apiKey），用户 profile 的 LLM key 经此注入 |
| `dsh-agent` | `core/agent-loop` | ✅ 已实现 | **手动** model→tool→model 循环（run + stream），turn/step 事件、压缩挂钩、additionalContexts 注入、**并行工具调用**（isConcurrencySafe 分类 + 有界池 + 顺序保持）、**多 agent 解析**（dsh.agents 配置按 id 查找，未知 id fail loud）、**per-agent 工具限制**（enabled/disabledTools 可见性过滤 + 硬调拒绝）、**turn 级模型调用重试**（退避 + 上限，流式已发 token 不重试）、**AgentScope 作用域**（额外段 + 可见性）、**作用域注册表**（第二十轮：`AgentScopeRegistry` 注册/遮蔽形态 — 同键 shadow、精确优先于通配、工具过滤 AND 收窄）、**per-agent LLM 凭据**（第二十一轮：`dsh.agents.*.credential-ref` 只落引用，agent-loop 每次调用前经 CredentialService 解析，请求级覆盖优先）、**settings 调优消费**（temperature/max-steps/并行上限经设置覆盖） |
| `dsh-interaction` | `interaction/*` | ✅ 已实现（第二轮） | 一次性审批（fail closed）+ `ask_user_question` + 权限预设（ApprovalGate 挂在管线 pre-execute） |
| `dsh-guard` | `guard/repeat-tool-reminder` | ✅ 已实现（第二轮） | 重复调用提醒（additionalContexts 注入；工具超时已在 dsh-tool 管线实现） |
| `dsh-compaction` | `compaction/compaction-basic` | ✅ 已实现（第二轮 + 第十九轮 shadow boundary + 第二十二轮工具结果截断 + 第二十三轮输出转存 + 第二十七轮手动压缩） | token 压力触发 + LLM 摘要（失败确定性回退）+ 保留尾部，摘要持久化为 USER 消息；**shadow boundary**：`CompactionBoundaryStore` 记录每会话遮蔽边界（绝对日志下标），压缩评估/压缩基于边界后有效历史，后续 turn 从边界起播、不再重发被摘要覆盖的旧头（对应 DSH surfaceOp replace 语义）；**工具结果截断**（对应 compaction/tool-result-pruner）：`ToolResultPruner` 在投影层把超阈值（默认 8192 字符）的工具结果截为 头+标记+尾（默认 4096+1024），回放安全（日志保留原文）、码点安全（不拆代理对），当前步结果保持完整；**工具输出转存**（对应 spill/spill-policy + spill-local）：`SpillService` 超限（默认 8192 字节）的纯文本工具结果保存到会话级文件（`dsh.spill.dir`），行内替换为 有界预览+定位符（含 read_file 取回指引），跳过 read_file 防 read→spill→read 循环，best-effort（保存失败保留原内容），替换产物永不超过上限；**手动压缩 `/compact`**（对应 command-compact）：`AgentLoopService.manualCompact` 低于自动阈值也压缩边界后有效历史并更新边界；REST chat/SSE 端点拦截 `/compact`（不经过模型、不写日志），`/compact <任何参数>` → `Usage: /compact (no arguments)`，无可压缩历史 → `No compactable history yet.` |
| `dsh-subagent` | `subagent/*` | ✅ 已实现（第三轮） | 进程内委托：`subagent`/`send_message`/`list_agents` 工具 + 深度守卫 + 持久子会话（continuable） |
| `dsh-context` | `context/*` | ✅ 已实现（第五轮） | time-context prompt 段（每 turn 注入当前时间）+ AGENTS.md/CLAUDE.md 工作区指令发现与注入（内容哈希去重、字节上限、父目录搜索） |
| `dsh-jobs` | `jobs/*` | ✅ 已实现（第六轮） | JobService 后台任务（所有者隔离、waitFor/kill 杀进程树、输出字节上限）+ `job_status`/`job_kill` 工具；bash 工具支持 `run_in_background` |
| `dsh-mcp` | `mcp/mcp-client` | ✅ 已实现（第七轮 + 第十五轮凭据） | 官方 MCP Java SDK 桥：stdio/SSE 传输、工具注册为 `mcp__server__tool`（纯函数命名）、schema 转换、call_tool 转发；**真实 stdio 传输集成测试通过**；**credentials 消费**：服务器 `credentialRef` → CredentialService 解析 → SSE `Authorization: Bearer` 头 |
| `dsh-sdk` | `sdk/protocol+server` | ✅ 已实现（第八轮） | JSON-RPC 2.0 行分隔协议 + stdio 服务器（initialize/session.prompt/session.list/session.messages/shutdown）；`--dsh.sdk.stdin-server.enabled=true` 启用；管道集成测试通过 |
| `dsh-credentials` | `credentials/*` | ✅ 已实现（第八轮） | 凭据引用缝（provider:key）：每操作解析、describe 永不带值、env provider + 内存覆盖；P1 消费者为 MCP/web provider 与 sandbox 升级 |
| `dsh-sandbox` | `sandbox/sandbox-policy` | ✅ 已实现（第十轮） | 每会话沙箱模式（read-only/workspace-write/danger-full-access）+ 预设；经 ToolContext 注入，fs 写与 bash 执行门控（策略栅栏） |
| `dsh-schedule` | `schedule/*` | ✅ 已实现（第十轮） | 会话内提醒：定时向会话日志写用户消息（durable，下次 turn 自然可见）+ `schedule_create`/`schedule_list` 工具 |
| `dsh-goal` | `goal/*` | ✅ 已实现（第二十四轮） | 持久化 same-session 目标：`Goal`（phase active/paused/blocked/complete + blockedCode/Reason + maxGoalRounds + roundsStarted + CAS revision）、`GoalService`（StorageService 落盘，create/current/update，CAS 精确 id+revision）；模型工具 `create_goal`/`update_goal`/`get_goal`（update 的 action=edit/pause/resume/complete/blocked，blocked 校验 lower-kebab-case code + 非空 reason）；`GoalPromptSection` 注入当前目标到 system prompt（无目标零贡献）；REST `GET/POST/PUT /api/goals`；**真实 e2e 通过（模型 create_goal → REST 查询 → 不调工具也能陈述自己的目标与 phase/maxGoalRounds → REST complete）** |
| `dsh-hooks` | `hooks/*` | ✅ 已实现（第二十五轮） | 外部 shell hook 桥（Claude Code 风格 `hooks.json`）：`HookConfig` 解析 PreToolUse/PostToolUse 事件 + matcher（前缀/`*`）；`HookRunner` 执行 hook 命令（stdin 事件 JSON + `CLAUDE_*` 环境变量，超时/非零退出/非 JSON 输出 → block 安全默认；ask → 映射为需询问的拒绝）；`HookGate`（PreToolUse，order=10）block 拒绝工具调用、`HookPostProcessor`（PostToolUse，order=90）best-effort；无配置文件直通；**真实 e2e 通过（hooks.json block `github_*` → 模型调 `github_list_repos` 被拒并可见理由；bash 未匹配直通）** |
| `dsh-workflow` | `workflow/tool-workflow` | ✅ 已实现（第二十六轮） | `workflow` 工具：执行模型编写的 JS 编排脚本（复用 code-runtime Node.js JSON-RPC 回环），脚本内经 `await tools.<name>(args)` 扇出任意管线工具（subagent/send_message/list_agents 等），`return` 最终值即工具结果；结果渲染有字符上限（`dsh.workflow.max-result-chars`，默认 50000，超长截断+提示）；**修复依赖环**：RunCodeTool/WorkflowTool 改经 `ObjectProvider<CodeRuntimeService>` 懒解析（其运行时链在 ToolRegistry 构造期 `getBeansOfType(AgentTool)` 中形成环被静默跳过 — 此前 run_code 也从未注册，现已注册）；**真实 e2e 通过（workflow 脚本扇出 subagent 子会话各自完成 turn；脚本 return 17*23+31*47=1848 正确返回）** |
| `dsh-web` | 前端 UI（Vue 3） | ✅ 已实现（第二十八~三十轮 Web Components + 第三十二轮 Vue3/Vite/Element Plus 重构） | **前端 UI 项目：Vue 3（SFC + Composition API）+ Vite + Element Plus**（vue 3.5 / element-plus 2.14 / vite 6，npm 联网装一次后离线可用）；布局参考 external/javaai（宰辅），输入框/交互模式参照 deepseek Web；**数据层**：`src/store.ts` Vue `reactive` 单例（组件直接读写自动响应）+ `api.ts`（REST/SSE）；**业务编排集中 App.vue**（对应 DSH runtime/service 层：loadSessions/openSession/send+SSE/Send-Stop(abort)/goal CAS），子组件经 `defineEmits` 派发（`@send/@stop/@select-session` 等）；**组件**：`Sidebar.vue`（el-input 搜索/会话列表/el-empty 空态）、`MessageList.vue`（欢迎页/消息流 marked+DOMPurify+highlight.js/工具折叠 v-html/流式光标，`watch` 自动滚底）、`Composer.vue`（**el-input textarea `autosize` 自动增长** + IME 组合保护 keyCode 229 + Enter 发送/Shift+Enter 换行/连发保护 + el-select 模型 + Send/Stop 按钮切换 + el-alert 错误）、`GoalView.vue`（el-form/el-tag/el-empty，CAS 管理）；**构建链**：npm install（联网一次）→ `npm run build`（vite，base `./`，outDir → `dsh-web/src/main/resources/static`）→ Maven exec-maven-plugin 调 build.sh → **dsh-web jar**，boot 经 Maven 依赖引入，Spring Boot classpath 服务 `/`（实测页面/bundle/API 200、SSE tool/message/done 链正常、目标 CRUD 正常）；全量 `mvn -o clean install` 268 单测 0 失败 |
| `dsh-browser` | `browser/*`（Playwright） | ✅ 已接入（用户新增 + 本轮接入验证） | Playwright 浏览器工具集 24 个（导航/点击/截图/表单/JS/会话）；已接入 boot 应用，**真实 browser_navigate + browser_get_text 端到端实测通过**（Chromium headless） |
| `dsh-github` | `github/*` | ✅ 已接入（用户新增 + 本轮接入验证 + 凭据接入） | GitHub 工具集 30 个（issue/PR/branch/commit/搜索/readme 等，基于 kohsuke GitHub API）；已接入 boot；**credentials 消费**：`dsh.github.credential-ref` 经 CredentialService 解析（回退明文 token） |
| `dsh-postgres` | `postgres/*` | ✅ 已接入（用户新增 + 本轮接入验证） | `postgres_query`/`postgres_describe_table`/`postgres_suggest_index`（惰性连接，动态连接信息）；已接入 boot；真实调用需可达的 PostgreSQL |
| `dsh-mysql` | `mysql/*` | ✅ 已接入（用户新增 + 本轮接入验证） | `mysql_query`/`mysql_describe_table`（只读 SELECT/WITH 保护、动态连接）；已接入 boot；真实调用需可达的 MySQL |
| `dsh-identity` | `identity/anonymous-user-id` | ✅ 已实现（第十一轮） | 匿名用户 id：首次生成 UUID 持久化到文件（`dsh.identity.file` 可配）、之后复用；遥测/反馈匿名身份 |
| `dsh-feedback` | `feedback/message-feedback` | ✅ 已实现（第十一轮） | 不可变反馈记录（评分 1-5 + 备注，不进模型上下文）+ `FEEDBACK` 事件 + REST 端点 `POST/GET /api/feedback` |
| `dsh-storage` | `storage/*` | ✅ 已实现（第十二轮 + 第十七轮修复） | 非会话数据存储：kv 命名空间 + 后端 SPI（内存/JSON 文件原子写）；TodoStore/PlanStore 已接入（重启存活）；**第十七轮修复后端顺序**：文件后端 `@Order(0)` 在内存后端 `@Order(1)` 之前（StorageService 写入第一个后端），否则 `put` 只落内存、重启即丢 |
| `dsh-settings` | `settings/*` | ✅ 已实现（第十二轮） | 命名空间设置：schema 默认 > 用户覆盖，覆盖经 storage 持久化；REST 端点 `GET/PUT /api/settings/{ns}/{key}` |
| `dsh-user` | `user/profile`（本轮） | ✅ 已实现（第十七轮 + 第十八轮 key 消费） | **用户 profile 管理**：`register`（PBKDF2WithHmacSHA256 120k 迭代哈希）/`authenticate`/`updateLlmConfig`；LLM API key 经 CredentialService（provider=user, key=userId:llm-api-key，describe 永不带值，重启存活）；AuthService 签发 `tok_` token（内存会话 + TTL）；REST `POST /api/users/register|login|logout` · `GET /api/users/me` · `PUT /api/users/me/llm`；`dsh.api.auth.enabled=true` 开启全站鉴权过滤器；`/v1/chat/completions` 带 token 时：未带 model 回退用户 profile 的 llmModel、**用户配置了 LLM key 则按用户 key 路由**（见 dsh-llm 行）；**真实 e2e 通过（注册→登录→me→更新 LLM 配置→重启持久→带 token 免 model 调 OpenAI 兼容端点；错 key 被拒、对 key 可用）** |
| `dsh-lsp` | `lsp/*` | ✅ 已实现（第十二轮，缝） | LSP 能力缝：provider SPI + `lsp` 工具（4 个语义操作，1-based 行坐标）；LSP4J stdio 后端因库不在本地仓库标 P2 |
| `dsh-code-runtime` | `code-runtime/*` | ✅ 已实现（第十三轮） | **Code Mode**：`run_code` 工具 + **JS(Node.js)/Python(python3) 双语言运行时**（模型程序经 JSON-RPC 回环调用管线工具；失败结果 reject 为程序异常；日志/结果捕获）；GraalJS 语言后端待仓库构件就绪可替换 |
| `dsh-shell`（增强） | `shell/*` `terminal/*` | ✅ 已实现（第十三轮） | **持久终端**：每会话长驻 bash（cwd/export/函数跨调用保留）+ `bash_persistent` 工具；真实 PTY（pty4j+jtermios）因依赖缺失标 P2 |
| `dsh-search` | `web/*` | ✅ 已实现（第三轮 + 第九轮搜索 provider + 第二十轮凭据消费） | `web_fetch`（匿名 HTTP 抓取 + HTML→文本）+ `web_search`（provider SPI + **DuckDuckGo 真实无 key 搜索 provider**，实测可用）；**credentials 消费**：provider 可声明 `credentialRef`，`WebSearchService` 每次调用前经 CredentialService 解析并传入 `search(query, max, apiKey)`（每操作解析、引用不落配置） |
| `dsh-api` | `api/gateway` | ✅ 已实现 | REST 会话 API + SSE 流式 + OpenAI 兼容 `/v1/chat/completions` + **人机协作端点**（审批/问答的 pending/应答）+ **反馈端点** + **凭据操作端点**（describe 永不带值） |
| `dsh-boot` | `boot/app-boot` | ✅ 已实现 | 可运行 Spring Boot 应用 + application.yml + 示例技能 |

## 2. DSH 概念 → Spring 映射

| DSH 概念 | Java/Spring 对应 | 落地位置 |
|---|---|---|
| `ctx.<service>` | `@Service` + 构造器注入；可选服务用 `ObjectProvider` | 各模块 |
| session 事件溯源（`session/event`） | `SessionEventBus`（observe-only 通知）+ 持久化监听 | `dsh-core` |
| waterfall（可短路链） | **自建**有序监听器链（Spring 事件广播不支持短路） | `dsh-tool` 管线、`SystemPromptSection` |
| scope（agent 作用域注册） | P1：`AgentScope` 实例化注册表（Spring 单例不满足） | P1 |
| 工具注册/执行管线（pre-execute→guard→execute→post-execute→result） | `ToolRegistry` + `ToolExecutionPipeline` + `ToolPreExecuteGate`/`ToolPostProcessor` | `dsh-tool` |
| system-prompt 组装（有序 section + 变量插值） | `SystemPromptService` + `SystemPromptSection`（order 波段） | `dsh-core` |
| llm 服务定义 + 适配器 | `LlmGateway` + `SpringAiLlmGateway`（spring-ai-starter-model-openai） | `dsh-llm` |
| agent-loop（turn/step 驱动） | `AgentLoopService`（手动循环，见 §3） | `dsh-agent` |
| 模型可见 ⟺ 已记录 | assistant（含工具调用）/tool 结果逐条落库；system prompt 纯函数可重建 | `dsh-agent` |

## 3. 关键设计决策：为什么手动驱动 agent-loop

Spring AI 2.0 把工具执行循环移到了 `ToolCallingAdvisor`（默认自动执行工具并回填消息）。
DSH 的管线（门控/审批/事件/持久化/压缩挂钩）要求**每个工具调用都经过我们的执行管线**，
且中间消息必须逐条落会话日志。因此：

- `dsh-agent` **不用 ChatClient 的内部循环**，直接调 `LlmGateway.call/stream(Prompt)`；
  模型返回的 `AssistantMessage` 若带 `toolCalls`，由本循环逐个经 `ToolExecutionPipeline`
  执行，再以 `ToolResponseMessage` 回填，继续下一 step。
- 每个 step 重发 system prompt + 工具 schema + 派生消息（与 DSH 一致）；KV 缓存友好的
  追加式历史保留到压缩（P1）。
- 流式：文本增量实时转发；工具调用步骤非流式（模型调用工具时通常无文本），
  待 P1 补全工具增量流。

## 4. 运行方式

```bash
# 编译 + 测试
mvn -o test

# 启动（默认 H2 内存库 + DeepSeek 兼容端点）
OPENAI_API_KEY=sk-xxx mvn -o -pl dsh-boot spring-boot:run

# 或打可执行 jar
mvn -o -pl dsh-boot package
java -jar dsh-boot/target/dsh-boot-0.0.1-SNAPSHOT.jar
```

接口：

- `POST /api/sessions` — 建会话；`POST /api/sessions/{id}/chat` — 普通对话
- `POST /api/sessions/{id}/chat/stream` — SSE 流式（message/tool/done 事件）
- `POST /v1/chat/completions` — OpenAI 兼容（JSON 与 SSE 两种 Accept）
- `GET /api/interactions/approvals/pending` + `POST /approvals/{id}/approve|reject` — 审批应答
- `GET /api/interactions/questions/pending` + `POST /questions/{id}/answer` — 问答应答
- `GET /healthz` — 健康检查；`/swagger-ui.html`（springdoc）

配置（环境变量）：`OPENAI_API_KEY` / `OPENAI_BASE_URL`（默认 https://api.deepseek.com）/
`OPENAI_MODEL`（默认 deepseek-chat）/ `DSH_DB_URL`（默认 H2 内存）。

## 4.1 真实端到端验证（2026-08 用 DeepSeek API 实测通过）

以真实 API key 启动后逐一验证：

1. **bash 工具**：`"用 bash 运行 pwd"` → 工具调用 steps=2/toolCalls=1，回答 `/home/john/workspace/anchon`；
   会话日志完整持久化 user → assistant(工具调用) → tool 结果 → 最终回答（Model-visible ⟺ logged ✓）。
2. **todo_write**：`"记录两条待办"` → 模型正确传对象数组（schema 修复后），2 项入库。
3. **subagent**：`"委托子代理计算 17*23"` → 创建独立子会话 `子代理-sub_xxx`（含委托提示词与
   子代理回答 391），父代理汇总返回。
4. **web_fetch**：抓取 https://example.com → 提取标题 "Example Domain"。
5. **list_agents**：可调用，列出子代理（含状态/深度/内容）。
6. **自动集成测试**（`DeepSeekE2ETest`，`DEEPSEEK_API_KEY` 门控）：bash 真实调用、
   todo_write 真实调用、planner 多 agent persona 生效 — 每轮改动后回归 5/5 通过（bash/todo_write/planner persona/goal/manual compact）。
7. **web_search 真实搜索**：模型调用 `web_search`（DuckDuckGo Instant Answer，无需 key），
   基于真实搜索结果回答 DeepSeek 公司简介（含产品线/V4-Pro 等搜索结果信息）—
   turn steps=3 / toolCalls=2，搜索 provider 全链路可用。
8. **shell-env 受管变量**：bash 前台/后台子进程注入 `DSH_SESSION_ID`/`DSH_WORKDIR`。
9. **浏览器能力**：`browser_navigate` 打开 https://example.com + `browser_get_text` 获取页面文本，
   模型准确总结页面内容 — Playwright Chromium headless 真实闭环（42 个工具全部注册）。
10. **用户 profile 管理（第十七轮）**：注册 alice → 登录签发 `tok_` token → `GET /me` 带 token 返回
    profile（无 token 401）→ `PUT /me/llm` 更新为 deepseek-chat → **重启应用后 alice 仍可登录且
    保留新配置**（PBKDF2 哈希 + profile 经 `data/users.json`、API key 经 `data/credentials.json`
    持久化）→ 带 token、不带 model 调 `/v1/chat/completions`，回退用户 profile 的
    `deepseek-chat` 返回真实回答；错密码登录返回 401"用户名或密码错误"。
11. **按用户 API key 路由（第十八轮）**：注册 carol → `PUT /me/llm` 配置**错误** key
    （sk-invalid-key-12345）→ 带 token 调 `/v1/chat/completions` → DeepSeek 返回
    `401: Authentication Fails, Your api key: ****2345 is invalid`（证明用的是用户 key 而非全局
    key，全局 key 本是正确的）→ `PUT /me/llm` 换成**正确** key → 同端点返回真实回答"收到"；
    按 key 缓存的独立 `OpenAiChatModel`（同 base-url）实测可切换。
12. **压缩 shadow boundary（第十九轮）**：40 条大消息会话超阈值 → turn1 压缩并持久化摘要
    （边界=31）→ turn2 有效历史低于阈值不再压缩，回放从边界起播 — 模型提示含摘要与尾部
    第一条，不再包含被遮蔽的 HDR1..HDR31（AgentLoopCompactionTest 断言全部通过）；
    `CompactionBoundaryStore` 读写/缺省/每会话隔离单测通过。
13. **作用域注册表 + 搜索凭据消费（第二十轮）**：`AgentScopeRegistry` 单测 7 个全绿
    （同键 shadow 新指导遮蔽旧指导、精确 agentId 遮蔽通配、工具过滤 AND 收窄、空注册表
    退化 agent 配置）；`WebSearchService` 凭据消费单测 4 个全绿（声明 credentialRef 的
    provider 收到解析后的 key、未声明收 null、未解析回落下一 provider、无 provider 结构化失败）。
14. **per-agent LLM 凭据引用（第二十一轮）**：启动 `--dsh.agents.main.credential-ref=env:AGENT_KEY`
    （全局 key 正确）：配 `AGENT_KEY=sk-invalid-agent-key` → `/v1/chat/completions` 返回
    `401: Authentication Fails, Your api key: ****-key is invalid`（证明用的是 agent key 而非全局）；
    换成正确 key → 返回真实回答"你好"；请求级覆盖（用户 profile key）优先于 agent 凭据引用的
    优先级链由单测锁定。
15. **工具结果截断（第二十二轮）**：阈值 60 启动 → bash echo 74 字符结果 → 下一 turn 问模型
    "输出里是否有英文方括号标记" → 模型回答看到 **`[... tool result middle pruned ...]`** 且
    头尾正确（`{"success"…` / 尾段）— 截断标记在真实回放链路对模型可见，头+尾保留；
    `ToolResultPrunerTest` 7 个 + `MessageProjectorPruneTest` 4 个单测全绿。
16. **工具输出转存（第二十三轮）**：默认配置启动 → bash 输出 30000 字符 → 下一 turn 问模型
    "是否有 完整输出已转存 提示" → 模型准确复述 **"完整输出已转存: …/data/spill/<会话>/bash_call_….txt"**
    与 "30102 bytes 已省略"，并正确给出用 `read_file` 读取完整内容的调用示例 —
    转存文件（全文）落盘、模型可见面为 预览+定位符；`SpillServiceTest` 7 个 +
    `AgentLoopSpillTest` 1 个集成单测全绿。
17. **目标（第二十四轮）**：会话内让模型 "用 create_goal 创建目标：调研 ToolCallingAdvisor（maxGoalRounds 5）"
    → 模型调用工具创建（revision=1, active）→ `GET /api/goals` 返回目标 →
    下一 turn 要求**不调用工具**直接陈述当前目标 → 模型准确回答 objective/phase=active/maxGoalRounds=5
    （**GoalPromptSection 注入生效**）→ `PUT /api/goals` complete → phase=complete revision=2。
18. **hook 桥（第二十五轮）**：工作区放 hooks.json（PreToolUse matcher=github → block "沙箱策略禁止
    GitHub 操作（hooks 演示）"）→ 会话内让模型调 `github_list_repos` → 工具返回
    `"已拒绝: 沙箱策略禁止 GitHub 操作（hooks 演示）"`，模型正确报告被策略拦截（非 token 问题）；
    同会话 `bash echo hook-pass` 未匹配 hook → 正常执行成功（直通）。
19. **workflow（第二十六轮）**：模型用 `workflow` 工具运行 JS 编排脚本（`await tools.subagent(...)`
    扇出两个子代理分别计算 17*23、31*47 — 子会话各自完成 turn 由日志证实），脚本 `return` 之和；
    快速脚本（不调工具 `return 17*23+31*47`）返回 **1848** 正确；同时修复 run_code 从未注册的
    依赖环缺口（RunCodeTool/WorkflowTool 改 ObjectProvider 懒解析，装配回归测试断言
    run_code/workflow 均在注册表）。
20. **手动压缩 /compact（第二十七轮）**：两轮大消息后发 `/compact` → 立即返回
    `已压缩 1 条历史（保留 3 条，预估节省 282 tokens）`（低于自动阈值 600 也压缩；
    不经过模型 turn）；`/compact please` → `Usage: /compact (no arguments)`；
    空会话 `/compact` → `No compactable history yet.`（单测覆盖）。

### 端到端暴露并修复的问题

- **依赖环导致工具静默丢失**：`ToolRegistry` 构造时 `getBeansOfType(AgentTool)` 实例化工具，
  若工具构造链回到 `AgentLoopService`（→ ToolRegistry）会形成环，Spring 在 getBeansOfType
  中静默跳过该 bean（subagent/send_message 消失但其余 12 个正常、上下文仍加载）。
  修复：`SubagentRunner` 用 `ObjectProvider<AgentLoopService>` 懒解析，打破
  ToolRegistry → SubagentTool → SubagentRunner → AgentLoopService → ToolRegistry 环。
- **todo_write schema 误导模型**：todos 被声明为字符串数组，模型传字符串导致"空标题"校验失败。
  修复：`ToolSchema` 支持对象数组 items（嵌套 properties/required），并让 execute 兼容字符串项。
- **`dsh.*` 前缀被 harness 系统属性污染**：`$DSH_SHELL/DSH_HOME/DSH_WEB_URL…` 展平为
  `dsh.shell/dsh.home/dsh.search`，与 yml 的 `dsh.agent/dsh.agents` 混入同一前缀，
  导致类级 `@ConfigurationProperties` 绑定失效（present=false）。
  修复：`AgentProperties.from(Environment)` 用 `Binder` 显式绑定为
  `Map<String, AgentSpec>`（实测可行）。
- **`/api/goals` 无目标时 500**：`GoalController.current` 用 `Map.of("goal", null)` —
  `Map.of` 禁止 null value → NPE。修复：改 `LinkedHashMap` 表达 `{"goal": null}`；
  回归测试 `noGoalReturns200WithNullGoal`。
- **重放窗口切开 tool_calls 对导致 OpenAI 400**（`insufficient tool messages following
  tool_calls`）：滑动窗口 `from = max(boundary, size - maxHistoryMessages)` 或压缩边界可能把
  `assistant(tool_calls)` 与其 TOOL 响应切成两半，重放给模型的消息序列出现孤立 tool_calls /
  孤立 TOOL → 400。修复：重放时**配对过滤** — 窗口内 assistant 的 tool_calls 必须被窗口内
  TOOL 全覆盖（否则只发文本、剥离 tool_calls），TOOL 只发其 id 属于某个完整 assistant 的
  （孤立 TOOL 跳过）；回归测试 `replaySkipsOrphanToolWhenWindowCutsPair`。
- **ask_user_question 前端选择框联动（新增能力）**：模型调用 `ask_user_question`
  时 `AskUserQuestionTool` 在阻塞前发布 `QUESTION_REQUESTED` 事件（sessionId 经事件
  自带字段），`SessionController` SSE 流推 **`event:question`**（question/options/multiSelect）；
  前端 `MessageList.vue` 渲染选择框（选项按钮/多选/自由输入），用户点选 →
  `POST /api/interactions/questions/{id}/answer` → 工具 future 完成、模型继续；
  **真实 e2e 通过**（question 事件含 3 选项 → 应答 Go/Python → tool 返回 + 流式继续）；
  `SessionEvent` 增 `get(key)`，`SessionEventType` 增 `QUESTION_REQUESTED`。
- **模型主动用选择框的引导**：原始问题（"推荐开发语言供我选择"）下模型默认倾向
  文本罗列而非交互；在 `SystemPromptService` 增加内建**交互约定** prompt 段
  （"需要用户做选择/确认偏好时用 ask_user_question 提供选项，不要文本罗列"）。
  **实测**：加入后原始问题触发模型主动多轮 ask_user_question（使用场景→平台→部署形态），
  每轮 SSE 推 question、应答后继续，最终给出"Python + Flask + SQLite"推荐与理由。
- **SSE 发送 `HttpMessageNotWritableException`**：`SessionController.sendSse` 用
  `SseEmitter.event().data(data)` 未指定 MediaType — SseEmitter 默认按
  `text/event-stream` 找 converter，无 JSON converter → 抛
  "No converter for MapN"。修复：`data(data, MediaType.APPLICATION_JSON)`
  （与 ChatCompletionController 一致）；命令分支的 SSE 发送同修。
- **CompletionException 400 归属澄清**：日志确认流式 400 的 `CompletionException`
  来自 Spring AI 异步客户端（`MessageAggregator` Aggregation Error），根因同
  重放窗口切开（修复前实例）；配对过滤部署后实例日志 0 错误。
- **存储后端顺序未定义导致数据"重启即丢"（第十七轮）**：`JsonFileStorageBackend` 与
  `InMemoryStorageBackend` 都是组件，`orderedStream()` 顺序未定义；`StorageService.put`
  只写第一个后端，若内存后端排前则一切只落内存。修复：文件后端 `@Order(0)`、
  内存后端 `@Order(1)`（持久化优先）。
- **`UpdateLlmRequest` 字段名与 JSON 键不一致导致静默 no-op（第十七轮）**：record 字段
  `provider`/`model` 而请求体键为 `llmProvider`/`llmModel`，Jackson 反序列化为 null，
  更新回退旧值、接口 200 但无变化。修复：record 改为 `llmProvider`/`llmModel`/`apiKey`
  并补 JSON 往返回归测试。

### 真实 API 集成测试（`DeepSeekE2ETest`）

`DEEPSEEK_API_KEY` 环境变量门控（无 key 自跳过，DSH e2e 模式）。有 key 时实测通过（5/5）：
`bash` 工具真实调用、`todo_write` 真实调用、`planner` 多 agent persona 生效、
`create_goal` 真实创建并持久化目标、`/compact` 手动压缩（摘要持久化 + 后续 turn 正常回放）。

## 5. 路线图（对应能力映射的 P1/P2）

- **P1 全部闭环（第二十轮）**：agent 作用域注册表完整注册/遮蔽形态
  （`AgentScopeRegistry`：同键 shadow、精确优先于通配、工具过滤 AND 收窄）、
  web 搜索 provider 的凭据消费（`credentialRef` 声明 + 每调用解析）。
- **P2 外围**（需外部依赖/网络，本地仓库不可得）：LSP4J stdio 后端、真实 PTY（pty4j+jtermios）、
  GraalJS 语言后端（js-language jar 下载未完成）、sandbox OS 级 runner（bwrap）。
- **已闭环（第十九轮）**：压缩遮蔽边界（shadow boundary）已落地
  （`CompactionBoundaryStore` + 边界起播），从 P2 移除。

## 6. 已知限制（本迭代接受，P2 处理）

- **工具超时协作式**：超时只返回结构化失败，不中断仍在运行的工具体（与 DSH 一致：
  忽略 signal 的工具不会停）；后续加协作式取消信号。
- **审批/问答阻塞模型调用**：等待人工应答会占用 turn 的虚拟线程（DSH 同样阻塞），
  REST 应答端点属 P1。
- **（已修复）压缩重放顺序**：曾为 [压缩前历史…, 上一用户消息, 摘要, 尾部]（摘要未遮蔽
  旧头）；第十九轮起 `CompactionBoundaryStore` 记录遮蔽边界，后续 turn 从边界起播，
  不再重发被摘要覆盖的历史（对应 DSH surfaceOp replace 语义）。

## 6. 与 external/javaai 的关系

`external/javaai` 是单应用 Spring AI 参考实现（ChatClient + 自研工具注册 + SSE + OpenAI 兼容层）；
本工程吸收其**工具适配器模式**（AgentTool → ToolCallback）与 OpenAI 兼容出口思路，
但把循环控制、事件、持久化提升到 DSH 语义（手动循环 + 事件溯源 + 逐条落库），
并以多模块镜像 DSH 包结构。