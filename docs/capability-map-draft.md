# DSH 能力 → Java/Spring AI 实现建议（映射文档草稿）

> 本文是**分析草稿**，不做 Java 实现。信息来源：`external/deepseek/packages/**/README.md`（含组级与子包 README），按任务清单逐组提炼。凡未逐包细读的，标注「未细读，建议读 src/」，不编造。
>
> 目标技术栈：Spring Boot 4 + Spring AI 2.0，多模块 Maven 工程（一个 DSH 能力组 ≈ 一个 Maven 模块）。

## 0. 阅读约定（跨组移植总原则）

DSH 是 Cordis 插件体系，移植到 Spring 时以下概念一一对应：

| DSH 概念 | Java/Spring 对应 |
|---|---|
| `ctx.<key>` 服务（如 `ctx.sessions`、`ctx.tools`） | 一个 `@Service` 接口 + `@Component` 实现；**可选服务**用 `ObjectProvider<T>` / `@Autowired(required=false)` 注入（对应 `ctx.get(name)` 的宽松语义） |
| 事件（`session/event`、`agent/*`、`tools/*`、`system-prompt/assemble`…） | 优先 `ApplicationEventPublisher` + `@EventListener`；但 DSH 有大量 **waterfall（链式可短路）** 事件，Spring 事件广播不支持短路，需自建 `Ordered` 监听器链（`List<Listener>`，`next()` 语义） |
| `scope`（agent 作用域注册：一个 agent 一套 tools/sections/variables） | 每个 `Agent` 持有自己的作用域注册表实例（`AgentScope`），或用 `ThreadLocal<AgentScope>`/上下文对象贯穿 turn 执行；Spring 单例 bean 需显式区分"全局层"与"agent 层" |
| 注册即 effect（register 返回 disposer） | `AutoCloseable`/`DisposableBean`；`@Bean(destroyMethod=)`；作用域销毁时批量释放 |
| 事件溯源会话（append-only `SessionEvent` 日志 + surface 投影） | `record` + `sealed interface` 定义事件类型（对应 `SessionEventMap` 合并扩展）；追加式事件列表 + 投影器（`deriveMessages()`） |
| **Model-visible ⟺ logged**（模型可见的一切必须可从会话日志重建） | 设计铁律：新增模型可见输入必须同步写会话事件 |
| LLM 流式（`StreamChunk` 协议 + `BlockAssembler`） | Spring AI 的流式 `ChatClient`（`Flux<ChatResponse>`）+ 自建块装配器；工具调用用 Spring AI `ToolCallback` 适配到 DSH 工具管线 |
| waterfall 拦截（`agent/request`、`llm/stream`、`tools/execute`） | 类似 Spring AI 的 `CallAdvisor`/`StreamAdvisor`（around advice 链）或自建有序链 |
| 重试策略（`agent/request-error`） | Spring Retry / Resilience4j，但**必须挂在 turn 级恢复点**而非包住流式调用（见 llm-retry） |

---

## 1. core — 产品 API 主干（`packages/core/`）

组级 README：会话日志、system-prompt 组装、工具注册表、Agent 词汇表、默认模型选择、具体循环 = 默认控制主干；全部为**产品**包，是插件与消费者的稳定面。

### 1.1 scope（`core/scope`）

| 包名 | 职责 | 关键扩展点/事件 | Java 映射建议 |
|---|---|---|---|
| `core/scope` | 作用域注册原语：`createScope(ctx,key)`、`scopeOf(ctx)`、`scopeTarget(base,key)`、`bindScopeParent` 父链 | 无 ctx key（库级）；注册的可见性与生命周期绑定同一作用域；事件按 key 过滤路由 | `AgentScope` 类：持有一组作用域注册表（tools/sections/variables）；`ScopeKey` 用强类型对象而非字符串 |

**Java 要点**：
1. 可见性与生命周期**必须一致**：一个注册要么全局可见、要么只在该 agent 作用域内可见且随 agent 销毁；父链是"child 见 ancestor 层（近者遮蔽远者）、事件向祖先方向放行"。
2. 明确非目标：**不是安全/授权边界**（DSH 明确声明 scope 是 trusted same-process 路由）。
3. Spring 单例与 agent 作用域冲突是最大坑：全局层用 Spring bean，agent 层必须是非 Spring 管理的实例化注册表。

### 1.2 session（`core/session`）— 事件溯源会话

| 包名 | 职责 | 关键扩展点/事件 | Java 映射建议 |
|---|---|---|---|
| `core/session` | 事件溯源会话日志 + 内存存储；`Session` 是唯一真相源，LLM 消息历史由它**派生**（surface 投影） | `session/event`（追加通知）、`session/flush`（显式持久化屏障）、`session/created`/`session/disposed`；`fork(source,boundary)` | `SessionAggregate`（append 校验/冻结/通知）+ `SessionStore`（create/get/list/fork/flush）；`SessionEvent` = sealed interface + record |

**Java 要点**：
1. **append-only + 深冻结**：事件提交前快照并冻结（Java 用不可变 record 天然支持）；`deriveMessages()` 只投影 `user/message`、`assistant/message`、`tool/result` 三类 surface 事件（含 `surfaceOp: replace` 的压缩替换）。
2. 持久化**不是本包职责**：插件订阅 `session/event` 做 write-behind，`session/flush` 是 await 的落盘屏障——Java 侧用监听器 + `CompletableFuture` 汇聚实现。
3. turn/step 封闭不变式（`turn/start`…`turn/end`、同 step 的 tool call/result 配对）应由 invariant 检查器在导入/回放时校验。

### 1.3 system-prompt（`core/system-prompt`）

| 包名 | 职责 | 关键扩展点/事件 | Java 映射建议 |
|---|---|---|---|
| `core/system-prompt` | 段注册表：有序 section、`{{variable}}` 插值、工具 schema 提供者；每次 step 组装一次 | `system-prompt/assemble`（waterfall，可替换整份组装）；`section()/context()/tools()/variable()` 均返回 disposer | `SystemPromptService`：`List<PromptSection>`（`Ordered`）、`VariableResolver`、`ToolSchemaProvider`；组装 = 有序链 + 严格插值 |

**Java 要点**：
1. 段有 order 波段约定：-100 身份、0 persona、100-199 工具指导；`complete:true` 段成为唯一 prompt。
2. `toolOrder` 显式工具顺序 + 严格变量插值（未知变量**fail loud**而非留空）。
3. 组装结果含 `tools` schema——"模型被告知能做什么"与 prompt 是一体两面，Spring AI 侧对应把 ToolCallback schema 喂给 ChatClient。

### 1.4 tools（`core/tools`）— 工具注册表与执行管线

| 包名 | 职责 | 关键扩展点/事件 | Java 映射建议 |
|---|---|---|---|
| `core/tools` | 工具注册 + 执行管线：pre-execute（allow/deny/ask 门）→ guard（单调）→ execute（around 包装）→ post-execute（可改结果）→ `finalizeContent` → `tools/result`（只读通知）；mode native/code/both | `tools/pre-execute`、`tools/execute`、`tools/post-execute`、`tools/result`；`register()/guard()/restrict()/presentAs()/execute()` | `ToolRegistry` + `ToolExecutionPipeline`（有序监听器链）；`ToolDefinition`（name/schema/execute(args, signal)/timeoutMs/isConcurrencySafe） |

