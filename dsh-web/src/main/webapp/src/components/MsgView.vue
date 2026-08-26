<script setup lang="ts">
// 消息列表：欢迎页 / 消息流（markdown + 高亮 + 净化）/ 工具折叠 / 流式光标 / 问答选择框。
import { computed, nextTick, ref, watch } from 'vue';
import { appState, type MessageView } from '../store';
import { renderMarkdown, escapeHtml } from '../render';
import { answerQuestion, pendingQuestions, readFile } from '../api';

/** 文件查看弹窗（点击工具行路径触发）。 */
const fileDialog = ref<{ path: string; content: string; loading: boolean; error: string } | null>(null);

async function openFile(path: string): Promise<void> {
  fileDialog.value = { path, content: '', loading: true, error: '' };
  try {
    const res = await readFile(path);
    if (res.error) {
      fileDialog.value = { path, content: '', loading: false, error: res.error };
    } else {
      fileDialog.value = { path, content: res.content, loading: false, error: '' };
    }
  } catch (e) {
    fileDialog.value = { path, content: '', loading: false, error: (e as Error).message };
  }
}

/** 消息流点击委托：命中 data-open-path 的路径链接 → 打开文件。 */
function onRowClick(e: MouseEvent): void {
  const el = (e.target as HTMLElement).closest?.('[data-open-path]') as HTMLElement | null;
  const path = el?.getAttribute('data-open-path');
  if (path) {
    e.preventDefault();
    void openFile(path);
  }
}

// 离开对话 tab 时关闭文件预览弹窗（避免 teleport 弹层叠加在其他视图上 = 共存显示）
watch(() => appState.view, (v) => {
  if (v !== 'chat') fileDialog.value = null;
});

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
    // workflow 特殊渲染：编排流程卡片（区别于普通工具折叠行）
    if (m.toolName === 'workflow') {
      return workflowCardHtml(m);
    }
    // bash 特殊渲染：标题 + 摘要 + 展开完整输出（对齐官方 BashRow）
    if (m.toolName === 'bash') {
      return bashRowHtml(m);
    }
    // 通用工具：图标 + 工具名 + 摘要（参数文件名/路径 或 结果首行），展开看完整输出
    return genericToolRowHtml(m);
  }
  const cls = m.role === 'user' ? 'user' : 'assistant';
  return `<div class="row ${cls}"><div class="bubble">${renderMarkdown(m.content || '…')}</div></div>`;
}

/** 参数中的文件名/路径提取（read/write/edit/glob/grep 等工具摘要用）。 */
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

/** 各工具的摘要字段（对齐官方 SUMMARY_KEYS：从参数里挑最可读的作摘要）。 */
const SUMMARY_KEYS: Record<string, string[]> = {
  read_file: ['path', 'file_path', 'url'],
  write_file: ['path', 'file_path'],
  edit: ['path', 'file_path'],
  glob: ['pattern', 'path'],
  grep: ['pattern', 'path'],
  web_fetch: ['url'],
  web_search: ['query'],
  subagent: ['prompt'],
  send_message: ['message'],
  skill: ['name'],
  list_agents: [],
};

/** 从参数 JSON 里按工具取摘要字段（取第一个非空字符串，取首行）。 */
function toolArgSummary(toolName: string, content: string): string {
  if (!content.trimStart().startsWith('{')) return '';
  try {
    const parsed = JSON.parse(content) as Record<string, unknown>;
    const keys = SUMMARY_KEYS[toolName] ?? [];
    for (const key of keys) {
      const v = parsed[key];
      if (typeof v === 'string' && v.trim()) return v.trim().split('\n')[0].slice(0, 80);
    }
    // 兜底：任意字符串参数（排除已知的长文本字段）
    for (const [k, v] of Object.entries(parsed)) {
      if (typeof v === 'string' && v.trim() && !['content', 'code', 'script', 'prompt'].includes(k)) {
        return v.trim().split('\n')[0].slice(0, 80);
      }
    }
    return '';
  } catch {
    return '';
  }
}

/** 工具 → 官方变体标题（对齐 tool-call-model 的 VARIANT_TITLES/TOOL_TITLES）。 */
const TOOL_TITLES: Record<string, string> = {
  read_file: 'Read',
  write_file: 'Write',
  edit: 'Edit',
  bash: 'Bash',
  glob: 'Search',
  grep: 'Search',
  web_fetch: 'Fetch',
  run_code: 'Code',
};

function toolTitle(toolName: string): string {
  return TOOL_TITLES[toolName] ?? 'Tool call';
}

/** 通用工具行：`Read <路径>` / `Search <关键词>` 标题 + 摘要，展开完整输出。
 *  摘要来源：TOOL_CALL → 参数摘要字段（SUMMARY_KEYS）；TOOL_RESULT → 结果首行。 */
