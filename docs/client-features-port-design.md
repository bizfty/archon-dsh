# 设计：将官方 DeepSeek 客户端功能移植到 dsh-web（anchon 项目）

> 状态：**设计稿 v1（待评审）**
> 目标项目：`/home/john/workspace/anchon` —— Spring Boot 4.1 + Spring AI 2.0 复刻的 DeepSeek Harness（Java），
> 前端为 Vue 3 + Element Plus（`dsh-web/src/main/webapp`）。
> 本设计将官方 DeepSeek 客户端（chat.deepseek.com / App）的四项交互移植进来：
> **① 联网搜索开关　② 深度思考开关　③ 语音输入　④ 非图片文件上传**。

---

## 0. 现状盘点（调研结论）

### 0.1 后端（Java）

| 能力 | 现状 | 证据 |
|---|---|---|
| `web_search` / `web_fetch` 工具 | ✅ 已存在且注册 | `dsh-search/.../WebSearchTool.java`、`WebFetchTool.java`（`@Tool` 注解自注册进 `ToolRegistry`） |
| 搜索 provider | ✅ DuckDuckGo（无 key，真实可用） | `DuckDuckGoSearchProvider.java`（`dsh.search.search-max-results` 默认 8） |
| 模型调用 | `LlmGateway`（call/stream）+ Spring AI `OpenAiChatOptions`（model/temperature/toolCallbacks） | `SpringAiLlmGateway.java`、`AgentLoopService.java:309` |
| 推理模型 | 无专门逻辑；模型名直接透传；**`streamStep` 只读 `getText()+toolCalls`，`deepseek-reasoner` 的 `reasoning_content` 会被丢弃**；但 **Spring AI 2.0 `OpenAiChatOptions` 支持 `reasoningEffort`**（可作增强） | `AgentLoopService.resolveModel`、`streamStep`、前端模型列表已含 reasoner |
| 音频转写（潜在） | Spring AI 自带 `OpenAiAudioTranscriptionModel`（Whisper），可作语音后端兜底（v2） | spring-ai-openai 依赖 |
| 工具可见性过滤 | `Agent.isToolVisible`（enabled/disabledTools）+ `AgentScope` 每运行解析 | `Agent.java`、`AgentScope.java`、`AgentLoopService.java:228-230` |
| 文件上传 | ❌ 无上传端点、`SessionMessage` 无附件字段、`ChatRequest` 无文件字段 | grep `MultipartFile|upload|attachment` 于 dsh-api/dsh-boot 零命中 |
| 语音 | ❌ 无任何 ASR/音频能力 | 全仓库零命中 |

### 0.2 前端（Vue 3）

| 能力 | 现状 |
|---|---|
| Composer | 清空按钮 + 模型下拉（硬编码 4 模型，含 `deepseek-reasoner`）+ Send/Stop 圆形按钮 + `el-input textarea` |
| 开关类控件 | ❌ 无任何 toggle/switch |
| 附件/上传/语音 | ❌ 全无 |
| SSE 事件 | `message / tool / question / done / error / unknown`；`tool` 事件渲染为通用折叠块（toolName + 前 4000 字符），`event_type` 透传未消费 |
| 发送链路 | `Composer emit('send')` → `App.send()` → `chatStream(sessionId, {message, model}, onSse)`（唯一通道，改一处即可） |

### 0.3 关键结论

- **联网搜索的"模型可用 + 可执行 + 可展示"已全部就绪**（DuckDuckGo provider 无 key、web_search 工具已注册、工具结果以 tool 折叠块渲染），缺的只是**用户侧开关 UI + 每轮意图载体**。
- **深度思考的"模型选项"已存在**（deepseek-reasoner），缺的是独立开关体验与后端 `reasoning_effort` 透传。
- **语音输入**可纯前端实现（浏览器 Web Speech API），后端零改动。
- **非图片文件上传**是全栈缺口：需新增上传端点、附件持久化、注入上下文、前端附件栏。

---

## 1. 功能 ①：联网搜索开关

### 1.1 目标交互（对齐官方客户端）

输入框旁一个开关；开启后**本轮对话**模型必须走 web 搜索，结果以引用形式可见。

### 1.2 方案：开关 + 每轮意图载体 + 持久化指令注入（推荐）

复用已注册的 `web_search` 工具与 DuckDuckGo provider，不做 pre-search（v2 再说），不做工具 schema 切换（会破坏 KV-cache 前缀，见官方 TS 仓库调研结论）。

