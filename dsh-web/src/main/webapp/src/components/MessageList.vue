<script setup lang="ts">
// 消息列表：欢迎页 / 消息流（markdown + 高亮 + 净化）/ 工具折叠 / 流式光标 / 问答选择框。
import { computed, nextTick, ref, watch } from 'vue';
import { appState, type MessageView } from '../store';
import { renderMarkdown, escapeHtml, collapsible } from '../render';
import { answerQuestion, pendingQuestions } from '../api';

const container = ref<HTMLElement | null>(null);

const messages = computed(() => appState.messages);
const streaming = computed(() => appState.streamingText);

function scrollToBottom(): void {
  void nextTick(() => {
    if (container.value) container.value.scrollTop = container.value.scrollHeight;
  });
}

watch([messages, streaming], scrollToBottom, { deep: true });

function rowHtml(m: MessageView): string {
  if (m.role === 'tool') {
    const title = `🔧 ${m.toolName || '工具'}${m.toolCallId ? ' · ' + m.toolCallId.slice(0, 12) : ''}`;
    return collapsible(title, `<pre class="tool-pre">${escapeHtml(m.content.slice(0, 4000))}</pre>`);
  }
  const cls = m.role === 'user' ? 'user' : 'assistant';
  return `<div class="row ${cls}"><div class="bubble">${renderMarkdown(m.content || '…')}</div></div>`;
}

// ---- 问答选择框（ask_user_question）----
const selected = ref<Set<string>>(new Set());
const freeText = ref('');
const submitting = ref(false);

function toggleOption(opt: string): void {
  const q = appState.question;
  if (!q) return;
  if (q.multiSelect) {
    const next = new Set(selected.value);
    if (next.has(opt)) next.delete(opt); else next.add(opt);
    selected.value = next;
  } else {
    selected.value = new Set([opt]);
  }
}

async function submitAnswer(): Promise<void> {
  let q = appState.question;
  const answer = [...selected.value].join(', ') || freeText.value.trim();
  if (!q || !answer || submitting.value) return;
  submitting.value = true;
  try {
    // SSE 事件不带 id：若尚未从 pending 补齐，提交前拉取
    if (!q.id) {
      const list = await pendingQuestions();
      if (list.length > 0) q = { ...q, id: list[0].id };
    }
    if (!q.id) throw new Error('未找到挂起问题（可能已超时）');
    await answerQuestion(q.id, answer);
    appState.question = null;
    selected.value = new Set();
    freeText.value = '';
  } catch (e) {
    appState.notice = '提交答案失败: ' + (e as Error).message;
  } finally {
    submitting.value = false;
  }
}
</script>

<template>
  <div ref="container" class="msg-list">
    <div v-if="!appState.sessionId" class="welcome">
      <div class="logo">🧭</div>
      <h2>Archon</h2>
      <p>统筹诸事，伴你运筹 · 基于 Spring AI + DSH 能力面</p>
      <div class="tips">
        <div>💬 对话：SSE 流式 + 工具调用折叠</div>
        <div>🎯 目标：持久化 same-session 目标（CAS）</div>
        <div>🛠 工具：bash / fs / subagent / workflow / 浏览器 / GitHub…</div>
      </div>
    </div>
    <template v-else>
      <!-- 历史消息：markdown 渲染（v-html 已经 DOMPurify 净化） -->
      <div v-for="m in messages" :key="m.id" v-html="rowHtml(m)"></div>
      <!-- 流式：纯文本 + 光标 -->
      <div v-if="appState.running" class="row assistant">
        <div class="bubble">{{ streaming }}<span v-if="!streaming" class="cursor">▋</span></div>
      </div>
      <!-- 问答选择框（ask_user_question）：模型阻塞等待用户选择 -->
      <div v-if="appState.question" class="question-card">
        <div class="question-text">❓ {{ appState.question.question }}</div>
        <div v-if="appState.question.options.length > 0" class="question-options">
          <button
            v-for="opt in appState.question.options"
            :key="opt"
            class="option"
            :class="{ picked: selected.has(opt) }"
            @click="toggleOption(opt)"
          >{{ opt }}</button>
        </div>
        <el-input
          v-else
          v-model="freeText"
          placeholder="输入你的回答..."
          class="free-input"
        />
        <div class="question-actions">
          <el-button type="primary" :disabled="selected.size === 0 && !freeText.trim()" :loading="submitting" @click="submitAnswer">
            提交选择
          </el-button>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.msg-list { flex: 1; overflow-y: auto; padding: 20px; }
.welcome { text-align: center; padding: 80px 20px; }
.logo { font-size: 64px; }
h2 { font-size: 28px; margin: 12px 0 8px; }
p { color: #9a9ba6; margin-bottom: 30px; }
.tips { display: flex; flex-direction: column; gap: 10px; align-items: center; }
.tips div { background: #26272e; border: 1px solid #33343d; border-radius: 8px; padding: 8px 18px; color: #9a9ba6; font-size: 13px; }
:deep(.row) { display: flex; }
:deep(.row.user) { justify-content: flex-end; }
:deep(.bubble) { max-width: 78%; padding: 10px 14px; border-radius: 12px; line-height: 1.6; background: #26272e; border: 1px solid #33343d; overflow-wrap: break-word; margin-bottom: 14px; }
:deep(.row.user .bubble) { background: #4f7cff; border-color: #4f7cff; color: #fff; }
:deep(.bubble p) { margin: 6px 0; }
:deep(.bubble pre) { background: #14151a; border-radius: 8px; padding: 10px; overflow-x: auto; font-size: 12.5px; margin: 8px 0; }
:deep(.bubble :not(pre) > code) { background: #14151a; padding: 1px 5px; border-radius: 4px; }
:deep(.tool-details) { border: 1px solid #33343d; border-radius: 8px; margin: 4px 0 14px; background: #14151a; overflow: hidden; color: #9a9ba6; }
:deep(.tool-details summary) { cursor: pointer; padding: 8px 12px; font-size: 12.5px; user-select: none; list-style: none; }
:deep(.tool-details summary::-webkit-details-marker) { display: none; }
:deep(.tool-details summary::before) { content: '▸'; margin-right: 6px; }
:deep(.tool-details[open] summary::before) { display: inline-block; transform: rotate(90deg); }
:deep(.tool-pre) { padding: 0 12px 10px; font-size: 12px; white-space: pre-wrap; overflow-wrap: break-word; color: #e6e6eb; }
.cursor { animation: blink 1s infinite; }
@keyframes blink { 50% { opacity: 0; } }

/* 问答选择框 */
.question-card {
  margin: 10px 0;
  padding: 16px;
  border: 1px solid #4f7cff;
  border-radius: 12px;
  background: #26272e;
}
.question-text { font-size: 14px; margin-bottom: 12px; color: #e6e6eb; }
.question-options { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 12px; }
.option {
  padding: 8px 14px;
  border: 1px solid #33343d;
  border-radius: 20px;
  background: #1e1f24;
  color: #e6e6eb;
  cursor: pointer;
  font-size: 13px;
  font-family: inherit;
}
.option:hover { border-color: #4f7cff; }
.option.picked { border-color: #4f7cff; background: #4f7cff; color: #fff; }
.free-input { margin-bottom: 12px; }
.question-actions { display: flex; justify-content: flex-end; }
</style>
