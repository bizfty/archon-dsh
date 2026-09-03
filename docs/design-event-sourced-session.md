# 支柱① 事件源会话 — 专项设计（M1）

> 目标基线：archon-dsh HEAD `c1400b5` · 配套 [design-upstream-replication.md](design-upstream-replication.md) M1
> 状态：**草案（待评审）** — 评审点见 §8

## 1. 背景与目标

Java 会话目前是"**消息表为真 + 事件表旁路审计**"。与上游"**事件日志为真、可见面为投影
（surfaceOp）**"相比存在一个真实缺陷和一个语义缺口：

1. **双写不一致（真实缺陷）**：同一业务动作既写消息行（`SessionService.append`，事务 A）
   又发事件（`SessionEventBus.publish` → `SessionEventPersistenceListener`，`REQUIRES_NEW`
   事务 B）。两者不同事务、无原子性：崩溃窗口内消息/事件可能不一致；且 USER/ASSISTANT/TOOL
   消息动作是"双写重复"（同信息两处）。
2. **无表面自由裁剪（语义缺口）**：模型可见面目前由"压缩边界 + pruner 行标记 + 读时截断"
   拼出，无法表达上游 surfaceOp 的任意 replace/append 语义，也无法做细粒度可见面回卷。

本设计把会话**消息事实**改为**事件为真**（过程事件仍保留观察/审计用途），消息表降级为
**可重建的物化投影**（读模型），从而：原子性单写、崩溃一致性、解锁 surfaceOp、并对齐上游
seq-ranges/surface/repair 的区间管理思路。

**非目标**：不搬 Cordis、不引入新存储中间件、不改对外 REST/SSE/OpenAI 接口契约、不迁移已有
历史数据（存量会话在兼容开关下继续走旧读路径，见 §6）。

## 2. 现状事实（2026-09-03 代码级核实）

### 2.1 写路径
- `SessionService.append`（@Transactional）写 `anchon_session_message`：`seq = countBySessionId+1`。
  并发兜底实测（DDL 核实，0001-04/0006）：**消息表无 `(sessionId,seq)` 唯一约束**；同会话并发
  的第一道防线是 append 内 `saveAndFlush` 会话行 → 乐观锁 version 冲突抛
  `SessionConcurrentModificationException` 回滚整个事务（含消息行），不会静默错序；
  唯一 UK 只存在于事件表（`uk_anchon_event_session_seq`，0006-03）。
- 事件由 9 个发布者发出（AgentLoopService / SessionTitleService / ToolResultPruneService /
  CompactionService / FeedbackService / ApprovalGate / AskUserQuestionTool / ScheduleService /
  ToolEventPublisher），总线 `SessionEventBus` 进程内同步 observe-only 分发。
- 持久化监听器以 `REQUIRES_NEW` 写 `anchon_session_event`（order=-200 先落库），**与消息
  append 异事务** → 双写缺口。
- AgentLoopService 本身无事务；同一 turn 内多次 append + publish。

### 2.2 事件与消息行的对应（关键：现有事件 payload 有损，不能作消息事实源）
| 消息行类型 | 对应事件 | 事件 payload 完整性 |
|---|---|---|
| USER 行 | USER_MESSAGE | 只 `{content}`（压缩摘要 USER 行在 manualCompact/maybeCompact **无对应事件**） |
| ASSISTANT 行（含 toolCallsJson） | ASSISTANT_MESSAGE | 只 `{tool_calls: n}`，**不含文本/工具调用 JSON** |
| TOOL 行（完整 resultJson，可含 spill 定位符） | TOOL_RESULT/TOOL_ERROR | 含 `message+data`，但**不等同**行内 `result.toMap()` 全量；TOOL_CALL 的 args 被 `summarize()` 有损化 |
| — | TURN_START/END/ERROR、STEP_START、MODEL_REQUEST/RESPONSE、ASSISTANT_TOKEN、TOOL_DENIED/TIMEOUT、APPROVAL_REQUESTED、QUESTION_REQUESTED、FEEDBACK、TITLE_UPDATED、SESSION_CREATED/DISPOSED、TOOL_RESULT_PRUNE | 纯过程/审计，无消息行对应 |

