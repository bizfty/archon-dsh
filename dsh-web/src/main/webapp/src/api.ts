// API 客户端 — 对接 dsh-api 的 REST 端点与 SSE 流式。
// 目标 base：同源（Spring Boot 静态资源 + API 同机部署）。

export const BASE = '';

export interface SessionDto {
  id: string;
  title: string;
  model: string | null;
  cwd: string | null;
  status: string;
  createdAt: string;
  updatedAt: string;
}

export interface MessageDto {
  id: string;
  role: string;
  content: string;
  toolName?: string | null;
  toolCallId?: string | null;
}

export interface GoalView {
  id: string;
  sessionId: string;
  objective: string;
  phase: string;
  blockedCode: string | null;
  blockedReason: string | null;
  maxGoalRounds: number;
  roundsStarted: number;
  createdAt: number;
  updatedAt: number;
  revision: number;
}

export interface CreateSessionRequest {
  title?: string;
  model?: string;
  /** 会话工作目录（代码开发/自我完善：当前项目/源码根目录）。 */
  cwd?: string;
}


export interface WorkspaceDto {
  id: string;
  path: string;
  title: string | null;
  sessionIds: string[];
  createdAt: string;
  updatedAt: string;
}

export interface DirectoryEntry {
  name: string;
  path: string;
  hidden: boolean;
}

export interface DirectoryListing {
  path: string;
  home: string;
  crumbs: DirectoryEntry[];
  entries: DirectoryEntry[];
  truncated: boolean;
}

/** 部署布局固定根：workspaceRoot=固定工作区根、codeRoot=应用源码根、home。 */
export interface DirectoryRoots {
  workspaceRoot: string;
  codeRoot: string;
  home: string;
}

export interface ChatRequest {
  message: string;
  model?: string;
  agentId?: string;
}

export type SseEvent =
  | { event: 'message'; content: string }
  | { event: 'tool'; tool: string; event_type: string; success: boolean; message: string }
  | { event: 'question'; question: string; options: string[]; multiSelect: boolean }
  | { event: 'done'; ok: boolean }
  | { event: 'error'; message: string; errorType?: string }
  | { event: 'unknown'; raw: string };

let authToken: string | null = null;

export function setAuthToken(token: string | null): void {
  authToken = token;
}

function headers(json = true): Record<string, string> {
  const h: Record<string, string> = {};
  if (json) h['Content-Type'] = 'application/json';
  if (authToken) h['X-Auth-Token'] = authToken;
  return h;
}

async function parse<T>(resp: Response): Promise<T> {
  if (!resp.ok) {
    let msg = `${resp.status} ${resp.statusText}`;
    try {
      const body = await resp.json();
      if (body && (body.error || body.message)) msg = body.error || body.message;
    } catch {
      /* 忽略非 JSON 错误体 */
    }
    throw new Error(msg);
  }
  return resp.json() as Promise<T>;
}

/** 会话列表 */
export async function listSessions(): Promise<SessionDto[]> {
  const resp = await fetch(`${BASE}/api/sessions`, { headers: headers(false) });
  return parse<SessionDto[]>(resp);
}

/** 创建会话 */
export async function createSession(title?: string, model?: string, cwd?: string): Promise<SessionDto> {
  const body: CreateSessionRequest = {};
  if (title) body.title = title;
  if (model) body.model = model;
  if (cwd) body.cwd = cwd;
  const resp = await fetch(`${BASE}/api/sessions`, {
    method: 'POST',
    headers: headers(),
    body: JSON.stringify(body),
  });
  return parse<SessionDto>(resp);
}

/** 会话消息 */
export async function listMessages(sessionId: string): Promise<MessageDto[]> {
  const resp = await fetch(`${BASE}/api/sessions/${encodeURIComponent(sessionId)}/messages`, {
    headers: headers(false),
  });
  return parse<MessageDto[]>(resp);
}

/** 删除会话 */
export async function deleteSession(sessionId: string): Promise<void> {
  const resp = await fetch(`${BASE}/api/sessions/${encodeURIComponent(sessionId)}`, {
    method: 'DELETE',
    headers: headers(false),
  });
  if (!resp.ok) throw new Error(`${resp.status} ${resp.statusText}`);
}


// ---------- 工作区（Workspace：先选工作目录再开会话，对齐官方 workspaces service）----------

/** 工作区列表（含各工作区下会话 id）。 */
export async function listWorkspaces(): Promise<WorkspaceDto[]> {
  const resp = await fetch(`${BASE}/api/workspaces`, { headers: headers(false) });
  return parse<WorkspaceDto[]>(resp);
}

