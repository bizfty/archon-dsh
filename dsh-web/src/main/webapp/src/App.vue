<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { Check } from '@element-plus/icons-vue';
import Sidebar from './components/Sidebar.vue';
import MsgView from './components/MsgView.vue';
import Composer from './components/Composer.vue';
import GoalView from './components/GoalView.vue';
import TrajectoryView from './components/TrajectoryView.vue';
import PlanView from './components/PlanView.vue';
import ToolsPage from './components/ToolsPage.vue';
import {
  appState, pushNotice, initTheme, setTheme, setModel, setPlanMode,
  THEMES, type ThemeKey, toggleSidebar,
} from './store';
import {
  listSessions, createSession, listMessages, deleteSession,
  chatStream, sendChat, listSubagents, sendSubagentMessage,
  getGoal, createGoal, updateGoal, pendingQuestions,
  getTrajectory, listModels,
  getPlanMode, enterPlanMode, exitPlanMode, submitPlanMode,
  type SseEvent,
} from './api';
import { WsClient, connectionLabel, type SessionFrame } from './ws';

/** 传输层可用性：WS 为主通道，连续建连失败回退 SSE（SSE 具备断线续流重连）。 */
const wsAvailable = ref(true);
let wsClient: WsClient | null = null;

// ---- 子代理对话抽屉（对齐官方 ui-subagent child transcript）----
const subagentDrawerOpen = ref(false);
const activeSubagent = ref<{ id: string; sessionId: string } | null>(null);
const subagentMessages = ref<{ id: string; role: string; content: string }[]>([]);
const subagentDraft = ref('');
const subagentBusy = ref(false);

/** 本 turn 是否已通过 TURN_ERROR 事件提示（避免与 HTTP catch 重复提示）。 */
let turnErrorNotified = false;

const SIDEBAR_EXPANDED = 260;
const SIDEBAR_COLLAPSED = 56;

const sidebarWidth = computed(() =>
  appState.sidebarCollapsed ? SIDEBAR_COLLAPSED : SIDEBAR_EXPANDED,
);

/** 工具类视图：以独立页面渲染（保留侧边栏，无 main 页签/输入 dock）。 */
const isToolView = computed(() =>
  appState.view === 'mcp' || appState.view === 'skills' || appState.view === 'jobs'
  || appState.view === 'expert' || appState.view === 'coder' || appState.view === 'self',
);

const currentTitle = computed(() => {
  if (!appState.sessionId) return '选择或新建一个会话';
  const s = appState.sessions.find((x) => x.id === appState.sessionId);
  return s?.title || '会话';
});

async function loadSessions(): Promise<void> {
  try {
    const list = await listSessions();
    appState.sessions = list.map((s) => ({ id: s.id, title: s.title, model: s.model, updatedAt: s.updatedAt }));
    appState.sessionsPhase = 'ready';
    appState.sessionsError = null;
  } catch (e) {
    appState.sessionsError = (e as Error).message;
  }
}

