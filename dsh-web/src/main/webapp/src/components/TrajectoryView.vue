<script setup lang="ts">
import { computed, ref, watch, nextTick } from 'vue';
import mermaid from 'mermaid';
import { appState } from '../store';
import { getTrajectory, type TrajectoryStep } from '../api';
import { renderMarkdown, escapeHtml } from '../render';
import { generateMermaidDAG } from '../dag';

const viewMode = ref<'timeline' | 'dag'>('timeline');
const dagSvg = ref('');
const dagError = ref('');
const dagRendering = ref(false);

let mermaidInitialized = false;

function themeIsDark(): boolean {
  return appState.themeMode === 'dark';
}

function ensureMermaid(): void {
  const dark = themeIsDark();
  const accent = getComputedStyle(document.documentElement).getPropertyValue('--dsh-accent').trim() || '#4f7cff';
  const bg0 = getComputedStyle(document.documentElement).getPropertyValue('--dsh-bg-1').trim() || '#1e1f24';
  const bg2 = getComputedStyle(document.documentElement).getPropertyValue('--dsh-bg-2').trim() || '#26272e';
  const fg0 = getComputedStyle(document.documentElement).getPropertyValue('--dsh-fg-0').trim() || '#e6e6eb';
  const fg2 = getComputedStyle(document.documentElement).getPropertyValue('--dsh-fg-2').trim() || '#9a9ba6';
  const border = getComputedStyle(document.documentElement).getPropertyValue('--dsh-border').trim() || '#33343d';

  mermaid.initialize({
    startOnLoad: false,
    theme: dark ? 'dark' : 'default',
    darkMode: dark,
    themeVariables: {
      background: bg0,
      primaryColor: bg2,
      primaryTextColor: fg0,
      primaryBorderColor: border,
      lineColor: accent,
      fontFamily: '-apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", sans-serif',
      fontSize: '12px',
    },
    flowchart: {
      htmlLabels: true,
      curve: 'basis',
      useMaxWidth: true,
      padding: 10,
    },
  });
  mermaidInitialized = true;
}

async function renderDag(): Promise<void> {
  const traj = appState.trajectory;
  if (!traj) return;
  mermaidInitialized = false;
  ensureMermaid();
  dagRendering.value = true;
  dagError.value = '';
  try {
    const definition = generateMermaidDAG(traj);
    const id = 'dag-svg-' + Date.now();
    const { svg } = await mermaid.render(id, definition);
    dagSvg.value = svg;
  } catch (e) {
    dagError.value = 'DAG 渲染失败: ' + (e as Error).message;
  } finally {
    dagRendering.value = false;
  }
}

watch([viewMode, () => appState.trajectory, () => appState.theme], ([mode, traj]) => {
  if (mode === 'dag' && traj && traj.turns.length > 0) {
    void nextTick(() => renderDag());
  }
}, { immediate: true });

async function loadTrajectory(): Promise<void> {
  if (!appState.sessionId) return;
  appState.trajectoryLoading = true;
  try {
    appState.trajectory = await getTrajectory(appState.sessionId);
    if (viewMode.value === 'dag' && appState.trajectory?.turns.length) {
      await nextTick();
      await renderDag();
    }
  } catch (e) {
    appState.notice = '加载轨迹失败: ' + (e as Error).message;
  } finally {
    appState.trajectoryLoading = false;
  }
}

function fmtTime(iso: string | null): string {
  if (!iso) return '';
  return new Date(iso).toLocaleString('zh-CN', {
    month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit',
  });
}

function fmtDuration(from: string | null, to: string | null): string {
  if (!from || !to) return '';
  const ms = new Date(to).getTime() - new Date(from).getTime();
  if (ms < 0) return '';
  if (ms < 1000) return `${ms}ms`;
  if (ms < 60000) return `${(ms / 1000).toFixed(1)}s`;
  return `${Math.floor(ms / 60000)}m${Math.floor((ms % 60000) / 1000)}s`;
}

function stepIcon(step: TrajectoryStep): string {
  switch (step.type) {
    case 'user': return '👤';
    case 'assistant': return '🤖';
    case 'tool': return '🔧';
    case 'system': return '⚙️';
    default: return '📄';
  }
}

function stepColor(step: TrajectoryStep): string {
  switch (step.type) {
    case 'user': return 'var(--dsh-accent)';
    case 'assistant': return 'var(--dsh-success)';
    case 'tool': return 'var(--dsh-warning)';
    case 'system': return '#9b59b6';
    default: return 'var(--dsh-fg-2)';
  }
}

