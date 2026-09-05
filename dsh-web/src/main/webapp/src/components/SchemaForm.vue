<script setup lang="ts">
// SchemaForm.vue — schema 驱动表单容器（内核②/P2 递归 + P3 官方语义 + C 档官方模型层）。
// 数据源：GET /api/settings/meta 的 redacted view（value/user/revision/secrets/applies +
// 官方 schemastery envelope schema 字段，C 档双发）。
// 顶层字段源：view.schema 存在时经 dsh-client-schema-form rehydrateSchema 重建官方节点树，
// 由 schemaFieldModel 归一为 RenderField（保序、label/role/visibleWhen/union→enum）；
// 无 envelope（旧后端）回退 view.settings 描述符直通（字段级兼容）。
// 草稿为 reactive 树（view.value 现值优先，缺键用 RenderField 默认递归构造）；
// secret 顶层键不进 draft（值与变更都只走 write-only path op，防默认值误写）；
// 保存 = validateDraft（官方校验）→ 一次性 POST /api/settings/{ns}/ops（diff 变更 + secret ops）；
// 409 冲突 → 提示 + emit conflict（SettingsPage 重载最新 view，丢弃过期草稿）。
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { validateDraft } from '@deepseek-ai/dsh-client-schema-form';
import { putSettingOps, type SettingNamespaceView, type SettingPathOp } from '../api';
import { defaultValue, deepEquals } from '../schemaDefaults';
import {
  rootNodeFromEnvelope,
  topRenderFields,
  type RenderField,
} from '../schemaFieldModel';
import SchemaField from './SchemaField.vue';

const props = defineProps<{
  view: SettingNamespaceView;
}>();

const emit = defineEmits<{
  (e: 'saved', namespace: string, revision: number): void;
  (e: 'conflict', namespace: string): void;
}>();

/** 官方 envelope 重建的 object 根（无/非法 → null，回退描述符直通）。 */
const schemaRoot = computed(() => rootNodeFromEnvelope(props.view.schema));

/** 顶层渲染字段：官方树保序优先；回退描述符。 */
const fields = computed<RenderField[]>(() => topRenderFields(props.view.settings, schemaRoot.value));

/** 本地草稿树：以 view.value 初始化，未覆盖键用字段默认值（含嵌套递归）；secret 顶层键跳过。 */
const draft = reactive<Record<string, unknown>>({});

function syncDraft(): void {
  const next: Record<string, unknown> = {};
  for (const f of fields.value) {
    if (f.secret) continue; // secret 值不回显不入草稿（write-only sidecar）
    const cur = props.view.value[f.key];
    next[f.key] = cur !== undefined && cur !== null ? cur : defaultValue(f);
  }
  for (const k of Object.keys(draft)) delete draft[k];
  Object.assign(draft, next);
}

// view 替换（保存/冲突后 SettingsPage reload）→ 重同步草稿
watch(
  () => [props.view.namespace, props.view.revision, props.view.value, props.view.schema],
  syncDraft,
  { immediate: true, deep: true },
);

// C 档验收标记：schema 字段存在且 rehydrate 成功 → official 驱动；否则回退 descriptor 直通。
onMounted(() => {
  console.info(
    `[dsh-c] ns=${props.view.namespace} driver=${schemaRoot.value ? 'official' : 'descriptor'}`,
    `fields=${fields.value.length} schema=${schemaRoot.value ? 'ok' : 'none'}`,
  );
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
  const f = fields.value.find((x) => x.key === key);
  draft[key] = f ? defaultValue(f) : undefined;
  pendingUnset.add(key);
}

/** secret 编辑收集：pathKey → {path, value}（value null = 清除/unset）。 */
const pendingSecrets = reactive(
  new Map<string, { path: string[]; value: unknown | null }>(),
);
function onSecret(path: string[], value: unknown | null): void {
  pendingSecrets.set(path.join('.'), { path, value });
}

/** 草稿 → 纯 JSON 值（validateDraft 需 plain object；secret 与 undefined 不参与校验）。 */
function plainDraft(): Record<string, unknown> {
  return JSON.parse(JSON.stringify(draft)) as Record<string, unknown>;
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
    // 官方模型层校验：rehydrated schemastery 节点树拒绝非法草稿（enum 越界/数值越界/类型不符）
    if (schemaRoot.value) {
      const problem = validateDraft(schemaRoot.value, plainDraft());
      if (problem) {
        saveError.value = `校验未通过：${problem}`;
        return;
      }
    }
    const ops: SettingPathOp[] = [];
    // 1) 变更的顶层键（排除已恢复默认与 secret——二者走 unset/write-only path op）
    for (const f of fields.value) {
      if (f.secret || pendingUnset.has(f.key)) continue;
      const cur = draft[f.key];
      const base = props.view.value[f.key];
      if (!deepEquals(cur, base)) ops.push({ op: 'set', path: [f.key], value: cur });
    }
    // 2) 恢复默认（unset）
    for (const key of pendingUnset) {
      ops.push({ op: 'unset', path: [key] });
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
    <div v-for="field in fields" :key="field.key" class="sf-top">
      <div v-if="overriddenTop(field.key)" class="sf-top-bar">
        <span class="sf-badge-overridden" title="该键已由用户覆盖（view.user 在场）">已覆盖</span>
        <el-button size="small" text class="sf-reset" @click="resetTop(field.key)">恢复默认</el-button>
      </div>
      <SchemaField
        :field="field"
        :layer="draft"
        :path="[field.key]"
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
