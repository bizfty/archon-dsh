<script setup lang="ts">
// CodeView.vue — coder 场景：在线代码开发工具（项目 + 文件树 + 编辑器）。
// 轻量自包含：不引入 Monaco，textarea 编辑 + highlight.js 预览切换。
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue';
import hljs from 'highlight.js';
import {
  listCodeProjects, createCodeProject, getCodeTree, readCodeFile,
  saveCodeFile, createCodeFile, deleteCodeFile,
  type CodeProject, type CodeTreeNode, type CodeFileContent,
} from '../api';

const emit = defineEmits<{ (e: 'close'): void }>();

// ---- 状态 ----
const projects = ref<CodeProject[]>([]);
const currentProject = ref('');
const treeRoot = ref<CodeTreeNode | null>(null);
const flatTree = ref<FlatNode[]>([]);
const currentPath = ref('');
const currentContent = ref('');
const currentLines = ref(0);
const dirty = ref(false);
const saving = ref(false);
const loadingTree = ref(false);
const previewMode = ref(false);
const notice = ref('');
const statusText = ref('就绪');

const newProjectDialog = ref(false);
const newProjectName = ref('');
const newFileDialog = ref(false);
const newFilePath = ref('');

interface FlatNode {
  name: string;
  path: string;
  type: 'dir' | 'file';
  depth: number;
  size?: number;
  children?: FlatNode[];
  expanded: boolean;
}

// ---- 语言映射（扩展名 → highlight.js 语言）----
const LANG_BY_EXT: Record<string, string> = {
  java: 'java', kt: 'kotlin', groovy: 'groovy', gradle: 'groovy',
  py: 'python', js: 'javascript', jsx: 'jsx', mjs: 'javascript',
  ts: 'typescript', tsx: 'tsx', vue: 'xml', svelte: 'html',
  html: 'xml', htm: 'xml', css: 'css', scss: 'scss', less: 'less',
  json: 'json', xml: 'xml', yaml: 'yaml', yml: 'yaml',
  md: 'markdown', markdown: 'markdown', sh: 'bash', bash: 'bash',
  sql: 'sql', go: 'go', rs: 'rust', c: 'c', h: 'c', cpp: 'cpp', hpp: 'cpp',
  rb: 'ruby', php: 'php', properties: 'properties', ini: 'ini',
  toml: 'ini', dockerfile: 'dockerfile', txt: 'plaintext', log: 'plaintext',
};

function langFor(path: string): string {
  const base = path.split('/').pop() || '';
  if (base.toLowerCase() === 'dockerfile') return 'dockerfile';
  const ext = base.includes('.') ? base.split('.').pop()!.toLowerCase() : '';
  return LANG_BY_EXT[ext] || 'plaintext';
}

// ---- 文件树 ----
function flatten(root: CodeTreeNode | null): FlatNode[] {
  if (!root || !root.children) return [];
  const out: FlatNode[] = [];
  const walk = (nodes: CodeTreeNode[], depth: number) => {
    for (const n of nodes) {
      const fn: FlatNode = { ...n, depth, expanded: depth === 0 };
      out.push(fn);
      if (n.type === 'dir' && n.children) walk(n.children, depth + 1);
    }
  };
  walk(root.children, 0);
  return out;
}

async function loadProjects() {
  try {
    projects.value = await listCodeProjects();
    if (!currentProject.value && projects.value.length > 0) {
      currentProject.value = projects.value[0].name;
      await loadTree();
    } else if (currentProject.value) {
      await loadTree();
    }
  } catch (e: any) {
    notice.value = '加载项目失败: ' + (e.message || e);
  }
}

async function loadTree() {
  if (!currentProject.value) { treeRoot.value = null; flatTree.value = []; return; }
  loadingTree.value = true;
  try {
    treeRoot.value = await getCodeTree(currentProject.value);
    flatTree.value = flatten(treeRoot.value);
  } catch (e: any) {
    notice.value = '加载文件树失败: ' + (e.message || e);
  } finally {
    loadingTree.value = false;
  }
}

function onProjectChange() {
  currentPath.value = '';
  currentContent.value = '';
  dirty.value = false;
  loadTree();
}