function genericToolRowHtml(m: MessageView): string {
  const content = m.content || '';
  const path = toolArgPath(content);
  const title = toolTitle(m.toolName || '');
  // 路径可点击（data-open-path 由事件委托打开文件）
  const pathHtml = path
    ? `<a class="tool-path" data-open-path="${escapeHtml(path)}" title="点击查看文件">${escapeHtml(path)}</a>`
    : '';
  // 摘要：TOOL_CALL → 参数摘要；TOOL_RESULT → 结果首行
  let summary = '';
  let body = content;
  if (content.trimStart().startsWith('{')) {
    // TOOL_CALL：摘要用参数字段；body 展示 pretty 参数
    summary = toolArgSummary(m.toolName || '', content);
    try {
      body = JSON.stringify(JSON.parse(content), null, 2);
    } catch {
      // 保持原样
    }
  } else {
    const first = content.trim().split('\n')[0].slice(0, 140);
    if (first) summary = escapeHtml(first);
  }
  const summaryHtml = summary
    ? `<span class="tool-summary" title="${summary}">${summary}</span>`
    : '';
  return `<div class="generic-tool-row">
    <div class="tool-line"><span class="tool-action">${escapeHtml(title)}</span>${pathHtml}${summaryHtml}</div>
    ${body ? `<div class="tool-body"><pre class="tool-pre">${escapeHtml(body.slice(0, 6000))}</pre></div>` : ''}
  </div>`;
}

/**
 * Bash 行：图标 + `bash · {command}` 标题 + 摘要（输出首行 / exit code），
 * 展开显示完整输出。对齐官方 BashRow 的 title + description 双层结构。
 */
function bashRowHtml(m: MessageView): string {
  const content = m.content || '';
  let command = m.toolCallId ? '' : '';
  let output = content;
  // TOOL_CALL：content 是 {"command":"...","workdir":...} JSON → 取 command 作标题
  if (content.trimStart().startsWith('{')) {
    try {
      const parsed = JSON.parse(content) as { command?: string };
      command = parsed.command ?? '';
      output = '';
    } catch {
      // 解析失败当输出处理
    }
  }
  const title = `💻 bash${command ? ' · ' + escapeHtml(command) : ''}`;
  // 摘要：输出首行（去首尾空白）；无输出则无摘要
  let summary = '';
  if (output.trim()) {
    const firstLine = output.trim().split('\n')[0].slice(0, 120);
    if (firstLine) summary = escapeHtml(firstLine);
  }
  const summaryHtml = summary
    ? `<span class="bash-summary" title="${summary}">${summary}</span>`
    : '<span class="bash-summary muted">…</span>';
  const bodyHtml = output
    ? `<pre class="bash-output">${escapeHtml(output.slice(0, 6000))}</pre>`
    : '';
  return `<div class="bash-row">
    <div class="bash-line">${title}${summaryHtml}</div>
    ${bodyHtml ? `<div class="bash-body">${bodyHtml}</div>` : ''}
  </div>`;
}

/** 从 workflow 脚本文本提取 tools.xxx 调用序列（去重保序）。 */
function workflowSteps(script: string): string[] {
  const steps: string[] = [];
  const seen = new Set<string>();
  const re = /tools\.([a-zA-Z_][a-zA-Z0-9_]*)\s*\(/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(script)) !== null) {
    if (!seen.has(m[1])) {
      seen.add(m[1]);
      steps.push(m[1]);
    }
  }
  return steps;
}

