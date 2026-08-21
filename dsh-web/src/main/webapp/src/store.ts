// 应用状态（Vue reactive 单例）：组件直接读写，视图自动响应。
import { reactive } from 'vue';

export interface SessionSummary {
  id: string;
  title: string;
  model: string | null;
  updatedAt: string;
}

export interface MessageView {
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
  revision: number;
}

/** 挂起的用户问题（模型在 ask_user_question 上阻塞，前端渲染选择框）。 */
export interface PendingQuestion {
  id: string;
  question: string;
  options: string[];
  multiSelect: boolean;
}

export const appState = reactive({
  sessions: [] as SessionSummary[],
  sessionsPhase: 'pending' as 'pending' | 'ready',
  sessionsError: null as string | null,

  sessionId: null as string | null,
  messages: [] as MessageView[],
  running: false,
  streamingText: '',
  draft: '',
  model: 'deepseek-chat',
  disabled: false,

  goal: null as GoalView | null,
  question: null as PendingQuestion | null,

  view: 'chat' as 'chat' | 'goal',
  notice: null as string | null,
});

let noticeTimer: number | null = null;

/** 瞬态错误提示（自管理 4s 自动消失）。 */
export function pushNotice(text: string): void {
  appState.notice = text;
  if (noticeTimer !== null) window.clearTimeout(noticeTimer);
  noticeTimer = window.setTimeout(() => { appState.notice = null; }, 4000);
}

/** 清空通知。 */
export function clearNotice(): void {
  appState.notice = null;
}