**结论**：现有事件流是"过程审计流"，消息真相只在消息表。事件源化必须**新增完整消息事实
事件**，而不是把审计事件当事实源。

### 2.3 边界/投影/修剪现状（迁移对象）
- `CompactionBoundaryStore`（storage 键值）：存 `shadowedHeadCount`（被遮蔽消息条数）。
- 压缩摘要 = **以 USER 行 append**（manualCompact / maybeCompact 阈值触发），边界外消息仍全量留表。
- `ToolResultPruner`：纯函数（长度判定 + 码点截断 + PRUNE_MARKER）。
- `ToolResultPruneService`：`markToolResultPruned` 置**行标记**（pruned=true、原文保留）+ 发
  TOOL_RESULT_PRUNE 事件；投影层 `MessageProjector` 对已 pruned TOOL 行输出截断视图。
- series/恢复：`RequestSeriesTracker`（内存态，按 headerFingerprint 判定 resume/change/series）；
  `EventLogReader.lastAgentTurnHeaderFingerprint`（从事件表读上次指纹 → 重启后系列判定）；
  `InterruptedTurnRepairer`（启动扫描 open turn → 补 `interrupted` TURN_END，幂等）。
- 读侧：`SessionService.listMessages` 直接查消息表；`MessageProjector` 把行投影成 LLM/API 消息
  （含配对过滤、pruned 截断）；`SessionQueryService` 全文搜索走消息表。

### 2.4 契约锁（改造后须原样通过或按契约迁移）
- 单元/集成：AgentLoop*Test ×10、MessageProjectorPruneTest、SessionWriteSafetyUnitTest、
  SessionServiceTest、SessionEventPersistenceListenerTest、EventLogReaderTest、
  InterruptedTurnRepairerTest、RequestSeriesTrackerTest、CompactionBoundaryStoreTest、
  ToolResultPrunerTest、ToolResultPruneServiceTest、SpillServiceTest、SessionTitleServiceTest 等。
- e2e：DeepSeekE2ETest（~20 条真实 API）、DshApplicationTests、LiquibaseMigrationTest、
  WorkflowRegisteredTest、BuiltinSkillsLoadedTest、CompactionArgsBindingTest。

## 3. 目标模型

```
                    写路径（单事务）
  AgentLoop/Title/Prune/… ──► SessionFactStore.write(sessionId, MessageAppended, record)
                                    │ 1 个事务
                                    ├─► anchon_session_fact （事件为真，事实事件）
                                    └─► 事务内同步物化投影行（anchon_session_message 保留，读加速/搜索/旧路径）
                                          │
                        读路径：投影行（即时）· 重建器从 fact 重放（幂等，崩溃自愈）
```

- **事实事件（fact）**：只承载"消息追加"这一类可重建会话消息序的事件，payload = 完整消息记录
  （与现消息行同构）。它是消息序的**唯一真相**。
- **过程事件（audit）**：沿用现 `anchon_session_event` 与 `SessionEventType`，保持观察/审计/
  series/repair 用途，**不参与**消息重建；payload 有损可接受。
- **消息行**：降级为事实事件的**物化投影缓存**（读加速 + 现有查询/搜索接口零改）。

## 4. 核心机制

### 4.1 事实事件模型
新增**表** `anchon_session_fact` 与事件类型 **`MESSAGE_APPENDED`**（或同类命名，评审定名）：

