<script setup lang="ts">
import { ref, watch, computed, onMounted, onBeforeUnmount } from 'vue';
import { appState, setModel } from '../store';
import CommandMenu, { type CommandItem } from './CommandMenu.vue';
import SkillPicker from './SkillPicker.vue';
import ModelPicker from './ModelPicker.vue';
import { compactSession, exportSession, submitFeedback, listMessages, executeSkill, type SkillInfo } from '../api';

const emit = defineEmits<{
  (e: 'send', text: string): void;
  (e: 'stop'): void;
  (e: 'clear'): void;
  (e: 'command', name: string): void;
}>();

const draft = ref('');
const composing = ref(false);
const commandMenuOpen = ref(false);
const modelPickerOpen = ref(false);
const skillPickerOpen = ref(false);
const cmdWrapperRef = ref<HTMLElement | null>(null);
const modelWrapperRef = ref<HTMLElement | null>(null);
const textareaWrapperRef = ref<HTMLElement | null>(null);
const lastSlashCommand = ref('');

const SLASH_COMMANDS = ['/skill'];

/** 无任何工作区且无当前会话：输入禁用（对齐官方 hero inert——先选工作目录是前置）。 */
const inputDisabled = computed(
  () => appState.disabled
    || (!appState.sessionId && appState.workspacesPhase === 'ready' && appState.workspaces.length === 0),
);

/** 输入框占位文案：无工作区时引导先选目录。 */
const inputPlaceholder = computed(() =>
  !appState.sessionId && appState.workspacesPhase === 'ready' && appState.workspaces.length === 0
    ? '先选择工作目录（点击顶部 📁 工作区），再开始对话…'
    : '分配一个任务或提问任何问题...（Enter 发送 / Shift+Enter 换行 / /skill 选技能）',
);

function detectSlashCommand(text: string): string | null {
  const trimmed = text.trim();
  for (const cmd of SLASH_COMMANDS) {
    if (trimmed === cmd || trimmed.startsWith(cmd + ' ')) {
      return cmd;
    }
  }
  return null;
}

function onDocPointerDown(e: PointerEvent): void {
  if (commandMenuOpen.value && cmdWrapperRef.value && !cmdWrapperRef.value.contains(e.target as Node)) {
    commandMenuOpen.value = false;
  }
}

onMounted(() => {
  document.addEventListener('pointerdown', onDocPointerDown, true);
});

onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', onDocPointerDown, true);
});

watch(
  () => appState.draft,
  (v) => { if (v !== draft.value) draft.value = v; },
);

watch(draft, (v) => {
  // 写回全局草稿：用户输入实时同步，供「在此创建会话」等切换流程迁移草稿
  if (v !== appState.draft) appState.draft = v;
  const cmd = detectSlashCommand(v);
  if (cmd === '/skill' && !skillPickerOpen.value) {
    skillPickerOpen.value = true;
    lastSlashCommand.value = cmd;
  }
  if (cmd !== '/skill' && skillPickerOpen.value) {
    skillPickerOpen.value = false;
  }
});

const currentModel = computed(() =>
  appState.models.find(m => m.id === appState.model),
);

const commands = computed<CommandItem[]>(() => [
  {
    id: 'compact',
    icon: '🗜️',
    label: 'compact',
    group: '命令',
    detail: '压缩旧对话历史，节省 context',
    action: () => handleCompact(),
  },
  {
    id: 'export',
    icon: '📦',
    label: 'export',
    group: '命令',
    detail: '下载本会话记录为 ZIP 归档',
    action: () => handleExport(),
  },
  {
    id: 'feedback',
    icon: '💬',
    label: 'feedback',
    group: '命令',
    detail: '记录本会话反馈',
    action: () => handleFeedback(),
  },
  {
    id: 'goal',
    icon: '🎯',
    label: 'goal',
    group: '命令',
    detail: '设置或查看长期任务目标',
    action: () => emit('command', 'goal'),
  },
  {
    id: 'plan',
    icon: '📋',
    label: appState.planMode ? 'plan (退出)' : 'plan (进入)',
    group: '命令',
    detail: '进入或退出计划模式',
    action: () => emit('command', 'plan'),
  },
  {
    id: 'model',
    icon: '🧠',
    label: 'model',
    group: '命令',
    detail: '选择本会话使用的模型',
    action: () => { modelPickerOpen.value = true; },
  },
  {
    id: 'skill',
    icon: '⚡',
    label: 'skill',
    group: '命令',
    detail: '选择并执行一个技能',
    action: () => { skillPickerOpen.value = true; },
  },
]);

function toggleCommandMenu(): void {
  commandMenuOpen.value = !commandMenuOpen.value;
}

