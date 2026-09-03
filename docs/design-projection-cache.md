# 投影 registry 化缓存：遮蔽区间表进程内物化 — 专项设计（M9）

> 位置：`docs/design-projection-cache.md`
> 依据：`docs/design-surface-op.md` §4.3（"投影 registry 化（远期，本里程碑只开 seam）：派生状态
> （遮蔽区间表、当前可见代数）进程内缓存 + fact/surface 变更失效 —— 对齐 ctx.sessionProjections；
> 默认**不缓存**（读时计算），缓存作 M9 优化项"）、`docs/design-event-sourced-session.md` §7 远期
> （"projection registry（派生状态）… 可重建的物化投影（读模型）"）、`docs/IMPLEMENTATION_DIFF.md` §4/§11/§13.2
> 前置：M8 surfaceOp 落地完成 —— `mvn verify` 506 tests 全绿；读侧 seam `SurfaceProjector` 为
> 无状态纯函数，每 turn 从原始指令全量重算遮蔽区间表
> 状态：**定稿（2026-09-04 人类批准）+ 落地完成（plan-a2eefa3a）** — D1–D6 裁决见 §8；落地记录见 §9

## 1. 背景与目标

M8 把"模型可见面"升级为显式 surface 记录语言后，读路径（AgentLoop seam）每 turn 做：

1. `listMessages(sessionId)` —— 投影行全量读（DB N 行，主导成本，不在本设计范围）；
2. `listInstructions(sessionId)` —— 表面指令全量读（DB K 行 + metaJson 解析，M8-2 实现）；
3. `SurfaceProjector.projectVisible` —— 空指令走 fast-path（O(窗口)，与现状同构）；有指令走
   `buildSegments`（**遮蔽区间表从零重建**：指令依序 cover/uncover 做差 + 排序，最坏 O(K²)）→
   `applySegments`（O(N) 全历史遍历 + D9-A 去重）→ 排尾 → 尾部窗口 → 配对过滤。

**可观察浪费**：遮蔽区间表（`segments`）只依赖"指令序列（+ 指令为空时的 legacyBoundary）"，
而指令是 **append-only**，只在压缩（REPLACE_HEAD）、折叠（REPLACE_RANGE）、回卷（restoreRange）
时增长。两次 surface 写之间的所有 turn（通常是**绝大多数**）读到的指令集**逐位相同**，
`buildSegments` 却在每次读时把同一张表重算一遍，`listInstructions` 把同一批 K 行从 DB 重读一遍。

本设计把遮蔽区间表 + 可见代数**物化为会话级进程内派生状态**（对齐上游 `ctx.sessionProjections`
的 registry 语义），由 surface 写事件失效 —— 读路径从"K 行 DB + O(K²) 重建"降为"一次 Map 命中"。
**只缓存结构小、命中率高、失效锚唯一的派生状态；不缓存依赖每 turn 参数（history/窗口/排尾）的部分。**

**非目标**：不做历史消息查询缓存（`listMessages` 全量读是另一优化，触发条件与 surface 无关）；
不引入第三方缓存库/分布式缓存；不缓存 applySegments 的可见消息列表（依赖 history 内容与 D9-A
匹配，append 后即陈旧，且省的是 O(N) CPU 而非 DB 主导成本）；不改 REST/SSE/OpenAI 契约；
不迁移 M8 的表/指令语义；不新增多实例一致性基础设施（一致性档见 D4，默认单实例强一致）。

## 2. 现状核实（2026-09-04 代码级清点，M9 设计依据）

### 2.1 读侧 seam 装配（dsh-agent `AgentLoopService`）
- `hasSurfaceSeam()` = `surfaceProjector != null && sessionSurfaceStore != null`（可选注入，未装配走原路径）；
- execute 每 turn：`history = sessionService.listMessages(sessionId)` → `compaction = maybeCompact(...)`
  （读 `CompactionBoundaryStore` boundary）→ 若 seam 且未压缩：`surfaceOps = sessionSurfaceStore.listInstructions(sessionId)`
  → `projectVisible(history, surfaceOps, compaction.boundary(), maxHistoryMessages, 1)`；