function stepBadge(step: TrajectoryStep): string {
  switch (step.type) {
    case 'user': return 'USER';
    case 'assistant': return 'ASSIST';
    case 'tool': return step.toolName?.toUpperCase() || 'TOOL';
    case 'system': return 'SYS';
    default: return String(step.type).toUpperCase();
  }
}

function renderStepContent(step: TrajectoryStep): string {
  if (step.type === 'tool') {
    const title = '🔧 ' + (step.toolName || 'tool') + (step.toolCallId ? ' · ' + step.toolCallId.slice(0, 12) : '');
    return '<details class="tool-details"><summary>' + title + '</summary><div class="tool-body"><pre class="tool-pre">' + escapeHtml(step.content || '') + '</pre></div></details>';
  }
  if (step.type === 'user') {
    return '<div class="traj-content user-content">' + escapeHtml(step.content || '') + '</div>';
  }
  if (step.type === 'assistant') {
    let html = '<div class="traj-content">' + renderMarkdown(step.content || '') + '</div>';
    if (step.toolCalls && step.toolCalls.length > 0) {
      html += '<div class="tool-calls-block">';
      for (const tc of step.toolCalls) {
        const argsPreview = tc.arguments.length > 300
          ? tc.arguments.slice(0, 300) + '...'
          : tc.arguments;
        html += '<div class="tool-call-item"><span class="tc-name">🔧 ' + escapeHtml(tc.name) + '</span><pre class="tc-args">' + escapeHtml(argsPreview) + '</pre></div>';
      }
      html += '</div>';
    }
    return html;
  }
  return '<div class="traj-content">' + escapeHtml(step.content || '') + '</div>';
}

const traj = computed(() => appState.trajectory);
const isLoading = computed(() => appState.trajectoryLoading);
</script>

<template>
  <div class="trajectory">
    <div v-if="!appState.sessionId" class="empty-state">
      <div class="empty-icon">🛤</div>
      <h3>暂无会话</h3>
      <p>请先选择或创建一个会话</p>
    </div>

    <template v-else>
      <div class="traj-header">
        <div class="header-title">
          <h2>🛤 消息轨迹</h2>
          <span class="subtitle">Agent 执行步骤追踪</span>
        </div>
        <div class="header-actions">
          <div class="view-toggle">
            <button
              class="toggle-btn"
              :class="{ active: viewMode === 'timeline' }"
              @click="viewMode = 'timeline'"
            >📋 时间线</button>
            <button
              class="toggle-btn"
              :class="{ active: viewMode === 'dag' }"
              @click="viewMode = 'dag'"
            >🕸 DAG 图</button>
          </div>
          <el-button :loading="isLoading" @click="loadTrajectory" :disabled="!appState.sessionId">
            🔄 刷新
          </el-button>
        </div>
      </div>

      <div v-if="traj" class="traj-stats">
        <div class="stat-card">
          <div class="stat-value">{{ traj.totalTurns }}</div>
          <div class="stat-label">轮次 (Turns)</div>
        </div>
        <div class="stat-card">
          <div class="stat-value">{{ traj.totalMessages }}</div>
          <div class="stat-label">消息数</div>
        </div>
        <div class="stat-card">
          <div class="stat-value">{{ traj.totalToolCalls }}</div>
          <div class="stat-label">工具调用</div>
        </div>
        <div class="stat-card">
          <div class="stat-value">{{ traj.estimatedTokens.toLocaleString() }}</div>
          <div class="stat-label">预估 Tokens</div>
        </div>
        <div class="stat-card">
          <div class="stat-value">{{ fmtDuration(traj.startedAt, traj.endedAt) || '--' }}</div>
          <div class="stat-label">总耗时</div>
        </div>
      </div>

      <div v-if="viewMode === 'dag'">
        <div v-if="dagError" class="dag-error">
          <span>❌ {{ dagError }}</span>
        </div>
        <div v-else-if="traj && traj.turns.length === 0" class="empty-state">
          <div class="empty-icon">📭</div>
          <h3>无轨迹数据</h3>
          <p>发送消息后即可查看 DAG</p>
        </div>
        <div v-else class="dag-container">
          <div v-if="dagRendering" class="dag-loading">
            <div class="spinner"></div>
            <span>渲染 DAG 中...</span>
          </div>
          <div v-show="!dagRendering" class="dag-svg" v-html="dagSvg"></div>
          <div class="dag-legend">
            <span class="legend-item"><span class="dot user"></span>USER</span>
            <span class="legend-item"><span class="dot assistant"></span>ASSISTANT</span>
            <span class="legend-item"><span class="dot tool"></span>TOOL</span>
            <span class="legend-item"><span class="dot system"></span>SYSTEM</span>
          </div>
        </div>
      </div>

      <template v-else>
        <div v-if="traj && traj.turns.length === 0" class="empty-state">
          <div class="empty-icon">📭</div>
          <h3>无轨迹数据</h3>
          <p>发送消息后即可查看轨迹</p>
        </div>

        <div v-for="turn in traj?.turns" :key="'turn-' + turn.turn" class="turn-block">
          <div class="turn-header">
            <span class="turn-badge">Turn #{{ turn.turn }}</span>
            <span v-if="turn.hasToolCalls" class="tool-badge">含工具调用</span>
            <span class="turn-step-count">{{ turn.steps.length }} 步骤</span>
          </div>
          <div class="turn-steps">
            <div v-for="(step, idx) in turn.steps" :key="'step-' + step.step" class="step-row">
              <div class="step-timeline">
                <div class="step-dot" :style="{ backgroundColor: stepColor(step) }">
                  <span class="dot-icon">{{ stepIcon(step) }}</span>
                </div>
                <div v-if="idx < turn.steps.length - 1" class="step-line"></div>
              </div>
              <div class="step-content">
                <div class="step-meta">
                  <span class="step-badge" :style="{ backgroundColor: stepColor(step) }">{{ stepBadge(step) }}</span>
                  <span class="step-num">Step {{ step.step }}</span>
                  <span v-if="step.createdAt" class="step-time">{{ fmtTime(step.createdAt) }}</span>
                </div>
                <div class="step-body" v-html="renderStepContent(step)"></div>
              </div>
            </div>
          </div>
        </div>

        <div v-if="traj && traj.steps.length > 0" class="traj-footer">
          <span v-if="traj.startedAt">开始: {{ fmtTime(traj.startedAt) }}</span>
          <span v-if="traj.endedAt" class="sep">|</span>
          <span v-if="traj.endedAt">结束: {{ fmtTime(traj.endedAt) }}</span>
        </div>
      </template>
    </template>
  </div>
