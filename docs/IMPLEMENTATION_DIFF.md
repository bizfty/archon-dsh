# archon-dsh × external/deepseek — 实现差异分析

> 对照基线：
>
> - **上游参照** `external/deepseek` = DeepSeek Harness（TypeScript/Cordis monorepo）
>   commit `76fda729799fe9b3848dbe2c211d4b231032b81e`（2026-09-03，v0.1.2-rc.1 之后）
> - **本工程** `archon-dsh`（Java） = HEAD `c1400b5`（2026-09-03）
>
> 本文聚焦**代码实现层面的差异**（架构范式、机制、取舍），不重复能力面清单；
> 能力面逐组分析见 [capability-map-draft.md](capability-map-draft.md)、缺口与移植记录见
> [DSH_JAVA_MAPPING.md](DSH_JAVA_MAPPING.md) §7 与 [design-upstream-migration.md](design-upstream-migration.md)。

## 1. 总览

| 维度 | external/deepseek（上游） | archon-dsh（Java 复刻） |
|---|---|---|
| 语言/生态 | TypeScript + Node，pnpm workspace，50+ 包 | Java 21 + Maven，40 构建单元（`dsh-*`） |
| 宿主运行时 | Cordis（`vendor/cordis` fork）"一切皆插件"，`Service extends Service`、`ctx.*` DI、插件生命周期（start/teardown/abort signal、FactoryOwnership） | Spring Boot 4.1 IoC（`@Service`/`@Component`/构造器注入/`ObjectProvider` 破环），静态装配 |
| 源码规模 | ~21 万行 `.ts`（src 级，未含 tests/lib/node_modules） | ~9.7 万行 Java（其中 dsh-github 内 vendored kohsuke GitHub API 4.7 万行；自有 ~5 万行）+ Vue3 前端 ~30 文件 |
| agent 驱动 | 常驻可恢复 Agent（`ReactLoopAgent`）：Phase 状态机 + Inbox 排队 + AbortSignal | 无状态服务 `AgentLoopService`：一次请求一次同步 turn 循环，协作式取消标志 |
| 会话 | 事件日志（seq/chunk-rows/surface/request-header）为事实源，surfaceOp 决定模型可见面 | JPA 消息表为回放事实源 + 事件表审计/durable 恢复 + 投影/边界视图 |
| 工具 | schema 一套多消费（LLM JSON Schema、client UI、ts/py 类型绑定、presentation、PTC run_code） | `AgentTool` SPI + 自建 `ToolSchema`（仅供模型）+ 管线（gate/超时/后处理）+ Spring AI ToolCallback 桥 |
| 配置 | 每包 zod schema + settings 命名空间热 patch，运行期可动态装卸插件 | `application.yml` + `@ConfigurationProperties`/`Binder` + `dsh.settings` 命名空间（REST），编译期静态 |
| 沙箱 | OS 级真实隔离：landlock/bwrap/seatbelt、Windows ACL（FFI）、e2b 云端 | 进程内策略门控（`SandboxMode`）+ hooks 桥；OS 级隔离标 P2 未落地 |
| 前端 | client/ui-* 多包，Cordis client schema 驱动 + slots 插槽 | 单 `dsh-web` Vue3 + Vite + Element Plus，REST/SSE 手工对接 |

## 2. 运行时与架构范式

- **上游：插件 = 一等公民。** 每个能力包都是 Cordis 插件：注册 schema、声明服务、挂
  `ctx.on(...)` 事件、实现可被替换的接口（如 `sessionProjections.register(...)`、
  `dispatch.waterfall(...)`）。装配、生命周期（dispose/teardown）、作用域（scope）
  都在运行时容器中，第三方可以 `dsh-plugin` 动态接入（见 AGENTS.md / docs 的
  cordis-primer、capability-seams）。事件是主控流（session events 驱动投影与 agent）。
- **Java：模块 + Spring 装配。** 同层职责以 Maven 模块边界 + Spring bean 图表达；
  运行期是固定 bean 图。上游的"waterfall（可短路链）"在 Spring 事件广播里没有对应物，
  故 `SessionEventBus`（dsh-core）刻意**只做 observe-only 通知**（源码注释明示），
  需要短路/替换的扩展点（工具 pre-execute、system-prompt assemble）由各模块**自建有序链**。
  需要懒解析破环的地方用 `ObjectProvider`（修复过 ToolRegistry→SubagentTool→…→ToolRegistry 环）。

一句话：上游把"扩展性"做成**运行期插件协议**；Java 侧把它做成**编译期 SPI + 有序链**。

