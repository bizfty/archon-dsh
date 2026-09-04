# Remote 服务 ns 方法契约盘点（survey-a）

> 来源：`external/deepseek` 官方 TS 源（只读，未改动）。
> 约定：远程方法 = 类上带 `@Remote`（可带标签）的方法。`@Remote('x')` 表示 Host 侧方法名与 client 调用名不同，client 调用名为标签 `x`。无标签 `@Remote` 时方法名即 client 调用名。
> 通用于所有跨 wire 方法：`Agent`、`SessionId`、`Signal`（AbortSignal）等 Host 内建对象由 Remote carrier 编码/解析，参数表里的 `agent` 为从 wire 身份解析出的“精确 live Agent”。

---

## ns settings

- Host owner：`SettingsController extends TypertRemoteService`，`super(ctx,'settingsController',{namespace:'settings'})`（`packages/api/settings-controller/src/index.ts`）。同一包还挂载 `credentials` ns（见下节，独立 plugin）。
- 端点形态：`settings.describe` / `settings.canOpenAgentPresetDirectory` / `settings.update` / `settings.replace` / `settings.mutate` / `settings.openSettingsDocument` / `settings.openAgentPresetDirectory`（方法名即 client 调用名，均无标签 `@Remote`）。
- 所有读取使用 `redactSecrets:true`，`role('secret')` 字段永不随响应返回。
- 类型（wire 视图）定义在 `@deepseek-ai/dsh-settings/types` 与 `@deepseek-ai/dsh-api-settings-controller/types`。

### 方法

- `settings.describe(): SettingsDescribeValue`
  - 返回：`{ writable: boolean, hasDocument: boolean, namespaces: SettingsNamespaceView[] }`
    - `writable`：provider 是否接受写；`false` 关闭所有写控件。
    - `hasDocument`：文件型 provider 是否拥有本地文档（不暴露 Host 路径）。
    - `namespaces`：每个已注册 ns 一个视图。
  - SettingsNamespaceView（每项）：`{ ns: string, schema: JsonValue /* schema.toJSON()，可 new Schema(json) 复原 */, value: JsonValue /* redacted resolved（默认→base→user）*/, base?: JsonValue /* redacted composition base，仅注册者声明时 */, user?: JsonValue /* redacted 原始 user section，存在时其字段标记为 user-overridden */, applies: 'live'|'restart', secrets: SettingsSecretView[] /* {path:string[],set:boolean} */, revision: number /* 单调；回传为 expectedRevision */ }`
  - 错误：无 settings provider 挂载时抛 `RemoteError`（`gateway/internal`）。

- `settings.canOpenAgentPresetDirectory(): boolean`
  - 返回：本部署能否原生打开 authoring 的 Agent preset 目录（true 表示对应打开操作可用）。

- `settings.update(ns: string, patch: Record<string,JsonValue>, expectedRevision: number|undefined): Promise<SettingsNamespaceView>`
  - 语义：把 patch 合并进某 ns 存储的 user section。
  - `expectedRevision`：`undefined` 无条件写；否则 stale writer 被拒。
  - 返回：写后该 ns 的 redacted 视图（结构同 describe 项）。
  - 错误码：`gateway/bad-request`（ns 非法：zod `z.string().min(1)`）、`settings/conflict`、`settings/rejected`、`gateway/internal`（provider 缺失或写后 ns 被并发释放）。

- `settings.replace(ns: string, section: Record<string,JsonValue>, expectedRevision: number|undefined): Promise<SettingsNamespaceView>`
  - 语义：整体替换某 ns 的 user section。返回/错误同 update。

- `settings.mutate(ns: string, ops: SettingsPathOpView[], expectedRevision: number|undefined): Promise<SettingsNamespaceView>`
  - 语义：按序应用 path 寻址编辑，基于“存储时的 section”解析（而非 caller 上次读的），再返回新视图。
  - SettingsPathOpView：`{op:'set', path:string[], value:JsonValue} | {op:'unset', path:string[]}`。空 path 指 section 根。
  - 返回/错误同 update。