- 压缩当 turn（`compacted()==true`）走特制 `[摘要 + tail]` 列表，**不经过 projectVisible**；
- `manualCompact`（/compact 命令）与 `maybeCompact` 自动压缩（AgentLoopService:251/969）都是
  **先 `compactionBoundaryStore.write` 再 `sessionSurfaceStore.replaceHead`**（M8-3 双写冗余兼容）。

### 2.2 写入侧失效锚（dsh-session `SessionSurfaceStore`）
- 唯一写入口：`replaceHead` / `replaceRange` / `restoreRange`（类 javadoc 明示"唯一写入口"）；
- 每写分配单调 `gen`（会话行乐观锁 gate 后 count+1），append-only 落 `anchon_session_surface`；
- 写后 `eventBus.publish(sessionId, SESSION_SURFACE_CHANGED, ...)`（M8-2）；
- `currentGeneration(sessionId)` = `countBySessionId`（1 行 count，O(1) DB）。

### 2.3 SurfaceProjector（dsh-session，无状态纯函数）
- `Segment(long from, long to, String replacement)` 为**同包 record**（package-private，缓存组件同包可直接持有）；
- `buildSegments(List<SurfaceInstruction> ops, long legacyBoundary)`：指令为空且 boundary>0 时以
  `replaceHead(0, boundary, null, "legacy-boundary")` 作隐式指令（D3-A）；`cover`/`uncover` 每次
  返回**新列表并排序** → 最坏 O(K²)；
- 结论：遮蔽区间表是**纯指令集的确定性函数**，适合物化。

### 2.4 失效语义推导（fact append 对遮蔽区间表免疫）
- 遮蔽区间（REPLACE_HEAD 的 `[1..to]`、REPLACE_RANGE 的 `[from..to]`、restore 的做差）**区间端点
  在指令写定时刻即固定**，只指向当时已存在的 seq；
- 之后的 fact/投影行 append 都是**更大 seq**，不会落入既有遮蔽区间 → `segments` 不被 append 影响；
- 压缩（boundary 写）在 seam 下**必伴随 surface replaceHead**（§2.1）→ surface 写事件已覆盖压缩失效；
- 空指令 fast-path（legacy 纯窗口）只依赖 boundary + 窗口参数，不缓存 segments（本设计只缓存
  surface 派生状态；fast-path 已是最优 O(窗口)，无需缓存）。

### 2.5 容量与依赖事实
- 无 caffeine/cache2k 等缓存库依赖（pom 清点）→ 自写轻量 registry，不引入三方；
- `SessionId` 为 record（`String value()`）→ equals/hashCode 就绪，可直接作 Map key；
- `SESSION_SURFACE_CHANGED` 事件 M8 已持久化审计（SessionEventPersistenceListener）——事件面已存在。

## 3. 上游对照

| 上游 | M8 Java 现状 | M9 目标 |
|---|---|---|
| projection registry（`ctx.sessionProjections`）：派生状态由事件流计算并缓存 | 无（读时计算：每 turn 全量指令 + 遮蔽区间表从零重建） | 会话级 registry：遮蔽区间表 + 可见代数物化，surface 写失效 |
| surface generation（可见面代数） | `gen` 已落库（append-only 连续无洞），但读路径未利用 | 缓存 entry 携带代数，与 DB 代数对照判有效（一致性档见 D4） |
| 派生状态按会话隔离 | — | Map<SessionId, Entry>，会话隔离，互不串扰 |

## 4. 方案

