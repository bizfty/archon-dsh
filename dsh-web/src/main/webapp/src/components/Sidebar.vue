<script setup lang="ts">
// 侧边栏：会话浏览区域（对齐 deepseek-harness 的 ui-workspace / ui-sidebar）。
// 展开态 section header 一行 3 个图标按钮：🔍搜索（图标胶囊，点击展开输入框）、
// 🎛视图选项（分组/排序菜单）、＋添加工作区；搜索展开时隐藏 label 与 actions。
// 顶部无 top-area（折叠按钮移至底部 user 行；展开态新建会话走工作区 ⋯ 菜单）。
// 折叠态（rail 56px）：section 为 💬新建会话 + 🔍搜索 + ＋添加工作区 三个 36px 图标
// （对齐 deepseek rail 的 new chat + search + add workspace 控件）。
import { computed, nextTick, ref, watch } from 'vue';
import { appState, toggleSidebar, workspaceLabel, sessionsOfWorkspace, loadWorkspaces } from '../store';
import { deleteWorkspace, renameWorkspace } from '../api';
import { ElMessageBox } from 'element-plus';

const emit = defineEmits<{
  (e: 'new-session'): void;
  (e: 'new-session-in', workspaceId: string): void;
  (e: 'select-session', id: string): void;
  (e: 'select-tool', id: string): void;
  (e: 'add-workspace'): void;
}>();

const kw = ref('');
/** 展开态搜索胶囊是否展开（对齐 deepseek：图标点击展开输入框，query 常驻）。 */
const searchOpen = ref(false);
/** 折叠态点搜索：展开侧边栏并聚焦搜索框（对齐 deepseek rail search）。 */
const searchOnExpand = ref(false);
const searchInput = ref<{ focus: () => void } | null>(null);
/** 折叠状态：workspaceId → 是否折叠（未分组固定展开）。 */
const collapsed = ref<Record<string, boolean>>({});
/** 视图选项（对齐 deepseek ViewOptionsMenu）：分组 + 排序。 */
const viewOpts = ref<{ groupBy: 'workspace' | 'flat'; orderBy: 'manual' | 'updated' }>({
  groupBy: 'workspace',
  orderBy: 'manual',
});

function toggleGroup(key: string): void {
  collapsed.value = { ...collapsed.value, [key]: !collapsed.value[key] };
}

/** 打开搜索胶囊：展开输入框并聚焦。 */
function openSearch(): void {
  searchOpen.value = true;
  nextTick(() => searchInput.value?.focus());
}

/** 关闭搜索胶囊（query 常驻不丢；deepseek 同款：仅空 query 时外部点击收起）。 */
function closeSearch(): void {
  searchOpen.value = false;
  document.removeEventListener('click', onDocClick);
}

function clearSearch(): void {
  kw.value = '';
  closeSearch();
}

function onDocClick(event: MouseEvent): void {
  const t = event.target as Node | null;
  if (t && (t as HTMLElement).closest?.('.search-slot')) return;
  if (kw.value.trim() !== '') return;
  closeSearch();
}

watch(searchOpen, (open) => {
  if (open) document.addEventListener('click', onDocClick);
  else document.removeEventListener('click', onDocClick);
});

/** 折叠态搜索入口：展开侧边栏，展开动画结束后聚焦搜索框（query 常驻不丢）。 */
function onRailSearch(): void {
  searchOnExpand.value = true;
  searchOpen.value = true;
  if (appState.sidebarCollapsed) toggleSidebar();
}

// 展开完成后若由 rail 搜索触发则聚焦输入框（等待 width 过渡 200ms 结束）
watch(() => appState.sidebarCollapsed, (collapsedNow) => {
  if (collapsedNow || !searchOnExpand.value) return;
  window.setTimeout(() => {
    searchInput.value?.focus();
    searchOnExpand.value = false;
  }, 220);
});

/** cwd → 工作区显示名（标题或目录 basename），用于搜索匹配 workspace 名。 */
const workspaceLabelByPath = computed(() => {
  const m = new Map<string, string>();
  for (const w of appState.workspaces) m.set(w.path, workspaceLabel(w));
  return m;
});

/** 搜索匹配：会话标题 / id / 所属工作区名（对齐官方「titles and workspace names」）。 */
function matches(s: { title: string | null; id: string; cwd?: string | null }): boolean {
  const key = kw.value.trim().toLowerCase();
  if (!key) return true;
  if ((s.title || '').toLowerCase().includes(key) || s.id.includes(key)) return true;
  if (s.cwd) {
    const label = workspaceLabelByPath.value.get(s.cwd);
    if (label && label.toLowerCase().includes(key)) return true;
  }
  return false;
}

