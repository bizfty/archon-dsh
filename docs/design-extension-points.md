# 支柱③专项设计：扩展点抽象（DshExtension 注册表 + 命令注册表范围圈定）

> 位置：`docs/design-extension-points.md`
> 依据：`docs/design-upstream-replication.md` §3.3（支柱③ 扩展点抽象）与 §4 外围增量（命令子系统）
> 前置：支柱①（M1/M2 事件为真）、支柱②（M3/M4 常驻 agent）已达成 —— `mvn verify` 478 tests 全绿
> 状态：M5 专项设计（plan-c68c5bfa）—— **人类评审放行后定稿**

## 1. 目标

上游以 Cordis 插件协议（生命周期/teardown/schema/waterfall）承载扩展；Java 侧现状是
"Spring bean 图 + 各模块自建有序链"。支柱③不做热装卸/插件沙箱（收益低、成本高），只做
**机制对齐到统一可插拔表达**：让"哪些扩展点在跑、按什么序、能否停用"从隐式（散落各处）
变为显式（一处注册表可见可配）。判定标准沿用总文档 §1：**消除真实缺陷或解锁语义无法
表达的能力**，而非"长得像上游"。

本设计同时圈定**命令注册表**（外围低成本项，design-resident-agent.md D7 曾挂起）是否纳入。

## 2. 现状核实（2026-09-04 代码面清点，M5-1 落档）

已存在三类扩展形态，互相割裂：

**A. Spring 接口集合注入型有序链**（同一接口的多个实现 bean 由容器收集、order 排序）：
- `ToolExecutionPipeline`（dsh-tool，`@Component` 构造 `List<ToolPreExecuteGate>` +
  `List<ToolPostProcessor>`，`order()` 升序执行；门控单调拒绝——一旦拒绝后续不放行）。
  实现者跨模块：`ApprovalGate`（dsh-interaction，审批门）、`HookGate`/`HookPostProcessor`
  （dsh-hooks）、`RepeatToolReminder`（dsh-guard）等 —— **扩展散落在各 jar，无集中视图**。
- `SystemPromptService`（dsh-core，构造 `List<SystemPromptSection>` + AgentScopeRegistry 的
  extraSections 组合，`order()` 排序渲染）。

**B. 运行时注册表模式**（进程内 register/resolve）：
- `ToolRegistry`（dsh-tool）：`ApplicationContext.getBeansOfType(AgentTool.class)` 启动收集 +
  `registerDynamic(AgentTool)` 运行时注册（LinkedHashMap 保序）。
- `AgentScopeRegistry`（dsh-agent）：`CopyOnWriteArrayList` + `register(...)`/`resolve(Agent)`；
  同键遮蔽（order 大者胜、精确 id 优先于通配 `*`）—— **最接近目标注册表形态的现成模板**。

**C. observe-only 事件总线**：`SessionEventBus`（dsh-core，`addListener` order 排序、监听器
异常隔离）。javadoc 明示：**waterfall（可短路链）语义不进总线**——需要短路/替换的扩展点
（tool pre-execute / system-prompt assemble）由各模块自建有序链承担。**本设计保持此决定不变**
（支柱③不把 observe-only 改成短路总线）。

