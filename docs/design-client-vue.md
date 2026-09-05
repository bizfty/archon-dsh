# 实现官方 client：dsh-web（Vue）改造路线设计

> 基线：archon-dsh HEAD `b742b2f`（2026-09-04，M0–M10 + P2/P3 settings 已落地，571 tests 全绿）
> 上游参考：external/deepseek `76fda72` `packages/client`（40+ 包：cordis 插件体系 + React 渲染器 + schemastery）
> 前置文档：[design-frontend-replication.md](design-frontend-replication.md)（四路径评估，§4 A/B/C/D）、
> [design-schema-ui.md](design-schema-ui.md)（M10 内核② schema 单源）、
> [design-settings-p3-path-cas.md](design-settings-p3-path-cas.md)（settings 官方语义 P3）
> 缘起：用户 2026-09-04 追问「要实现官方的 client，当前 dsh-web vue 怎么改」——本设计回答此问题（文件级核实、三档路线、默认推进 C→B）。

## 1. 问题界定

archon-dsh 已实现官方 schema 驱动的**等价功能**（工具元数据 + Settings 动态表单 + path CAS/redact/presence），
但官方 `packages/client`（schemastery envelope 传输、schema-form 模型层、cordis 插件运行时、React ui-* 消费页）
**未包级引入**。本设计给出：要在 archon-dsh 内「实现官方 client」，`dsh-web`
（Vue3 + Vite + Element Plus，`src/main/webapp` 独立 npm 工程，27 源文件 ≈ 9.1k 行）怎么改。

## 2. 官方 client 分层事实（核实 76fda72，文件级）

| 层 | 官方包 | 技术形态 | 能否进 Vue |
|---|---|---|---|
| 类型系统 | `vendor/schemastery` | 纯 JS，无 React/cordis | ✅ 原样收编（file/npm 依赖） |
| schema 模型层 | `client/schema-form` | 纯 TS，仅依赖 schemastery；**仓库内无 package.json/src，只剩 `lib/` 产物（含 d.ts）** | ✅ 原样收编产物 |
| 状态基元 | `client/store` | React-free 快照/订阅（Immer 后备、stable snapshot） | ✅ 语义等价（Vue reactive/composable） |
| 容器/运行时 | `@deepseek-ai/cordis` + `client/runtime` | cordis 插件容器 | ⚠️ 需剥离或语义复刻，Vue 内不引 cordis |
| 槽系统 | `client/ui-slots` + `client/ui-renderer` | ui-slots 纯 TS 类型契约（React-free）；引擎在 ui-renderer（React） | ⚠️ 类型契约可学，引擎换 Vue 自研 |
| 设置 scope 契约 | `client/ui-settings`（schema.ts / settings-contract.ts / settings-scope.ts） | TS + cordis `Service`（settingsSchema） | ⚠️ 契约语义可镜像（scope 快照），cordis Service 剥掉 |
| UI 页面 | `ui-layout` / `ui-sidebar` / `ui-settings-general` / `ui-chat` 等 40+ | **cordis 插件 + React `.tsx`**（peer 依赖 cordis；primitives/web-react 依赖 react） | ❌ Vue 无法直接跑 → 只能 A（引真身）或复刻子集 |
| 成品入口 | `client/web` + `web-react` + `apps/web` | React18 + cordis 装配（apps/web main.ts 仅 4 行 → `new AppWebEntry(el).run()`） | ❌ 同上 |

关键结论：
- 官方 client 里 **唯一能在 Vue 内「原样复用」的是 schemastery 与 schema-form（纯逻辑层）**；官方自己也没有通用渲染器——`ui-settings-general`
  的 `SettingsRoot/GeneralSection/chrome.tsx` 是手写 React 布局，页面自绘控件、模型层共用（与 archon-dsh SchemaForm/SchemaField 自绘思路同构）。
- `ui-*` 全体挂在 cordis 插件体系上，物理上不属于 Vue 技术栈 → "在 Vue 里实现官方 client"只能取**架构内核语义复刻** + **纯逻辑包原样引入**；
  要像素级官方 UX 只有一条路：**真身引入（后端改官方 host 契约）**，与 Vue 并存或弃用。

## 3. 三条路线（成本-风险-收益）

