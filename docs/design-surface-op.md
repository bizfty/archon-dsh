# 支柱①远期收口：完整 surfaceOp（surface 记录语言）— 专项设计（M7）

> 位置：`docs/design-surface-op.md`
> 依据：`docs/design-upstream-replication.md` 差异①（"解锁 surfaceOp 自由裁剪/回卷"）、
> `docs/design-event-sourced-session.md` §7 远期（"完整 surfaceOp（任意段 replace/append 的
> 可见面语言）、投影 registry 化"）、`docs/IMPLEMENTATION_DIFF.md` §4/§11/§13.2
> 前置：支柱①②（M2 事件为真、M4 常驻 agent）已达成 —— `mvn verify` 482 tests 全绿
> 状态：**定稿（2026-09-04 人类批准）+ 落地完成（M8）** — D1–D9 裁决见 §8.1；
> 落地见 §9.1 落地记录（plan-4fd83e93，全量 verify 全绿）

## 1. 背景与目标

M2 将会话消息真相收敛到 `anchon_session_fact`（事件为真 + 同事务投影物化），但**模型可见面
仍由"压缩遮蔽边界 + 尾部窗口 + durable pruned 标记 + 读时截断"拼出**（design-event-sourced-
session.md §4.4 明示迁移属远期）。这与上游"日志 + surfaceOp + 投影派生可见面"仍有结构性差异
（IMPLEMENTATION_DIFF §13.2）：**现有近似只支持"遮蔽头部一段"（shadow boundary = 单点
replace 头），不支持任意段 replace/append、不支持细粒度可见面回卷**。

本设计把可见面从"拼出来的窗口"升级为**显式 surface 记录语言**：在真相（fact，不可变 append-only）
之上叠加一层**可变的可见面指令序列**，使"模型看到什么"成为可表达、可审计、可回卷的派生状态。
这兑现 design-upstream-replication.md 差异①的原始承诺（"解锁 surfaceOp 自由裁剪/回卷"），
并保持 M1/M2 确立的事实/投影分离原则不被破坏。

**非目标**：不搬上游 surface.ts 逐行移植、不做 fact chunk 化（大行 spill 机制已覆盖）、不引入
新存储中间件、不改对外 REST/SSE/OpenAI 接口契约、不迁移既有会话（存量默认空 surface 序列 =
现状窗口语义，渐进启用）。**本里程碑只做"语言 + 存储 + 执行 seam + 兼容"设计与 M8 落地拆解**。

## 2. 现状核实（2026-09-04 代码级清点）

### 2.1 真相层（M2 后已收敛）
- `anchon_session_fact`（`SessionFactEntity`）：`(sessionId, seq)` UK，消息事实 append-only 真相；
  `SessionFactStore.append`（@Transactional）单事务写 fact 行 + 投影行 + 会话行乐观锁 gate；
  `markPruned` 同事务置 fact+投影 pruned（原文保留）；`listFromFact` 只读重放（fact-replay 验证用，
  `replay_<h>_<seq>` 派生 id）。
- 投影缓存 `anchon_session_message`（`SessionMessageEntity`）：字段与消息同构，供读路径/搜索/旧路径。

### 2.2 可见面近似机制（现状"拼窗口"，本设计迁移对象）
| 机制 | 位置 | 语义 | 与 surfaceOp 关系 |
|---|---|---|---|
| `CompactionBoundaryStore` | dsh-compaction（storage 键值） | 存 `shadowedHeadCount`（被遮蔽头条数）；读 `read()`/写 `write()` | **单点 replace 头**：压缩摘要 append 后遮蔽旧头，回放从摘要起播 |
| 尾部窗口 | AgentLoopService `execute`（~545-560） | `from = max(boundary, size - maxHistoryMessages)`；`toExclusive = size-1`（排除当前 USER） | 模型可见 = **boundary 头裁剪 + 尾部窗口** |
| 配对过滤 | AgentLoopService `execute`（~560-590） | 窗口内 assistant(tool_calls) 须被 TOOL 全覆盖，孤立 TOOL 跳过 | 防 400 的**完整性规则**（非裁剪语义） |
| `MessageProjector.project` | dsh-agent（唯一投影 seam，javadoc 明示） | 行 → Spring AI 消息；pruned/超阈值 TOOL 读时截断 | 行级**投影渲染**（surface 之后执行） |
| `ToolResultPruner`/`ToolResultPruneService` | dsh-compaction/dsh-agent | durable pruned 标记 + 压力估算 | 单行内容级裁剪（**非消息序裁剪**，保持独立） |
| `SessionReadModel` | dsh-session | `table`（默认读投影）/`fact-replay`（验证读重放） | 读源开关，与 surface 正交 |