**数据流**：Composer 开关 → `appState.webSearch` → `ChatRequest.webSearch`（新可选字段）→ `AgentRunRequest.searchForced`（新字段）→ `AgentLoopService` 在 system prompt 组装时注入一条持久化指令段 → 模型调用 `web_search` → 工具结果以 tool 事件回流 → 前端按来源渲染引用卡片。

### 1.3 改动清单

**后端（Java）**

| 文件 | 改动 |
|---|---|
| `dsh-api/src/main/java/com/example/dsh/api/dto/ChatRequest.java` | record 加 `Boolean webSearch`（可选，null=不启用） |
| `dsh-agent/src/main/java/com/example/dsh/agent/AgentRunRequest.java` | record 加 `boolean searchForced`（默认 false），Builder 对应 |
| `dsh-api/.../SessionController.java` | `chat` / `chatStream` 两处构造 `AgentRunRequest` 时透传 `Boolean.TRUE.equals(request.webSearch())` |
| `dsh-agent/.../AgentLoopService.java` | 组装 system prompt 前：`searchForced` 为 true 时追加一条 `SystemPromptSection`（或直接在 `assemble` 调用处拼段），文案如：`## 本轮联网搜索\n用户已开启联网搜索：本轮必须调用 web_search 工具检索最新信息，并在回答中引用来源 URL。` |
| （可选）新增 `dsh-agent/.../SearchForcedSection.java` | `implements SystemPromptSection`，order 100+，按 `context` 中的运行标志渲染 |

**实现细节**：`AgentLoopService.execute()` 在 `systemPromptService.assemble(...)` 处已有 `scope.extraSections()` 扩展点；最简做法是 `searchForced` 时在 `assemble` 返回后直接追加指令文本段（`systemPrompt += "\n## 本轮联网搜索\n用户已开启联网搜索：本轮必须调用 web_search 工具检索最新信息，并在回答中引用来源 URL。\n"`）。由于 `SystemPromptContext` 不携带每轮运行标志（record 只有 session/agent/toolRefs/variables），新增 `SearchForcedSection` 需要先扩展 context 字段——**P0 用追加文本（零 context 改动），扩展 context 列为增强**。指令写进 system prompt 会改动该轮 `header.system`（KV 前缀失效），但 Java 项目无 KV-cache 机制（Spring AI 直连 DeepSeek，无前缀缓存优化），无此顾虑——**与官方 TS 仓库不同，这里注入 system prompt 与注入用户消息等价**。选 system prompt 段：语义更清晰、且只影响"开启"的那一轮。

**前端（Vue 3）**

| 文件 | 改动 |
|---|---|
| `src/store.ts` | `appState` 加 `webSearch: boolean`（默认 false） |
| `src/components/Composer.vue` | `.row .tools` 区（清空按钮旁）加 `el-switch v-model="appState.webSearch"` + 标签（🌐 联网搜索） |
| `src/api.ts` | `ChatRequest` 接口加 `webSearch?: boolean` |
| `src/App.vue` | `send()` 的 `chatStream` body 加 `webSearch: appState.webSearch` |

### 1.4 展示增强（可选，建议做）

官方客户端的搜索结果是可见引用列表。当前 `tool` 事件渲染为通用折叠块。建议 MessageList 对 `toolName === 'web_search'` 的 tool 消息渲染来源卡片（标题链接 + snippet）。

- 需要后端 `WebSearchTool` 的结果带结构化 sources。当前 `ToolResult.success("找到 N 条结果", Map.of("results", items))` 已含 `{title,url,snippet}[]`，但 `message` 字段（SSE tool 事件的 message）只有"找到 N 条结果"。前端 `rowHtml` 拿不到结构化 results。
- **方案 A（推荐）**：`WebSearchTool` 把结果列表格式化进 `message` 文本（每行 `- [title](url) — snippet`），前端 `renderMarkdown` 直接渲染为链接列表——零前端协议改动。
- **方案 B**：SSE tool 事件增加 `data` 字段透传结构化结果，前端专用渲染。改动更大。
- 建议先 A 后 B（A 是最小可行，B 作为后续增强）。

### 1.5 测试

- 后端单测：`searchForced=true` 时 system prompt 含搜索指令段（直接调 `AgentLoopService` 或抽出的组装方法）。
- E2E（key 门控，仿 `DeepSeekE2ETest`）：`webSearch: true` 后模型应调用 `web_search` 工具（DuckDuckGo 无需 key，可无 key 验证工具被调用；真实搜索需外网）。
- 前端：Composer 开关存在、发送时 body 带 `webSearch`。

---

## 2. 功能 ②：深度思考开关