async function openFile(node: FlatNode) {
  if (node.type !== 'file' || !currentProject.value) return;
  if (dirty.value && currentPath.value !== node.path) {
    const ok = window.confirm(`「${currentPath.value}」有未保存修改，放弃并打开新文件？`);
    if (!ok) return;
  }
  try {
    const f = await readCodeFile(currentProject.value, node.path);
    if (f.error) { notice.value = f.error; return; }
    currentPath.value = f.path;
    currentContent.value = f.content;
    currentLines.value = f.lines;
    dirty.value = false;
    previewMode.value = false;
    statusText.value = `已打开 ${f.path} · ${f.lines} 行`;
  } catch (e: any) {
    notice.value = '打开失败: ' + (e.message || e);
  }
}

function toggleDir(node: FlatNode) {
  node.expanded = !node.expanded;
  rebuildFlat();
}

function rebuildFlat() {
  if (!treeRoot.value) { flatTree.value = []; return; }
  const out: FlatNode[] = [];
  const walk = (nodes: CodeTreeNode[], depth: number) => {
    for (const n of nodes) {
      const fn: FlatNode = { ...n, depth, expanded: depth === 0 };
      out.push(fn);
      if (n.type === 'dir' && fn.expanded && n.children) walk(n.children, depth + 1);
    }
  };
  walk(treeRoot.value.children || [], 0);
  flatTree.value = out;
}

// ---- 编辑 ----
function onEdit() {
  dirty.value = true;
  currentLines.value = currentContent.value.split('\n').length;
  statusText.value = '编辑中…';
}

function onKeydown(e: KeyboardEvent) {
  if ((e.ctrlKey || e.metaKey) && e.key === 's') {
    e.preventDefault();
    save();
  }
  if (e.key === 'Tab' && !e.ctrlKey && !e.metaKey) {
    e.preventDefault();
    const ta = e.target as HTMLTextAreaElement;
    const start = ta.selectionStart;
    const end = ta.selectionEnd;
    currentContent.value = currentContent.value.slice(0, start) + '  ' + currentContent.value.slice(end);
    requestAnimationFrame(() => { ta.selectionStart = ta.selectionEnd = start + 2; });
    dirty.value = true;
    statusText.value = '编辑中…';
  }
}

async function save() {
  if (!currentProject.value || !currentPath.value || !dirty.value) return;
  saving.value = true;
  try {
    await saveCodeFile(currentProject.value, currentPath.value, currentContent.value);
    dirty.value = false;
    statusText.value = `已保存 ${currentPath.value}`;
  } catch (e: any) {
    notice.value = '保存失败: ' + (e.message || e);
  } finally {
    saving.value = false;
  }
}

// ---- 新建 ----
async function doCreateProject() {
  const name = newProjectName.value.trim();
  if (!name) return;
  try {
    await createCodeProject(name);
    newProjectDialog.value = false;
    newProjectName.value = '';
    currentProject.value = name;
    await loadProjects();
    statusText.value = `项目 ${name} 已创建`;
  } catch (e: any) {
    notice.value = '创建失败: ' + (e.message || e);
  }
}

async function doCreateFile() {
  const path = newFilePath.value.trim();
  if (!path || !currentProject.value) return;
  try {
    await createCodeFile(currentProject.value, path);
    newFileDialog.value = false;
    newFilePath.value = '';
    await loadTree();
    const node = flatTree.value.find(n => n.path === path);
    if (node) await openFile(node);
    statusText.value = `文件 ${path} 已创建`;
  } catch (e: any) {
    notice.value = '创建失败: ' + (e.message || e);
  }
}

async function doDeleteCurrent() {
  if (!currentProject.value || !currentPath.value) return;
  if (!window.confirm(`删除 ${currentPath.value}？此操作不可恢复。`)) return;
  try {
    await deleteCodeFile(currentProject.value, currentPath.value);
    currentPath.value = '';
    currentContent.value = '';
    dirty.value = false;
    await loadTree();
    statusText.value = '文件已删除';
  } catch (e: any) {
    notice.value = '删除失败: ' + (e.message || e);
  }
}

// ---- 预览 ----
const previewHtml = computed(() => {
  if (!currentContent.value) return '';
  const lang = langFor(currentPath.value);
  try {
    if (lang && hljs.getLanguage(lang)) {
      return hljs.highlight(currentContent.value, { language: lang }).value;
    }
    return hljs.highlightAuto(currentContent.value).value;
  } catch {
    return '';
  }
});

// ---- 行号 gutter ----
const lineNumbers = computed(() => {
  const n = Math.max(currentContent.value.split('\n').length, 1);
  return Array.from({ length: n }, (_, i) => i + 1).join('\n');
});

function onEditorScroll() {
  const gutter = gutterRef.value;
  const ta = editorRef.value;
  if (gutter && ta) gutter.scrollTop = ta.scrollTop;
}