### 2.2bis 压缩/遮蔽精确语义（2026-09-04 代码核实，M7 必须忠实表述）
- **摘要物理在尾部、可见语义在头部**：压缩时 `sessionService.append(USER, "（历史压缩摘要）...")`
  把摘要写成 **fact 新行（seq=当前末尾+1）**；`newBoundary = effectiveFrom + compressedCount`
  写入 `CompactionBoundaryStore`（遮蔽头条数 = 绝对日志下标）。
- **当 turn 可见视图是特制列表**：`maybeCompact` 返回 `[摘要, ...plan.tail()]`（摘要在前）——不落库，
  仅本 turn 用；`from = max(0, size - maxHistory)`（compacted=true 时）。
- **后续 turn 从 boundary 起播**：`from = max(boundary, size - maxHistory)`（compacted=false 时），
  即"遮蔽旧头 [0..boundary) + 尾部窗口"。**摘要行 seq 在末尾** → 若 tail 非空且窗口足够大，
  模型实际看到的是 tail 在前、摘要行在尾 —— 物理 seq 与可见语义位置**错位**，靠"窗口足够小把
  摘要挤出/或 boundary 覆盖到近末尾"才不显形。这是 surface 要解耦的真实痛点（上游 replace 把
  摘要放在被替换段的位置，无此错位）。
- `markToolResultPruned` 是**内容级** durable 标记（原文保留、投影截断），与消息序遮蔽正交。

### 2.3 Gap 分析（为什么现有机制不够）
1. **不可任意段 replace**：`CompactionBoundaryStore` 只有"遮蔽头部 N 条"一个标量。若要
   "把第 5-8 条 TOOL 结果替换为一行摘要"、"把中间一段 assistant 回答折叠"——无表达。
2. **不可细粒度回卷/撤销**：边界只能前进（写更大的 N），无"恢复某段可见"操作；
   pruned 标记同理只置位不恢复。
3. **可见面无审计**：窗口/边界是"读时计算"，不落盘为"本次会话做过哪些表面操作"的序列，
   无法回答"这个会话的可见面是怎么演变的"（上游 surface generation 语义）。
4. **窗口语义耦合在 AgentLoop**：`from/toExclusive` 手工切片 + 配对过滤散在执行方法内，
   不是投影层的能力（MessageProjector javadoc 说自己是唯一 seam，但窗口切片不在它那里）。
5. **system prompt / tool schema 面未受影响**：surface 只裁剪消息序，不碰 prompt/tools（上游一致）。

### 2.4 契约锁（改造后须原样通过）
- AgentLoop*Test ×N、MessageProjectorPruneTest、SessionWriteSafetyUnitTest、SessionServiceTest、
  SessionFact*Test（M2 新增）、CompactionBoundaryStoreTest、ToolResultPruneServiceTest 等单元；
- DeepSeekE2ETest（~20 条真实 API）、Resident*Test（M4）、CommandRegistryTest（M6）等 e2e；
- **红线：`mvn verify` 482 全绿不回退**；压缩/修剪/窗口既有用例行为不变（默认空 surface = 现状窗口）。

## 3. 上游 surface 语义对照（素材来源：docs/capability-map-draft.md §1.2、IMPLEMENTATION_DIFF §4）