**Java 要点**：
1. 管线顺序是**扩展契约**：deny 不可被下游翻转（guard 单调）；`tools/execute` 包装器只能替换 `signal`；结果经 `output.schema` 校验后渲染为模型可见 `tool/result` 并落会话日志。
2. 并行调度：`isConcurrencySafe(args)` 精确 true 才并行（有界滚动池），否则串行屏障；`maxParallelToolCalls` 默认 10。
3. 取消协作式：`AbortSignal`（Java 用 `CompletableFuture.cancel` 或自建信号对象）贯穿工具体；取消前未分发 = `ABORTED_BEFORE_DISPATCH`。
4. **Code Mode**（run_code + 生成 SDK）是可选大件，P2 再考虑（见 code-runtime 组）。

### 1.5 agent（`core/agent`）

| 包名 | 职责 | 关键扩展点/事件 | Java 映射建议 |
|---|---|---|---|
| `core/agent` | Agent 接口、注册表、initiator 作用域（进程内当前 agent 传播）、`agent/*` 事件词汇；**零循环依赖**（loop 可换） | `agent/created`/`agent/disposed`、`agent/pre-step`（可 reject/enter）、`agent/request-error`（恢复 waterfall）、`agent/turn-stopping`、`agent/inbox/inserted|claimed|discarded`、`agent/session-start` | `Agent` 接口（inbox/followup/steer/inject/cancel/whenIdle/session/status/ctx）+ `AgentRegistry` + `AgentHandle`（dispose 为消费者能力） |

**Java 要点**：
1. `Agent` 是插件唯一编程面，与具体 loop 解耦——Java 侧先定接口再实现循环，保证可替换。
2. inbox 语义：`followup`（下一 turn，唤醒）、`steer`（下一 step，唤醒）、`inject`（下一 step，不唤醒）；claim 是"纯删除拼接"并发出 `agent/inbox/claimed`。
3. initiator 作用域 = 进程内"当前正在驱动的 agent"上下文（对应 Cordis AsyncLocalStorage）：Java 用 `ThreadLocal`/虚拟线程局部上下文，注意跨线程（worker/HTTP/持久化）必须显式传 agent 身份。

### 1.6 agent-default-model（`core/agent-default-model`）

| 包名 | 职责 | 关键扩展点/事件 | Java 映射建议 |
|---|---|---|---|
| `core/agent-default-model` | 部署默认 provider/model 选择；新 agent 无会话级选择时使用；`reasoningEffort` 只属 settings 层 | 无事件；`currentSelection()`/`saveSelection()`；配置为 `{provider, model}` 必填 | `AgentDefaultModel` bean：默认选择解析 + 可选 settings 覆盖层 |

### 1.7 agent-loop（`core/agent-loop`）— 唯一含循环逻辑的包

| 包名 | 职责 | 关键扩展点/事件 | Java 映射建议 |
|---|---|---|---|
| `core/agent-loop` | 具体 agent 驱动：create/resume 事务、inbox、turn/step 生命周期、模型调用、工具调度、取消/错误恢复 | 消费全部 `agent/*` 与 `session/*` 事件；`ctx.agents.setFactory(this)`；配置 `maxParallelToolCalls`、`agents[]` | `AgentLoop`（虚拟线程或 Reactor 驱动的 turn/step 循环）；`AgentFactory` 实现并注册 |

**Java 要点**（这是移植难度最高的包）：
1. **create/resume 是回滚覆盖的一次性事务**：私有会话+agent+作用域 → 可选 setup（未发布）→ 注册表 enter → 发布（`session/created`→`agent/created`→`agent/session-start`）；并发同 id 只有一个能 enter，败者整体回滚。
2. 每个成功到终点的 provider 调用恰好追加一个 `assistant/message` 锚点（含 max-tokens 完成、空内容）；每 step 发送 = 渲染 system prompt + 可见工具 schema + 派生消息。
3. 插件失败只结束当前 turn 不结束 loop：`agent/request-error` 是恢复点（llm-retry 在这里重试）；未处理失败即终局。
4. 取消语义：`cancel(cause)` 清 pending 收件箱（除非 keepInbox），turn 记录 `aborted`/`disposed`；未分发的工具调用补合成 `ABORTED_BEFORE_DISPATCH` 结果。

### 1.8 agent-tool-presentation（`core/agent-tool-presentation`）

| 包名 | 职责 | 关键扩展点/事件 | Java 映射建议 |
|---|---|---|---|
| `core/agent-tool-presentation` | preset 携带的行：声明该 agent 工具对模型的呈现形式 native/code/both | 通过 `ctx.tools.presentAs()` 作用于单 agent | `ToolPresentationMode` 枚举 + preset 装配时应用 |

---

## 2. llm — LLM 能力族（`packages/llm/`）

| 包名 | 职责 | 关键扩展点/事件 | Java 映射建议 |
|---|---|---|---|
| `llm/llm` | 服务定义：adapter 注册 + 单流式调用 API + `StreamChunk` 协议 + `BlockAssembler` + 错误码体系（`HarnessError`） | `llm/stream`（waterfall，可拦截/包装每个流式调用）；`registerAdapter(providers, adapter)` | `LlmRuntime` 接口 + `StreamChunk` 模型；Spring AI `ChatModel` 适配器包装 |
| `llm/llm-deepseek` | DeepSeek `deepseek-official` 路由适配器：fetch+SSE → StreamChunk；配置 thinking/reasoningEffort/maxTokens/retryPolicy/models | 每请求经 `ctx.credentials` 解析 API key；错误分类 `AUTH/QUOTA/RATE_LIMIT/CONTEXT_WINDOW_EXCEEDED/…`；`generateOptions.purpose`（conversation/compaction/session-title） | `DeepSeekLlmAdapter`：Spring AI `ChatClient` 或直接 WebClient+SSE；错误 → `LlmError(code)` |
| `llm/llm-retry` | 按 provider 策略在 `agent/request-error` 上重试（不包 stream！每次重试 = 新编号 turn） | 监听 `agent/request-error`；追加 `llm/retry`、`llm/retry-started` 事件 | `ModelRequestRetryPolicy` bean：bounded 指数退避（500ms–10s、10% jitter）或 always 模式 |

**Java 要点**：
1. `StreamChunk` 是原始 chunk 协议（block-start/text-delta/reasoning-delta/tool-call-delta/block-end/usage/finish），**所有适配器结果统一为终态 finish**（error/aborted），不跨流抛异常——Java 用 `Flux<StreamChunk>` + 统一 terminal 元素实现。
2. 重试**不包装流**：每次重试开新 turn、重建请求，靠会话日志恢复；Java 侧对应在 turn 循环的错误分支做策略决策，而不是给 ChatClient 套 RetryTemplate。
3. 模型调用配置（provider/model/effort/temperature/maxTokens）是**每会话状态**，写入 `request/header` 事件保证可重建（Model-visible ⟺ logged）。

---

## 3. session — 持久化数据平面（`packages/session/`）

