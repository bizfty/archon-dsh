# B/L3 拆槽设计：App.vue 插槽化宿主 + module 注册表（2026-09-05）

> 关联 design-client-vue.md §6.2（L3 插槽化宿主）与 §8 P3 档（App.vue 拆槽 + 注册表）。
> 验收判据：**页面结构等价**；新增「槽/视图」无需改 App.vue。
> 克制红线：不引 cordis 依赖注入、不搬 React 渲染器；注册表组合式注入。
> **已落地（2026-09-05）**：经用户确认走「全量 P3」路径，超本文档 §7 保守版——除组装来源注册化外，
> 跨模块业务动作一并下沉为独立模块（floating/planMode/sessionActs/turn），各视图自包含；App.vue 瘦身为
> 薄壳 + MainShell + 注册表。§7 的「动作留 App.vue 经 emit」为初始保守方案，实际以自包含化为准。
> 门禁：mvn verify BUILD SUCCESS / vite build ✅ / npm test 16 绿 / 浏览器冒烟（view 切换 + 独立页 + 发消息回合）通过。

## 1. 现状盘点

App.vue 现 954 行，是「flex 骨架 + 内联视图编排」的单体。结构三层：

```
app-shell (flex: sidebar + 右侧)
├─ <aside.sidebar>           → <Sidebar>（子组件，已拆）
├─ 分支 A  isToolView        → <ToolsPage>（独立页：mcp/skills/jobs/expert/coder/self 聚合壳，已拆）
├─ 分支 B  view==='settings' → <SettingsPage>（独立页，已拆）
└─ 分支 C  else → <main>
   ├─ <header>  breadcrumb / right(conn·theme·workspace-chip·删除)   ← 内联全局壳
   ├─ subagents-bar                                                  ← 内联（chat 特供）
   ├─ <nav.tabs>  💬对话 · 📋计划 · 🎯目标 · 🛤轨迹                    ← 内联硬编码 4 tab
   └─ <div.body>  v-show 分发 4 视图                                  ← 内联 if/else
      ├─ chat        → <MsgView>（v-show）
      ├─ plan        → 内联 plan 特有 UI(toolbar/alert/文本collapse) + <PlanView>
      ├─ goal        → <GoalView>
      └─ trajectory  → <TrajectoryView>
   └─ <div.composer-dock> → <Composer>（常驻 dock）
├─ 全局浮层：子代理 <el-drawer>、<DirectoryBrowser>
```

已具备的组合式底座：所有组件 `import { appState } from '../store'` 共享 Vue reactive 单例；
WS 下行由 App.vue 持有 `WsClient` 并调 `handleEvent` → 写 appState。子组件已从 App.vue 拆出。

**未拆/难以独立**的部分（本设计要解决的）：
1. `view` 分发是硬编码 if/else（ToolsPage / SettingsPage / main 三支 + body 内 4 视图 v-show）。
2. nav tabs 4 个硬编码与 body 视图一一绑定。
3. plan 特有 UI（toolbar/alert/文本 textarea）仍内联在 App.vue，且依赖 App.vue 的
   `planBusy/planText/togglePlanModeBackend/doSubmitPlan`。
4. 大量业务 handler（send/stop/newSession/openSession/resyncSession/doGoalUpdate/continuePlan…）
   定义在 App.vue script，子组件通过 `@emit` 回调反向调用——一旦要新增一个「同构」页面模块，
   这些动作无法被模块内组合式复用（除非复制 App.vue 逻辑）。

## 2. 设计目标与拆槽边界

**目标**：把「view → 页面模块」与「body 视图 + tab」的对应关系从硬编码改为**注册表装配**，
使新增一个页面/视图 = 一次 `register({...})` + 加一个 tab 项（数据），不改 App.vue 的
template 骨架与分支结构；同时把 plan 等仍内联的视图特有 UI 抽为独立注册组件。

**App.vue 保留（shell 职责，不拆）**：
- flex 骨架：aside.sidebar + 右侧主区 + composer-dock + 全局浮层（子代理 drawer / DirectoryBrowser）
- header 全局工具区：breadcrumb / connection 徽标 / theme 皮肤 / workspace-chip / 删除按钮
- subagents-bar（chat 特供，属会话 header 语义）
- WS 生命周期与事件接线（WsClient + onWsFrame/onWsState/handleEvent → 写 appState）
- 全局业务动作（send/stop/resyncSession/newSession/openSession 等）——它们驱动 WS/HTTP，
  属「宿主级」能力，集中一处避免多实例开 WS 副作用。