上游 `core/session` surface 面：
- **日志为真、可见面派生**：`deriveMessages()` 只投影 `user/message`、`assistant/message`、
  `tool/result` 三类 surface 事件，日志保留全量。
- **surfaceOp = generation/replace/append**：压缩以 `user/message + surfaceOp: replace` 做表面
  替换（替换被摘要覆盖的历史头）；surface 操作是**事件日志内的显式指令**。
- **seq-ranges/chunk-rows**：区间管理大日志/分块行（Java 侧 spill 已近似，不在本里程碑）。
- **projection registry（ctx.sessionProjections）**：派生状态（turnBoundary 等）由事件流计算并可缓存。

**对齐映射（本设计）**：

| 上游 | Java 现状 | M7 目标 |
|---|---|---|
| 日志为真 | `anchon_session_fact`（M2 ✅） | 不变（surface 不写 fact，只写指令） |
| surfaceOp replace/append（事件内） | `CompactionBoundaryStore` 标量 + AgentLoop 窗口 | `anchon_session_surface` 指令表（见 §4） |
| surface generation（可见面演进审计） | 无 | surface 指令带单调 `gen` 序号（可见面代数） |
| projection registry（派生状态可缓存） | 无（窗口读时计算） | 投影执行 seam：surface 指令 → 可见消息列表（§4.3） |
| repair/invariant | `SessionProjectionVerifier`（fact vs 投影行） | 不变；surface 指令表自身幂等/可重放校验（§4.4） |

## 4. 目标模型

```
真相层（不可变）：anchon_session_fact ──(seq 单调)──► 每次会话消息追加
可见面指令层（可变）：anchon_session_surface ──(gen 单调)──► 每次表面操作（replace/append）
执行 seam：SurfaceProjector(fact/投影行 + surface 指令 + 尾部窗口 + 配对过滤) → 模型可见 List<SessionMessage>
              │
              └──► MessageProjector.project（行级渲染，pruned/截断 —— 不变，仍唯一投影 seam）
```

### 4.1 surface 指令模型（`anchon_session_surface`）
新增表（评审点命名），记录"会话可见面的显式演进"，**不触碰 fact 真相**：

```
anchon_session_surface
  id            varchar(64)   # surface_{uuid}
  session_id    varchar(64)
  gen           bigint        # 可见面代数：会话内单调（1..N），每次表面操作 +1
  op            varchar(16)   # REPLACE_HEAD | REPLACE_RANGE | APPEND_VIEW
  range_from    bigint        # op 作用的事实 seq 起始（含）；REPLACE_HEAD 可等价 range_from=1
  range_to      bigint        # 事实 seq 结束（含）；null = 到当前末尾
  replacement   text          # REPLACE 时的摘要/折叠文本（USER 角色行）；null=纯遮蔽（隐藏不替换）
  meta_json     text          # 可选：reason=compaction|manual|tool-fold、由谁触发（executionId）、
                              #        originalCount/visibleCount 审计等
  created_at    timestamp
  UK(session_id, gen)
  (session_id, range_from, range_to) 普通索引
```

- **REPLACE_HEAD**：遮蔽 `[1..range_to]`（兼容现 CompactionBoundaryStore 单点语义：压缩写此
  op，遮蔽旧头）；`replacement` 为**该段的可见代表行**（派生视图行，不落 fact），置于遮蔽区间
  原起点 —— 让"摘要语义位置在头"不再依赖当 turn 特制列表或 seq 末尾错位（见 §2.2bis；压缩是否
  仍物理 append 摘要 fact 行见 D9）。
- **REPLACE_RANGE**：遮蔽任意 `[range_from..range_to]` 段，`replacement` 非空时以该行代替该段
  （折叠中间 TOOL 结果/中间回答）。
- **APPEND_VIEW**：仅附加可见性备注（用于"可见面恢复/回卷后记录"，非新消息追加 —— 消息追加
  仍走 fact append）。
