<script setup lang="ts">
// SchemaForm.vue — schema 驱动表单容器（内核②/P2 递归 + P3 官方语义）。
// 数据源：GET /api/settings/meta 的 redacted view（value/user/revision/secrets/applies）。
// 草稿为 reactive 树（view.value 现值优先，缺键用 schema 默认递归构造）；
// 顶层键行显示「已覆盖」（path ∈ user）与「恢复默认」（unset op）；
// secret 叶子经 SchemaField write-only 上抛 → 收集为 set/unset path op；
// 保存 = 一次性 POST /api/settings/{ns}/ops（diff 变更 + secret ops + unset ops，expectedRevision=view.revision）；
// 409 冲突 → 提示 + emit conflict（SettingsPage 重载最新 view，丢弃过期草稿）。
import { computed, reactive, ref, watch } from 'vue';
import { putSettingOps, type SettingNamespaceView, type SettingPathOp } from '../api';
import { defaultValue, deepEquals } from '../schemaDefaults';
import SchemaField from './SchemaField.vue';

const props = defineProps<{
  view: SettingNamespaceView;
}>();

const emit = defineEmits<{
  (e: 'saved', namespace: string, revision: number): void;
  (e: 'conflict', namespace: string): void;
}>();

/** 本地草稿树：以 view.value 初始化，未覆盖键用描述符默认值（含嵌套递归）。 */
const draft = reactive<Record<string, unknown>>({});

function syncDraft(): void {
  const next: Record<string, unknown> = {};
  for (const s of props.view.settings) {
    const cur = props.view.value[s.key];
    next[s.key] = cur !== undefined && cur !== null ? cur : defaultValue(s);
  }
  for (const k of Object.keys(draft)) delete draft[k];
  Object.assign(draft, next);
}

// view 替换（保存/冲突后 SettingsPage reload）→ 重同步草稿
watch(() => [props.view.namespace, props.view.revision, props.view.value], syncDraft, {
  immediate: true,
  deep: true,
});

/** secret sidecar 查询：path.join('.') → set。 */
const secretMap = computed(() => {
  const m = new Map<string, boolean>();
  for (const s of props.view.secrets ?? []) m.set(s.path.join('.'), s.set);
  return m;
});
function secretSetAt(path: string[]): boolean {
  return secretMap.value.get(path.join('.')) ?? false;
}

/** 顶层键覆盖标记（presence）：键在 redacted user 层在场 ⇔ user-overridden。 */
function overriddenTop(key: string): boolean {
  const u = props.view.user;
  return !!u && Object.prototype.hasOwnProperty.call(u, key);
}

/** 恢复默认：本地回默认渲染 + 记 unset op（保存时提交，不 set 默认值）。 */
const pendingUnset = reactive(new Set<string>());
function resetTop(key: string): void {
  const desc = props.view.settings.find((s) => s.key === key);
  draft[key] = desc ? defaultValue(desc) : undefined;
  pendingUnset.add(key);
}

/** secret 编辑收集：pathKey → {path, value}（value null = 清除/unset）。 */
const pendingSecrets = reactive(
  new Map<string, { path: string[]; value: unknown | null }>(),
);
function onSecret(path: string[], value: unknown | null): void {
  pendingSecrets.set(path.join('.'), { path, value });
}

const saving = ref(false);
const saveError = ref<string | null>(null);
const saveOk = ref('');
let okTimer: ReturnType<typeof setTimeout> | undefined;

async function saveAll(): Promise<void> {
  if (saving.value) return;
  saving.value = true;
  saveError.value = null;
  saveOk.value = '';
  try {
    const ops: SettingPathOp[] = [];
    const pending = new Set<string>();
    // 1) 变更的顶层键（排除已恢复默认的键——它们走 unset）
    for (const s of props.view.settings) {
      const key = s.key;
      if (pendingUnset.has(key)) continue;
      const cur = draft[key];
      const base = props.view.value[key];
      if (!deepEquals(cur, base)) ops.push({ op: 'set', path: [key], value: cur });
    }
    // 2) 恢复默认（unset）
    for (const key of pendingUnset) {
      ops.push({ op: 'unset', path: [key] });
      pending.add(key);
    }
    // 3) secret set/unset
    for (const { path, value } of pendingSecrets.values()) {
      ops.push(value === null ? { op: 'unset', path } : { op: 'set', path, value });
    }

    if (ops.length === 0) {
      saveOk.value = '没有需要保存的更改';
      return;
    }

    const resp = await putSettingOps(props.view.namespace, ops, props.view.revision);
    pendingUnset.clear();
    pendingSecrets.clear();
    saveOk.value = '已保存 ✓';
    if (okTimer) clearTimeout(okTimer);
    okTimer = setTimeout(() => (saveOk.value = ''), 3000);
    emit('saved', props.view.namespace, resp.revision);
  } catch (e) {
    const err = e as Error & { conflict?: boolean };
    if (err.conflict) {
      saveError.value = err.message;
      emit('conflict', props.view.namespace);
    } else {
      saveError.value = (e as Error).message;
    }
  } finally {
    saving.value = false;
  }
}
</script>

<template>
  <div class="schema-form">
    <div v-for="s in view.settings" :key="s.key" class="sf-top">
      <div v-if="overriddenTop(s.key)" class="sf-top-bar">
        <span class="sf-badge-overridden" title="该键已由用户覆盖（view.user 在场）">已覆盖</span>
        <el-button size="small" text class="sf-reset" @click="resetTop(s.key)">恢复默认</el-button>
      </div>
      <SchemaField
        :field="s"
        :layer="draft"
        :path="[s.key]"
        :secret-set="secretSetAt"
        @secret="onSecret"
      />
    </div>
    <div v-if="saveError" class="sf-error">保存失败：{{ saveError }}</div>
    <div v-if="saveOk" class="sf-ok">{{ saveOk }}</div>
    <div class="sf-actions">
      <el-button size="small" type="primary" :loading="saving" @click="saveAll">保存更改</el-button>
      <span v-if="pendingUnset.size || pendingSecrets.size" class="sf-pending">
        待保存：{{ pendingUnset.size }} 项恢复默认 · {{ pendingSecrets.size }} 项凭据
      </span>
    </div>
  </div>
</template>

<style scoped>
.schema-form { display: flex; flex-direction: column; gap: 12px; }
.sf-top { display: flex; flex-direction: column; gap: 4px; }
.sf-top-bar { display: flex; align-items: center; gap: 8px; padding-left: 2px; }
.sf-badge-overridden {
  font-size: 11px; line-height: 1; padding: 3px 7px; border-radius: 999px;
  background: rgba(230, 162, 60, 0.14); color: var(--dsh-warn, #e6a23c);
  border: 1px solid rgba(230, 162, 60, 0.35);
}
.sf-reset { margin-left: auto; font-size: 12px; }
.sf-actions { display: flex; align-items: center; gap: 12px; padding-top: 4px; }
.sf-error { color: var(--dsh-danger, #f56c6c); font-size: 12px; }
.sf-ok { color: var(--dsh-success, #67c23a); font-size: 12px; }
.sf-pending { color: var(--dsh-fg-2); font-size: 12px; }
</style>