### 4.1 缓存物：`SessionProjection`（会话级派生状态快照）
```
SessionProjection {
    long surfaceGen;              // 可见面代数 = 已应用的指令数（DB count 口径）
    long legacyBoundaryAtSurface; // 指令为空时的 boundary（fast-path 参数；有指令时 unused）
    List<Segment> segments;       // 遮蔽区间表（SurfaceProjector.Segment，有序、做差完毕）
}
```
- `surfaceGen == 0` 且 `segments` 空 = "无 surface 指令"（fast-path 语义，不缓存也正确 —— registry
  命中代数 0 时可直接走现状 fast-path，无需持有 segments）；
- 构建：`SurfaceProjector.buildSegments(listInstructions(sessionId), boundary)`（现有纯逻辑复用，零语义复制）。

### 4.2 组件：`SessionProjectionRegistry`（dsh-session，@Component）
- 存储：`ConcurrentHashMap<SessionId, Entry>`；`Entry { volatile SessionProjection projection; long lastAccessNanos; }`；
- 读：`Optional<SessionProjection> get(SessionId)`（命中刷 lastAccess）；
- 写失效：`invalidate(SessionId)`（删 key，下一读惰性重建）；
- 原子装载：`SessionProjection compute(SessionId, long boundary, Supplier<List<SurfaceInstruction>> loader)`
  —— `map.compute` 原子：miss / 代数落后 → 调 loader（默认 `SessionSurfaceStore::listInstructions`）
  → `buildSegments` → put；并发同 miss 由 CHM.compute 合并为一次装载；
- 容量：maxEntries（默认 4096，配置 `dsh.session.projection-cache.max-entries`）；超限时清空
  lastAccess 最旧的一半（简单 LRU-ish；会话并发量小，逐出后读回 DB 重建，正确性无损）；
- **归属 dsh-session**：与 `SurfaceProjector` 同包，`Segment`/`buildSegments` 包可见直接复用；
  `SessionSurfaceStore` 注入 loader —— 依赖方向 `AgentLoop → Registry（dsh-session）→ Store`，不回引 dsh-agent。

### 4.3 AgentLoop seam 装配改动（最小侵入）
- 读侧现状（§2.1 第 3 步）替换为：
  ```
  SessionProjection proj = registry.compute(sessionId, boundary,
                              () -> sessionSurfaceStore.listInstructions(sessionId));
  List<SessionMessage> visible = surfaceProjector.projectVisibleFrom(history, proj, maxHistory, 1);
  ```
  即把"原始指令 + buildSegments"换成"registry 派生快照"；`SurfaceProjector` 新增包可见重载
  `projectVisibleFrom(history, SessionProjection, maxHistory, excludeTrailing)` —— **与现有
  `projectVisible(history, instructions, boundary, …)` 走完全相同的 applySegments/窗口/配对过滤**，
  仅跳过 buildSegments（快照已是 segments）；空快照（gen=0）走原 fast-path 分支，行为逐位不变；
- 现有 `projectVisible`（从指令直算）**保留不动**：契约锁测试、双跑对比、未装配 registry 场景沿用；
- `registry` 为可选注入（同 surfaceProjector 模式）：未装配时 seam 退化为现状直算 —— M8 契约锁零破坏。

### 4.4 失效通道
- **本地直失效**：`SessionSurfaceStore.replaceHead/replaceRange/restoreRange` 写方法末尾
  `projectionRegistry.invalidate(sessionId)`（同模块直接注入；Store 是唯一写入口，失效与时序零竞态
  —— 写事务内失效发生在任何后续读之前，JVM happens-before 成立）；
- **事件兜底（可选）**：registry 构造时 `eventBus.addListener` 订阅 `SESSION_SURFACE_CHANGED` →
  invalidate。与直失效冗余（防未来出现绕过 Store 的写者）；若 D3 选"仅直失效"，事件监听不注册；
- boundary 单独写**不触发失效**（§2.4：seam 下压缩总伴随 replaceHead；空指令 fast-path 无缓存物）。