## 3. agent-loop：两种驱动模型（差异最深处）

### 上游 `core/agent-loop`（agent.ts 545 行 + index.ts 915 行）
- `ReactLoopAgent implements Agent` 是**常驻、可恢复对象**：Phase 状态机（idle/running 等）；
  Inbox 承载多 turn 输入（claim/wakeup），支持 turn 排队与"被 abort 后唤醒到 next-turn"
  （`wakingAfterAbort`）。
- 每步经 `dispatch.waterfall` 派发 pre-step decision（enter decision 可声明
  `startsRequestSeries`），`requestProposal`/epoch header 把模型配置随请求头持久到会话。
- 流式由 `BlockAssembler` 装配（含 interrupted blocks 处理），事件 `turn/start|end`、
  `step/start|end` 驱动 `turnBoundaryProjection`（宿主投影）。
- 配置化 agent 支持"身份持久 + remount 恢复"（`restoreOrCreateConfigured`：stable identity
  重启后 materialized history 继续）。
- `agent/error → disarm`（解除自动续行）。

### Java `dsh-agent/AgentLoopService`（871 行）
- **无状态服务，一次 request = 一个同步 turn**：`executeInner` 打开 turn →
  `append(USER)` → 可选标题 → 作用域/工具可见性过滤 → system prompt（**每 turn 一次**）→
  历史（压缩）+ **配对过滤**（窗口切断 tool_calls/TOOL 对时剥离，防 OpenAI 400）→
  `while(true)` step 循环：取消检查 → 步数上限 → `STEP_START` → 模型 call/stream →
  有 toolCalls 则 `executeToolCalls`（safe 并行入池/exclusive 串行屏障，**结果按下标保序**）→
  TOOL 逐条落库 + spill/pruner → 回填 → 循环；`TURN_END`/`TURN_ERROR`（error 时 goal disarm，
  对齐 goal-round-driver）。
- 取消是**协作式布尔标志**（SessionCancellation，模型/工具间隙检查），无 abort 信号链、
  无 inbox 排队（会话级串行由上层端点承担）。

| 语义 | 上游 | Java |
|---|---|---|
| turn 来源 | Inbox 排队 + 事件唤醒 | REST/SSE 同步触发 |
| agent 生命周期 | 常驻对象 + 可恢复身份 | 每次请求现建上下文 |
| 取消 | AbortSignal 全链路（含流） | 间隙协作式检查 |
| 步进派发 | waterfall（可替换决策链） | 硬编码 step 循环 + 事件广播 |
| 请求头/series | epoch header 随会话持久 | `RequestSeriesTracker` + 事件库 durable 指纹恢复（C-①，语义对齐） |

## 4. 会话与持久化：事实源不同

### 上游 `core/session`（index.ts 1224 行 + types/surface/seq-ranges/chunk-rows/request-header/repair）
- **会话 = 事件日志**：`seq-ranges` 管理区间、`chunk-rows` 处理大日志分块行、
  `request-header` 保存每 epoch 请求配置快照、`surface`（generation/replace/append
  surfaceOp）控制**模型可见面**、`repair.ts`/`invariant.ts`/`preparation.ts` 处理
  坏日志/修复。模型看到的序列由"日志 + surfaceOp + 投影"**派生**，日志本身保留全量。
- 投影注册表 `ctx.sessionProjections`：派生状态（turnBoundary 等）由事件流计算并可缓存。

### Java `dsh-session`（JPA）+ `dsh-core`
- **回放事实源是 JPA 消息表**（`SessionMessageEntity`，seq 单调），会话/事件/消息三实体；
  `SessionService.append` 逐条落消息；`SessionEventBus`（内存）只广播通知，持久化走监听器。
- 上游 surfaceOp/replace 的语义由 Java 侧**工具**近似：
  - 压缩遮蔽 = `CompactionBoundaryStore`（绝对日志下标，边界后起播，对应 replace）；
  - 模型可见裁剪 = `MessageProjector`（读时截断/配对过滤）+ `ToolResultPruner`（durable 标记）；
  - 事件表（`SessionEventEntity` + `EventLogReader`）承担**审计与 durable 恢复**（series 指纹、crash recovery `InterruptedTurnRepairer`）。
- 写安全：行 `@Version` 乐观锁 + `(sessionId,seq)` 唯一约束 fail-fast + 启动幂等 Repairer。

差异本质：上游"**日志为真、可见面为投影**"；Java"**消息行为真、事件为旁路审计**"。
C/P2 轮后 Java 通过事件库补齐了 durable 恢复/指纹，**能力语义对齐，数据模型仍异构**。