/** 会话所属工作区显示名（flat 模式副标题用）。 */
function wsLabelOf(s: { cwd?: string | null }): string {
  if (!s.cwd) return '';
  return workspaceLabelByPath.value.get(s.cwd) || '';
}

/**
 * 分组：按工作区（默认）或平铺（flat）展示，支持按更新时间排序。
 * 未分组 = 不属于任何工作区的旧会话（仅 workspace 模式）。
 */
const groups = computed(() => {
  let sessions = appState.sessions.filter(matches);
  if (viewOpts.value.orderBy === 'updated') {
    sessions = [...sessions].sort(
      (a, b) => new Date(b.updatedAt).getTime() - new Date(a.updatedAt).getTime(),
    );
  }
  if (viewOpts.value.groupBy === 'flat') {
    return [{ key: '__flat__', label: '全部会话', path: '', sessions, collapsed: false }];
  }
  const ws = appState.workspaces.map((w) => ({
    key: w.id,
    label: workspaceLabel(w),
    path: w.path,
    sessions: sessionsOfWorkspace(w).filter(matches),
  }));
  const known = new Set(appState.workspaces.map((w) => w.path));
  const ungrouped = appState.sessions.filter((s) => !s.cwd || !known.has(s.cwd)).filter(matches);
  const list = ws.map((g) => ({ ...g, collapsed: !!collapsed.value[g.key] }));
  if (ungrouped.length > 0) {
    list.push({ key: '__ungrouped__', label: '未分组', path: '', sessions: ungrouped, collapsed: false });
  }
  return list;
});

const totalSessions = computed(() => appState.sessions.length);

function fmtTime(iso: string): string {
  return new Date(iso).toLocaleString('zh-CN', {
    month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
  });
}

/** 会话项 tooltip：执行中显示状态提示（切换不中断）；折叠态显示标题。 */
function itemTitle(s: { id: string; title: string | null }): string {
  if (appState.runningBySession[s.id]) return '执行中…（切换不中断）';
  return appState.sidebarCollapsed ? (s.title || s.id.slice(0, 16)) : '';
}

/** 重命名工作区。 */
async function doRename(w: { key: string; label: string }): Promise<void> {
  try {
    const { value } = await ElMessageBox.prompt(`重命名工作区「${w.label}」`, '重命名', {
      inputValue: w.label,
      confirmButtonText: '保存',
      cancelButtonText: '取消',
    });
    if (value && value.trim() && value.trim() !== w.label) {
      await renameWorkspace(w.key, value.trim());
      await loadWorkspaces();
    }
  } catch { /* 取消 */ }
}

/** 删除工作区（仅移除分组记录，其下会话保留）。 */
async function doDelete(w: { key: string; label: string; path: string }): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `删除工作区「${w.label}」？\n仅移除分组记录，其下会话保留（cwd 不变）。`,
      '删除工作区',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' },
    );
  } catch {
    return; // 取消
  }
  try {
    await deleteWorkspace(w.key);
    await loadWorkspaces();
  } catch (e) {
    ElMessageBox.alert((e as Error).message, '删除失败', { confirmButtonText: '知道了' });
  }
}

/** 工作区头部「⋮」菜单命令：在此创建会话 / 重命名 / 删除。 */
function onWsCommand(cmd: string, w: { key: string; label: string; path: string }): void {
  if (cmd === 'new-session') {
    emit('new-session-in', w.key);
  } else if (cmd === 'rename') {
    void doRename(w);
  } else if (cmd === 'delete') {
    void doDelete(w);
  }
}

/** 视图选项菜单命令（对齐 deepseek ViewOptionsMenu 的分组/排序）。 */
function onViewCommand(cmd: string): void {
  if (cmd === 'group-workspace') viewOpts.value.groupBy = 'workspace';
  else if (cmd === 'group-flat') viewOpts.value.groupBy = 'flat';
  else if (cmd === 'order-manual') viewOpts.value.orderBy = 'manual';
  else if (cmd === 'order-updated') viewOpts.value.orderBy = 'updated';
}

/** 场景/工具菜单（对齐 javaai 侧边栏「工具」分组，与会话列表 list 同层）。
 *  不包含「对话」：主 tab 已有 💬 对话，会话列表即聊天视图，重复入口无意义。 */
