# P2 深化设计（series durable · 辅助标注 · durable prune · 写安全 · 映射其余缺口）

> 前置：[design-upstream-migration.md](design-upstream-migration.md) v1（C-①②③ 已落地 `2458331`，4 模块 123 测试全绿）。
> 本文为 P2 深化四篇设计（DAG `plan-965b5513` 的 d-series / d-prune / d-write / d-misc），
> 现状均经源码核对（对照 `external/deepseek` @ `76fda7297`）。
> 设计共同原则：**只增不改既有载荷字段 / 事件类型语义 / 消息内联**，默认开关不改变现行为，schema 演进走 Liquibase。

---

## A. P2-① series durable 化 + 辅助调用点标注（d-series）

### A.1 现状与差距（已核）

| 点 | 现状 | 差距 |
|---|---|---|
| 载荷 | `ModelCallEventPayloads.requestPayload(model,messages,options,callSite,series)`：`requestSeries` 内嵌 `{seriesId,reason,startsSeries,stepInSeries}` | payload **无 header 指纹** —— 重启后无法判定 header 是否变化 |
| tracker | `RequestSeriesTracker`（dsh-agent，进程内 `ConcurrentHashMap`）：`onTurn(sessionId,executionId,headerFingerprint,surfaceReplaced)`；重启后 state 空 → 一律 `initial` | durable resume/change 缺失：重启后首 turn 无法区分「header 未变的延续」与「全新会话」 |
| 事件库 | `SessionEventEntity`（sessionId/seq/eventType/executionId/payloadJson）+ `SessionEventRepository.findBySessionIdOrderBySeqAsc` | 无按 eventType 定向查询；payload 内 callSite 需解析 JSON 判定 |
| 辅助调用点 | `SessionTitleService.maybeTitle` 与 `CompactionService` 摘要调用发布 `MODEL_REQUEST`（callSite=session_title / compaction），**无 requestSeries**（4 参重载） | 未纳入系列语义（C-① 实施记录明示 P2） |

header 指纹（`AgentLoopService.headerFingerprint(model, systemPrompt, toolRefs, options)`）是 tracker 判定 `change` 的输入；它不参与 messages（系列内 step 副本同 header）。

### A.2 设计

**A.2.1 载荷增 header 指纹键（向后兼容，只增不改）**

`ModelCallEventPayloads`：
- 新增 6 参重载 `requestPayload(model, messages, options, callSite, series, String headerFingerprint)`；
- payload 顶层写 `headerFingerprint`（`series != null && headerFingerprint != null` 时；辅助调用点 series 有但指纹无 → 不写该键；旧调用不传 → 键不存在，旧事件/旧测试不受影响）；
- 语义注释：指纹 = 对话 header 快照（model + systemPrompt + 可见工具集 + options 白名单）的 sha256，仅供重启后 durable `change/resume` 判定，不参与 messages。

`AgentLoopService` 两处 agent_turn 发布（`callStep`/`streamStep` 内 675/708）改走 6 参重载，传 `headerFingerprint`（方法内已可算）。

**A.2.2 durable 恢复（重启后首 turn 判定 resume/change）**

查询侧（dsh-session）：
- `SessionEventRepository` 增 `List<SessionEventEntity> findTop5BySessionIdAndEventTypeOrderBySeqDesc(String sessionId, SessionEventType type)`（上限 5 足够——同 turn 多 step 全同 header，取最近一条 agent_turn 即可；多取几条防御辅助调用点夹在中间）；
- `SessionQueryService`（或事件读取服务）增：
  `Optional<String> lastAgentTurnHeaderFingerprint(SessionId)`：
  取该会话最近 ≤5 条 `MODEL_REQUEST`，从新到旧解析 `payloadJson`，第一条 `callSite == "agent_turn"` 且含 `headerFingerprint` 则返回；无则 `Optional.empty()`（旧数据无指纹 → 按 initial 处理，与现状一致）。