/** 注册工作区（规范化 + 幂等：同路径已存在时返回既有）。 */
export async function createWorkspace(path: string, title?: string): Promise<WorkspaceDto> {
  const body: Record<string, string> = { path };
  if (title) body.title = title;
  const resp = await fetch(`${BASE}/api/workspaces`, {
    method: 'POST',
    headers: headers(),
    body: JSON.stringify(body),
  });
  return parse<WorkspaceDto>(resp);
}

/** 删除工作区（仅移除分组记录，其下会话保留）。 */
export async function deleteWorkspace(workspaceId: string): Promise<void> {
  const resp = await fetch(`${BASE}/api/workspaces/${encodeURIComponent(workspaceId)}`, {
    method: 'DELETE',
    headers: headers(false),
  });
  if (!resp.ok) throw new Error(`${resp.status} ${resp.statusText}`);
}

/** 重命名工作区。 */
export async function renameWorkspace(workspaceId: string, title: string): Promise<WorkspaceDto> {
  const resp = await fetch(`${BASE}/api/workspaces/${encodeURIComponent(workspaceId)}`, {
    method: 'PATCH',
    headers: headers(),
    body: JSON.stringify({ title }),
  });
  return parse<WorkspaceDto>(resp);
}

/**
 * 连接工作区：复用该目录下已有 blank 会话（无消息），否则新建 cwd=workspace.path 的会话。
 * 保证每个目录最多一个空白会话。
 */
export async function connectWorkspace(workspaceId: string): Promise<{ session: SessionDto }> {
  const resp = await fetch(`${BASE}/api/workspaces/${encodeURIComponent(workspaceId)}/connect`, {
    method: 'POST',
    headers: headers(),
  });
  return parse<{ session: SessionDto }>(resp);
}

/** 最近工作区 id（跨重启记忆）。 */
export async function getRecentWorkspace(): Promise<{ workspace_id?: string }> {
  const resp = await fetch(`${BASE}/api/workspaces/recent`, { headers: headers(false) });
  return parse<{ workspace_id?: string }>(resp);
}

/** 记录最近工作区。 */
export async function setRecentWorkspace(workspaceId: string): Promise<{ workspace_id: string }> {
  const resp = await fetch(`${BASE}/api/workspaces/recent`, {
    method: 'PUT',
    headers: headers(),
    body: JSON.stringify({ workspaceId }),
  });
  return parse<{ workspace_id: string }>(resp);
}

// ---------- 目录浏览（DirectoryBrowser：网页内目录树，对齐官方 directory-picker browse）----------

/** 列出一层子目录（缺省 path = host home）；只返回目录，name-sorted。 */
export async function listDirectory(path?: string): Promise<DirectoryListing> {
  const q = path ? `?path=${encodeURIComponent(path)}` : '';
  const resp = await fetch(`${BASE}/api/dirs${q}`, { headers: headers(false) });
  return parse<DirectoryListing>(resp);
}

/** 部署布局固定根（工作区根 / 代码根 / home），供目录浏览器做快捷入口。 */
export async function getDirectoryRoots(): Promise<DirectoryRoots> {
  const resp = await fetch(`${BASE}/api/dirs/roots`, { headers: headers(false) });
  return parse<DirectoryRoots>(resp);
}

/** 在父目录下新建子目录；返回新目录绝对路径。 */
export async function createDirectory(path: string, name: string): Promise<{ path: string }> {
  const resp = await fetch(`${BASE}/api/dirs`, {
    method: 'POST',
    headers: headers(),
    body: JSON.stringify({ path, name }),
  });
  return parse<{ path: string }>(resp);
}

/**
 * 非流式对话：POST /api/sessions/{id}/chat — 触发一个 turn 并等待其完成。
 * 事件（token/tool/...）经常驻 WebSocket 下行实时推送（见 ws.ts），
 * 因此本调用只负责"发起并最终确认"，UI 流式更新来自 WS 帧。
 */
export async function sendChat(
  sessionId: string,
  request: ChatRequest,
): Promise<{ content: string }> {
  const resp = await fetch(`${BASE}/api/sessions/${encodeURIComponent(sessionId)}/chat`, {
    method: 'POST',
    headers: headers(),
    body: JSON.stringify(request),
  });
  return parse<{ content: string }>(resp);
}

/** 会话的子代理列表（chat 顶部展示；对齐官方 ui-subagent）。 */
export interface SubagentView {
  id: string;
  sessionId: string;
  delegationDepth: number;
  status: string;
  lastContent: string | null;
  createdAt: string | null;
}