```
anchon_session_fact
  id            varchar(64)   # fact_{sessionIdHash}_{seq}
  session_id    varchar(64)
  seq           bigint        # 会话内消息事实序号（= 旧消息行 seq 语义，从 1 连续）
  role          varchar(16)   # USER | ASSISTANT | TOOL | SYSTEM（SkillController 技能种子行，§8.1 清点）
  content       text          # 完整原文（TOOL 行为 spill 后定位符文本，与现消息行一致）
  tool_call_id  varchar(64)
  tool_name     varchar(128)
  tool_calls_json text        # ASSISTANT 行的工具调用 JSON（完整序列化）
  pruned        boolean       # durable 修剪标记（原语义：原文保留、投影截断）
  meta_json     text          # 可选：compacted=true（摘要行来源标记）等
  created_at    timestamp
  UK(session_id, seq)
```

- **不改造现 `anchon_session_event`**：审计/镜像事件流继续独立保留（观察、series、repair 用途）。
  "关双写缺口"的准确含义 = **消息真相单一化**：事实表是唯一"消息真相"，且 fact 与投影缓存同事务
  原子；消息镜像审计事件降级为**可丢观察流**（§4.2），其与 fact 的偏差不再代表"真相不一致"。
- ASSISTANT 行含 toolCallsJson → fact 完整存；TOOL 行存 spill 后文本（保持现状语义：
  "日志无损"是指含定位符的可重建版本，spill 文件仍在）。

**为什么是独立 fact 表而非别处**（2026-09-03 用户评审提问：fact vs session 区别）——三选一取舍：

| 方案 | 形态 | 结论 |
|---|---|---|
| A. 独立 `anchon_session_fact`（本设计） | 会话 = 头(1 行) + 逐条事实日志(N 行) | ✅ **采用**：真相与投影分离；投影半写/损坏可从 fact 重放自愈（§6.3 验证）；message 可修剪/重建而不损真相 |
| B. 就地升级 `anchon_session_message` 为事实 | 消息行即事实、即投影 | ❌ 无独立真相源：投影行若损坏无源可重放，"崩溃自愈"验证（§6.3）无从成立；pruned/内容变异破坏不可变性 |
| C. 会话行 JSON 内嵌事实 | session 单行塞全部消息 | ❌ 单行无限膨胀、每次追加全量重写（O(n²)）、同会话并发写放大更烈、无法按 (session, seq) 检索/局部重建 |

职责分离：`fact` 只答"此会话逐条发生过什么（append-only 真相）"；`session` 只答"此会话的元数据/状态"；
`message`（M2 后）是 fact 同事务物化的投影缓存，供读路径/搜索。三者互不替代。

### 4.2 原子写入 `SessionFactStore`
- 新增 `SessionFactStore`（dsh-session）：
  - `append(sessionId, MessageAppend record)`：**单事务**写入 fact 行 + **同事务物化**投影行
    （`SessionMessageEntity` 照旧写，作为投影缓存）→ 双写缺口关闭：两者要么都成要么都不成。
  - seq 分配：**fact 表 `count(sessionId)+1`，fact 自建 `UK(session_id,seq)` 为第二道防线**；
    第一道防线沿用现行为——append 同事务 `saveAndFlush` 会话行乐观锁，冲突抛
    `SessionConcurrentModificationException` 整体回滚（fact+投影+会话行一起滚），无静默重复；
    即同会话并发 fail-fast 语义与现状一致（支柱② inbox 排队是根治，见 §6）。
  - `SessionService.append` 是**唯一写入口（facade）**：内部委托 `SessionFactStore` 完成
    fact+投影+会话行同事务；§8.1 的 11 个调用点**源码零改动**（AgentLoop/Skill/Schedule 仍调
    `sessionService.append`），收敛发生在内部实现——**写接口签名不变**（与读接口零变对称）。
  - 消息镜像审计事件（USER_MESSAGE/ASSISTANT_MESSAGE/TOOL_RESULT/TOOL_ERROR 等）**保留发布**：
    前端/WS/SSE 观察依赖（WS 帧 `{sessionId,seq,event}` 实时推送）。其角色定位为**可丢观察流**：
    不再承载消息真相（真相=fact），允许与 fact 偏（事件 `REQUIRES_NEW` 独立提交）；series 恢复
    依赖 **MODEL_REQUEST** 事件（EventLogReader 核实，见 §2.3/§9 修订记录），不依赖消息镜像事件，
    故其可丢不影响恢复。观察流偏/滞后不构成"真相不一致"——双写缺口从定义上关闭。
