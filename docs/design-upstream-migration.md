# 上游缺口移植设计（series 化 request 日志 / ToolResultPruner 对齐 / 持久化 crash-recovery）

> 对照上游 `external/deepseek` @ `76fda7297`（2026-09-03）。本文是**移植设计**，
> 面向后续实施；现状描述均经源码核对（Java 侧包 `com.bizfty.anchon.dsh.*`）。
> 缺口来源见 [DSH_JAVA_MAPPING.md §7](DSH_JAVA_MAPPING.md)。

---

## 1. C-① series 化 request 日志

### 1.1 上游语义（已核）

- **durable `request/header` 信封事件**（`external/deepseek/docs/subsystems/session.md` §request/header；
  生产点 `packages/core/agent-loop/src/agent.ts` `buildRequest()`）：
  信封 = `EpochHeader{ config, adapterDefaults?, system?, tools? }`，只记**变化边界**的快照，
  reason ∈ `initial | resume | change | series`：
  - `initial`：loop 实例首次落信封（无既有折叠头）；
  - `resume`：loop 实例重启但已有折叠头；
  - `change`：header 内容变化（`headerEquals` 不等），若同时开新系列则带 `startsSeries: true`；
  - `series`：header 未变但**显式声明开新系列**（enter decision `startsRequestSeries`，或
    surface generation 变化 = surface 替换）。
  - 同系列内的**后续 step / 重试 / 追加 turn**：header 不变 → **不追加**任何事件；
    `foldRequestHeader(events)` 取最新快照重建。
- **请求本身不落日志**：模型请求 = 日志纯函数（config+system+tools 来自 `request/header` 折叠，
  messages 由 `deriveMessages()` 从 durable surface 重建）。
- `request/context` 容量事件：provider/model/contextWindow 变化时追加（独立于 header）。
- 附带词：`agent/pre-step` 的 enter decision 可带 `startsRequestSeries?: true`
  （`packages/core/agent/src/runtime-types.ts`）。

### 1.2 Java 现状（已核）

- `SessionEventType` 已有持久化事件：`TURN_START / TURN_END / TURN_ERROR / STEP_START /
  MODEL_REQUEST / MODEL_RESPONSE / USER_MESSAGE / …`；`MODEL_REQUEST`/`MODEL_RESPONSE`
  已纳入 `SessionEventPersistenceListener.PERSISTED` 白名单 → 落 `anchon_session_event`。
- `MODEL_REQUEST` payload（`dsh-llm/ModelCallEventPayloads.requestPayload`）= `{ model, callSite,
  messages[全量], options{白名单} }` —— **全量 messages 内联**（上轮明示保留此语义）。
- `AgentLoopService.executeInner`：TURN_START → USER_MESSAGE →（标题辅助调用）→ step 循环
  （STEP_START → streamStep/callStep 发 MODEL_REQUEST/RESPONSE）→ 工具回填后循环下一 step →
  TURN_END。同一 turn 多 step 时 messages 列表递增，**每 step 的 MODEL_REQUEST 都携带当时全量
  递增 messages** —— 即 N 份递增重复副本（上轮已识别，但未引入系列维度）。

### 1.3 差距结论

Java 无「系列边界」概念：下游无法区分「同 turn 内 step 的重复副本」与「跨 loop 实例 /
跨 surface 替换的新系列」；存储上同 turn N step 产生 N 份全量递增副本（用户决策保留 messages，
本设计不改此语义，只加**系列标注**供折叠/审计/可选压缩）。

### 1.4 设计

**目标**：保留 MODEL_REQUEST 全量 messages 内联的前提下，引入与上游词汇一致（initial/resume/
change/series + startsSeries）的**系列标注**，使下游能折叠每系列的边界与代表请求。

1. **载荷扩展**（`dsh-llm/ModelCallEventPayloads`）：
   - 新增 record `RequestSeriesInfo(String seriesId, String reason, boolean startsSeries, int stepInSeries)`
     （reason ∈ `initial|resume|change|series`，词对齐上游）。
   - `requestPayload(...)` 增重载带 `RequestSeriesInfo`；序列化进 payload 键 `requestSeries`
     （`{ seriesId, reason, startsSeries, stepInSeries }`）。callSite 保持。
   - `STEP_START` payload 同步带 `seriesId`（轻量，供事件流分组）。
   - 向后兼容：不带 series 的重载保留（旧测试/辅助调用点默认无系列=null）。