export async function listSubagents(sessionId: string): Promise<SubagentView[]> {
  const resp = await fetch(`${BASE}/api/sessions/${encodeURIComponent(sessionId)}/subagents`, {
    headers: headers(false),
  });
  return parse<SubagentView[]>(resp);
}

/** 向子代理发送消息（继续同一子会话对话）。 */
export async function sendSubagentMessage(
  parentSessionId: string,
  childId: string,
  message: string,
): Promise<{ childId: string; reply: string }> {
  const resp = await fetch(
    `${BASE}/api/sessions/${encodeURIComponent(parentSessionId)}/subagents/${encodeURIComponent(childId)}/message`,
    {
      method: 'POST',
      headers: headers(),
      body: JSON.stringify({ message }),
    },
  );
  return parse<{ childId: string; reply: string }>(resp);
}

/**
 * SSE 流式对话：POST /api/sessions/{id}/chat/stream
 * 逐个事件回调；返回句柄 { promise, abort, executionId } — abort() 中止流（对应前端 Stop 按钮）。
 *
 * 断线自动恢复：连接异常中断（非用户 Stop）时，以同一 executionId 按指数退避
 * 重连 `?resume=<executionId>`，后端重放已发生事件并续推实时事件；
 * 收到 done 事件才 resolve。executionId 由本函数生成并透出（前端无需感知）。
 */
export function chatStream(
  sessionId: string,
  request: ChatRequest,
  onEvent: (ev: SseEvent) => void,
): { promise: Promise<void>; abort: () => void; executionId: string } {
  const executionId = `fe-${crypto.randomUUID()}`;
  const controller = new AbortController();
  let stopped = false; // 用户 Stop：不再重连
  let attempt = 0;

  const BACKOFF_BASE_MS = 500;
  const BACKOFF_FACTOR = 2;
  const BACKOFF_MAX_MS = 10_000;
  const MAX_ATTEMPTS = 12;

  const sleep = (ms: number): Promise<void> => new Promise((r) => setTimeout(r, ms));

  const promise = new Promise<void>((resolve, reject) => {
    let receivedDone = false;
    const markDone = (): void => {
      receivedDone = true;
    };

    const run = (resume: string | null): void => {
      if (stopped) {
        resolve();
        return;
      }
      const url = `${BASE}/api/sessions/${encodeURIComponent(sessionId)}/chat/stream`
        + (resume ? `?resume=${encodeURIComponent(resume)}` : '');
      fetch(url, {
        method: 'POST',
        headers: headers(),
        body: JSON.stringify(request),
        signal: controller.signal,
      })
        .then((resp) => {
          if (!resp.ok || !resp.body) {
            return resp.text().then((t) => reject(new Error(`SSE 失败 ${resp.status}: ${t.slice(0, 200)}`)));
          }
          const reader = resp.body.getReader();
          const decoder = new TextDecoder();
          let buffer = '';
          const pump = (): void => {
            reader.read().then(({ done, value }) => {
              if (done) {
                // 服务端正常关闭。若尚未收到 done（异常场景兜底），尝试续流重连。
                if (!receivedDone && !stopped) {
                  scheduleReconnect();
                } else {
                  resolve();
                }
                return;
              }
              buffer += decoder.decode(value, { stream: true });
              // SSE 事件以空行分隔
              let idx: number;
              while ((idx = buffer.indexOf('\n\n')) >= 0) {
                const rawEvent = buffer.slice(0, idx);
                buffer = buffer.slice(idx + 2);
                emit(rawEvent, onEvent, markDone);
              }
              pump();
            }).catch((err) => {
              if (err instanceof DOMException && err.name === 'AbortError') {
                stopped = true;
                resolve(); // 用户主动停止：视为正常结束
              } else {
                scheduleReconnect();
              }
            });
          };
          pump();
        })
        .catch((err) => {
          if (err instanceof DOMException && err.name === 'AbortError') {
            stopped = true;
            resolve();
          } else {
            scheduleReconnect();
          }
        });
    };

    const scheduleReconnect = (): void => {
      if (stopped) {
        resolve();
        return;
      }
      attempt += 1;
      if (attempt > MAX_ATTEMPTS) {
        reject(new Error(`连接中断且重连 ${MAX_ATTEMPTS} 次失败（executionId=${executionId}）`));
        return;
      }
      const delay = Math.min(BACKOFF_MAX_MS, BACKOFF_BASE_MS * BACKOFF_FACTOR ** (attempt - 1));
      void sleep(delay).then(() => {
        if (!stopped) {
          run(executionId); // 续流重连：后端重放快照 + 续推实时事件
        }
      });
    };

    run(null); // 首次连接
  });
  return { promise, abort: () => { stopped = true; controller.abort(); }, executionId };
}