| 包名 | 职责 | 关键扩展点/事件 | Java 映射建议 |
|---|---|---|---|
| `session/session-persistence` | 持久化服务定义 + 共享写协调器（bounded batch、crash repair、contiguous seq、durability） | 后端契约 `PersistenceBackend`（loadStored/appendBatch/commitRepair/list）；`session/event` 订阅 + `session/flush` 屏障 | `SessionPersistence` 接口 + `PersistenceCoordinator`（每会话写控制器） |
| `session/session-persistence-jsonl` / `-sqlite` | JSONL / SQLite 后端（未逐个细读，建议读 src/） | 注册 `ctx.sessionPersistence` | JSONL 后端 / Spring JDBC + SQLite 后端 |
| `session/session-checkpoint-policy` | 语义化持久化检查点（包 `ctx.llm` 与 `ctx.tools`） | — | 检查点切面（模型调用/工具结果后 flush） |
| `session/session-projection` | 投影注册表：驱动纯函数 unit（`init/apply/view`）消费已提交事件，服务整值快照 | `sessionProjections.register()`；change feed；`snapshot()` 一致切面 | `ProjectionRegistry`：`apply` 须返回同引用表示无关事件；**whole-value 规则**（状态事件携带完整状态） |
| `session/session-projection-cache` | 投影检查点持久化/恢复 | — | 投影缓存存储（可并入 storage） |
| `session/session-title` | 日志驱动标题：确定性 fallback + 可选单 LLM provider；`session/title` 事件 | `register(provider)`（唯一）、`refresh()`、`rename()` | `SessionTitleService` |
| `session/session-title-llm` / `-first-prompt-llm` / `-all-prompts-llm` | 模型生成标题的共享/两种节奏 provider（未细读，建议读 src/） | 注册 `ctx.sessionTitle` | LLM 标题 provider（辅助调用，purpose=session-title） |
| `session/session-telemetry` | 遥测服务定义：`SessionTelemetrySink` 契约 + live/on-demand 捕获协调器 | `sessionTelemetry/record`（脱敏 waterfall）；`session/created/event/flush/disposed` + `agent/error` | `SessionTelemetrySink` 接口 + 捕获协调器（记录 `(session.id, seq)` 游标） |
| `session/session-telemetry-otel` | OpenTelemetry 后端（FULL/FEEDBACK_ONLY/DISABLED） | — | Micrometer/OTel 日志后端 |
| `session/session-stats` | `sessionStats` 投影单元：turns/steps/llmMs/ttft/decode/tool 耗时 | 注册 `ctx.sessionProjections` | 统计折叠（纯函数 fold） |

**Java 要点**：
1. 持久化单元**就是** `SessionEvent`（无平行"持久化消息"类型）；`SessionHeader`（version/id/createdAt/cwd/parentSession/seedLength/delegationDepth）单独存。
2. crash 修复：**追加式**，崩溃的 turn 用合成 `tool/result`/`turn/end {interrupted}` 闭合而非截断；只丢弃 torn tail。seq 必须连续。
3. 投影 unit 全同步纯函数 + whole-value 规则，是 Java 侧最容易直接照搬的设计（`apply(state, event)` 返回同引用 = 无变化）。

---

## 4. fs — 文件系统能力族（`packages/fs/`）

| 包名 | 职责 | 关键扩展点/事件 | Java 映射建议 |
|---|---|---|---|
| `fs/fs` | 服务定义：`FileSystem` 12 原语（resolve/processPath/fileUrl/contains/stat/lstat/readText/streamText/readBytes/listDir/writeText/editText）+ 版本守卫；owns `fs/*` 策略事件词汇 | `fs/write-intent`、`fs/edit-intent`（单槽决策 waterfall）、`fs/observed`（fire-and-forget 记录） | `FileSystem` 接口 + `FsError`（稳定 code 枚举） |
| `fs/fs-local` | 本地实现：原子写（temp+fsync+发布）、版本 token（dev:ino:size:mtimeNs:ctimeNs）、edit 临界区 | 注册 `ctx.fs` | `LocalFileSystem`：`java.nio` + 原子移动 + 版本校验 |
| `fs/fs-sandbox` | 沙箱栅栏后端：按 per-call 模式 + workspace root 对 write/edit 加栅栏（read-only 拒、workspace-write 限制到 workspace+temp），读放行 | 依赖 `ctx.sandboxPolicy`；复用 `writableRoots` | `SandboxedFileSystem`：路径规范化 + 包含性检查（TOCTOU 仅收窄不消除，**是策略栅栏不是内核边界**） |
| `fs/fs-observation-policy` | 策略门插件（无服务，纯 `fs/*` 监听器）：observed-state + read-before-edit + 版本守卫写/编辑 | `fs/write-intent`→`createIfAbsent`/`replaceIfVersion`；`fs/edit-intent`→`{version}`/`FS_NOT_OBSERVED`；`fs/observed` 记录 | `FsObservationPolicy`：弱引用 owner→target 状态表（present/absent/unseen） |
| `fs/tool-fs` | 模型侧 `read`/`read_image`/`write`/`edit` 工具 + executor：read 窗口化、行号、结果渲染；经 `ctx.fs` 读写，分发 `fs/*` 事件 | 注册 `ctx.tools`；经 `ctx.approval` 处理沙箱升级 | `ToolFs`：工具定义 + 执行器（窗口化读取、渲染 footer） |
| `fs/tool-fs-search` | `glob`/`grep` 发现工具：打包 ripgrep 二进制经 `ctx.subprocess` 调用（**不经** `ctx.fs`） | 注册 `ctx.tools`；可选 `ctx.spillStore` 存超限完整结果 | `ToolFsSearch`：Java 无 ripgrep——可选方案 a) 自带 rg 二进制调用；b) 自实现 glob/正则行搜索（Java 21 模式匹配） |
| `fs/tool-str-replace-editor` | 独立 `str_replace_editor` 工具（view/create/str_replace/insert） | 同 `fs/*` 事件门 | `ToolStrReplaceEditor` |

**Java 要点**：
1. 四层分离：tool/executor → policy（事件门，可摘除）→ provider 契约 → provider 实现；policy 摘除后工具退化为无条件读写（graceful degrade）——Java 侧保持"policy 是监听器不是被注入的服务"。
2. 文件 IO **无超时**（OS 会完成的工作不应被杀），只有协作式取消（signal）；`read` 有窗口/字节上限（readLimit 2000、readMaxBytes 51200）。
3. 模型可见错误文本要**稳定**（"edit requires reading ... first"、`FS_STALE_VERSION` 追加修复指引），便于模型自纠正。

---

## 5. shell — bash 能力族（`packages/shell/`）

| 包名 | 职责 | 关键扩展点/事件 | Java 映射建议 |
|---|---|---|---|
| `shell/shell` | `ShellExecutor` 服务定义：run（前台）/start（后台）；`sandboxMode` 能力事实；`ShellProcess`（增量读/kill）；`parseExitStatus` 共享渲染契约 | 能力缝（服务定义/提供者/消费者三分）；`SHELL_SETTINGS_NAMESPACE='bash'` | `ShellExecutor` 接口 + `ShellRunResult`/`ShellProcess` |
| `shell/bash-local` | 每次 `bash -c <cmd>` 新进程（无状态）；超时/取消分类（timedOut/aborted）；模型友好 env（NO_COLOR/TERM=dumb…） | 注册 `ctx.shell`；配置 timeoutMs/maxOutputBytes/spill/graceMs | `BashExecutor`：`ProcessBuilder` 包装 + 进程组终止 |
| `shell/bash-sandbox` | 沙箱包装执行器：把精确 argv 交给 `ctx.sandbox` 提供者；denial 作为结果事实（非异常） | 依赖 `ctx.sandbox` + `ctx.sandboxPolicy` | `SandboxBashExecutor`：外包装进程（bwrap 等）或 Java 侧文件系统权限包装 |
| `shell/pwsh-local` | Windows PowerShell 对应实现（pwsh -Command、UTF-8 固定） | 注册 `ctx.shell` | `PwshExecutor`（Windows 分支） |
| `shell/shell-env` | 受管 `DSH_*` 环境注册表（DSH_HOME/DSH_SHELL/DSH_SESSION_ID/DSH_SESSION_JSONL） | `register()` 贡献者；执行时收集快照经 `dshEnv` 通道 | `ShellEnvRegistry`：每调用收集受管变量快照 |
| `shell/tool-bash` | 模型侧 `bash` 工具：前台 + `run_in_background`（经 `ctx.jobs`）+ 沙箱升级字段（`sandbox_permissions`+`justification` 经 `ctx.approval`） | 注册 `ctx.tools`；`tool:bash` prompt 段（order 105） | `ToolBash`：`[exit code: N]` 标记渲染、后台任务接入 |
| `shell/tool-pwsh` | 模型侧 `pwsh` 工具（Windows） | 注册 `ctx.tools` | `ToolPwsh` |
| `shell/tool-bash-persistent` | 基于 `ctx.terminals` 的持久 shell `bash(command)`（每 agent 一个 shell、状态跨调用） | 注册 `ctx.tools`；依赖 PTY 后端 | `ToolBashPersistent`（P2，依赖 terminal 组） |

