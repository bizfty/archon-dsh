/**
 * turn.ts —— 回合与传输层下行投影（B/L3 拆槽：App.vue 的 WS/SSE/回合逻辑下沉）。
 *
 * 统一承载：
 *   上行用户动作  send / stop / continuePlan；
 *   下行事件投影  handleEvent（WS 帧）/ onSseEvent（SSE 回退）/ finalize / fetchQuestionId；
 *   传输语义      onWsFrame / onWsState / onWsAvailability / onWsReconnected。
 *
 * App.vue 瘦身后只需：new WsClient(...) 把 onFrame/onState/onAvailability/onReconnected
 * 桥接到本模块的四个 onWs* 入口；MainShell(Composer/Plan 视图) 组合式调用 send/stop/continuePlan。
 * 全部状态经 store(appState) 共享 —— 不引 cordis，不搬渲染器。
 */
import { ref } from 'vue';
import { appState, pushNotice, setWsAvailable, setSessionRunning, appendStream, clearStream,
  isSessionRunning } from './store';
import { sendChat, chatStream, cancelChat, listMessages, pendingQuestions, type SseEvent } from './api';
import type { SessionFrame } from './ws';
import { sessionEventLog } from './eventLog';
import { refreshGoal, loadSessions, resyncSession, newSession } from './sessionActs';

/** 流式 SSE 句柄（回退通道；WS 主通道为 null）。 */
const activeStreamHandle = ref<{ abort: () => void; promise: Promise<void> } | null>(null);
/** 本回合是否已提示过错误（避免 HTTP 层与事件层重复提示）。 */
let turnErrorNotified = false;

/** 继续执行计划（将计划文本交给 agent 推进下一步）。 */
export function continuePlan(): void {
  void send('继续执行当前计划：检查计划进度，推进下一步（plan_get / plan_step_update），直到计划完成。');
}

/** 发送用户消息（主：WS 下行 + HTTP 上行；回退：SSE 流式）。 */
export async function send(text: string): Promise<void> {
  if (!text || appState.running) return;
  if (!appState.sessionId) {
    await newSession();
    if (!appState.sessionId) return;
  }
  const sessionId = appState.sessionId;
  turnErrorNotified = false;
  appState.messages = [...appState.messages, { id: 'local', role: 'user', content: text }];
  appState.draft = '';
  appState.draftsBySession[sessionId] = '';
  appState.disabled = true;
  setSessionRunning(sessionId, true);
  clearStream(sessionId);

  try {
    if (appState.wsAvailable) {
      // 主通道：WS 下行 + HTTP 上行（对齐官方）
      await sendChat(sessionId, { message: text, model: appState.model });
    } else {
      // 回退通道：SSE 流式（chatStream 内置断线续流重连）
      const handle = chatStream(sessionId, { message: text, model: appState.model }, (ev) => onSseEvent(sessionId, ev));
      activeStreamHandle.value = handle;
      try {
        await handle.promise;
      } finally {
        activeStreamHandle.value = null;
      }
    }
  } catch (e) {
    // TURN_ERROR 事件已提示过（含明确"任务失败"文案）则不重复提示
    if (!turnErrorNotified) {
      pushNotice('请求失败: ' + (e as Error).message);
    }
  } finally {
    turnErrorNotified = false;
    finalize();
  }
}

/** SSE 回退通道事件 → 与 WS 帧一致的 UI 更新（按 sessionId 归属）。 */
function onSseEvent(sessionId: string, ev: SseEvent): void {
  switch (ev.event) {
    case 'message':
      appendStream(sessionId, ev.content);
      break;
    case 'tool':
      // 仅当前会话实时渲染 tool 行；非当前会话切回时 listMessages 重拉
      if (sessionId === appState.sessionId) {
        appState.messages = [...appState.messages, {
          id: 'tool-' + Math.random().toString(36).slice(2, 8),
          role: 'tool', content: ev.message, toolName: ev.tool,
        }];
      }
      break;
    case 'question':
      if (sessionId === appState.sessionId) {
        appState.question = { id: '', question: ev.question, options: ev.options, multiSelect: ev.multiSelect };
        void fetchQuestionId();
      }
      break;
    case 'error':
      if (ev.errorType === 'cancelled') {
        // 用户「停止生成」：静默复位（stop() 已提示），保留已生成内容
        setSessionRunning(sessionId, false);
        clearStream(sessionId);
        break;
      }
      // SSE 通道的 turn 失败：标记已提示，避免与 HTTP catch 重复
      if (sessionId === appState.sessionId) turnErrorNotified = true;
      setSessionRunning(sessionId, false);
      clearStream(sessionId);
      pushNotice((sessionId !== appState.sessionId ? `[${sessionId.slice(0, 8)}] ` : '') + '任务失败: ' + ev.message);
      break;
    default:
      break;
  }
}

async function fetchQuestionId(): Promise<void> {
  if (!appState.sessionId) return;
  try {
    const list = await pendingQuestions(appState.sessionId);
    if (list.length > 0 && appState.question) {
      appState.question = { ...appState.question, id: list[0].id };
    }
  } catch {
    /* 轮询失败保留未带 id 的问题（可重试） */
  }
}

function finalize(): void {
  const id = appState.sessionId;
  if (id) {
    setSessionRunning(id, false);
    clearStream(id);
    resyncSession(id, { sessions: true });
  }
}

