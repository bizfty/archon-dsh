# 复刻上游（DeepSeek Harness）可行性评估与路线图

> 位置：`docs/design-upstream-replication.md`
> 基线：archon-dsh HEAD `c1400b5` ↔ external/deepseek `76fda72`（v0.1.2-rc.1 后）
> 配套：实现差异总览见 [IMPLEMENTATION_DIFF.md](IMPLEMENTATION_DIFF.md)；能力面见
> [DSH_JAVA_MAPPING.md](DSH_JAVA_MAPPING.md) / [capability-map-draft.md](capability-map-draft.md)。

## 1. 先定标尺：本文说的"复刻"是什么

DeepSeek Harness 是 TS/Cordis monorepo（~21 万行 TS，50+ 包）。在 Java（Spring Boot 4.1 +
Spring AI 2.0）里**逐行 1:1 移植不可行也无必要**（Cordis 容器、schema 单源多端、BFF 事件化、
python SDK、运行时热装卸——收益面远小于成本，且上游 pre-release 不承诺 API 稳定）。

因此"复刻上游"在本工程的合理含义是 **机制对齐**：把 Java 侧已在语义上对齐、但实现机制
异构的三处结构性差异，向"事件源会话 / 常驻 agent / 显式扩展点"收敛；外围面保留 Java 出口。
判定是否值得做某一步的标准：**它是否消除了一个真实缺陷或解锁了语义无法表达的能力**，
而不是"和上游长得更像"。

## 2. 现状差距盘点（2026-09-03 核实）

| # | 差异 | 上游机制 | Java 现状 | 复刻的直接收益 |
|---|---|---|---|---|
| ① | 会话事实源 | 事件日志 + surfaceOp(replace/append)，消息视图由投影派生 | JPA 消息表为真；`anchon_session_event` 事件表**已旁路持久化**（seq 单调 + executionId + 可开 token 回放），但仅作审计/durable 恢复 | 消除**双写一致性缺口**；解锁 surfaceOp 自由裁剪/回卷；崩溃恢复彻底 |
| ② | agent 驱动 | 常驻 `ReactLoopAgent`：Phase 状态机 + Inbox 排队 + AbortSignal 全链路 + 身份持久恢复 | 无状态 `AgentLoopService` 同步 turn 循环；**无会话级串行锁** | 修复**同会话并发 chat 竞态**（`seq=count+1` 并发重复）；取消及时化；断点续跑由临时机制转正 |
| ③ | 扩展机制 | Cordis 插件协议（生命周期/teardown/schema/waterfall） | Spring bean 图 + 各模块自建有序链；`SessionEventBus` observe-only（注释明示：短路语义不进总线） | 统一扩展点抽象；第三方/未来能力可插拔（不要求热装卸） |

**已核实的关键约束**（设计时必须遵守）：
- AgentLoopService 有 **5 个调用入口**：`SessionController`（REST chat/stream/compact）、
  `ChatCompletionController`（OpenAI 兼容）、`JsonRpcDispatcher`（SDK）、`SubagentRunner`、
  `GoalService`（自动续行）。常驻化不能破坏任一入口契约。
- 现有 **13+ 个 AgentLoop 测试类 + ~20 条真实 API e2e** 全部围绕"同步 turn 循环"语义编写，
  必须当作**行为契约锁**（改造后应原样通过，或按契约迁移）。
- 事件持久化监听器 `SessionEventPersistenceListener` 以 `REQUIRES_NEW` 落库（order=-200），
  与消息 append 不在同一事务 → **现有双写即存在时序缺口**（事件可见先于/晚于消息），
  是 ① 的首要动因。
- 压缩边界（`CompactionBoundaryStore`）、工具结果修剪（`markToolResultPruned` 置位）、
  series 指纹（`RequestSeriesTracker` + `EventLogReader.lastAgentTurnHeaderFingerprint`）
  全部挂在"消息表视图"上——是 ① 迁移的主要对象。
- 会话/事件/消息三表已有 `(sessionId, seq)` 唯一约束与 `@Version` 乐观锁；事件表有
  默认排除 token、可开完整回放的开关（`dsh.event-log.include-tokens`）。

## 3. 三支柱改造设计概览

### 支柱 ① 会话 = 事件源（先做）

**目标态**：事件日志（升级后的 `anchon_session_event` + 消息事件）成为会话唯一事实源；
`SessionMessage` 行降级为**可重建的物化投影缓存**（读模型），或按需即时投影。

**关键机制设计点**：
- **append 语义收敛**：`SessionService.append` 改为"写事件为主"——新增 `MESSAGE_APPEND`
  事件类型（或直接用既有 USER/ASSISTANT/TOOL 事件），消息行的物化与事件写入收敛到
  **同一事务**（去掉 `REQUIRES_NEW` 旁路，改为事务内先事件后投影，或投影由事务内监听器
  同步物化），消除双写缺口。
- **重建器**：`SessionProjectionRebuilder` 从事件日志（含 token 回放开关）重放 → 重建
  消息序列/压缩边界/pruner 标记/series 指纹。重启幂等（现有 `InterruptedTurnRepairer` 泛化）。