判定侧（dsh-agent）：
- `RequestSeriesTracker` 增状态感知：`boolean hasState(SessionId)`；
- `onTurn` 保持纯函数签名不变（同进程语义零改动）；新增恢复入口：
  `Series onTurnAfterRestart(SessionId, executionId, headerFingerprint, surfaceReplaced, Optional<String> durableLastHeaderFingerprint)`
  —— 当 tracker 无该会话内存态且 durable 指纹存在时：同指纹 → `resume`；异指纹 → `change`；无 durable → `initial`（回落现状）。`seriesId` 生成规则不变（`s-<executionId>-<counter>`，重启后 counter 从 1 重新计——durable 判定只影响 reason，不影响 seriesId 唯一性：executionId 本就唯一）。
- `AgentLoopService.executeInner` turn 入口（现 505 行 `onTurn` 处）：
  ```
  RequestSeriesTracker.Series series;
  if (!requestSeriesTracker.hasState(sessionId)) {
      series = requestSeriesTracker.onTurnAfterRestart(sessionId, executionId, headerFingerprint,
          compaction.compacted(), sessionQueryService.lastAgentTurnHeaderFingerprint(sessionId));
  } else {
      series = requestSeriesTracker.onTurn(sessionId, executionId, headerFingerprint, compaction.compacted());
  }
  ```
  同进程内后续 turn 有内存态 → 不查库（零额外 IO）。

**A.2.3 辅助调用点纳入系列语义（独立 series，callSite 维度）**

上游辅助请求（标题/压缩）不属于 agent_turn 请求系列；按 §1.4 设计给独立 seriesId + reason=initial（自身即边界首请求）：

- `SessionTitleService.maybeTitle(SessionId, String firstUserMessage, String executionId)`：
  事件发布改 5 参重载，`series = new RequestSeriesInfo("title-" + executionId, SERIES_REASON_INITIAL, true, 1)`。
  调用点唯一（`AgentLoopService` 407 行 `sessionTitleService.maybeTitle(sessionId, request.userMessage())`）→ 直接改签名并传 `executionId`（同方法作用域已有）。
- `CompactionService.compress`：摘要调用（`summarize` 内 177 行发布）带
  `new RequestSeriesInfo("compact-" + executionId, SERIES_REASON_INITIAL, true, 1)`。
  现状 `compress(sessionId, history, gateway)` 被测试直接调用且无 executionId → **保留旧方法**（内部 series=null，行为不变），新增 `compress(sessionId, history, gateway, String executionId)` 重载（内部带 series）。`AgentLoopService.maybeCompact`（778 行）改调新重载，executionId 自 executeInner 作用域传入（maybeCompact 已是实例方法，可在 executeInner 内持有 executionId 的字段/参数传递——实现时选参数透传）。
- 辅助调用点**不写 headerFingerprint**（无对话 header 概念）；callSite 已区分来源。

### A.3 不移植 / 取舍

- durable `request/header` 信封替换 messages 内联：维持（保留全量 messages 是既定决策）。
- `foldRequestHeader` 重建：Java 由 systemPromptService+session 配置重建，无需事件化。
- 增量引用降本（§1.4 第 5 点）：不动 payload 版本，保持每 step 全量自洽（属可选优化，非本 P2，避免查询方兼容面）。
- durable 判定只覆盖「进程重启后首个 turn」：多实例同时活跃场景归 P2-③ 写安全（本设计不引入跨实例共享态）。

### A.4 测试

- `ModelCallEventPayloadsTest`：6 参重载写 `headerFingerprint` 键；null/辅助调用不写键；旧 4/5 参键集合不变。
- `RequestSeriesTrackerTest`：新增 onTurnAfterRestart 用例（durable 同指纹→resume / 异→change / 空→initial / 有内存态不查库）。
- `SessionEventPersistenceListenerTest` / dsh-session：落库后 `lastAgentTurnHeaderFingerprint` 返回正确值；无 agent_turn 事件 → empty。
- `AgentLoopServiceTest` 集成：新 tracker + 预置历史 MODEL_REQUEST（含 headerFingerprint）→ 重启后首 turn reason=resume；标题辅助调用事件含 `requestSeries.seriesId` 前缀 `title-`。
- 回归：既有 series 断言（两 step 同系列、stepInSeries 递增）不变。

---

## B. P2-② 压缩截断 durable 行替换 + TOOL_RESULT_PRUNE 事件（d-prune）

### B.1 现状与差距（已核）

