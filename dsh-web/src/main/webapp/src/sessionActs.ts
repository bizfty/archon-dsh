/**
 * sessionActs.ts —— 会话级动作层（B/L3 拆槽：App.vue 的会话/视图切换逻辑下沉）。
 *
 * 原内联于 App.vue script（owner 单例）。现独立为组合式注入模块，供
 * Sidebar / MainShell(header) / MsgView / GoalView / 各视图模块共用——任意组件可直接调用，
 * 达成「新增视图免改 App.vue」的组合式底座（对齐官方 business share 轻量版）。
 *
 * 克制：不含 WS 下行接线（留在 App.vue/宿主持有）；本层是「用户动作」→ api + store 投影。
 */
import { computed } from 'vue';
import { appState, pushNotice, projectSession, isSessionRunning,
  carryDraftTo, workspaceOfCurrent, workspaceLabel, openWorkspace, loadWorkspaces } from './store';
import { listSessions, listMessages, deleteSession, listModels, getGoal,
  createGoal, updateGoal, getTrajectory } from './api';
import { refreshSubagents, openDirBrowser, pickWorkspace as floatingPick } from './floating';
import { refreshPlanState } from './planMode';

// ---- 会话列表 / 模型 ----
export async function loadSessions(): Promise<void> {
  try {
    const list = await listSessions();
    appState.sessions = list.map((s) => ({ id: s.id, title: s.title, model: s.model, cwd: s.cwd, updatedAt: s.updatedAt }));
    appState.sessionsPhase = 'ready';
    appState.sessionsError = null;
  } catch (e) {
    appState.sessionsError = (e as Error).message;
  }
}

export async function loadModels(): Promise<void> {
  appState.modelsPhase = 'pending';
  try {
    const list = await listModels();
    appState.models = list.map((m) => ({ id: m.id, name: m.name, group: m.group }));
    appState.modelsPhase = 'ready';
    appState.modelsError = null;
    if (!appState.models.find(m => m.id === appState.model)) {
      appState.model = list[0]?.id || 'deepseek-chat';
    }
  } catch (e) {
    appState.modelsError = (e as Error).message;
    appState.modelsPhase = 'ready';
  }
}

// ---- 目标 ----
export async function refreshGoal(sessionId: string): Promise<void> {
  try {
    appState.goal = await getGoal(sessionId);
  } catch {
    appState.goal = null;
  }
}

export async function doGoalCreate(objective: string, maxGoalRounds?: number): Promise<void> {
  const id = appState.sessionId;
  if (!id) return;
  if (!objective) { pushNotice('目标不能为空'); return; }
  try {
    appState.goal = await createGoal(id, objective, maxGoalRounds);
  } catch (e) {
    pushNotice('创建目标失败: ' + (e as Error).message);
  }
}

export async function doGoalUpdate(action: string, extra?: Record<string, unknown>): Promise<void> {
  const id = appState.sessionId;
  const goal = appState.goal;
  if (!id || !goal) return;
  try {
    appState.goal = await updateGoal(id, goal.id, goal.revision, action, extra as never);
  } catch (e) {
    pushNotice(`更新目标失败（${action}）: ` + (e as Error).message);
  }
}

// ---- 会话打开 / 新建 / 切换 ----
export async function openSession(id: string): Promise<void> {
  if (id === appState.sessionId) return; // 点击当前会话本身：无需操作
  // 流按会话隔离：切换不打断任何会话的执行；视图投影到目标会话的运行状态/流缓冲
  projectSession(id);
  appState.messages = [];
  appState.goal = null;
  appState.trajectory = null;
  appState.question = null;
  appState.view = 'chat';
  try {
    appState.messages = await listMessages(id) as never[];
  } catch (e) {
    pushNotice('加载消息失败: ' + (e as Error).message);
  }
  await refreshGoal(id);
  await refreshSubagents(id);
  await refreshPlanState(id);
}

export async function newSession(): Promise<void> {
  // 对齐官方「先选工作目录再开会话」：目标工作区 = 当前会话所属 → 最近 → 无则打开目录浏览器引导
  const current = workspaceOfCurrent();
  const target = current?.id
    ?? (appState.recentWorkspaceId && appState.workspaces.some(w => w.id === appState.recentWorkspaceId)
      ? appState.recentWorkspaceId
      : null);
  if (!target) {
    openDirBrowser(); // 空状态引导：先选工作目录
    return;
  }
  await connectToWorkspace(target);
}

