<script setup lang="ts">
// 布局壳 + 业务编排（对应 DSH runtime/service 层；组件只渲染 + 派发）。
import { computed, onMounted } from 'vue';
import Sidebar from './components/Sidebar.vue';
import MessageList from './components/MessageList.vue';
import Composer from './components/Composer.vue';
import GoalView from './components/GoalView.vue';
import { appState, pushNotice } from './store';
import {
  listSessions, createSession, listMessages, deleteSession,
  chatStream, getGoal, createGoal, updateGoal, pendingQuestions,
  type SseEvent,
} from './api';

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

async function openSession(id: string): Promise<void> {
  if (appState.running) return;
  appState.sessionId = id;
  appState.messages = [];
  appState.goal = null;
  appState.view = 'chat';
  try {
    appState.messages = await listMessages(id) as never[];
  } catch (e) {
    pushNotice('加载消息失败: ' + (e as Error).message);
  }
  await refreshGoal(id);
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

let activeAbort: (() => void) | null = null;

async function send(text: string): Promise<void> {
  if (!text || appState.running) return;
  if (!appState.sessionId) {
    await newSession();
    if (!appState.sessionId) return;
  }
  const sessionId = appState.sessionId;
  appState.messages = [...appState.messages, { id: 'local', role: 'user', content: text }];
  appState.draft = '';
  appState.disabled = true;
  appState.running = true;
  appState.streamingText = '';

  const handle = chatStream(sessionId, { message: text, model: appState.model }, (ev) => onSse(ev));
  activeAbort = handle.abort;
  try {
    await handle.promise;
  } catch (e) {
    pushNotice('请求失败: ' + (e as Error).message);
  } finally {
    finalize();
  }
}

function onSse(ev: SseEvent): void {
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
      // ask_user_question 阻塞 → 前端渲染选择框（MessageList 消费；id 经 /questions/pending 获取）
      appState.question = { id: '', question: ev.question, options: ev.options, multiSelect: ev.multiSelect };
      void fetchQuestionId();
      break;
    case 'error':
      pushNotice(ev.message);
      break;
    default:
      break;
  }
}

/** SSE 事件不带 question id：从 pending 列表补齐（submitAnswer 需要 id）。 */
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
  activeAbort = null;
  appState.running = false;
  appState.disabled = false;
  appState.streamingText = '';
  const id = appState.sessionId;
  if (id) {
    listMessages(id).then((ms) => { appState.messages = ms as never[]; }).catch(() => undefined);
    loadSessions().catch(() => undefined);
    refreshGoal(id).catch(() => undefined);
  }
}

function stop(): void {
  activeAbort?.();
}

async function refreshGoal(sessionId: string): Promise<void> {
  try {
    appState.goal = await getGoal(sessionId);
  } catch {
    appState.goal = null;
  }
}

function switchGoal(): void {
  appState.view = 'goal';
  if (appState.sessionId) void refreshGoal(appState.sessionId);
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

onMounted(() => { void loadSessions(); });
</script>

<template>
  <div class="app-shell dsh-dark">
    <aside class="sidebar">
      <Sidebar @new-session="newSession" @select-session="openSession" />
    </aside>
    <main class="main">
      <header class="header">
        <div class="breadcrumb">
          <span class="dim">DSH Java</span>
          <span class="sep">›</span>
          <b>{{ currentTitle }}</b>
        </div>
        <div class="right">
          <span class="safety">🛡️ 沙箱防护中</span>
          <el-button size="small" @click="removeSession" v-if="appState.sessionId">🗑️ 删除</el-button>
        </div>
      </header>
      <nav class="tabs">
        <button class="tab" :class="{ active: appState.view === 'chat' }" @click="appState.view = 'chat'">💬 对话</button>
        <button class="tab" :class="{ active: appState.view === 'goal' }" @click="switchGoal">🎯 目标</button>
      </nav>
      <div class="body">
        <MessageList v-show="appState.view === 'chat'" />
        <GoalView v-show="appState.view === 'goal'" @create="doGoalCreate" @update="doGoalUpdate" />
      </div>
      <Composer
        v-show="appState.view === 'chat'"
        @send="send"
        @stop="stop"
        @clear="appState.messages = []"
      />
    </main>
  </div>
</template>

<style scoped>
.app-shell { display: flex; height: 100vh; overflow: hidden; }
.sidebar { width: 260px; min-width: 260px; background: #17181d; border-right: 1px solid #33343d; }
.main { flex: 1; display: flex; flex-direction: column; min-width: 0; }
.header { display: flex; justify-content: space-between; align-items: center; padding: 12px 20px; border-bottom: 1px solid #33343d; }
.breadcrumb { font-size: 14px; }
.dim { color: #9a9ba6; }
.sep { color: #9a9ba6; margin: 0 6px; }
.right { display: flex; align-items: center; gap: 12px; }
.safety { font-size: 12px; color: #2ecc71; border: 1px solid #33343d; padding: 4px 10px; border-radius: 16px; }
.tabs { display: flex; gap: 4px; padding: 8px 20px 0; }
.tab { padding: 7px 14px; border: none; background: none; color: #9a9ba6; font-size: 13px; cursor: pointer; border-radius: 8px 8px 0 0; font-family: inherit; }
.tab.active { background: #26272e; color: #e6e6eb; }
.body { flex: 1; display: flex; flex-direction: column; min-height: 0; }
</style>