**注册化（进 registry）**：
- `view:*` 页面模块：chat-main / plan / goal / trajectory / settings / tools（每工具页）
- 各视图模块的自描述（tab 标签/图标/是否独立页/是否需 composer）
- plan 特有 UI 抽为独立 `<PlanModePanel>`（toolbar/alert/文本 collapse），body 内 plan 槽组合之

关键：**拆分后视觉/DOM 结构必须与现状等价**（P3 判据）。做法：注册表只改变「组装来源」，
不改变「最终渲染的组件树与 class/布局」。App.vue 仍负责渲染 sidebar/menu/composer/全局壳，
仅把「中间内容区按 view 选哪个模块」交给注册表查表。

## 3. 注册表 API（registry.ts，纯逻辑可单测）

```ts
// registry.ts —— module 级注册表。纯 TS：无 Vue 依赖亦可单测（component 字段用抽象占位）。
export interface ViewModule {
  key: string;            // 视图键（= appState.view 取值；settings/tools 亦同）
  component: unknown;     // 页面根组件（运行时由 Slot 用 <component :is> 渲染）
  label?: string;         // 若为主区 tab，给 tab 标签（如 chat='💬 对话'）
  tab?: boolean;          // 是否出现在 main 的 nav.tabs（chat/plan/goal/trajectory=true）
  shell: 'main' | 'standalone'; // main=复用 tabs+composer 的会话壳；standalone=独立页(settings/tools)
}
export interface Registry { register(m: ViewModule): void; unregister(key: string): void; get(key: string): ViewModule | undefined; keys(): string[]; clear(): void; }
export function createRegistry(): Registry;
export const viewRegistry: Registry;   // 应用级单例（组合式注入）
```

- `register` 覆盖语义：同 key 后注册覆盖先注册（模块可替换/重载）。
- 纯 TS，component 用 `Component`（vue 运行期类型）；单测用假 component 对象断言
  register/get/覆盖/clear 顺序与覆盖行为。
- **新增视图不改 App.vue**：新页面 `import { viewRegistry }` + `viewRegistry.register({...})`
  （在 main.ts 或该页目录的 index 里 side-effect 注册），App.vue 只认 `viewRegistry.get(view)`。

## 4. <Slot> 渲染组件（Slot.vue）

一个轻量渲染器，按槽名 + 当前选中键查表：

```vue
<template>
  <component :is="entry?.component" v-if="entry" v-bind="$attrs" />
</template>
<script setup lang="ts">
// Slot.vue —— 按注册表装配的插槽宿主渲染器。
// props.registry  注册表实例（默认应用级 viewRegistry）
// props.active   当前选中键（= appState.view）
// 组合式注入：组件本身不背状态，渲染目标由调用方（App.vue 或父壳）传 active。
</script>
```

槽位划分（对应 §6.2 的 layout 槽语义，映射到本项目实际）：
| 槽 | 内容 | 渲染组件来源 |
|---|---|---|
| `page:main` | 右侧会话壳（header+subagents+tabs+body+composer） | App.vue 壳（保留） |
| `body:<view>` | main.body 内当前视图 | `viewRegistry.get(view)` 的 component |
| `page:standalone` | settings/tools 独立页 | `viewRegistry.get(view)` 的 component |
| `nav:tabs` | body 顶部 tab 栏数据 | `viewRegistry` 中 `tab:true` 条的 label 数组 |

## 5. view 分发重构（App.vue 改动）

把第 1 节「分支 A/B/C + body 内 v-show」重构为查注册表：

```vue
<!-- 独立页（standalone）：settings / 各工具页 -->
<component v-if="viewEntry && viewEntry.shell==='standalone'" :is="viewEntry.component" @back="goChat" />
<!-- 会话壳（main） -->
<main v-else class="main">
  <header>…（保留全局壳）…</header>
  <SubagentsBar v-if="chatViewHasSubagents" …/>   <!-- 逻辑同现状：仅 chat 显示 -->
  <nav v-if="mainTabs.length">…遍历 mainTabs 渲染 tab…</nav>
  <div class="body">
    <component :is="viewEntry.component" v-if="viewEntry" … />
  </div>
  <Composer … />   <!-- composer 常驻，属 main 壳 -->
</main>
```

