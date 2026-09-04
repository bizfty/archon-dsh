# A2 Remote RPC 桥接表 + $events 事件桥 + 官方 client 伺服规格

> 契约源：external/deepseek `76fda72`（typert 生成面 + client/connection wire + api/remotes allowlist）
> 配套精读：[_survey-a.md](_survey-a.md)（settings/credentials/llm/agentPresets/commands/goals/messageFeedback 7 ns 方法级详情）
> 　　　　　[_survey-b.md](_survey-b.md)（session/workspace/fileReferences/skills/directoryPicker/sessionReferenceResolver/pluginInventory/subagents + 18 事件签名）
> 本文档是 Java 桥接实现的工作基线；与 [contract-host-a2.md](contract-host-a2.md) 的分工：后者管 wire 信封/帧/认证，本文管 **endpoint 语义表**与 **boot 伺服产物规格**。

## 1. endpoint 命名（修正基线）

- **endpoint = `ns/method`**（斜杠连接，每段匹配 `[A-Za-z0-9_$.-]+`）。
  证据：`gateway.host.spec.ts` 用 `goals/create`；client fixture 用 `fileReferences/list`。
  早前 contract-host-a2.md §2.1 示例 `settings.view` 的点号为笔误，以本文为准。
- wire 上是**无参对象封装**：payload 顶层通常为 `{ args: {…} }`（见 §1.1），unary 信封 `{type:'client-request',rpcId,method:'ns/method',payload}`。
- 方法首参若为 `request` 对象 → 字段并入 args；标量（`sessionId`/`ns`/`agent`…）也入 args；`agent:Agent` 序列化为 `agentId`；`AbortSignal` 不跨 wire（carrier 承载）。
- `@Remote('x')` 重命名时 client 名 = x（如 llm `remoteDiscoverModels` → `discoverModels`）；remoteExport* 前缀同理被投影为正式名。

### 1.1 payload 封装（待 typert descriptor 核实）
client fixture 提示方法参数字段装入 `args`：请求信封 `payload` 顶层即**被调方法命名字段**或统一 `args`。P4 实现时以官方 fixture（`connection/tests`、`gateway*.spec.ts`）回放校验，不通则按 fixture 调整——wire 由官方 fixture 集裁决。

## 2. Remote ns 桥接表

图例：★=client 消费面已见（P0 最小集，Java 首轮实现）；○=host 面注册但 UI 未直调（P4 轮）。

### 2.1 session（owner api/session-controller）→ archon dsh-session/dsh-api
| endpoint | 参数(args) | 返回 | Java 映射 | 错误码 |
|---|---|---|---|---|
| ★ session/list | `_request:{cursor?}` | `{items:[{sessionId,updatedAt,running,blank,parentSessionId?,origin?,cwd?,projections?}]}` | SessionReadModel 列表 | — |
| ○ session/search | `{query}` | `{items:[{sessionId,snippet}],hasMore}` | SessionQueryService.search | — |
| ★ session/create | `{workspaceId?,cwd?,sessionId?,agentPreset?}` | `{sessionId,agentPreset?}` | SessionFactory/workspace 挂接 | session/conflict 等 |
| ★ session/prompt | `{requestId,sessionId,mode:'queue'\|'steer',content:[{type:'text',text}\|{type:'image',mediaType,data,name?}],clientTimeZone?}` | `{accepted:true}` | 既有 prompt 管线（dsh-api/ws 同源） | gateway/cancelled 等 |
| ★ session/modelCatalog | — | ModelCatalog（default/routableProviders/groups/failures） | dsh-llm LlmGateway 投影 | — |
| ○ session/selectModel | `{sessionId,provider,model,reasoningEffort?}` | `{selected:…}` | dsh-llm | session/model-unavailable |
| ○ session/rename | `{sessionId,title}` | `{title,seq}` | SessionSurface 改名 | session/title-invalid |
| ○ session/fork | `{sessionId,atSeq?}` | `{sessionId}` | —（P4+） | session/fork-unavailable |
| ○ session/attachment | `{sessionId,attachmentId}` | `{attachment,data(base64)}` | dsh-fs/attachment | session/attachment-invalid |
| ○ session/updateQueue | `{sessionId,itemId,action:{kind:'edit'\|'remove'\|'steer',…}}` | `{accepted:true}` | 队列控制 | session/queue-item-not-found |
| ○ session/cancel | `{sessionId}` | `{accepted:true}` | cancel 事件 | — |
| ○ session/page | `{address,…}` | PageFrame | EventLogReader | — |
| ○ session/follow | stream（mux 流端点） | snapshot/event 帧 | 事件流 | — |
| ○ session/control | stream | baseline/queue/jobs/projection 帧 | 控制流 | — |
| ★ session/canOpenWorkspacePath | — | boolean | nativeOpen 能力 | — |
| ○ session/openWorkspacePath | `{path}` | `{opened:true}` | 目录打开（trustedHosts 约束） | gateway/bad-request |

