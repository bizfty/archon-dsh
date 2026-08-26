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

export type ThemeKey = 'midnight' | 'aurora' | 'ember' | 'royal' | 'cloud' | 'paper';

export interface ThemeDef {
  key: ThemeKey;
  name: string;
  mode: 'dark' | 'light';
  accent: string;
  background: string;
}

export const THEMES: ThemeDef[] = [
  { key: 'midnight', name: '午夜', mode: 'dark', accent: '#4f7cff', background: '#1e1f24' },
  { key: 'aurora',   name: '极光', mode: 'dark', accent: '#2ecc71', background: '#162019' },
  { key: 'ember',    name: '余烬', mode: 'dark', accent: '#ff6b35', background: '#261e1b' },
  { key: 'royal',    name: '皇室', mode: 'dark', accent: '#8a5cf6', background: '#1d1930' },
  { key: 'cloud',    name: '云蓝', mode: 'light', accent: '#2e6dd6', background: '#f4f6fa' },
  { key: 'paper',    name: '纸白', mode: 'light', accent: '#c0392b', background: '#f8f3e8' },
];

const THEME_KEY = 'dsh.theme';

function loadStoredTheme(): ThemeKey {
  try {
    const v = localStorage.getItem(THEME_KEY);
    if (v && THEMES.some(t => t.key === v)) return v as ThemeKey;
  } catch { /* ignore */ }
  return 'midnight';
}

function applyThemeAttr(key: ThemeKey): void {
  const def = THEMES.find(t => t.key === key)!;
  const root = document.documentElement;
  if (key === 'midnight') {
    root.removeAttribute('data-theme');
  } else {
    root.setAttribute('data-theme', key);
  }
  root.classList.toggle('dsh-dark', def.mode === 'dark');
}

const _initialTheme = loadStoredTheme();

export interface ModelDef {
  id: string;
  name: string;
  group: string;
}

export interface CompactSummary {
  summary: string;
  shadowedCount: number;
  compactedAt: string;
}

const SIDEBAR_KEY = 'dsh.sidebar.collapsed';
const MODEL_KEY = 'dsh.model';
const PLAN_MODE_KEY = 'dsh.planMode';

function loadStoredSidebar(): boolean {
  try {
    return localStorage.getItem(SIDEBAR_KEY) === '1';
  } catch { return false; }
}

function loadStoredModel(): string {
  try {
    return localStorage.getItem(MODEL_KEY) || 'deepseek-chat';
  } catch { return 'deepseek-chat'; }
}

function loadStoredPlanMode(): boolean {
  try {
    return localStorage.getItem(PLAN_MODE_KEY) === '1';
  } catch { return false; }
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
  model: loadStoredModel(),
  models: [] as ModelDef[],
  modelsPhase: 'pending' as 'pending' | 'ready',
  modelsError: null as string | null,
  disabled: false,

  goal: null as GoalView | null,
  question: null as PendingQuestion | null,

  trajectory: null as import('./api').TrajectoryView | null,
  trajectoryLoading: false,

  view: 'chat' as 'chat' | 'plan' | 'goal' | 'trajectory' | 'jobs' | 'coder',
  notice: null as string | null,

  /** 常驻 WebSocket 下行连接状态（对齐官方 connected/reconnecting）。 */
  connectionState: 'connecting' as 'connecting' | 'connected' | 'reconnecting' | 'closed',

  /** 会话的子代理列表（chat 顶部展示，对齐官方 ui-subagent）。 */
  subagents: [] as { id: string; sessionId: string; delegationDepth: number; status: string; lastContent: string | null; createdAt: string | null }[],

  /** 当前会话的后台任务列表（⚙️ 任务 tab）。 */
  jobs: [] as import('./api').JobDto[],
  jobsPhase: 'pending' as 'pending' | 'ready',
  jobsError: null as string | null,

  theme: _initialTheme as ThemeKey,
  themeMode: (THEMES.find(t => t.key === _initialTheme)!.mode) as 'dark' | 'light',

  sidebarCollapsed: loadStoredSidebar(),

  planMode: loadStoredPlanMode(),
  compactSummary: null as CompactSummary | null,
  compacting: false,
});

export function setSidebarCollapsed(v: boolean): void {
  appState.sidebarCollapsed = v;
  try { localStorage.setItem(SIDEBAR_KEY, v ? '1' : '0'); } catch { /* ignore */ }
}

export function toggleSidebar(): void {
  setSidebarCollapsed(!appState.sidebarCollapsed);
}

export function setModel(id: string): void {
  appState.model = id;
  try { localStorage.setItem(MODEL_KEY, id); } catch { /* ignore */ }
}

export function setPlanMode(v: boolean): void {
  appState.planMode = v;
  try { localStorage.setItem(PLAN_MODE_KEY, v ? '1' : '0'); } catch { /* ignore */ }
}

export function togglePlanMode(): void {
  setPlanMode(!appState.planMode);
}export function setTheme(key: ThemeKey): void {
  const def = THEMES.find(t => t.key === key);
  if (!def) return;
  appState.theme = key;
  appState.themeMode = def.mode;
  try { localStorage.setItem(THEME_KEY, key); } catch { /* ignore */ }
  applyThemeAttr(key);
}

export function initTheme(): void {
  applyThemeAttr(appState.theme);
}

let noticeTimer: number | null = null;

export function pushNotice(text: string): void {
  appState.notice = text;
  if (noticeTimer !== null) window.clearTimeout(noticeTimer);
  noticeTimer = window.setTimeout(() => { appState.notice = null; }, 4000);
}

export function clearNotice(): void {
  appState.notice = null;
}