2. **系列跟踪器**（`dsh-agent` 新增 `RequestSeriesTracker`，per-session 状态，非 Spring bean 或
   `@Scope("prototype")`）：
   - `String currentSeriesId`：`exec-<executionId>` 派生（对齐上游"loop 实例边界"）。
   - 滚动规则（对齐上游判定，`executeInner` 每 turn 入口评估一次；step 间不变）：
     | 条件 | reason | startsSeries |
     |---|---|---|
     | 会话首 turn（事件日志无前系列） | `initial` | true |
     | 新 loop 实例（executionId 变化）且已有历史 | `resume` | true |
     | header 变化：model / systemPrompt 文本 / 可见 tool 集合 / options 白名单任一变化 | `change` | true |
     | surface 替换：`maybeCompact` 发生（boundary 推进）或边界起播点变化 | `series` | true |
     | 同 turn 后续 step（工具回填后） | 沿用当前系列 | false（不重复边界） |
   - header 指纹：`sha256(model + systemPrompt + sortedToolNames + optionsWhitelist)`，缓存在 tracker。

3. **辅助调用点**（`session_title` / `compaction` callSite）：各自独立 seriesId
   （`title-<executionId>` / `compact-<executionId>`），reason=`initial`（它们不是 agent_turn 系列，
   避免混入对话步进）。callSite 已区分，本设计不再改动其 messages 语义。

4. **不移植项**：durable `request/header` 信封替换 messages 内联（用户决策保留 messages）；
   `request/context` 容量事件（P2 可加，需 contextWindow 数据源=Spring AI 无此元数据，列为 P3）；
   `foldRequestHeader` 重建逻辑（Java 由 systemPromptService + session 配置可重建，无需事件化）。

5. **可选降本（P2）**：同系列内后续 step 的 MODEL_REQUEST messages 序列化为**增量引用**
   （首 step 全量 + 后续仅工具回填增量），事件行仍自洽。需 payload 版本字段，涉及查询方兼容。

### 1.5 测试建议

- `RequestSeriesTrackerTest`：initial→resume→change→series 各滚动条件（header 指纹变化、
  boundary 推进、executionId 变化、step 内稳定）。
- `AgentLoopSeriesIntegrationTest`：两 step turn → 首 step MODEL_REQUEST 带
  `startsSeries=true`，次 step `stepInSeries=2` 同 seriesId；压缩 turn → reason=series；
  标题辅助调用 → seriesId 前缀 `title-`。
- 回归：既有 `ModelCallEventPayloadsTest`（payload 键不变，新增键可选）。

---

## 2. C-② ToolResultPruner 对齐

### 2.1 上游语义（已核）

- `ctx.toolResultPruner`（`packages/compaction/compaction-tool-result-pruner`）是 **compaction 的
  可选前置 seam**（`external/deepseek/docs/subsystems/compaction.md`）：
  - 在压力/溢出压缩的**范围选择前**调用；纯修剪可 advance surface（无需摘要）即降 token；
  - 报告每个 **durable content replacement** + 聚合 Unicode 码点缩减（结果类型在
    `compaction-tool-result-pruner/src/types.ts`）；
  - 修剪不得破坏 tool-call/result 配对（`toolPairingBalancedBefore/After` 校验边界）；
  - `CompactionEngine` 另有 durable `compaction/start`/`compaction/end` 事件（压缩锁语义）、
    `ManualCompactionErrorCode`（busy/cancelled/changed/summary/commit/persistence）。

### 2.2 Java 现状（已核）

- `dsh-compaction.ToolResultPruner` + `ToolResultPruneProperties`（阈值 8192 / head 4096 / tail 1024）：
  **只做投影层读时截断** —— `dsh-agent.MessageProjector.project()` 对 TOOL 消息调
  `pruner.prune(content)`（头+标记+尾），**会话日志保留完整原文**（回放安全、确定性）。
- `AgentLoopService.maybeCompact`：仅摘要替换 + `CompactionBoundaryStore` 边界；**不使用 pruner**。
- 压缩辅助 LLM 调用已事件化（callSite=compaction）。

### 2.3 差距结论

Java 已覆盖「读时截断」（比上游 durable replace 更保守，日志无损）；缺上游「压缩期修剪旧工具
结果以 advance surface（无摘要降 token）」与「聚合缩减报告」。**单进程同步模型下
compaction/start-end 锁语义与 manual error 分类无竞态需求，不移植。**

### 2.4 设计

