<script setup lang="ts">
// 输入框（deepseek InputBar 模式 + Element Plus el-input autosize）：
// IME 组合保护 / Enter 发送 / Shift+Enter 换行 / 连发保护 / Send-Stop 切换。
import { ref, watch } from 'vue';
import { appState } from '../store';

const emit = defineEmits<{
  (e: 'send', text: string): void;
  (e: 'stop'): void;
  (e: 'clear'): void;
}>();

const draft = ref('');
const composing = ref(false);

// 外部清空（发送后 appState.draft = ''）→ 同步草稿
watch(
  () => appState.draft,
  (v) => { if (v !== draft.value) draft.value = v; },
);

const MODELS = [
  { group: 'DeepSeek', id: 'deepseek-chat', name: 'DeepSeek Chat' },
  { group: 'DeepSeek', id: 'deepseek-reasoner', name: 'DeepSeek Reasoner' },
  { group: 'OpenAI', id: 'gpt-4o', name: 'GPT-4o' },
  { group: 'OpenAI', id: 'gpt-4o-mini', name: 'GPT-4o Mini' },
];

function onCompositionStart(): void { composing.value = true; }
function onCompositionEnd(): void {
  // Safari 在 compositionend 之后才送 closing keydown
  setTimeout(() => { composing.value = false; }, 10);
}

function onKeydown(e: KeyboardEvent): void {
  if (e.key === 'Enter' && e.shiftKey) return; // Shift+Enter 无条件换行
  if (e.key !== 'Enter') return;
  const legacy = e as KeyboardEvent & { keyCode?: number };
  if (composing.value || e.isComposing || legacy.keyCode === 229) return; // IME 保护
  e.preventDefault();
  if (e.repeat) return; // 连发保护
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
</script>

<template>
  <div class="composer">
    <div class="card">
      <el-input
        v-model="draft"
        type="textarea"
        :rows="2"
        :autosize="{ minRows: 2, maxRows: 12 }"
        resize="none"
        placeholder="分配一个任务或提问任何问题...（Enter 发送 / Shift+Enter 换行）"
        :disabled="appState.disabled"
        @compositionstart="onCompositionStart"
        @compositionend="onCompositionEnd"
        @keydown="onKeydown"
      />
      <div class="row">
        <div class="tools">
          <el-button text :icon="'🧹'" title="清空会话" @click="emit('clear')" />
        </div>
        <div class="trailing">
          <el-select v-model="appState.model" size="small" style="width: 170px" placeholder="模型">
            <el-option-group v-for="g in MODELS" :key="g.group" :label="g.group">
              <el-option v-for="m in MODELS.filter((x) => x.group === g.group)" :key="m.id"
                :label="m.name" :value="m.id" />
            </el-option-group>
          </el-select>
          <el-button
            :type="appState.running ? 'warning' : 'primary'"
            :icon="appState.running ? '✕' : '➤'"
            circle
            :title="appState.running ? '停止生成' : '发送 (Enter)'"
            @click="primary"
          />
        </div>
      </div>
      <el-alert v-if="appState.notice" :title="appState.notice" type="error" :closable="false" class="notice" />
    </div>
    <div class="hint">Agent 可能会出错，请核对重要信息</div>
  </div>
</template>

<style scoped>
.composer { padding: 12px 20px 16px; border-top: 1px solid #33343d; }
.card { background: #26272e; border: 1px solid #33343d; border-radius: 16px; padding: 10px 14px; }
.row { display: flex; justify-content: space-between; align-items: center; margin-top: 8px; }
.tools { display: flex; align-items: center; }
.trailing { display: flex; align-items: center; gap: 10px; }
.notice { margin-top: 8px; }
.hint { text-align: center; font-size: 11px; color: #9a9ba6; margin-top: 8px; }
</style>