- `settings.openSettingsDocument(signal: AbortSignal): Promise<SettingsDocumentOpenValue>`
  - 语义：物化 provider 持有的 settings 文档并交给原生文本编辑器。
  - 返回：`SettingsDocumentOpenValue = { opened: true }`（确认已交给原生编辑器）。
  - 错误码：`gateway/cancelled`（signal 中止）、`gateway/internal`（无本地文档 / 准备失败 / 打开失败）。注意本方法错误码为 `gateway/*`，不是 `settings/*`。

- `settings.openAgentPresetDirectory(agentPreset: string, signal: AbortSignal): Promise<AgentPresetDirectoryOpenValue>`
  - 语义：打开一个 user-authored Agent preset 目录；无原生 opener 时返回其路径供文本展示。
  - 返回：`AgentPresetDirectoryOpenValue = {opened:true} | {opened:false, path:string}`。
  - 错误码：`gateway/bad-request`（空 id）、`agent-preset/not-found`（无 agentPresets 服务）、`agent-preset/read-only`（preset 非 user trust，随部署提供）、`gateway/cancelled`、`gateway/internal`。

### RemoteError code 全集（settings 域，见 types.ts RemoteErrorDetailsMap）

- `settings/rejected`: `{ ns: string }` —— 非 stale-write 的一切 seam 拒绝（未注册/畸形 ns、只读 provider、schema 校验、存储）。
- `settings/conflict`: `{ ns: string, expected: number, actual: number }` —— caller 读后存储 revision 已变；是独立结果而非非法请求，caller 须重读重应用。
- `credential/rejected`: `{ ref: string }` ——（credentials 域，见下节；此处列出因同文件声明）。
- 另见方法内联的 `gateway/bad-request`、`gateway/internal`、`gateway/cancelled`、`agent-preset/*`。

---

## ns credentials

- Host owner：`CredentialsController extends TypertRemoteService`，`super(ctx,'credentialsController',{namespace:'credentials'})`（`packages/api/settings-controller/src/credentials.ts`），由 SettingsController 构造时 `ctx.plugin(CredentialsController)` 挂载。
- 端点形态：`credentials.describe` / `credentials.set` / `credentials.unset`（方法名即 client 调用名）。
- 参考名语法（zod）：`/^[A-Za-z_][A-Za-z0-9_]*$/`（`credentialRefSchema`）；越界即整批 `gateway/bad-request`。

### 方法

- `credentials.describe(refs: string[]): Promise<Record<string, CredentialInfo>>`
  - 语义：批量描述若干参考。批上限 `MAX_DESCRIBE_REFS = 64`；`refs` 超上限或含语法外名字 → 整批 `gateway/bad-request`。
  - 返回：以请求名为键、每键一个 `CredentialInfo` 视图。CredentialInfo 字段由 `projectCredentialInfo` 裁剪为：`{ configured: boolean, source?: …, writable: boolean }`（configured/writable 恒在，source 仅 provider 声明时）。secret 值永不返回。
  - 错误码：`gateway/bad-request`、`gateway/internal`（无 credential provider）。

- `credentials.set(ref: string, value: string): Promise<void>`
  - 语义：从配置面存储一个值（仅此方向携带 secret；读路径永不返回）。
  - 校验：`ref` 语法、`value` 非空（`z.string().min(1)`）。
  - 错误码：`gateway/bad-request`、`credential/rejected`（provider 拒绝，如只读源遮蔽；details 只含 `{ref}`，永不携带值）。

- `credentials.unset(ref: string): Promise<void>`
  - 语义：移除一个参考。
  - 错误码：`gateway/bad-request`、`credential/rejected`（`{ref}`）。

---

## ns llm

