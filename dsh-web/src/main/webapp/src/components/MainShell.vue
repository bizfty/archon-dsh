<script setup lang="ts">
/**
 * MainShell.vue —— main 会话壳（B/L3：原 App.vue `<main>` 块整体抽出）。
 *
 * 承载会话主工作区：header（breadcrumb/连接状态/计划 chip/皮肤/工作区 chip/删除）、
 * 子代理条、nav.tabs + body（均遍历 viewRegistry.list('main')，新增 main 视图免改本壳）、
 * composer dock。
 *
 * 自包含：动作经组合式注入模块取用（turn.send/stop、sessionActs.*、planMode.*、floating.*），
 * 不向 App.vue 回调业务 —— 本壳只负责 main 区装配。
 */
import { computed } from 'vue';
import { Check } from '@element-plus/icons-vue';
import Composer from './Composer.vue';
import type { Component } from 'vue';
import { viewRegistry } from '../registry';
import { appState, setTheme, THEMES, workspaceLabel, workspaceOfCurrent, sessionsOfWorkspace } from '../store';
import { connectionLabel } from '../ws';
import { send, stop } from '../turn';
import { onWorkspaceCommand, currentWorkspaceLabel, removeSession, onCommand } from '../sessionActs';
import { openSubagent } from '../floating';
import { togglePlanModeBackend } from '../planMode';

/** main 壳 body 视图（chat/plan/goal/trajectory…后续在此注册即自动出现）。 */
const mainModules = viewRegistry.list('main');
/** nav.tabs 项：仅 tab:true 的 main 模块。 */
const tabModules = computed(() => mainModules.filter((m) => m.tab));

const currentTitle = computed(() => {
  if (!appState.sessionId) return '选择或新建一个会话';
  const s = appState.sessions.find((x) => x.id === appState.sessionId);
  return s?.title || s?.id || '会话';
});

function onClear(): void {
  appState.messages = [];
}
function onCommandHandler(name: string): void {
  onCommand(name);
}
/** tab 点击：置 view（activate 触发各视图的域同步）。 */
function selectTab(m: { key: string; activate?: () => void }): void {
  m.activate ? m.activate() : (appState.view = m.key as never);
}
</script>

<template>
  <main class="main">
    <header class="header">
      <div class="breadcrumb">
        <span class="dim">DSH Java</span>
        <span class="sep">›</span>
        <b>{{ currentTitle }}</b>
      </div>
      <div class="right">
        <span class="safety">🛡️ 沙箱防护中</span>
        <span class="conn" :class="appState.connectionState">{{ connectionLabel(appState.connectionState) }}</span>
        <span v-if="appState.planMode" class="plan-chip" @click="togglePlanModeBackend()">📋 计划模式</span>
        <el-dropdown trigger="click" @command="(k: string) => setTheme(k as Parameters<typeof setTheme>[0])">
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
        <!-- 工作区 chip（对齐官方 hero workspace：切换/添加，始终可交互） -->
        <el-dropdown trigger="click" @command="onWorkspaceCommand">
          <button class="workspace-chip" :title="appState.workspaces.map(w => workspaceLabel(w)).join('\n')">
            {{ currentWorkspaceLabel }}
          </button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item
                v-for="w in appState.workspaces" :key="w.id" :command="'ws:' + w.id"
                :class="{ 'is-active': w.id === (workspaceOfCurrent()?.id ?? appState.recentWorkspaceId) }"
              >
                <span class="ws-item">
                  <span class="ws-name">📁 {{ workspaceLabel(w) }}</span>
                  <span v-if="sessionsOfWorkspace(w).length > 0" class="ws-sessions">
                    {{ sessionsOfWorkspace(w).slice(0, 3).map(s => s.title || s.id).join(' · ') }}<template v-if="sessionsOfWorkspace(w).length > 3"> …</template>
                  </span>
                </span>
                <span class="ws-path">{{ w.path }} · {{ sessionsOfWorkspace(w).length }} 会话</span>
              </el-dropdown-item>
              <el-dropdown-item divided command="add">＋ 添加工作目录…</el-dropdown-item>
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
    <!-- nav.tabs + body：遍历 main 注册表（新增 main 视图无需改本壳） -->
    <nav class="tabs">
      <button
        v-for="m in tabModules" :key="m.key"
        class="tab" :class="{ active: appState.view === m.key }"
        @click="selectTab(m)"
      >{{ m.label }}</button>
    </nav>
    <div class="body">
      <component
        v-for="m in mainModules" :key="m.key"
        :is="m.component as Component"
        v-show="appState.view === m.key"
      />
    </div>
    <!-- 对话输入 dock（常驻，不属于任何 tab；对齐官方 conversation.input.dock） -->
    <div class="composer-dock">
      <Composer @send="(t: string) => send(t)" @stop="stop" @clear="onClear" @command="onCommandHandler" />
    </div>
  </main>