| 维度 | A. 真身引入 | B. 架构内核复刻 | C. 纯逻辑层官方对齐（务实档） |
|---|---|---|---|
| 目标 | 官方 React client（apps/web 整树）原样伺服，UX 100% 官方 | Vue 栈拥有官方 client 分层内核：L1 事件投影 store + L2 官方 schema 语义 + L3 插槽化宿主 | wire/模型层官方对齐：npm 引 schemastery + schema-form；Vue 组件自绘保留 |
| 后端改动 | **≈ 重写 dsh-api 为官方 host 契约**：connection RPC bridge、api-path、rpc-schema、browser-auth、generation 代次重放、events.mux 序列化、settings 官方 envelope | 小：meta 端点加官方 envelope 兼容下发 | 小：meta/settings 端点 envelope 双发（兼容优先） |
| 前端改动 | dsh-web Vue 弃用/并存（iframe 或独立路由隔离） | store.ts→事件投影；App.vue 拆槽；SchemaForm 换官方模型 | SchemaForm/SettingsPage 内部换官方模型层驱动；api.ts 类型扩 |
| 成本 | 极高（≈ 重写 dsh-api + 40+ 官方包锁版本追迭代） | 中-高（估 2–4 周专注） | 低-中（1 周内） |
| 风险 | 高（上游 web 快迭代，30+ e2e 追版本；Java 无 cordis server 生态） | 低-中 | 低 |
| 既有衔接 | 与 M10/P3 wire 不兼容，需平移 | 承接 M10/P3 增量演进 | 直接叠加于 M10/P3 |
| 何时选 | UX 必须像素级官方且接受协议绑定（评估 §6 明确不推荐） | 长期架构目标 | **默认第一步** |

## 4. 推荐：C → B 渐进（A 留作判据门）

- **C 先行**：官方 client 中唯有纯逻辑层可在 Vue 内原样落地；C 把「schema 单一事实源」从 archon 自研（SettingDescriptor/手写校验）
  升级为**官方字节语义**（schemastery envelope 传输 + schema-form 校验/编辑），每步独立可合并、wire 兼容不破坏 P3。
- **B 承接**：在 C 的官方模型层之上做 L1（事件投影 store，对齐 client/store 快照语义）与 L3（App 插槽化，对齐 ui-slots 契约的轻量 Vue 版），
  前端获得官方 client 的架构内核，后端几乎不动。
- **A 判据门**：除非要求「像素级官方 UX + 接受官方协议绑定 + Java 侧放弃维护 cordis 生态」，否则维持评估文档「A 不推荐」。

## 5. C 档改造明细（文件级）

### 5.1 依赖收编（离线可用优先）
- 将 `external/deepseek/vendor/schemastery`（源码）与 `packages/client/schema-form/lib/`（产物 + d.ts）收编为
  `dsh-web/src/main/webapp/vendor/@deepseek-ai/{schemastery, dsh-client-schema-form}`；
  `webapp/package.json` `dependencies` 加 `"file:vendor/..."`（registry 可达，npm i 正常；离线也可本地装）。
- schema-form 仅有 schemastery 依赖、无 React/cordis → 直接进 vite bundle；**以 d.ts 为准**（仓库无 src，改进需自维护或提上游）。

### 5.2 wire 升级（后端，兼容优先）
- `SettingsController.GET /api/settings/meta`：在现 SettingDescriptor 结构旁**增加官方 envelope 字段**（`schema.toJSON()` 形状，
  即 schemastery 节点树），双发或 `Accept` 协商；不破坏 P3 前端读取。
- `ToolsMetaController.GET /api/tools/meta`：核对元素键与官方 tool schema 键映射（`description`/`inputSchema` 已具，补官方键别名），无结构性改动。
- 红线：AgentLoop 读取零漂移；既有前端零回归；wire 无 verbatim secret 明文路径。

### 5.3 前端改造
- `SchemaForm.vue`/`SchemaField.vue`（171/287 行）：**渲染自绘保留**；草稿默认树与校验改由官方模型层驱动：
  `rehydrateSchema(meta.schema)` → `nodeAtPath` 取字段节点 → `validateDraft` 出校验文案；
  草稿编辑 immutable `setPath`/`deletePath`（替换 `schemaDefaults.ts` 手写深合并/兜底逻辑）。
- `schemaDefaults.ts`（62 行）：降级为官方 defaultValue 语义的兼容薄层或删除。
- `api.ts`（1140 行）：meta 响应类型扩展 envelope；新增客户端 SettingsScope 镜像（status/value/base/user/revision/writable/mode）——
  P3 已具 user/revision/presence/secret，补 `base` 分层展示与 `mode`（host/memory）即可。
