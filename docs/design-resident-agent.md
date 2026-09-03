# 支柱② 常驻 agent — 专项设计（M3）

> 状态：**草案（待评审）** — 评审点见 §8；上游路线图见 `design-upstream-replication.md` §3-②/§5（M3/M4）。
> M3 = ②专项设计（本文 + DAG 计划）；M4 = ②落地（queue/abort/resume/身份恢复，5 入口契约不变、13+ 测试类原样通过）。

## 1. 背景与目标

上游（DeepSeek Harness）是常驻 `ReactLoopAgent`：Phase 状态机 + Inbox 排队 + AbortSignal 全链路 + 身份持久恢复。
Java 现状是无状态 `AgentLoopService` 同步 turn 循环、无会话级串行锁。M2（支柱①）已把**写侧并发**修到
fail-fast（会话行 gate 序列化 + `SessionConcurrentModificationException`），但同会话并发 chat 仍
"后到者失败/交错"，没有排队语义；取消是协作标志但无对象态；断点续跑靠 SSE executionId 续流（临时机制）。

本设计目标（对齐上游三机制，收益见上游文档 §2 行 24）：

1. **queue**：同会话并发 chat 排队顺序执行（而非竞态报错）；跨会话天然并行。
2. **abort**：取消及时化（流式中断 / step 间隙检查），留下可恢复的对象态。
3. **resume**：断点续跑由临时机制（executionId 续流）转正为对象能力（重启/断线后可恢复）。
4. **身份恢复**：稳定 agentId + 会话维度恢复（对齐上游 `restoreOrCreateConfigured`）。

约束（上游文档 §2 已核实，本设计必须遵守）：

- `AgentLoopService` **5 个调用入口**：`SessionController`（REST chat/stream/compact）、
  `ChatCompletionController`（OpenAI 兼容）、`JsonRpcDispatcher`（SDK）、`SubagentRunner`、
  `GoalService`（自动续行）。常驻化不能破坏任一入口契约。
- 现有 **19 个 dsh-agent 测试类**（10 个 `AgentLoop*` + 9 个相邻组件，2026-09-03 清点）围绕
  "同步 turn 循环" 语义编写 → 行为契约锁：改造后**原样通过**（同步调用仍同步返回）。
- M2 后 `mvn verify` 457 tests 全绿（红线基线）；DeepSeekE2ETest 无 key 自跳过（既有约定）。

## 2. 现状事实（2026-09-03 代码级核实）

### 2.1 AgentLoopService（无状态门面，`dsh-agent`）

- 公开入口：`run(request)`（L176，→ `runWithGoalContinuation`）、`stream(request, onToken, onToolEvent)`（L218）、
  `manualCompact(sessionId)`（L187）、`compactionBoundary(sessionId)`（L318）。
- 内部：`runWithGoalContinuation`（L239）执行 `execute(request.withPlanStepId(...), ...)` 后，若
  active goal 未超限则循环 `reserveNextRound` 续轮；`executeInner`（L390）是 step 主循环
  `while(true)`（L525）：LlmGateway 调用 → 解析工具调用 → `executeToolCalls` → 追加结果 → 回到模型，
  直到 finish/max-tokens/无工具。
- **无 per-session 对象/队列**：每个调用直接在请求线程跑完整同步循环。同会话两个并发 chat →
  M2 后写侧各自事务经会话行 gate 串行化，但 turn 之间**可交错**或后者在 append 时撞 gate
  抛 `SessionConcurrentModificationException`（fail-fast，客户端自行处理）——无"排队"语义。
- 取消：字段 `SessionCancellation sessionCancellation`（可空，setter 注入）；step 循环内检查
  （模型/工具间隙）。`SessionCancellation`（`dsh-agent`）是 per-session 协作标志
  （ConcurrentHashMap<String, AtomicBoolean>），cancel/isCancelled/clear（clear 每 turn 开始）。
  当前"取消延迟"≈ 一次 LlmGateway 调用阻塞时长（非流式无法中断；流式可中断）。
