<script setup lang="ts">
// SchemaField.vue — 递归 schema 字段编辑器（P2 递归渲染 + P3 官方语义前端）。
// 叶子按 type 渲染 EP 控件；object→折叠分组内递归 children；array→行式增删；
// visibleWhen→同层兄弟键值深等才显示（隐藏 ≠ 删除）；
// secret 叶子→write-only（不读值：输入/覆盖/清除经 emit 上抛，由 SchemaForm 以 path op 提交）。
// path = 到本字段的完整路径（自命名空间值根，含自身 key），供 secret sidecar 定位。
import { computed, ref } from 'vue';
import type { RenderField } from '../schemaFieldModel';
import { defaultValue, deepEquals, objectDefault } from '../schemaDefaults';

const props = defineProps<{
  field: RenderField;
  /** 当前对象层：field.key 在此读写。 */
  layer: Record<string, unknown>;
  /** 到本字段的完整路径（含 field.key）。 */
  path: string[];
  /** 查 view.secrets：该路径当前是否持值（已设置）。 */
  secretSet: (path: string[]) => boolean;
}>();

const emit = defineEmits<{
  (e: 'secret', path: string[], value: unknown | null): void;
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

/** 普通叶子 v-model（值即 layer[field.key]）。 */
const leafModel = computed({
  get: () => props.layer[props.field.key],
  set: (v: unknown) => {
    props.layer[props.field.key] = v;
  },
});

// ---- secret 叶子（write-only）----
const secretInput = ref('');
const secretNow = computed(() => props.secretSet(props.path));

function saveSecret(): void {
  if (!secretInput.value) return;
  emit('secret', props.path, secretInput.value);
  secretInput.value = '';
}
function clearSecret(): void {
  emit('secret', props.path, null);
}

/** 子递归层事件转发（多参事件经 handler 收敛）。 */
function onChildSecret(childPath: string[], value: unknown | null): void {
  emit('secret', childPath, value);
}

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
function childPath(child: RenderField, index?: number): string[] {
  return index === undefined ? [...props.path, child.key] : [...props.path, String(index), child.key];
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
            :path="childPath(child)"
            :secret-set="secretSet"
            @secret="onChildSecret"
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
              :path="childPath(c, i)"
              :secret-set="secretSet"
              @secret="onChildSecret"
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


    <!-- 高级/未知 schemastery 类型：只读降级（原值保留，不参与编辑） -->
    <div v-else-if="field.type === 'unknown'" class="sf-field sf-row">
      <div class="sf-meta">
        <label class="sf-label" :title="field.description ?? ''">{{ field.label ?? field.key }}</label>
        <span v-if="field.description" class="sf-desc">{{ field.description }}</span>
        <span class="sf-unknown-tag">只读 · 该字段类型暂不支持编辑</span>
      </div>
      <div class="sf-control sf-unknown-value">
        <code v-if="layer[field.key] !== undefined">{{ JSON.stringify(layer[field.key]) }}</code>
        <span v-else class="sf-fg-muted">（未设置）</span>
      </div>
    </div>

    <!-- secret 叶子：write-only（值不读回） -->
    <div v-else-if="field.secret" class="sf-field sf-row">
      <div class="sf-meta">
        <label class="sf-label" :title="field.description ?? ''">{{ field.label ?? field.key }}</label>
        <span v-if="field.description" class="sf-desc">{{ field.description }}</span>
        <span class="sf-secret-tag" :class="secretNow ? 'sf-secret-set' : 'sf-secret-empty'">
          {{ secretNow ? '已设置（值不回显）' : '未设置' }}
        </span>
      </div>
      <div class="sf-secret-ctl">
        <el-input
          v-model="secretInput"
          type="password"
          show-password
          size="small"
          :placeholder="secretNow ? '输入新值可覆盖' : '输入要保存的值'"
        />
        <div class="sf-secret-actions">
          <el-button size="small" type="primary" text :disabled="!secretInput" @click="saveSecret">
            保存
          </el-button>
          <el-button
            v-if="secretNow"
            size="small"
            text
            type="danger"
            title="移除该值（回默认/未设置）"
            @click="clearSecret"
          >
            清除
          </el-button>
        </div>
      </div>
    </div>

    <!-- 普通叶子：行式 meta + 控件 -->
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

/* secret write-only */
.sf-secret-tag { font-size: 11px; line-height: 1.4; }
.sf-secret-set { color: var(--dsh-warn, #e6a23c); }
.sf-secret-empty { color: var(--dsh-fg-3, #999); }
.sf-secret-ctl { width: 300px; flex-shrink: 0; display: flex; flex-direction: column; gap: 4px; }
.sf-secret-actions { display: flex; gap: 6px; justify-content: flex-end; }

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
.sf-unknown-tag { font-size: 11px; color: var(--dsh-fg-3, #999); }
.sf-unknown-value { display: flex; align-items: center; }
.sf-unknown-value code {
  font-size: 12px; color: var(--dsh-fg-1); background: var(--dsh-bg-1, #f5f5f5);
  border-radius: 4px; padding: 2px 6px; word-break: break-all; max-height: 80px; overflow: auto;
}
.sf-fg-muted { color: var(--dsh-fg-3, #999); font-size: 12px; }
</style>