- 投影物化策略（评审点 B）：默认**同事务物化**（延迟低、读一致）；可选"fact 后异步物化"
  以减少写放大（需读模型容忍短暂滞后），不在本里程碑默认。

### 4.3 读路径与重建
- `SessionService.listMessages` 保持查**消息表**（投影缓存）→ 对外零接口变化。
- 新增 `SessionFactReplayer`（幂等重建器）：从 fact 按 `(session_id, seq)` 重放 → 全量/自
  指定 seq 重建投影行。用于：启动校验、发现投影缺失/损坏时自愈、灾难恢复演练。
- 启动钩子：`SessionProjectionVerifier`（ApplicationRunner，可选开关）：抽查会话 fact 末 seq
  与投影行数一致，不一致触发局部重建（对齐上游 repair 语义）。

### 4.4 边界/修剪/surface 迁移（视图层语义保持）
- `CompactionBoundaryStore` **保留**（存储键值，仍记 shadowedHeadCount）——压缩摘要行落 fact
  时标记 `meta.compacted=true`；投影读模型仍以 boundary 从消息表裁剪。迁移到"事件序边界"的
  完整 surfaceOp 属后续（§7 远期），本里程碑只把**写入源**换到 fact 且保证语义不变。
- `ToolResultPruner` / `MessageProjector` / `ToolResultPruneService` **接口不变**：
  - prune 动作改为 `SessionFactStore.markPruned(sessionId, seq)`（同事务置 fact.pruned + 投影
    行 pruned），TOOL_RESULT_PRUNE 审计事件保留。
  - 投影层从"读行 + pruned 标记截断"逻辑**原样沿用**（消息行仍是投影缓存，字段不变）。
- series/恢复不动（`RequestSeriesTracker` + `EventLogReader` 读审计事件）——过程事件流未变。

### 4.5 兼容开关与渐进切换
- `dsh.session.read-model=table|event`：
  - `table`（默认，**存量兼容**）：新会话双写？——不。渐进策略：**feature flag 双轨**：
    - `write=fact|dual`：新里程碑默认 `fact`（单写 fact + 同事务物化）；`dual` 仅迁移期
      灰度用（写 fact + 仍写旧审计事件，供对照）。
    - `read=table|fact-replay`：默认 `table`（读投影缓存）；`fact-replay` 供验证（读路径改为
      从 fact 重放，作为 M2 验证手段，不默认）。
  - 存量历史行不做物理迁移：fact 从启用时刻起积累，旧消息行仍留在消息表（会话"迁移前
    消息 + 迁移后 fact"如何统一见 §7 开放问题 R3——最简方案：首次启用时对存量行做一次
    fact 回填快照（`backfill`），保证会话内 fact 自 1 连续，之后单一事实源成立）。
- Liquibase 变更集新增 `anchon_session_fact` + 索引（评审点确认表名/列名）。

## 5. 与上游对齐点

| 上游 | 本设计对应 |
|---|---|
| session log 为真 | `anchon_session_fact` 为真 |
| seq-ranges（区间/大日志管理） | 现事实 `(session_id,seq)` + chunk 化：**大 content 行沿用 spill 定位符机制**（不新增 chunk 表，评审点可议） |
| surfaceOp replace/append | boundary + fact 顺序 = 轻量 replace（shadow head）；完整 surfaceOp 属 §7 |
| repair/invariant | `SessionProjectionVerifier` + 幂等 `SessionFactReplayer` |
| projection registry（派生状态） | 消息表投影缓存（读加速）；series/turnBoundary 已在审计事件侧 |
| compaction 写日志 | 摘要行以 fact 追加 + `meta.compacted`（对应上游 compacted summary 入日志语义） |