- **语义**：指令**累积**（新指令只增不改旧指令）；"当前可见面" = 从 fact 全量按**最新生效的
  遮蔽集合**投影（重叠取最新 gen；见 §4.2 解析规则）。历史指令保留 = surface 演进审计（generation）。

### 4.2 解析规则（当前可见面 = f(fact, surface 指令, 尾部窗口)）
1. 取该会话 surface 指令按 `gen` 升序；构造**遮蔽区间表**：按 `range_from` 排序，重叠区间取
   gen 大者（后操作覆盖先操作），REPLACE_HEAD 视为 `[1..range_to]`。
2. fact 消息 `seq` 若落在任一遮蔽区间 → 不可见（模型侧）。
3. 遮蔽区间带 `replacement`（非空）→ 派生一条**视图行**（USER，content=replacement，虚拟定位在
   遮蔽区间原起点之前 —— 非 fact 行、无真实 seq），作为"该段的可见代表"，置于该段**原本在日志
   中的位置**（对齐上游 replace：摘要出现在被替换段的位置，而非日志末尾错位）；多个相邻遮蔽段
   各自在其起点插入。
4. 尾部窗口（现状 `maxHistoryMessages`）在**遮蔽后的可见序列**上应用（保持现状行为：
   只留最近 N 条可见消息；`toExclusive` 排除刚写入的当前 USER 语义由 AgentLoop 保留）。
5. 配对过滤（assistant tool_calls ↔ TOOL 全覆盖）在最终可见序列上执行（现状规则原样）。
6. 输出 List<SessionMessage> → `MessageProjector.project`（行级渲染不变）。

**兼容性**：空 surface 表（存量/未启用）→ 步骤 1-3 为空 → 退化为现状 `boundary + size-window`
（`CompactionBoundaryStore` 值读入作为隐式 REPLACE_HEAD，见 D3 双读兼容）。

### 4.3 执行 seam：`SurfaceProjector`
- 新增 dsh-session（或 dsh-agent？D 决策）：`projectVisible(sessionId, excludeLastN)` →
  读 fact/投影行 + surface 指令 → 按 §4.2 解析 → 返回可见 `List<SessionMessage>`。
- **AgentLoopService.execute / manualCompact 的窗口 + 配对过滤逻辑迁移进 seam**（行为等价，
  默认配置下与现状输出一致 —— 契约锁）；`CompactionBoundaryStore` 调用点改经 surface 指令
  读写（D3 决定保留旧存储还是迁移）。
- **投影 registry 化（远期，本里程碑只开 seam；→ M9 已定稿 `docs/design-projection-cache.md`）**：
  派生状态（遮蔽区间表、当前可见代数）进程内缓存 + fact/surface 变更失效 —— 对齐
  ctx.sessionProjections；M8 默认**不缓存**（读时计算，量级同现状窗口计算），M9 已落地（plan-a2eefa3a）：
  遮蔽区间表物化为会话级派生状态（SessionProjectionRegistry，Store 写后直失效 + 事件兜底双通道，
  命中零 DB 指令读 + 零 O(K²) 重建；A1 verify-gen 开关位预留多实例），记录见 docs/design-projection-cache.md §9。

### 4.4 写入 API 与回卷
- dsh-session 新增 `SessionSurfaceStore`：
  - `replaceHead(sessionId, count, replacement, reason)`（压缩用）；
  - `replaceRange(sessionId, fromSeq, toSeq, replacementOrNull, reason)`（工具折叠/中间折叠）；
  - `restoreRange(sessionId, fromSeq, toSeq, reason)`（回卷：遮蔽区间表追加"该区间恢复可见"
    的覆盖指令，append-only 审计完整，见 §4.2 重叠取最新 gen 规则）。
  - 均**@Transactional** + 会话行乐观锁 gate（与 SessionFactStore 同模式，防并发错序 gen）。
  - 写后发 `SESSION_SURFACE_CHANGED` 审计事件（SessionEventBus，observe-only，供前端/日志）。
- **不提供**：删除/修改历史 surface 指令（append-only 审计）；回卷只追加新指令。

