/**
 * floating.ts —— 跨视图浮层状态与动作（B/L3：App.vue 瘦身的配套下沉）。
 *
 * 承载「子代理对话抽屉」与「工作目录浏览器」这两处全局浮层的 UI 状态与业务动作。
 * 原定义在 App.vue script（owner 单例），现独立为组合式注入模块：MainShell / MsgView /
 * header 等任意组件可直接调用打开/关闭，宿主（App.vue）只负责渲染浮层 DOM。
 *
 * 克制：不引 cordis/React；纯 Vue reactive 单例 + api 动作，与 store 同构。
 */
import { reactive } from 'vue';
import { appState, pushNotice } from './store';
import { listMessages, sendSubagentMessage, listSubagents, createWorkspace } from './api';

// ---- 子代理对话抽屉（对齐官方 ui-subagent child transcript）----
export const subagentDrawer = reactive({
  open: false,
  active: null as { id: string; sessionId: string } | null,
  messages: [] as { id: string; role: string; content: string }[],
  draft: '',
  busy: false,
});

/** 点击子代理 chip：打开抽屉并加载其子会话消息。 */
export async function openSubagent(sub: { id: string; sessionId: string }): Promise<void> {
  subagentDrawer.active = sub;
  subagentDrawer.open = true;
  subagentDrawer.messages = [];
  subagentDrawer.draft = '';
  try {
    subagentDrawer.messages = await listMessages(sub.sessionId) as never[];
  } catch (e) {
    pushNotice('加载子代理对话失败: ' + (e as Error).message);
  }
}

/** 刷新当前会话的子代理列表（chat 顶部展示）。 */
export async function refreshSubagents(id: string): Promise<void> {
  try {
    appState.subagents = await listSubagents(id);
  } catch {
    /* 子代理查询失败不阻塞主流程 */
  }
}

/** 抽屉内继续对话：给子代理发消息 → 追加回复 + 刷新。 */
export async function sendToSubagent(): Promise<void> {
  const sub = subagentDrawer.active;
  const text = subagentDrawer.draft.trim();
  if (!sub || !text || subagentDrawer.busy) return;
  subagentDrawer.busy = true;
  try {
    subagentDrawer.messages = [
      ...subagentDrawer.messages,
      { id: 'local-' + Math.random().toString(36).slice(2, 8), role: 'user', content: text },
    ];
    subagentDrawer.draft = '';
    const parentId = appState.sessionId;
    if (!parentId) return;
    const { reply } = await sendSubagentMessage(parentId, sub.id, text);
    subagentDrawer.messages = [
      ...subagentDrawer.messages,
      { id: 'reply-' + Math.random().toString(36).slice(2, 8), role: 'assistant', content: reply },
    ];
    // 子代理状态可能变化（DONE）：刷新顶部列表
    await refreshSubagents(parentId);
  } catch (e) {
    pushNotice('发送给子代理失败: ' + (e as Error).message);
  } finally {
    subagentDrawer.busy = false;
  }
}

// ---- 工作目录浏览器（DirectoryBrowser：网页内目录树浏览）----
export const dirBrowser = reactive({
  open: false,
  busy: false,
});

/** 打开目录浏览器（「添加工作目录…」）。 */
export function openDirBrowser(): void {
  dirBrowser.open = true;
}

/** DirectoryBrowser 确认：createWorkspace(path) 采纳 → 由 caller 连接（返回工作区）。 */
export async function pickWorkspace(path: string): Promise<{ id: string } | null> {
  dirBrowser.busy = true;
  try {
    const ws = await createWorkspace(path);
    dirBrowser.open = false;
    return { id: ws.id };
  } catch (e) {
    pushNotice('创建工作区失败: ' + (e as Error).message);
    return null;
  } finally {
    dirBrowser.busy = false;
  }
}