## 6. 验证红线（M2 完成判定）

1. `mvn verify` 全绿；DeepSeekE2ETest 等集成测试通过（消息对外接口行为不变）。
2. **并发**：同会话并发 append 压测（新增用例）——保持"乐观锁冲突显式抛
   `SessionConcurrentModificationException`"（fact UK 第二道防线兜底），**不得出现静默重复
   seq/fact 或幻读**；契约：报错而不是错序（支柱② inbox 排队是根治，本里程碑保证 fail-safe）。
3. **崩溃恢复**：写入一半（fact 已写、投影未写）模拟 → 重启 `SessionProjectionVerifier` 检出
   不一致并局部重建 → 消息序完整（新增用例）。
4. **语义保持**：压缩摘要/修剪后会话导出与改造前一致（fact 与投影行同内容）；`read=fact-replay`
   与 `read=table` 结果一致。
5. **性能**：fact+同事务物化下，单 turn 写路径延迟与现行为同量级（基准用例记录）。

## 7. 开放问题与远期

- R1（评审点 A）：事实事件命名 `MESSAGE_APPENDED` + 表 `anchon_session_fact` 是否采纳；
  或改为"升级现有 USER_MESSAGE/ASSISTANT_MESSAGE/TOOL_RESULT 为完整负载并充当事实源"
  （改审计语义、影响大，不推荐）。
- R2（评审点 B）：投影物化**同事务**（默认，推荐）vs 异步物化。
- R3（评审点 C）：存量会话启用时**回填 fact 快照**（推荐，保 seq 连续单一真相）vs
  "迁移前读消息表 + 迁移后读 fact"双源拼接（不推荐，复杂度高）。
- R4：过程审计事件是否最终可裁剪（tier/保留策略）——本里程碑不动。
- 远期：完整 surfaceOp（任意段 replace/append 的可见面语言）、投影 registry 化、fact chunk
  化大行（对齐 chunk-rows）；这些在支柱②落地后视需要做。

## 8. 评审要点（请 reviewer/用户拍板）

1. §4.1 事实事件模型与表设计（列/UK/命名）。
2. §4.2 写路径收敛策略（本版已修订）：`SessionService.append`/`markToolResultPruned` **内部委托**
   `SessionFactStore`（调用点 11 处零变更、写接口零变）；消息镜像审计事件**保留为可丢观察流**
   （前端/WS 依赖），不参与真相。请确认采纳该收敛策略。
3. §4.5 渐进开关默认值（write=fact 直接默认，还是先 dual 灰度一轮）。
4. §7 R1/R2/R3 三个决策。
5. 本里程碑边界：**不含**全 surfaceOp、不含事实 chunk、不含支柱②——确认。

6. **事实表形态（2026-09-03 用户评审提问）**：独立 fact 表（方案 A，推荐）vs 就地升级
   message 表为事实源（方案 B）vs 会话行 JSON 内嵌（方案 C）。取舍对比见 §4.1 末尾；
   B/C 不满足"真相与投影分离、崩溃自愈"（§6.3）红线，不推荐。

> 评审通过后：转 M2 落地 DAG（s4）——建表 + SessionFactStore + 写点迁移 + 重建器 + 验证用例，
> 每步可独立合并、带对应契约锁测试。

## 8.1 写点迁移清单（§8 决策点 2 的代码级答案，2026-09-03 全库清点）

`sessionService.append` / `markToolResultPruned` 全部调用点（11 处，跨 4 模块）：