function emit(rawEvent: string, onEvent: (ev: SseEvent) => void, markDone: () => void = () => undefined): void {
  if (!rawEvent.trim()) return;
  const dataLines: string[] = [];
  let eventName = 'message';
  for (const line of rawEvent.split('\n')) {
    if (line.startsWith('event:')) eventName = line.slice(6).trim();
    else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim());
  }
  const data = dataLines.join('\n');
  if (!data) return;
  try {
    const payload = JSON.parse(data);
    if (eventName === 'message') {
      onEvent({ event: 'message', content: String(payload.content ?? '') });
    } else if (eventName === 'tool') {
      onEvent({
        event: 'tool',
        tool: String(payload.tool ?? ''),
        event_type: String(payload.event_type ?? ''),
        success: Boolean(payload.success),
        message: String(payload.message ?? ''),
      });
    } else if (eventName === 'done') {
      markDone();
      onEvent({ event: 'done', ok: true });
    } else if (eventName === 'question') {
      // ask_user_question 阻塞：推选择框数据（前端渲染选项/输入框）
      onEvent({
        event: 'question',
        question: String(payload.question ?? ''),
        options: Array.isArray(payload.options) ? payload.options.map(String) : [],
        multiSelect: Boolean(payload.multiSelect),
      });
    } else if (eventName === 'error') {
      onEvent({ event: 'error', message: String(payload.message ?? ''), errorType: String(payload.error_type ?? '') });
    } else {
      onEvent({ event: 'unknown', raw: data });
    }
  } catch {
    onEvent({ event: 'unknown', raw: data });
  }
}

/** 目标：当前 */
export async function getGoal(sessionId: string): Promise<GoalView | null> {
  const resp = await fetch(`${BASE}/api/goals?sessionId=${encodeURIComponent(sessionId)}`, {
    headers: headers(false),
  });
  const body = await parse<{ goal: GoalView | null } | GoalView>(resp);
  if (body && typeof (body as { goal?: GoalView | null }).goal === 'object') {
    return (body as { goal: GoalView | null }).goal;
  }
  return body as GoalView;
}

/** 目标：创建 */
export async function createGoal(sessionId: string, objective: string, maxGoalRounds?: number): Promise<GoalView> {
  const body: Record<string, unknown> = { sessionId, objective };
  if (maxGoalRounds) body.maxGoalRounds = maxGoalRounds;
  const resp = await fetch(`${BASE}/api/goals`, { method: 'POST', headers: headers(), body: JSON.stringify(body) });
  return parse<GoalView>(resp);
}

/** 目标：更新（CAS） */
export async function updateGoal(
  sessionId: string,
  goalId: string,
  revision: number,
  action: string,
  extra?: { objective?: string; maxGoalRounds?: number; blockedCode?: string; blockedReason?: string },
): Promise<GoalView> {
  const body: Record<string, unknown> = { sessionId, goalId, revision, action };
  if (extra?.objective) body.objective = extra.objective;
  if (extra?.maxGoalRounds) body.maxGoalRounds = extra.maxGoalRounds;
  if (extra?.blockedCode) body.blockedCode = extra.blockedCode;
  if (extra?.blockedReason) body.blockedReason = extra.blockedReason;
  const resp = await fetch(`${BASE}/api/goals`, { method: 'PUT', headers: headers(), body: JSON.stringify(body) });
  return parse<GoalView>(resp);
}

/** 用户登录（可选） */
export async function userLogin(username: string, password: string): Promise<{ token: string }> {
  const resp = await fetch(`${BASE}/api/users/login`, {
    method: 'POST',
    headers: headers(),
    body: JSON.stringify({ username, password }),
  });
  return parse<{ token: string }>(resp);
}

// ---------- 人机问答（ask_user_question 选择框） ----------

export interface PendingQuestion {
  id: string;
  sessionId: string;
  question: string;
  options: string[];
  multiSelect: boolean;
}

/** 挂起的用户问题（模型在 ask_user_question 上阻塞等待）；传 sessionId 只取该会话的。 */
export async function pendingQuestions(sessionId?: string | null): Promise<PendingQuestion[]> {
  const q = sessionId ? `?sessionId=${encodeURIComponent(sessionId)}` : '';
  const resp = await fetch(`${BASE}/api/interactions/questions/pending${q}`, { headers: headers(false) });
  return parse<PendingQuestion[]>(resp);
}