- `viewEntry = viewRegistry.get(appState.view)`。
- `mainTabs = viewRegistry 中 shell==='main' && tab===true 的模块`（对话/计划/目标/轨迹）。
- 点击 tab → `appState.view = key`（沿用现状 onSelectTool 语义）。
- tab 激活态 = `appState.view === key`。

**行为等价细节**：
- 现状 body 中 chat/plan/goal/trajectory 是**同时挂载 v-show 切换**（保留各视图本地状态）。
  注册表版用 `<component :is>` 会卸载/重建 → **状态丢失**。为保等价，body 内对 4 个 main 视图
  仍用「多 `<component>` + v-show」还是「单 `<component>` 动态换」需决策：
  - 若保 v-show：需渲染**所有** main 模块并 v-show 当前 → 注册表需能列出 shell==='main' 全部，
    body 内 `v-for` 渲染全部 + v-show 当前。**取此方案**（严格等价，各视图本地状态/滚动保留）。
  - 单 component 动态换：状态会丢（MsgView 滚动、plan textarea、trajectory 等），不等价。弃。
- plan 视图组合：plan 模块 = `<PlanModePanel>`（App.vue 内联 UI 抽出）+ `<PlanView>`，
  注册为 `shell:'main'` 的 plan 模块组件，body 内 v-show 渲染。
- trajectory/goal 加载副作用（switchTrajectory 里的 getTrajectory 拉取）由各模块自身 onMounted
  触发（v-show 首次挂载触发一次，切走再切回不重拉 → 与现状单次拉取等价）。

## 6. plan 特有 UI 抽组件（PlanModePanel.vue）

把 App.vue 内联的 plan toolbar/alert/文本 collapse 抽为独立组件，供 plan 模块组合：

```vue
<!-- PlanModePanel.vue —— 计划模式控制条 + 文本计划编辑（对齐官方 plan-mode） -->
<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { appState } from '../store';
import { getPlanMode, enterPlanMode, exitPlanMode, submitPlanMode } from '../api';
// 组合式注入：读 appState.planMode；本地 planBusy/planText 移入本组件（原在 App.vue）
// 原 togglePlanModeBackend/doSubmitPlan 移入本组件（appState.planMode 联动后端）
</script>
<template>
  <!-- 与现状 App.vue 内联完全一致的 DOM/class：plan-toolbar / el-alert / 文本 collapse -->
</template>
```
- 打开会话时同步后端计划状态（refreshPlan 原在 App.vue）→ 移入 PlanModePanel onMounted / 或经
  store 增加 `planText/planBusy` 由宿主打开会话时刷新。决策：plan 状态本就 per-session，
  放 store（planText/planBusy 进 appState 或独立 module ref）最贴合现状（App.vue 打开会话时
  refreshPlan 已写 planText）。**保留 planText/planBusy 在宿主**（App.vue 持有），
  PlanModePanel 通过 props/emit 与宿主协作，避免大改 store——见 §7 权衡。

## 7. 业务动作归属（对齐官方四 share 轻量版）

官方 ui-slots 四 share：owner / session / store / business。本项目轻量对齐：
| 官方 share | 本项目载体 | 说明 |
|---|---|---|
| store | `appState`（reactive 单例） | 全部视图共享状态，组合式注入 |
| session | `appState.sessionId/messages/…` + projectSession | 会话投影/草稿按会话隔离 |
| business | api.ts + store 导出的动作函数 | 后端动作（listSessions/chatStream/…） |
| owner | App.vue（shell） | 持有 WsClient 生命周期与 handler 编排 |

**（已更新为「全量」）** 决策经复核改为：业务动作下沉到独立组合式模块（`turn.ts` 回合/WS/SSE、
`sessionActs.ts` 会话级、`floating.ts` 浮层、`planMode.ts` 计划），使各视图模块真正自包含、可插拔——
新增视图无需 App.vue 注入 handler。此为「全量 P3」的实现选择（页面结构等价 + 冒烟通过）。