// ---- 生命周期 ----
const gutterRef = ref<HTMLDivElement | null>(null);
const editorRef = ref<HTMLTextAreaElement | null>(null);

onMounted(() => {
  loadProjects();
  document.addEventListener('keydown', onKeydown);
});

onBeforeUnmount(() => {
  document.removeEventListener('keydown', onKeydown);
});

watch(notice, (v) => {
  if (v) {
    setTimeout(() => { notice.value = ''; }, 4000);
  }
});

defineExpose({});
</script>

<template>
  <div class="code-view">
    <!-- 顶部工具栏 -->
    <div class="code-toolbar">
      <el-button size="small" text class="back-btn" @click="emit('close')" title="返回对话">
        ← 对话
      </el-button>
      <el-select
        v-model="currentProject"
        size="small"
        class="project-select"
        placeholder="选择项目"
        @change="onProjectChange"
      >
        <el-option v-for="p in projects" :key="p.name" :label="`${p.name} (${p.fileCount})`" :value="p.name" />
      </el-select>
      <el-button size="small" @click="newProjectDialog = true">＋ 项目</el-button>
      <el-button size="small" :disabled="!currentProject" @click="newFileDialog = true">＋ 文件</el-button>
      <el-button size="small" :disabled="!currentPath" @click="doDeleteCurrent">🗑 删除</el-button>
      <el-button size="small" @click="loadTree">⟳ 刷新</el-button>
      <div class="toolbar-spacer" />
      <el-button
        size="small"
        :type="previewMode ? 'primary' : 'default'"
        :disabled="!currentPath"
        @click="previewMode = !previewMode"
      >
        {{ previewMode ? '✎ 编辑' : '👁 预览' }}
      </el-button>
      <el-button
        size="small"
        type="success"
        :loading="saving"
        :disabled="!dirty || !currentPath"
        @click="save"
      >
        保存 (Ctrl+S)
      </el-button>
    </div>

    <!-- 主区 -->
    <div class="code-main">
      <!-- 文件树 -->
      <div class="code-tree" v-loading="loadingTree">
        <div v-if="!currentProject" class="tree-empty">选择或创建项目开始</div>
        <div
          v-for="node in flatTree"
          :key="node.path"
          class="tree-node"
          :class="{ 'is-file': node.type === 'file', 'is-active': node.path === currentPath, 'is-dir': node.type === 'dir' }"
          :style="{ paddingLeft: (8 + node.depth * 16) + 'px' }"
          @click="node.type === 'dir' ? toggleDir(node) : openFile(node)"
        >
          <span class="tree-icon">{{ node.type === 'dir' ? (node.expanded ? '📂' : '📁') : '📄' }}</span>
          <span class="tree-name" :title="node.path">{{ node.name }}</span>
          <span v-if="node.type === 'file' && node.size !== undefined" class="tree-size">{{ node.size }}</span>
        </div>
      </div>

      <!-- 编辑器 -->
      <div class="code-editor">
        <div v-if="!currentPath" class="editor-empty">
          <div class="empty-icon">💻</div>
          <div class="empty-title">在线代码开发</div>
          <div class="empty-sub">选择一个项目与文件开始编辑，或新建项目 / 文件</div>
        </div>
        <template v-else>
          <div class="editor-head">
            <span class="file-path">{{ currentProject }} / {{ currentPath }}</span>
            <el-tag size="small" :type="dirty ? 'warning' : 'success'" effect="plain">
              {{ dirty ? '未保存' : '已保存' }}
            </el-tag>
          </div>
          <div class="editor-body">
            <div class="gutter" ref="gutterRef">{{ lineNumbers }}</div>
            <textarea
              v-if="!previewMode"
              ref="editorRef"
              class="editor-textarea"
              :value="currentContent"
              spellcheck="false"
              @input="onEdit"
              @scroll="onEditorScroll"
            />
            <div v-else class="editor-preview">
              <pre><code v-html="previewHtml"></code></pre>
            </div>
          </div>
        </template>
      </div>
    </div>

    <!-- 状态栏 -->
    <div class="code-statusbar">
      <span class="status-left">{{ statusText }}</span>
      <span v-if="currentPath" class="status-right">{{ currentLines }} 行 · {{ langFor(currentPath) }}</span>
    </div>

    <!-- 新建项目对话框 -->
    <el-dialog v-model="newProjectDialog" title="新建项目" width="420px" append-to-body>
      <el-input
        v-model="newProjectName"
        placeholder="项目名（字母/数字/下划线/连字符）"
        @keydown.enter="doCreateProject"
      />
      <template #footer>
        <el-button @click="newProjectDialog = false">取消</el-button>
        <el-button type="primary" :disabled="!newProjectName.trim()" @click="doCreateProject">创建</el-button>
      </template>
    </el-dialog>

    <!-- 新建文件对话框 -->
    <el-dialog v-model="newFileDialog" title="新建文件" width="420px" append-to-body>
      <el-input
        v-model="newFilePath"
        placeholder="相对路径，如 src/main/java/Hello.java"
        @keydown.enter="doCreateFile"
      />
      <template #footer>
        <el-button @click="newFileDialog = false">取消</el-button>
        <el-button type="primary" :disabled="!newFilePath.trim()" @click="doCreateFile">创建</el-button>
      </template>
    </el-dialog>

    <el-alert v-if="notice" class="code-notice" :title="notice" type="error" :closable="false" />
  </div>
