# schema 驱动 UI 专项设计（内核②全量落地）

> 基线：archon-dsh HEAD `a0585e0`（2026-09-04，M0–M9 后端三支柱全落地，520 tests 全绿）
> 上游参考：external/deepseek `76fda72`（packages/client/schema-form = **一套 schema 多消费**）
> 配套：[design-frontend-replication.md](design-frontend-replication.md) §4 路径 C / §3 内核②（schema 单源消费）
> 目标文档：[design-upstream-replication.md](design-upstream-replication.md) 里程碑表（远期项「schema 驱动 UI」）

## 1. 目标与边界（用户 2026-09-04 圈定档位）

在 Vue 栈内落地 schema 单源消费，**后端 schema 成为唯一事实源，新工具/新设置项不再要求改前端**：

- **A. 最小闭环（工具元数据）**：后端补工具显示元数据 + `GET /api/tools/meta` 下发
  （name/标题/摘要键/inputSchema）；前端 `MsgView.vue` 删除硬编码 `SUMMARY_KEYS`/`TOOL_TITLES`，
  改为按工具名查元数据渲染标题与参数摘要，未命中走泛化兜底。
- **B. Settings 动态表单**：dsh-settings 补**设置描述符**（key → type/label/description/默认值/选项/边界）
  注册机制 + `GET /api/settings/meta` 下发；前端新增 `SchemaForm.vue` 通用 schema 表单组件 +
  `SettingsPage.vue` 设置页（动态生成表单，保存走既有 PUT），接入 Sidebar/App 视图。

**不做**（范围克制）：L1 事件投影 store（内核①，另一专题）、L3 App.vue 全面插槽化、Settings 之外
的新增命名空间 UI、工具审批流/插件卡（ToolsPage 现状保留，仅新增设置页入口）。

## 2. 现状核实（文件级，2026-09-04）

### 后端
| 事实 | 位置 | 结论 |
|---|---|---|
| 工具注册表 | `dsh-tool` `ToolRegistry`（@Component）：ApplicationContext 收集 `@Tool(name, description, requiresApproval, timeoutMs)` 的 `AgentTool` bean；`allTools()/toolNames()/toToolRefs()` | schema 源已存在；**无显示标题/摘要键元数据** |
| 工具 schema | `ToolSchema`(name, description, parameters{type,description,items}, required) → `inputSchema()`（OpenAI function-calling JSON Schema） | 参数概要可下发；无 enum/默认值字段（表单受限） |
| 设置服务 | `dsh-settings` `SettingsService`：namespace → `registerDefaults(Map<Object>)` + storage 覆盖分层解析；`get/set/all(namespace)` | **只有值无 schema**；主代码 `registerDefaults` **零调用**（agent 的 `settings.agent.*` 默认来自 `AgentLoopProperties` 兜底） |
| 设置端点 | `SettingsController` `GET/PUT /api/settings/{namespace}/{key}`、`GET /api/settings/{namespace}` | PUT 已就绪可复用；缺描述符下发 |
| API 依赖 | `dsh-api/pom.xml` 依赖 `dsh-agent`（compile）→ 传递依赖 `dsh-tool`、`dsh-settings`（dsh-agent 亦依赖） | **零 pom 改动**即可在 dsh-api 注入 `ToolRegistry`/`SettingsService` |
| agent 生效读取 | `AgentLoopService.effectiveTemperature/MaxSteps/MaxParallelToolCalls`：`settings.agent.*` > `AgentLoopProperties`(dsh.agent.* 默认) | 注册 defaults 后读取语义不变（原 null → properties；注册后返回注册默认） |

### 前端（dsh-web/src/main/webapp，Vue3 + Vite + Element Plus）
| 事实 | 位置 | 结论 |
|---|---|---|
| 硬编码标题表 | `MsgView.vue` `TOOL_TITLES`（8 工具：Read/Write/Edit/Bash/Search/Fetch/Code…） | **删除**，改元数据 |
| 硬编码摘要键表 | `MsgView.vue` `SUMMARY_KEYS`（11 工具：read_file/write_file/edit/glob/grep/web_fetch/web_search/subagent/send_message/skill/list_agents → 键数组） | **删除**，改元数据 |
| 工具行渲染链 | `template v-for v-html="rowHtml(m)"` → `genericToolRowHtml` → `toolTitle`/`toolArgSummary`（同步纯函数） | 改读响应式 toolMeta，模板依赖收集自动重渲 |
| 泛化兜底 | `toolArgSummary` 已有遍历参数排除长文本的启发（非工具专属） | **保留**（未命中元数据时用） |
| 视图切换 | `App.vue` `appState.view` + `isToolView` → `<ToolsPage v-if>`；Sidebar 菜单项设 view | 加 `view='settings'` + SettingsPage 平级挂载 |
| API 层 | `api.ts`（80+ 端点函数）；**无工具元数据/无 settings 函数** | 需新增 3 个函数 |
| 状态层 | `store.ts` `appState` reactive 单例 | 工具元数据表放 MsgView 局部 module 级 ref 即可（唯一消费方） |
| 构建/验证 | 脚本仅 `dev/build`（vite build）；tsconfig strict+noEmit 但 **无 .vue shim、无 vue-tsc** → 现无类型检查 gate | 验证 = vite build + 后端测试 + 可选浏览器 smoke；不引入新 devDep（离线保守） |

