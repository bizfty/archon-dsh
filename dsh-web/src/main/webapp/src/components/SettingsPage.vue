<script setup lang="ts">
// SettingsPage.vue — 设置页（schema 驱动动态表单，P3 redacted view）。
// 数据源：GET /api/settings/meta → 每命名空间 = SettingsNamespaceView（描述符树 + redacted
// value/user + revision + secrets + applies）；渲染 SchemaForm（递归 + secret write-only +
// presence 徽标）；保存经 POST ops（expectedRevision CAS，409 冲突 → 提示并重载最新设置）。
import { onMounted, ref } from 'vue';
import { fetchSettingsMeta, type SettingsNamespaceView } from '../api';
import SchemaForm from './SchemaForm.vue';
import { appState, pushNotice } from '../store';

function goBack(): void { appState.view = 'chat'; }

const namespaces = ref<SettingsNamespaceView[]>([]);
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

/** SchemaForm 保存成功：重载最新 view（revision 推进，view 替换 → SchemaForm 重同步草稿）。 */
function onSaved(namespace: string): void {
  pushNotice(`已保存 ${namespace}`);
  void load();
}

/** CAS 冲突：提示并重载（丢弃过期草稿，避免静默覆盖并发修改）。 */
function onConflict(namespace: string): void {
  pushNotice(`${namespace} 设置已被其他会话修改，已重载最新值`);
  void load();
}

onMounted(() => void load());
</script>

<template>
  <main class="settings-page">
    <header class="settings-page-header">
      <div class="settings-page-title">
        <span class="settings-page-icon">🔧</span>
        <b>设置</b>
        <span class="settings-page-desc">schema 驱动动态表单（redacted view + revision CAS + secret write-only）</span>
      </div>
      <el-button size="small" text class="settings-back" @click="goBack()" title="返回对话">← 返回对话</el-button>
    </header>

    <div class="settings-page-body">
      <el-alert v-if="error" type="error" :title="`设置加载失败：${error}`" :closable="false" />
      <div v-loading="loading" class="settings-grid">
        <section v-for="ns in namespaces" :key="ns.namespace" class="settings-card">
          <div class="settings-card-head">
            <b class="settings-card-ns">{{ ns.namespace }}</b>
            <el-button size="small" text @click="load" title="重新加载">↻ 刷新</el-button>
          </div>
          <SchemaForm :view="ns" @saved="onSaved" @conflict="onConflict" />
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