- Host owner：`LlmRuntime extends TypertRemoteService`，`super(ctx,'llm')`（`packages/llm/llm/src/index.ts`）。
- 端点形态（client 调用名）：`llm.listProviders` / `llm.listConfigurableProviders` / `llm.discoverModels`。
  - Host 侧实现方法名为 `remoteDiscoverModels`，标注 `@Remote('discoverModels')` → client 调用名 = `discoverModels`。
  - `listProviders`、`listConfigurableProviders` 无标签，方法名即调用名。
- 注：`discoverModels`（无 Remote 标注的纯 Host 方法）与 `remoteDiscoverModels`（@Remote）并存 —— Remote 暴露的是后者；`listModels`/`resolveModelInfo`/`resolveCallConfig`/`prepareCall`/`stream` 等均非 @Remote（Host 内部/本地调用），不在本盘点。

### 方法

- `llm.listProviders(): LlmProviderInfo[]`
  - 返回：按注册顺序的 detached provider 元数据。`LlmProviderInfo = { id: string, name: string }`（id 即 provider route key）。

- `llm.listConfigurableProviders(): LlmConfigurableProvider[]`
  - 返回：按声明顺序的 detached 目录条目。`LlmConfigurableProvider = { provider: string, displayName: string, settingsNs: string, settingsPath: readonly string[], declared?: boolean }`。
    - `settingsPath`：从该 ns section 根到该 provider profile 对象的路径；空 = 整个 section 即 profile。
    - `declared` 缺失 = adapter 不区分；false = 该 route 是 adapter 自己的。

- `llm.discoverModels(settingsNs: string, request: LlmModelDiscoveryRequest, signal: AbortSignal): Promise<LlmDiscoveredModel[]>`
  - 语义：为 draft provider 询问一个端点所广告的模型（不读写 settings/credentials；返回候选元数据供 surface 采纳）。按端点顺序去重（重复/空 id 丢弃）。
  - `LlmModelDiscoveryRequest`：`{ provider?: string, baseURL?: string, api?: string, apiKey?: string }`。draft 须至少给出 provider route 或 baseURL 其一（否则 Host 抛 `INVALID_DISCOVERY`）。
  - 返回：`LlmDiscoveredModel[]`，`LlmDiscoveredModel = { id: string, name?: string, contextWindow?: number, maxTokens?: number }`（仅 id 必填）。
  - 错误码（@Remote 包装）：`llm/model-discovery-rejected`（discovery 拒绝或失败），details `{ settingsNs: string, baseURL?: string }`。底层未注册 discovery 抛 LlmError `NO_DISCOVERY`；无 provider 且无 baseURL 抛 `INVALID_DISCOVERY`。

### 依赖目录类型（精简，见 `packages/llm/llm/src/types.ts`）

- provider route：`LlmProviderInfo = { id, name }`。
- configurable provider：见上 `LlmConfigurableProvider`。
- discovered model：见上 `LlmDiscoveredModel`。
- （models 目录非本 ns 方法直接返回，供参考）`LlmModelInfo`/`LlmResolvedModelInfo` 为 adapter 端的 model listing/resolution 形状，Remote 面仅通过上述三个方法暴露，未直接 wire 完整 model JSON。

---

## ns agentPresets

- Host owner：`AgentPresets extends TypertRemoteService`，`super(ctx,'agentPresets')`（`packages/preset/agent-presets/src/index.ts`）。
- 端点形态（client 调用名 ← Host 方法）：
  - `agentPresets.list` ← `remoteExportList`（`@Remote('list')`）
  - `agentPresets.read` ← `readDocument`（`@Remote('read')`）
  - `agentPresets.copy` ← `remoteExportCopy`（`@Remote('copy')`）
  - `agentPresets.deletePreset` ← `remoteExportDelete`（`@Remote('deletePreset')`）
  - `agentPresets.select` ← `select`（`@Remote('select')`）
