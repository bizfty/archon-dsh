# A 档（真身引入官方 client）实施路线设计

> 基线：archon-dsh HEAD `b742b2f` · 上游快照 external/deepseek `76fda72`
> 前置：[design-client-vue.md](design-client-vue.md)（三档评估，本文件为 A 档展开）
> 结论先行：**A 档存在 A1/A2 两个不可合并的子方向，必须先拍板；本文件给两者完整任务分解。**

## 0. 为什么 A 档要先讲清楚"真身"指什么

文件级核实（external/deepseek `76fda72`）确认：**官方 client 不是可连任意后端的独立前端成品**。

- `apps/web/vite.config.ts` 明示"not a standalone application"，必须由官方 `dsh web`（Node host）伺服，注入
  `window.__DSH_BOOT__`（boot manifest）+ `window.__ModuleLoader__`（cordis-plugin-loader bootstrap）。
- `packages/client/web/src/boot.ts`：`AppWebEntry.run()` 用 cordis Loader 按 manifest 动态 import 全部 client 插件
  （`ui-*` 包），`ctx.inject(['uiRenderer'])` 挂载 React 渲染器。
- 连接协议面（`packages/client/connection` README）：`/api` HTTP bridge（POST unary RPC）、`/api/remote.mux`
  WebSocket（mux 逻辑流）、Fetch route registry（GET/HEAD 精确路由）、browser-auth（launch `?token=` → authority-bound
  signed cookie）、request-trust（Host/Origin/sec-fetch-site → 403/401）、generation（ready item 带 `clientId`/`host.home`）。
- host 领域面（`packages/api/*` + 顶层 ~30 个 cordis server 包 session/workspace/fs/shell/settings/plan/goal/
  subagent/llm/credentials/…）：业务全在 **host 侧 cordis service** 里，client 只是其前端。`packages/api/remotes`
  把 host 的 cordis 事件转发进 mux `$events` 流（`API_REMOTE_FORWARDED_EVENTS` 清单，ready 后发）。

**推论**：官方 client（真身）只认"官方 host 协议 + 官方 host 领域事件/方法"。archon-dsh 若保留 Java 主体，
只有 A2 一条路；若接受官方 host 替代 Java 主体，走 A1。二者成本与产品含义完全不同，不可混为一谈。

## 1. A1：官方 host 整树真身（Java 外围化）

### 定义
把 external/deepseek `76fda72` 构建出的官方 **dsh（Node host + cordis 领域 + apps/web）**作为 archon-dsh 的
"引擎与前端"，Java dsh-api 降级为被官方 host 以 http/mcp 工具调用的外围服务。官方 client 即官方 apps/web 成品。

### 任务分解
| # | 任务 | 说明 |
|---|---|---|
| A1-1 | 上游构建基线 | external/deepseek 锁 `76fda72`；pnpm install + `pnpm dsh web` 冒烟官方成品 |
| A1-2 | 官方 host 装配 | 确定 host 目录/home、credentials、settings provider（`$DSH_HOME/.credentials.yaml`） |
| A1-3 | archon Java 服务保留面 | 定哪些 Java 能力留作被调工具/mcp 服务（AgentLoop、DAG plan、工具执行……），暴露官方 host 可消费形态 |
| A1-4 | 认证/端口/网络 | dsh web 监听、browser token 打印、反代；Vue dsh-web 处置（弃用/登录壳/并存 iframe） |
| A1-5 | 数据迁移/并存期 | archon 既有 session/设置/DB 与官方 host 存储（$DSH_HOME）的桥接或一次性迁移 |
| A1-6 | 验收 | 官方 e2e 冒烟子集；archon 既有 571 测试仅对保留的 Java 服务面回归 |

成本：中-高（上游构建 + 外围化改造），但**产品空心化**：对话/设置/工作区/plan/agent 全变成官方 dsh 的，
archon-dsh 自己只剩"被调用的一堆 Java 工具"。选它等于承认 archon-dsh 产品被上游取代。

## 2. A2：Java 复刻官方 host 契约面（archon 主体保留）

### 定义
dsh-api 实现官方 host 的**协议层 + 事件面 + 数据形状**，伺服官方 client bundle（真身前端）；
浏览器里跑官方 cordis+React 插件，Java 提供它们期望的全部 `/api` 面。Vue dsh-web 并存/退役。

### 任务分解（按依赖排序）

#### P0 契约冻结（先做，产契约基线 md）
- [ ] P0-1 版本锁：external/deepseek `76fda72` 为唯一契约源；记录 client 各包版本（40+ workspace 包）。
- [ ] P0-2 盘点官方 host 协议面到文件：connection（api-path/http-bridge/rpc.ts envelope/rpc-schema/browser-auth/
      api-request-trust/generation）、gateway（stream-protocol/stream-server/mux）、remotes（$events ready +
      `API_REMOTE_FORWARDED_EVENTS` 清单）、settings envelope、fetch route registry → `docs/contract-host-a2.md`。
- [ ] P0-3 盘点 client 消费面：按 ui-*/store/modules 反推 client 调用的 remote/事件/store 形状（页面 → service 面）。
- [ ] P0-4 盘点 archon 现状映射：dsh-api 现有端点/事件 ↔ 官方面，标 已有/需补/需桥。