/** 取消某会话的当前执行（前端「停止生成」按钮；后端协作式取消，AgentLoop 在 step 间隙停止）。 */
export async function cancelChat(sessionId: string): Promise<void> {
  const resp = await fetch(`${BASE}/api/sessions/${encodeURIComponent(sessionId)}/chat/cancel`, {
    method: 'POST',
    headers: headers(),
  });
  if (!resp.ok) throw new Error(`${resp.status} ${resp.statusText}`);
}

export interface TrajectoryStep {
  step: number;
  type: 'user' | 'assistant' | 'tool' | 'system';
  toolName: string | null;
  content: string | null;
  toolCalls: TrajectoryToolCall[] | null;
  toolCallId: string | null;
  createdAt: string | null;
}

export interface TrajectoryToolCall {
  id: string;
  name: string;
  arguments: string;
}

export interface TrajectoryTurn {
  turn: number;
  steps: TrajectoryStep[];
  hasToolCalls: boolean;
}

export interface TrajectoryView {
  sessionId: string;
  startedAt: string | null;
  endedAt: string | null;
  turns: TrajectoryTurn[];
  steps: TrajectoryStep[];
  totalMessages: number;
  estimatedTokens: number;
  totalToolCalls: number;
  totalTurns: number;
}

export async function getTrajectory(sessionId: string): Promise<TrajectoryView> {
  const resp = await fetch(`${BASE}/api/sessions/trajectory?sessionId=${encodeURIComponent(sessionId)}`, {
    headers: headers(false),
  });
  return parse<TrajectoryView>(resp);
}

/** 应答问题：前端选择框提交后调用，模型据此继续。 */
export async function answerQuestion(id: string, answer: string): Promise<void> {
  const resp = await fetch(`${BASE}/api/interactions/questions/${encodeURIComponent(id)}/answer`, {
    method: 'POST',
    headers: headers(),
    body: JSON.stringify({ answer }),
  });
  if (!resp.ok) throw new Error(`${resp.status} ${resp.statusText}`);
}

/** 模型列表：尝试从后台接口获取，失败时回退到默认列表。 */
export interface ModelInfo {
  id: string;
  name: string;
  group: string;
}

const FALLBACK_MODELS: ModelInfo[] = [
  { group: 'DeepSeek', id: 'deepseek-chat', name: 'DeepSeek Chat' },
  { group: 'DeepSeek', id: 'deepseek-reasoner', name: 'DeepSeek Reasoner' },
  { group: 'OpenAI', id: 'gpt-4o', name: 'GPT-4o' },
  { group: 'OpenAI', id: 'gpt-4o-mini', name: 'GPT-4o Mini' },
];

export async function listModels(): Promise<ModelInfo[]> {
  try {
    const resp = await fetch(`${BASE}/api/models`, { headers: headers(false) });
    if (!resp.ok) throw new Error(`models ${resp.status}`);
    const data = await parse<ModelInfo[] | { models: ModelInfo[] }>(resp);
    const list = Array.isArray(data) ? data : data?.models;
    if (Array.isArray(list) && list.length > 0) return list;
    return FALLBACK_MODELS;
  } catch {
    return FALLBACK_MODELS;
  }
}

/** 压缩会话历史（compact）：总结旧消息以节省 context。 */
export async function compactSession(sessionId: string): Promise<{
  summary: string;
  shadowedCount: number;
}> {
  const resp = await fetch(`${BASE}/api/sessions/${encodeURIComponent(sessionId)}/compact`, {
    method: 'POST',
    headers: headers(),
  });
  return parse<{ summary: string; shadowedCount: number }>(resp);
}

/** 导出会话为 ZIP 归档。 */
export async function exportSession(sessionId: string): Promise<Blob> {
  const resp = await fetch(`${BASE}/api/sessions/${encodeURIComponent(sessionId)}/export`, {
    headers: headers(false),
  });
  if (!resp.ok) throw new Error(`${resp.status} ${resp.statusText}`);
  return resp.blob();
}

/** 提交会话反馈。 */
export async function submitFeedback(sessionId: string, text: string): Promise<void> {
  const resp = await fetch(`${BASE}/api/sessions/${encodeURIComponent(sessionId)}/feedback`, {
    method: 'POST',
    headers: headers(),
    body: JSON.stringify({ text }),
  });
  if (!resp.ok) throw new Error(`${resp.status} ${resp.statusText}`);
}

export interface SkillInfo {
  name: string;
  description: string;
  tools: string;
}