**Java 要点**：
1. **每次调用新 shell**（无持久状态）是默认；`workdir` 默认 = 调用 agent 的 `session.header.cwd`（每会话工作区），相对路径对同一身份解析。
2. 后台进程：shell 层只返回 `ShellProcess` 句柄，job id/所有权/轮询/通知归通用 `ctx.jobs`——Java 侧职责同样分开。
3. 沙箱升级流：denial → 模型以**最窄**更宽模式 + justification 重试一次 → `ctx.approval` 审批后才执行；非严格加宽或审批禁用即终局（与 DSH 文件沙箱同一模式词汇）。

---

## 6. subprocess — 子进程能力族（`packages/subprocess/`）

| 包名 | 职责 | 关键扩展点/事件 | Java 映射建议 |
|---|---|---|---|
| `subprocess/subprocess` | 服务定义：可执行查找、受管子进程树、原始/收集 stdio、终端进程原语、句柄生命周期、共享 env/输出词汇 | 消费者：bash executors、LSP host、PTY 后端、ACP subagent 后端 | `SubprocessRuntime` 接口：`ProcessBuilder` + `ProcessHandle` 树管理 |
| `subprocess/subprocess-local` | 本地提供者：detached 进程树、bounded 收集/spill、node-pty、树信号、terminate-and-join 处置 | 注册 `ctx.subprocess` | `LocalSubprocessRuntime`：输出上限 + spill 文件；PTY 用 pty4j |

**Java 要点**：进程生命周期跨消费者重载存活；服务 owns 进程、消费者 owns 语义默认值；输出有内存上限（超限落 spill 文件）。Java 侧注意 `Process.destroy()`/`destroyForcibly()` + 进程树（`ProcessHandle.descendants()`）。

---

## 7. terminal — 持久 PTY 能力族（`packages/terminal/`）

| 包名 | 职责 | 关键扩展点/事件 | Java 映射建议 |
|---|---|---|---|
| `terminal/terminal`（`dsh-terminal`） | PTY 后端注册表、branded id、精确 Agent 所有权、会话操作、await 清理 | `ctx.terminals` | `TerminalRegistry`（pty4j 封装） |
| `terminal/terminal-bash` | shell 后端（readiness 检测、bounded terminal state、sandbox policy） | 注册 `ctx.terminals` | `BashTerminalBackend` |
| `terminal/tool-terminal` | 六个模型侧工具 + 后台发送经通用任务集成 | 注册 `ctx.tools` | `ToolTerminal` |

**Java 要点**：持久 shell = 跨工具调用状态（cwd/export/函数/后台进程持久）；超时/取消会关闭不确定的 shell 并告知模型下个调用全新开始（避免把尾巴当完整输出）。

---

## 8. skill — 技能能力族（`packages/skill/`）

| 包名 | 职责 | 关键扩展点/事件 | Java 映射建议 |
|---|---|---|---|
| `skill/skill` | `ctx.skills` 提供者注册/快照/加载；invocation policy（model/user 两维） | `skills/change`（失效通知）；`registerProvider()`；`snapshot()/list()/get()` | `SkillRegistry`：提供者接口 + 快照合并（global 层 + 作用域链） |
| `skill/skill-filesystem` | 本地文件提供者：项目/自定义/用户根扫描 `SKILL.md`/flat md、frontmatter 解析、Chokidar watch 失效 | 注册 `ctx.skills` | `FilesystemSkillProvider`：目录扫描 + `WatchService` |
| `skill/tool-skill` | 模型侧 `skill` 目录（每 `agent/pre-step` 注入 durable 目录消息）+ `skill` 工具 | 注册 `ctx.tools`；`agent/pre-step` 注入 | `ToolSkill`：目录渲染（`<available_skills>`）+ 加载器 |

**Java 要点**：目录是 durable user-role 消息（首次完整、变更后整体替换、清空即 tombstone）；digest 决定是否重发；"目录只含 name+description，body 不缓存、每次加载现读"。

---

## 9. plan — 计划协作状态（`packages/plan/`）

| 包名 | 职责 | 关键扩展点/事件 | Java 映射建议 |
|---|---|---|---|
| `plan/plan-mode` | plan 模式 = 日志化的 per-agent 状态：`plan/mode` 事件（whole-value replace）；`/plan`、`/plan off` 命令；`exit_plan_mode` 工具 + 经 `ctx.userQuestions` 的评审 | `plan/mode` 折叠恢复；`command/run`（plan 记录）→ 投影 unit | `PlanModeService`：`plan/mode` 事件 + `exit_plan_mode` 工具（带 `plan-review` 呈现意图） |

**Java 要点**：plan 模式是**软指导**（prompt 段 + 退出工具），不自己强制执行——沙箱/审批策略独立强制执行；`set()` 在 idle 时立即追加事件、运行时挂起到下一个 pre-step。

---

## 10. todo — 待办能力族（`packages/todo/`）

| 包名 | 职责 | 关键扩展点/事件 | Java 映射建议 |
|---|---|---|---|
| `todo/tool-todo` | `todo_write` 工具：**整表替换**；`todo/write` 事件（全量快照，last-write-wins）；单 agent 会话所有权 | 注册 `ctx.tools`；`todo/write` 事件 + `todos` 投影 unit（turn/start 时清空"进行中计划"） | `ToolTodo` + `TodoProjectionUnit` |

**Java 要点**：无部分更新/无读回工具；`allowParallelInProgress` 是部署选择（影响模型指令与校验，但不进日志不变式——旧日志在新策略下仍须可回放）。

---

## 11. goal — 持久化同会话目标（`packages/goal/`）

| 包名 | 职责 | 关键扩展点/事件 | Java 映射建议 |
|---|---|---|---|
| `goal/goal` | 事件溯源目标状态：`goal/change` 事件（全量快照 + CAS revision）、`goal/changed` 事件；激活（continuation 权限）**不持久化**，会话恢复/复刻即解除 | 服务动词 create/edit/pause/resume/complete/block/clear；`disarm()`；`maxGoalRounds` 预算 | `GoalService`：`GoalRef{id,revision}` CAS + 严格回放校验 |
| `goal/goal-round-driver` / `tool-goal` / `command-goal` | 同会话延续驱动 / 模型侧工具 / 人类命令（未细读，建议读 src/） | — | 延续驱动（把目标轮次作为 user/message 注入）+ 工具 |

**Java 要点**：状态在会话日志、决策（何时继续）在消费者；block 用策略所有者的低 kebab-case code + 解释；`defaultMaxGoalRounds` 部署默认（默认 256）。