- `RequestSeriesTracker`（实例字段，per-session ConcurrentHashMap，线程安全）：以
  (sessionId, executionId, headerFingerprint) 判定 series / 开启原因（initial|resume|change|series）——
  已有"身份/系列恢复"的现成锚点，靠审计事件 `EventLogReader.lastAgentTurnHeaderFingerprint` 回读。
- `AgentRunRequest(sessionId, userMessage, modelOverride, apiKeyOverride, agentId, executionId,
  delegationDepth, planStepId)`：**agentId + executionId 字段已存在**，身份恢复不必新增契约字段。

### 2.2 5 个调用入口（调用形态）

| 入口 | 位置 | 调用 | 语义 |
|---|---|---|---|
| SessionController REST chat | dsh-api `SessionController:179` | `run` | 同步返回全文 |
| SessionController compact | dsh-api `:208/:433` | `manualCompact` | 同步 /compact |
| SessionController SSE chat | dsh-api `:277` | `stream` + SseExecutionStore 续流 | 流式 + 断线重连 |
| ChatCompletionController | dsh-api `openai/ChatCompletionController:80/109` | `run`/`stream` | OpenAI 兼容 |
| JsonRpcDispatcher（SDK） | dsh-sdk `server/JsonRpcDispatcher:127` | `run` | RPC 同步 |
| SubagentRunner | dsh-subagent `SubagentRunner:70/92` | `run`（child session） | 子代理 start/followup |
| GoalService | dsh-goal `GoalService` | `run` | 自动续行（另有内部 goal-round 循环） |

> 上游文档口径"5 入口"= SessionController/ChatCompletion/JsonRpc/Subagent/Goal；SessionController 含
> chat/stream/compact 三形态。全部走 `run/stream/manualCompact` 三个门面方法 —— 常驻化收敛点明确。

### 2.3 SSE 断线续流（临时机制，待转正）

`SseExecutionStore`（dsh-api，内存）：executionId → `Execution`（已发生事件快照 + running 标志 +
续传订阅者）。断线后客户端以同一 executionId 重连：重放已发生事件 + 续推实时事件
（SessionController:223-249：生成 `sse-<uuid>` executionId，`stream(request.executionId(...))`）。
executionId 同时驱动 series 判定（2.1）——**resume 语义的现成载体**。

### 2.4 断点/崩溃现状

- `InterruptedTurnRepairer`（dsh-session，M1/M2 未改）：审计事件侧"中断 turn 修复"。
- 消息/事实真相：M2 后 `anchon_session_fact` 为真 + 投影自愈（`SessionFactReplayer`/
  `SessionProjectionVerifier`）——**重启后消息视图完整性已由支柱①保证**；缺的是"agent 执行对象
  在跑到哪一步"的对象态持久化。

## 3. 目标模型

每会话一个**可恢复执行对象** `ResidentAgent`（对应上游 `ReactLoopAgent` 角色）：

```
per-session ResidentAgent
├─ Phase 状态机：idle ⇄ queued → running ⇄ aborted(paused) → idle
│                  └── error → idle(可重试)/需人工
├─ Inbox（队列）：同会话 chat 请求排队（FIFO），串行消费
├─ 取消信号：abort() 置位 → 协作检查点（流式中断 / step 间隙）→ 停到安全点
├─ 身份：agentId（稳定）+ sessionId + 当前 executionId / 续流快照
└─ 持久化 phase（供重启恢复）
```

`AgentLoopService` **保留为单轮门面**：`run/stream/manualCompact` 内部把工作委托给
`ResidentAgentRegistry` 取/建该会话的 `ResidentAgent` 并 `enqueue`（同步调用 = enqueue + 等待完成
并返回 `AgentRunResult`，语义与现状一致）；5 入口无需改动。`manualCompact` 作为非模型命令直接
作用于会话（无需排队消费模型 turn，但与 running 互斥：若 agent 正在跑，入队为命令项）。

## 4. 核心机制设计

### 4.1 会话级串行队列（queue）

- `ResidentAgentRegistry`（dsh-agent，Spring bean）：`ConcurrentHashMap<sessionId, ResidentAgent>`；
  `agent(sessionId)` 取或建（内存对象；多实例部署不在本里程碑——D6）。