</template>

<style scoped>
.code-view {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  background: var(--dsh-bg-1);
  border: 1px solid var(--dsh-border);
  border-radius: 10px;
  overflow: hidden;
}

.code-toolbar {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 10px;
  background: var(--dsh-bg-2);
  border-bottom: 1px solid var(--dsh-border);
  flex-wrap: wrap;
}

.project-select { width: 220px; }

.toolbar-spacer { flex: 1; }

.code-main {
  display: flex;
  flex: 1;
  min-height: 0;
}

.code-tree {
  width: 260px;
  min-width: 200px;
  overflow: auto;
  background: var(--dsh-bg-2);
  border-right: 1px solid var(--dsh-border);
  padding: 6px 0;
}

.tree-empty { color: var(--dsh-fg-3); padding: 16px; font-size: 12px; }

.tree-node {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 3px 8px;
  cursor: pointer;
  font-size: 13px;
  color: var(--dsh-fg-1);
  white-space: nowrap;
  user-select: none;
}

.tree-node:hover { background: var(--dsh-bg-3); }
.tree-node.is-active { background: var(--dsh-accent-soft); color: var(--dsh-accent); }
.tree-icon { flex: none; }
.tree-name { overflow: hidden; text-overflow: ellipsis; }
.tree-size { margin-left: auto; color: var(--dsh-fg-3); font-size: 11px; }

.code-editor {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  background: var(--dsh-bg-0);
}

.editor-empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: var(--dsh-fg-3);
  gap: 8px;
}

.empty-icon { font-size: 48px; }
.empty-title { font-size: 18px; color: var(--dsh-fg-1); }
.empty-sub { font-size: 13px; }

.editor-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  background: var(--dsh-bg-2);
  border-bottom: 1px solid var(--dsh-border);
}

.file-path {
  font-family: ui-monospace, 'JetBrains Mono', Consolas, monospace;
  font-size: 12px;
  color: var(--dsh-fg-2);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.editor-body {
  flex: 1;
  display: flex;
  min-height: 0;
  overflow: hidden;
}

.gutter {
  width: 44px;
  flex: none;
  padding: 8px 8px 8px 0;
  text-align: right;
  font-family: ui-monospace, 'JetBrains Mono', Consolas, monospace;
  font-size: 13px;
  line-height: 20px;
  color: var(--dsh-fg-3);
  background: var(--dsh-bg-1);
  border-right: 1px solid var(--dsh-border);
  overflow: hidden;
  white-space: pre;
  user-select: none;
}

.editor-textarea {
  flex: 1;
  min-width: 0;
  resize: none;
  border: none;
  outline: none;
  background: transparent;
  color: var(--dsh-fg-0);
  font-family: ui-monospace, 'JetBrains Mono', Consolas, monospace;
  font-size: 13px;
  line-height: 20px;
  padding: 8px 12px;
  tab-size: 2;
  overflow: auto;
}

.editor-preview {
  flex: 1;
  min-width: 0;
  overflow: auto;
  padding: 8px 12px;
}

.editor-preview pre {
  margin: 0;
  font-family: ui-monospace, 'JetBrains Mono', Consolas, monospace;
  font-size: 13px;
  line-height: 20px;
}

.editor-preview code { background: transparent !important; padding: 0 !important; }

.code-statusbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 4px 10px;
  font-size: 12px;
  color: var(--dsh-fg-3);
  background: var(--dsh-bg-2);
  border-top: 1px solid var(--dsh-border);
}

.code-notice { position: absolute; bottom: 40px; left: 50%; transform: translateX(-50%); width: 70%; z-index: 30; }
</style>