</template>

<style scoped>
.main { flex: 1; display: flex; flex-direction: column; min-width: 0; background: var(--dsh-bg-1); }
.header { display: flex; justify-content: space-between; align-items: center; padding: 12px 20px; border-bottom: 1px solid var(--dsh-border); flex-shrink: 0; }
.breadcrumb { font-size: 14px; }
.dim { color: var(--dsh-fg-2); }
.sep { color: var(--dsh-fg-2); margin: 0 6px; }
.right { display: flex; align-items: center; gap: 12px; }
.safety { font-size: 12px; color: var(--dsh-success); border: 1px solid var(--dsh-border); padding: 4px 10px; border-radius: 16px; background: var(--dsh-bg-2); }
.plan-chip { font-size: 12px; color: var(--dsh-accent); border: 1px solid var(--dsh-accent); padding: 4px 10px; border-radius: 16px; background: var(--dsh-accent-soft); cursor: pointer; transition: background-color .15s; }
.plan-chip:hover { background: var(--dsh-accent); color: #fff; }
.tabs { display: flex; gap: 4px; padding: 8px 20px 0; flex-shrink: 0; }
.tab { padding: 7px 14px; border: none; background: none; color: var(--dsh-fg-2); font-size: 13px; cursor: pointer; border-radius: 8px 8px 0 0; font-family: inherit; }
.tab:hover { color: var(--dsh-fg-0); }
.tab.active { background: var(--dsh-bg-2); color: var(--dsh-fg-0); }
.body { flex: 1; display: flex; flex-direction: column; min-height: 0; overflow: hidden; }
.composer-dock { flex-shrink: 0; display: flex; flex-direction: column; gap: 8px; padding: 8px 20px 12px; border-top: 1px solid var(--dsh-border); background: var(--dsh-bg-0); }
.workspace-chip { display: flex; align-items: center; gap: 6px; background: var(--dsh-bg-2); border: 1px solid var(--dsh-border); color: var(--dsh-fg-0); border-radius: 16px; padding: 4px 12px; font-size: 12px; cursor: pointer; font-family: inherit; max-width: 220px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.workspace-chip:hover { border-color: var(--dsh-accent); color: var(--dsh-accent); }
.ws-path { display: block; font-size: 11px; color: var(--dsh-fg-2); max-width: 300px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ws-item { display: block; }
.ws-name { display: block; }
.ws-sessions { display: block; font-size: 11px; color: var(--dsh-fg-2); margin-top: 2px; max-width: 300px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.theme-btn { display: flex; align-items: center; gap: 6px; }
.accent-dot { width: 10px; height: 10px; border-radius: 50%; background: var(--dsh-accent); }
.swatch { display: inline-block; width: 14px; height: 14px; border-radius: 4px; margin-right: 8px; vertical-align: middle; border: 1px solid var(--dsh-border); }
.theme-name { vertical-align: middle; }
.check { float: right; color: var(--dsh-accent); }
:deep(.el-dropdown-menu) { background: var(--dsh-bg-2); border-color: var(--dsh-border); }
:deep(.el-dropdown-menu__item) { color: var(--dsh-fg-0); }
:deep(.el-dropdown-menu__item:hover) { background: var(--dsh-accent-soft); }
:deep(.el-dropdown-menu__item.is-active) { background: var(--dsh-accent-soft); }
.conn { font-size: 12px; padding: 4px 10px; border-radius: 16px; border: 1px solid var(--dsh-border); color: var(--dsh-fg-2); }
.conn.connected { color: #2ecc71; }
.conn.reconnecting { color: #f5a623; }
.conn.closed { color: #e5484d; }
.subagents-bar { display: flex; align-items: center; gap: 8px; padding: 6px 20px; overflow-x: auto; border-bottom: 1px solid var(--dsh-border); flex-shrink: 0; }
.subagents-title { font-size: 12px; color: var(--dsh-fg-2); white-space: nowrap; }
.subagent-chip { display: inline-flex; align-items: center; gap: 6px; background: var(--dsh-bg-2); border: 1px solid var(--dsh-border); border-radius: 14px; padding: 3px 10px; font-size: 12px; white-space: nowrap; cursor: pointer; }
.subagent-chip:hover { border-color: var(--dsh-accent); }
.subagent-chip .dot { width: 7px; height: 7px; border-radius: 50%; background: var(--dsh-fg-2); }
.subagent-chip.running .dot { background: var(--dsh-accent); }
.subagent-chip.done .dot { background: #2ecc71; }
.subagent-chip .name { color: var(--dsh-fg-0); }
.subagent-chip .meta { color: var(--dsh-fg-2); font-size: 11px; }
</style>