/** workflow 编排流程卡片：脚本中的工具链 + 结果摘要。 */
function workflowCardHtml(m: MessageView): string {
  const content = m.content || '';
  // TOOL_CALL 时 content 是 {"script":"...","description":"..."} JSON；TOOL_RESULT 时是 "workflow 结果: ..."
  let script = '';
  let result = '';
  let description = '';
  if (content.startsWith('{')) {
    try {
      const parsed = JSON.parse(content) as { script?: string; description?: string };
      script = parsed.script ?? '';
      description = parsed.description ?? '';
    } catch {
      script = content;
    }
  } else {
    result = content;
    // 结果消息无脚本 → 用已有 steps（从同一 toolCallId 无法取，这里只显示结果）
  }
  const steps = workflowSteps(script);
  const stepsHtml = steps.length > 0
    ? `<div class="wf-steps">${steps.map((s, i) =>
        `<span class="wf-node">${i + 1}. ${escapeHtml(s)}</span>`).join('<span class="wf-arrow">→</span>')}</div>`
    : '';
  const descHtml = description ? `<div class="wf-desc">${escapeHtml(description)}</div>` : '';
  const resultHtml = result ? `<pre class="wf-result">${escapeHtml(result.slice(0, 2000))}</pre>` : '';
  return `<div class="workflow-card">
    <div class="wf-head"><span class="wf-icon">⚙️</span><b>Workflow 编排</b>
      <span class="wf-badge">${steps.length || 0} 步</span></div>
    ${descHtml}${stepsHtml}${resultHtml}
  </div>`;
}

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
  <div ref="container" class="msg-view" @click="onRowClick">
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
      <div v-if="appState.compactSummary" class="compact-marker">
        <div class="compact-icon">🗜️</div>
        <div class="compact-info">
          <div class="compact-title">已压缩 {{ appState.compactSummary.shadowedCount }} 条历史消息</div>
          <div class="compact-time">{{ new Date(appState.compactSummary.compactedAt).toLocaleString() }}</div>
        </div>
      </div>
      <div v-for="m in messages" :key="m.id" v-html="rowHtml(m)"></div>
      <div v-if="appState.running" class="row assistant">
        <div class="bubble">{{ streaming }}<span v-if="!streaming" class="cursor">▋</span></div>
      </div>
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

    <!-- 文件查看弹窗（点击工具行路径触发）；置于 msg-view 根内 → 单根组件，v-show 才能正常隐藏 -->
    <el-dialog
      :model-value="fileDialog !== null"
      :title="fileDialog ? '📄 ' + fileDialog.path : ''"
      width="70%"
      append-to-body
      @update:model-value="(v: boolean) => { if (!v) fileDialog = null }"
      @close="fileDialog = null"
    >
      <div v-if="fileDialog" class="file-dialog">
        <el-alert v-if="fileDialog.error" type="error" :title="fileDialog.error" :closable="false" />
        <div v-loading="fileDialog.loading" class="file-content">
          <pre v-if="!fileDialog.error">{{ fileDialog.content }}</pre>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<style scoped>
.msg-view { flex: 1; overflow-y: auto; padding: 20px; min-height: 0; }

/* 通用工具行：标题 + 摘要 + 展开体（对齐官方 ToolRow） */
.generic-tool-row {
  background: var(--dsh-bg-2);
  border: 1px solid var(--dsh-border);
  border-left: 3px solid var(--dsh-fg-2);
  border-radius: 8px;
  padding: 6px 12px;
  margin-bottom: 8px;
}
.tool-line {
  display: flex; align-items: center; gap: 10px;
  font-size: 12.5px; color: var(--dsh-fg-0);
}
.tool-action { font-weight: 600; color: var(--dsh-accent); flex-shrink: 0; }
.tool-path {
  color: var(--dsh-accent); cursor: pointer; text-decoration: underline;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  max-width: 55%; flex-shrink: 1;
}
.tool-path:hover { text-decoration: none; color: var(--dsh-accent); opacity: .85; }
.tool-summary {
  color: var(--dsh-fg-2); font-size: 12px;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  max-width: 40%; flex-shrink: 1;
}
.tool-body { margin-top: 6px; border-top: 1px solid var(--dsh-border); padding-top: 6px; }
.tool-pre {
  margin: 0; background: var(--dsh-code-bg); border-radius: 6px;
  padding: 6px 10px; font-size: 12px; max-height: 240px; overflow-y: auto;
  color: var(--dsh-fg-1); white-space: pre-wrap; word-break: break-word;
}
.file-dialog { display: flex; flex-direction: column; gap: 10px; }
.file-content { min-height: 200px; }
.file-content pre {
  margin: 0; background: var(--dsh-code-bg); border-radius: 8px;
  padding: 12px; font-size: 12.5px; max-height: 60vh; overflow: auto;
  color: var(--dsh-fg-1); white-space: pre-wrap; word-break: break-word;
}

/* Bash 行（对齐官方 BashRow：标题 + 摘要 + 展开输出） */
.bash-row {
  background: var(--dsh-bg-2);
  border: 1px solid var(--dsh-border);
  border-left: 3px solid var(--dsh-accent);
  border-radius: 8px;
  padding: 6px 12px;
  margin-bottom: 8px;
}
.bash-line {
  display: flex; align-items: center; gap: 10px;
  font-size: 12.5px; color: var(--dsh-fg-0);
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}
.bash-summary {
  color: var(--dsh-fg-2); font-size: 12px;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  max-width: 60%; flex-shrink: 1;
}
.bash-summary.muted { color: var(--dsh-fg-2); opacity: .6; }
.bash-body { margin-top: 6px; border-top: 1px solid var(--dsh-border); padding-top: 6px; }
.bash-output {
  margin: 0; background: var(--dsh-code-bg); border-radius: 6px;
  padding: 6px 10px; font-size: 12px; max-height: 240px; overflow-y: auto;
  color: var(--dsh-fg-1); white-space: pre-wrap; word-break: break-word;
}