export interface SkillDetail {
  name: string;
  description: string;
  tools: string;
  basePath: string;
  body: string;
}

const FALLBACK_SKILLS: SkillInfo[] = [];

export async function listSkills(): Promise<SkillInfo[]> {
  try {
    const resp = await fetch(`${BASE}/api/skills`, { headers: headers(false) });
    if (!resp.ok) throw new Error(`skills ${resp.status}`);
    return parse<SkillInfo[]>(resp);
  } catch {
    return FALLBACK_SKILLS;
  }
}

export async function executeSkill(
  name: string,
  sessionId: string,
  userMessage?: string,
): Promise<{ skill: string; status: string }> {
  const body: Record<string, string> = { sessionId };
  if (userMessage) body.userMessage = userMessage;
  const resp = await fetch(`${BASE}/api/skills/${encodeURIComponent(name)}/execute`, {
    method: 'POST',
    headers: headers(),
    body: JSON.stringify(body),
  });
  return parse<{ skill: string; status: string }>(resp);
}

// ---- 后台任务（background jobs，对齐官方 background-job list）----

export interface JobDto {
  id: string;
  kind: string;
  command: string;
  status: 'running' | 'done' | 'failed' | 'killed';
  exitCode: number | null;
  output: string;
  durationMs: number;
  createdAt: string;
  completedAt: string | null;
}

/** 会话的后台任务列表。 */
export async function listJobs(sessionId: string): Promise<JobDto[]> {
  const resp = await fetch(`${BASE}/api/sessions/${encodeURIComponent(sessionId)}/jobs`, {
    headers: headers(false),
  });
  return parse<JobDto[]>(resp);
}

/** 终止指定后台任务。 */
export async function killJob(sessionId: string, jobId: string): Promise<{ ok: boolean; jobId: string }> {
  const resp = await fetch(
    `${BASE}/api/sessions/${encodeURIComponent(sessionId)}/jobs/${encodeURIComponent(jobId)}/kill`,
    { method: 'POST', headers: headers() },
  );
  return parse<{ ok: boolean; jobId: string }>(resp);
}

// ---- 计划模式（plan mode，对齐官方 plan-mode）----

export interface PlanState {
  active: boolean;
  planText: string;
}

/** 当前计划模式状态与已提交计划。 */
export async function getPlanMode(sessionId: string): Promise<PlanState> {
  const resp = await fetch(`${BASE}/api/sessions/${encodeURIComponent(sessionId)}/plan-mode`, {
    headers: headers(false),
  });
  return parse<PlanState>(resp);
}

/** 进入计划模式（只规划，不实现）。 */
export async function enterPlanMode(sessionId: string): Promise<{ active: boolean }> {
  const resp = await fetch(`${BASE}/api/sessions/${encodeURIComponent(sessionId)}/plan-mode/enter`, {
    method: 'POST',
    headers: headers(),
  });
  return parse<{ active: boolean }>(resp);
}

/** 退出计划模式（批准后进入执行）。 */
export async function exitPlanMode(sessionId: string): Promise<{ active: boolean }> {
  const resp = await fetch(`${BASE}/api/sessions/${encodeURIComponent(sessionId)}/plan-mode/exit`, {
    method: 'POST',
    headers: headers(),
  });
  return parse<{ active: boolean }>(resp);
}

/** 人类提交计划（保存计划并退出计划模式）。 */
export async function submitPlanMode(sessionId: string, plan: string): Promise<PlanState> {
  const resp = await fetch(`${BASE}/api/sessions/${encodeURIComponent(sessionId)}/plan-mode`, {
    method: 'POST',
    headers: headers(),
    body: JSON.stringify({ plan }),
  });
  return parse<PlanState>(resp);
}

// ---- DAG 计划（plan/plan_step/plan_step_dep，支持依赖与拓扑推进）----

export interface PlanStep {
  id: string;
  planId: string;
  title: string;
  description: string;
  status: 'pending' | 'in_progress' | 'completed' | 'cancelled' | 'skipped' | 'failed';
  required: boolean;
  /** 工件类型：task / proposal / spec / design / doc。 */
  kind: string;
  /** 审阅门：doc 类步骤须 reviewed=true 才可执行。 */
  reviewed: boolean;
  seq: number;
}

export interface PlanDep {
  step: string;
  dependsOn: string;
}

export interface PlanView {
  hasPlan?: boolean;
  plan: { id: string; sessionId: string; title: string; status: string; createdAt: string; updatedAt: string };
  steps: PlanStep[];
  deps: PlanDep[];
  nextSteps: PlanStep[];
}

