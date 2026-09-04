# B 盘点：Remote 服务 ns 契约 + 转发事件签名

> 契约源：external/deepseek `packages/{api/session-controller, api/workspace-controller, context/session-reference, host/plugin-inventory, subagent/subagent, api/remotes}` + 各事件 owner 包。
> 服务 owner 均为 `TypertRemoteService` 子类，经 `ctx.remote.<ns>` 暴露；endpoint 命名 `ns/method`（`@Remote('m')`）或 `ns/方法名`（裸 `@Remote`）。
> 类型以源码 TS 为准；wire 上方法首参若为 `request`/`_request` 对象，则作为 payload 的 `args.request` 传输；其余标量（含 `agent`）按 `args` 字段传输（`agent` 序列化为 `agentId`）。`AbortSignal` 不跨 wire，由传输层承载为 caller lifetime。
> 不确定处已用“⚠”标注。

## ns session

owner：`packages/api/session-controller/src/index.ts` `SessionController`（`super(ctx,'sessionController',{namespace:'session'})`）。

> 说明：任务猜想里 session 下的 `archiveSession` 并不属于本 ns；`archiveSession` 实际在 **workspace** ns（见下）。session ns 无该方法。
> `list/search/modelCatalog/canOpenWorkspacePath` 等为冷读（不唤醒 Agent）；`create/selectModel/rename/fork/prompt/attachment/updateQueue/cancel` 为显式 Agent 命令；`page/follow/control` 为历史/控制流。

### 端点

- `session.list(_request?: SessionListRequest, signal)` → `{ items: SessionSummary[] }`
  - 参数 `_request: { cursor?: string }`（保留/预留空）。
  - 返回元素 `SessionSummary`：`{ sessionId, updatedAt:number, running:boolean, blank:boolean, parentSessionId?:SessionId, origin?:'subagent', cwd?:string, projections?: SessionProjectionHints }`；`projections` = `{ asOfSeq:number, values: Partial<SessionProjectionMap> }`。
- `session.search(request: { query:string }, signal)` → `{ items: SessionSearchItem[], hasMore:boolean }`
  - `SessionSearchItem = { sessionId, snippet:string }`；上限 20 条、snippet ≤240 码点。
- `session.create(request: SessionCreateRequest)` → `{ sessionId, agentPreset?:string }`
  - request：`{ workspaceId?:WorkspaceId, cwd?:string, sessionId?:SessionId, agentPreset?:string }`（idempotent adopt，或新建）。
- `session.selectModel(request: SessionSelectModelRequest)` → `{ selected: ModelSelection }`
  - request extends ModelSelection：`{ sessionId, provider:string, model:string, reasoningEffort?:string }`；先显式 resume 会话。
- `session.modelCatalog()` → `ModelCatalog`
  - `{ default:ModelSelection, routableProviders:string[], groups:ModelProviderGroup[], failures:ModelCatalogFailure[] }`；group=`{id,name,models:[{id,name,description?,reasoning?}]}`。
- `session.canOpenWorkspacePath()` → `boolean`（裸 `@Remote`，方法名即 endpoint）
- `session.openWorkspacePath(request: { path:string }, signal)` → `{ opened:true }`
  - path 空 → `gateway/bad-request`；abort → `gateway/cancelled`；opener 失败 → `gateway/internal`。
- `session.rename(request: { sessionId, title:string })` → `{ title:string, seq:number }`
- `session.fork(request: { sessionId, atSeq?:number })` → `{ sessionId }`（fork 完成的冷读前缀）
- `session.prompt(request: SessionPromptRequest, signal)` → `{ accepted:true }`（admission 前 abort 生效）
  - request：`{ requestId:SessionRequestId, sessionId, mode:'queue'|'steer', content: PromptContentPart[], clientTimeZone?:string }`；`PromptContentPart = {type:'text',text}|{type:'image',mediaType,data,name?}`。