## 3. 决策（D1–D7）

### D1 工具元数据端点契约 — `GET /api/tools/meta`
返回（顺序=注册顺序，ToolRegistry.allTools() 投影）：
```json
[{ "name":"glob","displayTitle":"Search","summaryKeys":["pattern","path"],
   "description":"按 glob 模式查找文件。","inputSchema":{...},
   "requiresApproval":false,"timeoutMs":0 }]
```
- `displayTitle`/`summaryKeys` **可为 null/[]**（未声明 → 前端泛化兜底，见 D6）。
- `inputSchema` = 既有 `ToolSchema.inputSchema()`（与注入 LLM 的 function-calling 声明**同源同形**，
  天然 schema 单源，不会两份漂移）。
- 端点落在 dsh-api `ToolsMetaController`（注入 `ToolRegistry`，传递依赖即可）；registry 空/未装配时返回 `[]`。

### D2 Tool 注解/AgentTool 显示元数据扩展（零破坏）
- `Tool` 注解加：`String displayTitle() default ""; String[] summaryKeys() default {};`（既有实现零改动）。
- `AgentTool` 加 default 读取（`getClass().getAnnotation(Tool.class)`），无注解/空 → null/[]。
- 现有 100+ @Tool 工具**不逐一声明**；第一批显式补齐 MsgView 原硬编码涉及的
  read_file/write_file/edit/glob/grep/web_fetch/web_search/subagent/send_message/skill/list_agents/
  run_code/bash/workflow（title=现状值迁移、summaryKeys=现状键迁移），作为 schema 真值落库；
  其余工具不发摘要键 → 前端泛化兜底标题 'Tool call'/启发摘要。**前端不再有工具名专属分支数据。**

### D3 SettingsService 描述符机制
dsh-settings 新增（保持既有 API 兼容）：
```java
public record SettingDescriptor(
    String key, String type,          // string|number|integer|boolean|enum
    String label, String description,
    Object defaultValue,              // 注册期现值快照（properties/静态默认）
    List<String> options,             // type=enum
    Double min, Double max, Double step) // number/integer
```
- `SettingsService.registerDescriptor(namespace, SettingDescriptor)` → `Map<String, Map<String, SettingDescriptor>> descriptors`；
- `describe(namespace)` → LinkedHashMap 保序（与 all(namespace) 同序）；
- **键存在性防御**：PUT 未知键仍允许（现状语义不破坏，历史客户端/模型写入兼容）；
- `registerDefaults` 与 `registerDescriptor` 独立（默认值=值层、描述=显示层，均可单独注册）。

### D4 Settings 描述符端点 — `GET /api/settings/meta`
```json
[{ "namespace":"agent",
   "settings":[{ "key":"temperature","type":"number","label":"温度","description":"…",
                 "defaultValue":0.7,"min":0,"max":2,"step":0.1 }],
   "values":{"temperature":0.7,"max-steps":200} }]
```
- `values` = 现状 `settingsService.all(namespace)`（默认+覆盖合并视图）；
- 已注册描述符的 namespace 才出现（无描述符的 namespace 不暴露——动态表单只渲染有 schema 的项）；
- SettingsController 加路由（同 Controller，复用注入）。

### D5 agent 命名空间接入（首个真实 schema 消费）
新增 `dsh-agent` 装配类 `AgentSettingsConfig`（@Component，注入 AgentLoopProperties + SettingsService）：
- `registerDefaults("agent", {temperature: properties.temperature(), "max-steps": properties.maxSteps(),
  "max-parallel-tool-calls": properties.maxParallelToolCalls()})` —— 把属性默认正式收编为 settings 默认层；
- `registerDescriptor` × 3：temperature(number, min0 max2 step0.1)、max-steps(integer min1 max10000)、
  max-parallel-tool-calls(integer min1 max64)。
- 语义核对：`AgentLoopService.effectiveXxx` 现读 `settings.agent.*`（defaults 注册后返回属性值，与原
  properties 兜底**数值一致**）→ **行为零漂移**；且 all("agent") 首次有默认值可显示（修复现状空白）。