> 原保守方案（留档）：业务动作留在 App.vue（owner），通过 `@emit` 给子模块，而非下沉到每模块。理由：
- WS 主通道是单例下行，send 需写 appState 并驱动回合——集中一处最稳，避免多实例开 WS 副作用。
- 现状子组件（MsgView/Composer/Sidebar/…）已通过 emit 反向调 App.vue handler 工作良好，
  页面结构等价要求尽量小改——若把 send 下沉到 store/模块，会牵动 MsgView/Composer 的 emit 契约，
  改动面大且回归风险高。
- §6.2 的「TS 泛型收窄 props」落到本设计 = 各页面模块组件声明自己的 props/emit 类型
  （如 SettingsPage 的 `{onBack}`），由 registry 的 component 类型 + Slot 的 `$attrs` 透传保证。

因此：**拆槽主要解决「组装来源注册化」，而非「业务逻辑下沉」**。新增一个与 chat 同构的
main 视图时，若它需调用 send/stop 等宿主动作，通过 props 注入宿主 handler（Slot 透传 $attrs），
组件不改 App.vue 即可被装配。

## 8. 文件级改动清单

| 文件 | 动作 | 内容 |
|---|---|---|
| `src/registry.ts` | 新增 | ViewModule 类型 + createRegistry/viewRegistry + register/unregister/get/keys/clear |
| `src/registry.test.ts` | 新增 | 单测：register/get/覆盖/clear/keys 顺序 |
| `src/components/Slot.vue` | 新增 | 按注册表 + active 装配渲染的宿主渲染器 |
| `src/components/PlanModePanel.vue` | 新增 | 从 App.vue 抽出 plan toolbar/alert/文本 collapse（DOM/class 等价） |
| `src/views/ChatMain?`/plan/goal/trajectory 组合 | 视情况 | 若需将 plan=PlanModePanel+PlanView 组合为模块，建轻量包装组件 |
| `src/modules/registerViews.ts` | 新增 | side-effect：把 chat/plan/goal/trajectory/settings/tools 各模块 register 进 viewRegistry |
| `src/App.vue` | 重构 | 骨架保留；view 分发/body v-show 改查注册表；nav tabs 改数据遍历；plan 内联 UI 移除引用改 PlanModePanel |
| `src/main.ts` | 微调 | import registerViews 副作用（注册在 App 挂载前完成） |
| `docs/design-client-vue.md` | 追加 | §10 B/L3 记录 |
| （实际新增，替代上表部分） | MainShell.vue / PlanBody.vue / floating.ts / planMode.ts / sessionActs.ts / turn.ts | 壳抽出 + 动作下沉 |

## 9. 验证策略（P3 判据）

1. **registry 单测**：register 后 get 命中、覆盖替换、unregister 移除、clear 清空、keys 保序。
2. **页面结构等价**：重构后浏览器冒烟，逐项核对 ——
   - sidebar 与各工作区/会话交互不变；
   - main 壳：header（breadcrumb/conn/theme/workspace-chip/删除）、subagents-bar、nav 4 tab 顺序与激活、
     body 内 4 视图 v-show 切换、composer dock 常驻；
   - chat/plan/goal/trajectory 各自渲染与切回状态保留（v-show 等价）；
   - settings / 各工具独立页（standalone）正常进入/返回；
   - 发消息 → WS 回合 → 流式/工具行/结束 resync 全链路正常；
   - console 无报错。
3. **门禁**：npm test 全绿（10 + registry 新增）；vite build 绿；mvn verify 不回退。
4. **新增视图免改 App.vue**（演示）：注册表中临时 register 一个 key，App.vue 无需改动即可渲染
   ——以 plan 模块注册为验收代表（plan 已从内联改为「注册模块」，本身就是证明）。

## 10. 风险与克制

- **风险**：body 内多模块 v-show 需渲染全部 main 模块（含其 onMounted 副作用）。
  缓解：与现状等价——现状本就 v-show 全部 4 视图同时挂载，副作用时机不变。
- **克制**：不引 cordis / 不搬 React / 不做「业务逻辑下沉重写」；改动收敛在「组装来源注册化」+
  「plan 内联 UI 抽组件」两处；子组件 emit 契约不改（MsgView/Composer/Sidebar/GoalView/… 不动）。
- **不做**：settings 内部已官方模型驱动不动；tools 聚合壳 ToolsPage 不拆碎（仍单模块注册）。
