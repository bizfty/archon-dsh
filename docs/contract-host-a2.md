# A2 Host 协议契约基线（Java 必须实现的官方 wire 面）

> 契约源：external/deepseek `76fda72` packages/client/connection + packages/api/gateway + packages/api/remotes
> 基线用途：dsh-api（Java）实现官方 host 协议面，伺服官方 client bundle（A2，见 [design-client-vue-a.md](design-client-vue-a.md)）
> 本文所有形状均来自官方源文件级核实；实现以本文为准，升级上游时先重跑本文。

## 1. 通道总览

浏览器官方 client（cordis 插件树）与 host 之间只有 4 类通道：

| 通道 | 载体 | 路径 | 用途 |
|---|---|---|---|
| Unary RPC | HTTP POST JSON | `/api/<endpoint>` | 一切请求-响应 Remote 调用（含 `$events/result`） |
| Remote 流 | WebSocket 文本帧 | `/api/remote.mux` | 多逻辑流复用：`$events` 事件流 + 各 Remote 流 |
| Fetch 路由 | HTTP GET/HEAD | `/api` 下精确路径 | 非 JSON 响应（如 Session-log 下载） |
| 静态前端 | HTTP GET | `/`（index.html + assets） | 官方 client bundle 与 `__DSH_BOOT__`/`__ModuleLoader__` 注入 |

所有通道在分发前先过 **信任(403)→认证(401)** 闸门；静态资产公开。

## 2. Unary RPC 面（HTTP POST `/api/<endpoint>`）

### 2.1 请求信封（client→host）
```jsonc
{ "type": "client-request", "rpcId": "<随机uuid>", "method": "<endpoint>", "payload": <任意JSON> }
```
- URL：`POST {origin}/api/{endpoint}`；`endpoint` 段字符集 `[A-Za-z0-9_$.-]+`（如 `settings.view`、`$events/result`）。
- Header：`content-type: application/json`；body 即信封 JSON（无额外包装）。
- 源：`packages/client/connection/src/client/rpc.ts` `createWebConnectionRpc.call`。

### 2.2 响应信封（host→client）
```jsonc
// 成功
{ "type": "server-response", "rpcId": "<回显>", "result": { "ok": true, "value": <任意JSON> } }
// 失败（业务/网关错误，HTTP 仍 200）
{ "type": "server-response", "rpcId": "<回显>", "result": { "ok": false, "error": { "code": "gateway/*", "message": "…", "details": {} } } }
```
- client 校验：`response.ok`（HTTP）必须为 2xx，否则 `transport failure … HTTP <status>`；信封 `rpcId` 必须回显一致。
- 源：`connection/src/rpc.ts`（信封）、`connection/src/rpc-host.ts`（host 回包）。

### 2.3 HTTP 状态语义
| 状态 | 含义 | 时机 |
|---|---|---|
| 200 | 业务完成（含信封内 `ok:false` 的业务错误） | 信封 |
| 401 | 未认证 | 任意受保护请求无有效 cookie（分发前） |
| 403 | Host/Origin 不受信 | DNS-rebinding/跨站闸门（分发前） |
| 404 | endpoint 未认领 | 无 interceptor / endpoint 不匹配（`createSharedFetchHandler`） |
| 413 | 请求体超限 | >300 MiB（默认） |

- 错误 code 命名空间见 `packages/api/gateway/src/types.ts` `TypertGatewayErrorCode`（`gateway/ambiguous-endpoint`、`gateway/lookup-not-found` 等 17 个）。
- host 端只有一个共享通道 interceptor 注册在 `/api`（`rpc-host.ts`：duplicate interceptor 抛错）。

## 3. Remote 流面（WebSocket `/api/remote.mux`）

### 3.1 物理帧（JSON 文本帧）
client→server：
```jsonc
{ "type": "open",   "streamId": "<client分配>", "endpoint": "<logical endpoint>", "payload": <任意JSON> }
{ "type": "cancel", "streamId": "<已开流的 id>" }
```
server→client：
```jsonc
{ "type": "item",  "streamId": "...", "value": <任意JSON> }
{ "type": "end",   "streamId": "..." }
{ "type": "error", "streamId": "...", "error": { "code": "…", "message": "…", "details": {} } }
```
- 一 WS 多逻辑流靠 `streamId` 复用；重复 open 同 id 报错；socket 关闭 abort 全部流。
- 帧校验（`stream-protocol.ts` `parseRemoteStreamClientMessage/ServerMessage`）必须严格 exact-keys。
- 升级前同样过 trust/auth（拒绝时 401/403 close）。
- 源：`gateway/src/stream-server.ts` `RemoteStreamMuxServer`、`stream-protocol.ts`。