- 验证：`vite build` + tsconfig strict（现状无 .vue shim / 无 vue-tsc gate，不强引 devDep）；后端 `mvn verify`；
  **schema 往返 golden 单测**（schemastery `toJSON → rehydrate` 往返 + `validateDraft` 拒绝非法草稿）。

### 5.4 语义全集与克制
官方 schemastery 类型全集含 dict/tuple/union/intersection/transform 等。本期不追求穷举：
- 已支持 7 类（string/number/integer/boolean/enum/object/array）声明式渲染；
- 高级/未知节点降级**只读展示 + 原文编辑 fallback**（官方自身页面亦手写控件，模型层共用）。

## 6. B 档改造明细（承接 C）

### 6.1 L1 事件投影 store（对齐 client/store 快照语义）
- 现状：`ws.ts`（153 行）已发 `{type,sessionId,seq,event}` 帧 + SSE 回退（注释对齐 ConnectionController/events.mux）；
  `store.ts`（436 行）Vue reactive 单例、拉取后手工 set，**非投影**。
- 演进：帧 → 投影 reducer（seq 单调去重 / 乱序缓冲 / 断线增量 resync，对齐官方 generation 语义轻量版）；
  对外 `getSnapshot()/subscribe` 快照接口 + Vue reactive 适配（ref/computed 包装），对齐官方
  "stable snapshot until next change"。
- 收益：实时状态正确、重连不闪跳；解锁后端 M3–M9（常驻 agent/取消及时化）的前端实时化。

### 6.2 L3 插槽化宿主（对齐 ui-slots 契约的轻量 Vue 版）
- 现状：`App.vue`（943 行）单体编排。
- 演进：拆 layout 槽（sidebar / main / composer / settings / plan / goal）+ module 级注册表
  `register({slot,name,component})` + `<Slot name="…">` 渲染；TS 泛型收窄 props
  （轻量对齐官方四 share：owner / session / store / business）。
- 克制：**不引 cordis 依赖注入、不搬 React 渲染器**；注册表组合式注入即可。

### 6.3 Settings scope 全语义镜像
前端按 `settings-contract.ts` 的 `SettingsScopeSnapshot` 形状（status/value/base/user/revision/writable/mode）
镜像每命名空间同步态；P3 已覆盖 user/revision/presence/secret write-only → 补 base 分层与 memory 模式降级。

## 7. A 档真身引入：判据与最低路径

判据（全部满足才选 A）：① UX 需像素级官方；② 接受官方协议绑定并追上游迭代；③ 接受 dsh-api 大改。
最低路径：dsh-api 实现官方 host 契约端点清单（connection bridge / api-path / rpc-schema / browser-auth /
generation 重放 / events.mux 序列化 / settings envelope wire）→ 静态伺服官方 apps/web 产物 →
Vue dsh-web 以 iframe/路由前缀并存。结论维持：**Java 侧不推荐 A**（评估 §4/§6 复核成立）。

## 8. 阶段划分与验收（每阶段独立可合并）

| 阶段 | 内容 | 验收 |
|---|---|---|
| P0（C 前半） | vendor 收编 schemastery + schema-form；meta 端点 envelope 双发 | `vite build` ✅、`mvn verify` 571/0 ✅、官方包往返 golden 单测 ✅ |
| P1（C 后半） | SchemaForm/SchemaField 换官方模型驱动（validateDraft/setPath/deletePath）；base 展示 | 既有 settings 保存零回归；secret 仍 write-only；AgentLoop 读取零漂移 |
| P2（B/L1） | store.ts → 事件投影（seq 去重/增量 resync） | 断线重连增量 resync 单测；实时状态正确 |
| P3（B/L3） | App.vue 拆槽 + 注册表 | 页面结构等价；新增「槽」无需改 App.vue |

全程红线：`mvn verify` 不回退 · `vite build` ✅ · AgentLoop 读取零漂移 · wire 无 verbatim secret · 既有 REST 端点不破坏（envelope 为增强非替换）。

## 9. 风险与不做

- 风险：官方包迭代追版本 → 收编 vendor 快照即固定版本（可控）；schema-form 仅 lib 无 src → 以 d.ts 为准，需自维护时提上游。
- 不做：cordis 运行时引入 · React ui-* 包搬运 · 官方 wire 全协议替换主契约（archon REST 保留为主，envelope 为消费增强）。

## 10. 实施状态与冒烟记录（2026-09-05）

**B 档 L1（store.ts 事件投影）已于 C 档 P0+P1 之上追加落地**。路线维持 Vue3 + Element Plus（A2 伺服停用）。

