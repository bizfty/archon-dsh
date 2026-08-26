<script setup lang="ts">
// FloatingChat.vue — 代码开发 / 自我完善页的浮动对话窗。
// 需求：不再“返回对话”，而是把对话作为小窗口浮动在页面上，
// 编辑文件的同时能实时看到 AI 对话与文件变化（工具调用写文件会高亮）。
// 数据共享全局 appState：WS 下行在 App.vue 常驻处理，消息实时进入 store；
// 本组件只做 HTTP 上行（sendChat），与主对话行为一致。
import { computed, nextTick, ref, watch } from 'vue';
import { appState, pushNotice } from '../store';
import { sendChat, listMessages, createSession, listSessions } from '../api';
import { renderMarkdown, escapeHtml } from '../render';

const open = ref(false);
const draft = ref('');
const listRef = ref<HTMLElement | null>(null);

/** 写文件类工具：高亮显示（文件变化）。 */
const WRITE_RE = /^(write|edit|save|create|patch|update)/i;

/**
 * 文件变化判定（高亮）：
 * 1) 工具名命中写类（WS 实时事件可用）；
 * 2) 兜底：从工具结果 content JSON 的 message 推断（历史消息 toolName 可能缺失）。
 */
const WRITE_MSG_RE = /已写入|已保存|已创建|已更新|成功写入|写入成功|保存成功|saved|written|created|updated/i;
function isWriteMessage(m: { role: string; content: string; toolName?: string | null }): boolean {
  if (m.role !== 'tool') return false;
  if (WRITE_RE.test(m.toolName || '')) return true;
  try {
    const parsed = JSON.parse(m.content || '');
    const msg = String(parsed.message || parsed.data?.message || '');
    if (WRITE_MSG_RE.test(msg)) return true;
    // data.path 存在 + 结果正常（写入类工具的结果通常带 path）
    const path = parsed.data?.path;
    return typeof path === 'string' && path.length > 0 && parsed.success === true && WRITE_MSG_RE.test(msg || '');
  } catch {
    return false;
  }
}

function toggle(): void {
  open.value = !open.value;
  if (open.value) void nextTick(scrollBottom);
}
defineExpose({ toggle, openChat: () => { open.value = true; } });

// ---- 拖拽（标题栏 pointer 拖动，限制在视口内）----
const posX = ref<number | null>(null);
const posY = ref<number | null>(null);
let dragStart: { x: number; y: number; l: number; t: number } | null = null;

const winStyle = computed(() => {
  const s: Record<string, string> = { width: '400px', height: 'min(68vh, 560px)' };
  if (posX.value !== null && posY.value !== null) {
    s.left = posX.value + 'px';
    s.top = posY.value + 'px';
    s.right = 'auto';
    s.bottom = 'auto';
  } else {
    s.right = '16px';
    s.bottom = '16px';
  }
  return s;
});

function onHeadDown(e: PointerEvent): void {
  if ((e.target as HTMLElement).closest('button')) return;
  const w = 400;
  const h = Math.min(window.innerHeight * 0.68, 560);
  dragStart = {
    x: e.clientX,
    y: e.clientY,
    l: posX.value ?? window.innerWidth - w - 16,
    t: posY.value ?? window.innerHeight - h - 16,
  };
  window.addEventListener('pointermove', onMove);
  window.addEventListener('pointerup', onUp);
}
function onMove(e: PointerEvent): void {
  if (!dragStart) return;
  posX.value = Math.max(0, Math.min(window.innerWidth - 80, dragStart.l + e.clientX - dragStart.x));
  posY.value = Math.max(0, Math.min(window.innerHeight - 48, dragStart.t + e.clientY - dragStart.y));
}
function onUp(): void {
  dragStart = null;
  window.removeEventListener('pointermove', onMove);
  window.removeEventListener('pointerup', onUp);
}

// ---- 消息流 ----
const sessionTitle = computed(() => {
  const s = appState.sessions.find((x) => x.id === appState.sessionId);
  return s?.title || '会话';
});

function scrollBottom(): void {
  if (listRef.value) listRef.value.scrollTop = listRef.value.scrollHeight;
}
watch(() => [appState.messages, appState.streamingText], scrollBottom, { deep: true });

/** 工具参数 JSON 中提取路径。 */
function toolArgPath(content: string): string {
  if (!content.trimStart().startsWith('{')) return '';
  try {
    const parsed = JSON.parse(content) as Record<string, unknown>;
    for (const key of ['path', 'file_path', 'filename', 'file']) {
      const v = parsed[key];
      if (typeof v === 'string' && v.trim()) return v.trim();
    }
    return '';
  } catch {
    return '';
  }
}