### 4.5 一致性档（D4 裁决）
| 档 | 机制 | DB 附加成本 | 正确性 | 适用 |
|---|---|---|---|---|
| A0 | 仅本地失效（直失效 + 事件冗余） | 0（命中零额外读） | 单实例强一致；多实例各自缓存可陈旧（无跨实例通知） | **推荐默认**：本项目单进程部署、surface 写低频 |
| A1 | 每次 seam 读先 `currentGeneration()`（1 行 count）与 entry.surfaceGen 比对，落后即重建 | 1 count / 读 | 跨实例正确（最多滞后一事务提交） | 多实例水平扩展时（未来，配置开关） |
| A2 | 本地失效 + 最大 TTL（如 60s）兜底 | 0 | 单实例强一致 + 陈旧上界（防漏失效） | 可作 A0 的清理兜底（可选叠加） |

落地实现 A0 骨架 + A1 开关位（`dsh.session.projection-cache.verify-gen=false`），A2 视 D4 裁决追加 TTL 字段。

### 4.6 收益量级（分析）
命中时每 turn 省：`listInstructions`（K 行 DB 往返 + parse）与 `buildSegments`（O(K²) 做差+排序）。
参考 M8 PerfSmoke：150 段指令 × 2 万条 × 20 轮 = 0.3s 总量（含 applySegments），其中 buildSegments
为每次调用固定开销。指令表在两次压缩/折叠之间不变 → 长期会话命中率 ≈ 100%（除写后首读）。
场景：K=150、QPS=20、每读省 ~50µs–1ms CPU + 1 次 DB 往返 → 数 % ~ 低单位数 % 读路径延迟。
**收益真实但非紧急** —— 属"低风险、中收益"优化；本设计定案是把它从"每 turn 重复劳动"转为
"版本化物化 + 失效"，并为未来多派生（UI 可见面 / 标题摘要等多个 ctx.sessionProjections 投影）
提供 registry 骨架。触发条件按 design-upstream-replication.md §4 口径：**长会话高读频成为热点时启用**；
落地成本小（纯增量组件 + 一行装配 + 契约锁），批准后可随时落地。

## 5. 验证红线（M9 落地后）

1. `mvn verify` 全绿不回退（506 + 新增用例）；
2. **缓存命中与直算逐条一致**：同会话同代数下 `registry 快照路径` 与 `原 projectVisible 直算路径`
   输出消息列表逐条相同（多场景双跑：空指令+legacy / REPLACE_HEAD / 折叠 / restore / 混合）；
3. **失效正确**：surface 写后下一读不含旧遮蔽（零陈旧窗口）—— 写 replaceHead 后再 projectVisible，
   输出与"直算最新指令"一致且与写前不同；
4. **并发安全**：多线程交替读/写同会话（对齐 SessionSurfaceStoreTest 并发口径）无 stale 输出、无异常；
5. **容量逐出自愈**：maxEntries 压满逐出后，下一次读自动重建且输出正确（不依赖被逐缓存）；
6. **性能**：命中路径 ≥ 10x 快于同参直算重建（宽松烟雾，PerfSmoke 风格）；A1 档开启时读路径仅 +1 count。

## 6. 风险与回退
- **陈旧投影**：A0 下若未来出现绕过 Store 的 surface 写者 → 事件兜底（D3-C）拦截；A1 开关可无痛升级；
- **内存**：每 entry 为 O(K) 段列表（K=指令数，KB 级/会话），maxEntries + 逐出限制上界；
- **行为漂移**：registry 只替换 buildSegments 的来源，applySegments/窗口/配对过滤逻辑零改动；
  契约锁（§5-2 双跑）与 M8 全部用例原样通过即证明等价；
- **回退**：删装配一行即回现状直算（registry 组件保留无害）。

## 7. 落地拆解（M9 落地，D1–D6 定案后按此转正式 DAG；步骤 m9r-*，与本文档编号一致）
- **M9-1 缓存物 + Registry**：`SessionProjection` + `SessionProjectionRegistry`（CHM + compute +
  容量逐出 + 配置 max-entries）；`SurfaceProjector.buildSegments` 复用（包可见已满足）；
