# 前端复刻上游（DeepSeek Web Client）评估

> 基线：archon-dsh HEAD `c1400b5` · external/deepseek `76fda72`（2026-09-03）
> 配套：[design-upstream-replication.md](design-upstream-replication.md)（后端三支柱）、
> [IMPLEMENTATION_DIFF.md](IMPLEMENTATION_DIFF.md) §9。
> 落地：**内核②（schema 单源消费）已按路径 C+L2 全量落地（2026-09-04，M10）** →
> [design-schema-ui.md](design-schema-ui.md)；L1（事件投影 store）与 L3（组件插槽化）未动。

## 1. 上游前端到底是什么（先纠偏）

上游**不是"一个 web app"**，而是一整套可拼装的客户端 SDK：

| 层 | 位置 | 职责 |
|---|---|---|
| 连接协议 | `packages/client/connection/` | RPC over HTTP bridge、api-path、rpc-schema（schema 化路由）、browser-auth、loopback-hostname、**generation 代次重放** |
| 客户端容器 | `packages/client/runtime/` `store/` | Cordis client 运行时：事件投影、应用状态、客户端 scope |
| schema 表单 | `packages/client/schema-form/` | **一套 schema 多消费**：设置/工具参数/客户端动作 → 动态表单 |
| UI 组件库 | `packages/client/ui-*`（40+ 包） | ui-chat/ui-conversation/ui-sidebar/ui-settings*/ui-slots/ui-renderer/ui-tool/ui-primitives/ui-goal/ui-plan/ui-subagent…可组合插槽 |
| 成品入口 | `packages/client/web`（AppWebEntry）、`apps/web/`、`client/web-react` | 浏览器壳；apps/web/main.ts 仅 4 行 → `new AppWebEntry(el).run()` |

## 2. Java dsh-web 现状（核实到文件级）

- 规模：`dsh-web/src/main/webapp/` 22 个源文件、~8.3k 行（Vue3 + Vite + Element Plus）。
- **传输三通道已齐**：上行 HTTP（`api.ts` ~1014 行、80+ 端点函数：sessions/workspaces/
  directory/chat/subagents/goal/plan(DAG)/auth/interaction/trajectory/skills/jobs/feedback/
  export/compact…）；下行 WebSocket（`ws.ts`：常驻连接 + 指数退避 + availability 回退 SSE，
  **注释明示对齐官方 ConnectionController/events.mux 语义**，后端 `SessionEventWebSocketHandler`
  已发 `{type,sessionId,seq,event}` 帧）；下行 SSE（`chatStream` 事件解析 + resume 续流）。
- 组件面宽：MsgView(流式/折叠/问答框)、CodeView(Monaco)、PlanView(DAG)、GoalView、TodoPanel、
  Composer、Sidebar、CommandMenu、ModelPicker、SkillPicker、ToolsPage、TrajectoryView、
  DirectoryBrowser、FloatingChat。
- 数据层：`store.ts` = Vue reactive **单例**（拉取后手工 set），**非事件投影**；render.ts 仅
  markdown/净化/折叠 HTML 工具。
- **关键缺口**（核实）：
  1. 后端**无工具 schema 下发端点**；MsgView 靠**硬编码** `SUMMARY_KEYS/TOOL_TITLES`
     （注释自称"对齐官方 SUMMARY_KEYS/VARIANT_TITLES/TOOL_TITLES"）→ 新增工具须改前端。
  2. `store.ts` 不消费 WS 事件帧作为事实源（帧仅触发局部刷新/notice）→ 实时状态靠拉取。
  3. `App.vue` 940 行单体编排；无 settings 页/插件卡（ToolsPage 的 mcp/expert 仍是"规划中"占位）。

## 3. 复刻 = 什么（三条内核，非搬 React）

在 Vue 栈内借鉴上游 client 的架构内核，后端几乎不动：

- **内核①：事件投影 store（L1）**——把 `store.ts` 演进为"WS 帧 → 投影"。帧已带
  `(sessionId, seq)`，可做 seq 去重/乱序、断线重连增量 resync（对齐上游 generation 语义的
  轻量版）。收益：实时状态正确性、重连不闪跳。
- **内核②：schema 单源消费（L2）**——诚实界定价值点：chat 工具调用由**模型**发起、前端只
  展示摘要，schema 表单在这里价值有限；**真正价值在设置/配置面**（Java 已有
  `dsh.settings` REST GET/PUT + SettingsController 可 schema 化）、参数预览卡、可插拔页面。
  落点：后端补**工具元数据端点**（toolName/摘要键/标题/参数概要，替代前端硬编码表，新工具
  不再改前端）；设置页用 settings schema 生成动态表单。
- **内核③：组件插槽化（L3）**——App.vue 拆分为 sidebar/conversation/composer/settings 布局
  + 插槽注册（对齐上游 ui-layout/ui-slots/ui-settings-*），为将来功能留口，改可维护性。

## 4. 四条路径

| 路径 | 做法 | 成本 | 风险 | 何时选 |
|---|---|---|---|---|
| A. 整包挂官方 UI | Java 后端实现官方 connection 协议（rpc-schema/api-path/browser-auth/generation/events.mux 序列化）+ 官方数据形状 | 极高（≈ 重写 dsh-api 为官方 host 契约） | 高（上游 web 快迭代，30+ e2e 追版本） | 需 100% 官方 UX 且接受协议绑定，不推荐 |
| B. 分层借鉴（推荐） | 现有栈内做 L1 投影 store → L2 schema 消费（工具元数据 + settings 表单）→ L3 拆组件；逐步替换手工件 | 中（L1+L2 为主，估 1–2 周专注） | 低（每步独立可合并，22 文件逐一替换） | 默认路线 |
| C. 只做 schema 驱动 | 后端 +1 元数据端点 + SchemaForm.vue + MsgView 参数卡改 schema 渲染 | 小 | 低 | 只想"新工具不再改前端" |
| D. 现状+按需 | 维持单体，按需补 settings/models 等页面 | 小 | 低 | 前端非重点时 |

## 5. 与后端复刻的关系

前端三内核与后端三支柱**解耦**：schema 元数据端点不依赖事件源改造；但若后端做支柱②
（常驻 agent/取消及时化），L1 投影层收益更大（实时状态更准）——二者可并行，建议前端先做
L1+L2（独立闭环），再视后端支柱节奏推进。

## 6. 建议

选 **B：L1（事件投影 store）→ L2（schema 消费）→ L3（拆组件）** 渐进落地；其中 L2 先做
"工具元数据端点替换硬编码表"这个最小闭环（收益/成本最高），再做 settings 动态表单。
若前端非重点，可停在 C。A 在 Java 侧不推荐。

（2026-09-03 定稿：基于文件级核实；2026-09-04 用户圈定 **路径 C + L2 全量（内核②全量）**，
落地见 [design-schema-ui.md](design-schema-ui.md) §6，M10 ✅）