### 已落地变更
- **P0 vendor 收编**：`dsh-web/src/main/webapp/vendor/@deepseek-ai/{schemastery, dsh-client-schema-form}` + `cosmokit`（纯逻辑层，
  无 React/cordis），`package.json` 未引 npm registry（vendor 即源），`vite.config.ts` alias + `tsconfig.json` paths 指向 vendor。
- **P0 wire 双发**：`SchemasteryEnvelope`（dsh-settings）将 `SettingDescriptor` 树翻译为官方 schemastery envelope
  （`{uid, refs}` 引用图；root=0，children 从 1；integer 语义经 `meta.dshType=integer` 标记）；
  `SettingsController.GET /api/settings/meta` 在 P3 REST 数组旁并发现场 envelope（`schema` 字段），P3 读取零回归。
- **P1 官方模型层驱动**：`api.ts` 增 envelope 类型与 `view.schema`；`schemaFieldModel.ts` 归一官方节点树 → `RenderField`
  （保序/label/role/visibleWhen/union→enum/dshType 精度）；`SchemaForm.vue` 以 `rehydrateSchema` 重建官方根 → 渲染
  自绘保留（EP 控件）；保存前 `validateDraft` 官方校验，草稿 diff 走既有 ops（set/unset + secret write-only path op），
  secret 顶层键不入草稿（防默认值误写）；`SchemaField.vue` 高级/未知节点只读降级；`schemaDefaults.ts` 降为兼容薄层。
- **B/L1 事件投影有序交付层**：`webapp/src/eventLog.ts`（纯 TS，node 单测无 DOM）以每会话 `seq` 单调去重：
  首帧（无基线）即建水位直接 applied；缺口帧滞留缓冲，前序到达后链式排空（顺序正确）；已应用/缓冲/低水位重复帧判 duplicate；
  `reset/resetAll` 清状态（断线重连重设新基线）、`setWatermark` 预留后端增量端点、缺口超上限保守 self-reset（靠全量重拉兜底）、
  非正整数 seq 防御性丢弃。`App.vue` script 接线：`onWsFrame` 帧经 EventLog 按序应用（乱序被缓冲直至排空），`onWsState`
  断线 `resetAll`（重连后首帧即新水位），`connect/resyncSession` 收敛重复的全量拉取段；template/style 零改动。
  新增 `vitest` devDependency + `npm test`（10 用例含乱序/去重/跨会话隔离/缺口补齐/防御）。
- 运行时 driver 标记：SchemaForm `onMounted` console `[dsh-c] ns=… driver=official|descriptor`（envelope 缺失时回退描述符直通）。

### 门禁
- `mvn verify`：BUILD SUCCESS，622 tests / 0 fail（≥ 571 基线，不回退）。
- `vite build`：✅（~36s，无 TS gate，按 §5.3 不强引 vue-tsc）。
- `npm test`（vitest，webapp）：10/10 绿（eventLog 有序交付层，node 环境纯逻辑）。

### 浏览器冒烟（boot jar = 最新 build，Chrome）
- `/api/settings/meta`：agent ns 返回 P3 数组 + `schema` envelope（uid=0，refs=4；root object dict 保序含 temperature /
  max-steps / max-parallel-tool-calls，叶子 number + `dshType=integer` + step/max 语义）。agent 实际 settings 3 叶一致。
- 设置页：三字段 el-input-number 渲染（温度 0.7/step0.1/min0/max2；步数 200/max10000；并行 10/max64），step/min/max
  元数据来自 envelope meta；温度 0.7→0.8 编辑 → 保存（validateDraft 通过 → ops diff set temperature → "已保存 ✓"）
  → 后端 GET 回读 `temperature:0.8` → 刷新 UI 0.8/200/10 一致（reload → syncDraft）。
- 会话页：工作区/会话树/设置入口渲染正常（未触达 B 档改动面）。

### 遗留与假设
- vendor 为快照即固定版本（schema-form 仅 d.ts 无 src，改进需自维护，见 §9）。
- meta 双发为增强非替换：旧前端读 P3 数组不受影响；新前端 envelope 缺失自动回退描述符。
- AgentLoop 读取零漂移：envelope 仅消费展示层，写路径仍为既有 ops + revision CAS。

## 11. 下一步

B/L1（事件投影）已落地；B/L3（App.vue 拆槽 + 注册表）与 A 档真身判据复核留待后续评估。