| # | 调用点 | 角色 | 语义 | 现事件对应 |
|---|---|---|---|---|
| 1 | AgentLoopService:196 `manualCompact` | USER | 压缩摘要行 | 无（摘要无事件） |
| 2 | AgentLoopService:411 `execute` | USER | 用户消息 | USER_MESSAGE（同时 publish，重复） |
| 3 | AgentLoopService:550 `execute`（正常收尾） | ASSISTANT | 最终回答 | ASSISTANT_MESSAGE + TURN_END |
| 4 | AgentLoopService:564 `execute`（同轮二次收尾路径） | ASSISTANT | 最终回答 | ASSISTANT_MESSAGE + TURN_END |
| 5 | AgentLoopService:576 `execute` | ASSISTANT | 含 toolCallsJson 的工具发起行 | ASSISTANT_MESSAGE（仅计数） |
| 6 | AgentLoopService:595 `execute` | TOOL | 工具结果行（resultJson，spill 后） | TOOL_RESULT/ERROR（publish 在管线内、与 append 解耦） |
| 7 | AgentLoopService:615 `execute` | USER | additionalContexts 注入行 | 无（上下文注入无事件） |
| 8 | AgentLoopService:796 `maybeCompact` | USER | 自动压缩摘要行 | 无 |
| 9 | SkillController:83-84 | SYSTEM+USER | 技能运行种子行 | 无 |
| 10 | ScheduleService:63 | USER | 定时任务触发行 | 无（ScheduleService 仅对执行结果发事件） |
| 11 | ToolResultPruneService:76 | — | markToolResultPruned 行标记 | TOOL_RESULT_PRUNE（publish 在标记后，解耦） |

**推论**：
- 11 处中仅 #2/#3/#4/#5/#6 有（有损）对应审计事件，#1/#7/#8/#9/#10 完全无事件 → 现有事件流
  连"逐条消息记录"都覆盖不全，进一步坐实 §2.2"审计事件不能作事实源"。
- M2 迁移面 = **`SessionService.append` / `markToolResultPruned` 两个公开方法内部收敛**到
  `SessionFactStore`（#1-#10 走 append、#11 走 markPruned）：11 个调用点**不改**、作为回归面，
  调用点源码零变更即"写接口零变"。SYSTEM 角色必须进 fact 模型（§4.1 已补）。
- `SessionService.createSession(seeds)` 内部 self-call `append`：种子行也是事实（同一会话事务内）。

## 8.2 修订预案（2026-09-03 第二轮评审：用户选"要改设计"）

自查识别并**已应用**的实质修订（非文字润色，均有代码证据）：

1. **写路径收敛策略改写（§4.2/§8 决策点 2/§8.1 推论/§10 M2-3）**：收敛点从"改 11 个调用点"
   改为"`SessionService.append`/`markToolResultPruned` **内部委托** `SessionFactStore`"——
   调用点源码零改动、写接口签名不变（与读接口零变对称），M2 回归面 = 11 个调用点全量测试，
   而非大爆炸改调用方。原版"AgentLoop 等改调 SessionFactStore 且 9 发布者不动"自相矛盾。
2. **消息镜像审计事件定位澄清（§3/§4.1/§4.2）**：USER_MESSAGE/ASSISTANT_MESSAGE/TOOL_RESULT
   等**保留发布**（前端/WS/SSE 观察依赖 WS 帧），角色=**可丢观察流**：不承载真相、允许与 fact
   偏。关键证据：series 恢复只依赖 MODEL_REQUEST 事件（EventLogReader 回看
   `RECENT_MODEL_REQUEST_LIMIT=5` 条 MODEL_REQUEST 的 headerFingerprint），不依赖消息镜像事件，
   故镜像事件可丢不影响重启恢复。原版"双写重复因此消除"表述过度（镜像事件仍在发），已精确化。
3. **并发防线描述修正（§2.1/§4.2/§6.2，DDL 级核实）**：`anchon_session_message` **无**
   `(session_id,seq)` UK（0001-04 仅 PK+普通索引）；唯一 UK 只在事件表
   `uk_anchon_event_session_seq`（0006-03）；append 并发第一道防线=会话行乐观锁
   `saveAndFlush`→`SessionConcurrentModificationException` 回滚。fact 新 UK 是第二道防线。

