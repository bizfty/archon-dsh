// 应用状态（Vue reactive 单例）：组件直接读写，视图自动响应。
import { reactive } from 'vue';
import { connectWorkspace } from './api';

export interface SessionSummary {
  id: string;
  title: string;
  model: string | null;
  cwd: string | null;
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

/**
 * 进行中的工作区连接（并发合并）：同一工作区连点「在此创建会话」时复用同一个
 * in-flight connectWorkspace，避免并发创建多个空白会话
 * （对齐 deepseek-harness WorkspaceRuntime.connecting Map）。
 */
const connectingWorkspaces = new Map<string, Promise<string | null>>();

export const appState = reactive({
  sessions: [] as SessionSummary[],
  sessionsPhase: 'pending' as 'pending' | 'ready',
  sessionsError: null as string | null,

  /** 工作区列表（对齐官方 WorkspaceView：每个工作区含其下会话 id）。 */
  workspaces: [] as import('./api').WorkspaceDto[],
  workspacesPhase: 'pending' as 'pending' | 'ready',
  workspacesError: null as string | null,
  /** 最近工作区 id（跨重启记忆，来自后端 settings）。 */
  recentWorkspaceId: null as string | null,
  /** 固定工作区根（来自 /api/dirs/roots，部署布局 dsh.workspace.root）。 */
  workspaceRoot: null as string | null,
  directoryRoots: null as { workspaceRoot: string; codeRoot: string; home: string } | null,

  sessionId: null as string | null,
  messages: [] as MessageView[],

  /**
   * 代码工具（coder/自我完善）当前作用目录（绝对路径）：
   * 由 CodeView 在项目/场景切换时同步；FloatingChat 用它过滤会话列表、
   * 并在「新建会话」时作为新会话 cwd（多会话按目录归组）。
   */
  codeCwd: null as string | null,

  /** 当前会话是否执行中（= runningBySession[sessionId]；切换会话时由辅助函数同步）。 */
  running: false,
  /** 当前会话的流式文本缓冲（= streamBuffers[sessionId]；切换会话时由辅助函数同步）。 */
  streamingText: '',
  /** 按会话隔离的执行状态：sessionId → 是否执行中。 */
  runningBySession: {} as Record<string, boolean>,
  /** 按会话隔离的流式文本缓冲：sessionId → 已流出的 token 文本（切回会话时恢复视图）。 */
  streamBuffers: {} as Record<string, string>,
  /** 当前会话草稿（Composer 双向绑定；切换会话时由 projectSession 存/取）。 */
  draft: '',
  /** 按会话隔离的草稿：sessionId → 草稿文本（对齐 deepseek-harness per-session input machine）。 */
  draftsBySession: {} as Record<string, string>,
  model: loadStoredModel(),
  models: [] as ModelDef[],
  modelsPhase: 'pending' as 'pending' | 'ready',
  modelsError: null as string | null,
  disabled: false,

  goal: null as GoalView | null,
  question: null as PendingQuestion | null,

  trajectory: null as import('./api').TrajectoryView | null,
  trajectoryLoading: false,

  view: 'chat' as 'chat' | 'plan' | 'goal' | 'trajectory' | 'jobs' | 'coder' | 'self' | 'mcp' | 'skills' | 'expert',
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

// ---- 按会话隔离的执行状态与流缓冲 ----
// appState.running / appState.streamingText 是「当前会话」的投影：
// 事件按 sessionId 写入字典，若该会话恰为当前会话则同步投影字段，组件无需感知隔离。

function syncCurrentProjection(): void {
  const id = appState.sessionId;
  appState.running = id ? !!appState.runningBySession[id] : false;
  appState.streamingText = id ? (appState.streamBuffers[id] ?? '') : '';
}

/** 设置某会话的执行状态；若为当前会话同步 running/disabled 投影。 */
export function setSessionRunning(sessionId: string, v: boolean): void {
  if (v) appState.runningBySession[sessionId] = true;
  else delete appState.runningBySession[sessionId];
  if (sessionId === appState.sessionId) {
    appState.running = v;
    if (!v) appState.disabled = false;
  }
}

/** 某会话是否执行中。 */
export function isSessionRunning(sessionId: string): boolean {
  return !!appState.runningBySession[sessionId];
}

/** 追加某会话的流式 token；若为当前会话同步 streamingText。 */
export function appendStream(sessionId: string, text: string): void {
  appState.streamBuffers[sessionId] = (appState.streamBuffers[sessionId] ?? '') + text;
  if (sessionId === appState.sessionId) appState.streamingText += text;
}

/** 清空某会话的流缓冲；若为当前会话同步 streamingText。 */
export function clearStream(sessionId: string): void {
  delete appState.streamBuffers[sessionId];
  if (sessionId === appState.sessionId) appState.streamingText = '';
}

/** 切换会话时调用：将 running/streamingText/disabled 投影到新会话。 */
export function projectSession(id: string | null): void {
  const from = appState.sessionId;
  // 切走前先把当前草稿存回源会话（普通切换：各会话草稿独立）
  if (from !== null && from !== id) {
    appState.draftsBySession[from] = appState.draft;
  }
  appState.sessionId = id;
  syncCurrentProjection();
  appState.disabled = !!id && !!appState.runningBySession[id];
  // 恢复目标会话草稿（无会话或从无草稿 → 空）
  appState.draft = id !== null ? (appState.draftsBySession[id] ?? '') : '';
}

/**
 * New Session 草稿迁移：把源会话的草稿文本迁移到目标会话并置为当前草稿，
 * 清空源会话草稿（对齐 deepseek-harness selectWorkspace 的 draft hand-off）。
 * @param fromSession 源会话 id（可为 null：无当前会话时仅设置目标）
 * @param toSession 目标会话 id
 * @param text 要迁移的草稿（空串不迁移）
 */
export function carryDraftTo(fromSession: string | null, toSession: string, text: string): void {
  if (!text) return;
  if (fromSession !== null && fromSession !== toSession) {
    appState.draftsBySession[fromSession] = '';
  }
  appState.draftsBySession[toSession] = text;
  appState.draft = text;
}

// ---- 工作区（Workspace：先选工作目录再开会话，对齐官方 workspaces service）----

/** 当前会话所属工作区（按 cwd 匹配；无会话或未匹配返回 null）。 */
export function workspaceOfCurrent(): import('./api').WorkspaceDto | null {
  const sid = appState.sessionId;
  if (!sid) return null;
  const s = appState.sessions.find(x => x.id === sid);
  if (!s || !s.cwd) return null;
  return appState.workspaces.find(w => w.path === s.cwd) ?? null;
}

/** 工作区 id → 其下会话（按 sessionIds 匹配；无则按 cwd 兜底）。 */
export function sessionsOfWorkspace(w: import('./api').WorkspaceDto): SessionSummary[] {
  const byId = new Set(w.sessionIds);
  const matched = appState.sessions.filter(s => byId.has(s.id));
  if (matched.length > 0) return matched;
  return appState.sessions.filter(s => s.cwd === w.path);
}

/** 拉取工作区列表 + 最近工作区。 */
export async function loadWorkspaces(): Promise<void> {
  appState.workspacesPhase = 'pending';
  appState.workspacesError = null;
  try {
    const [list, recent] = await Promise.all([
      import('./api').then(m => m.listWorkspaces()),
      import('./api').then(m => m.getRecentWorkspace()),
    ]);
    appState.workspaces = list;
    appState.recentWorkspaceId = recent.workspace_id ?? null;
    appState.workspacesPhase = 'ready';
  } catch (e) {
    appState.workspacesError = e instanceof Error ? e.message : String(e);
    appState.workspacesPhase = 'ready';
  }
}

/** 拉取部署布局固定根（工作区根/代码根/home）。失败不阻塞（目录浏览器回退 home）。 */
export async function loadDirectoryRoots(): Promise<void> {
  try {
    const roots = await import('./api').then(m => m.getDirectoryRoots());
    appState.directoryRoots = roots;
    appState.workspaceRoot = roots.workspaceRoot;
  } catch (e) {
    appState.directoryRoots = null;
    appState.workspaceRoot = null;
  }
}

/** 固定工作区：path 规范化后与 workspaceRoot 匹配的工作区（启动时自动注册的默认工作区）。 */
export function defaultWorkspace(): import('./api').WorkspaceDto | null {
  const root = appState.workspaceRoot;
  if (!root) return null;
  const norm = (p: string) => p.replace(/\\+$/, '');
  return appState.workspaces.find(w => norm(w.path) === norm(root)) ?? null;
}

/** 设置当前工作区并记忆为最近（对齐官方 recentWorkspaceId）。 */
export function setCurrentWorkspace(workspaceId: string): void {
  appState.recentWorkspaceId = workspaceId;
  void import('./api').then(m => m.setRecentWorkspace(workspaceId)).catch(() => undefined);
}

/**
 * 连接工作区（并发合并入口）：同一工作区的 in-flight 连接复用同一 promise，
 * 防止连点/重复触发产生多个空白会话。实际逻辑见 {@link doOpenWorkspace}。
 * @returns 采纳的会话 id（失败返回 null，不抛）。
 */
export async function openWorkspace(workspaceId: string): Promise<string | null> {
  const inflight = connectingWorkspaces.get(workspaceId);
  if (inflight) return inflight;
  const attempt = doOpenWorkspace(workspaceId).finally(() => {
    connectingWorkspaces.delete(workspaceId);
  });
  connectingWorkspaces.set(workspaceId, attempt);
  return attempt;
}

async function doOpenWorkspace(workspaceId: string): Promise<string | null> {
  try {
    // 静态导入 connectWorkspace（api.ts 零依赖）：vite 内联动态导入 + 解构会产生错误代码
    // （.then(n=>n.connectWorkspace(e)) 中 n 被提前解构成 {session}，导致 undefined）。
    const { session } = await connectWorkspace(workspaceId);
    setCurrentWorkspace(workspaceId);
    // 采纳会话：若列表里没有则插入（复用场景后端返回的可能是已存在会话）
    if (!appState.sessions.some(s => s.id === session.id)) {
      appState.sessions.unshift({
        id: session.id,
        title: session.title,
        model: session.model,
        cwd: session.cwd,
        updatedAt: session.updatedAt,
      });
    }
    projectSession(session.id);
    return session.id;
  } catch (e) {
    appState.workspacesError = e instanceof Error ? e.message : String(e);
    return null;
  }
}
/** 工作区显示名：title 或目录 basename。 */

/**
 * 按目录绝对路径在对应工作区开会话（代码编辑器「在此目录开会话」）。
 * createWorkspace 后端幂等：同 canonical path 返回既有工作区；随后 openWorkspace
 * 复用该目录已有 blank 会话或新建 cwd=path 的会话，并采纳为当前会话。
 * @returns 采纳的会话 id（失败返回 null，不抛）。
 */
export async function connectWorkspaceByPath(path: string): Promise<string | null> {
  if (!path) return null;
  try {
    const { createWorkspace } = await import('./api');
    const ws = await createWorkspace(path);
    return await openWorkspace(ws.id);
  } catch (e) {
    appState.workspacesError = e instanceof Error ? e.message : String(e);
    return null;
  }
}

export function workspaceLabel(w: import('./api').WorkspaceDto): string {
  if (w.title) return w.title;
  const p = w.path.replace(/\\+$/, '');
  const i = p.lastIndexOf('/');
  return i >= 0 ? p.slice(i + 1) : p;
}