- 注：Host 侧另有非 @Remote 的内部方法 `list()/read()/copy()/remove()/resolve()/mount()/composeFrom()/recompose()` 等，不暴露给 client。`remoteExport*` 命名是“client 调 copy/deletePreset/list/read/select”的 Host 实现载体（@Remote 标签决定 client 调用名）。

### 方法

- `agentPresets.list(): Promise<AgentPresetRoster>`
  - 语义：Host 侧 roster（`list()`）投影为 path-free 行，标记 default，附 authoring 能力。
  - 返回：`AgentPresetRoster = { presets: AgentPresetRow[], authorable: boolean }`。
    - `AgentPresetRow`：`{ id: string, trust: PresetTrust, isDefault: boolean, name?: string, description?: string, broken?: string }`（`PresetTrust: 'system'|'user'`；`broken` 为此 preset 无法 compose 的原因，可 compose 时缺失）。
    - `authorable`：该部署是否存在 user-authoring 根。

- `agentPresets.read(agentPreset: string): Promise<AgentPresetDocument>`
  - 语义：读某 preset 的 composition 文本（如存储原样）。
  - 返回：`AgentPresetDocument = { agentPreset: string, trust: PresetTrust, content: string /* composition 原文 */, name?: string, description?: string }`。
  - 错误码：`gateway/bad-request`（空 id）、`agent-preset/not-found`。

- `agentPresets.copy(from: string, id: string, name?: string): Promise<void>`
  - 语义：复制现有 preset 整目录为本地 authored preset（copy 是唯一 authoring 写；source 目录按原样复制，不跨 wire 传 composition 文本）。
  - 校验：`from`、`id` 非空；`id` 已被任一 root 供应 → refused。
  - 错误码：`gateway/bad-request`、`agent-preset/not-found`（source 未知）、`agent-preset/invalid`（id 不可用/已占用/无 writable root）。

- `agentPresets.deletePreset(id: string): Promise<void>`
  - 语义：删除本地 authored preset。若被删 preset 恰为 settings 默认，则同时 unset 该默认（回落到部署默认）。
  - 错误码：`gateway/bad-request`、`agent-preset/not-found`、`agent-preset/read-only`（随部署提供的 preset 不可删）。

- `agentPresets.select(agent: Agent, agentPreset: string): Promise<string>`
  - 语义：把 blank session 的 agent 从另一 preset 组合并记录。逐 session 串行化（防并发 select 双写）。
  - 返回：记录的 preset id。
  - 错误码：`gateway/bad-request`（空 id）、`agent-preset/locked`（session 已开始对话，组合固定）、`agent-preset/not-found`、`agent-preset/invalid`。
  - 注：`select` 参数表含 `agent: Agent`（wire 解析出的精确 live agent）+ `agentPreset: string`。

### RemoteError code 全集（agent-presets 域，types.ts RemoteErrorDetailsMap）

- `agent-preset/not-found`: `{ agentPreset: string, available: readonly string[] }`
- `agent-preset/invalid`: `{ agentPreset: string, reason: string }`（id 不可用/已占用/组合无法安装）
- `agent-preset/read-only`: `{ agentPreset: string, reason: string }`（随部署提供，非 user 可改）
- `agent-preset/locked`: `{ sessionId: SessionId, agentPreset: string }`（对话已开始，组合固定）

---

## ns commands

- Host owner：`CommandRuntime extends TypertRemoteService`，`super(ctx,'commands')`（`packages/interaction/commands/src/index.ts`）。
- 端点形态：`commands.list` / `commands.execute`（方法名即 client 调用名，均无标签 `@Remote`）。

### 方法

- `commands.list(agent: Agent): readonly CommandDescriptor[]`
  - 语义：列出一 agent 的有效不可变命令描述（scoped shadow 后按名排序）。
  - 参数：`agent: Agent`（wire 解析的精确 agent；既是接收者又是 scope-layer key）。
  - 返回：`CommandDescriptor[]`（name 排序，冻结）。
    - `CommandDescriptor = { name: string, description: string, input?: CommandInputDescriptor }`。
    - `CommandInputDescriptor = { hint: string, images?: boolean }`（images=true 才接受随行图像附件）。