async function loadModels(): Promise<void> {
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

async function openSession(id: string): Promise<void> {
  if (appState.running) return;
  appState.sessionId = id;
  appState.messages = [];
  appState.goal = null;
  appState.trajectory = null;
  appState.view = 'chat';
  try {
    appState.messages = await listMessages(id) as never[];
  } catch (e) {
    pushNotice('加载消息失败: ' + (e as Error).message);
  }
  await refreshGoal(id);
  await refreshSubagents(id);
  await refreshPlan(id);
}

async function newSession(): Promise<void> {
  if (appState.running) return;
  try {
    const s = await createSession('新会话', appState.model);
    await loadSessions();
    await openSession(s.id);
  } catch (e) {
    pushNotice('新建会话失败: ' + (e as Error).message);
  }
}

async function removeSession(): Promise<void> {
  const id = appState.sessionId;
  if (!id || appState.running) return;
  if (!window.confirm('删除当前会话？')) return;
  try {
    await deleteSession(id);
    appState.sessionId = null;
    appState.messages = [];
    await loadSessions();
  } catch (e) {
    pushNotice('删除失败: ' + (e as Error).message);
  }
}

/** 计划页签「继续执行」：让 agent 继续按当前计划推进下一步（复用 send 驱动一轮）。 */
function continuePlan(): void {
  if (appState.running) return;
  void send('继续执行当前计划：检查计划进度，推进下一步（plan_get / plan_step_update），直到计划完成。');
}

async function send(text: string): Promise<void> {
  if (!text || appState.running) return;
  if (!appState.sessionId) {
    await newSession();
    if (!appState.sessionId) return;
  }
  const sessionId = appState.sessionId;
  turnErrorNotified = false;
  appState.messages = [...appState.messages, { id: 'local', role: 'user', content: text }];
  appState.draft = '';
  appState.disabled = true;
  appState.running = true;
  appState.streamingText = '';

  try {
    if (wsAvailable.value) {
      // 主通道：WS 下行 + HTTP 上行（对齐官方）
      await sendChat(sessionId, { message: text, model: appState.model });
    } else {
      // 回退通道：SSE 流式（chatStream 内置断线续流重连）
      await chatStream(sessionId, { message: text, model: appState.model }, (ev) => onSseEvent(ev)).promise;
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

function onSse(ev: SseEvent): void {
  onSseEvent(ev);
}

/** SSE 回退通道事件 → 与 WS 帧一致的 UI 更新。 */
function onSseEvent(ev: SseEvent): void {
  switch (ev.event) {
    case 'message':
      appState.streamingText += ev.content;
      break;
    case 'tool':
      appState.messages = [...appState.messages, {
        id: 'tool-' + Math.random().toString(36).slice(2, 8),
        role: 'tool', content: ev.message, toolName: ev.tool,
      }];
      break;
    case 'question':
      appState.question = { id: '', question: ev.question, options: ev.options, multiSelect: ev.multiSelect };
      void fetchQuestionId();
      break;
    case 'error':
      // SSE 通道的 turn 失败：标记已提示，避免与 HTTP catch 重复
      turnErrorNotified = true;
      appState.streamingText = '';
      appState.running = false;
      appState.disabled = false;
      pushNotice('任务失败: ' + ev.message);
      break;
    default:
      break;
  }
}

async function fetchQuestionId(): Promise<void> {
  try {
    const list = await pendingQuestions();
    if (list.length > 0 && appState.question) {
      appState.question = { ...appState.question, id: list[0].id };
    }
  } catch {
    /* 轮询失败保留未带 id 的问题（可重试） */
  }
}

function finalize(): void {
  appState.running = false;
  appState.disabled = false;
  appState.streamingText = '';
  const id = appState.sessionId;
  if (id) {
    listMessages(id).then((ms) => { appState.messages = ms as never[]; }).catch(() => undefined);
    loadSessions().catch(() => undefined);
    refreshGoal(id).catch(() => undefined);
    refreshSubagents(id).catch(() => undefined);
    refreshPlan(id).catch(() => undefined);
  }
}

function stop(): void {
  // HTTP 上行无可取消句柄（turn 由后端跑完）；WS 下行继续收事件。
  // 如需中断，可扩展后端取消端点；当前保持与官方一致：上行提交即执行。
}

/** 刷新当前会话的子代理列表（chat 顶部展示）。 */
async function refreshSubagents(id: string): Promise<void> {
  try {
    appState.subagents = await listSubagents(id);
  } catch {
    /* 子代理查询失败不阻塞主流程 */
  }
}

/** 侧边栏「工具」菜单 → 切换视图（工具为独立页面，见 ToolsPage.vue）。 */
function onSelectTool(id: string): void {
  appState.view = id as 'chat' | 'plan' | 'goal' | 'trajectory' | 'jobs' | 'coder' | 'self' | 'mcp' | 'skills' | 'expert';
}

// ---- 常驻 WebSocket 下行（对齐官方：退避重连 + connected/reconnecting）----
function onWsFrame(frame: SessionFrame): void {
  // 只处理当前会话的事件（frame 携带 sessionId；历史会话事件忽略）
  if (!appState.sessionId || frame.sessionId !== appState.sessionId) return;
  handleEvent(frame.event.eventType, frame.event.data);
}

function onWsState(state: 'connected' | 'reconnecting' | 'closed'): void {
  appState.connectionState = state;
}

function onWsAvailability(available: boolean): void {
  wsAvailable.value = available;
  if (!available) {
    pushNotice('WebSocket 不可用，已回退 SSE 通道');
  }
}

function onWsReconnected(): void {
  // 重连成功：resync 当前会话消息与子代理（断线期间可能遗漏）
  const id = appState.sessionId;
  if (id) {
    listMessages(id).then((ms) => { appState.messages = ms as never[]; }).catch(() => undefined);
    refreshSubagents(id).catch(() => undefined);
    refreshPlan(id).catch(() => undefined);
  }
}

/** 会话事件 → UI 状态（对齐官方事件流：token/tool/question 实时更新）。 */
function handleEvent(eventType: string, data: Record<string, unknown>): void {
  switch (eventType) {
    case 'ASSISTANT_TOKEN':
      appState.streamingText += String(data.content ?? '');
      break;
    case 'TOOL_CALL':
      appState.messages = [...appState.messages, {
        id: 'tool-' + Math.random().toString(36).slice(2, 8),
        role: 'tool', content: String(data.arguments ?? ''), toolName: String(data.tool ?? '工具'),
      }];
      break;
    case 'TOOL_RESULT':
    case 'TOOL_ERROR':
    case 'TOOL_DENIED':
    case 'TOOL_TIMEOUT':
      appState.messages = [...appState.messages, {
        id: 'tool-' + Math.random().toString(36).slice(2, 8),
        role: 'tool', content: String(data.content ?? data.message ?? ''), toolName: String(data.tool ?? '工具'),
      }];
      break;
    case 'QUESTION_REQUESTED':
      // ask_user_question 阻塞 → 前端渲染选择框（id 经 /questions/pending 获取）
      appState.question = {
        id: '',
        question: String(data.question ?? ''),
        options: Array.isArray(data.options) ? data.options.map(String) : [],
        multiSelect: Boolean(data.multiSelect),
      };
      void fetchQuestionId();
      break;
    case 'APPROVAL_REQUESTED':
      pushNotice(`等待审批: ${String(data.tool ?? '工具')}`);
      break;
    case 'TURN_ERROR':
      // turn 失败（如超过最大步数上限）：复位 UI + 明确提示 + 标记已处理
      turnErrorNotified = true;
      appState.streamingText = '';
      appState.running = false;
      appState.disabled = false;
      pushNotice(`任务失败: ${String(data.message ?? 'agent 执行出错')}`);
      {
        const errId = appState.sessionId;
        if (errId) {
          listMessages(errId).then((ms) => { appState.messages = ms as never[]; }).catch(() => undefined);
        }
      }
      break;
    case 'TURN_END':
      // turn 完成：拉取持久化消息刷新（最终 assistant 内容落库）
      appState.streamingText = '';
      {
        const tid = appState.sessionId;
        if (tid) {
          listMessages(tid).then((ms) => { appState.messages = ms as never[]; }).catch(() => undefined);
          loadSessions().catch(() => undefined);
          refreshGoal(tid).catch(() => undefined);
          refreshSubagents(tid).catch(() => undefined);
          refreshPlan(tid).catch(() => undefined);
        }
      }
      break;
    default:
      break;
  }
}

/** 点击子代理 chip：打开抽屉并加载其子会话消息。 */
async function openSubagent(sub: { id: string; sessionId: string }): Promise<void> {
  activeSubagent.value = sub;
  subagentDrawerOpen.value = true;
  subagentMessages.value = [];
  subagentDraft.value = '';
  try {
    subagentMessages.value = await listMessages(sub.sessionId) as never[];
  } catch (e) {
    pushNotice('加载子代理对话失败: ' + (e as Error).message);
  }
}

/** 抽屉内继续对话：给子代理发消息 → 追加回复 + 刷新。 */
async function sendToSubagent(): Promise<void> {
  const sub = activeSubagent.value;
  const text = subagentDraft.value.trim();
  if (!sub || !text || subagentBusy.value) return;
  subagentBusy.value = true;
  try {
    subagentMessages.value = [
      ...subagentMessages.value,
      { id: 'local-' + Math.random().toString(36).slice(2, 8), role: 'user', content: text },
    ];
    subagentDraft.value = '';
    const parentId = appState.sessionId;
    if (!parentId) return;
    const { reply } = await sendSubagentMessage(parentId, sub.id, text);
    subagentMessages.value = [
      ...subagentMessages.value,
      { id: 'reply-' + Math.random().toString(36).slice(2, 8), role: 'assistant', content: reply },
    ];
    // 子代理状态可能变化（DONE）：刷新顶部列表
    await refreshSubagents(parentId);
  } catch (e) {
    pushNotice('发送给子代理失败: ' + (e as Error).message);
  } finally {
    subagentBusy.value = false;
  }
}

async function refreshGoal(sessionId: string): Promise<void> {
  try {
    appState.goal = await getGoal(sessionId);
  } catch {
    appState.goal = null;
  }
}

function switchPlan(): void {
  appState.view = 'plan';
  if (appState.sessionId) {
    void refreshPlan(appState.sessionId);
  }
}

function switchGoal(): void {
  appState.view = 'goal';
  if (appState.sessionId) void refreshGoal(appState.sessionId);
}

function switchTrajectory(): void {
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

async function doGoalCreate(objective: string, maxGoalRounds?: number): Promise<void> {
  const id = appState.sessionId;
  if (!id) return;
  if (!objective) { pushNotice('目标不能为空'); return; }
  try {
    appState.goal = await createGoal(id, objective, maxGoalRounds);
  } catch (e) {
    pushNotice('创建目标失败: ' + (e as Error).message);
  }
}

async function doGoalUpdate(action: string, extra?: Record<string, unknown>): Promise<void> {
  const id = appState.sessionId;
  const goal = appState.goal;
  if (!id || !goal) return;
  try {
    appState.goal = await updateGoal(id, goal.id, goal.revision, action, extra as never);
  } catch (e) {
    pushNotice(`更新目标失败（${action}）: ` + (e as Error).message);
  }
}

function onThemePick(key: ThemeKey): void {
  setTheme(key);
}

function onModelChange(id: string): void {
  setModel(id);
}

function onCommand(name: string): void {
  if (name === 'goal') {
    switchGoal();
  } else if (name === 'plan') {
    switchPlan();
  }
}

// ---- 计划模式（联动后端 PlanModeService；对齐官方 plan-mode）----
/** 已提交的计划文本（供展示与继续执行参考）。 */
const planText = ref('');
/** 计划提交/切换是否进行中。 */
const planBusy = ref(false);

/** 打开会话时同步后端计划状态。 */
async function refreshPlan(sessionId: string): Promise<void> {
  try {
    const state = await getPlanMode(sessionId);
    setPlanMode(state.active);
    planText.value = state.planText;
  } catch {
    /* 计划状态同步失败不阻塞 */
  }
}

/** 切换计划模式：真实调用后端 enter/exit，成功后更新本地。 */
async function togglePlanModeBackend(): Promise<void> {
  const id = appState.sessionId;
  if (!id || planBusy.value) return;
  planBusy.value = true;
  try {
    if (appState.planMode) {
      await exitPlanMode(id);
      setPlanMode(false);
      pushNotice('已退出计划模式，可开始执行');
    } else {
      await enterPlanMode(id);
      setPlanMode(true);
      pushNotice('已进入计划模式：只规划，不实现');
    }
  } catch (e) {
    pushNotice('切换计划模式失败: ' + (e as Error).message);
  } finally {
    planBusy.value = false;
  }
}

/** 人类提交/更新计划（保存计划文本并退出计划模式）。 */
async function doSubmitPlan(): Promise<void> {
  const id = appState.sessionId;
  const text = planText.value.trim();
  if (!id) return;
  if (!text) { pushNotice('计划内容不能为空'); return; }
  planBusy.value = true;
  try {
    const state = await submitPlanMode(id, text);
    setPlanMode(false);
    planText.value = state.planText;
    pushNotice('计划已提交，退出计划模式');
  } catch (e) {
    pushNotice('提交计划失败: ' + (e as Error).message);
  } finally {
    planBusy.value = false;
  }
}

watch(() => appState.model, onModelChange);

onMounted(() => {
  initTheme();
  void loadSessions();
  void loadModels();
  // 常驻 WebSocket 下行：连接一次，事件实时推；断线自动退避重连（对齐官方）。
  // 连续建连失败 → onAvailabilityChange(false) → 回退 SSE 通道。
  wsClient = new WsClient({
    onFrame: onWsFrame,
    onStateChange: onWsState,
    onReconnected: onWsReconnected,
    onAvailabilityChange: onWsAvailability,
  });
  wsClient.connect();
});

onBeforeUnmount(() => {
  wsClient?.close();
  wsClient = null;
});
</script>

<template>
  <div class="app-shell">
    <aside class="sidebar" :style="{ width: sidebarWidth + 'px', minWidth: sidebarWidth + 'px' }">
      <Sidebar @new-session="newSession" @select-session="openSession" @select-tool="onSelectTool" />
    </aside>
    <!-- 工具独立页面（保留侧边栏，右侧为独立页面布局：无 main 页签/输入 dock） -->
    <ToolsPage v-if="isToolView" :view="appState.view" @back="appState.view = 'chat'" />
    <main v-else class="main">
      <header class="header">
        <div class="breadcrumb">
          <span class="dim">DSH Java</span>
          <span class="sep">›</span>
          <b>{{ currentTitle }}</b>
        </div>
        <div class="right">
          <button class="icon-btn sidebar-toggle" @click="toggleSidebar()" :title="appState.sidebarCollapsed ? '展开侧边栏' : '折叠侧边栏'">
            <span v-if="!appState.sidebarCollapsed">◀</span>
            <span v-else>▶</span>
          </button>
          <span class="safety">🛡️ 沙箱防护中</span>
          <span class="conn" :class="appState.connectionState">{{ connectionLabel(appState.connectionState) }}</span>
          <span v-if="appState.planMode" class="plan-chip" @click="togglePlanModeBackend()">📋 计划模式</span>
          <el-dropdown trigger="click" @command="onThemePick">
            <el-button size="small" round class="theme-btn">
              <span class="accent-dot" /> 皮肤
            </el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item
                  v-for="t in THEMES" :key="t.key" :command="t.key"
                  :class="{ 'is-active': t.key === appState.theme }"
                >
                  <span class="swatch" :style="{ background: t.accent }" />
                  <span class="theme-name">{{ t.name }}</span>
                  <el-icon v-if="t.key === appState.theme" class="check"><Check /></el-icon>
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
          <el-button size="small" @click="removeSession" v-if="appState.sessionId">🗑️ 删除</el-button>
        </div>
      </header>
      <!-- 会话子代理（对齐官方会话头部）：当前会话的子代理一览 -->
      <div v-if="appState.subagents.length > 0 && appState.view === 'chat'" class="subagents-bar">
        <span class="subagents-title">🤖 子代理</span>
        <div
          v-for="sa in appState.subagents"
          :key="sa.id"
          class="subagent-chip"
          :class="sa.status.toLowerCase()"
          :title="sa.lastContent ? sa.lastContent.slice(0, 200) : ''"
          @click="openSubagent(sa)"
        >
          <span class="dot"></span>
          <span class="name">{{ sa.id }}</span>
          <span class="meta">深度 {{ sa.delegationDepth }} · {{ sa.status }}</span>
        </div>
      </div>
      <nav class="tabs">
        <button class="tab" :class="{ active: appState.view === 'chat' }" @click="appState.view = 'chat'">💬 对话</button>
        <button class="tab" :class="{ active: appState.view === 'plan' }" @click="switchPlan">📋 计划</button>
        <button class="tab" :class="{ active: appState.view === 'goal' }" @click="switchGoal">🎯 目标</button>
        <button class="tab" :class="{ active: appState.view === 'trajectory' }" @click="switchTrajectory">🛤 轨迹</button>
      </nav>
      <!-- 消息流主列（常驻 dock 之上；对话/计划/目标/轨迹均为 body 视图切换） -->
      <div class="body">
        <MsgView v-show="appState.view === 'chat'" />
        <div v-show="appState.view === 'plan'" class="plan-view">
          <div class="plan-toolbar">
            <el-button
              size="small"
              :type="appState.planMode ? 'warning' : 'primary'"
              :loading="planBusy"
              @click="togglePlanModeBackend"
            >
              {{ appState.planMode ? '退出计划模式' : '进入计划模式' }}
            </el-button>
            <el-button size="small" type="success" :loading="planBusy" @click="doSubmitPlan">提交文本计划</el-button>
          </div>
          <el-alert
            v-if="appState.planMode"
            type="warning"
            :closable="false"
            show-icon
            title="计划模式已激活：agent 只规划，不实现代码/不改文件"
          />
          <el-collapse class="plan-text-collapse">
            <el-collapse-item title="文本计划（markdown，兼容旧流程）">
              <el-input
                v-model="planText"
                type="textarea"
                :rows="8"
                placeholder="计划内容（markdown）。进入计划模式后让 agent 调研并规划，或用 exit_plan_mode 提交。"
              />
            </el-collapse-item>
          </el-collapse>
          <div class="plan-dag-title">📊 DAG 计划（步骤 + 依赖，按依赖顺序执行）</div>
          <PlanView @continue="continuePlan" />
        </div>
        <GoalView v-show="appState.view === 'goal'" @create="doGoalCreate" @update="doGoalUpdate" />
        <TrajectoryView v-show="appState.view === 'trajectory'" />
      </div>
      <!-- 对话输入 dock（常驻，不属于任何 tab；对齐官方 conversation.input.dock） -->
      <div class="composer-dock">
        <Composer
          @send="send"
          @stop="stop"
          @clear="appState.messages = []"
          @command="onCommand"
        />
      </div>
    </main>

    <!-- 子代理对话抽屉（对齐官方 ui-subagent child transcript） -->
    <el-drawer
      v-model="subagentDrawerOpen"
      :title="activeSubagent ? '子代理 ' + activeSubagent.id : ''"
      size="480px"
      destroy-on-close
    >
      <div class="subagent-drawer" v-if="activeSubagent">
        <div class="subagent-msgs">
          <div v-for="m in subagentMessages" :key="m.id" class="subagent-msg" :class="m.role">
            <div class="subagent-bubble">{{ m.content }}</div>
          </div>
          <div v-if="subagentMessages.length === 0" class="subagent-empty">暂无消息</div>
        </div>
        <div class="subagent-input">
          <el-input
            v-model="subagentDraft"
            type="textarea"
            :rows="2"
            placeholder="继续对话…（Enter 发送 / Shift+Enter 换行）"
            :disabled="subagentBusy"
            @keydown.enter.exact.prevent="sendToSubagent"
          />
          <el-button type="primary" :loading="subagentBusy" :disabled="!subagentDraft.trim()" @click="sendToSubagent">
            发送
          </el-button>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.app-shell { display: flex; height: 100vh; overflow: hidden; }
.sidebar { background: var(--dsh-bg-0); border-right: 1px solid var(--dsh-border); transition: width .2s ease, min-width .2s ease; overflow: hidden; display: flex; flex-shrink: 0; }
.main { flex: 1; display: flex; flex-direction: column; min-width: 0; background: var(--dsh-bg-1); }
.header { display: flex; justify-content: space-between; align-items: center; padding: 12px 20px; border-bottom: 1px solid var(--dsh-border); flex-shrink: 0; }
.breadcrumb { font-size: 14px; }
.dim { color: var(--dsh-fg-2); }
.sep { color: var(--dsh-fg-2); margin: 0 6px; }
.right { display: flex; align-items: center; gap: 12px; }
.icon-btn {
  background: none; border: 1px solid var(--dsh-border); border-radius: 8px;
  color: var(--dsh-fg-2); cursor: pointer; padding: 5px 10px; font-size: 13px;
  display: flex; align-items: center; justify-content: center;
  transition: all .15s;
}
.icon-btn:hover { color: var(--dsh-accent); border-color: var(--dsh-accent); }
.safety { font-size: 12px; color: var(--dsh-success); border: 1px solid var(--dsh-border); padding: 4px 10px; border-radius: 16px; background: var(--dsh-bg-2); }
.plan-chip { font-size: 12px; color: var(--dsh-accent); border: 1px solid var(--dsh-accent); padding: 4px 10px; border-radius: 16px; background: var(--dsh-accent-soft); cursor: pointer; transition: background-color .15s; }
.plan-chip:hover { background: var(--dsh-accent); color: #fff; }
.tabs { display: flex; gap: 4px; padding: 8px 20px 0; flex-shrink: 0; }
.tab { padding: 7px 14px; border: none; background: none; color: var(--dsh-fg-2); font-size: 13px; cursor: pointer; border-radius: 8px 8px 0 0; font-family: inherit; }
.tab:hover { color: var(--dsh-fg-0); }
.tab.active { background: var(--dsh-bg-2); color: var(--dsh-fg-0); }
.body { flex: 1; display: flex; flex-direction: column; min-height: 0; overflow: hidden; }

/* 对话输入 dock（常驻，不属于任何 tab；对齐官方 conversation.input.dock + composer） */
.composer-dock {
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 8px 20px 12px;
  border-top: 1px solid var(--dsh-border);
  background: var(--dsh-bg-0);
}

/* 计划视图（对齐官方 plan-mode：进入/退出 + 提交计划 + DAG） */
.plan-view { flex: 1; display: flex; flex-direction: column; gap: 10px; padding: 16px 20px; overflow: hidden; min-height: 0; }
.plan-toolbar { display: flex; gap: 8px; align-items: center; }
.plan-view :deep(.el-textarea__inner) { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 13px; line-height: 1.6; }
.plan-text-collapse { border: 1px solid var(--dsh-border); border-radius: 10px; }
.plan-text-collapse :deep(.el-collapse-item__header) { background: var(--dsh-bg-2); color: var(--dsh-fg-0); font-size: 13px; }
.plan-text-collapse :deep(.el-collapse-item__content) { background: var(--dsh-bg-2); }
.plan-dag-title { font-size: 13px; color: var(--dsh-fg-2); margin-top: 4px; }

.theme-btn { display: flex; align-items: center; gap: 6px; }
.accent-dot { width: 10px; height: 10px; border-radius: 50%; background: var(--dsh-accent); }
.swatch { display: inline-block; width: 14px; height: 14px; border-radius: 4px; margin-right: 8px; vertical-align: middle; border: 1px solid var(--dsh-border); }
.theme-name { vertical-align: middle; }
.check { float: right; color: var(--dsh-accent); }
:deep(.el-dropdown-menu) { background: var(--dsh-bg-2); border-color: var(--dsh-border); }
:deep(.el-dropdown-menu__item) { color: var(--dsh-fg-0); }
:deep(.el-dropdown-menu__item:hover) { background: var(--dsh-accent-soft); }
:deep(.el-dropdown-menu__item.is-active) { background: var(--dsh-accent-soft); }

/* 连接状态徽标（对齐官方 connected/reconnecting） */
.conn { font-size: 12px; padding: 4px 10px; border-radius: 16px; border: 1px solid var(--dsh-border); color: var(--dsh-fg-2); }
.conn.connected { color: #2ecc71; }
.conn.reconnecting { color: #f5a623; }
.conn.closed { color: #e5484d; }

/* 子代理条（chat 顶部，对齐官方会话头部） */
.subagents-bar { display: flex; align-items: center; gap: 8px; padding: 6px 20px; overflow-x: auto; border-bottom: 1px solid var(--dsh-border); flex-shrink: 0; }
.subagents-title { font-size: 12px; color: var(--dsh-fg-2); white-space: nowrap; }
.subagent-chip { display: inline-flex; align-items: center; gap: 6px; background: var(--dsh-bg-2); border: 1px solid var(--dsh-border); border-radius: 14px; padding: 3px 10px; font-size: 12px; white-space: nowrap; cursor: pointer; }
.subagent-chip:hover { border-color: var(--dsh-accent); }
.subagent-chip .dot { width: 7px; height: 7px; border-radius: 50%; background: var(--dsh-fg-2); }
.subagent-chip.running .dot { background: var(--dsh-accent); }
.subagent-chip.done .dot { background: #2ecc71; }
.subagent-chip .name { color: var(--dsh-fg-0); }
.subagent-chip .meta { color: var(--dsh-fg-2); font-size: 11px; }


/* 子代理对话抽屉 */
.subagent-drawer { display: flex; flex-direction: column; height: 100%; }
.subagent-msgs { flex: 1; overflow-y: auto; display: flex; flex-direction: column; gap: 10px; padding-bottom: 12px; }
.subagent-msg { display: flex; }
.subagent-msg.user { justify-content: flex-end; }
.subagent-bubble { max-width: 85%; padding: 8px 12px; border-radius: 10px; background: var(--dsh-bg-2); border: 1px solid var(--dsh-border); white-space: pre-wrap; overflow-wrap: break-word; font-size: 13px; line-height: 1.5; }
.subagent-msg.user .subagent-bubble { background: var(--dsh-accent); border-color: var(--dsh-accent); color: #fff; }
.subagent-empty { text-align: center; color: var(--dsh-fg-2); padding: 30px 0; font-size: 13px; }
.subagent-input { display: flex; gap: 8px; align-items: flex-end; border-top: 1px solid var(--dsh-border); padding-top: 10px; }

</style>