- `ResidentAgent` 持有 `ExecutorService`（虚拟线程 per-session 执行者，D2 决策）或直接在调用线程
  执行 + `ReentrantLock`/队列。推荐**每会话一个虚拟线程执行者**：无请求时挂起（虚拟线程空闲成本低，
  对齐上游"每会话一个执行者，无请求时挂起"）；请求线程 enqueue 后等待 Future 完成（同步返回语义）。
- 队列项：`Turn(userMessage, modelOverride, apiKeyOverride, agentId, executionId, planStepId,
  delegationDepth)` 或 `Command(manualCompact/abort)`；`ChatMessage` 驱动。
- 消费循环：从 inbox 取队首 → 若 running 冲突（同会话已有执行）则等锁 → 串行执行 → 发完成信号
  给等待方。FIFO 保证同会话 chat 顺序 = 到达顺序（对齐上游 Inbox）。
- 行为变化：并发 chat **不再报错**而是排队（契约变化点，见 §6 红线 2b；M2 的写侧 gate 保留为
  兜底防线，不删除——跨实例/异常路径仍 fail-fast）。

### 4.2 取消信号链（abort）

- 沿用 `SessionCancellation` 语义（per-session 协作标志）→ 由 `ResidentAgent` 持有并检查，
  替代 AgentLoopService 的 setter 字段方式（门面委托后从对象态读取）。
- 检查点（现状已具备，补齐"对象态记录"）：
  1. step 循环顶部（L525 while 前/每次迭代前）；
  2. `streamStep` 的流式订阅：abort 时调用流订阅 cancel → Spring AI 流式中断（取消延迟 ≈ 0，
     对齐红线"cancel 延迟显著下降"）；
  3. 工具步间隙（工具本身不中断，同上游 AbortSignal 语义——已在 SessionCancellation 注释约定）。
- abort 后 Phase → `aborted`（=paused）：已完成的 step 已落 fact（真相无损），未开始的 step 不执行；
  记录 abort 点（当前 executionId + 已执行 seq 上限）供 resume。

### 4.3 resume 与身份恢复

- **agentId**（稳定）已在 `AgentRunRequest`；`ResidentAgentRegistry.agent(sessionId)` 等价
  上游 `restoreOrCreateConfigured`：同一会话固定同一执行对象，直到显式释放。
- **turn 级 resume**（D4 决策）：resume = 以同一 executionId 重新入队续轮。现状已支撑：
  SSE 断线以同一 executionId 重连 → `SseExecutionStore` 重放 + 续推；`RequestSeriesTracker`
  对同 executionId 判 `reason=resume` 续 series。常驻对象把它**收编为对象状态**：
  `ResidentAgent.state = {executionId, seriesId, lastSeq, headerFingerprint}`。
- **重启恢复**：Phase 持久化（D3）→ 启动时 `RestoreAgentRunner`（ApplicationRunner）扫描
  phase ∈ {running, aborted, queued} 的会话 → 重建 `ResidentAgent`（aborted → 可 resume；
  running（崩溃时残留）→ 置 aborted/error 等用户 resume；queued → 重新入队）。消息真相由
  支柱①自愈（verifier），执行态由本文恢复——两支柱互补。
- 恢复只保证"turn 边界一致"，不恢复中断在工具调用中间的进程内局部状态（工具副作用不可回滚，
  同上游：工具本身不中断/不重放——工具结果以 TOOL 消息落 fact，重跑会看到该事实并继续）。

### 4.4 Phase 状态机与持久化（D3）

- Phase：`idle | queued | running | aborted | error`。
  - idle：对象存在但无任务（保留供上下文/身份，可释放）。
  - queued：Inbox 非空等待消费。
  - running：消费中（模型/tool step）。
  - aborted：被取消/断线后停驻（可 resume）。
  - error：turn 抛异常（TURN_ERROR 已发布；对象回 idle 供重试，重试由客户端/上层决定——不自动）。