function onCompositionStart(): void { composing.value = true; }
function onCompositionEnd(): void {
  setTimeout(() => { composing.value = false; }, 10);
}

function onKeydown(e: KeyboardEvent): void {
  if (e.key === 'Enter' && e.shiftKey) return;
  if (e.key !== 'Enter') return;
  const legacy = e as KeyboardEvent & { keyCode?: number };
  if (composing.value || e.isComposing || legacy.keyCode === 229) return;
  e.preventDefault();
  if (e.repeat) return;
  const text = draft.value.trim();
  if (text) emit('send', text);
}

function primary(): void {
  if (appState.running) {
    emit('stop');
  } else {
    const text = draft.value.trim();
    if (text) emit('send', text);
  }
}

function onModelSelect(id: string): void {
  setModel(id);
  modelPickerOpen.value = false;
}

async function handleCompact(): Promise<void> {
  const id = appState.sessionId;
  if (!id || appState.compacting) return;
  appState.compacting = true;
  try {
    const result = await compactSession(id);
    appState.compactSummary = {
      summary: result.summary,
      shadowedCount: result.shadowedCount,
      compactedAt: new Date().toISOString(),
    };
    appState.messages = await listMessages(id) as never[];
  } catch (e) {
    appState.notice = '压缩失败: ' + (e as Error).message;
  } finally {
    appState.compacting = false;
  }
}

async function handleExport(): Promise<void> {
  const id = appState.sessionId;
  if (!id) return;
  try {
    const blob = await exportSession(id);
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `session-${id}.zip`;
    a.click();
    URL.revokeObjectURL(url);
  } catch (e) {
    appState.notice = '导出失败: ' + (e as Error).message;
  }
}

async function handleFeedback(): Promise<void> {
  const id = appState.sessionId;
  if (!id) return;
  const text = window.prompt('请输入反馈内容：');
  if (!text) return;
  try {
    await submitFeedback(id, text);
  } catch (e) {
    appState.notice = '反馈提交失败: ' + (e as Error).message;
  }
}

async function handleSkill(skillName: string, userMessage?: string): Promise<void> {
  const id = appState.sessionId;
  if (!id) {
    appState.notice = '请先创建会话';
    return;
  }
  try {
    const result = await executeSkill(skillName, id, userMessage);
    if (result.status === 'injected') {
      appState.notice = `技能 "${skillName}" 已注入`;
    }
    appState.messages = await listMessages(id) as never[];
  } catch (e) {
    appState.notice = `技能执行失败: ${(e as Error).message}`;
  }
}

function onSkillPicked(skill: SkillInfo): void {
  skillPickerOpen.value = false;
  const remaining = draft.value.trim().startsWith('/skill')
    ? draft.value.trim().slice('/skill'.length).trim()
    : '';
  if (remaining) {
    draft.value = '';
    void handleSkill(skill.name, remaining);
  } else {
    draft.value = '';
    const msg = window.prompt(`执行技能 "${skill.name}"，输入给 AI 的指令（留空使用默认）：`);
    if (msg === null) return;
    void handleSkill(skill.name, msg || undefined);
  }
}
</script>

<template>
  <div class="composer">
    <div class="card">
      <div ref="textareaWrapperRef">
        <el-input
          v-model="draft"
          type="textarea"
          :rows="2"
          :autosize="{ minRows: 2, maxRows: 10 }"
          resize="none"
          :placeholder="inputPlaceholder"
          :disabled="inputDisabled"
          @compositionstart="onCompositionStart"
          @compositionend="onCompositionEnd"
          @keydown="onKeydown"
        />
      </div>
      <SkillPicker
        :visible="skillPickerOpen"        :anchor-el="textareaWrapperRef"
        @close="skillPickerOpen = false"
        @select="onSkillPicked"
      />
      <div class="row">
        <div class="tools">
          <div class="cmd-wrapper" ref="cmdWrapperRef">
            <button
              class="cmd-btn"
              :class="{ active: commandMenuOpen }"
              title="命令菜单"
              @click="toggleCommandMenu"
            >
              <span class="plus-icon">＋</span>
            </button>
            <CommandMenu
              v-if="commandMenuOpen"
              :commands="commands"
              @close="commandMenuOpen = false"
            />
          </div>
          <button
            v-if="appState.planMode"
            class="mode-chip plan"
            title="计划模式已激活，点击退出"
            @click="emit('command', 'plan')"
          >📋 计划</button>
          <el-button text title="清空会话" @click="emit('clear')">🧹</el-button>
        </div>
        <div class="trailing">
          <div class="model-picker-wrapper" ref="modelWrapperRef">
            <button
              class="model-btn"
              @click="modelPickerOpen = !modelPickerOpen"
            >
              <span class="model-name">{{ currentModel?.name || '选择模型' }}</span>
              <span class="chev" :class="{ open: modelPickerOpen }">▾</span>
            </button>
            <ModelPicker
              :visible="modelPickerOpen"
              :anchor-el="modelWrapperRef"
              @close="modelPickerOpen = false"
              @select="onModelSelect"
            />
          </div>
          <el-button
            v-if="appState.compacting"
            type="info"
            size="small"
            loading
            disabled
          >压缩中...</el-button>
          <el-button
            :type="appState.running ? 'warning' : 'primary'"
            circle
            :title="appState.running ? '停止生成 (Esc)' : '发送 (Enter)'"
            @click="primary"
          >
            <span class="btn-icon">{{ appState.running ? '✕' : '➤' }}</span>
          </el-button>
        </div>
      </div>
      <div v-if="appState.compactSummary" class="compact-summary">
        <span class="compact-label">📝 已压缩：{{ appState.compactSummary.shadowedCount }} 条消息</span>
        <span class="compact-time">{{ new Date(appState.compactSummary.compactedAt).toLocaleTimeString() }}</span>
      </div>
      <el-alert v-if="appState.notice" :title="appState.notice" type="error" :closable="false" class="notice" />
    </div>
    <div class="hint">Agent 可能会出错，请核对重要信息 · 试试 /skill 或 + 按钮</div>
  </div>