### D6 前端改造
- **api.ts**：`listToolMeta(): Promise<ToolMeta[]>`（GET /api/tools/meta）、
  `fetchSettingsMeta(): Promise<SettingsNamespace[]>`（GET /api/settings/meta）、
  `putSetting(namespace,key,value)`（PUT /api/settings/{namespace}/{key}）；
  类型：`ToolMeta{name, displayTitle|null, summaryKeys[], description, inputSchema, requiresApproval, timeoutMs}`、
  `SettingDescriptor{key,type,label,description,defaultValue,options?,min?,max?,step?}`、
  `SettingsNamespace{namespace, settings[], values{}}`。
- **MsgView.vue**：
  - 删除 `TOOL_TITLES`/`SUMMARY_KEYS` 常量；
  - module 级 `const toolMeta = shallowRef<Map<string, ToolMeta> | null>(null)` +
    `loadToolMeta()`（api.listToolMeta → 建 Map；失败静默置 null 不阻塞）；
  - `toolTitle(name)` = meta?.get(name)?.displayTitle ?? 'Tool call'；
  - `toolArgSummary(name, content)` = meta?.get(name)?.summaryKeys 按序取参 → **原启发兜底保留**；
  - 模板 `rowHtml` 内读 toolMeta → 依赖收集：App onMounted/首帧 fire-and-forget `loadToolMeta()`，
    fetch 返回后自动重渲既有消息（无需版本号 hack）；
  - 未命中/未加载 = 标题 'Tool call' + 启发摘要（现兜底语义，无工具专属数据）。
- **SchemaForm.vue（新，通用组件）**：props `{settings: SettingDescriptor[], values: Record<string,unknown>, namespace}`；
  按 type 渲染 Element Plus：number/integer → `el-input-number`(min/max/step)、boolean → `el-switch`、
  enum → `el-select`(options)、string → `el-input`；表单底栏「保存本项」逐键 PUT（复用既有端点），
  label 用 `label ?? key`，tooltip 用 description；宽字段可 textarea（预留，不在首版范围）。
- **SettingsPage.vue（新）**：挂载拉 `fetchSettingsMeta()`；每 namespace 一组 SchemaForm 卡片；
  保存成功 pushNotice + 局部更新 values；namespace 空 → 空态提示。
- **App.vue / Sidebar.vue**：Sidebar 菜单加「设置」（`view='settings'`）；App 模板
  `<SettingsPage v-if="appState.view==='settings'">`（与 ToolsPage 平级 + @back 回 chat）。
- **不改**：ToolsPage（保持 skill 视角；工具全量列表已由 D1 端点可查，本轮不新增其 UI 依赖）。

### D7 验证口径与红线
1. 后端：SettingsService 描述符单测（注册/describe/all 合并/未知键兼容）、SettingsController meta 路由测试
   （可复用既有测试风格）、ToolsMeta 聚合测试（ToolRegistry 投影：displayTitle/summaryKeys 注解默认空、
   inputSchema 同形）、AgentSettingsConfig 装配测试（effectiveXxx 与 properties 兜底数值一致）；
   **`mvn verify` 全绿不回退**（520 baseline + 新增）。
2. 前端：`vite build` 通过（现状唯一 gate）；**grep 校验**：`MsgView.vue` 无 `TOOL_TITLES|SUMMARY_KEYS`
   工具名→映射残留（schema 单源达成证明）。
3. 浏览器 smoke（可选加分，用既有 browser 工具链）：后端起服务 → 页面设置项可渲染 → 修改温度保存
   → PUT 生效回显。
4. 文档回写：design-frontend-replication.md 内核②标注落地 ✅（工具元数据 + settings 表单），
   本文件 §6 落地记录；design-upstream-replication.md 里程碑远期项「schema 驱动 UI」✅。

## 4. 落点拆解（批准后转正式 DAG）

| 步 | 内容 |
|---|---|
| sui-1 | dsh-tool：Tool 注解 + AgentTool 加 displayTitle/summaryKeys（default 读取）；首批工具显式补齐元数据（原 MsgView 涉及 13 工具 title/keys 迁移） |
| sui-2 | dsh-api：ToolsMetaController（GET /api/tools/meta 投影）+ 测试 |
| sui-3 | dsh-settings：SettingDescriptor + registerDescriptor/describe + 单测；SettingsController + GET /api/settings/meta + 测试 |
| sui-4 | dsh-agent：AgentSettingsConfig（defaults+描述符注册）+ 装配/数值一致测试 |
| sui-5 | 前端：api.ts 三函数与类型；MsgView 硬编码删除 + toolMeta 渲染 + 启动加载 |
| sui-6 | 前端：SchemaForm.vue + SettingsPage.vue + Sidebar/App 视图接入 |
| sui-7 | 收口：mvn verify 全绿 + vite build + grep 单源校验 + 文档回写 + 分阶段 git 提交 |