- 持久化推荐（D3 决策点）：**不新增表**——会话维度 phase 事件写入 `anchon_session_event`
  （`AGENT_PHASE` type + payload={phase, executionId, seq}，复用现有事件基建与 REQUIRES_NEW
  落库），读侧 `EventLogReader` 已能回读最新 phase；避免引入新的 phase 表/列与一致性负担。
  备选：会话表加 `phase` 列（更简单，但混入真相行）。→ 推荐事件化（对齐"执行也是过程"）。

### 4.5 AgentLoopService 门面委托（零入口改动）

- `run(request)`：`registry.agent(sessionId).enqueue(turn).join()` → 返回 AgentRunResult。
- `stream(request, onToken, onToolEvent)`：同上 + 回调透传（对象态内执行，SSE 订阅流式步骤中
  onToken 直通）。executionId 续流逻辑不变（SessionController 侧）。
- `manualCompact(sessionId)`：命令项入队（非模型 turn；running 时排队等当前 turn 完成）。
- `compactionBoundary(sessionId)` 等只读方法无需入队。
- 门面保留现有同步返回 → **测试契约锁原样通过**（多数测试直接调 run 并断言同步结果；
  虚拟线程 join 保证同步可见性）。

## 5. 与上游对齐点

| 上游 | 本设计对应 |
|---|---|
| ReactLoopAgent 常驻对象 | `ResidentAgent`（per-session，queue/abort/resume） |
| Inbox 排队 | `ResidentAgent.inbox` FIFO + 虚拟线程执行者 |
| AbortSignal 全链路 | `SessionCancellation` 协作标志 + 流式订阅 cancel + step/工具间隙检查 |
| Phase 状态机 | idle/queued/running/aborted/error（事件化持久化 D3） |
| 身份 restoreOrCreateConfigured | `ResidentAgentRegistry.agent(sessionId)` + agentId/executionId 收编 |
| 断线续流（现 Java 临时机制） | `SseExecutionStore` executionId 续流 → 收编为对象状态 |
| 重启恢复 | phase 事件回读 → `RestoreAgentRunner` 重建 + 支柱①投影自愈 |

## 6. 验证红线（M4 完成判定）

1. `mvn verify` 全绿（457 tests 基线不回退）；DeepSeekE2ETest 等 e2e（无 key 自跳过约定不变）。
2. **并发/排队（新增用例）**：
   a. 同会话并发 N 个 chat → **全部成功、按入队顺序执行**（消息序 = 到达序），不再抛
      `SessionConcurrentModificationException`；
   b. 契约变化显式声明：M2 语义"并发写 fail-fast 报错"→ M4 语义"并发 chat 排队"；
      写侧 gate/UK 保留为兜底（跨实例/异常路径仍 fail-fast，不删除）；
   c. 跨会话并发 chat 仍并行（不互相阻塞）。
3. **取消（新增用例）**：流式 turn 中 abort → 订阅在流式步骤内被取消（延迟显著低于等完整 turn）；
   非流式在 step 间隙停止；工具调用不中断（语义保持）。
4. **恢复（新增用例）**：
   a. SSE 断线以同 executionId 重连 → 续流（现行为保持，收编后不回归）；
   b. 模拟进程崩溃（phase=running 残留）→ 重启 `RestoreAgentRunner` 置 aborted → resume 后续轮
      消息序完整（依赖支柱① fact 自愈）；
   c. abort 后 resume → 从安全点继续，不重放已完成 step（fact 无重复 seq）。
5. **契约锁**：19 个 dsh-agent 测试类原样通过（同步调用仍同步返回）；5 入口代码零改动或仅
   "run/stream/manualCompact 方法体委托"级改动（接口签名不变）。

## 7. 风险与回退

- **行为漂移**：并发 chat 从"报错"变"排队"是**有意契约变化**（上游对齐），需在 §6-2b 明示；
  其余对外语义（同步返回、消息序、导出、compact 文本）用既有测试锁死。
- **对象态内存泄漏**：长期不活跃会话的 ResidentAgent 需释放策略（idle 超时移除；D6 边界内
  至少提供 `release(sessionId)` 显式释放 + 会话删除钩子）。