## 5. 工具系统

| 面 | 上游 core/tools（index.ts 1937 行 + 子模块） | Java dsh-tool |
|---|---|---|
| 定义 | `defineTool` + schema（schema.ts/json-schema.ts） | `AgentTool` SPI（name/description/schema/execute） |
| schema 消费 | 一套多用：LLM JSON Schema、client UI 动态表单、ts-types/py-types 双语言绑定、presentation | `ToolSchema`（自建，支持嵌套对象数组）仅供模型；UI 前端手工表单 |
| 注册 | 运行期 ctx 插件/动态 | `ToolRegistry`：bean 自动收集 + `registerDynamic` |
| 执行 | PTC/统一 runtime scheduler；工具超时属运行契约 | `ToolExecutionPipeline`：pre gates（有序，可短路）→ 独立 executor 超时杀 → post processors；`AgentToolCallback` 桥到 Spring AI ToolCallback |
| 代码运行 | `run_code` 由 PTC 传输（嵌套子调度可重建日志，只有外层结果进模型历史） | `run_code`/`workflow` 走 Node/Python JSON-RPC 回环，复用同一管线（懒解析破环后已注册） |

## 6. LLM 调用面

- 上游 `llm/*`：`LlmCallConfig`/`PreparedLlmCall`（路由 provider/model/reasoningEffort/
  maxTokens）、`BlockAssembler`（流块装配、interrupted blocks）、request series 标记、
  `token-meter`（TokenMeasurement/TokenSurfaceNode）。
- Java `dsh-llm`：`LlmGateway.call/stream` 包 Spring AI `ChatModel`；`OpenAiChatOptions`
  组装（model/temperature/toolCallbacks）；usage/finishReason 已建模；
  `RequestSeriesTracker` 提供 series 载荷与 durable 恢复；按 key 缓存独立 ChatModel
  （用户/agent/全局三级 key 优先级）。**无 BlockAssembler 等价物**：流式直接暴露文本
  增量（工具步非流式），中断/恢复由上层 SSE 与会话日志承担——属有意的简化。

## 7. 配置与类型

- 上游：包内 zod schema + `settings` 命名空间（schema 默认 > 用户覆盖，可热 patch），
  类型全部动态、可在运行期校验用户输入；插件可装卸即配置面可增删。
- Java：强类型 + `application.yml`（`dsh.agents.*`/`dsh.settings`…）；`AgentProperties`
  曾因 `DSH_*` 环境变量展平污染同前缀导致绑定失效，改用 `Binder` 显式绑定 Map 修复；
  `dsh.settings` 经 REST `GET/PUT /api/settings/{ns}/{key}` 覆盖，持久化走 storage。

## 8. 沙箱与安全

- 上游：`sandbox/sandbox-local`（landlock/bwrap/seatbelt + ACL grants + packed
  workspace closure）、`sandbox/sandbox-windows-acl`（win32 FFI token/ACL/path-boundary）、
  `e2b`（云端沙箱）、`sandbox-policy` 预设；另有 native/landlock-run。隔离是 **OS 进程级**。
- Java：`dsh-sandbox` 每会话模式（read-only/workspace-write/danger-full-access）+ 预设，
  以**进程内策略栅栏**作用于 fs 写与 bash 执行；`dsh-shell` 超时杀进程树、受管环境变量；
  hooks.json 桥（PreToolUse/PostToolUse）可做外部策略。真实 OS 级（bwrap/landlock）、
  PTY（pty4j）、LSP4J 均因本地仓库依赖不可得标 **P2 未落地**（见 mapping §5/§6）。

## 9. Web / 前端