### 4.5 与 fact 的关系（不变式）
- fact 是**唯一消息真相**：surface 永不改写 fact（连 pruned 都不动 —— pruned 仍由
  SessionFactStore.markPruned 管，它是"内容级"标记；surface 是"消息序/可见段"指令，两者正交，
  执行 seam 先 surface 遮蔽后 MessageProjector pruned 截断）。
- 投影缓存行（anchon_session_message）仍与 fact 同事务物化，**不受 surface 影响**（surface
  只作用于"模型可见视图"，不删行）。SessionQueryService 全文搜索仍查投影行（含被遮蔽消息，
  与上游"日志保留全量"一致）。

## 5. 决策点（请 reviewer/用户拍板）

- **D1 范围**：只做本设计定案（同 M3/M5：批准后落地按需）还是设计+落地一步到位？
  （推荐：设计定案 → 转 M8 正式 DAG，每步独立可合并。）
- **D2 surface 存储形态**：
  - A. 独立 `anchon_session_surface` 指令表（本设计推荐）：append-only 审计 + UK(gen) + 区间索引；
  - B. 复用 `anchon_session_fact` 存 surface 事件（混合真相/指令，需类型字段 + 与消息 seq 空间
    冲突，不推荐）；
  - C. storage 键值存 JSON 指令数组（轻但无 gen 单调/并发 gate/区间查询，不推荐，同 M1 fact 表
    取舍 C 理由）。
- **D3 CompactionBoundaryStore 兼容**：A. surface 启用后压缩写 REPLACE_HEAD，旧 boundary 读入
  作为隐式初始指令（双读兼容、零迁移，推荐）；B. 一次性迁移 boundary → surface 后弃旧存储。
- **D4 回卷实现**：A. append-only 追加"restore 覆盖指令"（重叠取最新 gen，审计完整，推荐）；
  B. 物理删除/修改历史指令（破坏 append-only，不推荐）。
- **D5 seam 落点**：A. dsh-session（读 fact/投影最近，语义属会话层；AgentLoop 调它，推荐）；
  B. dsh-agent（窗口逻辑现居处，但会反向依赖 session 的 surface 存储，分层倒挂）。
- **D6 启用粒度**：A. 默认空表全兼容，surface 写入 API 先行落地、读 seam 默认等价现状（推荐，
  无 flag 无灰度）；B. `dsh.session.surface=enabled|off` 开关（off 时完全走旧窗口路径）。
- **D7 里程碑边界**：本里程碑是否含"REPLACE_RANGE + restoreRange 全能力 + M8 验证用例"
  （推荐含：这是"任意段/回卷"的兑现点），还是只先落 REPLACE_HEAD 等兼容等价物？
- **D8 表/事件命名**：`anchon_session_surface` + `SESSION_SURFACE_CHANGED` 是否采纳。
- **D9 压缩摘要物理行策略**（忠实现状 vs 干净表面，影响压缩写路径行为）：A. 保持现状 —— 摘要
  仍 append fact USER 行（seq 末尾），surface REPLACE_HEAD 只记录遮蔽与 replacement（replacement
  与物理摘要行并存，读 seam 去重：有 surface 代表行时跳过末尾物理摘要行？需要去重规则，推荐）；
  B. 改变压缩写路径 —— 摘要**不再 append fact**，只写 surface REPLACE_HEAD + replacement（真相更
  干净但改既有压缩行为、动 M2 fact 语义，需迁移既有已 append 的摘要行，**不推荐**在本里程碑做）；
  C. 折中：新压缩双写（fact 摘要行加 `meta.surface=true` 标记 + surface 指令），读 seam 对标记行
  隐藏。 —— **D9-A 推荐**（零行为变更 + 读 seam 去重规则，M8-3 契约锁最稳）。

## 6. 验证红线（M8 落地完成判定，待 D1-D8 后定稿）