- `commands.execute(agent: Agent, line: string, images: readonly EncodedImageAttachment[], signal: AbortSignal): Promise<CommandExecution | undefined>`
  - 语义：解析并执行一条已知 slash 命令，不发给 model。生命周期记入 session log：handler 前 `command/run`、settle 后 `command/done`（thrown/aborted handler settle 为 `kind:'error'`）。语法或未知名 → 返回 `undefined`（不记日志）。
  - 参数：
    - `agent: Agent` —— 精确接收 agent。
    - `line: string` —— 完整 slash 命令行（`parseCommand` 解析出 name + rawInput）。
    - `images: readonly EncodedImageAttachment[]` —— base64 composer 图像，随行顺序；空 = 纯文本调用。仅当命令声明 `input.images` 且存在 attachment store 才 admitted；否则 settle 为 `kind:'error'`。
    - `signal: AbortSignal` —— UI 请求取消信号（handler 前、admission 期间均须 honor）。
  - 返回：`CommandExecution | undefined`。
    - `CommandExecution = { commandId: CommandId, result: CommandResult }`（commandId 为 `command/run`↔`command/done` 配对 id）。
    - `CommandResult = { kind:'success', text?: string, sourceEventSeq?: SessionSeq } | { kind:'error', text: string }`（success 的 text/sourceEventSeq 可选；error 的 text 非空）。
  - 错误：signal 已中止 → 抛 abortError（非 RemoteError，carrier 层处理）。handler thrown → 抛原错误（先补记 `command/done` error）。

---

## ns goals

- Host owner：`GoalService extends TypertRemoteService`，`super(ctx,'goals')`（`packages/goal/goal/src/index.ts`）。
- 端点形态（client 调用名 ← Host 方法）：
  - `goals.edit` ← `edit`（`@Remote('edit')`）
  - `goals.pause` ← `pause`（`@Remote('pause')`）
  - `goals.resume` ← `resume`（`@Remote('resume')`）
  - `goals.complete` ← `complete`（`@Remote('complete')`）
  - `goals.clear` ← `clear`（`@Remote('clear')`）
  - `goals.create` ← `remoteExportCreate`（`@Remote('create')`）
- 注：任务描述中“list?/edit/pause/resume/clear/complete/remoteExportCreate” —— **GoalService 无 `list` 方法**（无 @Remote 亦无本地 list）。读取当前 goal 走 Host 本地 `get(agent)`（非 @Remote，不暴露）。client 侧（依任务）调 edit/pause/resume/clear（另 create 经 remoteExportCreate 暴露）。Host 另有非 @Remote 的 `create()/block()/get()/disarm()` 等内部方法，不暴露给 client（`block` 无 @Remote 标注）。
- 参数表通用：`agent: Agent`（wire 解析的精确 live agent；`assertLive` 校验 `ctx.agents.get(agent.id)===agent`）、`ref: GoalRef`（CAS：`{ id: GoalId, revision: number }`，须匹配当前，否则 stale 拒绝）。

### 方法

- `goals.edit(agent: Agent, ref: GoalRef, request: EditGoalRequest): GoalView`
  - `EditGoalRequest = { objective?: string, maxGoalRounds?: number }`（至少一个字段必填，否则 `GOAL_INVALID_EDIT`）。
  - 语义：改 objective 与/或 round cap，不改 phase。revision+1。
  - 返回：`GoalView`（见下）。

- `goals.pause(agent: Agent, ref: GoalRef): GoalView`
  - 语义：active→paused 并 disarm 自动续跑。

- `goals.resume(agent: Agent, ref: GoalRef): GoalView`
  - 语义：active/paused/blocked→active 并 arm；round budget 已耗尽则拒绝（须先提高 maxGoalRounds）。