### 2.2 settings（owner api/settings-controller）→ archon dsh-settings SettingsService ★
| endpoint | 参数 | 返回 | Java 映射 |
|---|---|---|---|
| ★ settings/describe | — | `{writable,hasDocument,namespaces:[{ns,schema,value,base?,user?,applies,secrets,revision}]}` | describe+redact（SettingsRedactor） |
| ★ settings/update | `{ns,patch,expectedRevision?}` | SettingsNamespaceView | update(ns,patch,rev) |
| ★ settings/replace | `{ns,section,expectedRevision?}` | SettingsNamespaceView | replace(ns,section,rev) |
| ★ settings/mutate | `{ns,ops:[SettingsPathOpView],expectedRevision?}` | SettingsNamespaceView | mutate(ns,ops,rev) |
| ○ settings/openSettingsDocument | (signal) | `{opened:true}` | 文档准备+打开 |
| ○ settings/openAgentPresetDirectory | `{agentPreset}` | `{opened,path?}` | 目录打开 |
| ○ settings/canOpenAgentPresetDirectory | — | boolean | 能力 |
错误码：`gateway/bad-request`（zod 校验）、`settings/conflict`（rev 不符）、`settings/rejected`、`gateway/internal`；`agent-preset/not-found|read-only|invalid`。

### 2.3 credentials（owner api/settings-controller/credentials.ts）→ archon dsh-credentials CredentialService ★
| endpoint | 参数 | 返回 |
|---|---|---|
| ★ credentials/describe | `{ref}`（wire 形状待 fixture：provider/name/id） | ref 的 redacted 描述 |
| ★ credentials/set | `{ref,value?}` | `{…}` |
| ★ credentials/unset | `{ref}` | `{…}` |
错误码：`credential/rejected`、`gateway/bad-request`。

### 2.4 llm → archon dsh-llm
| endpoint | 参数 | 返回 |
|---|---|---|
| ★ llm/listProviders | — | ProviderInfo[] |
| ○ llm/listConfigurableProviders | — | 可配置 provider 集 |
| ★ llm/discoverModels | （wire 名 discoverModels；owner 名 remoteDiscoverModels） | 模型目录 |

### 2.5 agentPresets → archon dsh-agent 侧 preset 存储（★ client 消费）
`agentPresets/select(sessionId,preset)`、`list()`、`read(id)`、`copy(id)`、`deletePreset(id)`、○`readDocument(id)`。
错误码：`agent-preset/not-found|invalid|read-only|locked|conflict`。

### 2.6 commands（★）/goals（★）/messageFeedback（★）→ archon dsh-interaction / dsh-goal / dsh-feedback
- commands：`list(sessionId)` → CommandDescriptor[]；`execute(sessionId,line,images?)` → CommandExecution{result 成功/错误}；码 `unknown-command`。
- goals：`edit/pause/resume/clear(sessionId,ref,…)`、○`complete`、○`create`；无 list（读在 host 本地）。
- messageFeedback：`put/delete/list`；业务 `ok:true|false`；码 `session-not-found|target-not-found|version-conflict|note-blank|note-too-large`。

### 2.7 其余 ns（P4+，host 全集）
- workspace（create/rename/delete/insertBefore/insertSessionBefore/archiveSession/follow(stream)）→ archon dsh-session WorkspaceService（dsh-api/WorkspaceController 已有雏形）。
- directoryPicker（pick/list/createDirectory）、sessionReferenceResolver/candidates、fileReferences/list、skills/list、pluginInventory/list、subagents（list/prompt/interruptByParent）。

## 3. $events 事件桥（18 项 allowlist → Java 事件源）

