<script setup lang="ts">
// SchemaForm.vue — 通用 schema 驱动表单（内核②：一套 schema 多消费）。
// 由后端 SettingDescriptor（type/label/description/options/min/max/step/defaultValue）
// 生成动态表单：boolean → 开关、number/integer → 数字输入（步进/边界）、enum → 下拉、
// 其余 → 文本输入。保存逐键 PUT /api/settings/{namespace}/{key}。
import { reactive, ref, watch } from 'vue';
import { putSetting, type SettingDescriptor } from '../api';
import { pushNotice } from '../store';

const props = defineProps<{
  namespace: string;
  settings: SettingDescriptor[];
  values: Record<string, unknown>;
}>();

const emit = defineEmits<{ (e: 'changed', namespace: string, key: string, value: unknown): void }>();

/** 本地草稿：以当前值初始化，未覆盖键用描述符默认值。 */
const draft = reactive<Record<string, any>>({});

function syncDraft(): void {
  for (const s of props.settings) {
    draft[s.key] = props.values[s.key] ?? s.defaultValue ?? defaultFor(s);
  }
}

function defaultFor(s: SettingDescriptor): unknown {
  if (s.type === 'boolean') return false;
  if (s.type === 'number') return 0;
  if (s.type === 'integer') return 0;
  return '';
}

watch(() => [props.namespace, props.values, props.settings], syncDraft, { immediate: true, deep: true });

const saving = ref(false);
const saveError = ref<string | null>(null);

async function saveAll(): Promise<void> {
  saving.value = true;
  saveError.value = null;
  try {
    for (const s of props.settings) {
      const value = draft[s.key] ?? null;
      await putSetting(props.namespace, s.key, value);
      emit('changed', props.namespace, s.key, value);
      pushNotice(`已保存 ${props.namespace}.${s.key}`);
    }
  } catch (e) {
    saveError.value = (e as Error).message;
  } finally {
    saving.value = false;
  }
}

</script>

<template>
  <div class="schema-form">
    <div v-for="s in settings" :key="s.key" class="sf-row">
      <div class="sf-meta">
        <label class="sf-label" :title="s.description ?? ''">{{ s.label ?? s.key }}</label>
        <span v-if="s.description" class="sf-desc">{{ s.description }}</span>
      </div>
      <div class="sf-control">
        <el-switch
          v-if="s.type === 'boolean'"
          v-model="draft[s.key]"
        />
        <el-input-number
          v-else-if="s.type === 'number' || s.type === 'integer'"
          v-model="draft[s.key]"
          :min="s.min ?? undefined"
          :max="s.max ?? undefined"
          :step="s.step ?? (s.type === 'integer' ? 1 : 0.1)"
          :precision="s.type === 'integer' ? 0 : undefined"
          size="small"
          controls-position="right"
        />
        <el-select v-else-if="s.type === 'enum' && s.options && s.options.length > 0" v-model="draft[s.key]" size="small" style="width: 100%">
          <el-option v-for="o in s.options" :key="o" :value="o" :label="o" />
        </el-select>
        <el-input v-else v-model="draft[s.key]" size="small" />
      </div>
    </div>
    <div v-if="saveError" class="sf-error">保存失败：{{ saveError }}</div>
    <div class="sf-actions">
      <el-button size="small" type="primary" :loading="saving" @click="saveAll">保存更改</el-button>
    </div>
  </div>
</template>

<style scoped>
.schema-form { display: flex; flex-direction: column; gap: 12px; }
.sf-row { display: flex; align-items: flex-start; gap: 16px; }
.sf-meta { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.sf-label { font-size: 13px; font-weight: 600; color: var(--dsh-fg-0); }
.sf-desc { font-size: 11.5px; color: var(--dsh-fg-2); line-height: 1.5; }
.sf-control { width: 220px; flex-shrink: 0; }
.sf-actions { display: flex; align-items: center; gap: 10px; padding-top: 4px; }
.sf-error { color: var(--dsh-danger, #f56c6c); font-size: 12px; }
</style>
