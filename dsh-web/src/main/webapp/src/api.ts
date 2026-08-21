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
  | { event: 'error'; message: string }
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
export async function createSession(title?: string, model?: string): Promise<SessionDto> {
  const body: CreateSessionRequest = {};
  if (title) body.title = title;
  if (model) body.model = model;
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

/**
 * SSE 流式对话：POST /api/sessions/{id}/chat/stream
 * 逐个事件回调；返回句柄 { promise, abort } — abort() 中止流（对应前端 Stop 按钮）。
 */
export function chatStream(
  sessionId: string,
  request: ChatRequest,
  onEvent: (ev: SseEvent) => void,
): { promise: Promise<void>; abort: () => void } {
  const controller = new AbortController();
  const promise = new Promise<void>((resolve, reject) => {
    fetch(`${BASE}/api/sessions/${encodeURIComponent(sessionId)}/chat/stream`, {
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
              resolve();
              return;
            }
            buffer += decoder.decode(value, { stream: true });
            // SSE 事件以空行分隔
            let idx: number;
            while ((idx = buffer.indexOf('\n\n')) >= 0) {
              const rawEvent = buffer.slice(0, idx);
              buffer = buffer.slice(idx + 2);
              emit(rawEvent, onEvent);
            }
            pump();
          }).catch((err) => reject(err));
        };
        pump();
      })
      .catch((err) => {
        if (err instanceof DOMException && err.name === 'AbortError') {
          resolve(); // 用户主动停止：视为正常结束
        } else {
          reject(err);
        }
      });
  });
  return { promise, abort: () => controller.abort() };
}

function emit(rawEvent: string, onEvent: (ev: SseEvent) => void): void {
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
      onEvent({ event: 'error', message: String(payload.message ?? '') });
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
  question: string;
  options: string[];
  multiSelect: boolean;
}

/** 挂起的用户问题（模型在 ask_user_question 上阻塞等待）。 */
export async function pendingQuestions(): Promise<PendingQuestion[]> {
  const resp = await fetch(`${BASE}/api/interactions/questions/pending`, { headers: headers(false) });
  return parse<PendingQuestion[]>(resp);
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