---

## 12. workflow — 动态工作流能力族（`packages/workflow/`）

| 包名 | 职责 | 关键扩展点/事件 | Java 映射建议 |
|---|---|---|---|
| `workflow/workflow` | `ctx.workflowEngine`：执行模型编写的编排脚本（fan-out 子代理）；定义 script/run/result/error/event 契约 | `workflow/start`、`workflow/end`、`workflow/phase`、`workflow/log`、`workflow/agent-start`、`workflow/agent-end`（均 observe-only） | `WorkflowEngine` 接口 + `WorkflowRun`（holder-owned、result 永不 reject） |
| `workflow/workflow-worker-thread` | 工作线程执行（隔离但不安全边界） | 注册 `ctx.workflowEngine` | 可省略线程隔离（Java 虚拟线程） |
| `workflow/tool-workflow` / `tool-ralph` | 通用工作流工具 / 固定 fresh-agent Ralph 工具 | 注册 `ctx.tools` | 工具 + 固定工作流 |

**Java 要点**：`WorkflowError` 分类（fatal 的必然逃逸 `parallel()`/`pipeline()`；普通子代理失败 → `agent()` 返回 null 让脚本处理）；脚本语言 Java 侧可选：a) 模型写 Java API 调用序列；b) GraalJS 执行 JS 脚本。无日志/无恢复（holder 负责 dispose）。

---

## 13. subagent — 子代理能力族（`packages/subagent/`）

| 包名 | 职责 | 关键扩展点/事件 | Java 映射建议 |
|---|---|---|---|
| `subagent/subagent` | `ctx.subagents`：provider 注册、start/startContinuable/followup/interrupt/reportFrom/listChildren/listDescendants；durable descriptor（`subagent/descriptor` 事件）；委托策略继承 | `subagent/start`、`subagent/end`（父子成对）、`subagent/provider-added|removed`；`subagent:delegation` 运行时上下文语句 | `SubagentRuntime` 接口 + `SubagentProvider` SPI |
| `subagent/subagent-in-process-driver` | 共享进程内运行驱动（spawn 无 seed / fork 传父完成 turn 前缀）；深度校验、结构化输出运行时 | — | `InProcessSubagentDriver`：用 `AgentFactory` 建子 agent + `followup` + `whenIdle` |
| `subagent/subagent-spawn-in-process` / `-fork-in-process` | 两个进程内 provider | 注册 `ctx.subagents` | 对应 provider 实现 |
| `subagent/tool-subagent` | 模型侧委托工具（provider→toolName 绑定；one-shot 前台/后台、continuable 模式） | 注册 `ctx.tools` | `ToolSubagent` |
| `subagent/tool-subagent-control` | `send_message`/`interrupt_agent`/`list_agents` 控制工具 | 注册 `ctx.tools` | `ToolSubagentControl` |
| `subagent/tool-subagent-report` | 子→父 report 通道（未细读，建议读 src/） | 注册于子作用域 | report 工具 |
| `subagent/subagent-acp` / `-codex` / `-claude-code` / `-dsh-sdk` | 进程外 provider（未细读，建议读 src/） | 注册 `ctx.subagents` | P2：进程外/远程 provider |

**Java 要点**：
1. 委托策略固定子代理权限：捕获父 sandbox 覆盖 + approval 钉死 `'never'`，子代理无法自我加宽（`subagent:delegation` 语句告知模型"被拒即上报，不重试"）。
2. continuable 子代理：一个 durable Session + 至多一个进程内 Activation（驻留 epoch）；Agent inbox 是唯一 turn 队列；冷恢复从持久化 descriptor 重建，不再走 provider。
3. 深度预算：`SessionHeader.delegationDepth` 权威且单调（运行时只加深不降低）。

---

## 14. compaction — 压缩能力族（`packages/compaction/`）

| 包名 | 职责 | 关键扩展点/事件 | Java 映射建议 |
|---|---|---|---|
| `compaction/compaction` | `ctx.compaction`：compactIfNeeded（pressure/context-overflow）/compactNow/compactRegion；`compaction/*` 事件词汇 + 表面替换 + 工具配对边界 | `compaction/start`、`compaction/summary`、`compaction/end`（日志锁定括号）；表面替换 = 单个 `user/message` + `surfaceOp: replace` | `CompactionEngine` 接口 + `CompactionLock`（日志化括号） |
| `compaction/compaction-basic` | token 压力 + 保留尾部 + LLM 摘要后端（未细读，建议读 src/） | 注册 `ctx.compaction`；摘要 = 直接 `llm.stream()` 辅助调用 | `BasicCompactor`：`ctx.tokenMeter` 压力 + 摘要调用 |
| `compaction/compaction-tool-result-pruner` | 无模型工具结果修剪（content-only `tool/result` 替换） | `ctx.toolResultPruner` | 结果修剪器 |
| `compaction/command-compact` | `/compact` 人类命令 | 注册 `ctx.commands` | 命令 |

**Java 要点**：压缩是**表面替换**而非删日志——被遮蔽事件保留在原始日志中保证确定性回放；锁是日志化的 start/end 括号（崩溃留下可检测的孤儿锁而非假成功）；工具配对边界（不能跨越未答复的工具调用切开）。

---

## 15. context — 请求上下文扩展（`packages/context/`）

| 包名 | 职责 | 关键扩展点/事件 | Java 映射建议 |
|---|---|---|---|
| `context/agent-instructions` | 每会话工作区指令（AGENTS.md 兼容）加载：首步注入基线 + 文件工具成功后动态发现/变更/移除 | `agent/pre-step` 注入 durable user-role 消息；观察 `tools/result` 的 read/write/edit 成功 | `AgentInstructions`：发现（$DSH_HOME + 项目根到 cwd）+ SHA-1 去重 + 预算（maxBytes 必填） |
| `context/time-context` | 每步时间上下文（当前时刻/浏览器时区/经过时长） | `agent/pre-step` 追加 sourced `UserMessage`（`{plugin:'time-context'}`） | `TimeContext`：每 step 采样注入 |
| `context/session-reference` | 其他会话的有界快照（未细读，建议读 src/） | `ctx.sessionReferenceResolver` | 会话引用解析器 |
| `context/tmux-context` | tmux 位置上下文（未细读，建议读 src/） | — | 可选 |

**Java 要点**：agent-instructions 的刷新是 touch-driven（无 watcher）：下次成功的 read/write/edit、resume 基线对账、pre-step 恢复被压缩遮蔽的基线时生效；按目录去重（CLAUDE.md 与 AGENTS.md 内容相同只渲染一次）；model-visible 文本中 `</system-reminder>` 必须转义防逃逸。

---

## 16. interaction — 人机协作平面（`packages/interaction/`）

| 包名 | 职责 | 关键扩展点/事件 | Java 映射建议 |
|---|---|---|---|
| `interaction/commands` | 人类命令注册/分发：`ctx.commands`；`command/run` + `command/done` 日志对 | `commands/change`；`parseCommand()`（斜杠+小写名） | `CommandRegistry` + `CommandExecution` |
| `interaction/user-approval` | 一次性审批缝：`ctx.approval.request()` → allowed-once/rejected/cancelled/unavailable；fail closed | `approval/request`（waterfall，应答者决定）；`approval/asked`+`approval/decided` 审计事件；`approval/policy`（ask/never） | `ApprovalService` + `ApprovalPolicy`；审批渠道接口（Web/CLI 适配器） |
| `interaction/user-questions` | provider 中立的人机问答缝（未细读，建议读 src/） | `ctx.userQuestions` | `UserQuestionService` |
| `interaction/tool-ask-user` | 模型侧 `ask_user_question` 工具（问题数组/选项/多选） | 注册 `ctx.tools` | `ToolAskUser` |
| `interaction/permission-presets` | 权限预设表：`sandbox/mode` × `approval/policy` 捆绑（workspace-write+ask / danger-full-access+never）；`permissionPresets/preset` 事件 | `set(session, name)` → 各旋钮 setter；`current(events)` 折叠 | `PermissionPresetService` |