1. `mvn verify` 全绿（482 tests 基线不回退）；AgentLoop 契约类、Resident*、CommandRegistry 原样通过。
2. **兼容等价**：空 surface（或仅 REPLACE_HEAD 由 boundary 读入、D9-A 去重规则生效）时，
   模型可见序列与现状（boundary + 尾部窗口 + 配对过滤 + 当 turn 特制 [摘要+tail]）**逐条一致**
   （新增等价性用例：同会话双跑对比）。
3. **REPLACE_RANGE**：任意段遮蔽 + 折叠替换后，可见序列 = 头(若在遮蔽外) + 摘要 + 尾，且
   fact 全量不变（新增用例：折叠中间 TOOL 段）。
4. **回卷**：restoreRange 后该段重新可见（新增用例：遮蔽 → 恢复 → 可见；fact/投影行始终无损）。
5. **并发/幂等**：surface 写并发 gate 抛 `SessionConcurrentModificationException`（同 fact 契约）；
   重启后指令表完整（gen 连续），投影不变。
6. **性能**：surface 空/少量指令下读 seam 延迟与现状窗口同量级（探针对照，>10x 判失败）。

## 7. 风险与回退

- **行为漂移**：窗口/配对逻辑迁入 seam 是核心读路径改造 → 用既有 AgentLoop 契约测试当锁；
  默认空 surface 时输出逐条等价（红线 2）保证漂移可检出；seam 提供"旧逻辑直通"回退分支（D6-A
  下仍保留 CompactionBoundaryStore 读入路径，可逐点回退）。
- **过度设计**：若 D7 判只落 REPLACE_HEAD，本设计退化为"boundary 落成指令表"（收益=审计），
  全能力延后 —— 风险是语言未兑现承诺。推荐 D7 含 REPLACE_RANGE/restore。
- **查询面**：全文搜索仍见被遮蔽消息（有意为之，同上游日志全量）；若产品要求搜索也按可见面
  过滤，属后续增强（§4.5 已注明）。
- **存储增长**：指令表 append-only 量级小（每次压缩/折叠 1 行），无清理策略压力；可后续加
  tier/保留（同 R4 审计事件裁剪，不在本里程碑）。

## 8. 评审要点

1. §4 目标模型：surface 指令表形态（op 集、gen、range、replacement、meta）是否采纳；
2. §4.2 解析规则：遮蔽区间重叠取最新 gen + replacement 在区间起点插入的可见视图规则；
3. §4.3 执行 seam 落点（D5）与窗口/配对逻辑迁移（红线 2 等价性）；
4. §5 D1-D9 决策（推荐项已标）；
5. 里程碑边界（D7）：本设计批准后 M8 范围 = surface 表 + SessionSurfaceStore + SurfaceProjector
   迁移 + REPLACE_RANGE/restore + 验证红线全绿。

> 评审通过后：转 M8 落地 DAG（建表 + SessionSurfaceStore + SurfaceProjector seam + AgentLoop
> 窗口迁移 + REPLACE_RANGE/restore + 验证用例），每步独立可合并、带契约锁测试。

## 8.1 评审裁决（2026-09-04 人类拍板，D1–D9 定案）

- **D1 范围**：设计定案（本里程碑止步设计，M8 落地转正式 DAG，每步独立可合并）。
- **D2 surface 存储形态**：**方案 A**（独立 `anchon_session_surface` 指令表，append-only + UK(gen) + 区间索引）。
- **D3 CompactionBoundaryStore 兼容**：**方案 A**（旧 boundary 读入作为隐式初始 REPLACE_HEAD，双读兼容、零迁移；surface 启用后压缩写 REPLACE_HEAD）。
- **D4 回卷实现**：**方案 A**（append-only 追加"restore 覆盖指令"，重叠取最新 gen，审计完整）。
- **D5 seam 落点**：**方案 A**（dsh-session：读 fact/投影最近，语义属会话层；AgentLoop 调它）。
- **D6 启用粒度**：**方案 A**（默认空表全兼容，surface 写入 API 先行、读 seam 默认等价现状，无 flag 无灰度）。
- **D7 里程碑边界**：**含全能力**（REPLACE_RANGE + restoreRange + M8 验证用例 —— 这是"任意段/回卷"的兑现点）。
- **D8 命名**：采纳 `anchon_session_surface` + `SESSION_SURFACE_CHANGED`。
- **D9 压缩摘要物理行策略**：**方案 A**（摘要仍 append fact USER 行，surface REPLACE_HEAD 记录遮蔽与 replacement，读 seam 去重：有 surface 代表行时跳过末尾物理摘要行 —— 零行为变更 + 去重规则，M8-3 契约锁最稳）。