**未改、仍待用户明示**（若上述修订未覆盖你想改的点，请指出具体项）：
- §4.1 表列/命名（`MESSAGE_APPENDED` + `anchon_session_fact`，推荐项不变）；
- §4.5 开关默认（write=fact 直启 vs dual 灰度，推荐直启+dual 可选对照）；
- §7 R2 投影同事务（推荐）vs 异步物化；R3 存量回填（推荐）；
- §8 决策点 5 里程碑边界（不含全 surfaceOp/fact chunk/支柱②）。

## 9. 自查评审记录（demo-plan-review，2026-09-03）

```yaml
verdict: approve（待人类正式放行 s3 评审门）
issues:
  - severity: medium
    description: 2026-09-03 二轮评审修订（§8.2）：①收敛策略改 SessionService 内部委托（11 调用点
      零改动）；②消息镜像审计事件定位为可丢观察流（series 只依赖 MODEL_REQUEST，EventLogReader
      核实）；③并发防线描述按 DDL 修正（消息表无 UK，会话行乐观锁为第一道防线）。
      修订均已在正文应用并留痕。
    suggestion: 重交评审时重点确认 §8 决策点 2（收敛策略）与决策点 6（fact 表形态）。
  - severity: low
    description: 设计依赖若干开放决策点（R1/R2/R3/开关默认/命名），文档 §8 均已给出推荐项，
      故缺省可按推荐值定案，不阻塞 M2 计划编制；若用户后续否决某推荐项再回改设计。
    suggestion: s4 转 M2 计划时以"推荐值 + 可覆盖参数"方式落地，避免硬编码假设。
  - severity: low
    description: 同事务物化投影会增大写事务耗时/锁持有（fact+message 两行一事务），设计未量化现状
      append 事务基线；验证红线 #5 仅声明"同量级"无数值基线。
    suggestion: M2 首个落地步骤补一个 append 路径基准测试（记录现状耗时/行数），作为 #5 对照。
  - severity: low
    description: LiquibaseMigrationTest 覆盖迁移；新增 anchon_session_fact 变更集需同步补断言，
      设计文档未显式提及。
    suggestion: s4/M2 建表步骤包含 LiquibaseMigrationTest 断言更新。
summary: 目标一致（支柱①专项设计 → 转 M2）；s1/s2 调研充分且产出契约锁清单；s3 事实源模型
  选择有代码证据支撑（审计事件有损不能充当消息事实源）；双写缺口关闭路径清晰；风险项均为
  low 级实施细节，可在 M2 计划中吸收。正式执行仍需人类 plan_step_review 放行 s3。

## 10. M2 落地拆解（草案，**待 s3 评审通过后转正式 DAG**）

> 状态说明：M1 的 s3（本设计）评审门未放行前，本节仅为预起草。执行须满足两个前提：
> （a）人类 `plan_step_review` 批准本设计；（b）人类确认 M2 范围（§8 决策点 5）。
> 每步均带契约锁测试，独立可合并；验证红线映射见 §6。

- **M2-1 建表与实体**：Liquibase 变更集建 `anchon_session_fact`（§4.1 列，含 SYSTEM），
  实体/仓储层；`LiquibaseMigrationTest` 补断言。
- **M2-2 `SessionFactStore` + 同事务物化**：单事务写 fact + 投影消息行 + 会话行 updatedAt；
  seq 分配沿用 count+1 + fact 新 UK（第二道防线）；第一道防线沿用会话行乐观锁 saveAndFlush；
  并发语义保持 fail-fast（补并发压测用例 §6.2）。
- **M2-3 `SessionService` 内部收敛**：`SessionService.append`/`markToolResultPruned` 改内部委托
  `SessionFactStore`（fact+投影+会话行同事务）；§8.1 的 11 个调用点源码不动、作为回归面（对照
  AgentLoop*Test / SkillController 相关 / ScheduleService 测试原样通过）；消息镜像审计事件
  保留发布（观察流语义，不参与真相）。
- **M2-4 幂等重建器 `SessionFactReplayer` + 启动校验**：fact→投影重放（全量/自 seq）、
  `SessionProjectionVerifier` 启动抽查与局部重建；`InterruptedTurnRepairer` 不改（审计流未变）。
  崩溃恢复用例（fact 已写投影未写 → 重启自愈）§6.3。
- **M2-5 read 开关与验证收口**：`dsh.session.read-model=table|fact-replay`（默认 table，
  fact-replay 仅验证用）；附录基准 append 延迟（对照 §6.5）；全套 `mvn verify` +
  DeepSeekE2ETest 等 e2e（§6.1）；fact/table 读一致性对照（§6.4）。
- 存量回填（§4.5 R3 推荐）：迁移版次内对启用时点前的会话行做一次 fact 快照回填，保证
  fact 自 seq=1 连续（M2-1 变更集后、M2-3 前执行，属数据迁移工具 + 幂等重跑能力）。

风险与回滚：每步独立提交可回退；`write=dual` 灰度提供迁移期对照（默认直启 `fact`，决策点 3）。

## 附录 A：append 写路径现状基线（m2-baseline，2026-09-03，H2 内存库）

探针：`dsh-session/src/test/java/.../AppendPathBaselineProbeTest.java`（可复跑；仅软性指标，无时序断言）。
测量对象：改造前 `SessionService.append`（现状写路径：1 消息行 + 会话行乐观锁 saveAndFlush，单事务；审计事件在调用点独立 REQUIRES_NEW 事务，不计入）。

```
[BASELINE] append path metrics: avgMs=3.23 totalMs=97 p50Ms=3 ops=30 messageRows=35 sessionVersion=35
```

对照口径（m2-5 红线 #5）：`SessionFactStore.append`（fact 行 + 投影消息行 + 会话行，单事务）应与之**同量级**
（同一探针复跑比对，H2 环境一致；允许差 2 行写放大但不得量级恶化，如 >10x 判失败）。

## 附录 B：append 写路径 M2 迁移后对照（m2-5 红线 #5 验证，2026-09-03，H2 内存库）

同一探针（`AppendPathBaselineProbeTest`）在 m2-3 收敛后复跑 —— 此时 `SessionService.append` 已委托
`SessionFactStore`（单事务：fact 行 + 投影消息行 + 会话行乐观锁 gate saveAndFlush）。三连跑：

```
[BASELINE] append path metrics: avgMs=11.07 totalMs=332 p50Ms=11 ops=30 messageRows=35 sessionVersion=35
[BASELINE] append path metrics: avgMs=10.17 totalMs=305 p50Ms=10 ops=30 messageRows=35 sessionVersion=35
[BASELINE] append path metrics: avgMs=9.93  totalMs=298 p50Ms=8  ops=30 messageRows=35 sessionVersion=35
```

对照结论（红线 #5 判据：同量级，>10x 判失败）：
- 迁移后 avg ~10ms / p50 ~8-11ms vs 基线 avg 3.23ms / p50 3ms → **约 3.1-3.4x，同量级、无量级恶化（<10x）**。
- 写放大符合预期：每 op 由 1 行（消息行）增为 2 行（fact + 消息行）；额外开销主因是
  "先碰会话行 gate saveAndFlush 再 count+插行" 序列化设计（m2-2 并发竞态修复的必要代价：
  无 gate 前置时并发下 UK 兜底会抛非契约异常类型，见 §6.2 fail-fast 契约）。
- 会话行 version 每次 append +1（与基线一致：35 条消息 → sessionVersion=35）；对外读接口无变化。