- `goals.complete(agent: Agent, ref: GoalRef): GoalView`
  - 语义：active/paused/blocked→complete 并 disarm。

- `goals.clear(agent: Agent, ref: GoalRef): GoalRef`
  - 语义：清除当前 goal 并保留 tombstone + 历史。
  - 返回：tombstone ref `{ id, revision: 当前revision+1 }`（非 GoalView）。

- `goals.create(agent: Agent, request: CreateGoalRequest): CreateGoalResult`（Host 方法 `remoteExportCreate`）
  - `CreateGoalRequest = { objective: string, maxGoalRounds?: number }`；缺省 cap 由服务配置默认（`defaultMaxGoalRounds`，默认 256）。
  - 语义：创建并 arm 一个 goal；仅 completed 的 goal 可被替换，其他 phase 须先 clear/resume。
  - 返回：`CreateGoalResult = { ref: GoalRef }`（`{id, revision}`，revision=1）。

### 返回对象形状

- `GoalView extends GoalSnapshot`，`GoalSnapshot = { id: GoalId, revision: number, objective: string, phase: GoalPhase, blockedReason?: GoalBlockReason, maxGoalRounds: number }`，另加派生字段：
  - `roundsStarted: number`（该 goal 已准入的最高 round 号）
  - `createdAt: number`、`updatedAt: number`（epoch ms）
  - `activation: 'armed'|'disarmed'`（process-local，永不持久化）
- `GoalPhase = 'active'|'paused'|'blocked'|'complete'`。
- `GoalBlockReason = { code: string /* lower-kebab-case */, message: string }`。
- `GoalRef = { id: GoalId, revision: number }`。

### GoalError code（Host 抛，carrier 层如何映射到 client 未在本源直接体现；如实列出 Host 侧 code）

- `GOAL_NOT_FOUND`（无 current goal）、`GOAL_STALE_REVISION`（ref 与当前不符）、`GOAL_AGENT_NOT_LIVE`、`GOAL_INVALID_TRANSITION`（phase 不允许）、`GOAL_ALREADY_EXISTS`、`GOAL_INVALID_EDIT`、`GOAL_INVALID_OBJECTIVE`、`GOAL_INVALID_MAX_ROUNDS`、`GOAL_INVALID_BLOCK_REASON`。
- 不确定：本文件未见把 GoalError 映射为 RemoteError code 的 RemoteErrorDetailsMap 声明（不同于 settings/agent-presets 域在 types.ts 里显式声明）；client 端错误码语义需以 client 侧 RemoteError 映射源为准（本盘点如实标注）。

---

## ns messageFeedback

- Host owner：`MessageFeedbackService extends TypertRemoteService`，`super(ctx,'messageFeedback')`（`packages/feedback/message-feedback/src/index.ts`）。storage-domain 侧车服务；只检查持久化 Session 历史，从不创建/恢复 Agent 或 Session。
- 端点形态（client 调用名 ← Host 方法，均带标签）：
  - `messageFeedback.list` ← `list`（`@Remote('list')`）
  - `messageFeedback.put` ← `put`（`@Remote('put')`）
  - `messageFeedback.delete` ← `delete`（`@Remote('delete')`）
- 结果风格：业务联合返回（非抛错）。成功 `{ok:true, value}`，失败 `{ok:false, error:{code,…}}`。
- 每个 mutation 按 sessionId 串行入队（`enqueue`）；服务 disposing 时 mutation 拒绝。

### 方法

- `messageFeedback.list(request: MessageFeedbackListRequest): Promise<MessageFeedbackListResult>`
  - `MessageFeedbackListRequest = { sessionId: SessionId }`。
  - 语义：读属于当前持久化 Session lifecycle 的反馈（stale 复用 id 的行不可见）。
  - 返回：`MessageFeedbackListResult = {ok:true, value:{items: MessageFeedbackItem[]}} | {ok:false, error:{code:'session-not-found', sessionId}}`。