- `session.attachment(request: { sessionId, attachmentId })` → `{ attachment: ImageAttachmentRef, data:string }`（base64 字节）
- `session.updateQueue(request: SessionUpdateQueueRequest)` → `{ accepted:true }`（同步返回）
  - request：`{ sessionId, itemId:MessageId, action: QueueAction }`；`QueueAction={kind:'edit',content}|{kind:'remove'}|{kind:'steer'}`。
- `session.cancel(request: { sessionId })` → `{ accepted:true }`（同步返回）
- `session.page(request: SessionPageRequest, signal)` → `SessionPage`（unary 历史页）
  - request：`{ address: SessionAddress, throughSeq:number, beforeSeq?:number, maxMessages?:number }`。
  - `SessionAddress = {kind:'session',sessionId} | {kind:'subagent',parentSessionId,childSessionId,mode:'one-shot'|'continuable'}`。
  - `SessionPage = { records: SessionHistoryRecord[], hasMore:boolean }`；record = raw `SessionEventEntry` 或 packed `SessionChunkRun`。
- `session.follow(request: SessionFollowRequest, signal)` → **stream** `AsyncIterable<SessionFollowFrame>`（`@Remote({mode:'stream'})`）
  - request：`{ address:SessionAddress, maxMessages?:number }`。
  - frame = `{type:'snapshot', header:SessionWireHeader, cursor, records, hasMore, projections}` | `{type:'event', event:SessionWireEvent}`。
- `session.control(signal)` → **stream** `AsyncIterable<SessionControlFrame>`（`@Remote({mode:'stream'})`）
  - frame = `{type:'baseline',value:SessionControlBaseline}` | `{type:'queue',sessionId,items}` | `{type:'jobs',sessionId,jobs}` | `{type:'projection',sessionId,key,value,seq}`。

### 错误码（session ns 相关，见 types.ts RemoteErrorDetailsMap 与实现）
- `gateway/bad-request`：payload 非法（如 openWorkspacePath 空 path）。
- `gateway/cancelled`：caller abort（prompt/openWorkspacePath 前 abort、follow/control 流取消）。
- `gateway/internal`：基础设施/未知失败、skill 目录读失败等。
- `session/not-found`：目标会话无法观察（skills.list 场景）。
- `session/model-unavailable` {provider,model}；`session/conflict` {sessionId,requestedCwd,existingCwd?}；`session/agent-busy` {reason}；`session/invalid-time-zone` {value}；`session/workspace-attach-failed` {sessionId,workspaceId}。
- `agent-preset/conflict` {sessionId,requestedPreset,existingPreset?}。
- `session/attachment-invalid` {reason}；`session/queue-item-not-found` {itemId}；`session/steer-unavailable` {itemId}；`session/title-invalid` {sessionId}；`session/fork-unavailable` {sessionId}。
- `subagent/not-found` {parentSessionId,childSessionId}；`subagent/catalog-diagnostic` {parentSessionId,childSessionId,reason:'corrupt'|'unsupported'|'unavailable'}。

## ns fileReferences

owner：`packages/api/session-controller/src/file-references.ts` `SessionFileReferences`（`namespace:'fileReferences'`）。

- `fileReferences.list(agent, query:string, signal)` → `FileReferenceCandidate[]`（裸 `@Remote`）
  - wire 首参 `agent:Agent` 序列化为 `agentId`（见 fixture `case 'fileReferences/list'`）。
  - `query`：`@` 或 `@"` 后的路径文本。
  - 返回元素 `FileReferenceCandidate`（`@deepseek-ai/dsh-file-reference/types`）：path-only 确定性候选。⚠ 精确字段未在本包展开，取自 `dsh-file-reference` 声明。

## ns skills

owner：`packages/api/session-controller/src/skill-catalog.ts` `SessionSkillCatalog`（`namespace:'skills'`）。

- `skills.list(request: { sessionId }, signal)` → `{ skills: SkillEntry[] }`（裸 `@Remote`）
  - `SkillEntry = { name, description, whenToUse?:string, modelInvocable:boolean }`；仅 user-invocable，不载入 body。
  - 会话不可观察 → `session/not-found` {sessionId}；无 cwd/无 registry/读失败 → `gateway/internal`。

