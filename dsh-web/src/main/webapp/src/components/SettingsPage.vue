<script setup lang="ts">
// SettingsPage.vue — 设置页（schema 驱动动态表单）。
// 数据源：GET /api/settings/meta → 每命名空间 = SettingDescriptor[] + 当前值合并视图；
// 渲染 SchemaForm（按 type 生成控件），保存走既有 PUT /api/settings/{namespace}/{key}。
// 仅后端已注册描述符的命名空间会出现；无任何描述符 → 空态提示。
import { onMounted, ref } from 'vue';
import { fetchSettingsMeta, type SettingsNamespace } from '../api';
import SchemaForm from './SchemaForm.vue';

const emit = defineEmits<{ (e: 'back'): void }>();

const namespaces = ref<SettingsNamespace[]>([]);
const loading = ref(false);
const error = ref<string | null>(null);
const loadedOnce = ref(false);

async function load(): Promise<void> {
  loading.value = true;
  error.value = null;
  try {
    namespaces.value = await fetchSettingsMeta();
    loadedOnce.value = true;
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    loading.value = false;
  }
}

/** SchemaForm 保存成功后同步当前值（其余 namespace/描述符不变）。 */
function onChanged(namespace: string, key: string, value: unknown): void {
  const ns = namespaces.value.find((n) => n.namespace === namespace);
  if (ns) ns.values[key] = value;
}

onMounted(() => void load());
</script>

<template>
  <main class="settings-page">
    <header class="settings-page-header">
      <div class="settings-page-title">
        <span class="settings-page-icon">🔧</span>
        <b>设置</b>
        <span class="settings-page-desc">schema 驱动动态表单（服务端设置描述符生成，新设置项无需改前端）</span>
      </div>
      <el-button size="small" text class="settings-back" @click="emit('back')" title="返回对话">← 返回对话</el-button>
    </header>

    <div class="settings-page-body">
      <el-alert v-if="error" type="error" :title="`设置加载失败：${error}`" :closable="false" />
      <div v-loading="loading" class="settings-grid">
        <section v-for="ns in namespaces" :key="ns.namespace" class="settings-card">
          <div class="settings-card-head">
            <b class="settings-card-ns">{{ ns.namespace }}</b>
            <el-button size="small" text @click="load" title="重新加载">↻ 刷新</el-button>
          </div>
          <SchemaForm
            :namespace="ns.namespace"
            :settings="ns.settings"
            :values="ns.values"
            @changed="onChanged"
          />
        </section>
        <div v-if="loadedOnce && !loading && namespaces.length === 0 && !error" class="settings-empty">
          暂无 schema 化设置项 — 后端注册设置描述符（SettingDescriptor）后，此处将自动出现对应表单。
        </div>
      </div>
    </div>
  </main>
</template>

<style scoped>
.settings-page { display: flex; flex-direction: column; height: 100%; overflow: hidden; }
.settings-page-header {
  display: flex; align-items: center; justify-content: space-between;
  padding: 14px 20px; border-bottom: 1px solid var(--dsh-border);
}
.settings-page-title { display: flex; align-items: baseline; gap: 10px; }
.settings-page-icon { font-size: 16px; }
.settings-page-title b { font-size: 15px; color: var(--dsh-fg-0); }
.settings-page-desc { font-size: 12px; color: var(--dsh-fg-2); }
.settings-page-body { flex: 1; overflow: auto; padding: 20px; }
.settings-grid { display: flex; flex-direction: column; gap: 16px; max-width: 720px; }
.settings-card {
  border: 1px solid var(--dsh-border); border-radius: 10px; padding: 16px 18px;
  background: var(--dsh-bg-1, var(--dsh-bg-0));
}
.settings-card-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 14px; }
.settings-card-ns { font-size: 13px; letter-spacing: .02em; color: var(--dsh-accent); }
.settings-empty { color: var(--dsh-fg-2); font-size: 13px; padding: 32px 12px; text-align: center; }
</style>