## 5. 风险与回退
- **MsgView 渲染回归**：改造保留现有兜底语义与渲染 HTML 结构，title/summary 仅取值来源变化；
  行级结构（tool-line/tool-path/tool-summary/tool-body）不动 → 视觉零变化，纯数据源切换。
- **后端有效值漂移**：defaults 注册值与 properties 同源同值；若运行期改 dsh.agent.* 需重启——与现状
  effectiveXxx 的 properties 读取时机一致（现状亦重启生效），无新语义。
- **schema 与工具执行分离**：displayTitle/summaryKeys 仅显示层，不影响 LLM 调用声明；
  inputSchema 仍与 function-calling 同源。摘要键若与实际参数不符 → 启发兜底兜住，无崩溃。
- **描述符缺失 namespace**：不暴露即可，前端空态，不阻碍其它 namespace 后续接入。
- **回退**：D2/D3 注解与 API 全 default/新增，无既有破坏；前端改动纯增量 + MsgView 单文件可控，
  异常时 revert MsgView 即可回到硬编码（schema 端点仍可留作无副作用增量）。

## 6. 落地记录（2026-09-04，plan-f2e88817 completed）

- **SUI-1** 工具元数据扩展：`Tool` 注解 + `AgentTool` 加 `displayTitle`/`summaryKeys`（default 零破坏）；
  首批 11 工具显式补齐（read_file/write_file/glob/grep/web_fetch/web_search/subagent/send_message/
  skill/run_code/bash；`edit` 无 @Tool 实现 → 前端删除硬编码后启发兜底覆盖）→ 编译通过。
- **SUI-2** `ToolsMetaController` `GET /api/tools/meta`（ToolRegistry 投影：displayTitle null 化 +
  summaryKeys 空数组 + inputSchema 与 function-calling 同源同形）→ `ToolsMetaControllerTest` 3 用例。
- **SUI-3** `SettingDescriptor`（dsh-settings）+ `SettingsService.registerDescriptor/describe/
  describedNamespaces`（注册序保序） + `SettingsController` `GET /api/settings/meta`
  （描述符 + `all()` 合并视图，仅已注册描述符 namespace 暴露）→ `SettingDescriptorTest` 3 +
  `SettingsMetaControllerTest` 2 用例。
- **SUI-4** `AgentSettingsConfig`（dsh-agent @Component）：agent 三键 defaults=AgentLoopProperties 现值 +
  3 描述符（temperature/min/max/step、max-steps、max-parallel-tool-calls）→ `AgentSettingsConfigTest`
  3 用例；effectiveXxx 数值零漂移（defaults 与 properties 同源同值）；dsh-agent 全模块回归通过。
- **SUI-5** 前端 MsgView schema 渲染：删 `TOOL_TITLES`/`SUMMARY_KEYS` 硬编码表 → module 级
  `shallowRef<Map<string, ToolMeta>>` + `loadToolMeta()`（onMounted fire-and-forget，失败静默降级）+
  `toolTitle`/`toolArgSummary` 查元数据（未命中 'Tool call'/启发兜底保留）—— grep 校验零残留；
  api.ts 加 `listToolMeta`。
- **SUI-6** `SchemaForm.vue`（按 descriptor type 渲染 el-switch/el-input-number/el-select/el-input，
  逐键 PUT + notice）+ `SettingsPage.vue`（/api/settings/meta → namespace 卡片 + 空态）；
  Sidebar「设置」入口 + App.vue `view==='settings'` 独立页挂载。
- **SUI-7** 验证收口：**mvn verify 全绿（520 + 11 新增 = 531 tests / 0 failures / 0 errors /
  3 skipped）**；`vite build` 通过；文档三处回写（本文件 + design-frontend-replication.md 内核② ✅ +
  design-upstream-replication.md M10 ✅ + 外围表状态）。

红线达成：
1. ✅ `mvn verify` 全绿不回退（531 tests，520 baseline + 11 新增）
2. ✅ 工具 schema 单源：MsgView 无 `TOOL_TITLES|SUMMARY_KEYS` 工具名→映射残留（grep 校验）；
   inputSchema 与 LLM function-calling 声明同源（同一 ToolSchema.inputSchema()）
3. ✅ 设置 schema 单源：SettingsService 描述符注册 + `/api/settings/meta` 下发；PUT 走既有端点
   复用（零新写通道）
4. ✅ 数值零漂移：AgentSettingsConfig defaults=properties 现值（AgentSettingsConfigTest 断言 +
   AgentLoopSettingsTest 回归）
5. ✅ 默认值零破坏：Tool 注解新增属性全 default（11 工具显式补齐，其余 90+ 工具原样编译）
6. ✅ 前端构建 gate：vite build 通过（现状唯一 gate，无新增 devDep）

git 提交：见上（后端 dsh-tool/dsh-settings/dsh-agent/dsh-api 与前端 dsh-web 分主题提交）。