const TOOLS = [
  { id: 'mcp', icon: '🔗', name: 'MCP' },
  { id: 'skills', icon: '⚙️', name: '技能' },
  { id: 'jobs', icon: '⏰', name: '定时任务' },
  { id: 'expert', icon: '🧩', name: '专家套件' },
  { id: 'coder', icon: '💻', name: '代码开发' },
  { id: 'self', icon: '🧬', name: '自我完善' },
];
</script>

<template>
  <div class="sidebar" :class="{ collapsed: appState.sidebarCollapsed }">
    <!-- 会话浏览区域 header（对齐 deepseek WorkspaceBrowser.sectionHeader）：
         展开态 = label + 搜索胶囊 + 视图选项 + 添加工作区；搜索展开时 label/actions 隐藏 -->
    <div class="section header">
      <span v-if="!appState.sidebarCollapsed && !searchOpen" class="section-label">工作区 · 会话</span>

      <!-- 搜索胶囊：展开态 图标点击展开输入框（deepseek searchExpanded 同款） -->
      <div v-if="!appState.sidebarCollapsed" class="search-slot" :class="{ expanded: searchOpen }">
        <button class="icon-btn search-toggle" :title="'搜索会话'" @click="searchOpen ? closeSearch() : openSearch()">
          🔍
        </button>
        <input
          v-if="searchOpen"
          ref="searchInput"
          v-model="kw"
          class="search-input"
          placeholder="搜索会话..."
          @keydown.esc="closeSearch"
        />
        <button v-if="searchOpen && kw" class="icon-btn search-clear" title="清除" @click="clearSearch">✕</button>
      </div>

      <!-- 展开态右侧 actions：视图选项 + 添加工作区（对齐 deepseek headerActions） -->
      <div v-if="!appState.sidebarCollapsed" class="header-actions" :class="{ hidden: searchOpen }">
        <el-dropdown trigger="click" @command="onViewCommand">
          <button class="icon-btn view-toggle" title="视图选项">🎛</button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="group-workspace" :disabled="viewOpts.groupBy === 'workspace'">按工作区分组</el-dropdown-item>
              <el-dropdown-item command="group-flat" :disabled="viewOpts.groupBy === 'flat'">平铺列表</el-dropdown-item>
              <el-dropdown-item command="order-manual" :disabled="viewOpts.orderBy === 'manual'" divided>保持顺序</el-dropdown-item>
              <el-dropdown-item command="order-updated" :disabled="viewOpts.orderBy === 'updated'">按更新时间</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
        <button class="icon-btn add-ws" title="添加工作目录" @click="emit('add-workspace')">＋</button>
      </div>

      <!-- 折叠态 rail actions：🔍搜索 + ＋添加工作区（对齐 deepseek rail 的 search + add 36px 控件） -->
      <div v-else class="rail-actions">
        <button class="rail-icon" title="新建会话" @click="emit('new-session')">💬</button>
        <button class="rail-icon" title="搜索会话" @click="onRailSearch">🔍</button>
        <button class="rail-icon" title="添加工作目录" @click="emit('add-workspace')">＋</button>
      </div>
    </div>

    <div class="list" v-loading="appState.sessionsPhase === 'pending' || appState.workspacesPhase === 'pending'">
      <!-- 按工作区分组（或平铺） -->
      <div v-for="g in groups" :key="g.key" class="ws-group">
        <div v-if="viewOpts.groupBy !== 'flat' || g.key === '__flat__' && g.sessions.length > 0"
          class="ws-head"
          :title="appState.sidebarCollapsed ? (g.path || g.label) : (g.path || '')"
          @click="viewOpts.groupBy !== 'flat' && toggleGroup(g.key)"
        >
          <span v-if="viewOpts.groupBy !== 'flat'" class="ws-caret" :class="{ open: !g.collapsed }">▸</span>
          <span class="ws-icon">📁</span>
          <span class="ws-label">{{ g.label }}</span>
          <span class="ws-count">{{ g.sessions.length }}</span>
          <span v-if="g.key !== '__ungrouped__' && g.key !== '__flat__'" class="ws-actions" @click.stop>
            <el-dropdown trigger="click" @command="(cmd) => onWsCommand(cmd, g)">
              <el-button size="small" text circle title="工作区菜单">⋯</el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="new-session">💬 在此创建会话</el-dropdown-item>
                  <el-dropdown-item command="rename">✎ 重命名</el-dropdown-item>
                  <el-dropdown-item command="delete" divided>🗑 删除工作区</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </span>
        </div>
        <template v-if="!g.collapsed">
          <div
            v-for="s in g.sessions"
            :key="s.id"
            class="item"
            :class="{ active: s.id === appState.sessionId, 'is-running': appState.runningBySession[s.id] }"
            @click="emit('select-session', s.id)"
            :title="itemTitle(s)"
          >
            <span v-if="appState.runningBySession[s.id]" class="run-dot" />
            <div v-if="!appState.sidebarCollapsed" class="t">{{ s.title || s.id.slice(0, 16) }}</div>
            <div v-if="!appState.sidebarCollapsed" class="m">
              <span v-if="viewOpts.groupBy === 'flat' && wsLabelOf(s)" class="ws-tag">{{ wsLabelOf(s) }}</span>{{ fmtTime(s.updatedAt) }}
            </div>
            <div v-else class="t-collapsed">{{ (s.title || s.id.slice(0, 2)).charAt(0) }}</div>
          </div>
        </template>
      </div>

      <el-empty v-if="groups.length === 0 && appState.sessionsPhase === 'ready' && appState.workspacesPhase === 'ready'"
        :image-size="60" description="还没有会话" />
      <el-alert v-if="appState.sessionsError || appState.workspacesError" type="error"
        :title="appState.sessionsError || appState.workspacesError" :closable="false" />
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
      <button class="collapse-btn bottom-toggle" @click="toggleSidebar()" :title="appState.sidebarCollapsed ? '展开侧边栏' : '折叠侧边栏'">
        <span>{{ appState.sidebarCollapsed ? '▶' : '◀' }}</span>
      </button>
    </div>
  </div>