- `ToolResultPruner.prune`（threshold 8192 / head 4096 / tail 1024 码点，确定性，回放安全）只做**投影层读时截断**：`dsh-agent/MessageProjector.project()` 对 TOOL 消息调 pruner；日志（事件表 + 消息表原文）无损。
- `CompactionService` 压力测量已按投影后视图（v1 C-② 落地）；**尚无「压缩期把超阈值旧工具结果落为 durable 截断」**——即 design §2.4 方案 b（`TOOL_RESULT_PRUNE` 观测事件 + 投影行 `pruned` 标记）未实现，开关与报告 API 未全就位。
- 读路径：`AgentLoopService` 472/477 从 session 消息列表逐条 `messageProjector.project(m)` 组装模型消息。
- `SessionMessage` 领域 record（dsh-core）无 pruned 字段；`SessionMessageEntity` 无对应列；schema 由 Liquibase（0001..0005）管理。
- `SessionEventType` 无 `TOOL_RESULT_PRUNE`；`SessionEventPersistenceListener.PERSISTED` 白名单需扩展。
- 上游语义：`ctx.toolResultPruner` 在压力压缩的**范围选择前**可 advance surface 降 token；修剪不得破坏 tool-call/result 配对；报告每个 durable content replacement + 聚合码点缩减。

### B.2 设计（方案 b：原文保留 + pruned 标记 + 观测事件）

**语义不变式**：消息行 content **永不覆盖**（日志无损铁律）；`pruned=true` 后，**模型可见投影**用确定性截断视图（`ToolResultPruner.prune` 对任意长度幂等：已短内容原样返回），重放/重启安全。

1. **schema（Liquibase `0006-p2-hardening.yaml`，`db.changelog-master.yaml` 登记）**
   - `anchon_session_message` 增列 `pruned BOOLEAN NOT NULL DEFAULT FALSE`（addColumn + preConditions onFail=MARK_RAN，与既有 changeset 幂等风格一致）。
   - `anchon_session` 增列 `version BIGINT NOT NULL DEFAULT 0`（供 P2-③，同文件集中演进）。
   - 注：Hibernate `ddl-auto` 建库环境由实体列自动覆盖；Liquibase 只对既有库幂等补列（既有 master 注释约定）。

2. **领域/实体**
   - `SessionMessage` record 增 `boolean pruned`（默认 false）——编译器将标出全部构造点（toDomain/from/测试构造），实现时逐一适配；等价替代「读时额外查 pruned id 集合」不选（破坏投影自包含、两次 IO）。
   - `SessionMessageEntity` 增 `pruned` 列 + getter/setter + from/toDomain 映射。

3. **读路径（投影）**
   - `MessageProjector.project(SessionMessage)`：TOOL 消息且 `message.pruned()` → 内容 = `pruner.prune(content)`（与既有读时截断同函数，逻辑合并为 `pruned || needsPruning → prune` 一条路径）；非 TOOL / 未 pruned 原样。既有无 pruner 构造（单参）保持：pruned 行无 pruner 时原样返回（投影不缩水，但 B.4 修剪入口要求装配 pruner）。

4. **修剪入口（CompactionService）**
   - 增 `ToolResultPruneReport pruneOldToolResults(SessionId, List<SessionMessage> effective, int fromSeq)`（effective = boundary 后窗口内消息）：
     - 从**尾向前**扫描 `TOOL` 消息：内容超阈值（`pruner.needsPruning`）且**配对完整**——其对应 assistant(tool_calls) 与 tool 响应均在 effective 窗内（按 `toolCallId` 配对；assistant 的 tool_calls 出现在该 TOOL 消息之前即可视为该结果已完成消费，等价上游 toolPairing 平衡检查的 Java 判定：窗内已出现该 toolCallId 的 assistant 调用）；
     - 对选中行：`messageRepository.markPruned(messageId)`（置 pruned=true，不改 content）+ 追加 `TOOL_RESULT_PRUNE` 事件（payload：`{messageId, seq, toolName, originalCodePoints, projectedCodePoints, savedCodePoints}`）；
     - 返回 `ToolResultPruneReport(replacedCount, savedCodePoints, replacedSeqRanges)`（沿用 v1 已建 report 类型/`projectedPruneReport` 聚合风格，落地为实例方法真实报告）。
   - `AgentLoopService.maybeCompact`：当 `needsCompaction` 且有效窗**无可摘要范围**（如单条超大结果主导、head 无可压缩对话）→ 调 `pruneOldToolResults` 替代摘要；摘要收益更大时仍优先摘要。开关 `dsh.compaction.prune-old-results.enabled`（默认 `false`，Properties 类扩展；真实会话验证后再开）。
   - `SessionEventType` 增 `TOOL_RESULT_PRUNE`；纳入 `SessionEventPersistenceListener.PERSISTED`。