</template>

<style scoped>
.composer {
  padding: 12px 20px 16px;
  border-top: 1px solid var(--dsh-border);
  flex-shrink: 0;
  max-height: 50vh;
  display: flex;
  flex-direction: column;
}
.card {
  background: var(--dsh-bg-2);
  border: none;
  border-radius: 16px;
  padding: 10px 14px;
  position: relative;
}
.row { display: flex; justify-content: space-between; align-items: center; margin-top: 8px; }
.tools { display: flex; align-items: center; gap: 6px; }
.trailing { display: flex; align-items: center; gap: 10px; }
.btn-icon { display: inline-flex; align-items: center; justify-content: center; font-size: 16px; line-height: 1; width: 100%; height: 100%; }
.notice { margin-top: 8px; }
.hint { text-align: center; font-size: 11px; color: var(--dsh-fg-2); margin-top: 8px; flex-shrink: 0; }

.cmd-wrapper { position: relative; }
.cmd-btn {
  width: 28px; height: 28px; border-radius: 8px;
  background: none; border: 1px solid var(--dsh-border);
  color: var(--dsh-fg-2); cursor: pointer;
  display: inline-flex; align-items: center; justify-content: center;
  font-size: 16px; transition: all .15s;
}
.cmd-btn:hover { color: var(--dsh-accent); border-color: var(--dsh-accent); background: var(--dsh-accent-soft); }
.cmd-btn.active { color: var(--dsh-accent); border-color: var(--dsh-accent); background: var(--dsh-accent-soft); }

.mode-chip {
  font-size: 12px; padding: 4px 10px; border-radius: 12px;
  border: 1px solid var(--dsh-border); cursor: pointer;
  background: var(--dsh-bg-0); color: var(--dsh-fg-2);
  display: inline-flex; align-items: center; gap: 4px;
}
.mode-chip.plan { color: var(--dsh-accent); border-color: var(--dsh-accent); }
.mode-chip:hover { background: var(--dsh-accent-soft); }

.model-picker-wrapper { position: relative; }
.model-btn {
  display: inline-flex; align-items: center; gap: 4px;
  padding: 5px 12px; border-radius: 8px;
  background: var(--dsh-bg-0); border: 1px solid var(--dsh-border);
  color: var(--dsh-fg-0); font-size: 13px; cursor: pointer;
  transition: all .15s; font-family: inherit;
}
.model-btn:hover { border-color: var(--dsh-accent); color: var(--dsh-accent); }
.model-name { max-width: 140px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.chev { font-size: 10px; transition: transform .15s; }
.chev.open { transform: rotate(180deg); }

.compact-summary {
  margin-top: 8px;
  padding: 6px 10px;
  background: var(--dsh-accent-soft);
  border-radius: 8px;
  font-size: 12px;
  color: var(--dsh-fg-0);
  display: flex; align-items: center; gap: 8px;
}
.compact-label { flex: 1; }
.compact-time { color: var(--dsh-fg-2); }

:deep(.el-textarea__inner) {
  border: none !important;
  box-shadow: none !important;
  background: transparent !important;
  padding: 4px 0;
}
:deep(.el-textarea__inner:focus) {
  border: none !important;
  box-shadow: none !important;
}
</style>