#### P1 传输与伺服层（Java）
- [ ] P1-1 静态伺服官方 bundle：从 external/deepseek 构建 apps/web 产物（`pnpm build`/`dsh web` 产 dist），
      由 dsh-web servlet/静态资源伺服；index.html 注入 `__DSH_BOOT__` manifest 与 `__ModuleLoader__` bootstrap
      （bundle 需含模块清单 + 分段加载，见 client-build-environment）。
- [ ] P1-2 `/api` HTTP bridge：POST unary RPC envelope（rpcId/payload，见 rpc.ts）→ 按方法分发；
      `ConnectionRpcResult {ok,value|error{code,message,details}}`。
- [ ] P1-3 `/api/remote.mux` WebSocket：mux 多逻辑流（$events + 各 Remote stream），stream-protocol 帧格式、
      journal/快照流语义；对照 archon 现 SessionEventWebSocketHandler 升级。
- [ ] P1-4 Fetch route registry：GET/HEAD 精确路由注册表（Session-log 下载等非 JSON 响应），未认领 404。
- [ ] P1-5 request-trust：loopback/trustedHosts Host 校验 + Origin 等 + `sec-fetch-site: cross-site` 拒 → 403/401。

#### P2 浏览器认证（Java）
- [ ] P2-1 launch token：进程随机 token，root `GET /` 带 `?token=` → 校验 → 发 authority-bound signed cookie
      （host-only、Path=/、HttpOnly、SameSite=Strict、30d）；静态资源公开、其余 401。
- [ ] P2-2 凭据与吊销：cookie 签名密钥存 archon 服务端；删除记录即吊销全会话。

#### P3 事件面（archon 事件 → 官方 $events）
- [ ] P3-1 事件名映射：按 `API_REMOTE_FORWARDED_EVENTS`（commands/credentials/llm/agent-presets/settings/
      user-approval/user-questions/session/…）把 archon 后端事件源映射成同名 cordis 事件 → 队列 → mux。
- [ ] P3-2 generation/ready：`{type:'ready', clientId, host:{home}}` 先于事件；断线重连按 500ms→10s 退避换流。

#### P4 领域 RPC 面（最大头：archon 领域 ↔ 官方 client 消费形状）
- [ ] P4-1 会话/消息：官方 client 期望的 session list/open/消息流（session-controller/remotes）↔ archon sessions/chat/ws。
- [ ] P4-2 设置：官方 envelope（schema.toJSON + value/base/user/revision/writable/mode + path ops + redact）↔
      archon P3 settings（已有大部分语义，需加 envelope 序列化 + scope 快照 + base/memory 模式）。
- [ ] P4-3 工作区/目录/fs：官方 workspace-controller/directory-picker ↔ archon DirectoryBrowser/workspaces。
- [ ] P4-4 模型/工具/技能：官方模型选择/工具元数据/技能列表 ↔ archon tools meta/skills（补官方键）。
- [ ] P4-5 会话内状态机：官方事件/消息形状（用户消息/助手流式/工具行/审批/提问/子代理/计划/目标/作业/反馈…）
      逐一映射或桥接 archon 现有 store/ws 帧。
- [ ] P4-6 补充官方特有面：approval、user-questions、attachment、trajectory、workflow-run 等 archon 缺失的
      remote/事件 → 新增端点或先降级（页面空态）。

#### P5 UI 装配与并存
- [ ] P5-1 官方 client 产物冒烟：仅伺服 + 认证 + $events 通 → boot 页加载 → React UI 挂载。
- [ ] P5-2 页面分级：先打通 会话/设置 两页；其余按 P4 进度逐步点亮。
- [ ] P5-3 Vue dsh-web 并存：旧入口保留（子路径/端口），官方入口独立路由；决定最终退役时点。

#### P6 验收与追版本
- [ ] P6-1 回归：archon 既有 571 测试（dsh-api/AgentLoop 读取零漂移）不回退；官方 apps/web e2e 冒烟子集。
- [ ] P6-2 追版本策略：锁快照制（升级=external/deepseek 更新 + 契约基线重跑 P0），记录上游迭代影响面。

### A2 成本量级
P1–P3 是"官方 host 协议的 Java 传输层"，可独立交付；**P4 领域 RPC 面是主体成本**（≈ 把 archon 领域模型按官方
client 的消费形状重建一遍 remote/事件），估为 A 档总工作量的 60%+，与上游迭代同生命周期维护。

## 3. 决策与建议

- 若产品目标是"archon-dsh 自研主体继续演进" → **A2**（保留 Java，但接受传输层 + 领域 RPC 面的大规模重建与追版本）；
- 若可接受"产品被官方 dsh 取代、Java 只剩被调工具" → **A1**（成本低但空心化）；
- 两条路的公共第一步都是 **P0 契约冻结 + P1/P2 传输认证 spike**（对 A1 则是 A1-1/A1-2 上游装配冒烟），
  建议先做 spike 验证协议面复杂度，再 commit 全量 A2。

## 4. 风险

- 上游 web 快迭代（30+ e2e、40+ 包、typert/cordis 运行时）→ 版本锁快照是唯一可行策略；
- cordis Remote 方法/事件名全集未集中成文档 → P0-3 需从包反推，是 A2 的主要不确定项；
- archon 领域（DAG plan、AgentLoop 取消及时化等）与官方会话状态机/事件语义差异 → P4 每项都要双向映射决策；
- 认证 cookie 非 Secure（官方默认 loopback HTTP）→ 若 archon 走 TLS 反代需扩展 SameSite/secure 策略。