/** 连接工作区（复用其 blank 会话或新建）并设为当前会话。 */
export async function connectToWorkspace(workspaceId: string): Promise<void> {
  // New Session：openWorkspace（内部 projectSession）会 stash 旧草稿，先快照源会话与草稿
  const fromId = appState.sessionId;
  const carriedDraft = appState.draft;
  try {
    const sessionId = await openWorkspace(workspaceId);
    if (sessionId) {
      // 草稿跟着用户走：迁移到新会话，清空源会话（对齐 deepseek-harness selectWorkspace）
      carryDraftTo(fromId, sessionId, carriedDraft);
      await loadSessions();
      await openSession(sessionId);
    }
  } catch (e) {
    pushNotice('连接工作区失败: ' + (e as Error).message);
  }
}

/** 工作区 chip 菜单命令：'add' → 目录浏览；'ws:{id}' → 连接该工作区。 */
export function onWorkspaceCommand(cmd: string): void {
  if (cmd === 'add') {
    openDirBrowser();
    return;
  }
  if (cmd.startsWith('ws:')) {
    void connectToWorkspace(cmd.slice(3));
  }
}

/** 当前显示的 workspace label（chip）：当前会话所属 → 最近工作区 → 占位「选择工作目录」。 */
export const currentWorkspaceLabel = computed(() => {
  const cur = workspaceOfCurrent();
  if (cur) return '📁 ' + workspaceLabel(cur);
  const recent = appState.workspaces.find(w => w.id === appState.recentWorkspaceId);
  if (recent) return '📁 ' + workspaceLabel(recent);
  return '📁 选择工作目录';
});

/** DirectoryBrowser 确认：createWorkspace(path) → 连接该工作区（自动开会话）。 */
export async function pickWorkspaceAndConnect(path: string): Promise<void> {
  const ws = await floatingPick(path);
  if (!ws) return;
  await loadWorkspaces();
  await connectToWorkspace(ws.id);
}

export async function removeSession(): Promise<void> {
  const id = appState.sessionId;
  if (!id) return;
  const running = isSessionRunning(id);
  if (!window.confirm(running ? '该会话正在执行中，删除后任务将中断且不可恢复，确定删除？' : '删除当前会话？')) return;
  try {
    await deleteSession(id);
    delete appState.draftsBySession[id];
    projectSession(null);
    appState.messages = [];
    appState.goal = null;
    appState.trajectory = null;
    appState.question = null;
    await loadSessions();
  } catch (e) {
    pushNotice('删除失败: ' + (e as Error).message);
  }
}

/**
 * 当前会话全量域重拉（resync 基线）：断线重连 / 回合收尾 / 事件缺口后的最终一致兜底。
 * opts.sessions=true 时额外刷新侧栏会话列表（标题/时间随回合可能更新）。
 * fire-and-forget（与原 .then() 并行模式等价），单项失败静默保留旧值。
 */
export function resyncSession(sessionId: string, opts: { sessions?: boolean } = {}): void {
  listMessages(sessionId).then((ms) => { appState.messages = ms as never[]; }).catch(() => undefined);
  refreshGoal(sessionId).catch(() => undefined);
  refreshSubagents(sessionId).catch(() => undefined);
  refreshPlanState(sessionId).catch(() => undefined);
  if (opts.sessions) loadSessions().catch(() => undefined);
}

// ---- 视图切换（sidebar 工具菜单 / tabs）----
/** 侧边栏「工具」菜单 → 切换视图（工具为独立页面）。 */
export function onSelectTool(id: string): void {
  appState.view = id as never;
}

export function switchPlan(): void {
  appState.view = 'plan';
  if (appState.sessionId) void refreshPlanState(appState.sessionId);
}

export function switchGoal(): void {
  appState.view = 'goal';
  if (appState.sessionId) void refreshGoal(appState.sessionId);
}

export function switchTrajectory(): void {
  appState.view = 'trajectory';
  if (appState.sessionId) {
    appState.trajectoryLoading = true;
    getTrajectory(appState.sessionId).then(t => {
      appState.trajectory = t;
      appState.trajectoryLoading = false;
    }).catch(e => {
      appState.notice = '加载轨迹失败: ' + (e as Error).message;
      appState.trajectoryLoading = false;
    });
  }
}

/** 命令（Composer / 计划继续）：goals / plan 等。 */
export function onCommand(name: string): void {
  if (name === 'goal') switchGoal();
  else if (name === 'plan') switchPlan();
}
