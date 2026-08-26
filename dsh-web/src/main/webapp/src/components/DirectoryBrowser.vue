<script setup lang="ts">
// DirectoryBrowser — 网页内目录树浏览（对齐官方 directory-picker browse）。
// 一次列一层子目录；面包屑逐级可跳；选中目录后确认返回 path；支持新建子目录。
import { ref, watch } from 'vue';
import { listDirectory, createDirectory, type DirectoryListing, type DirectoryEntry } from '../api';

const props = defineProps<{
  open: boolean;
  /** owner 采纳中（createWorkspace 在途）：禁用确认按钮。 */
  busy?: boolean;
  /** 初始浏览路径（缺省走后端默认：固定工作区根）。 */
  initialPath?: string;
  /** 部署布局固定根（工作区根/代码根/home），渲染为快捷入口。 */
  roots?: import('../api').DirectoryRoots | null;
}>();

const emit = defineEmits<{
  (e: 'pick', path: string): void;
  (e: 'cancel'): void;
}>();

const listing = ref<DirectoryListing | null>(null);
const loading = ref(false);
const error = ref('');
const selected = ref<string | null>(null);

const newName = ref('');
const creating = ref(false);
const newError = ref('');

async function load(path?: string): Promise<void> {
  loading.value = true;
  error.value = '';
  try {
    listing.value = await listDirectory(path);
    selected.value = null;
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    loading.value = false;
  }
}

watch(
  () => props.open,
  (v) => {
    if (v) void load(props.initialPath ?? undefined);
  },
);

/** 快捷入口：跳到部署布局固定根（工作区根/代码根/Home）。 */
function jump(path: string): void {
  void load(path);
}

/** 快捷入口列表（去重 + 保留 Home）。 */
const shortcuts = () => {
  const r = props.roots;
  const list: { label: string; path: string }[] = [];
  if (r) {
    if (r.workspaceRoot) list.push({ label: '📁 工作区根', path: r.workspaceRoot });
    if (r.codeRoot) list.push({ label: '📦 代码根', path: r.codeRoot });
    if (r.home) list.push({ label: '🏠 Home', path: r.home });
  }
  return list;
};

function enter(entry: DirectoryEntry): void {
  void load(entry.path);
}

function select(entry: DirectoryEntry): void {
  selected.value = entry.path;
}

function confirm(): void {
  if (selected.value) emit('pick', selected.value);
}

async function mkdir(): Promise<void> {
  const name = newName.value.trim();
  if (!name || !listing.value) return;
  creating.value = true;
  newError.value = '';
  try {
    await createDirectory(listing.value.path, name);
    newName.value = '';
    await load(listing.value.path);
  } catch (e) {
    newError.value = e instanceof Error ? e.message : String(e);
  } finally {
    creating.value = false;
  }
}
</script>

<template>
  <el-dialog
    :model-value="open"
    title="选择工作目录"
    width="560px"
    :close-on-click-modal="false"
    @close="emit('cancel')"
  >
    <!-- 快捷入口：部署布局固定根（工作区根/代码根/Home） -->
    <div v-if="shortcuts().length" class="shortcuts">
      <el-button v-for="s in shortcuts()" :key="s.path" size="small" text @click="jump(s.path)">
        {{ s.label }}
      </el-button>
    </div>

    <!-- 面包屑：Home 根 + 祖先链，每级可跳 -->
    <div v-if="listing && listing.crumbs.length > 1" class="crumbs">
      <el-breadcrumb separator="/">
        <el-breadcrumb-item
          v-for="c in listing.crumbs"
          :key="c.path"
          @click="enter(c)"
        >
          <a class="crumb-link">{{ c.path === listing.home ? 'Home' : c.name }}</a>
        </el-breadcrumb-item>
      </el-breadcrumb>
    </div>

    <div v-loading="loading" class="browser-body">
      <el-alert v-if="error" type="error" :title="error" show-icon :closable="false" />
      <el-empty v-else-if="listing && listing.entries.length === 0" description="此目录下没有子目录" :image-size="60" />
      <ul v-else-if="listing" class="dir-list">
        <li
          v-for="e in listing.entries"
          :key="e.path"
          class="dir-row"
          :class="{ selected: selected === e.path, hidden: e.hidden }"
          @click="select(e)"
          @dblclick="enter(e)"
        >
          <span class="dir-icon">📁</span>
          <span class="dir-name" :title="e.path">{{ e.name }}</span>
          <el-button size="small" text type="primary" class="open-btn" @click.stop="enter(e)">打开</el-button>
        </li>
      </ul>
      <div v-if="listing && listing.truncated" class="truncated-hint">仅显示前 {{ listing.entries.length }} 项，更深目录请在搜索中查找</div>
    </div>

    <!-- 新建子目录 -->
    <div class="mkdir-row">
      <el-input v-model="newName" placeholder="新建子目录名称" size="small" clearable @keyup.enter="mkdir" />
      <el-button size="small" :loading="creating" @click="mkdir">新建</el-button>
    </div>
    <div v-if="newError" class="mkdir-error">{{ newError }}</div>

    <template #footer>
      <div class="footer-hint">选择目录后将在该目录创建/连接会话</div>
      <el-button @click="emit('cancel')">取消</el-button>
      <el-button type="primary" :disabled="!selected || busy" :loading="busy" @click="confirm">
        选择此目录并开会话
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.shortcuts {
  margin-bottom: 8px;
  display: flex;
  flex-wrap: wrap;
  gap: 2px;
}
.crumbs {
  margin-bottom: 10px;
}
.crumb-link {
  cursor: pointer;
  color: var(--el-text-color-regular);
}
.crumb-link:hover {
  color: var(--el-color-primary);
}
.browser-body {
  min-height: 260px;
  max-height: 360px;
  overflow-y: auto;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  padding: 4px;
}
.dir-list {
  list-style: none;
  margin: 0;
  padding: 0;
}
.dir-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  border-radius: 4px;
  cursor: pointer;
  user-select: none;
}
.dir-row:hover {
  background: var(--el-fill-color-light);
}
.dir-row.selected {
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
}
.dir-row.hidden .dir-name {
  opacity: 0.55;
}
.dir-icon {
  flex: none;
}
.dir-name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.open-btn {
  flex: none;
  visibility: hidden;
}
.dir-row:hover .open-btn {
  visibility: visible;
}
.truncated-hint {
  padding: 6px 10px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.mkdir-row {
  display: flex;
  gap: 8px;
  margin-top: 10px;
}
.mkdir-error {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-color-danger);
}
.footer-hint {
  flex: 1;
  text-align: left;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