**Java 要点**：approval 只读一次授予（无 allow-always/记住规则）；`'never'` 在交互式分发前拒绝；审批请求必须属于打开的 agent turn；预设选择事件先于旋钮事件记录用户意图。

---

## 17. guard — 循环卫生守卫族（`packages/guard/`）

| 包名 | 职责 | 关键扩展点/事件 | Java 映射建议 |
|---|---|---|---|
| `guard/timeout-policy` | 工具调用超时执行器（零配置）：对声明 `timeoutMs` 的工具在 `tools/execute` 包装，超时替换为 `TOOL_TIMEOUT` 结构化结果 | 注册 `tools/execute` 监听器；协作式（只通知 signal，不硬杀） | `ToolCallTimeoutPolicy`：`exec.signal` 融合定时器 |
| `guard/repeat-tool-reminder` | 重复工具调用提醒（非阻断）：连续相同 (tool, 规范化参数) 达到阈值注入升级提醒 | `tools/post-execute` 的 `additionalContexts`（以注入 user/message 落日志）；`agent/pre-step` 重置链 | `RepeatToolReminder`：每 agent `WeakMap<Agent, Chain>` |

**Java 要点**：timeout 是**协作式**——只通过 signal 通知，忽略 signal 的工具不会停（只有 signal-forwarding 工具应声明 timeoutMs）；repeat-reminder 检测坐落在 post-execute（denied 调用也计数），提醒不替换 `tool/result` 内容。

---

## 18. web — Web 能力族（`packages/web/`）

| 包名 | 职责 | 关键扩展点/事件 | Java 映射建议 |
|---|---|---|---|
| `web/web` | `ctx.web` 服务定义：search/fetch 双操作、provider 注册/选择策略、`WebError` 分类 | `registerSearchProvider`/`registerFetchProvider`；执行时选择（配置 id / 唯一可用 / 歧义） | `WebRuntime`：`WebSearchProvider`/`WebFetchProvider` SPI + 选择策略 |
| `web/web-search-deepseek` | DeepSeek 原生搜索 provider（Anthropic-compatible Messages API + `web_search` 服务端工具；严格模式） | 注册 `ctx.web`；每搜索经 `ctx.credentials` 解析；记录辅助请求日志事件 | `DeepSeekWebSearchProvider`（辅助模型调用，purpose 标记） |
| `web/web-search-exa` / `-perplexity` | 其他搜索 provider（未细读，建议读 src/） | 注册 `ctx.web` | 对应 provider |
| `web/web-fetch-http` | 匿名 HTTP(S) fetch provider：URL 校验、同源重定向、字节/字符上限、二进制拒绝 | 注册 `ctx.web`；超时是资源 backstop（工具预算归 timeout-policy） | `HttpWebFetchProvider`：`java.net.http.HttpClient` |
| `web/tool-web` | 模型侧 `web_search`/`web_fetch`：schema、guidance、HTML→markdown 呈现、`card:'web'` 渲染意图 | 注册 `ctx.tools`；`fetchTimeoutMs`/`searchTimeoutMs` 声明为 `ToolDefinition.timeoutMs` | `ToolWeb`：jsoup + HTML→markdown 转换 |

**Java 要点**：
1. provider 注册**能力**而非工具；工具按产品启用（config）而非后端可用性注册——provider 缺失/歧义在执行时以结构化 `WebError` 失败，schema 保持稳定。
2. `web-fetch-http` 的 SSRF 防护**明确延期**（README 原话：部署能触及敏感内网时必须禁用它）——Java 移植应把 SSRF 防护列为默认开启的改进项。
3. 搜索/抓取结果有上限（`maxResults` 截断 + `truncated` 标志；fetch 字节/字符双上限），模型可见文本有固定模板。

---

## 19. mcp — Model Context Protocol（`packages/mcp/`）

| 包名 | 职责 | 关键扩展点/事件 | Java 映射建议 |
|---|---|---|---|
| `mcp/mcp-client` | MCP 客户端桥：连接外部 MCP 服务器（stdio / streamable-http），把服务器工具注册为 `mcp__<serverName>__<rawName>` 原生工具 | 每个服务器一个插件实例；工具命名规范化（64 字符 + 确定性哈希）；HMR 热换 | 官方 **MCP Java SDK**（`io.modelcontextprotocol.sdk`）+ `ToolRegistry` 注册适配器 |

**Java 要点**：工具公名 = `(serverName, rawName)` 的纯函数（与连接顺序无关）；重连退避（500ms 起倍增、30s 上限、10 次放弃）；toolCallTimeoutMs 默认 60s。

---

## 20. lsp — 语言服务器能力族（`packages/lsp/`）

| 包名 | 职责 | 关键扩展点/事件 | Java 映射建议 |
|---|---|---|---|
| `lsp/lsp` | `ctx.lsp` 服务定义：按 branded id 注册 provider + 扩展名映射、每查询选择、词汇、`LspError`；仅 4 个语义操作（goToDefinition/findReferences/goToImplementation/hover），无通用 JSON-RPC 逃生口 | provider 注册（能力而非工具） | `LspRuntime` 接口 + `LspProvider` SPI |
| `lsp/lsp-stdio` | 通用多服务器 stdio 后端（经 `ctx.fs`+`ctx.subprocess`；文档按查询瞬态打开） | 注册 provider | **LSP4J** stdio 后端 |
| `lsp/tool-lsp` | 模型侧 `lsp` 工具（1-based UTF-16 光标坐标） | 注册 `ctx.tools` | `ToolLsp` |

---

## 21. code-runtime — 代码执行能力族（`packages/code-runtime/`）

| 包名 | 职责 | 关键扩展点/事件 | Java 映射建议 |
|---|---|---|---|
| `code-runtime/code-runtime` | `ctx.codeRuntime` 服务定义：执行一段模型写的程序、捕获打印与返回值、host 提供的异步绑定 | Code Mode Consumer（`tools: { mode: code }` → `run_code` 工具 + 按语言生成的 SDK） | `CodeRuntime` 接口（P2 可选） |
| `code-runtime/code-runtime-worker-thread` | 工作线程后端（TypeScript SDK 渲染） | 注册 `ctx.codeRuntime` | Java 侧可用 GraalJS/Groovy 沙箱执行或委托外部运行时 |

**Java 要点**：Code Mode 是**大件**（生成 TS/Python SDK 类型、`run_code` 调度桥、子调用并发池、64MiB 输出上限）；建议 P2 且 Java 侧先只做 native mode，code mode 后置。隔离是"遏制"不是安全边界。

---

## 22. e2b — 远程运行时族（`packages/e2b/`）— 实验性

| 包名 | 职责 | 关键扩展点/事件 | Java 映射建议 |
|---|---|---|---|
| `e2b/e2b` | 创建/销毁 E2B 沙箱、准备工作目录、共享 SDK 句柄 | `ctx.e2b` | P2/实验：E2B 官方 SDK |
| `e2b/fs-e2b` / `subprocess-e2b` | 把 `ctx.fs`/`ctx.subprocess` 缝实现到 E2B 世界（未细读，建议读 src/） | 注册 `ctx.fs`/`ctx.subprocess` | 远程执行世界 provider |