### 2.1 目标交互

输入框旁"深度思考"开关；开启时本轮使用推理模式（DeepSeek R1/Reasoner 或 `reasoning_effort`）。

### 2.2 方案：开关 → 模型路由切换（最小） + `reasoning_effort` 透传（增强）

**最小方案（推荐先做）**：开启时发送请求的 `model` 用 `deepseek-reasoner`，关闭时用当前选中的普通模型。复用现有模型路由，后端零改动（`ChatRequest.model` 已有）。

- 前端：Composer 加 `el-switch v-model="appState.deepThink"`；`App.send()` 中 `model: appState.deepThink ? 'deepseek-reasoner' : appState.model`。
- 备注：`deepseek-reasoner` 是否真实走推理取决于 DeepSeek API 对该模型名的支持；**且 `streamStep` 只读 `getText()`，reasoner 的 `reasoning_content`（推理过程）不会显示**——用户只能看到最终答案，看不到思考过程。若要展示推理过程，需 SSE 新增 `reasoning` 事件 + `streamStep` 读取 `reasoning_content`（v2 增强）。

**增强方案（recommend v2，调研确认可行）**：`ChatRequest` 加 `reasoningEffort` 字段（`off|low|high|max`），`SpringAiLlmGateway`/`AgentLoopService` 组装 `OpenAiChatOptions` 时透传——**Spring AI 2.0 `OpenAiChatOptions` 已支持 `reasoningEffort`**（后端调研确认），无需自定义序列化。同时 `streamStep` 读取并转发 `reasoning_content` 为 SSE `reasoning` 事件，前端渲染可折叠思考行（对齐官方客户端的"深度思考"展示）。

### 2.3 改动清单（最小方案）

| 文件 | 改动 |
|---|---|
| `src/store.ts` | 加 `deepThink: boolean`（默认 false） |
| `src/components/Composer.vue` | `.row .tools` 加 `el-switch v-model="appState.deepThink"` + 标签（🧠 深度思考） |
| `src/App.vue` | `send()` 的 body 构造：`model: appState.deepThink ? 'deepseek-reasoner' : appState.model` |

### 2.4 测试

- 前端：开关存在、开启时 body.model === 'deepseek-reasoner'。
- E2E（key 门控）：开启深度思考后模型回答含推理内容（deepseek-reasoner 真实响应，需 key）。

---

## 3. 功能 ③：语音输入

### 3.1 目标交互

输入框旁麦克风按钮；按住/点击开始录音，转写文本进入输入框草稿（官方客户端行为：语音→文字→可编辑后发送）。

### 3.2 方案：浏览器 Web Speech API（纯前端，后端零改动）

- **录音 + 转写**：`webkitSpeechRecognition`（Chrome/Edge/Safari 支持；Firefox 不支持——见风险）连续识别，`lang: 'zh-CN'`，`interimResults: true` 实时显示；最终文本写入 `appState.draft`。
- **回退**：若 `webkitSpeechRecognition` 不可用，按钮禁用并提示"当前浏览器不支持语音输入"。
- **不引入 MediaRecorder + 后端 ASR**（v2 再考虑）：无现成 ASR 端点/凭据缝，浏览器 Web Speech API 零后端、零依赖。

**v2 兜底（调研确认可行）**：Spring AI 自带 `OpenAiAudioTranscriptionModel`（Whisper）——若需覆盖 Firefox 或转写质量不足，可新增后端 `POST /api/audio/transcribe` 端点（MultipartFile 音频 → Whisper 转写 → 返回文本），前端 `MediaRecorder` 录音上传。需要 `OPENAI_API_KEY` 支持 Whisper 端点（DeepSeek 兼容端点是否提供需验证，或配置独立 OpenAI key）。

### 3.3 改动清单（纯前端）

| 文件 | 改动 |
|---|---|
| `src/store.ts` | 加 `listening: boolean`（录音中状态） |
| `src/components/Composer.vue` | `.row .tools` 加麦克风按钮（🎤）：点击开始/停止识别；识别中按钮高亮 + `el-tooltip` 提示"正在聆听..."; 识别结果写入 `draft`（`appState.draft`，watch 已同步到本地 `draft`） |
| `src/api.ts` | 无改动 |

**实现要点**：
- 识别实例每轮新建（`SpeechRecognition` 是一次性的）；`onresult` 取 `event.results[event.resultIndex][0].transcript`，interim 实时拼接进草稿。
- 结束（`onspeechend`/`onerror`/手动停止）后释放。
- 输入法/文本共存：识别文本**追加**到现有草稿尾部（保留用户已输入内容）。