</template>

<style scoped>
.trajectory { padding: 20px 24px; height: 100%; overflow-y: auto; }
.traj-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.header-title h2 { font-size: 18px; margin: 0 0 2px; color: var(--dsh-fg-0); }
.subtitle { font-size: 12px; color: var(--dsh-fg-2); }
.header-actions { display: flex; align-items: center; gap: 12px; }

.view-toggle {
  display: flex; background: var(--dsh-bg-1); border: 1px solid var(--dsh-border);
  border-radius: 8px; padding: 3px;
}
.toggle-btn {
  padding: 5px 12px; border: none; background: none; color: var(--dsh-fg-2);
  font-size: 12px; cursor: pointer; border-radius: 6px; font-family: inherit;
  transition: all .15s;
}
.toggle-btn:hover { color: var(--dsh-fg-0); }
.toggle-btn.active { background: var(--dsh-accent); color: var(--dsh-accent-contrast); }

.traj-stats { display: flex; gap: 12px; margin-bottom: 20px; flex-wrap: wrap; }
.stat-card {
  flex: 1; min-width: 100px;
  background: var(--dsh-bg-2); border: 1px solid var(--dsh-border);
  border-radius: 10px; padding: 12px 14px;
}
.stat-value { font-size: 22px; font-weight: 700; color: var(--dsh-accent); }
.stat-label { font-size: 11px; color: var(--dsh-fg-2); margin-top: 2px; }

.dag-container {
  background: var(--dsh-bg-1); border: 1px solid var(--dsh-border);
  border-radius: 12px; padding: 20px; position: relative;
  min-height: 400px;
}
.dag-loading {
  position: absolute; inset: 0; display: flex;
  align-items: center; justify-content: center;
  flex-direction: column; gap: 12px; color: var(--dsh-fg-2); font-size: 13px;
}
.spinner {
  width: 32px; height: 32px; border: 3px solid var(--dsh-border);
  border-top-color: var(--dsh-accent); border-radius: 50%;
  animation: spin 0.8s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }

.dag-svg {
  overflow-x: auto; overflow-y: auto;
  max-height: 70vh; padding: 10px;
}
.dag-svg :deep(svg) { max-width: 100%; height: auto; }
.dag-svg :deep(.node rect),
.dag-svg :deep(.node polygon),
.dag-svg :deep(.node circle) {
  rx: 6px; ry: 6px;
}
.dag-svg :deep(.edgeLabel) {
  background: var(--dsh-bg-1) !important;
  border: 1px solid var(--dsh-border);
  border-radius: 4px;
  padding: 1px 4px;
}