/** 当前会话的 DAG 计划（无 → hasPlan=false）。 */
export async function getPlan(sessionId: string): Promise<PlanView> {
  const resp = await fetch(`${BASE}/api/sessions/${encodeURIComponent(sessionId)}/plan`, {
    headers: headers(false),
  });
  return parse<PlanView>(resp);
}

/** 创建 DAG 计划。 */
export async function createPlan(
  sessionId: string,
  title: string,
  steps: { id: string; title: string; description?: string }[],
  dependencies: { step: string; dependsOn: string }[],
): Promise<PlanView> {
  const resp = await fetch(`${BASE}/api/sessions/${encodeURIComponent(sessionId)}/plan`, {
    method: 'POST',
    headers: headers(),
    body: JSON.stringify({ title, steps, dependencies }),
  });
  return parse<PlanView>(resp);
}

/** 更新步骤状态。 */
export async function updateStepStatus(
  sessionId: string,
  stepId: string,
  status: string,
  planId?: string,
): Promise<PlanView> {
  const resp = await fetch(
    `${BASE}/api/sessions/${encodeURIComponent(sessionId)}/plan/steps/${encodeURIComponent(stepId)}/status`,
    { method: 'POST', headers: headers(), body: JSON.stringify({ planId, status }) },
  );
  return parse<PlanView>(resp);
}

/** 审阅步骤（批准/撤回批准；doc 类步骤须批准后才可执行）。 */
export async function reviewStep(
  sessionId: string,
  stepId: string,
  reviewed: boolean,
  planId?: string,
): Promise<PlanView> {
  const resp = await fetch(
    `${BASE}/api/sessions/${encodeURIComponent(sessionId)}/plan/steps/${encodeURIComponent(stepId)}/review`,
    { method: 'POST', headers: headers(), body: JSON.stringify({ planId, reviewed }) },
  );
  return parse<PlanView>(resp);
}

/** 完成整个计划。 */
export async function completePlan(sessionId: string, planId?: string): Promise<PlanView> {
  const resp = await fetch(`${BASE}/api/sessions/${encodeURIComponent(sessionId)}/plan/complete`, {
    method: 'POST',
    headers: headers(),
    body: JSON.stringify({ planId }),
  });
  return parse<PlanView>(resp);
}

/** 放弃计划。 */
export async function abandonPlan(sessionId: string, planId?: string): Promise<PlanView> {
  const resp = await fetch(`${BASE}/api/sessions/${encodeURIComponent(sessionId)}/plan/abandon`, {
    method: 'POST',
    headers: headers(),
    body: JSON.stringify({ planId }),
  });
  return parse<PlanView>(resp);
}

// ---- 步骤执行细节（点击已执行节点查看操作过程）----

export interface StepExecutionCall {
  tool: string;
  args: string;
  status: string;
  at: string;
}

export interface StepExecutionView {
  stepId: string;
  title: string;
  status: string;
  calls: StepExecutionCall[];
  error?: string;
}

export async function getStepExecution(sessionId: string, stepId: string): Promise<StepExecutionView> {
  const resp = await fetch(
    `${BASE}/api/sessions/${encodeURIComponent(sessionId)}/plan/steps/${encodeURIComponent(stepId)}/execution`,
    { headers: headers(false) },
  );
  return parse<StepExecutionView>(resp);
}

// ---- 待办计划清单（todo，对应官方 TodoPanel/plan strip）----

export interface TodoItemView {
  id: string;
  status: 'pending' | 'in_progress' | 'completed' | 'cancelled' | 'skipped' | 'failed';
  title: string;
  description: string;
  planStepId: string;
  required: boolean;
}

export interface TodosView {
  hasTodos: boolean;
  total: number;
  completed: number;
  inProgress: number;
  pending: number;
  items: TodoItemView[];
}

/** 会话当前 todo 清单（模型调 todo_write 后由面板渲染）。 */
export async function getTodos(sessionId: string): Promise<TodosView> {
  const resp = await fetch(`${BASE}/api/sessions/${encodeURIComponent(sessionId)}/todos`, {
    headers: headers(false),
  });
  return parse<TodosView>(resp);
}

// ---- 文件读取（工具行路径点击查看）----

export async function readFile(path: string): Promise<{ path: string; content: string; lines: number; error?: string }> {
  const resp = await fetch(`${BASE}/api/files/read?path=${encodeURIComponent(path)}`, {
    headers: headers(false),
  });
  return parse<{ path: string; content: string; lines: number; error?: string }>(resp);
}
// ---- coder / self 场景：在线代码开发（/api/code/*，见 CodeController）----
// scene: 'coder' = 用户工作区（默认根 data/workspace/coder/project 下选项目）；'self' = archon-dsh 源码目录（自我完善）。