function rowClass(m: { role: string; content: string; toolName?: string | null }): string {
  if (m.role === 'tool') return 'tool' + (isWriteMessage(m) ? ' write' : '');
  return m.role;
}

function rowHtml(m: { role: string; content: string; toolName?: string | null }): string {
  if (m.role === 'tool') {
    const name = m.toolName || '工具';
    const content = m.content || '';
    const isWrite = isWriteMessage(m);
    const path = toolArgPath(content);
    const pathHtml = path ? `<span class="fc-tool-path">${escapeHtml(path)}</span>` : '';
    const body = content.trim() ? `<pre class="fc-tool-body">${escapeHtml(content.slice(0, 1200))}</pre>` : '';
    return `<div class="fc-tool${isWrite ? ' write' : ''}">
      <div class="fc-tool-line"><span class="fc-tool-name">${isWrite ? '✏️' : '⚙️'} ${escapeHtml(name)}</span>${pathHtml}</div>
      ${body}
    </div>`;
  }
  const cls = m.role === 'user' ? 'user' : 'assistant';
  return `<div class="fc-bubble ${cls}">${renderMarkdown(m.content || '…')}</div>`;
}

// ---- 发送（HTTP 上行；WS 下行实时推回事件更新 store）----
async function send(): Promise<void> {
  const text = draft.value.trim();
  if (!text || appState.running) return;
  let id = appState.sessionId;
  if (!id) {
    // 无会话时自动新建（与主对话一致），并同步侧边栏会话列表
    try {
      const s = await createSession('新会话', appState.model);
      appState.sessionId = s.id;
      id = s.id;
      const list = await listSessions();
      appState.sessions = list.map((x) => ({ id: x.id, title: x.title, model: x.model, updatedAt: x.updatedAt }));
    } catch (e) {
      pushNotice('新建会话失败: ' + (e as Error).message);
      return;
    }
  }
  appState.messages = [...appState.messages, { id: 'local', role: 'user', content: text }];
  draft.value = '';
  appState.running = true;
  appState.disabled = true;
  appState.streamingText = '';
  try {
    await sendChat(id, { message: text, model: appState.model });
  } catch (e) {
    pushNotice('请求失败: ' + (e as Error).message);
  } finally {
    appState.running = false;
    appState.disabled = false;
    appState.streamingText = '';
    listMessages(id).then((ms) => { appState.messages = ms as never[]; }).catch(() => undefined);
  }
}
</script>

<template>
  <div class="floating-chat">
    <!-- 收起态：右下角悬浮按钮 -->
    <button v-if="!open" class="fc-fab" title="打开对话（可边看代码边对话）" @click="open = true">
      <span class="fc-fab-icon">💬</span>
      <span v-if="appState.running" class="fc-fab-dot" />
    </button>

    <!-- 展开态：浮动对话窗 -->
    <div v-else class="fc-window" :style="winStyle">
      <div class="fc-head" @pointerdown="onHeadDown">
        <span class="fc-title">💬 对话</span>
        <span class="fc-sub" :title="sessionTitle">{{ sessionTitle }}</span>
        <button class="fc-min" title="收起对话" @click="open = false">—</button>
      </div>
      <div ref="listRef" class="fc-list">
        <div v-for="m in appState.messages" :key="m.id" class="fc-row" :class="rowClass(m)" v-html="rowHtml(m)"></div>
        <div v-if="appState.running" class="fc-row assistant">
          <div class="fc-bubble assistant">{{ appState.streamingText }}<span v-if="!appState.streamingText" class="fc-cursor">▋</span></div>
        </div>
        <div v-if="appState.messages.length === 0 && !appState.running" class="fc-empty">
          💬 在这里对话，AI 修改文件时会实时显示工具记录（写文件高亮 ✏️）
        </div>
      </div>
      <div class="fc-input">
        <el-input
          v-model="draft"
          type="textarea"
          :rows="2"
          resize="none"
          placeholder="继续对话…（Enter 发送 / Shift+Enter 换行）"
          :disabled="appState.running"
          @keydown.enter.exact.prevent="send"
        />
        <el-button
          type="primary"
          circle
          :loading="appState.running"
          :disabled="!draft.trim()"
          title="发送"
          @click="send"
        >➤</el-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.floating-chat { position: fixed; z-index: 1200; }