mode `emit` = 通知下行；`waterfall` = client 应答/续传（走 `$events/result` unary 回投）。Java 桥实现：一个 `HostEventSource` 注册表 → `MuxSession` 推 emit 帧；waterfall 由 dispatcher 挂 pending 续延。

| 事件 | mode | 参数(owner 签名) | Java 事件源建议 |
|---|---|---|---|
| settings/document-updated | emit | (ns, revision) | SettingsService 写后钩子（update/replace/mutate 返回 rev 时广播 ns+rev） |
| api-session/added | emit | (sessionId, …) | 会话创建 |
| api-session/removed | emit | (sessionId) | 会话删除/归档 |
| api-session/status | emit | (sessionId, status) | 状态变更（running…） |
| api-session/activity | emit | (sessionId, activity…) | 事件日志落点 |
| api-session/error | emit | (sessionId, error…) | 会话错误 |
| agent-preset/selected | emit | (sessionId, agentPreset) | select 后广播 |
| commands/change | emit | () | 命令注册变更 |
| credentials/reference-updated | emit | (ref) | set/unset 后广播 |
| llm/adapters-updated | emit | () | provider 变更 |
| approval/request | waterfall | (request,next) | dsh-guard 审批桥 |
| user-questions/request | waterfall | (request,next) | 用户提问桥 |
| cordis/request-run 等 6 | emit | (payload) | 运行时（P4+） |

Java 侧 wire：`$events` 逻辑流 item.value = `{type:'emit',event,args:[…]}`（emitValue 已备）；waterfall 需把 `{type:'emit',event,args:[request]}` 对应 pending 表并在 client `$events/result` 回投时 resolve（endpoint `$events/result`，payload=client 对 request 的应答）。

## 4. 官方 client 伺服规格（76fda72 构建/伺服面）

### 4.1 双面事实（构建产物一致性陷阱）
- **源码 HEAD boot 面**（packages/client/web/src/boot.ts）：await `__DSH_BOOT_READY__.promise` → 读 `window.__ModuleLoader__`（queue facade）→ `.create({boot:window.__DSH_BOOT__,staticModules})`。
- **现存 dist（8/18）boot 面不同**：直接 `R3(globalThis.__DSH_BOOT__)` 校验 manifest `{rev,modules:[{id,url,rev?,inject?,immediately?}]}` → `new ClientModuleSystem({modules,staticModules})` + `registerStatic`。**旧 dist 非 76fda72 产物，需重构建**（s7）。
- 注入行机制（packages/client/modules/src/index.ts `bootInjections`）：head 区先插 **ModuleLoader queue 内联 script**（`window.__ModuleLoader__={mode:'queue',pendingQueue,load(reg){…},create(options){…}}`），再 script-src 预载 bootstrap 批次（`/plugins/@deepseek-ai/dsh-client-modules/client.js`），尾部 `{kind:'global',name:'__DSH_BOOT__',value:graph}`（graph 由 ClientModuleRegistry.compose 产：`{rev,entries,batches}`）。
- boot 尾注 `__DSH_BOOT_READY__` resolve script（injections.ts READY_MARKUP）——**served 形态必须含**。

