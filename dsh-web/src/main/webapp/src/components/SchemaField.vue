<script setup lang="ts">
// SchemaField.vue — 递归 schema 字段编辑器（P2）。
// 叶子按 type 渲染 EP 控件（boolean→switch / number·integer→input-number / enum→select / 其余→input）；
// object→折叠分组内递归 children；array→行式增删（items 标量行内控件 / items object 行卡片内嵌递归）；
// visibleWhen→同层兄弟键值深等时才显示（隐藏 ≠ 删除，值保留在草稿/提交中）。
// 值读写均作用于 layer（reactive 树），不改变 PUT 逐键契约。
import { computed } from 'vue';
import type { SettingDescriptor } from '../api';
import { defaultValue, deepEquals, objectDefault } from '../schemaDefaults';

const props = defineProps<{
  field: SettingDescriptor;
  /** 当前对象层：field.key 在此读写。 */
  layer: Record<string, unknown>;
}>();

const isObject = computed(() => props.field.type === 'object');
const isArray = computed(() => props.field.type === 'array');
const items = computed(() => props.field.items ?? null);
const itemsIsObject = computed(() => items.value?.type === 'object');

/** 联动可见：无 visibleWhen → 恒可见；有则同层兄弟键深等于 equals。 */
const visible = computed(() => {
  const vw = props.field.visibleWhen;
  return !vw || deepEquals(props.layer?.[vw.key], vw.equals);
});

// ---- 叶子 v-model（值即 layer[field.key]）----
const leafModel = computed({
  get: () => props.layer[props.field.key],
  set: (v: unknown) => {
    props.layer[props.field.key] = v;
  },
});

// ---- object：确保值对象存在（缺省建 children 默认）并返回，幂等 ----
function ensureObjectLayer(): Record<string, unknown> {
  const v = props.layer[props.field.key];
  if (!v || typeof v !== 'object' || Array.isArray(v)) {
    const fresh = objectDefault(props.field);
    props.layer[props.field.key] = fresh;
    return fresh;
  }
  return v as Record<string, unknown>;
}

// ---- array ----
function arr(): unknown[] {
  const v = props.layer[props.field.key];
  if (!Array.isArray(v)) {
    const fresh: unknown[] = [];
    props.layer[props.field.key] = fresh;
    return fresh;
  }
  return v;
}
function setRow(i: number, v: unknown): void {
  arr()[i] = v;
}
function addRow(): void {
  const item = items.value;
  arr().push(item ? defaultValue(item) : '');
}
function removeRow(i: number): void {
  arr().splice(i, 1);
}
function rowObject(row: unknown): Record<string, unknown> {
  return row && typeof row === 'object' && !Array.isArray(row) ? (row as Record<string, unknown>) : {};
}
</script>