## ns workspace

owner：`packages/api/workspace-controller/src/index.ts` `WorkspaceController`（`namespace:'workspace'`）。

- `workspace.create(request: { path:string })` → `{ workspace:WorkspaceView, created:boolean }`
- `workspace.rename(request: { workspaceId, title:string })` → `{ workspace:WorkspaceView }`
- `workspace.delete(request: { workspaceId })` → `{ deleted:true }`
- `workspace.insertBefore(request: { workspaceId, beforeWorkspaceId? })` → `{ workspaceIds: WorkspaceId[] }`
- `workspace.insertSessionBefore(request: { workspaceId, sessionId, beforeSessionId? })` → `{ workspace:WorkspaceView }`
- `workspace.archiveSession(request: { sessionId })` → `{ archivedSessionIds: SessionId[] }`
- `workspace.follow(signal)` → **stream** `AsyncIterable<WorkspaceFollowFrame>`（`@Remote({mode:'stream'})`）
  - frame = `{type:'baseline',value:WorkspaceBaseline}` | increment：`{type:'upsert',workspace}` | `{type:'remove',workspaceId}` | `{type:'order',workspaceIds}` | `{type:'archived',archivedSessionIds}`；每代先一个 baseline。

`WorkspaceView = { workspaceId, path, title, sessionIds:SessionId[], createdAt:string, updatedAt:string }`（ISO-8601）。

错误码：`workspace/invalid-path` {path}、`workspace/name-conflict` {name}、`workspace/move-invalid` {workspaceId,sessionId,beforeSessionId?}（另 fixture 见 `workspace/not-found`，⚠ 未在 RemoteErrorDetailsMap 中显式列出）。

## ns directoryPicker

owner：`packages/api/workspace-controller/src/directory-picker.ts` `DirectoryPickerController`（`namespace:'directoryPicker'`）。能力门控：`pick` 需 `native` 能力、`list/createDirectory` 需 `browse` 能力；不匹配 → `directory-picker/unavailable`。

- `directoryPicker.pick(signal)` → `string | null`（null = 操作者取消）
- `directoryPicker.list(path: string|undefined, signal)` → `DirectoryListing`（path 缺省列 home）
- `directoryPicker.createDirectory(path:string, name:string)` → `string`（新目录绝对路径）

错误码：`gateway/bad-request`（payload 校验，createDirectory 非法 name）、`gateway/cancelled`（abort）、`directory-picker/unavailable` {capability}、`directory-picker/unreadable` {path}、`directory-picker/exists` {path}、`directory-picker/create-failed` {path}、`gateway/internal`。`DirectoryListing/DirectoryEntry` 类型取自 `@deepseek-ai/dsh-host-directory-picker/types`。

## ns sessionReferenceResolver

owner：`packages/context/session-reference/src/index.ts` `SessionReferenceResolver`（`super(ctx,'sessionReferenceResolver')`，无 namespace 选项 → 默认即服务名）。

- `sessionReferenceResolver.candidates(agent, query:string, signal)` → `SessionReferenceMentionCandidate[]`（`@Remote('candidates')`，方法名 `remoteExportCandidates`）
  - wire 首参 `agent:Agent` 序列化为 `agentId`（fixture `case 'sessionReferenceResolver/candidates'` 用 `sessionId`）。
  - `query`：可选、大小写不敏感、匹配 sessionid/cwd/title 子串；`''` 返回全部。
  - 候选上限为配置 `candidateLimit`（默认 `DEFAULT_CANDIDATE_LIMIT`）。
  - 返回元素 = `SessionReferenceCandidate` + `mention:string`（`@[label](dsh-session:…)`）：
    `{ sessionId, label, cwd?, sameWorkspace:boolean, createdAt:number, mention }`。
  - 客户端（`packages/client/ui-reference`）仅消费 `remote.sessionReferenceResolver.candidates`。

## ns pluginInventory

owner：`packages/host/plugin-inventory/src/index.ts` `PluginInventoryGateway`（`super(ctx,'pluginInventory')`，无 namespace → 服务名）。