**目标**：补「冷结果修剪」路径，使高压力但摘要收益低时可通过 durable 替换旧 TOOL 结果降 token。

1. `dsh-compaction` 增 `ToolResultPruneReport`（record）：`replacedCount`、`savedCodePoints`、
   `replacedSeqRanges`（对齐上游聚合缩减报告）。
2. `CompactionService` 增方法 `pruneOldToolResults(sessionId, history, from)`：
   - 在 `effective`（boundary 之后）历史中选**超预算且已过配对安全点**的 TOOL 消息：
     从尾部向前扫描，仅修剪其 assistant(tool_calls) 已完整出现在有效窗内的结果
     （等价上游 toolPairing 平衡检查：Java 按 `SessionMessage.role/toolCallId` 配对判定，
     复用 AgentLoop 现有配对过滤的判定思路）。
   - durable 替换：把该 TOOL 消息内容**落为截断版**。实现选项：
     a. 就地更新 `SessionMessage` 行（需 `SessionMessageRepository` 增 update 方法 +
        `updatedAt`/`pruned` 标记；简单、直接，但破坏「日志完整原文」现状）；
     b. 追加 `TOOL_RESULT_PRUNE` 观测事件 + 投影表新列 `pruned=true`（读路径用截断版，
        原文保留）。—— **推荐 b**：维持 Java「日志无损」铁律，同时压缩窗口变小。
   - 返回 `ToolResultPruneReport`；`maybeCompact` 中当 `needsCompaction` 且无可摘要范围
     （如单条超大结果）时调用，之后仍可走摘要（摘要收益更大时优先摘要）。
3. 配置：`dsh.compaction.prune-old-results.enabled=false`（默认关，因改动投影语义，
   先经真实会话验证）；阈值复用 `ToolResultPruneProperties`。
4. 事件化：若选 b，`SessionEventType` 增 `TOOL_RESULT_PRUNE` 并纳入持久化白名单
   （与 MODEL 事件同级观测事件）。

### 2.5 测试建议

- `CompactionToolResultPruneTest`：单条超大 TOOL 在 boundary 后 → prune 返回 report、
  配对完整（assistant tool_calls 全覆盖）、后续回放消息为截断版；摘要可用时优先摘要不修剪。
- `MessageProjectorPruneTest` 回归（读时截断不受影响）。

---

## 3. C-③ 持久化 crash-recovery

### 3.1 上游语义（已核）

- 崩溃日志以 **open `turn/start` 无 `turn/end`** 结束；persistence **不截断不修复**，只丢弃
  未决 append 的 torn tail（`external/deepseek/docs/subsystems/persistence.md`）。
- 修复属 **resume/agent-loop**：经写句柄读日志 → 计算 `interruptedTurnClosers`
  （缺的 tool error、任何 open `step/end`、合成 `turn/end { reason: { kind: 'interrupted' } }`）
  → 以普通批量 append → 再发布 Session。`interrupted` 是**唯一 loop 自身不发射**的 TurnEndReason。
- 只读观察者（session-query）只内存平衡、不写回；写所有权单例（`SessionAlreadyOwnedError`）。
- `SessionHandle`（read/append/flush/close）+ flush checkpoint + 有界写批处理窗口；
  `SessionFormatUnsupportedError` + `SessionLocation`（format refusal 诊断）。

### 3.2 Java 现状（已核）

- `dsh-session` JPA：`SessionEventPersistenceListener`（order=-200，每条 `REQUIRES_NEW` 独立事务）
  把 TURN_START/STEP_START/MODEL_REQUEST/… 落 `anchon_session_event`
  （`SessionEventEntity`：sessionId/seq/executionId/payloadJson）。
- `AgentLoopService.executeInner` 同步：TURN_START → USER 消息 append → step 循环 → TURN_END。
  进程崩溃于中途 → 事件表尾有 TURN_START（+若干 STEP/MODEL/TOOL 事件）**无 TURN_END/TURN_ERROR**，
  且消息投影表可能含该 turn 的部分 USER/ASSISTANT/TOOL 行。
- 无「打开 turn 封口」：重启后新 turn 正常，旧执行事件孤儿残留（审计视图缺口）；
  消息投影（model-visible）不受影响（孤儿事件不参与 deriveMessages）。
- 无 format refusal：表结构由 Boot 管理；payload JSON 解析在读取路径由调用方负责。

### 3.3 差距结论

缺「中断 turn 封口」与「启动修复」。Java 每条事件独立事务 = 天然 durable（比上游批处理窗口更
即时），但 turn 打开/关闭**非原子**：TURN_START 先于 USER append；崩溃窗口内二者可能只落其一。