**D. 命令面**：`SessionController.tryCommand(Session, message)` 特判 `trimmed.equals("/compact")`
（chat 与 stream 两入口各调一次），无命令注册表、无参数解析、无其他命令。上游有 commands/*
子系统（命令面膨胀时再落地 —— 总文档 §4 触发条件）。

**E. 可选能力注入面**（已是"扩展点"但不叫这名）：`GoalService`/`PlanService`/`EventLogReader`/
`SessionCancellation`/`ResidentAgentRegistry` 在 AgentLoopService 以 `@Autowired(required=false)`
可空注入 —— 能力有无决定行为退化（无 goal → 单 turn；无 registry → 直接执行）。这是
**"内置扩展点"事实**，但不适合收编进通用注册表（它们是核心能力的条件装配，非第三方插件）。

**结论**：Java 侧真正的"扩展缺口"不是没有扩展能力（Spring 已很强），而是 **①跨模块扩展无
集中可见/可配视图；②命令面是特判 if 而非注册**。M5 若做，聚焦这两点。

## 3. 目标模型（候选，供 D 决策）

### 3.1 DshExtension 注册表（范围候选一：中成本）

轻量 `DshExtensionRegistry`（放 dsh-core 或新 dsh-extension 模块？—— D 决策）：
- **注册面**：启动时从 Spring 上下文收集显式标注 `@DshExtension(extensionPoint=..., order=...)`
  的 bean；也支持运行时 `register(...)`（对齐 AgentScopeRegistry 模板）。
- **扩展点分类**（枚举，先收编已有有序链，不发明新语义）：
  - `TOOL_PRE_EXECUTE_GATE` / `TOOL_POST_PROCESSOR`（现 ToolExecutionPipeline 的 List 参数来源改读注册表）
  - `SYSTEM_PROMPT_SECTION`（现 SystemPromptService 的 sections 收集改读注册表）
- **可见/可配**：`enabled` 标志（`@DshExtension(enabled=false)` 或运行时开关）、按扩展点列出
  生效链（含来源 bean 名与 order）→ 运维/诊断端点或日志。
- **不做**：热装卸、沙箱、生命周期 teardown、schema 合并（总文档 §3.3 明示不要求）。

### 3.2 命令注册表（范围候选二：低成本）

- `ChatCommand` 接口（name/描述/参数说明/execute(Session, args)→文本结果），
  `CommandRegistry`：Spring 收集 + `register`；`SessionController.tryCommand` 改为查注册表。
- 首收编 `/compact`（现 AgentLoopService.manualCompact），预留 `/help`（列命令）。
- 与 ResidentAgent 关系：命令项沿用 m4 的 `manualCompact` 门面委托入队语义不变；只把
  **识别与分发**从 if 特判改为注册表查表。

### 3.3 与现状关系

- `ToolExecutionPipeline` 仍保留（它是执行管线本体）；改的是**链的收集来源**（构造 List →
  注册表视图）或仅加"集中视图"不改收集（D 决策）。**契约锁：AgentLoop 测试与 19 类不变**。
- `SessionEventBus` observe-only 与短路分工声明**原样保留**（不改代码，必要时把 javadoc 引用
  到本文档作为设计锚点）。
- `AgentScopeRegistry` 保持独立（语义特殊：遮蔽），不并入通用注册表；但可作注册表 API 模板。

## 4. 验证红线（若落地，M5 完成判定）

1. `mvn verify` 全绿（478 tests 基线不回退）；19 个 AgentLoop 契约测试类原样通过。
2. 注册表视图：新增用例 —— 注册表列出 TOOL_PRE_EXECUTE_GATE 链含 ApprovalGate/HookGate 且
   order 正确；`enabled=false` 的扩展不进入执行链（新增 gate 停用用例）。
3. 命令注册表：`/compact` 经注册表分发结果与特判路径一致（契约测试对比）；`/help` 列出命令。
4. 跨模块依赖不新增环：dsh-core 不反向依赖扩展实现者（注册表只收接口/注解，实现靠 Spring
   收集或所在模块注册）。

## 5. 决策点（请 reviewer/用户拍板）

- **D1 范围**：只做设计定案（同 M3：批准后落地按需）还是设计+落地一步到位？
- **D2 支柱③做不做**：DshExtensionRegistry 收编 ToolExecutionPipeline/SystemPromptService 链
  （中成本、收益=集中视图+启用开关） vs 维持现状（Spring 集合注入已可用，仅文档化"如何加扩展"）？
- **D3 命令注册表做不做**：低成本先落地 `/compact` 收编 + `/help`（推荐） vs 维持 if 特判
  （命令面未膨胀）？
- **D4 注册表模块**：若做，放 dsh-core（接口/注解/注册表）——各模块实现不依赖 dsh-tool/dsh-agent？
- **D5 启用开关粒度**：`@DshExtension(enabled=false)` 静态禁用 vs 运行时 `disable(扩展名)`？
- **D6 收集时机**：启动全量收集（Spring beans） vs 惰性（首次用扩展点按类型收集）？
- **D7 enabled 后门**：停用某 gate 是否允许？ToolPreExecuteGate 含审批门 —— 停用=关闭安全门，
  是否加"安全关键扩展不可停用"保护位？
- **D8 命令语法**：仅 `/<name>`（现 /compact 形态） vs `/<name> <args>`（需参数解析/help 说明）？

## 6. 风险与回退

- **行为漂移**：链收集来源改动 → 顺序/集合语义必须与现状一致（order 升序、门控单调拒绝），
  用既有契约测试锁死；注册表只做"收集源替换"且保留构造 List 兼容路径可逐点回退。
- **过度设计**：若 D2 判"不做注册表"，支柱③收敛为**一份《如何扩展》文档**（把 A/B/C 三形态
  与"新增一个 gate/tool/section/命令"的步骤写成指南）——零代码风险同样满足"统一扩展契约"文档诉求。
- **命令面误判**：命令注册表收益低时 D3 判不做，保留 if 特判（现状无真实缺陷）。

## 7. M5 落地拆解草案（待 D1-D8 拍板后转正式 DAG；若仅设计定案则止步 §5）

- **M5-1 现状核实**（本文 §2，已完成落档）。
- **M5-2 设计定稿**（本文，评审门）。
- **若批准落地**：
  - 命令注册表（推荐先做）：`ChatCommand`/`CommandRegistry` + `/compact` 收编 + `/help` + 契约测试。
  - 扩展注册表（视 D2）：`@DshExtension` 注解 + `DshExtensionRegistry` + ToolExecutionPipeline/
    SystemPromptService 收集源替换（或仅加集中视图端点）+ 停用用例。
- **验证**：红线 §4 全绿。

（2026-09-04 draft：M5-1 现状核实完成，等待人类评审 D1-D8。）

## 8. 评审裁决（2026-09-04 人类拍板，D1-D8 定案）

- **D1 范围**：设计定案 + 落地**命令注册表**；扩展注册表不做。
- **D2 支柱③（DshExtensionRegistry）**：**不做**——维持 Spring 集合注入现状，补
  `docs/how-to-extend.md`《扩展指南》文档化 A/B/C 三形态与新增扩展步骤（零代码风险满足
  "统一扩展契约"文档诉求）。
- **D3 命令注册表**：**做**（低成本先落地）：`/compact` 收编 + `/help` 列命令；
  `SessionController.tryCommand` if 特判 → `CommandRegistry` 查表分发（chat/stream 行为不变）。
- **D4-D8**：因不做扩展注册表，D4-D7 不适用（不引入新模块/注解/开关）；D8 命令语法维持
  `/<name>`（/compact 现状形态），/help 仅列名与说明，不做参数解析。

落地范围（转正式 DAG）：命令注册表（ChatCommand/CommandRegistry + /compact 收编 + /help）+
《扩展指南》文档。验证红线 §4.1/§4.3：mvn verify 全绿（478 基线不回退）+ /compact 经注册表
分发结果与特判一致 + /help 列命令。
