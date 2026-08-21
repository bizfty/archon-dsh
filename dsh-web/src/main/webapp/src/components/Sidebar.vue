<script setup lang="ts">
// 侧边栏：新建 / 搜索 / 会话列表（Element Plus）。
import { computed, ref } from 'vue';
import { appState } from '../store';

const emit = defineEmits<{
  (e: 'new-session'): void;
  (e: 'select-session', id: string): void;
}>();

const kw = ref('');

const filtered = computed(() => {
  const key = kw.value.trim().toLowerCase();
  return [...appState.sessions]
    .filter((s) => !key || (s.title || '').toLowerCase().includes(key) || s.id.includes(key))
    .sort((a, b) => (a.updatedAt < b.updatedAt ? 1 : -1));
});

function fmtTime(iso: string): string {
  return new Date(iso).toLocaleString('zh-CN', {
    month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
  });
}
</script>

<template>
  <div class="sidebar">
    <el-button type="primary" class="new-btn" @click="emit('new-session')">＋ 新建会话</el-button>
    <el-input v-model="kw" placeholder="搜索会话..." clearable class="search">
      <template #prefix>🔍</template>
    </el-input>
    <div class="section">
      <span>最近会话</span>
      <el-tag size="small" type="info" round>{{ appState.sessions.length }}</el-tag>
    </div>
    <div class="list" v-loading="appState.sessionsPhase === 'pending'">
      <div
        v-for="s in filtered"
        :key="s.id"
        class="item"
        :class="{ active: s.id === appState.sessionId }"
        @click="emit('select-session', s.id)"
      >
        <div class="t">{{ s.title || s.id.slice(0, 16) }}</div>
        <div class="m">{{ fmtTime(s.updatedAt) }}</div>
      </div>
      <el-empty v-if="filtered.length === 0 && appState.sessionsPhase === 'ready'"
        description="还没有会话" :image-size="60" />
      <el-alert v-if="appState.sessionsError" type="error" :title="appState.sessionsError" :closable="false" />
    </div>
    <div class="bottom">
      <div class="user">
        <span class="avatar">🧭</span>
        <div>
          <div class="name">Archon DSH</div>
          <div class="status">● 在线</div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.sidebar { display: flex; flex-direction: column; height: 100%; padding: 12px; }
.new-btn { width: 100%; margin-bottom: 12px; }
.search { margin-bottom: 8px; }
.section { display: flex; justify-content: space-between; align-items: center; font-size: 12px; color: #9a9ba6; text-transform: uppercase; letter-spacing: .05em; margin: 8px 2px; }
.list { flex: 1; overflow-y: auto; display: flex; flex-direction: column; gap: 2px; }
.item { padding: 8px 10px; border: 1px solid transparent; border-radius: 8px; cursor: pointer; }
.item:hover { background: #2c2d35; }
.item.active { background: #2c2d35; border-color: #4f7cff; }
.t { font-size: 13px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.m { font-size: 11px; color: #9a9ba6; margin-top: 2px; }
.bottom { padding-top: 12px; border-top: 1px solid #33343d; }
.user { display: flex; align-items: center; gap: 10px; padding: 8px; }
.avatar { width: 34px; height: 34px; border-radius: 50%; background: linear-gradient(135deg, #4f7cff, #8a5cf6); display: grid; place-items: center; font-size: 18px; }
.name { font-size: 13px; font-weight: 600; }
.status { font-size: 12px; color: #2ecc71; }
</style>
