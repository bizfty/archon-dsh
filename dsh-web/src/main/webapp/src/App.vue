<script setup lang="ts">
/**
 * App.vue —— 薄壳（B/L3 拆槽后）。
 *
 * 职责收敛为「应用根装配」：
 *   1. 常驻 WebSocket 下行接线（桥接 turn.onWs*）+ 冷启动基线恢复；
 *   2. sidebar（chrome）装配 + 顶层视图分发：standalone 独立页（viewRegistry） vs MainShell；
 *   3. 全局浮层（子代理抽屉 / 目录浏览器，消费 floating.ts）渲染。
 *
 * 会话壳(main)与 body 视图已抽至 MainShell.vue + registerViews.ts —— 新增「槽/视图」
 * 只需 registerViews.register，无需改本文件。
 */
import { computed, onBeforeUnmount, onMounted, watch } from 'vue';
import Sidebar from './components/Sidebar.vue';
import MainShell from './components/MainShell.vue';
import DirectoryBrowser from './components/DirectoryBrowser.vue';
import { appState, initTheme, setModel, loadWorkspaces, loadDirectoryRoots, defaultWorkspace } from './store';
import { loadSessions, loadModels, newSession, openSession, connectToWorkspace,
  onSelectTool, pickWorkspaceAndConnect } from './sessionActs';
import { WsClient } from './ws';
import { onWsFrame, onWsState, onWsAvailability, onWsReconnected } from './turn';
import { openDirBrowser, subagentDrawer, sendToSubagent, dirBrowser } from './floating';
import './registerViews';
import { viewRegistry } from './registry';

let wsClient: WsClient | null = null;

const SIDEBAR_EXPANDED = 260;
const SIDEBAR_COLLAPSED = 56;
const sidebarWidth = computed(() => appState.sidebarCollapsed ? SIDEBAR_COLLAPSED : SIDEBAR_EXPANDED);

/** 当前 view 是否为 standalone 独立页（settings / 工具页）——是则顶层渲染独立页而非 MainShell。 */
const activeStandalone = computed(() => {
  const m = viewRegistry.get(appState.view);
  return m && m.shell === 'standalone' ? m : undefined;
});

watch(() => appState.model, (id: string) => setModel(id));

// ---- 冷启动恢复（对齐官方 startInitialSelection）：sessions + workspaces 基线就绪且无当前会话时，
// 自动连接最近工作区（复用其 blank 会话）；无最近工作区则连接固定默认工作区；连不上保持空态引导。
let initialSelectionStarted = false;
watch(
  () => [appState.sessionsPhase, appState.workspacesPhase, appState.sessionId] as const,
  () => {
    if (initialSelectionStarted) return;
    if (appState.sessionsPhase !== 'ready' || appState.workspacesPhase !== 'ready') return;
    initialSelectionStarted = true;
    if (appState.sessionId) return;
    const recent = appState.workspaces.find((w) => w.id === appState.recentWorkspaceId);
    if (recent) {
      void connectToWorkspace(recent.id);
    } else {
      const fixed = defaultWorkspace();
      if (fixed) void connectToWorkspace(fixed.id);
    }
  },
  { immediate: true },
);

onMounted(() => {
  initTheme();
  void loadSessions();
  void loadWorkspaces();
  void loadDirectoryRoots();
  void loadModels();
  // 常驻 WebSocket 下行：连接一次，事件实时推；断线自动退避重连（对齐官方）。
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

/** 目录浏览器采纳：createWorkspace → 连接（独立页/空态共用）。 */
function onPickWorkspace(path: string): void {
  void pickWorkspaceAndConnect(path);
}
function onDirCancel(): void {
  dirBrowser.open = false;
}
</script>

<template>
  <div class="app-shell">
    <aside class="sidebar" :style="{ width: sidebarWidth + 'px', minWidth: sidebarWidth + 'px' }">
      <Sidebar
        @new-session="newSession"
        @new-session-in="(wsId: string) => connectToWorkspace(wsId)"
        @select-session="(id: string) => openSession(id)"
        @select-tool="(id: string) => onSelectTool(id)"
        @add-workspace="openDirBrowser"
      />
    </aside>

    <!-- 顶层视图分发：standalone 独立页（注册表装配，保留侧边栏） / main 会话壳 -->
    <component :is="activeStandalone?.component" v-if="activeStandalone" />
    <MainShell v-else />

    <!-- 子代理对话抽屉（对齐官方 ui-subagent child transcript；状态在 floating.ts） -->
    <el-drawer
      v-model="subagentDrawer.open"
      :title="subagentDrawer.active ? '子代理 ' + subagentDrawer.active.id : ''"
      size="480px"
      destroy-on-close
    >
      <template #default>
        <div class="subagent-drawer" v-if="subagentDrawer.active">
          <div class="subagent-msgs">
            <div v-for="m in subagentDrawer.messages" :key="m.id" class="subagent-msg" :class="m.role">
              <div class="subagent-bubble">{{ m.content }}</div>
            </div>
            <div v-if="subagentDrawer.messages.length === 0" class="subagent-empty">暂无消息</div>
          </div>
          <div class="subagent-input">
            <el-input
              v-model="subagentDrawer.draft"
              type="textarea"
              :rows="2"
              placeholder="继续对话…（Enter 发送 / Shift+Enter 换行）"
              :disabled="subagentDrawer.busy"
              @keydown.enter.exact.prevent="sendToSubagent"
            />
            <el-button type="primary" :loading="subagentDrawer.busy" :disabled="!subagentDrawer.draft.trim()" @click="sendToSubagent">
              发送
            </el-button>
          </div>
        </div>
      </template>
    </el-drawer>

    <!-- 工作目录选择（DirectoryBrowser：网页内目录树浏览；状态在 floating.ts） -->
    <DirectoryBrowser
      :open="dirBrowser.open"
      :busy="dirBrowser.busy"
      :initial-path="appState.workspaceRoot ?? undefined"
      :roots="appState.directoryRoots ?? undefined"
      @pick="onPickWorkspace"
      @cancel="onDirCancel"
    />
  </div>
</template>

<style scoped>
.app-shell { display: flex; height: 100vh; overflow: hidden; }
.sidebar { background: var(--dsh-bg-0); border-right: 1px solid var(--dsh-border); transition: width .2s ease, min-width .2s ease; overflow: hidden; display: flex; flex-shrink: 0; }
:deep(.subagent-drawer) { display: flex; flex-direction: column; height: 100%; }
:deep(.subagent-msgs) { flex: 1; overflow-y: auto; display: flex; flex-direction: column; gap: 10px; padding-bottom: 12px; }
:deep(.subagent-msg) { display: flex; }
:deep(.subagent-msg.user) { justify-content: flex-end; }
:deep(.subagent-bubble) { max-width: 85%; padding: 8px 12px; border-radius: 10px; background: var(--dsh-bg-2); border: 1px solid var(--dsh-border); white-space: pre-wrap; overflow-wrap: break-word; font-size: 13px; line-height: 1.5; }
:deep(.subagent-msg.user .subagent-bubble) { background: var(--dsh-accent); border-color: var(--dsh-accent); color: #fff; }
:deep(.subagent-empty) { text-align: center; color: var(--dsh-fg-2); padding: 30px 0; font-size: 13px; }
:deep(.subagent-input) { display: flex; gap: 8px; align-items: flex-end; border-top: 1px solid var(--dsh-border); padding-top: 10px; }
</style>