### B.3 兼容 / 影响面

- 新增事件类型与列均为只增；`pruned=false` 旧行行为零变化；开关默认关 → 默认路径不触发修剪。
- 消息投影仅对 pruned 行改变输出（与现读时截断一致的方向），不改变非超阈值行为。
- `SessionMessage` record 加字段 → 编译期全量适配（dsh-core/dsh-session/dsh-agent/dsh-compaction 构造点），无行为变化；序列化若存在（消息 JSON 持久化 toolCallsJson 等）不受影响（record 不直接 JSON 化到既有列）。
- 不选方案 a（就地覆盖 content）：破坏日志无损；不选"事件行替换"（事件表是观测日志，surface 在消息表）。

### B.4 测试

- `CompactionServiceTest` 扩展：pruneOldToolResults 返回报告计数/省量/seq 范围；配对不完整的 TOOL 不修剪；未超阈值不修剪；非 TOOL 不修剪。
- 集成（AgentLoop 或 Session 侧）：修剪后消息行 pruned=true 且 content 原文未变；`MessageProjector` 投影 pruned 行输出截断版；`TOOL_RESULT_PRUNE` 事件落库且 payload 正确。
- `MessageProjectorPruneTest` 回归（读时截断路径不回归）。
- 开关默认 false 的行为测试（关闭时 maybeCompact 不触发修剪）。

---

## C. P2-③ 写安全加固：SessionHandle 式写所有权 / 多实例防线 / flush 等价（d-write）

### C.1 现状与差距（已核）

| 上游概念 | Java 现状 | 差距判定 |
|---|---|---|
| `SessionHandle`(read/append/flush/close) | 无句柄对象；`SessionService`/`SessionEventPersistenceListener` 直写 JPA，每事件 `REQUIRES_NEW` 独立事务 | **durability 已等价**（比上游批窗口更即时）。缺「写者标识/冲突防线」而非句柄抽象本身 |
| flush checkpoint | 无内存缓冲，无落盘窗口 | **无需**（每条事务即时落库）；文档化即可 |
| 有界写批处理窗口 | 无批窗口 | **无需**（同上） |
| 写所有权单例 `SessionAlreadyOwnedError` | 单实例部署；`InterruptedTurnRepairer` 注释明示「多实例需先引入写所有权」 | **真缺口**：多实例共享事件表时 (a) 会话行并发 update（updateTitle/updateModel/append 的 read-modify-write）；(b) 事件 seq/id 本地生成 → 双写同 session 同 seq 撞 id 主键（已 fail-fast 于 id，但错误语义不明）；(c) Repairer 多实例同时启动双补竞态 |
| `SessionFormatUnsupportedError`/format refusal | 无（读取容错归 P2-④ D.3） | 本文不重复 |

### C.2 设计（最小可行防线集；不引入句柄抽象）

原则：单实例路径**零行为变化**；多实例共享表从「静默/晦涩冲突」变为「明确乐观冲突 + fail-fast 可重试」。

1. **会话行乐观锁（写所有权最小落地）**
   - `SessionEntity` 增 `@Version long version`（列见 B.2 schema）；`createSession/updateTitle/updateModel/append` 的 `sessionRepository.save(entity)` 读改写路径在并发下抛 `OptimisticLockingFailureException` → 事务回滚。
   - `SessionService` 将乐观冲突包装为领域异常 `SessionConcurrentModificationException`（RuntimeException，消息含 sessionId），REST/loop 可重试或告警；**不做自动重试**（冲突即双写实例竞争，重试语义属上层调度）。
   - 等价论证写入注释：事件追加是 insert-only（无读改写），乐观锁不适用也不需要——事件表冲突防线见 2。