- `pluginInventory.list()` → `PluginInventorySnapshot`（`@Remote('list')`，无参数）
  - `{ entries: PluginInventoryEntry[], agentPresets?: AgentPresetPluginGroup[] }`（agentPresets 仅当装配 presets roster 时出现）。
  - `PluginInventoryEntry = { entryId, moduleName, enabled:boolean, fiberPhase:PluginFiberPhase }`；`fiberPhase='pending'|'loading'|'active'|'failed'|'unloading'|null`。
  - `AgentPresetPluginGroup = { id, trust:'system'|'user', name?, isDefault:boolean, broken?, rows:[{entryId:string|null, moduleName, enabled:boolean|'conditional', condition?, fiberPhase}] }`。

## ns subagents

owner：`packages/subagent/subagent/src/index.ts` `SubagentRuntime`（`super(ctx,'subagents')`，无 namespace → 服务名）。浏览器控制面三个 `@Remote`：

- `subagents/list(parentSessionId: SessionId, signal)` → `SubagentCatalog`（`@Remote('list')`，方法名 `remoteExportList`）
  - `{ entries: SubagentListEntry[], parentAvailable:boolean }`。
  - `SubagentListEntry`：`{kind:'child', id, activity:'running'|'inactive', hasChildren, mode:'one-shot',label?}` 或 `{kind:'child',…,mode:'continuable',label}` 或 `{kind:'diagnostic',id,reason:'corrupt'|'unsupported'|'unavailable'}`。
- `subagents/prompt(request: SubagentPromptRequest, signal)` → `{ messageId: MessageId }`（`@Remote('prompt')`）
  - request：`{ requestId:SubagentPromptRequestId, parentSessionId, childSessionId, mode:'continuable', content:PromptContentPart[], clientTimeZone? }`。
- `subagents/interruptByParent(childSessionId, parentSessionId, mode:'continuable')` → `{ accepted:true }`（`@Remote('interruptByParent')`，同步）

错误码：`gateway/bad-request`、`gateway/cancelled`、`gateway/internal`、`subagent/projections-unavailable` {}、`subagent/invalid-time-zone` {value}、`subagent/parent-unavailable` {parentSessionId}、`subagent/not-resumable` {childSessionId}、`subagent/unauthorized` {childSessionId}、`subagent/attachment-invalid` {reason}、`subagent/delivery-unavailable` {childSessionId}。

> 注：`subagents` 另有 provider 级 `list(): string[]`（非 Remote）、`start/startContinuable/sendMessage/interrupt/listChildren/listDescendants` 等 host 内部方法，均非浏览器 wire 端点。

---

## 转发事件签名

权威 allowlist：`packages/api/remotes/src/remote-events.ts` `API_REMOTE_FORWARDED_EVENTS`（18 项，无重命名转发；同时是该应用 `ctx.remote.$on` 的合法 key 集）。声明处见各 owner 包 `declare module '@deepseek-ai/cordis' { interface Events }`。

### 会话（api-session/*）— owner `session-controller/src/types.ts`（events emit 于 index.ts）
- `api-session/added(summary: SessionSummary): void` — emit；会话对 list 消费者可见；`SessionSummary` 见上 session.list 元素。
- `api-session/removed(sessionId: SessionId): void` — emit；会话离开 live 注册表。
- `api-session/status(sessionId: SessionId, running: boolean): void` — emit；Agent 运行态变化（`agent/status`）。
- `api-session/activity(sessionId: SessionId, updatedAt: number): void` — emit；用户消息推进活动序（用于 list 排序）。
- `api-session/error(sessionId: SessionId, message: string): void` — emit；Agent 在持久化回合位置外失败（user-safe 错误链 `errorChain`）。

### 设置 / 命令 / 凭据 / LLM
- `settings/document-updated(ns: SettingsNamespace, revision: number): void` — emit；owner `packages/settings/settings/src/types.ts`；某注册 ns 的 RAW user section 变更（含继承→覆盖语义变化），`revision` 为新修订号。`SettingsNamespace = Branded<'SettingsNamespace'>`。
  - 相关：`settings/updated(ns, next:unknown, prev:unknown, source:'update'|'provider')`（未在转发 allowlist，仅记录）。