- **视图裁剪迁移**：`MessageProjector`/`CompactionBoundaryStore`/`ToolResultPruner` 的
  输入从"消息表快照"改为"事件序视图"，surfaceOp（replace/append）成为显式裁剪语言。
- **落点模块**：dsh-session（核心）、dsh-core（事件面扩展）、dsh-agent（投影调用方）、
  dsh-compaction、dsh-api（导出/查询接口兼容）。

**成本/风险**：高成本、中风险。事件基建已有（seq/executionId/token 开关），最大工作量在
"投影重建一致性"与"既有查询/导出的行为保持"。**先出专项设计**（本路线第一步交付）。

### 支柱 ② agent 常驻化（后做，依赖 ① 的事件序视图）

**目标态**：每会话一个可恢复的执行对象（对应 `ReactLoopAgent` 角色），提供
queue + abort + resume；`AgentLoopService` 保留为**单轮门面**（同步调用被对象接受，语义不变），
5 个入口无需改动契约。

**关键机制设计点**：
- **会话级串行**：执行对象内排队（同会话并发 chat 排队而非竞态）；跨会话天然并行。
- **取消信号链**：`SessionCancellation` 布尔标志 → AbortSignal 等价物；Spring AI 流式
  `ChatModel.stream` 可中断；工具步间隙检查保留（工具本身不中断，同上游）。
- **身份/恢复**：稳定 agentId + 会话维度恢复（对齐上游 `restoreOrCreateConfigured`）；
  与现有一致：`SseExecutionStore` 的 executionId 续流机制**收编**为对象状态的一部分。
- **Phase 状态机**：idle/running/paused/error… 持久化 phase（可存事件表），供重启恢复。

**成本/风险**：高成本、中风险。最大风险在 13+ 测试类的语义迁移（同步调用仍应同步返回，
故多数测试**可原样通过**，风险集中在并发/取消/恢复新增用例）。虚拟线程下常驻对象
与请求线程的映射需设计清楚（每会话一个执行者，无请求时挂起）。

### 支柱 ③ 扩展点抽象（视需）

**目标态**：轻量 `DshExtension` 注册表（启动时从 Spring 上下文收集，带优先级/启用标志），
把"自建有序链"统一为一种可插拔表达；`SessionEventBus` 保持 observe-only 不变（短路语义
由扩展链承担，注释中的设计决定不改）。
**成本/风险**：中成本、低风险。收益中（统一扩展契约），可延后或不做。

## 4. 外围可选增量（不阻塞主干）