- **虚拟线程与请求线程映射**：join 等待 + 超时（SSE 断开不能挂死执行者）——需定义等待取消策略
  （客户端断开 → 若 turn 还在跑：转后台继续 or abort？上游：常驻 agent 独立于连接 → 转后台继续，
  由 abort API 显式停。Java 侧默认同此，SSE 断开不断执行，仅停止向断开的订阅者推送）。
- **回退**：每步独立提交可回退；门面委托失败可回落"直接执行"开关（`dsh.agent.resident=false`，
  默认 true 后一版再翻转；灰度期可配）。——决策点 D8。

## 8. 评审要点（请 reviewer/用户拍板）

- **D1 收敛面**：常驻对象承载哪些入口？（推荐：run/stream/manualCompact 全委托；只读方法不入队）
- **D2 执行载体**：每会话一个虚拟线程执行者 + inbox 消费（推荐） vs 调用线程直接执行 + 锁排队？
  虚拟线程下"无请求挂起"成本低，但队列在内存、多实例需外置（D6）。
- **D3 Phase 持久化**：事件化写入 `anchon_session_event`（推荐，复用基建） vs 会话表 phase 列 vs
  纯内存（重启丢失，不满足红线 4b）？
- **D4 resume 粒度**：turn 级（同 executionId 续轮，复用现有 series/续流，推荐） vs step 级
  （恢复 mid-turn 工具循环，需持久化 step 指针——成本高，远期）？
- **D5 abort→resume 语义**：abort 停驻可 resume（推荐） vs abort=终态（重新 chat）？
- **D6 部署边界**：单实例内存队列（推荐，本里程碑） vs 需多实例共享队列（引入外部队列，范围外）？
- **D7 范围**：不含 surfaceOp/支柱③/命令注册表（M5 按需）；`manualCompact` 仅作为命令项排队。
- **D8 回退开关**：`dsh.agent.resident` 灰度开关是否纳入 M4？（推荐纳入，默认 true 可回退 false）

## 9. M4 落地拆解草案（待 D1-D8 拍板后转正式 DAG）

> 每步独立可合并；验证红线映射见 §6。

- **M4-1 对象骨架**：`ResidentAgent` + `ResidentAgentRegistry`（per-session 取/建/release；
  虚拟线程执行者 + inbox 队列 + Phase 枚举）；单测（FIFO、同会话串行、跨会话并行）。
- **M4-2 门面委托**：AgentLoopService.run/stream/manualCompact 改委托 registry（同步 join）；
  5 入口零改动；**契约锁：19 测试类原样通过**（红线段 §6-5）。
- **M4-3 abort**：ResidentAgent 持有取消标志 + 流式订阅 cancel；`abort(sessionId)` API
  （SessionController 停止按钮改调它）；新增取消用例（红线 §6-3）。
- **M4-4 resume/身份**：executionId/seriesId/lastSeq 收编为对象状态；SSE 断线重连同 executionId
  续流验证不回归；`resume(sessionId)` API（重发队首 or 续轮）；红线 §6-4a/4c。
- **M4-5 phase 持久化 + 重启恢复**：`AGENT_PHASE` 事件写 anchon_session_event；`RestoreAgentRunner`
  启动重建；崩溃模拟用例（红线 §6-4b）。
- **M4-6 并发排队收口 + 验证**：并发 N chat 排队有序用例（红线 §6-2）；`dsh.agent.resident` 回退
  开关；全套 `mvn verify` + e2e 全绿 = M4 达成。

## 10. 自查记录（2026-09-03）

- 与支柱①互补确认：重启后"消息真相完整"由 M2 fact+verifier 保证；本设计只补"执行对象态"，
  不重复做消息重建。
- 契约锁口径：上游文档"13+ 测试类"；2026-09-03 清点 dsh-agent 实际 19 类（§2.4/§6-5 用 19 表述，
  覆盖并超过 13+）。
- 并发语义变化（报错→排队）是上游对齐的核心收益（上游文档 §2 行 24 明确"修复同会话并发 chat 竞态"），
  已列为 §6-2b 显式契约变化，非隐藏漂移。
- 取消链路检查点复用现有 SessionCancellation 约定（工具不中断），不引入线程中断。
