<script setup lang="ts">
import { computed, ref } from 'vue';
import { appState, toggleSidebar } from '../store';

const emit = defineEmits<{
  (e: 'new-session'): void;
  (e: 'select-session', id: string): void;
  (e: 'select-tool', id: string): void;
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

/** 场景/工具菜单（对齐 javaai 侧边栏「工具」分组）。 */
const TOOLS = [
  { id: 'chat', icon: '💬', name: '对话' },
  { id: 'coder', icon: '💻', name: '代码编辑器' },
  // 预留：doc / data / research 等场景
];
</script>

<template>
  <div class="sidebar" :class="{ collapsed: appState.sidebarCollapsed }">
    <div class="top-area">
      <button class="collapse-btn" @click="toggleSidebar()" :title="appState.sidebarCollapsed ? '展开侧边栏' : '折叠侧边栏'">
        <span v-if="!appState.sidebarCollapsed">◀</span>
        <span v-else>▶</span>
      </button>
      <el-button v-if="!appState.sidebarCollapsed" type="primary" class="new-btn" @click="emit('new-session')">
        ＋ 新建会话
      </el-button>
      <el-button v-else type="primary" class="new-btn-icon" circle @click="emit('new-session')" title="新建会话">＋</el-button>
    </div>

    <el-input
      v-if="!appState.sidebarCollapsed"
      v-model="kw"
      placeholder="搜索会话..."
      clearable
      class="search"
    >
      <template #prefix>🔍</template>
    </el-input>

    <div class="section">
      <span v-if="!appState.sidebarCollapsed">最近会话</span>
      <el-tag size="small" type="info" round>{{ appState.sessions.length }}</el-tag>
    </div>

    <div class="list" v-loading="appState.sessionsPhase === 'pending'">
      <div
        v-for="s in filtered"
        :key="s.id"
        class="item"
        :class="{ active: s.id === appState.sessionId }"
        @click="emit('select-session', s.id)"
        :title="appState.sidebarCollapsed ? (s.title || s.id.slice(0, 16)) : ''"
      >
        <div v-if="!appState.sidebarCollapsed" class="t">{{ s.title || s.id.slice(0, 16) }}</div>
        <div v-if="!appState.sidebarCollapsed" class="m">{{ fmtTime(s.updatedAt) }}</div>
        <div v-else class="t-collapsed">{{ (s.title || s.id.slice(0, 2)).charAt(0) }}</div>
      </div>
      <el-empty v-if="filtered.length === 0 && appState.sessionsPhase === 'ready'"
        :image-size="60" description="还没有会话" />
      <el-alert v-if="appState.sessionsError" type="error" :title="appState.sessionsError" :closable="false" />
    </div>

    <!-- 工具菜单（会话列表下方，对齐 javaai 侧边栏「工具」分组） -->
    <div class="section tools-section">
      <span v-if="!appState.sidebarCollapsed">工具</span>
    </div>
    <nav class="tools">
      <div
        v-for="t in TOOLS"
        :key="t.id"
        class="tool-item"
        :class="{ active: appState.view === t.id }"
        @click="emit('select-tool', t.id)"
        :title="appState.sidebarCollapsed ? t.name : ''"
      >
        <span class="tool-icon">{{ t.icon }}</span>
        <span v-if="!appState.sidebarCollapsed" class="tool-name">{{ t.name }}</span>
      </div>
    </nav>

    <div class="bottom">
      <div class="user" :title="appState.sidebarCollapsed ? 'Archon DSH · 在线' : ''">
        <span class="avatar">🧭</span>
        <div v-if="!appState.sidebarCollapsed">
          <div class="name">Archon DSH</div>
          <div class="status">● 在线</div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.sidebar { display: flex; flex-direction: column; height: 100%; padding: 12px; transition: width .2s ease; overflow: hidden; }
.sidebar.collapsed { padding: 8px 6px; }

.top-area { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; }
.collapse-btn {
  background: none; border: 1px solid var(--dsh-border); border-radius: 6px;
  color: var(--dsh-fg-2); cursor: pointer; padding: 4px 8px; font-size: 12px;
  display: flex; align-items: center; justify-content: center;
  transition: all .15s; flex-shrink: 0;
}
.collapse-btn:hover { color: var(--dsh-accent); border-color: var(--dsh-accent); }

.new-btn { flex: 1; min-width: 0; }
.sidebar.collapsed .new-btn { display: none; }
.new-btn-icon { width: 36px; height: 36px; padding: 0; flex-shrink: 0; }
.sidebar:not(.collapsed) .new-btn-icon { display: none; }

.search { margin-bottom: 8px; }
.sidebar.collapsed .search { display: none; }

.section { display: flex; justify-content: space-between; align-items: center; font-size: 12px; color: var(--dsh-fg-2); text-transform: uppercase; letter-spacing: .05em; margin: 8px 2px; }
.sidebar.collapsed .section span { display: none; }

.list { flex: 1; overflow-y: auto; display: flex; flex-direction: column; gap: 2px; min-height: 60px; }
.item { padding: 8px 10px; border: 1px solid transparent; border-radius: 8px; cursor: pointer; color: var(--dsh-fg-0); transition: background-color .15s; }
.item:hover { background: var(--dsh-bg-3); }
.item.active { background: var(--dsh-bg-3); border-color: var(--dsh-accent); }

.t { font-size: 13px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.m { font-size: 11px; color: var(--dsh-fg-2); margin-top: 2px; }

.sidebar.collapsed .item { padding: 6px 4px; text-align: center; }
.t-collapsed {
  width: 28px; height: 28px; border-radius: 50%;
  background: var(--dsh-bg-3); color: var(--dsh-fg-1);
  display: grid; place-items: center; font-size: 14px; font-weight: 600; margin: 0 auto;
}
.sidebar.collapsed .item.active .t-collapsed { background: var(--dsh-accent); color: var(--dsh-accent-contrast); }

/* 工具菜单（会话列表下方） */
.tools-section { margin-top: 4px; }
.tools { display: flex; flex-direction: column; gap: 2px; margin-bottom: 8px; }
.tool-item {
  display: flex; align-items: center; gap: 10px;
  padding: 7px 10px; border-radius: 8px; cursor: pointer;
  color: var(--dsh-fg-1); font-size: 13px; transition: background-color .15s;
  user-select: none; white-space: nowrap; overflow: hidden;
}
.tool-item:hover { background: var(--dsh-bg-3); color: var(--dsh-fg-0); }
.tool-item.active { background: var(--dsh-bg-3); color: var(--dsh-accent); font-weight: 600; }
.tool-icon { font-size: 15px; width: 20px; text-align: center; flex-shrink: 0; }
.tool-name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.sidebar.collapsed .tool-item { justify-content: center; padding: 7px 4px; }

.bottom { padding-top: 12px; border-top: 1px solid var(--dsh-border); }
.sidebar.collapsed .bottom { padding-top: 8px; }

.user { display: flex; align-items: center; gap: 10px; padding: 8px; }
.sidebar.collapsed .user { justify-content: center; padding: 6px; }

.avatar { width: 34px; height: 34px; border-radius: 50%; background: linear-gradient(135deg, var(--dsh-accent), #8a5cf6); display: grid; place-items: center; font-size: 18px; color: #fff; flex-shrink: 0; }
.name { font-size: 13px; font-weight: 600; color: var(--dsh-fg-0); }
.status { font-size: 12px; color: var(--dsh-success); }

:deep(.el-empty__description) { font-size: 12px; }
</style>