.dag-legend {
  display: flex; justify-content: center; gap: 16px;
  margin-top: 14px; padding-top: 12px;
  border-top: 1px solid var(--dsh-border);
  font-size: 12px; color: var(--dsh-fg-2);
}
.legend-item { display: flex; align-items: center; gap: 6px; }
.dot { width: 10px; height: 10px; border-radius: 50%; display: inline-block; }
.dot.user { background: var(--dsh-accent); }
.dot.assistant { background: var(--dsh-success); }
.dot.tool { background: var(--dsh-warning); }
.dot.system { background: #9b59b6; }

.dag-error {
  background: #3d1f1f; border: 1px solid #8b3a3a;
  border-radius: 8px; padding: 12px 16px;
  color: #e8a0a0; font-size: 13px; margin-bottom: 12px;
}

.turn-block { margin-bottom: 18px; }
.turn-header {
  display: flex; align-items: center; gap: 10px;
  padding: 8px 14px; background: var(--dsh-bg-2);
  border: 1px solid var(--dsh-border); border-bottom: none;
  border-radius: 10px 10px 0 0;
}
.turn-badge {
  background: var(--dsh-accent); color: var(--dsh-accent-contrast); font-size: 12px;
  padding: 2px 8px; border-radius: 12px; font-weight: 600;
}
.tool-badge {
  background: var(--dsh-warning); color: #fff; font-size: 11px;
  padding: 2px 6px; border-radius: 10px;
}
.turn-step-count { font-size: 12px; color: var(--dsh-fg-2); margin-left: auto; }

.turn-steps {
  background: var(--dsh-bg-1); border: 1px solid var(--dsh-border);
  border-radius: 0 0 10px 10px; padding: 12px 14px;
}

.step-row { display: flex; gap: 12px; padding: 8px 0; }
.step-timeline { display: flex; flex-direction: column; align-items: center; width: 32px; flex-shrink: 0; }
.step-dot {
  width: 26px; height: 26px; border-radius: 50%;
  display: grid; place-items: center;
  font-size: 13px; flex-shrink: 0;
}
.dot-icon { font-size: 14px; }
.step-line {
  width: 2px; flex: 1; min-height: 12px;
  background: var(--dsh-border); margin-top: 4px;
}

.step-content { flex: 1; min-width: 0; }
.step-meta { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
.step-badge {
  color: #fff; font-size: 10px; font-weight: 600;
  padding: 2px 6px; border-radius: 4px; letter-spacing: .03em;
}
.step-num { font-size: 11px; color: var(--dsh-fg-2); font-family: monospace; }
.step-time { font-size: 11px; color: var(--dsh-fg-2); margin-left: auto; font-family: monospace; }

.step-body {
  background: var(--dsh-bg-2); border: 1px solid var(--dsh-border);
  border-radius: 8px; padding: 10px 12px;
}
.traj-content { font-size: 13px; line-height: 1.6; color: var(--dsh-fg-0); }
.user-content { color: var(--dsh-fg-0); }

.tool-calls-block { margin-top: 8px; border-top: 1px dashed var(--dsh-border); padding-top: 8px; }
.tool-call-item { margin-bottom: 6px; }
.tc-name { font-size: 12px; color: var(--dsh-warning); font-weight: 600; }
.tc-args {
  background: var(--dsh-code-bg); border-radius: 6px;
  padding: 6px 8px; margin: 4px 0 0;
  font-size: 11px; white-space: pre-wrap;
  overflow-wrap: break-word; color: var(--dsh-fg-2);
  max-height: 120px; overflow-y: auto;
}

.traj-footer {
  display: flex; justify-content: center;
  margin-top: 20px; padding: 12px;
  font-size: 12px; color: var(--dsh-fg-2);
}
.sep { margin: 0 8px; }

.empty-state { text-align: center; padding: 80px 20px; }
.empty-icon { font-size: 48px; margin-bottom: 12px; }
.empty-state h3 { margin: 8px 0 4px; color: var(--dsh-fg-0); }
.empty-state p { color: var(--dsh-fg-2); font-size: 13px; }

:deep(.tool-details) { border: 1px solid var(--dsh-border); border-radius: 8px; margin: 4px 0; background: var(--dsh-code-bg); }
:deep(.tool-details summary) { cursor: pointer; padding: 8px 12px; font-size: 12.5px; user-select: none; list-style: none; color: var(--dsh-fg-0); }
:deep(.tool-details summary::-webkit-details-marker) { display: none; }
:deep(.tool-details summary::before) { content: '▸'; margin-right: 6px; }
:deep(.tool-details[open] summary::before) { display: inline-block; transform: rotate(90deg); }
:deep(.tool-pre) { padding: 0 12px 10px; font-size: 12px; white-space: pre-wrap; overflow-wrap: break-word; color: var(--dsh-fg-0); margin: 0; }
</style>