### 4.2 文件与路由
| 资源 | 位置 | Java 伺服 |
|---|---|---|
| dist shell | external/deepseek/apps/web/dist/**（vite 产物 index/assets/favicon/manifest） | 静态映射 `/` 前缀 |
| 插件 bundle | `packages/client/<pkg>/lib/client.js`（+`.map`） | `/plugins/<encoded-id>/client.js` → 包产物路径；combo `??a,b` 拼接 |
| manifest | 组装（rev=shortHash(entries+batches)） | 注入行生成（等价 bootInjections） |
| 包扫描输入 | 每包 package.json `dsh.client.{entry,inject,external,immediately,platform}` | Java 读取器 |

### 4.3 Java 伺服步骤（s8）
1. `GET /`（无 token）→ 302 `/?token=<launch>` 或登录页（同 browser-auth 语义）；带有效 `?token` → Set-Cookie + 302 `/`。
2. `GET /`（已认证）→ 读 dist/index.html → `renderIndexInjections` 等价：插 ModuleLoader queue script、bootstrap script-src、`__DSH_BOOT__` global、`__DSH_BOOT_READY__` resolve。
3. `GET /plugins/<id>/client.js`（+combo +.map）→ 伺服 workspace 包编译产物（lib/client.js）。
4. `GET /assets/*`、`favicon.svg`、`manifest.webmanifest` → 静态 dist。
5. 一切 API/WS 走既有 RpcBridgeController / RemoteMuxWebSocketHandler（auth/trust 闸门前置）。

### 4.4 archon 伺服落点（spike 模块 dsh-host-bridge 扩展）
`@Profile("hostbridge")`：`OfficialWebController`（index 注入+/plugins/静态）+ `RemoteMuxWebSocketHandler` 真接 Spring WS + 既有 `BrowserAuthFilter`/`RequestTrustFilter`。产物根目录做成可配置属性（`dsh.hostbridge.webroot=…/apps/web/dist`、`dsh.hostbridge.pluginsRoot=…/packages/client`）。

## 5. P4 实现次序（Java 桥）

1. **settings ns 全 4 写读 + describe**（settings 页可用）：SettingsService.update/mutate/replace 有 rev 冲突异常 → 映射 `settings/conflict`。
2. **session 冷读**：list/create/canOpenWorkspacePath + modelCatalog（会话页左栏可用）。
3. **credentials 3 法**（设置页模型/凭据区）。
4. $events：settings/document-updated + api-session/* emit 源（设置页保存后回刷 + 会话活动）。
5. commands.list/execute 存根 → dsh-interaction 真接（P4+）。
6. 其余 ns 按表补（P4+ 轮）。

## 6. 验收

- 每个实现 endpoint 有单测：信封 ok:true 形状 + 错误码映射；stream 端点帧序（baseline→upsert→…→end）。
- 官方 client 冒烟：登录 → boot 页无 fail 列表 → settings 页 describe/update 回写成功 → session 页 list/create 呈现。
- archon 全仓 `mvn verify` 571/0 不回退。

## 7. 伺服实测状态（2026-09-05 续跑）

### 已闭环（Java 8877 端口 hostbridge profile + H2 实测）
- `?token=` 交换 → 302 + `Set-Cookie`（dsh.<host>=v1.<payload>.<sig>; HttpOnly; SameSite=Strict）✓
- 认证 `GET /` → dist/index.html 注入 `__DSH_BOOT__` global + `__DSH_BOOT_READY__` resolve ✓
- `/plugins/<id>/client.js`（41+ 包产物，scan 覆盖全 packages 含 runtime 无清单目录）+ dist 静态 ✓
- unary：`settings/describe|update|replace|mutate`、`credentials/*`、`session/list|create` 全 200 `ok:true`（信封 §2 wire 一致）✓
- 浏览器：boot 执行至插件激活（`__DSH_MODULES__` 建立、manifest 47 entries、locale require 解析），
  随后卡 `attachment`（8/18 dist 静态 seed 包被误列为 loader 行）——最后一公里见下。

### 关键机制事实（下轮直接依据）
1. **dist(8/18) ≠ 源码 HEAD(76fda72) 产物**：76fda72 vite 重构建被 `apps/web/src/preview.ts →
   @deepseek-ai/dsh-experimental-webworker-runtime/worker` 链阻断（该包未构建产物）；需先 tsdown
   webworker-runtime 或临时剔除 preview input。
2. 8/18 dist boot：`R3(__DSH_BOOT__)` → `new ClientModuleSystem` **自装 `__ModuleLoader__`**
   （预装 queue 会抛 "already installed (double boot?)"）→ 伺服**不注入 queue**，只注入
   `__DSH_BOOT__` + ready（已按此改 `OfficialWebAssets.renderIndex(html)` 默认双参模式）。
3. **静态 seed 集（不进 loader rows）**：dist `Wm()` seed = react 系 + cordis + ui-slots/
   web-react/ui-primitives/ui-attachment/schema-form；`registerStatic` = app-shell + client-modules。
   扫描收录时须排除（否则 apply fail，如本次 attachment）。
4. **库类 bundle**（runtime 等无 package.json/dsh.client）：需注册供插件 `require`（dist require
   做 `stripClientSuffix`，注册键=根名）；manifest entries 是否含库行由 8/18 ClientModuleRegistry
   compose 决定——需从 8/18 client-modules 实现或 node host 实跑反推精确 entries 集与次序。
5. manifest `entries` = loader rows（R3 plugins 派生全量）→ 只可含可 apply 的 cordis 插件。