### 3.4 风险

- **Firefox 不支持** `webkitSpeechRecognition`（无标准 SpeechRecognition）→ 按钮禁用 + 提示。
- **Secure Context**：`localhost` 是安全上下文，可用；**LAN IP（http://192.168.x.x）拿不到麦克风**（需要 HTTPS）。本项目默认 127.0.0.1:8080 没问题。
- 转写质量：中文术语/代码片段识别差；结果进草稿让用户可编辑再发送（正是官方客户端行为，规避了该风险）。
- 隐私：全部在浏览器本地识别（Web Speech API 通常走系统/浏览器服务），不经过本项目后端——可在 UI 文案注明。

### 3.5 测试

- 单元/组件级：模拟 `webkitSpeechRecognition` 不存在时按钮禁用；模拟识别事件（注入 fake）验证草稿追加。
- 手动：Chrome 下点麦克风说话 → 文本进输入框。

---

## 4. 功能 ④：非图片文件上传

### 4.1 目标交互

输入框旁"+"上传文件（PDF/Word/TXT/代码等）；附件栏显示文件卡片（名称+大小）；发送后模型能读取文件内容参与对话；历史中附件可见。

### 4.2 现状缺口（全栈）

- 后端：无上传端点；`SessionMessage` content 是纯字符串（无附件字段）；`ChatRequest` 无文件字段；`MessageProjector` 只投 USER 文本。
- 前端：无附件栏；无上传函数；消息渲染无附件卡片。

### 4.3 方案：上传端点 + 附件落盘 + 文本注入上下文（P1 文本类）

**范围界定（P1）**：支持**文本类文件**（.txt/.md/.json/.yml/.java/.py 等常见文本/代码；`.pdf`/`.docx` 的二进制解析列为 P2，因需引入解析库）。图片走已有视觉能力另行评估（当前 Java 版 LLM 网关未确认多模态）。

**数据流**：
1. 前端 `el-upload` 选文件 → `POST /api/files`（`MultipartFile`）→ 后端保存到 `dsh.storage.dir/files/<sessionId>/` → 返回 `{fileId, name, size, mimeType, textPreview?}`。
2. 前端附件栏展示卡片；发送时 `ChatRequest.files: [{fileId, name}]`。
3. `SessionController` → `AgentRunRequest.files` → `AgentLoopService` 打开 turn 时：读文件文本（≤ `dsh.files.max-inject-bytes` 默认 64KB），以 **USER 上下文消息**注入（前缀"用户上传了文件 <name>，内容如下：\n...\n"），超限截断+提示"已截断，可用 read_file 读取完整内容（若文件在工作区）"。
4. 附件引用随用户消息持久化（`SessionMessage` 加可选 `attachments` JSON 字段，或退化为纯文本注入——见下）。

**决策点**：
- **A. 附件作为独立字段持久化**（`SessionMessage.attachments`，`MessageDto` 透出，前端历史渲染附件卡片）——语义完整，改动涉及 JPA 实体 + DTO + 投影。
- **B. 附件只做文本注入**（上传文件→读文本→注入为一条 USER 消息，原文件存盘但不建模）——最小改动，历史中附件显示为普通文本消息（内容就是文件文本，可接受）。
- **推荐 A（P1 半程）**：新增上传端点 + `ChatRequest.files` + 注入上下文（必做）；`SessionMessage.attachments` 持久化作为 A 增强（若时间紧先 B 后 A）。

### 4.4 改动清单

**后端（Java）**

| 文件 | 改动 |
|---|---|
| 新增 `dsh-api/.../FileController.java` | `POST /api/files`：`MultipartFile` → 校验大小（`dsh.files.max-upload-bytes` 默认 10MB）→ 保存 `dsh.storage.dir/files/<sessionId>/<fileId>_<name>` → 返回 `{fileId, name, size, mimeType}`。需在 `dsh-boot` 挂配置属性 |
| `dsh-api/.../dto/ChatRequest.java` | 加 `List<FileRef> files`（record `FileRef(String fileId, String name)`） |
| `dsh-agent/.../AgentRunRequest.java` | 加 `List<FileRef> files`（默认空），Builder 对应 |
| `dsh-api/.../SessionController.java` | `chat`/`chatStream` 透传 `request.files()` |
| `dsh-agent/.../AgentLoopService.java` | turn 打开后：对每个 `files` 读文本（`dsh.files.dir` + fileId 校验防穿越）→ 组装注入文本 → `sessionService.append(USER, ...)` + `messages.add(new UserMessage(...))`（与现有"附加上下文"模式一致，见 `AgentLoopService.java:376-384`，即 `result.additionalContexts()` 注入路径） |
| 新增配置 | `dsh.files.dir`（默认 `./data/files`）、`dsh.files.max-upload-bytes`、`dsh.files.max-inject-bytes`（默认 65536） |
| （A 增强）`dsh-session/.../SessionMessageEntity.java` | 加 `attachments` 列；`SessionMessage` record 加字段；`MessageProjector` 无需改（注入文本即可） |