### 3.2 `$events` 逻辑流（host→client 事件下行）
- open 参数：`endpoint='$events'`、`payload={ "args": {} }`（`REMOTE_EVENT_STREAM_PAYLOAD`）。
- open 后 host 立即下行 ready 帧（作为 item 的 value）：
```jsonc
{ "type": "ready", "clientId": "<generation id>", "host": { "home": "<host home 绝对路径>" } }
```
- 之后每个 item.value 为下行帧之一：
```jsonc
{ "type": "emit",  "event": "<cordis 事件名>", "args": [ ... ] }
{ "type": "waterfall", "event": "…", "eventId": "…", "agentId": "…", "request": {…} }
{ "type": "cancel", "eventId": "…" }
```
- client 对 waterfall 的回报走 unary `$events/result`（`clientId/eventId/outcome:{kind:'next'|'result',value?|'rejected',error:{name,message,code?,details?}}`）。
- 源：`gateway/src/stream-protocol.ts` 常量与帧类型、`remotes/src/index.ts`（remoteEventSource 注册 host.home、ready 先于事件）。

### 3.3 重连（client 侧自带，host 无需实现退避）
client `ConnectionController` 退避 500ms/1s/2s/4s/8s/10s + jitter；每代换新 WS 重开 `$events`，ready 到达才算 connected；离线暂停、online 重置。

## 4. 信任与认证面

### 4.1 信任闸门（403，所有请求含 WS upgrade、静态 index 除外）
`isTrustedApiRequest`（`connection/src/api-request-trust.ts`）：
1. Host 头解析 authority：必须 loopback hostname 或匹配 `trustedHosts`（`host[:port]` 精确；port-less 匹配任意端口）。
2. `sec-fetch-site: cross-site` → 拒绝。
3. 有 Origin 时必须等于该 authority（归一化比较）；无 Origin 可放行（Host 已绑定）。
顺序：Host 校验失败/未匹配 → 403；trusted 但无有效 cookie → 401。

### 4.2 浏览器会话认证（401 / cookie）
- 启动令牌：进程随机 launch token；`authenticatedUrl(base)` 给根 URL 加 `?token=...`；**仅在 `GET /`** 上接受（`authorizeIndex`）。
- 交换：`GET /?token=<launch>` 有效 → `Set-Cookie` + 302 到干净 `/`；cookie 无效/缺失/过期 → 401（分发前）。
- Cookie 形状（`browser-auth.ts`）：
  - 名：由规范化 authority 确定性派生（host+port 绑定）。
  - 值：`v1.<base64url(payload)>.<base64url(hmac)>`，payload=`{authority?, issuedAt, expiresAt, ...}`，HMAC 密钥=服务端持久 secret（archon：配置/密钥库）。
  - 属性：`Max-Age=<sec>; Path=/; Expires=<UTC>; HttpOnly; SameSite=Strict`（host-only；官方不加 Secure=loopback HTTP，TLS 反代需扩展）。
- 过期默认 30 天；吊销=删签名密钥/凭据记录。
- HTTP carrier 不接受 query token（除根交换）与 Authorization header。

## 5. Fetch 路由注册表（GET/HEAD 精确路由）

- host `ctx.connection.fetch.register({ path, methods:['GET'|'HEAD'], fetch(request)→Response })`；路径是 `/api` 之下的绝对路径。
- 共享通道分发：先精确 GET/HEAD 路由，再 RPC interceptor；都不属 → 404。
- 典型：Session-log 下载等非 JSON 响应（feature 包注册）。

## 6. settings Remote namespace（wire view）