- 上游：client/ui-* 多包（ui-conversation/ui-chat/ui-agent-preset/ui-subagent/ui-settings-
  plugins/ui-workspace/ui-cordis…）+ Cordis client 运行时：schema 驱动渲染、settings
  插件卡、slots 插槽扩展；BFF 事件化（api-session/*）。
- Java：单 `dsh-web`（Vue3 SFC + Vite + Element Plus，约 30 源文件），业务编排集中
  App.vue，组件手工实现（会话列表/SSE 流式/工具折叠/问答选择框/目标 CAS）；数据层
  `store.ts`（reactive 单例）+ `api.ts`。模型 schema 不驱动 UI——**前端为手工 REST/SSE 出口**。

## 10. 外围面

| 面 | 上游有 | Java 现状 |
|---|---|---|
| SDK | sdk/client + protocol + server；python/sdk、python/sdk-runtime | `dsh-sdk` JSON-RPC stdio **服务器**（initialize/session.*/shutdown）；无 Python SDK |
| ACP | packages/acp（Agent Client Protocol 参考） | 无 |
| webhook | packages/webhook（webhook/webhook-github） | 无 |
| 命令 | commands 子系统（slash 命令） | 仅手工特判 `/compact`（chat/SSE 端点拦截） |
| workspace/attachment | packages/workspace、attachment/*、api/workspace-controller | 无 workspace 多会话维度/附件（P3，产品决策） |
| 遥测/计量 | session-telemetry、token-meter | 事件含 usage，无 token-meter 服务面（P3 部分） |

## 11. "语义已对齐、实现异构"的模块清单（不重复建设）

| 语义 | 上游 | Java（机制不同但语义对齐） |
|---|---|---|
| 压缩遮蔽（surfaceOp replace） | surface.ts | `CompactionBoundaryStore` 边界起播 |
| 工具结果修剪 | compaction/tool-result-pruner seam | `ToolResultPruner` + `ToolResultPruneService`（压力按投影视图估算） |
| 作用域注册（shadow/AND 收窄） | core/scope | `AgentScopeRegistry`（同键 shadow、精确>通配） |
| 会话标题 | session-title-llm（first/all cadence） | `SessionTitleService`（first-prompt；all-prompts P3） |
| 目标 | goal/* | `dsh-goal`（phase/CAS/maxGoalRounds/blockedCode，e2e 通过） |
| 审批/问答 | interaction/* | `dsh-interaction`（fail-closed + SSE `approval_requested`/`question` 事件） |
| hooks | hooks/* | `dsh-hooks`（Claude Code 风格 hooks.json 桥，e2e 通过） |
| 通知 | session/event（waterfall） | `SessionEventBus` observe-only + 各模块自建短路链 |

## 12. Java 侧独有/增强（上游无对应或未做）

1. **OpenAI 兼容出口** `/v1/chat/completions`（JSON+SSE、X-Auth-Token 按用户路由）——非上游面。
2. **用户 profile + 鉴权**：注册/登录（PBKDF2）/token/LLM 配置/按用户 key 路由，全站鉴权开关。
3. **REST 人机协同端点**：审批/问答 pending + approve/reject/answer（对应上游 BFF 事件化未移植）。
4. **Vue3 Web 单包前端** + 前端问答选择框联动（QUESTION_REQUESTED → SSE question 事件）。
5. **`/compact` 手动压缩**、hooks.json、多 agent persona（`dsh.agents.*` + `planner` 实测）。
6. 由真实 DeepSeek API **端到端验证**的既有能力（§4.1 二十条记录）在 Java 侧持续回归。

## 13. 差异根因与取舍

1. **运行时契约不同**：TS 动态 + Cordis 容器 → 插件协议、schema 多端复用、热装卸；
   Java 静态 + Spring → 编译期 SPI、bean 图、强类型配置。扩展点设计目标相同（能力缝），
   实现载体不同。
2. **会话事实源不同**：上游事件日志 + surfaceOp（保留全量、投影裁剪）；Java 消息表 +
   边界/投影（裁剪即标记）。C/P2 轮后二者在 durable 语义上已等价，但"日志即真相"
   与"消息行即真相"仍是架构性差异，影响未来特性（如自由 surface 回卷、细粒度可见面）。
3. **agent 常驻与否**：上游 agent 是对象（队列/取消/恢复/身份）；Java 是每次请求的函数
   （简单、易测试，但多 turn 编排/排队/中断恢复须在端点层补）。
4. **依赖可得性驱动 P2**：真实 PTY、OS 级沙箱、LSP4J、GraalJS、javaai 参考实现等
   受本地仓库/外部依赖限制，Java 侧以进程内等价物先闭环。
5. 映射文档原则：**以能力语义为准，不做 1:1 代码移植**——上游 pre-release 快速迭代，
   每同步一次核对缺口（见 UPSTREAM_SYNC.md）。

## 14. 结论

- 能力面：Java 侧核心闭环 + 控制面 + 多数工具已对齐上游语义（见 DSH_JAVA_MAPPING §1/§7）。
- 实现面：**同语义、异构实现**是主旋律；真正的结构性差异集中在三处——
  `agent-loop`（常驻状态机 vs 同步 turn 循环）、`session`（事件日志+surface vs 消息表+边界投影）、
  `运行时扩展`（插件协议 vs SPI+有序链）。若后续要缩小差异，优先这三处。