### 3.4 设计

**目标**：进程重启后事件日志无孤儿执行事件 —— 任何中断 turn 都在启动时被合成 `interrupted`
封口，且与消息投影一致（不产生模型可见幽灵）。

1. **封口语义**（对齐上游）：新增唯一的、loop 自身不发射的 reason `interrupted`。
   `SessionEventType.TURN_END` payload 的 `finish` 值域补 `interrupted`（文档 + 校验常量）。
2. **启动修复器** `dsh-session` 新增 `InterruptedTurnRepairer`（`ApplicationRunner`/`@PostConstruct`，
   幂等）：
   - 扫描 `anchon_session_event` 每 session：按 seq 分组，找**尾事件为执行类事件且自最后一个
     TURN_START 起无 TURN_END/TURN_ERROR** 的会话；
   - 计算 closers：缺失的 tool error（若有 TOOL_CALL 无配对 TOOL_ERROR/TOOL_RESULT —— 投影层
     配对判定同 §2.4）、任何 open 的 STEP_START（补 STEP 不补——上游只补 step/end 于 resume 时；
     Java 简化：仅合成 TURN_END，STEP 缺口由 STEP_START/STEP 计数已知即可）；
   - 追加 `TURN_END { finish: "interrupted", steps: n, tool_calls: m, executionId }`
     （与上游 `turn/end interrupted` 一致）；幂等：同一 executionId 已有 TURN_END 则跳过。
   - 事务：每会话一个事务；只写不读模型表（不触碰 `SessionMessage` 投影）。
   - **单实例**部署无写竞态；多实例前先引入 `SessionHandle` 式写所有权（P2，见 §3.5）。
3. **顺序修正（减少孤儿窗口）**：`executeInner` 调换 USER 消息 append 与 TURN_START 事件顺序为
   —— USER 消息行先落、TURN_START 事件再发？**否决**（SSE/下游按 TURN_START 先序依赖，且
   TURN_START 是 turn 打开语义锚点）。保持现顺序；改为**封口器同时兜底消息缺口**：
   对「有 TURN_START 无后续 USER_MESSAGE 事件」的会话，封口器不动消息投影
   （消息投影表无幽灵行即安全）；对「USER_MESSAGE 事件存在但无 TURN_START」的异常序列（如
   顺序调整后的未来版本），封口器不强行补 TURN_START（属新格式，见 §3.5）。
4. **读取容错（近似 format-refusal 精神，P2）**：事件查询路径解析 payload 失败时**跳过该行并
   记录**，不整体失败（对应上游拒绝 vs Java 容错差异：JPA 行无法整体拒绝，取容错+告警）。

### 3.5 不移植/后续项

- `SessionHandle` 单写者所有权 / flush checkpoint / 有界批处理窗口：JPA 每条独立事务已满足
  单实例 durability；多实例写共享表需引入 **写租约**（`executionId + sessionId` 唯一键 +
  乐观锁）—— P2。
- format refusal（表结构）：Boot 管理 schema，无等价需求；payload schema 演进用
  payload 内 `schemaVersion`（P2）。
- `turn/end interrupted` 由 loop 外的 Repairer 合成 —— 与上游一致（loop 自身不发射）。

### 3.6 测试建议

- `InterruptedTurnRepairerTest`：构造尾 TURN_START 无 TURN_END 的会话 → 修复后事件序列以
  TURN_END(interrupted) 收尾；幂等（二次启动不重复补）；有 TURN_ERROR 的不动；executionId
  匹配正确。
- `SessionEventPersistenceListenerTest` 回归：TURN_END `interrupted` 可正常序列化/持久化。

---

## 4. 实施顺序与影响面

| 项 | 模块 | 优先级 | 兼容性 |
|---|---|---|---|
| C-① series 标注（载荷+tracker） | dsh-core（可选）/ dsh-llm / dsh-agent | P1 | payload 增键，向后兼容（旧事件无 requestSeries） |
| C-② 冷结果修剪（事件式 durable prune） | dsh-compaction / dsh-session / dsh-agent | P1（默认 off） | 新事件类型 + 白名单扩展 |
| C-③ InterruptedTurnRepairer | dsh-session | P1 | 幂等补事件，只增不改 |

全程不动 messages 内联语义、不动既有 MODEL_REQUEST/RESPONSE 载荷字段、不引入无关重构。