官方 `settings` namespace（host `ctx.remote.settings`，`packages/api/settings-controller`）的视图（**读路径一律 redactSecrets:true**）：
```jsonc
{
  "ns": "<namespace>",
  "schema": <schemastery toJSON envelope>,   // 字段级 schema 树
  "value": <解析后值>,                        // 已 redact
  "base"?: <组合层>, "user"?: <用户层>,        // presence=覆盖标记 依据
  "applies": <生效时机>,
  "secrets": [ { "path": [...], "set": <是否已设置> } ],  // secret 元数据（无值）
  "revision": <写栅栏号>
}
```
- 写失败分类：`settings/conflict`（revision 过期）、`settings/rejected`（provider 拒绝）。
- `credentials` 是另一独立 namespace（浏览器签名密钥等，非业务设置）。
- client 镜像（`client/ui-settings/settings-contract.ts` `SettingsScopeSnapshot`）：status(value/base/user/revision/writable/mode:host|memory)。
- **archon 映射**：dsh-settings P3（user 层单文档+revision CAS+path ops+redact+presence）已对齐官方语义（见 design-settings-p3-path-cas.md），需补：schema 字段 wire 改官方 schemastery envelope 序列化、`base` 层回传、`mode`/`writable` 客户端态、namespace 名与 `secrets` 元数据形状。

## 7. 静态前端与 boot 注入

- 官方 `apps/web` 构建产物由 host 伺服；`index.html` 内注入：
  - `window.__DSH_BOOT__`：`BootManifest { version, plugins: [{ id, url, immediately? }] }`（另有 batch 阶段 bootstrap/application 与脚本分组，见 `client/modules/src/client/manifest.ts`）。
  - `window.__ModuleLoader__`：cordis-plugin-loader bootstrap（`load({id,factory})`）；worker preview 变体经 `__DSH_TRANSPORT__` 注入 bundle 字节。
- 平台静态表（`client/web/src/seed.ts`）：react / react-jsx-runtime / react-dom / react-dom/client / @deepseek-ai/cordis / dsh-client-store / dsh-client-ui-slots / dsh-client-ui-primitives 由外壳静态 import 同实例。
- 官方构建（`scripts/client-build-environment.ts` + apps/web vite）产出 manifest+bundles；**Java 只做静态伺服 + index 注入**，不参与 JS 装配。
- 登录首达：`GET /` 无 cookie → 需先经 `?token=` 交换（见 §4.2），因此 index 伺服须与 authorizeIndex 集成（无 token 且无 cookie 时 401 或跳转由产品定）。

## 8. archon 现状映射表（gap）

| 官方契约面 | archon 现有件 | gap |
|---|---|---|
| `/api/<endpoint>` unary | dsh-api `SettingsController/ToolsMetaController/...`（自有 REST 语义） | 需新 RPC 信封层（rpcId/type/result），将 archon 端点包装为 Remote namespace.method |
| `/api/remote.mux` | `SessionEventWebSocketHandler`（下行 `{type,sessionId,seq,event}` 帧） | 需改造为 open/cancel/item/end/error 逻辑流 + `$events` ready/emit + streamId 复用 |
| trust/auth | 无（自有接口无浏览器令牌） | 新增（§4 全量） |
| fetch routes | 无（REST 直接返回 JSON） | 按需注册 GET/HEAD 精确路由 |
| settings envelope | P3 redacted view + ops CAS（自有 wire） | 补官方 envelope 序列化 + base + mode/writable + secrets 形状（§6） |
| 静态前端 + boot 注入 | dsh-web Vue SPA（build.sh → webapp assets） | 新增官方 bundle 构建与伺服入口（并存/隔离） |

## 9. P0 遗留（下一步 P4 契约细化输入）

- [ ] client 消费面反推：各 ui-*/store 调用的 Remote namespace.method 全集与事件名全集（P0-3）。
- [ ] `$events` 转发事件清单落地 `remotes/src/remote-events.ts` `API_REMOTE_FORWARDED_EVENTS`（命令/凭据/llm/agent-presets/settings/user-approval/user-questions/session 等）→ Java 事件桥接表。
- [ ] Remote endpoint 命名：typert namespace.method 到 `/api/<endpoint>` 的映射规则确认（含 `$events/result` 特殊端点）。
- [ ] cookie secure/SameSite 在 archon TLS 反代下的扩展策略。
- [ ] 官方 bundle 构建产物清单与体积（决定伺服/缓存策略）。

## 10. 验收红线（P1/P2 spike 沿用）

- archon 全仓 `mvn verify` 571/0 不回退；新增 spike 单测独立成模块。
- wire 面与本文逐条一致（信封 exact-key、状态码、cookie 属性、帧 exact-key）。
- AgentLoop 读取零漂移（spike 不触碰既有行为）。