- **M9-2 失效接线**：`SessionSurfaceStore` 三写方法末尾 invalidate（+ D3 裁决：事件兜底监听）；
- **M9-3 seam 装配**：`SurfaceProjector.projectVisibleFrom`（快照重载）+ AgentLoop 读侧换 registry
  （可选注入，未装配走现状直算）；`projectVisible` 原方法保留；
- **M9-4 验证**：红线 §5 全绿（双跑等价 / 失效正确 / 并发 / 逐出自愈 / PerfSmoke 对照）；
  更新 design-surface-op.md §4.3 远期指针 + design-upstream-replication.md 里程碑 M9 落地 ✅。

## 8. 评审裁决（2026-09-04 人类拍板，D1–D6 定案）

- **D1 范围**：本里程碑止步设计（用户选定"先做设计"）；M9 落地转正式 DAG，每步独立可合并（同 M7→M8 模式）。
- **D2 缓存粒度**：**方案 A**（仅遮蔽区间表 + 可见代数快照 `SessionProjection`；不缓存净可见列表 ——
  依赖 history append 与 D9-A content 匹配，收益低复杂度高）。
- **D3 失效通道**：**方案 C（双通道冗余）** —— Store 本地直失效为主（唯一写入口、同步、零延迟窗口）
  + `SESSION_SURFACE_CHANGED` 事件监听兜底（防未来绕过 Store 的写者；冗余失效幂等无害）。
- **D4 一致性档**：**A0 + A1 开关位** —— 默认 A0（仅本地失效，单实例强一致，命中零 DB）；
  配置 `dsh.session.projection-cache.verify-gen=false` 置 true 时走 A1（每读 `currentGeneration()`
  count 校验，多实例水平扩展正确，读路径 +1 count）；A2（TTL）不实现，逐出兜底由容量策略承担。
- **D5 容量策略**：**maxEntries 默认 4096（配置 `dsh.session.projection-cache.max-entries`）+
  超限逐出 lastAccess 最旧一半**（LRU-ish，无三方依赖；逐出后读回 DB 重建，正确性无损）。
- **D6 归属与装配**：**Registry 放 dsh-session**（与 `SurfaceProjector` 同包，`Segment`/`buildSegments`
  包可见直接复用；依赖方向 AgentLoop → Registry → Store，不回引 dsh-agent）；AgentLoop 可选注入
  registry（未装配走现状直算 —— M8 seam 契约锁零破坏）；原 `projectVisible`（指令直算）保留作契约锁。

（2026-09-04 评审通过：D1–D6 全部按推荐定案，放行 M9 落地 DAG 拆解。）

- **D1 范围**：本里程碑止步设计（用户已选"先做设计"）；批准后 M9 落地转正式 DAG，每步独立可合并？
- **D2 缓存粒度**：方案 A（仅遮蔽区间表 + 代数快照，推荐）/ B（另缓存净可见列表 —— 依赖 history
  append 与 D9-A content，收益低复杂度高，不推荐）/ C（仅代数戳不缓存 segments —— 无意义）？
- **D3 失效通道**：方案 A（Store 本地直失效）/ B（仅 SESSION_SURFACE_CHANGED 事件失效）/
  C（双通道冗余：直失效为主 + 事件兜底防未来绕过 Store 的写者 —— **推荐**，冗余失效幂等无害）？
- **D4 一致性档**：方案 A0（本地失效，无 DB 校验 —— 推荐默认，单实例强一致）/ A1（每读 count 校验，
  多实例正确，配置开关）/ A2（A0 + TTL 兜底）？默认启 A0、留 A1 开关位可否？
- **D5 容量策略**：maxEntries 4096 + 超限逐出 lastAccess 最旧半（推荐，无三方依赖）/ 不限制（会话数
  有界，靠 TTL）/ 引入 caffeine 依赖？