**前端（Vue 3）**

| 文件 | 改动 |
|---|---|
| `src/api.ts` | 加 `uploadFile(sessionId, file): Promise<FileRef>`（FormData）；`ChatRequest` 加 `files?: FileRef[]` |
| `src/store.ts` | 加 `attachments: {fileId, name, size, status}[]` |
| `src/components/Composer.vue` | `.row .tools` 加"+"按钮 → 隐藏 `<input type="file" multiple>` → 上传 → 附件卡片行（名称+大小+删除）；发送时把 `attachments` 随 `send` emit 出去 |
| `src/App.vue` | `send(text, attachments?)` 透传 `files` 进 body；发送后清空附件栏 |
| （A 增强）`src/components/MessageList.vue` | 附件消息渲染卡片（若走 B：附件即文本消息，无需改） |

**安全要点**：文件保存在 `dsh.files.dir/<sessionId>/`，读取时**必须校验 fileId 无路径穿越**（`fileId` 用 UUID，文件名清洗）；上传大小限制；注入字节上限。

### 4.5 测试

- 后端单测：上传端点保存文件 + 返回元数据；`AgentLoopService` 注入文本正确（含截断、路径穿越拒绝）。
- E2E（key 门控）：上传 txt → 发送"总结这个文件" → 模型引用文件内容。
- 前端：附件栏显示/删除/随请求发送。

---

## 5. 实施顺序与依赖

| 阶段 | 内容 | 依赖 |
|---|---|---|
| **P0（本周）** | 功能①联网搜索开关（后端字段+注入段+前端开关+工具结果格式化） | 无 |
| **P1a** | 功能②深度思考开关（最小方案：模型路由） | 无 |
| **P1b** | 功能④文件上传（上传端点+注入上下文+前端附件栏） | 无 |
| **P2** | 功能③语音输入（Web Speech API） | 无（纯前端） |
| **P3（可选增强）** | 搜索引用卡片渲染（方案 B）、`reasoning_effort` 透传、附件持久化字段（方案 A）、PDF/Word 解析 | P0/P1 |

> 建议 P0→P1a→P1b→P2 顺序实施，每阶段独立可验证（各有单测/手动验收）。

---

## 6. 未决问题（需确认）

1. **deepseek-reasoner 实际推理行为**：部署的 API key（`application.yml` 中已硬编码 `sk-efad94cc...`，注意这不是安全做法）对应的 DeepSeek 端点是否接受 `deepseek-reasoner` 模型名并返回推理内容？需真实调用验证（E2E）。**且 `streamStep` 当前丢弃 `reasoning_content`**——最小方案用户看不到思考过程，v2 需 SSE `reasoning` 事件。
2. **深度思考 v2 的 `reasoning_effort`**：Spring AI 2.0 `OpenAiChatOptions` 支持 `reasoningEffort`（已确认），但 DeepSeek 兼容端点是否接受该参数需真实验证。
3. **文件上传落点**：方案 A（附件独立字段持久化）vs 方案 B（仅文本注入）——本项目是教学/演示性质还是生产？决定投入。
4. **语音识别的浏览器目标**：仅 Chrome/Edge（webkitSpeechRecognition）即可，还是要覆盖 Firefox（需 v2 后端 Whisper）？
5. **图片/多模态**：是否要在本阶段做图片上传？当前 Java LLM 网关未见多模态支持，需确认 DeepSeek 端点是否接受图片（官方 API 支持 vision 输入时再评估）。
6. **`application.yml` 里的硬编码 API key** 建议改为环境变量引用（当前默认值已泄露在仓库中）。
7. **`ChatRequest.skillIds` 是死字段**（Controller 不读、AgentRunRequest 无、前端不发）——可顺手清理或忽略。

---

## 7. 参考

- 官方 TS 仓库调研（三个子代理报告）：联网搜索开关可行性、文件上传路径、语音输入能力——架构思路已吸收进本文。
- 本项目现有文档：`README.md`、`docs/DSH_JAVA_MAPPING.md`、`docs/DEMO.md`。