| 增量 | 上游 | Java 现状 | 触发条件 |
|---|---|---|---|
| schema 单源多消费 ✅ | 一套 schema → LLM/UI/ts-py 绑定/presentation | 工具元数据端点 + Settings 描述符（`/api/tools/meta`、`/api/settings/meta`） | 已落地（M10）→ `docs/design-schema-ui.md` |
| 命令子系统 | commands/* | 仅 `/compact` 特判 | 命令面膨胀时（低成本先落地：命令注册表） |
| BFF 事件化 | api-session/* | REST + SSE | 多端接入时 |
| python SDK / ACP / webhook | 有 | 无 | 生态需求时（ACP 可作 JSON-RPC 的扩展） |

## 5. 推进顺序与里程碑

```
M0  可行性评估（本文）                                ← 现在
M1  ①专项设计：事件源会话（append 收敛/重建器/视图迁移）
    → docs/design-event-sourced-session.md，DAG 计划实施
M2  ①落地：事件为真 + 投影物化 + 双写缺口关闭
    → 验证：20 条 e2e 全绿 + 并发 chat 压测（无重复 seq）+ 重启恢复用例
M3  ②专项设计：常驻 agent（queue/abort/resume/身份恢复）→ DAG 实施
M4  ②落地：5 入口契约不变、13+ 测试类原样通过、新增并发/取消/恢复用例
M5  ③+外围（按需）：扩展注册表 / 命令注册表 / schema 驱动 UI
```

**里程碑完成状态（2026-09-04 回写）**：
- M1 ✅ ①专项设计 → `docs/design-event-sourced-session.md`（plan-67e7fc50）
- M2 ✅ ①落地：事件为真 + 投影物化 + 双写缺口关闭（plan-089deb4b；457 tests 全绿）
- M3 ✅ ②专项设计 → `docs/design-resident-agent.md`（plan-19f984d1，D1-D8 定案）
- M4 ✅ ②落地：常驻 agent queue/abort/resume/身份 + 重启恢复（plan-7f52f91e；478 tests 全绿）
- M5 ✅ ③专项设计+裁决 → `docs/design-extension-points.md`（plan-c68c5bfa；D2 扩展注册表不做→
  `docs/how-to-extend.md` 指南；D3 命令注册表做）
- M6 ✅ ③命令面落地：ChatCommand/CommandRegistry（/compact 收编 + /help）+ 扩展指南（plan-902db498；
  **482 tests / 0 failures / 0 errors / 2 skipped 全绿**）
- M7 ✅ ①远期收口专项设计：完整 surfaceOp（surface 记录语言）→ `docs/design-surface-op.md`
  （plan-2234243e；D1–D9 定案：独立 `anchon_session_surface` 指令表 + SurfaceProjector seam +
  boundary 隐式初始指令 + D9-A 摘要去重 + REPLACE_RANGE/restore 全能力）—— M8 落地按需转 DAG
- M8 ✅ ①远期收口落地：surfaceOp 全能力（plan-4fd83e93 completed）
  — `anchon_session_surface` 指令表（0008 变更集）+ SessionSurfaceStore（replaceHead/replaceRange/
  restoreRange，乐观锁 gate + SESSION_SURFACE_CHANGED 审计）+ SurfaceProjector seam（AgentLoop
  读路径装配，空 surface 与现状逐条等价，压缩写 REPLACE_HEAD 后摘要视图行置头 + 物理行去重）；
  **全量 mvn verify 全绿（506 tests / 0 failures / 0 errors / 3 skipped）**，红线 §6-1~§6-6 全达成
- M9 ✅ ①远期优化专项设计：投影 registry 化缓存（design-surface-op.md §4.3 远期项）→
  `docs/design-projection-cache.md`（plan-0bdf706f；D1–D6 定案：遮蔽区间表+代数物化为会话级
  SessionProjection，Store 直失效+事件兜底双通道，一致性 A0+A1 开关位，容量 maxEntries 4096
  LRU 逐出，Registry 落 dsh-session 与 SurfaceProjector 同包，原 projectVisible 直算保留契约锁）
- M9 ✅ ①远期优化落地：投影 registry 化缓存（plan-a2eefa3a completed）
  — `SessionProjection` + `SessionProjectionRegistry`（遮蔽区间表+代数会话级物化，CHM+原子装载+
  LRU 逐出；SessionSurfaceStore 写后直失效 + SESSION_SURFACE_CHANGED 事件兜底双通道；
  AgentLoop seam registry 优先，命中零 DB 指令读 + 零 O(K²) 重建，未装配/无指令走 legacy fast-path）；
  **全量 mvn verify 全绿（520 tests / 0 failures / 0 errors / 3 skipped）**，红线 §5-1~§5-6 全达成
- M10 ✅ 外围「schema 驱动 UI」内核②落地（docs/design-frontend-replication.md 路径 C+L2；
  plan-f2e88817 completed）
  — 工具元数据单源：`Tool` 注解扩 `displayTitle/summaryKeys`（默认零破坏）+ `GET /api/tools/meta`
  （name/标题/摘要键/description/inputSchema/审批/超时）；前端 MsgView 删硬编码 TOOL_TITLES/
  SUMMARY_KEYS → 元数据表渲染 + 泛化兜底，新工具零改前端；
  — Settings 动态表单：`SettingDescriptor` 机制 + `GET /api/settings/meta`（描述符+当前值合并），
  agent 命名空间收编（defaults=properties 现值，数值零漂移）；SchemaForm.vue 通用 schema 表单 +
  SettingsPage.vue + 侧边栏「设置」入口；
  **mvn verify 全绿（531 tests / 0 failures / 0 errors / 3 skipped）+ vite build 通过**
> 三支柱机制对齐按裁决范围全部达成：①事件为真（fact 双写/read 开关/一致性对照）、②常驻 agent
> （同会话并发排队、协作取消/流式中断、executionId resume、AGENT_PHASE 事件化+启动恢复）、
> ③扩展面（命令注册表收编 if 特判 + 现状扩展机制文档化；统一 DshExtensionRegistry 经裁决不做）。

**验证红线**：
- 每条里程碑后 `mvn verify` 全绿 + `DeepSeekE2ETest`（~20 条真实 API 用例）通过；
- ①完成后并发 chat 无 seq 竞态（新增压测用例）；重启后会话可完整重建（含压缩边界）；
- ②完成后 cancel 延迟显著下降（流式中断），同会话并发请求被排队而非报错。

## 6. 风险与回退

- **行为漂移**：改造动核心数据路径 → 用既有测试当契约锁；每步独立可合并，支持逐里程碑
  回退（消息表兼容读：事件源落地前保留双写读旧路径的开关 `dsh.session.read-model=table|event`）。
- **性能**：事件源全量回放重建有成本 → 物化投影增量更新 + 定期快照（对齐上游 chunk-rows/
  seq-ranges 的区间管理思路）。
- **上游漂移**：上游仍在快速迭代 → 本路线只对齐**已稳定核验**的机制（agent/session），
  同步窗口仍按 UPSTREAM_SYNC.md 流程。

## 7. 建议

先启动 **M1（支柱①专项设计）**：它收益最明确（消除双写缺口、解锁 surfaceOp 语义）、
且是支柱②的前提。若你倾向最小改动，也可停在 M1 的"专项设计 + 双写一致性修复"（不动
事实源升格），作为低风险子集。

（2026-09-03 定稿：提问未获应答，本文固化为各档方案的公共底座；下一步由你圈定 M1~M5 任一档。）