<template>
  <template v-if="visible">
    <!-- object：折叠分组内递归 children -->
    <el-collapse v-if="isObject" class="sf-field sf-object" :model-value="[field.key]">
      <el-collapse-item :name="field.key">
        <template #title>
          <span class="sf-group-title">{{ field.label ?? field.key }}</span>
          <span v-if="field.description" class="sf-desc">{{ field.description }}</span>
        </template>
        <div class="sf-group-body">
          <SchemaField
            v-for="child in field.children ?? []"
            :key="child.key"
            :field="child"
            :layer="ensureObjectLayer()"
          />
        </div>
      </el-collapse-item>
    </el-collapse>

    <!-- array：行式增删 -->
    <div v-else-if="isArray" class="sf-field sf-array">
      <div class="sf-array-head">
        <span class="sf-group-title">{{ field.label ?? field.key }}</span>
        <span v-if="field.description" class="sf-desc">{{ field.description }}</span>
        <el-button size="small" text type="primary" class="sf-array-add" @click="addRow">＋ 添加</el-button>
      </div>
      <div v-for="(row, i) in arr()" :key="i" class="sf-array-row">
        <template v-if="itemsIsObject && items">
          <div class="sf-array-card">
            <SchemaField
              v-for="c in items.children ?? []"
              :key="c.key"
              :field="c"
              :layer="rowObject(row)"
            />
          </div>
        </template>
        <template v-else-if="items">
          <div class="sf-array-scalar">
            <el-switch
              v-if="items.type === 'boolean'"
              :model-value="row"
              @update:model-value="setRow(i, $event)"
            />
            <el-input-number
              v-else-if="items.type === 'number' || items.type === 'integer'"
              :model-value="row"
              @update:model-value="setRow(i, $event)"
              :min="items.min ?? undefined"
              :max="items.max ?? undefined"
              :step="items.step ?? (items.type === 'integer' ? 1 : 0.1)"
              :precision="items.type === 'integer' ? 0 : undefined"
              size="small"
              controls-position="right"
            />
            <el-select
              v-else-if="items.type === 'enum' && items.options && items.options.length > 0"
              :model-value="row"
              @update:model-value="setRow(i, $event)"
              size="small"
              style="width: 100%"
            >
              <el-option v-for="o in items.options" :key="o" :value="o" :label="o" />
            </el-select>
            <el-input
              v-else
              :model-value="row"
              @update:model-value="setRow(i, $event)"
              size="small"
            />
          </div>
        </template>
        <el-button size="small" text type="danger" class="sf-row-del" title="删除此行" @click="removeRow(i)">
          ✕
        </el-button>
      </div>
      <div v-if="arr().length === 0" class="sf-array-empty">（空列表 — 点“添加”新增一项）</div>
    </div>

    <!-- 叶子：行式 meta + 控件 -->
    <div v-else class="sf-field sf-row">
      <div class="sf-meta">
        <label class="sf-label" :title="field.description ?? ''">{{ field.label ?? field.key }}</label>
        <span v-if="field.description" class="sf-desc">{{ field.description }}</span>
      </div>
      <div class="sf-control">
        <el-switch v-if="field.type === 'boolean'" v-model="leafModel" />
        <el-input-number
          v-else-if="field.type === 'number' || field.type === 'integer'"
          v-model="leafModel"
          :min="field.min ?? undefined"
          :max="field.max ?? undefined"
          :step="field.step ?? (field.type === 'integer' ? 1 : 0.1)"
          :precision="field.type === 'integer' ? 0 : undefined"
          size="small"
          controls-position="right"
        />
        <el-select
          v-else-if="field.type === 'enum' && field.options && field.options.length > 0"
          v-model="leafModel"
          size="small"
          style="width: 100%"
        >
          <el-option v-for="o in field.options" :key="o" :value="o" :label="o" />
        </el-select>
        <el-input v-else v-model="leafModel" size="small" />
      </div>
    </div>
  </template>
</template>

<style scoped>
.sf-field { width: 100%; }
.sf-row { display: flex; align-items: flex-start; gap: 16px; }
.sf-meta { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.sf-label { font-size: 13px; font-weight: 600; color: var(--dsh-fg-0); }
.sf-desc { font-size: 11.5px; color: var(--dsh-fg-2); line-height: 1.5; }
.sf-control { width: 220px; flex-shrink: 0; }

/* object 分组 */
.sf-object { border: 1px solid var(--dsh-border); border-radius: 8px; }
.sf-group-title { font-size: 13px; font-weight: 600; color: var(--dsh-fg-0); }
.sf-group-body { display: flex; flex-direction: column; gap: 12px; padding: 4px 2px; }

/* array */
.sf-array { display: flex; flex-direction: column; gap: 6px; }
.sf-array-head { display: flex; align-items: baseline; gap: 10px; }
.sf-array-add { margin-left: auto; }
.sf-array-row { display: flex; align-items: flex-start; gap: 8px; }
.sf-array-card {
  flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 10px;
  border: 1px dashed var(--dsh-border); border-radius: 8px; padding: 10px 12px;
  background: var(--dsh-bg-0, transparent);
}
.sf-array-scalar { flex: 1; min-width: 0; }
.sf-array-empty { color: var(--dsh-fg-2); font-size: 12px; padding: 4px 2px; }
.sf-row-del { flex-shrink: 0; }
</style>