- **D6 归属与装配**：Registry 放 dsh-session（与 SurfaceProjector 同包，推荐）/ 放 dsh-agent（需
  暴露 Segment 为 public，破坏封装）？AgentLoop 可选注入退直算（同 M8 seam 模式）可否？

（2026-09-04 draft：m9-1 现状核实完成，草案落档，等待人类评审 D1–D6 / 放行 m9-3 定稿。）

## 9. M9 落地记录（2026-09-04，plan-a2eefa3a completed）

- **M9R-1** 缓存物 + Registry：`SessionProjection`（surfaceGen + 遮蔽区间表不可变快照；无指令不建，
  fast-path 语义）与 `SessionProjectionRegistry`（@Component，CHM + `snapshot(sessionId, loader, genLoader)`
  原子装载 + invalidate + maxEntries 4096 配置 + LRU-ish 逐出 + SESSION_SURFACE_CHANGED 事件兜底监听）；
  SurfaceProjector 拆出 `projectFromSegments` 共享段 + `projectVisible(history, projection, …)` 快照重载
  （projectVisible 指令直算保留契约锁）—— `SessionProjectionRegistryTest` 10 用例（命中零重载/空表不缓存/
  直失效重载/事件兜底/逐出自愈/A1 开关/双跑等价/并发 smoke/命中 ≥10x perf）。
- **M9R-2** 失效接线：SessionSurfaceStore 三写方法统一走 write() → 落库后 `projectionRegistry.invalidate`
  （Optional 注入，无 registry no-op；D3-C 主通道）；事件兜底监听（副通道）—— Store 既有测试零改动。
- **M9R-3** seam 装配：AgentLoopService 注入 `Optional` registry（setter，required=false）；seam 读侧
  registry 优先：snapshot 命中 → `projectVisible(history, projection, …)` 跳过 listInstructions/buidSegments；
  无快照（无指令）→ legacy fast-path 用实时 boundary；未装配 registry → M8 直算原路径 ——
  `AgentLoopProjectionRegistryTest` 2 用例（跨 turn 命中 loader 一次 + 直算回退同构）。
- **M9R-4** 验证收口：`SessionProjectionRegistryFlowTest` 2 用例（真实容器写后零陈旧 + 快照/直算逐条一致）；
  全量 `mvn verify` 全绿（506 + 14 新增用例 = 520）；文档里程碑 M9 落地 ✅。

红线达成：
1. ✅ `mvn verify` 全绿不回退（520 tests / 0 failures / 0 errors / 3 skipped）
2. ✅ 快照命中与指令直算逐条一致（RegistryTest.snapshotPathEqualsDirectPathForAllScenarios 4 场景 +
   FlowTest 双跑）
3. ✅ 失效正确：Store 写后下读零陈旧（FlowTest.writeInvalidates…；单测 invalidate/事件双通道）
4. ✅ 并发：多线程读写交错无异常（RegistryTest.concurrentSnapshotAndInvalidateAreSafe）
5. ✅ 容量逐出自愈：maxEntries 压满后读回重建正确（evictionRemovesOldestAndRebuilds）
6. ✅ 性能：命中 ≥10x 快于同参重建（registryHitIsAtLeast10xFasterThanRebuild，200 轮对照）

## 10. 自查评审记录
- [x] 方案不臆造：缓存物/失效锚/容量均来自代码清点（§2.1–2.5）与 M8 既有语义；
- [x] 与既有设计衔接：M8 §4.3"投影 registry 化（远期…缓存作 M9 优化项）"原文已引用为依据；
- [x] 契约锁：原 `projectVisible` 直算路径保留 + 双跑等价测试 → M8 用例零改动通过；
- [x] 一致性档覆盖多实例演进（A1 开关），不引入分布式基础设施；
- [x] 触发条件如实标注（收益真实非紧急，按需落地）；2026-09-04 人类批准 D1–D6 定稿。