落地范围（转 M8 正式 DAG）：surface 表（§4.1）+ SessionSurfaceStore + SurfaceProjector seam（AgentLoop
窗口/配对过滤迁移）+ CompactionBoundaryStore 隐式初始指令 + D9-A 去重 + REPLACE_RANGE/restore 全能力 +
验证红线 §6 全绿。

## 9. 落地拆解（M8，D1–D9 定案后按此转正式 DAG）

- **M8-1 建表与实体**：Liquibase 变更集建 `anchon_session_surface`（§4.1 列，UK(session_id,gen)）、
  实体/仓储；`LiquibaseMigrationTest` 补断言。
- **M8-2 `SessionSurfaceStore`**：replaceHead/replaceRange/restoreRange + 会话行乐观锁 gate +
  发 `SESSION_SURFACE_CHANGED`；单测（并发 gate、gen 单调、幂等）。
- **M8-3 `SurfaceProjector` seam（读侧迁移）**：解析规则 §4.2 实现；AgentLoopService.execute/
  manualCompact 的 boundary 读取 + 尾部窗口 + 配对过滤迁移到 seam；CompactionBoundaryStore
  读入作隐式初始 REPLACE_HEAD（D3-A）+ D9-A 摘要物理行去重规则（有 surface 代表行时末尾物理
  摘要行不重复输出）；红线 2 等价性用例（双跑对比）。
- **M8-4 REPLACE_RANGE + restoreRange 全能力**：折叠中间段/回卷用例；fact/投影行无损断言；
  审计事件。
- **M8-5 验证收口**：红线 1/3/4/5/6 全绿 + 性能探针对照；更新 design-upstream-replication.md
  里程碑表（M7 专项设计 ✅ / M8 落地 ✅）。

（2026-09-04 draft：m7-1 现状核实 + 草案落档，等待人类评审 D1-D8 / 放行 m7-2 评审门。）


## 9.1 M8 落地记录（2026-09-04，plan-4fd83e93 completed）

- **M8-1** 建表与实体：Liquibase 变更集 `0008-surface-op.yaml`（dsh-boot master include）、
  `SessionSurfaceEntity`/`SurfaceInstruction`/`SurfaceOp`/`SessionSurfaceRepository`；
  LiquibaseMigrationTest.surfaceSchemaExists；`SessionSurfaceRepositoryTest` 4 用例（读写升序/UK DB 兜底/清理）。
- **M8-2** 写入 API：`SessionSurfaceStore`（replaceHead/replaceRange/restoreRange/listInstructions/
  currentGeneration；会话行乐观锁 gate + 乐观锁冲突 fail-fast + gen 单调 + `SESSION_SURFACE_CHANGED`
  审计事件（SessionEventType 新增、持久化监听 PERSISTED 纳入））；`SessionSurfaceStoreTest` 5 用例（含并发 2 线程无重复 gen）。
- **M8-3** 读侧 seam：`SurfaceProjector.projectVisible`（空 surface → legacy fast-path 与现状
  boundary+窗口+配对过滤逐条同构【红线2】；surface 指令 → 遮蔽区间表（重叠取最新 gen）+ replacement
  视图行置段起点 + D9-A content 去重 + 窗口 + 配对过滤含不完整 assistant 剥离）；AgentLoopService
  executeInner 装配 seam（可选注入，未装配走原路径）—— `SurfaceProjectorTest` 5 用例（等价性双跑）、
  `AgentLoopSurfaceSeamTest`（seam 装配契约：摘要视图行置头、被遮蔽历史不重发）。