/** 停止生成：中止 SSE 流 + 后端协作式取消 + 本地复位重拉。 */
export async function stop(): Promise<void> {
  const id = appState.sessionId;
  if (!id || !isSessionRunning(id)) return;
  // 中止 SSE 流（防断线续流重连；WS 主通道无前端流）
  activeStreamHandle.value?.abort();
  activeStreamHandle.value = null;
  // 通知后端协作式取消（AgentLoop 在 step 间隙停止）
  try {
    await cancelChat(id);
  } catch (e) {
    // 后端不可达时仍本地复位（服务端 turn 会自然跑完，下次切换不受影响）
    pushNotice('停止请求未送达: ' + (e as Error).message);
  }
  // 本地复位 + 重拉消息（已生成内容保留）
  setSessionRunning(id, false);
  clearStream(id);
  pushNotice('已停止生成');
  listMessages(id).then((ms) => { appState.messages = ms as never[]; }).catch(() => undefined);
  loadSessions().catch(() => undefined);
  refreshGoal(id).catch(() => undefined);
}

// ---- 常驻 WebSocket 下行（对齐官方：退避重连 + connected/reconnecting）----
/** 桥接 WsClient.onFrame。 */
export function onWsFrame(frame: SessionFrame): void {
  // 有序交付：帧先经 EventLog 按会话内 seq 去重/缺口缓冲，仅按序就绪的帧才进 handleEvent。
  // 乱序帧滞留等待、重复帧丢弃；断线缺口由 onWsReconnected → resyncSession 全量兜底。
  const { ready } = sessionEventLog.accept(frame);
  for (const f of ready) {
    handleEvent(f.sessionId, f.event.eventType, f.event.data);
  }
}

/** 桥接 WsClient.onState。 */
export function onWsState(state: 'connected' | 'reconnecting' | 'closed'): void {
  appState.connectionState = state;
  if (state !== 'connected') {
    // 断线/关闭：旧连接的水位作废（后端重连不重放历史帧），重连后首帧重立基线。
    // 已应用帧由 resyncSession 全量重拉校正 —— EventLog 只防重连后重复应用。
    sessionEventLog.resetAll();
  }
}

/** 桥接 WsClient.onAvailable。 */
export function onWsAvailability(available: boolean): void {
  setWsAvailable(available);
  if (!available) {
    pushNotice('WebSocket 不可用，已回退 SSE 通道');
  }
}

/** 桥接 WsClient.onReconnected。 */
export function onWsReconnected(): void {
  // 重连成功：断线期间事件已丢失（后端不重放）→ 当前会话全量 resync。
  // EventLog 水位在 reconnecting 时已 resetAll，重连后首帧即新基线。
  const id = appState.sessionId;
  if (id) resyncSession(id);
}

/** 会话事件 → UI 状态（按 sessionId 归属；非当前会话仅更新 running/流缓冲）。 */
function handleEvent(sessionId: string, eventType: string, data: Record<string, unknown>): void {
  const isCurrent = sessionId === appState.sessionId;
  switch (eventType) {
    case 'ASSISTANT_TOKEN':
      appendStream(sessionId, String(data.content ?? ''));
      break;
    case 'TOOL_CALL':
      if (isCurrent) {
        appState.messages = [...appState.messages, {
          id: 'tool-' + Math.random().toString(36).slice(2, 8),
          role: 'tool', content: String(data.arguments ?? ''), toolName: String(data.tool ?? '工具'),
        }];
      }
      break;
    case 'TOOL_RESULT':
    case 'TOOL_ERROR':
    case 'TOOL_DENIED':
    case 'TOOL_TIMEOUT':
      if (isCurrent) {
        appState.messages = [...appState.messages, {
          id: 'tool-' + Math.random().toString(36).slice(2, 8),
          role: 'tool', content: String(data.content ?? data.message ?? ''), toolName: String(data.tool ?? '工具'),
        }];
      }
      break;
    case 'QUESTION_REQUESTED':
      // ask_user_question 阻塞 → 当前会话渲染选择框（id 经 /questions/pending 获取）
      if (isCurrent) {
        appState.question = {
          id: '',
          question: String(data.question ?? ''),
          options: Array.isArray(data.options) ? data.options.map(String) : [],
          multiSelect: Boolean(data.multiSelect),
        };
        void fetchQuestionId();
      }
      break;
    case 'APPROVAL_REQUESTED':
      // 审批跨会话也提示（后台会话等待审批时用户需知晓），带会话前缀
      pushNotice(`${isCurrent ? '' : '[' + sessionId.slice(0, 8) + '] '}等待审批: ${String(data.tool ?? '工具')}`);
      break;
    case 'TURN_ERROR':
      if (data.error_type === 'cancelled') {
        // 用户「停止生成」：静默复位（stop() 已提示），保留已生成内容
        setSessionRunning(sessionId, false);
        clearStream(sessionId);
        if (isCurrent) resyncSession(sessionId);
        break;
      }
      // turn 失败（如超过最大步数上限）：复位该会话运行状态 + 明确提示
      if (isCurrent) turnErrorNotified = true;
      setSessionRunning(sessionId, false);
      clearStream(sessionId);
      pushNotice(`${isCurrent ? '' : '[' + sessionId.slice(0, 8) + '] '}任务失败: ${String(data.message ?? 'agent 执行出错')}`);
      if (isCurrent) resyncSession(sessionId);
      break;
    case 'TURN_END':
      // turn 完成：复位该会话状态；当前会话则全量 resync（最终 assistant 内容落库）
      setSessionRunning(sessionId, false);
      clearStream(sessionId);
      if (isCurrent) {
        resyncSession(sessionId, { sessions: true });
      } else {
        loadSessions().catch(() => undefined); // 标题/时间可能更新，始终刷新侧边栏
      }
      break;
    default:
      break;
  }
}