</template>

<style scoped>
.sidebar { display: flex; flex-direction: column; height: 100%; padding: 12px; transition: width .2s ease; overflow: hidden; }
.sidebar.collapsed { padding: 8px 6px; }

.collapse-btn {
  background: none; border: 1px solid var(--dsh-border); border-radius: 6px;
  color: var(--dsh-fg-2); cursor: pointer; padding: 4px 8px; font-size: 12px;
  display: flex; align-items: center; justify-content: center;
  transition: all .15s; flex-shrink: 0;
}
.collapse-btn:hover { color: var(--dsh-accent); border-color: var(--dsh-accent); }
.bottom-toggle { width: 30px; height: 30px; padding: 0; }

/* 会话浏览区域 header（对齐 deepseek sectionHeader 36px 高） */
.section.header { display: flex; align-items: center; gap: 6px; height: 36px; margin: 4px 0 8px; }
.section-label {
  flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  font-size: 12px; color: var(--dsh-fg-2); text-transform: uppercase; letter-spacing: .05em;
}

/* 搜索胶囊（对齐 deepseek search capsule） */
.search-slot { flex: 1; display: flex; align-items: center; gap: 4px; min-width: 0; }
.search-slot.expanded { flex: 1; }
.search-toggle { flex-shrink: 0; }
.search-input {
  flex: 1; min-width: 0; border: none; background: transparent; outline: none;
  color: var(--dsh-fg-0); font-size: 13px; font-family: inherit;
}
.search-input::placeholder { color: var(--dsh-fg-2); }
.search-clear { flex-shrink: 0; font-size: 11px; padding: 0 6px; }

/* 展开态右侧 actions */
.header-actions { display: flex; align-items: center; gap: 4px; flex-shrink: 0; }
.header-actions.hidden { display: none; }
.header-actions :deep(.el-button) { margin-left: 0; }

/* 通用 36px 图标按钮（对齐 rail 控件节奏） */
.icon-btn {
  background: none; border: 1px solid var(--dsh-border); border-radius: 6px;
  color: var(--dsh-fg-2); cursor: pointer;
  width: 30px; height: 30px; font-size: 14px;
  display: flex; align-items: center; justify-content: center;
  transition: all .15s;
}
.icon-btn:hover { color: var(--dsh-accent); border-color: var(--dsh-accent); }

/* 折叠态 rail actions：🔍搜索 + ＋添加（36x36，对齐 deepseek rail） */
.rail-actions { display: flex; align-items: center; justify-content: center; gap: 4px; flex: 1; }
.rail-icon {
  background: none; border: 1px solid var(--dsh-border); border-radius: 6px;
  color: var(--dsh-fg-2); cursor: pointer;
  width: 36px; height: 32px; font-size: 14px;
  display: flex; align-items: center; justify-content: center;
  transition: all .15s;
}
.rail-icon:hover { color: var(--dsh-accent); border-color: var(--dsh-accent); }