- **M8-4** 全能力：REPLACE_RANGE 折叠任意段 + restoreRange 回卷（遮蔽做差挖洞/整体恢复）；
  压缩写路径升级（maybeCompact/manualCompact 写 surface REPLACE_HEAD，boundary 保留作兼容冗余）；
  `SurfaceOpVisibilityTest` 5 用例 + `SessionSurfaceFullFlowTest` 2 用例（fact/投影无损断言 + 审计事件）。
- **M8-5** 收口：全量 `mvn verify` 全绿（红线1/3/4/5）；`SurfaceProjectorPerfSmokeTest`（红线6：
  2 万条 × 20 轮 + 150 段指令线性量级，0.3s）；里程碑表更新。

红线达成：
1. ✅ `mvn verify` 全绿不回退（506 tests / 0 failures / 0 errors / 3 skipped）
2. ✅ 空 surface / legacy 与现状逐条等价（SurfaceProjectorTest.emptySurfaceWithLegacyBoundaryEqualsLegacyWindow + AgentLoop 契约锁）
3. ✅ REPLACE_RANGE 遮蔽+折叠（SurfaceOpVisibilityTest / SessionSurfaceFullFlowTest；fact 无损）
4. ✅ restoreRange 回卷恢复可见（同上）
5. ✅ 并发 gate 无重复 gen + 重启指令表完整（SessionSurfaceStoreTest.concurrentWritesFailFastWithoutGenDup + UK DB 兜底）
6. ✅ 性能同量级（PerfSmoke）

## 10. 自查评审记录（demo-plan-review，2026-09-04）

```yaml
verdict: approve（待人类正式放行 m7-2 评审门）
issues:
  - severity: high
    description: 草案 v1 臆造"replacement 在遮蔽区间起点插入"，与现状压缩语义不符 —— 已核实
      压缩摘要物理 append 为 fact 尾部行（seq=末尾+1），可见语义位置靠 boundary + 当 turn 特制
      [摘要+tail] 列表维持（§2.2bis），存在"物理 seq 与可见位置错位"痛点。v2 已修正表述并新增
      D9（摘要物理行策略，推荐 A：零行为变更 + 读 seam 去重）。
    suggestion: 重交评审时重点确认 §2.2bis 与 D9。
  - severity: medium
    description: §4.2 解析规则中"replacement 派生视图行 + 虚拟定位"目前无既有实现参照 ——
      需在 M8 用等价性用例锁死（红线 2：空 surface 与现状逐条一致）；若 D9-A 去重规则复杂
      可退化为 M8-3 先只做 REPLACE_HEAD 兼容等价，REPLACE_RANGE/restore 推 M8-4 单独验证。
    suggestion: M8 拆解已按此分步（M8-3 兼容 / M8-4 全能力），风险可隔离。
  - severity: low
    description: LiquibaseMigrationTest 需同步补 anchon_session_surface 断言；surface 指令表
      append-only 增长无清理策略（与审计事件 R4 同类，可后续 tier）。
    suggestion: M8-1 建表步骤包含 LiquibaseMigrationTest 断言更新。
  - severity: low
    description: 决策点 D1-D9 均给推荐项；用户缺席时可按推荐值缺省定案（同 M1 惯例），
      后续否决某推荐项再回改设计。
summary: 目标一致（design-event-sourced-session.md §7 远期收口 + design-upstream-replication.md
  差异①承诺兑现）；现状核实充分且有代码证据（§2.1/§2.2/§2.2bis）；v1 语义错误已自查修正；
  surface 指令表与 fact/投影/pruner 分层清晰、兼容路径明确（空 surface = 现状窗口）。风险项可
  在 M8 计划中隔离吸收。正式执行仍需人类 plan_step_review 放行 m7-2。
```

（2026-09-04 draft v2：m7-1 核实 + §2.2bis 忠实语义 + D9 已修订，等待人类评审 D1-D9 / 放行 m7-2 评审门。）