- `messageFeedback.put(request: MessageFeedbackPutRequest): Promise<MessageFeedbackPutResult>`
  - `MessageFeedbackPutRequest = { sessionId, messageId, rating: 'positive'|'negative', note?: string, ifVersion: MessageFeedbackVersion|null }`。
    - `ifVersion`：CAS —— 须匹配当前版本，或 `null` 要求目标不存在。
  - 语义：为一条 derived append-origin assistant 消息创建/替换反馈。target 必须是已 finalize 的 append-origin assistant 消息（否则 `target-not-found`）。匹配 no-op 返回已存项不改版本。
  - 返回分支：成功 `{ok:true, value: MessageFeedbackItem}`；失败 `session-not-found | target-not-found | version-conflict | note-blank | note-too-large`。
  - `MessageFeedbackItem = { messageId, rating, note?: string, version: MessageFeedbackVersion /* CAS token */, createdAt, updatedAt }`。

- `messageFeedback.delete(request: MessageFeedbackDeleteRequest): Promise<MessageFeedbackDeleteResult>`
  - `MessageFeedbackDeleteRequest = { sessionId, messageId, ifVersion: MessageFeedbackVersion }`（item 已 absent 时 ifVersion 忽略）。
  - 语义：删除一条反馈。absence 视为成功（幂等）。
  - 返回：成功 `{ok:true, value:{absent:true}}`；失败 `session-not-found | version-conflict`。

### 业务失败 code（types.ts MessageFeedbackFailure 联合）

- `session-not-found`: `{ code, sessionId }` —— 无该持久化 Session header。
- `target-not-found`: `{ code, sessionId, messageId }` —— id 不指向 derived append-origin assistant 消息。
- `version-conflict`: `{ code, current: MessageFeedbackItem|null }` —— 材料变更未匹配当前版本；`current` 为权威当前项或 null（不存在）。
- `note-blank`: `{ code }` —— note 无任何非空白字符。
- `note-too-large`: `{ code, maxBytes, actualBytes }` —— 超过配置的 UTF-8 字节上限（`maxNoteBytes`）。
- 注：这些是业务 result 分支（`ok:false` 内），非抛出的 RemoteError；与 settings/agent-presets 的 RemoteError code 体系不同。

---

## 覆盖清单与不确定点

覆盖 ns：`settings`、`credentials`、`llm`、`agentPresets`、`commands`、`goals`、`messageFeedback`。

不确定/如实标注：
1. **goals 无 `list`**：任务描述含“goals.list?”——GoalService 源中不存在 list 方法（读取走 Host 本地 `get`，非 @Remote）。client 不调 list。
2. **goals RemoteError 映射**：GoalService 抛的是 `GoalError`（Host code），包内未见 RemoteErrorDetailsMap 把 GoalError code 映射到 RemoteError 的声明；client 端错误码语义未在本源确认。
3. **remoteExport\* 命名**：agent-presets 与 goals 的 `@Remote('标签')` 决定 client 调用名（`remoteExportList→list`、`remoteExportCopy→copy`、`remoteExportDelete→deletePreset`、`readDocument→read`、`remoteExportCreate→create`）；Host 实现方法名带 `remoteExport` 前缀，标签才是 wire 名。
4. **agent / AbortSignal 参数**：`commands.list/execute`、`agentPresets.select`、`goals.*`、`settings.openSettingsDocument` 等参数含 Host 内建对象（Agent、AbortSignal），由 Remote carrier 从 wire 身份解析/编码；其 wire 编码细节不在本盘点源文件中。
5. **llm discoverModels**：仅 `remoteDiscoverModels`（`@Remote('discoverModels')`）暴露；纯 Host 方法 `discoverModels` 不暴露。llm 域 RemoteError code 仅 `llm/model-discovery-rejected`（types.ts 声明）。