- `commands/change(): void` — emit；owner `packages/interaction/commands/src/types.ts`；命令注册/注销（unfiltered registry 通知）。
- `credentials/reference-updated(ref: CredentialRef): void` — emit；owner `packages/credentials/credentials/src/types.ts`；provider 管理凭据源的提交变更（set/unset/外部存储编辑）。`CredentialRef = Branded<'CredentialRef'>`。同文件另有 `credentials/record-updated(key: CredentialKey)`（不在转发集）。
- `llm/adapters-updated(): void` — emit；owner `packages/llm/llm/src/types.ts`；adapter 拓扑变更。

### Agent preset
- `agent-preset/selected(sessionId: SessionId, agentPreset: string): void` — emit；owner `packages/preset/agent-presets/src/types.ts`（emit 于 index.ts `agent-preset/selected` 事件）。

### 交互（waterfall，scope-filtered）
- `approval/request(this: Scoped<Agent>, req: ApprovalRequestEvent, next: () => Promise<ApprovalOutcome>): Promise<ApprovalOutcome>` — **waterfall**；owner `packages/interaction/user-approval/src/types.ts`。
  - `ApprovalRequestEvent = { agent:Agent, toolName:string, callId?:ToolCallId, reason?:string, signal?:AbortSignal }`。
  - `ApprovalOutcome = 'allowed-once'|'rejected'|'cancelled'|'unavailable'`。
- `user-questions/request(this: Scoped<Agent>, request: AskUserQuestionRequestEvent, next: () => Promise<AskUserQuestionAnswer>): Promise<AskUserQuestionAnswer>` — **waterfall**；owner `packages/interaction/user-questions/src/types.ts`。
  - `AskUserQuestionRequestEvent = { questions: AskUserQuestionItem[], agent?:Agent, signal?:AbortSignal }`；`AskUserQuestionItem` 含 `id/question/header?/options?/multiSelect?/intent?`。
  - `AskUserQuestionAnswer = { answers: AskUserQuestionAnswerItem[] }`；item=`{id, selected:string[], custom?}`。

### cordis/*（动态宿主运行）— owner `packages/extensions/cordis-host-runner/src/types.ts`
- `cordis/request-run(request: DynamicCordisRunRequest): void` — emit；`DynamicCordisRunRequest={requestId:ApprovalRequestId, agentId:SessionId, pluginId, packageId, mode:'run'|'update', name, purpose, requiresApproval:boolean}`。
- `cordis/request-run-resolved(resolved: DynamicCordisRequestResolved): void` — emit；`{requestId, outcome:'approved'|'completed'|'rejected'|'cancelled'|'failed'}`。
- `cordis/dynamic-package(pkg: DynamicCordisPackage): void` — emit；`{pluginId, packageId, pluginRunId, name}`。
- `cordis/dynamic-retract(retracted: DynamicCordisRetracted): void` — emit；`{pluginId, packageId, pluginRunId}`。
- `cordis/inspect-query(request: CordisInspectQueryRequest): void` — emit；`{requestId, agentId:SessionId, provider, method, input?:JsonValue}`。
- `cordis/inspect-query-resolved(resolved: CordisInspectQueryResolved): void` — emit；`{requestId}`。

---

## 附注
- `@Remote({mode:'stream'})` 端点（session.follow/control、workspace.follow）走 WS 流通道；其余 unary 走 HTTP POST `/api/<ns>/<method>`。
- 各 ns 方法若声明 `signal: AbortSignal` 参数即支持 caller 取消；abort 语义多映射为 `gateway/cancelled`。
- ⚠ 不确定：`FileReferenceCandidate` 精确字段与 `workspace/not-found` code 的显式声明位置未在本包内核对（前者在 `dsh-file-reference`、后者仅见 fixture 用法）；`SettingsNamespace`、`CredentialRef` 均为 branded string，wire 上为普通字符串。