export type CodeScene = 'coder' | 'self';

export interface CodeProject {
  name: string;
  displayName?: string;
  fileCount: number;
  /** 项目类型：maven / gradle / node / generic。 */
  projectType?: string;
  /** 场景根目录绝对路径（coder = 项目根；self = 源码根）。用于「在此目录开会话」。 */
  root?: string;
}

export interface CodeTreeNode {
  name: string;
  path: string;
  type: 'dir' | 'file';
  size?: number;
  children?: CodeTreeNode[];
  /** 节点类别：dir / structural（Maven 结构目录）/ source-root（源码根）/ package（合并包链）/ chain（普通单链合并）。 */
  kind?: string;
  /** package 节点：完整点分包路径（如 com.bizfty.anchon.dsh.api），name 为短包名。 */
  package?: string;
  /** chain 节点：完整相对路径标签。 */
  pathLabel?: string;
}

export interface CodeFileContent {
  path: string;
  content: string;
  lines: number;
  size?: number;
  error?: string;
}

/** 列出代码项目。self 场景返回单一根项目（源码目录本身）。 */
export async function listCodeProjects(scene: CodeScene = 'coder'): Promise<CodeProject[]> {
  const resp = await fetch(`${BASE}/api/code/projects?scene=${encodeURIComponent(scene)}`, { headers: headers(false) });
  return parse<CodeProject[]>(resp);
}

/** 创建代码项目（coder 根目录下建子目录；self 场景被后端拒绝）。 */
export async function createCodeProject(name: string, scene: CodeScene = 'coder'): Promise<CodeProject> {
  const resp = await fetch(`${BASE}/api/code/projects?scene=${encodeURIComponent(scene)}`, {
    method: 'POST',
    headers: headers(),
    body: JSON.stringify({ name }),
  });
  return parse<CodeProject>(resp);
}

/** 项目文件树（递归，排除构建产物）。self 场景 project 传 '.'（后端忽略）。 */
export async function getCodeTree(project: string, scene: CodeScene = 'coder'): Promise<CodeTreeNode> {
  // self 场景 project 固定为 '.'：路径段中的 '.' 会被 Spring 规范化丢弃导致路由 404，
  // 用 %2E 编码绕过（self 场景后端忽略 project 值）。
  const proj = scene === 'self' && project === '.' ? '%2E' : project;
  const resp = await fetch(`${BASE}/api/code/projects/${encodeURIComponent(proj)}/tree?scene=${encodeURIComponent(scene)}`, {
    headers: headers(false),
  });
  return parse<CodeTreeNode>(resp);
}

/** 读文件内容。 */
export async function readCodeFile(project: string, path: string, scene: CodeScene = 'coder'): Promise<CodeFileContent> {
  const resp = await fetch(
    `${BASE}/api/code/files?project=${encodeURIComponent(project)}&path=${encodeURIComponent(path)}&scene=${encodeURIComponent(scene)}`,
    { headers: headers(false) },
  );
  return parse<CodeFileContent>(resp);
}

/** 保存文件（覆盖写，自动建父目录）。 */
export async function saveCodeFile(project: string, path: string, content: string, scene: CodeScene = 'coder'): Promise<{ path: string; size: number }> {
  const resp = await fetch(`${BASE}/api/code/files?scene=${encodeURIComponent(scene)}`, {
    method: 'PUT',
    headers: headers(),
    body: JSON.stringify({ project, path, content }),
  });
  return parse<{ path: string; size: number }>(resp);
}

/** 新建文件（不覆盖已存在）。 */
export async function createCodeFile(project: string, path: string, scene: CodeScene = 'coder'): Promise<{ path: string }> {
  const resp = await fetch(`${BASE}/api/code/files?scene=${encodeURIComponent(scene)}`, {
    method: 'POST',
    headers: headers(),
    body: JSON.stringify({ project, path }),
  });
  return parse<{ path: string }>(resp);
}

/** 删除文件（或空目录）。 */
export async function deleteCodeFile(project: string, path: string, scene: CodeScene = 'coder'): Promise<{ deleted: string }> {
  const resp = await fetch(
    `${BASE}/api/code/files?project=${encodeURIComponent(project)}&path=${encodeURIComponent(path)}&scene=${encodeURIComponent(scene)}`,
    { method: 'DELETE', headers: headers(false) },
  );
  return parse<{ deleted: string }>(resp);
}