.workflow-card {
  background: var(--dsh-bg-2);
  border: 1px solid var(--dsh-border);
  border-left: 3px solid #8a5cf6;
  border-radius: 10px;
  padding: 10px 14px;
  margin-bottom: 10px;
}
.wf-head { display: flex; align-items: center; gap: 8px; font-size: 13px; color: var(--dsh-fg-0); }
.wf-icon { font-size: 15px; }
.wf-badge {
  font-size: 11px; padding: 1px 8px; border-radius: 10px;
  background: rgba(138, 92, 246, .15); color: #8a5cf6; margin-left: auto;
}
.wf-desc { font-size: 12px; color: var(--dsh-fg-2); margin-top: 4px; }
.wf-steps { display: flex; align-items: center; flex-wrap: wrap; gap: 4px; margin-top: 8px; }
.wf-node {
  font-size: 12px; padding: 2px 8px; border-radius: 6px;
  background: var(--dsh-bg-3); color: var(--dsh-fg-0);
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}
.wf-arrow { color: var(--dsh-fg-2); font-size: 11px; }
.wf-result {
  margin-top: 8px; background: var(--dsh-code-bg); border-radius: 6px;
  padding: 6px 10px; font-size: 12px; max-height: 160px; overflow-y: auto;
  color: var(--dsh-fg-2); white-space: pre-wrap; word-break: break-word;
}

.welcome { text-align: center; padding: 80px 20px; }
.logo { font-size: 64px; }
h2 { font-size: 28px; margin: 12px 0 8px; color: var(--dsh-fg-0); }
p { color: var(--dsh-fg-2); margin-bottom: 30px; }
.tips { display: flex; flex-direction: column; gap: 10px; align-items: center; }
.tips div { background: var(--dsh-bg-2); border: 1px solid var(--dsh-border); border-radius: 8px; padding: 8px 18px; color: var(--dsh-fg-2); font-size: 13px; }
:deep(.row) { display: flex; }
:deep(.row.user) { justify-content: flex-end; }
:deep(.bubble) { max-width: 78%; padding: 10px 14px; border-radius: 12px; line-height: 1.6; background: var(--dsh-bg-2); border: 1px solid var(--dsh-border); overflow-wrap: break-word; margin-bottom: 14px; color: var(--dsh-fg-0); }
:deep(.row.user .bubble) { background: var(--dsh-accent); border-color: var(--dsh-accent); color: var(--dsh-accent-contrast); }
:deep(.bubble p) { margin: 6px 0; }
:deep(.bubble pre) { background: var(--dsh-code-bg); border-radius: 8px; padding: 10px; overflow-x: auto; font-size: 12.5px; margin: 8px 0; }
:deep(.bubble :not(pre) > code) { background: var(--dsh-code-bg); padding: 1px 5px; border-radius: 4px; }
:deep(.tool-details) { border: 1px solid var(--dsh-border); border-radius: 8px; margin: 4px 0 14px; background: var(--dsh-code-bg); overflow: hidden; color: var(--dsh-fg-2); }
:deep(.tool-details summary) { cursor: pointer; padding: 8px 12px; font-size: 12.5px; user-select: none; list-style: none; }
:deep(.tool-details summary::-webkit-details-marker) { display: none; }
:deep(.tool-details summary::before) { content: '▸'; margin-right: 6px; }
:deep(.tool-details[open] summary::before) { display: inline-block; transform: rotate(90deg); }
:deep(.tool-pre) { padding: 0 12px 10px; font-size: 12px; white-space: pre-wrap; overflow-wrap: break-word; color: var(--dsh-fg-0); }
.cursor { animation: blink 1s infinite; }
@keyframes blink { 50% { opacity: 0; } }

.question-card {
  margin: 10px 0;
  padding: 16px;
  border: 1px solid var(--dsh-accent);
  border-radius: 12px;
  background: var(--dsh-bg-2);
}
.question-text { font-size: 14px; margin-bottom: 12px; color: var(--dsh-fg-0); }
.question-options { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 12px; }
.option {
  padding: 8px 14px;
  border: 1px solid var(--dsh-border);
  border-radius: 20px;
  background: var(--dsh-bg-1);
  color: var(--dsh-fg-0);
  cursor: pointer;
  font-size: 13px;
  font-family: inherit;
}
.option:hover { border-color: var(--dsh-accent); }
.option.picked { border-color: var(--dsh-accent); background: var(--dsh-accent); color: var(--dsh-accent-contrast); }
.free-input { margin-bottom: 12px; }
.question-actions { display: flex; justify-content: flex-end; }

.compact-marker {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 14px;
  background: var(--dsh-accent-soft);
  border: 1px solid var(--dsh-accent);
  border-radius: 10px;
  margin-bottom: 16px;
}
.compact-icon { font-size: 22px; }
.compact-info { flex: 1; }
.compact-title { font-size: 13px; font-weight: 500; color: var(--dsh-fg-0); }
.compact-time { font-size: 11px; color: var(--dsh-fg-2); margin-top: 2px; }
</style>