**Java 要点**：价值在验证"执行世界可插拔"——bash/LSP/terminal 消费者只依赖 `ctx.fs`+`ctx.subprocess`，换后端不改消费者。移植价值低，建议 P2 甚至跳过。

---

## 23. sandbox — 进程沙箱能力族（`packages/sandbox/`）

| 包名 | 职责 | 关键扩展点/事件 | Java 映射建议 |
|---|---|---|---|
| `sandbox/sandbox` | 进程沙箱服务定义 + 共享升级词汇；模式 read-only / workspace-write / danger-full-access | `ctx.sandbox` | `SandboxProvider` 接口 |
| `sandbox/sandbox-local` | 本地平台后端（bwrap/Landlock/Seatbelt/Windows ACL；未细读，建议读 src/） | 注册 `ctx.sandbox` | Java 无 JVM 内沙箱（SecurityManager 已弃用）→ **OS 级**：容器/bwrap 包装进程 |
| `sandbox/sandbox-policy` | 每会话沙箱策略解析：部署默认 + `sandbox/mode` 事件折叠 + 不可变 workspaceRoot；`sandbox:policy` 运行时上下文 | `setSandboxMode(session, mode)` 写路径；`resolve({session, mode})` | `SandboxPolicyService` |

**Java 要点**：
1. 策略**单一归属**（fs 栅栏与 bash runner 共享 `writableRoots`，避免漂移成"分裂世界"）。
2. 模式解析优先级：显式批准 > 会话 `sandbox/mode` 事件折叠 > 部署默认；workspace root = 会话创建时的不可变 cwd。
3. Java 移植的现实：进程级隔离用容器/外部 runner（bwrap、Firejail、Windows Job Object + 受限令牌）；文件系统栅栏可在 JVM 内做路径规范化 + 包含检查（同 fs-sandbox 的"策略栅栏非内核边界"定位）。

---

## 24. storage / settings / credentials / identity

| 包名 | 职责 | 关键扩展点/事件 | Java 映射建议 |
|---|---|---|---|
| `storage/storage`（+ json/sqlite/domain 子包，未细读，建议读 src/） | 非会话数据存储 hub：backend 注册表 + 数据形式（kv/domain） | `ctx.storage`；`domain/changed` | `StorageHub` + `StorageBackend` SPI（JSON/SQLite） |
| `settings/settings`（+ settings-file，未细读，建议读 src/） | 用户设置：命名空间注册、分层解析（schema 默认 > 组合 base > 用户文档）、提交、watch | `settings/updated`、`settings/document-updated`；revision 冲突检测 | `SettingsService`：namespace 注册 + 分层合并 + 深冻结快照 |
| `credentials/credentials`（+ credentials-local，未细读，建议读 src/） | 凭据引用缝：配置只带引用不带密钥；**每操作解析**（不跨操作缓存）；空值 = 未配置 | `credentials/updated`；`resolve/describe/set/unset`（describe 永不带值） | `CredentialService`：`CredentialRef` 解析器（env/文件/KMS 提供者） |
| `identity/anonymous-user-id` | 一个匿名 Harness-home 关联 id（遥测/反馈/DeepSeek 请求头） | — | `AnonymousUserId`：持久化随机 id |

**Java 要点**：settings 的"可选服务"模式（无 provider 时一切照常解析 entry config）；credentials 三原则（引用不落配置、每操作解析、空值即缺席）是安全设计，直接照搬。

---

## 25. jobs / schedule / feedback / attachment / session-query / workspace

| 包名 | 职责 | 关键扩展点/事件 | Java 映射建议 |
|---|---|---|---|
| `jobs/jobs`（+ jobs-local/tool-jobs，未细读，建议读 src/） | 后台任务注册表：start/get/read/kill/wait/onJobDone/onJobsChanged；owner 隔离 | `ctx.jobs`；生产者扩展 `JobKindMap` | `JobRegistry`：任务句柄 + 完成通知（CompletableFuture/虚拟线程） |
| `schedule/schedule` | 会话内提醒：durable 状态在会话日志（`schedule/change` 事件流），进程内 timer 只等 live root Agent；冷会话恢复时补做 | 工具 + live root-Agent timer owner（组级 README + AGENTS.md 已读；子包 src 建议细读） | `ScheduleService`：`schedule/change` 折叠 + 定时器 owner（Timer/ScheduledExecutor） |
| `feedback/command-feedback` / `message-feedback` | 两种独立契约：不可变 `feedback/record` 日志事件（不进模型上下文）+ 每条 assistant 消息的 sidecar 评分/备注（storage-domain） | `feedback/record`（telemetry-otel 消费）；`messageFeedback` Remote 契约 | `FeedbackService` + 消息侧车（存储域） |
| `attachment/attachment`（+ attachment-local，未细读，建议读 src/） | 不可变附件引用、图片上限、存储服务；内容寻址（sha256） | `ctx.attachments` | `AttachmentStore`：内容寻址存储 |
| `session-query/session-query`（+ sqlite/tool/export 子包，未细读，建议读 src/） | 会话检索：受权读取、关系查询、搜索（独立于压缩） | `ctx.sessionQuery`；模型侧工具（workspace 受权） | `SessionQueryService`（SQLite FTS） |
| `workspace/workspace`（子包未细读，建议读 src/） | 持久工作区实体：用户目录 + 标题 + 有序会话成员 | `ctx.workspaceRegistry`；realpath 规范 | `WorkspaceRegistry`（存储域） |

---

## 26. acp / sdk / api — 进程外与远程接口

| 包名 | 职责 | 关键扩展点/事件 | Java 映射建议 |
|---|---|---|---|
| `acp/acp` | 自动化 Agent Client Protocol 服务器（子包未细读，建议读 src/） | ACP 会话驱动 | ACP 协议实现（Java 版，P2） |
| `sdk/protocol` | SDK 运行时线上协议：换行分隔 JSON-RPC 2.0 + 命名请求/结果/通知类型 | `initialize`、`session/prompt`、`shutdown`、`session.event`、`session.status`、`subagent.started/finished` | Jackson + 自写行帧 JSON-RPC 编解码 |
| `sdk/server` | stdio JSON-RPC 服务器插件：每 sessionId 一个 agent；stdout 只走协议 | 注入 `agents`；shutdown 语义（flush→dispose→exit 0） | `JsonRpcServer`（Spring 侧：stdio 服务或可映射为 WebSocket/REST） |
| `sdk/client` | **TypeScript 客户端（不需要移植）** | — | Java 侧如需要自建 client（或直接 HTTP/WS 调用） |
| `api/remotes` | Host BFF 策略 + Client Remote 装配（未细读，建议读 src/） | 消费 `ctx.remote` | 业务 API 层（Spring Controller/Service） |
| `api/gateway` | Typert 一元 RPC 端点（Host 分发 + Client Remote） | `ctx.typertGateway` / `ctx.remote`；`@Remote` 标记 | **不移植 Typert**：Spring MVC/WebFlux REST + WebSocket 直接替代 |

**Java 要点**：sdk 的价值在"另一进程驱动 harness"——Java 版可直接提供 JSON-RPC over stdio 或 WebSocket；`session.event` 推送全量会话事件（wire 词汇含 SessionEvent/ContentBlock）。

---

## 27. preset / boot — 组合与启动