/* 收起态 FAB */
.fc-fab {
  position: fixed;
  right: 18px;
  bottom: 18px;
  width: 52px;
  height: 52px;
  border-radius: 50%;
  border: 1px solid var(--dsh-border);
  background: var(--dsh-bg-2);
  color: var(--dsh-fg-0);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 6px 20px rgba(0, 0, 0, .25);
  transition: transform .15s, border-color .15s;
}
.fc-fab:hover { transform: scale(1.06); border-color: var(--dsh-accent); }
.fc-fab-icon { font-size: 22px; }
.fc-fab-dot {
  position: absolute;
  top: 8px;
  right: 8px;
  width: 9px;
  height: 9px;
  border-radius: 50%;
  background: var(--dsh-accent);
  animation: fc-pulse 1s infinite;
}
@keyframes fc-pulse { 50% { opacity: .3; } }

/* 展开窗口 */
.fc-window {
  position: fixed;
  display: flex;
  flex-direction: column;
  background: var(--dsh-bg-1);
  border: 1px solid var(--dsh-border);
  border-radius: 12px;
  box-shadow: 0 12px 40px rgba(0, 0, 0, .3);
  overflow: hidden;
}
.fc-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  background: var(--dsh-bg-0);
  border-bottom: 1px solid var(--dsh-border);
  cursor: grab;
  user-select: none;
  flex-shrink: 0;
}
.fc-head:active { cursor: grabbing; }
.fc-title { font-size: 13px; font-weight: 600; color: var(--dsh-fg-0); white-space: nowrap; }
.fc-sub {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 11px;
  color: var(--dsh-fg-2);
}
.fc-min {
  border: none;
  background: none;
  color: var(--dsh-fg-2);
  cursor: pointer;
  font-size: 14px;
  padding: 2px 8px;
  border-radius: 6px;
}
.fc-min:hover { background: var(--dsh-bg-3); color: var(--dsh-fg-0); }

/* 消息列表 */
.fc-list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 10px 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.fc-row { display: flex; flex-direction: column; }
.fc-row.user { align-items: flex-end; }
.fc-bubble {
  max-width: 92%;
  padding: 8px 12px;
  border-radius: 10px;
  line-height: 1.55;
  font-size: 12.5px;
  overflow-wrap: break-word;
  background: var(--dsh-bg-2);
  border: 1px solid var(--dsh-border);
  color: var(--dsh-fg-0);
}
.fc-bubble.user { background: var(--dsh-accent); border-color: var(--dsh-accent); color: var(--dsh-accent-contrast); align-self: flex-end; }
.fc-bubble :deep(p) { margin: 4px 0; }
.fc-bubble :deep(pre) { background: var(--dsh-code-bg); border-radius: 6px; padding: 6px 8px; overflow-x: auto; font-size: 11.5px; margin: 4px 0; }
.fc-bubble :deep(:not(pre) > code) { background: var(--dsh-code-bg); padding: 1px 4px; border-radius: 4px; font-size: 11.5px; }
.fc-cursor { animation: fc-blink 1s infinite; }
@keyframes fc-blink { 50% { opacity: 0; } }

/* 工具行（写文件高亮） */
.fc-tool {
  background: var(--dsh-bg-2);
  border: 1px solid var(--dsh-border);
  border-left: 3px solid var(--dsh-fg-2);
  border-radius: 8px;
  padding: 5px 10px;
  font-size: 12px;
}
.fc-tool.write { border-left-color: #2ecc71; background: rgba(46, 204, 113, .06); }
.fc-tool-line { display: flex; align-items: center; gap: 8px; color: var(--dsh-fg-0); }
.fc-tool-name { font-weight: 600; color: var(--dsh-accent); flex-shrink: 0; }
.fc-tool.write .fc-tool-name { color: #2ecc71; }
.fc-tool-path {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--dsh-fg-2);
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 11.5px;
}
.fc-tool-body {
  margin: 6px 0 0;
  padding: 6px 8px;
  background: var(--dsh-code-bg);
  border-radius: 6px;
  font-size: 11px;
  max-height: 120px;
  overflow-y: auto;
  color: var(--dsh-fg-2);
  white-space: pre-wrap;
  word-break: break-word;
}
.fc-empty {
  margin: auto;
  text-align: center;
  color: var(--dsh-fg-2);
  font-size: 12px;
  line-height: 1.7;
  padding: 12px;
}

/* 输入区 */
.fc-input {
  display: flex;
  gap: 8px;
  align-items: flex-end;
  padding: 8px 10px;
  border-top: 1px solid var(--dsh-border);
  background: var(--dsh-bg-0);
  flex-shrink: 0;
}
.fc-input :deep(.el-textarea__inner) {
  font-size: 12.5px;
  line-height: 1.5;
  background: var(--dsh-bg-2);
}
</style>