2. **事件表唯一约束 fail-fast（写所有权归属 executionId）**
   - Liquibase 0006：`anchon_session_event` 增唯一约束 `uk_anchon_event_session_seq (sessionId, seq)`（preConditions onFail=MARK_RAN；若某环境已存在重复则…预检 not 存在才加，重复数据在单实例历史上不存在——seq 由总线本地保证单调）。
   - 事件追加唯一性由事件 id（`evt-<seq>-<sessionHash>`）主键**已**保证同 session 同 seq 撞键；唯一约束把错误提前到 seq 层并给出明确冲突信号（`DataIntegrityViolationException` on (sessionId,seq)）。
   - 语义：事件行的写所有者 = `executionId` 列（TURN_START 等携带）；`SessionEventEntity.from` 保持。
3. **InterruptedTurnRepairer 多实例竞态**
   - 现状：应用层「同一 executionId 已有 TURN_END 则跳过」在双实例同时扫描时可能双补 → 同 (sessionId,seq) 唯一约束使其**后者失败回滚**（fail-fast），重启再跑幂等收敛；单实例不变。
   - 补充：repair 写入沿用 `SessionEventRepository.save`（独立事务），冲突异常记 warn 不中断启动。
4. **flush checkpoint / 批窗口 / SessionHandle 句柄**：不引入。文档化等价论证（见 C.1 表），避免为无缓冲模型造抽象。
5. **`SessionAlreadyOwnedError` 对应**：多实例同 turn 并发执行属**应用层调度**（谁把同一 session 交给两个实例跑），超出持久化层；持久化防线（乐观锁+唯一约束）保证其后果可检测不静默。文档标注此边界。

### C.3 测试

- `SessionServiceTest`：乐观锁冲突（并发 updateTitle 同一 session → 一成功一 `SessionConcurrentModificationException`）；正常路径 version 递增。
- 事件唯一约束：同 session 同 seq 插入第二条 → `DataIntegrityViolationException`（repository 层测试，H2 环境执行 Liquibase 0006）。
- `InterruptedTurnRepairerTest` 回归（单实例幂等不变）。

---

## D. P2-④ 映射 §7 其余缺口（d-misc）

逐项评估并给出可落地子项；**无数据源/无收益项明确标注不实现**（诚实记录，不空做）。

### D.1 request/context（route capacity 折叠记录）→ **不实现（数据源缺失）**

- 上游：`request/context` 容量事件在 provider/model/contextWindow 变化时追加。
- Java：`OpenAiChatOptions` 无 contextWindow；`LlmGateway` 无 model→容量注册表；现仅 `maxTokens` 可选配置。**无容量元数据源** → 事件无从生成。design §1.4 原判 P3 维持。
- 落点（文档）：`DSH_JAVA_MAPPING.md §7.1 行 2` 标注「无 contextWindow 数据源（Spring AI 无该元数据），P3 —— 待引入 model 容量注册表后实现」。不做代码。

### D.2 MessageProjector 注册式 seam → **落地：投影阶段列表化（含 pruned 视图就位）**

- 现状：`MessageProjector` 构造注入单个 `ToolResultPruner`（可空），project 内固定调用。
- 设计：把「TOOL 消息 → 模型可见内容」的变换从单一 pruner 收敛为**单一投影策略**并文档化为 seam：
  `projectTool(SessionMessage m)`：`pruned || pruner.needsPruning(content) → pruner.prune(content)`（见 B.2.3，恰好承载 P2-② 的 pruned 读路径）。
  - 不引入多实现 SPI 注册表（当前仅一个投影变换；无第二实现前接口是空抽象——避免无关改动）。
  - **可选缓存：不引入**。投影发生在 agent 每次 turn 组装消息时，消息 append-only 但 pruned 标记会变（修剪发生在读后）→ 缓存失效复杂、收益低；若未来出现重复投影热点再评估。
- seam 文档化：`MessageProjector` 类注释说明「投影 = 唯一模型可见变换点（读时截断 + pruned durable 截断），新增模型可见变换应集中于此」。
- 测试：既有 `MessageProjectorPruneTest` 覆盖读时截断；新增 pruned 分支（B.4）。