.list { flex: 1; overflow-y: auto; display: flex; flex-direction: column; gap: 2px; min-height: 60px; }

/* 工作区分组 */
.ws-group { margin-bottom: 4px; }
.ws-head {
  display: flex; align-items: center; gap: 6px;
  padding: 6px 8px; border-radius: 6px; cursor: pointer;
  color: var(--dsh-fg-1); font-size: 12px; font-weight: 600;
  user-select: none; white-space: nowrap;
}
.ws-head:hover { background: var(--dsh-bg-3); }
.ws-caret { display: inline-block; transition: transform .15s; font-size: 10px; color: var(--dsh-fg-2); }
.ws-caret.open { transform: rotate(90deg); }
.ws-icon { font-size: 13px; flex-shrink: 0; }
.ws-label { flex: 1; overflow: hidden; text-overflow: ellipsis; }
.ws-count { font-size: 11px; color: var(--dsh-fg-2); background: var(--dsh-bg-3); border-radius: 8px; padding: 0 6px; }
.ws-actions { display: none; align-items: center; gap: 2px; }
.ws-head:hover .ws-actions { display: flex; }
.ws-actions :deep(.el-button) { margin-left: 0; padding: 3px; }
/* 折叠态工作区头：只保留 📁 图标（对齐 rail 布局，隐藏文字防溢出） */
.sidebar.collapsed .ws-head { justify-content: center; padding: 6px 4px; }
.sidebar.collapsed .ws-caret,
.sidebar.collapsed .ws-label,
.sidebar.collapsed .ws-count,
.sidebar.collapsed .ws-actions { display: none; }

.item { padding: 8px 10px; border: 1px solid transparent; border-radius: 8px; cursor: pointer; color: var(--dsh-fg-0); transition: background-color .15s; }
.item:hover { background: var(--dsh-bg-3); }
.item.active { background: var(--dsh-bg-3); border-color: var(--dsh-accent); }
.item.is-running { position: relative; }
.item.is-running .t { padding-left: 10px; }
.run-dot {
  position: absolute; left: 8px; top: 12px;
  width: 7px; height: 7px; border-radius: 50%;
  background: var(--dsh-accent);
  animation: dsh-run-pulse 1.2s ease-in-out infinite;
}
.sidebar.collapsed .run-dot { left: 4px; top: 6px; }
@keyframes dsh-run-pulse {
  0%, 100% { opacity: 1; transform: scale(1); }
  50% { opacity: .35; transform: scale(.8); }
}

.t { font-size: 13px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.m { font-size: 11px; color: var(--dsh-fg-2); margin-top: 2px; display: flex; align-items: center; gap: 4px; }
.ws-tag {
  font-size: 10px; color: var(--dsh-accent); background: var(--dsh-accent-soft);
  border-radius: 4px; padding: 0 4px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 90px;
}

.sidebar.collapsed .item { padding: 6px 4px; text-align: center; }
.t-collapsed {
  width: 28px; height: 28px; border-radius: 50%;
  background: var(--dsh-bg-3); color: var(--dsh-fg-1);
  display: grid; place-items: center; font-size: 14px; font-weight: 600; margin: 0 auto;
}
.sidebar.collapsed .item.active .t-collapsed { background: var(--dsh-accent); color: var(--dsh-accent-contrast); }

/* 工具菜单（会话列表下方） */
.tools-section { margin-top: 4px; }
.section.tools-section { display: flex; align-items: center; font-size: 12px; color: var(--dsh-fg-2); text-transform: uppercase; letter-spacing: .05em; margin: 8px 2px; }
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

.bottom { padding-top: 12px; border-top: 1px solid var(--dsh-border); display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.sidebar.collapsed .bottom { padding-top: 8px; justify-content: center; }

.user { display: flex; align-items: center; gap: 10px; padding: 8px; min-width: 0; }
.sidebar.collapsed .user { justify-content: center; padding: 6px; }

.avatar { width: 34px; height: 34px; border-radius: 50%; background: linear-gradient(135deg, var(--dsh-accent), #8a5cf6); display: grid; place-items: center; font-size: 18px; color: #fff; flex-shrink: 0; }
.name { font-size: 13px; font-weight: 600; color: var(--dsh-fg-0); }
.status { font-size: 12px; color: var(--dsh-success); }

:deep(.el-empty__description) { font-size: 12px; }
</style>