| 包名 | 职责 | 关键扩展点/事件 | Java 映射建议 |
|---|---|---|---|
| `preset/agent-presets` | per-agent 组合：preset 目录（`agent.cordis.yml`）→ 每次进程挂一次 standing mount，agent scope key 父链到 mount → 该 agent 独享工具/段/投影 | `mount(agentCtx, id)`（在 factory setup 中）、`composeFrom`（子代理绑定）、`recompose`（仅空白会话）、`agent-preset/selected` 事件 | `AgentPresetRegistry`：目录发现 + 按 agent 作用域装配（Java 侧对应"每 agent 实例化自己的 ToolRegistry/SystemPrompt 层"） |
| `preset/persona` | persona 可组合行：shadow 部署 persona 或 complete 独占 system prompt；仅限作用域内挂载 | — | `PersonaRow`：system-prompt 段（order 0） |
| `boot/app-boot`（+ cmdline，未细读，建议读 src/） | 应用 bin 共享启动胶水：.env 加载、fail-loud Loader 守卫、快照感知配置、settle-the-tree 启动序列、profile/bundle | 库（无 ctx key） | Spring Boot 自动配置 + `ApplicationContextInitializer`；profile = Spring profile/外部化配置 |

**Java 要点**：preset 是"每会话不同组合共存于一个进程"的关键机制——Java 侧必须让 agent 作用域注册表实例化而非全局单例；persona 只允许作用域挂载（全局 persona 槽已被 system-prompt 拥有）。

---

## 28. 不需要移植（明确标注）

| 目录 | 原因 |
|---|---|
| `client/*` | 前端 UI（React 等），Java 侧由 Web/桌面前端另做 |
| `host/*` | Web 宿主（webserver/apiproxy 等），Java 侧以 Spring Boot Web 取代 |
| `typert/*` | 类型图生成/RPC 代码生成器，Java 用普通接口/record 取代 |
| `extensions/*` | Cordis 生态集成 |
| `test-support/*` | 测试设施（Java 侧自建 JUnit 测试基建） |
| `util/*` | 零依赖工具库，Java 侧用标准库/Spring 取代 |
| `examples`、`website`、`native`（node-addon Landlock runner）、`python` | 示例/文档/原生 runner/Python SDK |
| `bundle/*` | 可安装 profile 补丁层（部署打包概念，Java 侧用 Spring Boot starter 组合取代） |
| `sdk/client` | TS 客户端，Java 侧自建（如需） |
| `hooks/*`、`spill/*`、`self-modification/*` | 任务清单外；hooks 是 Claude Code/Codex 桥（Java 侧可视需要）；spill 是超限输出落盘（可并入 storage）；self-modification 是运行时自我挂载插件（Java 移植价值低） |

---

## 29. 建议的 Maven 模块划分（按优先级）

### P0 核心（最小可跑 agent 闭环）

| Maven 模块 | 对应 DSH 组 | 说明 |
|---|---|---|
| `dsh-core`（或拆 `dsh-scope` / `dsh-session` / `dsh-agent` / `dsh-tools` / `dsh-system-prompt` / `dsh-agent-loop` / `dsh-agent-default-model`） | core/* | 先定 `Agent` 接口与事件词汇，再实现 loop；session 事件溯源 + surface 投影是地基 |
| `dsh-llm` + `dsh-llm-deepseek` + `dsh-llm-retry` | llm/* | `LlmRuntime` 抽象 + Spring AI 适配器 + `Flux<StreamChunk>` 流式；重试挂 turn 级恢复点 |
| `dsh-session-persistence`（+ `-jsonl`/`-sqlite`） | session/* 持久化部分 | 写协调器 + 崩溃修复 + 连续 seq 不变式 |

### P1 能力（让 agent 真正干活）

| Maven 模块 | 对应 DSH 组 |
|---|---|
| `dsh-fs` + `dsh-tool-fs` + `dsh-tool-fs-search` + `dsh-fs-observation-policy`（+ `dsh-fs-sandbox`） | fs/* |
| `dsh-shell` + `dsh-bash-local` + `dsh-tool-bash` + `dsh-shell-env`（+ `dsh-bash-sandbox`、`dsh-tool-bash-persistent` 后置） | shell/* |
| `dsh-subprocess` | subprocess/* |
| `dsh-skill` + `dsh-tool-skill` | skill/* |
| `dsh-plan-mode` / `dsh-tool-todo` / `dsh-goal` | plan / todo / goal |
| `dsh-subagent`（+ `dsh-tool-subagent`、`dsh-tool-subagent-control`、in-process driver）+ `dsh-workflow` | subagent / workflow |
| `dsh-compaction` | compaction/* |
| `dsh-context`（agent-instructions、time-context） | context/* |
| `dsh-interaction`（approval、ask-user、commands、permission-presets）+ `dsh-guard`（timeout-policy、repeat-tool-reminder） | interaction / guard |
| `dsh-search` + `dsh-tool-web`（provider 后置） | web/* |
| `dsh-sandbox` + `dsh-sandbox-policy`（OS 级 runner） | sandbox/*（安全关键，建议随 P1 的 shell/fs 一起） |
| `dsh-session-projection` + `dsh-session-title` + `dsh-session-stats`（+ telemetry 后置） | session/* 投影/标题/统计 |

### P2 外围

| Maven 模块 | 对应 DSH 组 |
|---|---|
| `dsh-jobs` + `dsh-schedule` + `dsh-feedback` | 后台任务 / 提醒 / 反馈 |
| `dsh-storage` + `dsh-settings` + `dsh-credentials` + `dsh-identity` + `dsh-attachment` + `dsh-workspace` + `dsh-session-query` | 数据与配置基础设施 |
| `dsh-mcp-client` | mcp（官方 Java SDK 桥） |
| `dsh-lsp` | lsp（LSP4J） |
| `dsh-code-runtime`（Code Mode） | code-runtime |
| `dsh-terminal` | terminal（pty4j，持久 shell） |
| `dsh-session-telemetry`（Micrometer/OTel） | session-telemetry |
| `dsh-acp` / `dsh-sdk`（protocol+server） / `dsh-api`（REST 取代 gateway） | 进程外与远程接口 |
| `dsh-preset` + `dsh-boot`（Spring Boot 自动配置） | preset / boot |

---

## 附：跨组设计要点速查（移植时最易踩坑）

1. **事件溯源是总纲**：会话日志是唯一真相源；模型可见的一切必须可重建；UI/投影/统计都从日志派生。
2. **waterfall 语义**：Spring 事件广播不支持短路，需要自建有序监听器链并保留 `next()` 委托语义；拒绝"用监听器顺序假装优先级"。
3. **作用域 vs Spring 单例**：agent 级注册表必须实例化；这是 preset/subagent/Code Mode 一切能力的前提。
4. **协作式取消**：`AbortSignal`（自建信号）贯穿模型调用、工具体、子进程、PTY；超时策略只通知不硬杀（除子进程可 escalate 到强杀）。
5. **能力缝三分**：Service Definition / Service Provider / Consumer 分离（shell、fs、web、subagent、compaction 都是），Java 侧用 SPI + 可选注入保持可插拔。
6. **fail loud**：配置/注册错误在加载期或最早可解析点抛错，绝不静默降级（少数有意的 graceful degrade 除外，如无 settings provider）。
7. **错误码体系**：`HarnessError` + 稳定 code（`FS_*`/`WEB_*`/`LlmError`/`TOOL_TIMEOUT`…），模型可见错误文本稳定并含修复指引——这是 agent 自纠正能力的一部分。