### D.3 format refusal（读取容错，对应 §3.4 P2）→ **落地：事件读取集中容错 + 告警**

- 上游 `SessionFormatUnsupportedError` 拒绝无法忠实读取的格式；Java 事件行已落库、无"加载即拒绝"窗口 → **行级容错 + 告警**（§3.4 第 4 点）。
- 设计：dsh-session 增 `EventLogReader`（`@Component`）：
  - `List<SessionEvent> readEvents(SessionId)`：`findBySessionIdOrderBySeqAsc` → 逐行 `jsonUtils.fromJson(payloadJson)`；解析失败/未知 eventType → `log.warn("[EventLog] 跳过不可解析事件行 session={} seq={} type={} id={}", ...)` 并继续；返回完好事件。
  - 不抛整体失败（与上游"拒绝加载"的差异文档化：Java 无法整表拒绝，取容错保全其余日志）。
  - 存量调用方（repairer 等按需迁移——实现时仅新代码走 EventLogReader，不重构既有查询以免无关改动；或 repairer 若已自行解析则复用）。
- 测试：`EventLogReaderTest`：注入坏 payload 行 → 跳过并告警、相邻好行照常返回；空会话 → 空列表。

### D.4 CreateSessionOptions seeding → **落地：服务层种子会话**

- 上游 `CreateSessionOptions`（seeding）可在建会话时预置内容。
- 现状：`SessionService.createSession(title, model, cwd)`。
- 设计：
  - dsh-session 增 record `CreateSessionOptions(String title, String model, String cwd, List<SeedMessage> seeds)`；`SeedMessage(MessageRole role, String content)`（role ∈ SYSTEM/USER/ASSISTANT/TOOL 有 content 者）。
  - `SessionService.createSession(CreateSessionOptions)`：建会话行 → 依次 append 种子消息（复用 `append` 保证 seq 单调、updatedAt 更新）→ 发布 `SESSION_CREATED`（若总线注入）与各种子消息的对应事件（USER_MESSAGE/ASSISTANT_MESSAGE 按 role）；无 seeds 时等价现有 `createSession`。
  - 旧三参 `createSession` 保留（委托新方法 options=无 seeds），零行为变化。
  - **REST/boot 不接**（产品决策范畴，避免无关改动；服务层 API + 测试即交付）。
- 测试：`SessionServiceTest` 增 createSessionWithSeeds：会话建后消息列表 = 种子且 seq 1..n、updatedAt 更新；无 seeds 委托等价旧行为。

### D.5 文档同步（D 落地后）

`DSH_JAVA_MAPPING.md §7.2`：
- session 投影行：「部分覆盖，差注册式 seam/缓存（P2 可选）」→「投影 seam 集中化（读时截断 + pruned durable 截断）；缓存论证不引入（收益低/失效复杂）」；
- §7.1 行 2（route capacity）→ 标注 P3 数据源缺失；
- §7.1 行 3（persistence 强化）→ 补「会话行乐观锁 + 事件 (sessionId,seq) 唯一约束防线 + 事件读取容错（format-refusal 精神）」；
- `capability-map-draft.md` 相关行若引用缺口则同步（现状 159 行等描述性文字不涉及 Java 状态，仅核对）。

---

## E. 实施顺序与影响面汇总

| 设计 | 模块 | 默认行为 | schema（Liquibase 0006） | 兼容 |
|---|---|---|---|---|
| A series durable/辅助标注 | dsh-llm / dsh-agent / dsh-session | 不变（增 reason 准确性） | 无 | payload 增键 |
| B durable prune | dsh-compaction / dsh-agent / dsh-session / dsh-core | 关（开关默认 false） | 消息表 `pruned` 列 | 事件类型 + 白名单只增 |
| C 写安全 | dsh-session（+dsh-core 领域异常） | 不变（单实例无冲突） | 会话表 `version` 列 + 事件唯一约束 | 约束只增 |
| D misc | dsh-session / dsh-agent（注释） | 不变 | 无 | 只增 |

全程不动：messages 内联语义、既有 MODEL_REQUEST/RESPONSE 载荷字段、既有事件类型语义、既有 API 签名（仅新增重载/方法）。schema 演进仅加列/约束，不迁移数据。

