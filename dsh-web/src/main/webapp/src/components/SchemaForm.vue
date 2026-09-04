<script setup lang="ts">
// SchemaForm.vue — schema 驱动表单容器（内核②/P2：一套 schema 多消费）。
// 数据源：后端 SettingDescriptor（叶子 + object children 递归 + array items + visibleWhen 联动）。
// 顶层每键渲染递归 SchemaField；草稿为 reactive 树（现值优先，缺省用 schema 默认值递归构造）；
// 保存仍逐顶层键 PUT /api/settings/{namespace}/{key}（整键嵌套 JSON，wire 契约不变）。
import { reactive, ref, watch } from 'vue';
import { putSetting, type SettingDescriptor } from '../api';
import { defaultValue } from '../schemaDefaults';
import { pushNotice } from '../store';
import SchemaField from './SchemaField.vue';

const props = defineProps<{
  namespace: string;
  settings: SettingDescriptor[];
  values: Record<string, unknown>;
}>();

const emit = defineEmits<{ (e: 'changed', namespace: string, key: string, value: unknown): void }>();

/** 本地草稿树：以当前值初始化，未覆盖键用描述符默认值（含嵌套递归）。 */
const draft = reactive<Record<string, unknown>>({});

function syncDraft(): void {
  for (const s of props.settings) {
    const cur = props.values[s.key];
    draft[s.key] = cur !== undefined && cur !== null ? cur : defaultValue(s);
  }
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
    <SchemaField
      v-for="s in settings"
      :key="s.key"
      :field="s"
      :layer="draft"
    />
    <div v-if="saveError" class="sf-error">保存失败：{{ saveError }}</div>
    <div class="sf-actions">
      <el-button size="small" type="primary" :loading="saving" @click="saveAll">保存更改</el-button>
    </div>
  </div>
</template>

<style scoped>
.schema-form { display: flex; flex-direction: column; gap: 12px; }
.sf-actions { display: flex; align-items: center; gap: 10px; padding-top: 4px; }
.sf-error { color: var(--dsh-danger, #f56c6c); font-size: 12px; }
</style>