---

## F. 实施记录（落地）

P2 全部四主题已按本文实现（DAG `plan-965b5513`），提交 `?`，联合回归 4 模块全绿。**落地 / 差异**对照：

| 主题 | 落地文件 | 与 §设计 的差异与理由 |
|---|---|---|
| A series durable/辅助标注 | `ModelCallEventPayloads`（6 参重载 + `headerFingerprint` 键）；`RequestSeriesTracker`（`hasState`/`onTurnAfterRestart`）；`SessionEventRepository`（`findTop5...Desc`）；`EventLogReader`（`lastAgentTurnHeaderFingerprint` 容错解析）；`AgentLoopService`（首见走 durable 恢复、callStep/streamStep 载荷带指纹、maybeTitle/compress 透传 executionId）；`SessionTitleService`（`title-<executionId>` series）；`CompactionService.compress(..., executionId)`（`compact-<executionId>`） | 无实质差异；durable 判定只覆盖重启后首 turn（多实例共享态归 C 写安全，不引入跨实例缓存） |
| B durable prune | `SessionMessage` record +pruned（9 参兼容构造）；`SessionMessageEntity`/`SessionMessageRepository.markPruned`/`SessionService.markToolResultPruned`；`SessionEventType.TOOL_RESULT_PRUNE`（白名单）；`CompactionProperties.prune-old-results.enabled`（默认关）；`CompactionService`（`selectPrunableToolResults` 纯选择 + `toPruneReport` + estimateTokens pruned 感知）；`MessageProjector` pruned 截断视图；`ToolResultPruneService`（dsh-agent 编排：标记 + 事件）；Liquibase `0006`（`anchon_session_message.pruned`） | **markPruned 编排在 dsh-agent**（dsh-compaction 不依赖 dsh-session 的消息 repository，模块边界）；**不挂钩自动 maybeCompact** — Java 压力估算 v1 已按读时截断视图计长（模型可见 token 不变），durable 标记不改变估算，自动触发无意义；durable 值 = 语义持久化 + 稳定截断承诺 + TOOL_RESULT_PRUNE 观测 + 未来原文降本 seam。TOOL_RESULT_PRUNE 事件 payload 用候选维度（messageId/seq/toolName/码点），非「行替换写」语义 |
| C 写安全 | `SessionEntity` @Version（乐观锁）；`SessionService.saveSessionRow`（saveAndFlush + 包装 `SessionConcurrentModificationException`）；`SessionEventEntity` @Table uniqueConstraints + Liquibase `0006` 唯一约束 `uk_anchon_event_session_seq`；`InterruptedTurnRepairer` 追加冲突 fail-fast 容错 | 与 §设计一致：不引入 SessionHandle 句柄 / flush checkpoint（单实例每事件独立事务已等价 durability，无内存缓冲），文档化论证 |
| D misc | route capacity → **不实现**（无 contextWindow 数据源，文档标注 P3）；投影 seam → `MessageProjector` 类注释收敛声明（+B 的 pruned 截断集中）；format-refusal 容错 → `EventLogReader.readEvents`（坏行跳过告警）；seeding → `SessionService.createSession(CreateSessionOptions)`（服务层，REST 不接） | route capacity 不实现是设计内结论；投影缓存论证不引入（B 注释） |

**新增测试**：`ModelCallEventPayloadsTest`(+2)、`RequestSeriesTrackerTest`(+5)、`EventLogReaderTest`(7)、`SessionTitleServiceTest`（回归）、`CompactionServiceTest`(+3)、`MessageProjectorPruneTest`(+2)、`ToolResultPruneServiceTest`(3)、`SessionServiceTest`(+3 乐观锁/seeding/markPruned 等)、`SessionWriteSafetyUnitTest`(3)、`SessionEventPersistenceListenerTest`(+1 TOOL_RESULT_PRUNE)、`InterruptedTurnRepairerTest`（回归）。

**schema 演进**：Liquibase `0006-p2-hardening.yaml`（pruned 列 / version 列 / 事件唯一约束），只增不改、幂等（MARK_RAN）；实体注解与 changelog 双轨（ddl-auto 测试库由注解覆盖）。
