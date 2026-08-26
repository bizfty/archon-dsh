<script setup lang="ts">
// CodeView.vue — coder 场景：在线代码开发工具（项目 + 文件树 + 编辑器）。
// 编辑模式用 Monaco Editor（editor.main：完整 contrib（suggest/find/folding/括号匹配…）+ Monarch 高亮 +
// 官方语言服务智能提示（ts/js/json/css/less/scss/html）+ 无语言服务语言的关键词/片段补全；
// 语言本体与 worker 按需懒加载）；预览模式用 highlight.js 渲染。
import { ref, computed, onMounted, onBeforeUnmount, watch, nextTick } from 'vue';
import hljs from 'highlight.js';
import { renderMarkdown } from '../render';
import { appState, connectWorkspaceByPath, pushNotice } from '../store';
import {
  listCodeProjects, createCodeProject, getCodeTree, readCodeFile,
  saveCodeFile, createCodeFile, deleteCodeFile,
  type CodeProject, type CodeTreeNode, type CodeFileContent,
} from '../api';
import type * as MonacoNS from 'monaco-editor';
// Vite worker 导入：editor 核心 + 4 个官方语言服务（智能提示/校验/格式化），各自独立 worker chunk
import editorWorker from 'monaco-editor/editor/editor.worker?worker';
import jsonWorker from 'monaco-editor/language/json/json.worker?worker';
import cssWorker from 'monaco-editor/language/css/css.worker?worker';
import htmlWorker from 'monaco-editor/language/html/html.worker?worker';
import tsWorker from 'monaco-editor/language/typescript/ts.worker?worker';

const emit = defineEmits<{ (e: 'close'): void; (e: 'session-opened'): void }>();

// ---- 场景 ----
// scene: 'coder' = 用户工作区（默认根 data/workspace/coder/project 下选择项目）；
//        'self'  = 自我完善：直接操作 archon-dsh 源码目录（project 固定为 '.'）。
const props = withDefaults(defineProps<{ scene?: 'coder' | 'self'; embedded?: boolean }>(), { scene: 'coder', embedded: false });
const isSelf = computed(() => props.scene === 'self');
/** self 场景固定 project 名（后端忽略 project 参数，落到源码根）。 */
const SELF_PROJECT = '.';
const sceneLabel = computed(() => (isSelf.value ? '🧬 自我完善 · archon-dsh 源码' : '💻 代码开发'));
/** 当前目录绝对路径（用于「在此目录开会话」）：coder = 根/project；self = 源码根。 */
const currentWorkspacePath = computed(() => {
  const root = projects.value.find(p => p.name === currentProject.value)?.root;
  if (!root) return '';
  if (isSelf.value) return root;
  return root.replace(/\/+$/, '') + '/' + currentProject.value;
});

// ---- 状态 ----
const projects = ref<CodeProject[]>([]);
const currentProject = ref('');

/** 当前目录变化 → 同步到全局 codeCwd（浮动对话会话列表/新建会话的作用域）。
 *  注意：必须在 projects/currentProject 定义之后注册 watch（Vue watch 创建时
 *  立即 effect.run() 求值 getter，前置会触发 TDZ ReferenceError）。 */
watch(currentWorkspacePath, (p) => {
  appState.codeCwd = p || null;
});
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
/** 目录展开状态（按 path 持久保存，避免 rebuildFlat 重建时丢失）。 */
const expandedDirs = ref<Set<string>>(new Set());

const newProjectDialog = ref(false);
const newProjectName = ref('');
const newFileDialog = ref(false);
const newFilePath = ref('');
const sessionBusy = ref(false);

interface FlatNode {
  name: string;
  path: string;
  type: 'dir' | 'file';
  depth: number;
  size?: number;
  children?: CodeTreeNode[];
  expanded: boolean;
  kind?: string;
  package?: string;
  pathLabel?: string;
}

// ---- 语言映射 ----
/** 预览模式：扩展名 → highlight.js 语言 id。 */
const HLJS_LANG: Record<string, string> = {
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
/** 编辑模式：扩展名 → Monaco 语言 id（只引用已注册的 monarch 语言；c→cpp 近似、bash→shell、properties→ini）。 */
const MONACO_LANG: Record<string, string> = {
  java: 'java', kt: 'kotlin', groovy: 'groovy', gradle: 'groovy',
  py: 'python', js: 'javascript', jsx: 'javascript', mjs: 'javascript',
  ts: 'typescript', tsx: 'typescript', vue: 'html', svelte: 'html',
  html: 'html', htm: 'html', css: 'css', scss: 'scss', less: 'less',
  json: 'json', xml: 'xml', yaml: 'yaml', yml: 'yaml',
  md: 'markdown', markdown: 'markdown', sh: 'shell', bash: 'shell',
  sql: 'sql', go: 'go', rs: 'rust', c: 'cpp', h: 'cpp', cpp: 'cpp', hpp: 'cpp',
  rb: 'ruby', php: 'php', properties: 'ini', ini: 'ini',
  toml: 'ini', dockerfile: 'dockerfile', txt: 'plaintext', log: 'plaintext',
};

function langFor(path: string): string {
  const base = path.split('/').pop() || '';
  if (base.toLowerCase() === 'dockerfile') return 'dockerfile';
  const ext = base.includes('.') ? base.split('.').pop()!.toLowerCase() : '';
  return HLJS_LANG[ext] || 'plaintext';
}

function monacoLangFor(path: string): string {
  const base = path.split('/').pop() || '';
  if (base.toLowerCase() === 'dockerfile') return 'dockerfile';
  const ext = base.includes('.') ? base.split('.').pop()!.toLowerCase() : '';
  return MONACO_LANG[ext] || 'plaintext';
}

/** 扩展名判断：.md / .markdown → 渲染为 markdown 文档。 */
function isMarkdownPath(path: string): boolean {
  const lower = path.toLowerCase();
  return lower.endsWith('.md') || lower.endsWith('.markdown');
}

// ---- 文件树 ----
function flatten(root: CodeTreeNode | null): FlatNode[] {
  if (!root || !root.children) return [];
  const out: FlatNode[] = [];
  const walk = (nodes: CodeTreeNode[], depth: number) => {
    for (const n of nodes) {
      // 首次遇到目录：顶层、Maven 结构目录（src/src/main…）与源码根（src/main/java）默认展开；
      // 包节点（package）默认折叠，点击展开看文件
      if (n.type === 'dir' && !expandedDirs.value.has(n.path)
          && (depth === 0 || n.kind === 'structural' || n.kind === 'source-root')) {
        expandedDirs.value.add(n.path);
      }
      const fn: FlatNode = { ...n, depth, expanded: expandedDirs.value.has(n.path) };
      out.push(fn);
      if (n.type === 'dir' && fn.expanded && n.children) walk(n.children, depth + 1);
    }
  };
  walk(root.children, 0);
  return out;
}

async function loadProjects() {
  try {
    projects.value = await listCodeProjects(props.scene);
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
    treeRoot.value = await getCodeTree(currentProject.value, props.scene);
    expandedDirs.value = new Set<string>();
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
    const f = await readCodeFile(currentProject.value, node.path, props.scene);
    if (f.error) { notice.value = f.error; return; }
    currentPath.value = f.path;
    currentContent.value = f.content;
    currentLines.value = f.lines;
    dirty.value = false;
    // md 文件打开后直接进入渲染预览（文档模式）；其他文件进入编辑
    previewMode.value = isMarkdownPath(f.path);
    statusText.value = `已打开 ${f.path} · ${f.lines} 行`;
  } catch (e: any) {
    notice.value = '打开失败: ' + (e.message || e);
  }
}

/** 树节点图标：包节点📦 / 源码根🗂 / 单链🗃 / 普通目录📁📂 / 文件📄。 */
function treeIcon(node: FlatNode): string {
  if (node.type !== 'dir') return '📄';
  if (node.kind === 'package') return '📦';
  if (node.kind === 'chain') return '🗃';
  if (node.kind === 'source-root') return '🗂';
  return node.expanded ? '📂' : '📁';
}

/** 树节点 title：package 显示完整点分包路径，chain 显示完整相对路径，其余用 path。 */
function treeTitle(node: FlatNode): string {
  if (node.kind === 'package') return node.package || node.path;
  if (node.kind === 'chain') return node.pathLabel || node.path;
  return node.path;
}

/** 树节点 class：按 kind 追加样式（短包名高亮 / 源码根加粗等）。 */
function nodeClass(node: FlatNode): Record<string, boolean> {
  return {
    'is-file': node.type === 'file',
    'is-active': node.path === currentPath.value,
    'is-dir': node.type === 'dir',
    'is-package': node.kind === 'package',
    'is-chain': node.kind === 'chain',
    'is-source-root': node.kind === 'source-root',
    'is-structural': node.kind === 'structural',
  };
}

/** 项目类型徽标：maven→Maven / gradle→Gradle / node→Node / 其他→空。 */
function typeBadge(t?: string): string {
  const map: Record<string, string> = { maven: 'Maven', gradle: 'Gradle', node: 'Node' };
  const label = t ? map[t] : '';
  return label ? ` · ${label}` : '';
}

function toggleDir(node: FlatNode) {
  if (expandedDirs.value.has(node.path)) {
    expandedDirs.value.delete(node.path);
  } else {
    expandedDirs.value.add(node.path);
  }
  rebuildFlat();
}

function rebuildFlat() {
  if (!treeRoot.value) { flatTree.value = []; return; }
  const out: FlatNode[] = [];
  const walk = (nodes: CodeTreeNode[], depth: number) => {
    for (const n of nodes) {
      const fn: FlatNode = { ...n, depth, expanded: expandedDirs.value.has(n.path) };
      out.push(fn);
      if (n.type === 'dir' && fn.expanded && n.children) walk(n.children, depth + 1);
    }
  };
  walk(treeRoot.value.children || [], 0);
  flatTree.value = out;
}

// ---- Monaco 编辑器（编辑模式） ----
// editor.main 完整内核（全部 contrib + 语言注册，语言本体惰性 chunk）；语言服务 worker 按需加载。
const monacoHost = ref<HTMLDivElement | null>(null);
let monaco: typeof import('monaco-editor') | null = null;
let editor: any = null;
let monacoLoading: Promise<void> | null = null;


/** 官方语言服务（智能提示/校验/格式化）：语言需已注册（definitions 或自定义 monarch），服务经 onLanguage 挂载。 */
const LANG_SERVICE_MODULES: Array<() => Promise<unknown>> = [
  () => import('monaco-editor/language/typescript/monaco.contribution'), // ts / js（TS 语言服务）
  () => import('monaco-editor/language/json/monaco.contribution'),       // json（JSON 补全/校验）
  () => import('monaco-editor/language/css/monaco.contribution'),        // css / less / scss
  () => import('monaco-editor/language/html/monaco.contribution'),       // html
];

/** Monaco 环境：worker 分发（label 为语言 id；editor 为通用兜底）。 */
function setupMonacoEnvironment() {
  if ((self as any).MonacoEnvironment) return;
  (self as any).MonacoEnvironment = {
    getWorker(_workerId: string, label: string) {
      if (label === 'json') return new jsonWorker();
      if (label === 'css' || label === 'scss' || label === 'less') return new cssWorker();
      if (label === 'html' || label === 'handlebars' || label === 'razor') return new htmlWorker();
      if (label === 'typescript' || label === 'javascript') return new tsWorker();
      return new editorWorker();
    },
  };
}

function loadMonaco(): Promise<void> {
  if (monaco) return Promise.resolve();
  if (monacoLoading) return monacoLoading;
  monacoLoading = (async () => {
    setupMonacoEnvironment();
    // editor.main：完整编辑器（含 suggest/find/folding 等全部 contrib）+ 全部语言注册（register.js 惰性 loader，语言本体按需 chunk）
    const m = (await import('monaco-editor/editor/editor.main')) as typeof import('monaco-editor');
    // 并行加载官方语言服务（ts/js/json/css/less/scss/html 智能提示/校验/格式化）
    await Promise.all(LANG_SERVICE_MODULES.map((fn) => fn()));
    registerCustomLanguages(m);
    registerKeywordCompletions(m);
    monaco = m;
    applyMonacoTheme();
  })().catch((e: any) => {
    monacoLoading = null;
    notice.value = '编辑器内核加载失败: ' + (e?.message || e);
    throw e;
  });
  return monacoLoading;
}

/** 基础语言清单之外的补充：json（避开语言服务的 worker 依赖）与 groovy（Monaco 无内置）。 */
function registerCustomLanguages(m: typeof MonacoNS) {
  // Groovy（Gradle 脚本）：关键字/注释/字符串/注解/数字
  m.languages.register({ id: 'groovy', extensions: ['.groovy', '.gradle'], aliases: ['Groovy', 'groovy'] });
  m.languages.setMonarchTokensProvider('groovy', {
    tokenPostfix: '.groovy',
    keywords: [
      'def', 'class', 'interface', 'enum', 'trait', 'import', 'package', 'return',
      'if', 'else', 'for', 'while', 'switch', 'case', 'default', 'break', 'continue',
      'new', 'this', 'super', 'null', 'true', 'false', 'void', 'static', 'final',
      'public', 'private', 'protected', 'extends', 'implements', 'in', 'as', 'assert',
      'try', 'catch', 'finally', 'throw', 'throws', 'instanceof', 'synchronized',
    ],
    tokenizer: {
      root: [
        [/\/\/.*$/, 'comment'],
        [/\/\*/, 'comment', '@comment'],
        [/'[^']*'/, 'string'],
        [/"[^"]*"/, 'string'],
        [/@[a-zA-Z_]\w*/, 'annotation'],
        [/\b\d+(\.\d+)?\b/, 'number'],
        [/[a-zA-Z_]\w*/, { cases: { '@keywords': 'keyword', '@default': 'identifier' } }],
      ],
      comment: [
        [/\*\//, 'comment', '@pop'],
        [/./, 'comment'],
      ],
    },
  });
}

/** 无官方语言服务语言的轻量补全：关键词 + 常用片段（纯主线程，不依赖 worker）。 */
const KEYWORD_SUGGESTIONS: Record<string, { keywords: string[]; snippets?: Array<{ label: string; insertText: string; documentation?: string }> }> = {
  java: {
    keywords: ['abstract','assert','boolean','break','byte','case','catch','char','class','const','continue','default','do','double','else','enum','extends','final','finally','float','for','goto','if','implements','import','instanceof','int','interface','long','native','new','package','private','protected','public','return','short','static','strictfp','super','switch','synchronized','this','throw','throws','transient','try','void','volatile','while','true','false','null'],
    snippets: [
      { label: 'psvm', insertText: 'public static void main(String[] args) {\n\t${1}\n}', documentation: 'main 方法' },
      { label: 'sout', insertText: 'System.out.println(${1:expr});', documentation: '控制台输出' },
      { label: 'class', insertText: 'public class ${1:Name} {\n\t${2}\n}', documentation: '类声明' },
      { label: 'method', insertText: 'public ${1:void} ${2:name}(${3}) {\n\t${4}\n}', documentation: '方法声明' },
    ],
  },
  kotlin: {
    keywords: ['as','as?','break','class','continue','do','else','false','for','fun','if','in','interface','is','null','object','package','return','super','this','throw','true','try','typealias','typeof','val','var','when','while','private','public','protected','internal','override','abstract','open','sealed','data','companion','init','constructor','import'],
    snippets: [
      { label: 'main', insertText: 'fun main(args: Array<String>) {\n\t${1}\n}', documentation: '入口函数' },
      { label: 'class', insertText: 'class ${1:Name} {\n\t${2}\n}', documentation: '类声明' },
    ],
  },
  groovy: {
    keywords: ['def','class','interface','enum','trait','import','package','return','if','else','for','while','switch','case','default','break','continue','new','this','super','null','true','false','void','static','final','public','private','protected','extends','implements','in','as','assert','try','catch','finally','throw','throws','instanceof','synchronized'],
    snippets: [
      { label: 'plugins', insertText: 'plugins {\n\tid \'${1:java}\'\n}', documentation: 'Gradle plugins 块' },
      { label: 'dependencies', insertText: 'dependencies {\n\t${1:implementation \'group:artifact:version\'}\n}', documentation: 'Gradle dependencies 块' },
      { label: 'repositories', insertText: 'repositories {\n\tmavenCentral()\n}', documentation: 'Gradle repositories 块' },
    ],
  },
  python: {
    keywords: ['and','as','assert','async','await','break','class','continue','def','del','elif','else','except','False','finally','for','from','global','if','import','in','is','lambda','None','nonlocal','not','or','pass','raise','return','True','try','while','with','yield'],
    snippets: [
      { label: 'main', insertText: 'def main():\n\t${1:pass}\n\n\nif __name__ == \'__main__\':\n\tmain()', documentation: '主入口' },
      { label: 'class', insertText: 'class ${1:Name}:\n\tdef __init__(self):\n\t\t${2:pass}', documentation: '类声明' },
    ],
  },
  go: {
    keywords: ['break','case','chan','const','continue','default','defer','else','fallthrough','for','func','go','goto','if','import','interface','map','package','range','return','select','struct','switch','type','var'],
    snippets: [
      { label: 'main', insertText: 'func main() {\n\t${1}\n}', documentation: '主函数' },
      { label: 'iferr', insertText: 'if err != nil {\n\t${1:return err}\n}', documentation: '错误检查' },
    ],
  },
  rust: {
    keywords: ['as','async','await','break','const','continue','crate','dyn','else','enum','extern','false','fn','for','if','impl','in','let','loop','match','mod','move','mut','pub','ref','return','self','Self','static','struct','super','trait','true','type','unsafe','use','where','while'],
    snippets: [
      { label: 'main', insertText: 'fn main() {\n\t${1}\n}', documentation: '主函数' },
    ],
  },
  sql: {
    keywords: ['select','from','where','insert','into','values','update','set','delete','create','table','index','view','drop','alter','add','column','primary','key','foreign','references','join','inner','left','right','full','outer','on','group','by','order','having','limit','offset','and','or','not','null','default','unique','as','distinct','count','sum','avg','min','max','between','like','in','exists','union','all','case','when','then','else','end'],
    snippets: [
      { label: 'select', insertText: 'SELECT ${1:*} FROM ${2:table} WHERE ${3:cond};', documentation: '查询' },
      { label: 'create_table', insertText: 'CREATE TABLE ${1:name} (\n\t${2:id} ${3:INT} PRIMARY KEY\n);', documentation: '建表' },
    ],
  },
  yaml: { keywords: ['true','false','null','yes','no','on','off'] },
  shell: {
    keywords: ['if','then','else','elif','fi','for','do','done','while','until','case','esac','function','return','local','export','source','echo','cd','ls','pwd','mkdir','rm','cp','mv','grep','sed','awk','cat','chmod','sudo','exit','true','false'],
    snippets: [
      { label: 'if', insertText: 'if ${1:cond}; then\n\t${2}\nfi', documentation: 'if 分支' },
      { label: 'for', insertText: 'for ${1:item} in ${2:list}; do\n\t${3}\ndone', documentation: 'for 循环' },
    ],
  },
};

/** 为无官方语言服务的语言注册关键词/片段补全（Ctrl+Space 或输入触发）。 */
function registerKeywordCompletions(m: typeof MonacoNS) {
  for (const [lang, cfg] of Object.entries(KEYWORD_SUGGESTIONS)) {
    m.languages.registerCompletionItemProvider(lang, {
      provideCompletionItems(model, position) {
        const word = model.getWordUntilPosition(position);
        const range = { startLineNumber: position.lineNumber, startColumn: word.startColumn, endLineNumber: position.lineNumber, endColumn: word.endColumn };
        const snips = (cfg.snippets || []).map((s) => ({
          label: s.label,
          kind: m.languages.CompletionItemKind.Snippet,
          insertText: s.insertText,
          insertTextRules: m.languages.CompletionItemInsertTextRule.InsertAsSnippet,
          documentation: s.documentation,
          range,
        }));
        const kws = cfg.keywords.map((k) => ({
          label: k,
          kind: m.languages.CompletionItemKind.Keyword,
          insertText: k,
          range,
        }));
        return { suggestions: [...snips, ...kws] };
      },
    });
  }
}

/** 6 套皮肤 → Monaco 主题基座（与 styles.css 的 data-theme 对应）。 */
const THEME_META: Record<string, { base: 'vs' | 'vs-dark'; mode: 'dark' | 'light' }> = {
  midnight: { base: 'vs-dark', mode: 'dark' },
  aurora:   { base: 'vs-dark', mode: 'dark' },
  ember:    { base: 'vs-dark', mode: 'dark' },
  royal:    { base: 'vs-dark', mode: 'dark' },
  cloud:    { base: 'vs', mode: 'light' },
  paper:    { base: 'vs', mode: 'light' },
};

/** 当前皮肤名（未命中回退 midnight）。 */
function currentThemeName(): string {
  const t = document.documentElement.getAttribute('data-theme') || 'midnight';
  return THEME_META[t] ? t : 'midnight';
}

/** 按当前皮肤（读取 styles.css 的 --dsh-* 与 --hl-* 变量）定义并应用 Monaco 主题。 */
function applyMonacoTheme() {
  if (!monaco) return;
  const name = currentThemeName();
  const meta = THEME_META[name];
  const cs = getComputedStyle(document.documentElement);
  const v = (key: string, fallback: string) => cs.getPropertyValue(key).trim() || fallback;
  const bg0 = v('--dsh-bg-0', meta.mode === 'dark' ? '#1e1e1e' : '#ffffff');
  const bg1 = v('--dsh-bg-1', bg0);
  const fg0 = v('--dsh-fg-0', meta.mode === 'dark' ? '#d4d4d4' : '#000000');
  const fg2 = v('--dsh-fg-2', fg0);
  const fg3 = v('--dsh-fg-3', fg2);
  const accent = v('--dsh-accent', meta.mode === 'dark' ? '#4f7cff' : '#2e6dd6');
  const border = v('--dsh-border', fg3);
  const rules: Array<{ token: string; foreground: string }> = [];
  const add = (token: string, color: string) => { if (color) rules.push({ token, foreground: color }); };
  add('comment', v('--hl-comment', ''));
  add('keyword', v('--hl-keyword', ''));
  add('string', v('--hl-string', ''));
  add('number', v('--hl-number', ''));
  add('type', v('--hl-title', ''));
  add('type.identifier', v('--hl-title', ''));
  add('class', v('--hl-title', ''));
  add('class.identifier', v('--hl-title', ''));
  add('builtin', v('--hl-builtin', ''));
  add('builtin.identifier', v('--hl-builtin', ''));
  add('variable', v('--hl-variable', ''));
  add('meta', v('--hl-meta', ''));
  add('tag', v('--hl-tag', ''));
  add('annotation', v('--hl-meta', ''));
  add('function', v('--hl-builtin', ''));
  add('function.identifier', v('--hl-builtin', ''));
  add('section', v('--hl-section', ''));
  add('string.escape', v('--hl-title', ''));
  add('regexp', v('--hl-string', ''));
  rules.push({ token: 'delimiter', foreground: fg2 });
  rules.push({ token: 'operator', foreground: fg2 });
  monaco.editor.defineTheme('dsh-' + name, {
    base: meta.base,
    inherit: true,
    rules,
    colors: {
      'editor.background': bg0,
      'editor.foreground': fg0,
      'editor.lineHighlightBackground': bg1,
      'editor.lineHighlightBorder': '#00000000',
      'editor.selectionBackground': meta.mode === 'dark' ? '#264f78' : '#add6ff',
      'editor.inactiveSelectionBackground': meta.mode === 'dark' ? '#3a3d41' : '#e5ebf1',
      'editorCursor.foreground': accent,
      'editorLineNumber.foreground': fg3,
      'editorLineNumber.activeForeground': fg0,
      'editorIndentGuide.background1': border,
      'editorIndentGuide.activeBackground1': fg2,
      'editorGutter.background': bg0,
      'editorWidget.background': bg1,
      'editorWidget.border': border,
      'editorSuggestWidget.background': bg1,
      'editorSuggestWidget.border': border,
      'editorSuggestWidget.selectedBackground': meta.mode === 'dark' ? '#04395e' : '#cfe8ff',
      'editorHoverWidget.background': bg1,
      'editorHoverWidget.border': border,
      'editorOverviewRuler.border': '#00000000',
      'scrollbarSlider.background': fg3 + '66',
      'scrollbarSlider.hoverBackground': fg3 + 'aa',
      'editorBracketMatch.background': '#00000000',
      'editorBracketMatch.border': accent,
      'editorWhitespace.foreground': fg3,
    },
  });
  monaco.editor.setTheme('dsh-' + name);
}

/** 创建/复用 Monaco 实例（host 必须可见）。 */
function ensureEditor() {
  if (editor || !monaco || !monacoHost.value) return;
  editor = monaco.editor.create(monacoHost.value, {
    value: currentContent.value,
    language: monacoLangFor(currentPath.value),
    theme: 'dsh-' + currentThemeName(),
    automaticLayout: true,
    fontSize: 13,
    lineHeight: 20,
    fontFamily: "ui-monospace, 'JetBrains Mono', Consolas, monospace",
    tabSize: 2,
    insertSpaces: true,
    minimap: { enabled: false },
    scrollBeyondLastLine: false,
    lineNumbersMinChars: 3,
    folding: true,
    overviewRulerBorder: false,
    scrollbar: { verticalScrollbarSize: 10, horizontalScrollbarSize: 10 },
    padding: { top: 8 },
    renderLineHighlight: 'line',
  });
  // 编辑内容 → currentContent / dirty / 状态栏
  editor.onDidChangeModelContent(() => {
    const v = editor.getValue();
    if (v !== currentContent.value) {
      currentContent.value = v;
      currentLines.value = v.split('\n').length;
      dirty.value = true;
      statusText.value = '编辑中…';
    }
  });
  // Ctrl/Cmd+S 保存
  editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.KeyS, () => { save(); });
}

/** 外部内容/路径变化 → 同步到 Monaco（避免触发自身 change 事件循环）。 */
function syncEditor() {
  if (!editor || !monaco) return;
  if (editor.getValue() !== currentContent.value) {
    editor.setValue(currentContent.value);
  }
  const model = editor.getModel();
  if (model) monaco.editor.setModelLanguage(model, monacoLangFor(currentPath.value));
}

function disposeEditor() {
  if (editor) { editor.dispose(); editor = null; }
}

// ---- 保存 ----
function onKeydown(e: KeyboardEvent) {
  // 全局 Ctrl/Cmd+S（编辑器内已由 Monaco 快捷键处理，这里覆盖非编辑区焦点）
  if ((e.ctrlKey || e.metaKey) && e.key === 's') {
    e.preventDefault();
    save();
  }
}

async function save() {
  if (!currentProject.value || !currentPath.value || !dirty.value) return;
  saving.value = true;
  try {
    await saveCodeFile(currentProject.value, currentPath.value, currentContent.value, props.scene);
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
  if (!name || isSelf.value) return;
  try {
    await createCodeProject(name, props.scene);
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
    await createCodeFile(currentProject.value, path, props.scene);
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

async function openSessionInProject(): Promise<void> {
  const abs = currentWorkspacePath.value;
  if (!abs) {
    notice.value = '无法定位项目目录（未选择项目或缺少 root 信息）';
    return;
  }
  sessionBusy.value = true;
  try {
    const sid = await connectWorkspaceByPath(abs);
    if (sid) {
      pushNotice('已在该目录开会话：' + abs);
      emit('session-opened');
    } else {
      notice.value = '连接工作区失败，请重试';
    }
  } finally {
    sessionBusy.value = false;
  }
}

async function doDeleteCurrent() {
  if (!currentProject.value || !currentPath.value) return;
  if (!window.confirm(`删除 ${currentPath.value}？此操作不可恢复。`)) return;
  try {
    await deleteCodeFile(currentProject.value, currentPath.value, props.scene);
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
  // md 文件 → 渲染 markdown 文档（净化 + 高亮，复用 renderMarkdown）
  if (isMarkdownPath(currentPath.value)) {
    try {
      return renderMarkdown(currentContent.value);
    } catch {
      return '';
    }
  }
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

// ---- 场景切换：coder ↔ self 切换时组件复用（ToolsPage 中 v-else-if 保持挂载），
// 必须监听 scene 重新加载对应场景的项目列表与文件树，否则文件树停留在旧场景 ----
watch(
  () => props.scene,
  async () => {
    // 场景切换 = 切换工作区：清空当前编辑状态与项目选择，按新场景重新加载
    currentProject.value = '';
    currentPath.value = '';
    currentContent.value = '';
    currentLines.value = 0;
    dirty.value = false;
    previewMode.value = false;
    statusText.value = '就绪';
    treeRoot.value = null;
    flatTree.value = [];
    expandedDirs.value = new Set<string>();
    await loadProjects();
  },
);

// ---- 生命周期 ----
let themeObserver: MutationObserver | null = null;

onMounted(() => {
  loadProjects();
  document.addEventListener('keydown', onKeydown);
  // 预热加载 Monaco 内核（不阻塞界面，首次进入编辑即用）
  loadMonaco().catch(() => {});
  // 皮肤切换（data-theme）→ 同步 Monaco 主题
  themeObserver = new MutationObserver(() => {
    applyMonacoTheme();
  });
  themeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] });
});

onBeforeUnmount(() => {
  document.removeEventListener('keydown', onKeydown);
  themeObserver?.disconnect();
  disposeEditor();
});

watch(notice, (v) => {
  if (v) {
    setTimeout(() => { notice.value = ''; }, 4000);
  }
});

// 打开文件 → 创建/同步 Monaco（currentContent 已在同一 tick 内更新）
watch(currentPath, () => {
  if (previewMode.value) return;
  nextTick(async () => {
    try { await loadMonaco(); } catch { return; }
    ensureEditor();
    syncEditor();
    editor?.layout();
  });
});

// 编辑 ↔ 预览切换：进入编辑时确保 Monaco 已创建并重新布局
watch(previewMode, (v) => {
  if (!v && currentPath.value) {
    nextTick(async () => {
      try { await loadMonaco(); } catch { return; }
      ensureEditor();
      syncEditor();
      editor?.layout();
    });
  }
});

defineExpose({});
</script>

<template>
  <div class="code-view" :class="{ embedded: props.embedded }">
    <!-- 顶部工具栏 -->
    <div class="code-toolbar">
      <el-button v-if="!props.embedded" size="small" text class="back-btn" @click="emit('close')" title="返回对话">
        ← 对话
      </el-button>
      <span v-if="isSelf" class="scene-badge" :title="'root: ' + (projects[0]?.displayName || '')">{{ sceneLabel }}</span>
      <el-select
        v-else
        v-model="currentProject"
        size="small"
        class="project-select"
        placeholder="选择项目"
        @change="onProjectChange"
      >
        <el-option v-for="p in projects" :key="p.name" :label="`${p.name}${typeBadge(p.projectType)} (${p.fileCount})`" :value="p.name" />
      </el-select>
      <el-button v-if="!isSelf" size="small" @click="newProjectDialog = true">＋ 项目</el-button>
      <el-button
        size="small"
        type="primary"
        plain
        :loading="sessionBusy"
        :disabled="!currentProject || sessionBusy"
        :title="'在该目录创建/连接工作区会话（工具默认 cwd = 项目目录）'"
        @click="openSessionInProject"
      >
        💬 在此目录开会话
      </el-button>
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
          :class="nodeClass(node)"
          :style="{ paddingLeft: (8 + node.depth * 16) + 'px' }"
          @click="node.type === 'dir' ? toggleDir(node) : openFile(node)"
        >
          <span class="tree-icon">{{ treeIcon(node) }}</span>
          <span class="tree-name" :title="treeTitle(node)">{{ node.name }}</span>
          <span v-if="node.type === 'file' && node.size !== undefined" class="tree-size">{{ node.size }}</span>
        </div>
      </div>

      <!-- 编辑器 -->
      <div class="code-editor">
        <div v-if="!currentPath" class="editor-empty">
          <div class="empty-icon">💻</div>
          <div class="empty-title">{{ isSelf ? '🧬 自我完善' : '在线代码开发' }}</div>
          <div class="empty-sub">{{ isSelf ? '直接浏览 archon-dsh 源码并编辑保存（新建 / 删除可用）' : '选择一个项目与文件开始编辑，或新建项目 / 文件' }}</div>
          <div class="empty-hint">自动识别 Maven/Gradle 源码根（src/main/java、src/test/java、resources…），深层包目录合并为短包名</div>
        </div>
        <template v-else>
          <div class="editor-head">
            <span class="file-path">{{ isSelf ? currentPath : currentProject + ' / ' + currentPath }}</span>
            <el-tag size="small" :type="dirty ? 'warning' : 'success'" effect="plain">
              {{ dirty ? '未保存' : '已保存' }}
            </el-tag>
          </div>
          <div class="editor-body">
            <!-- 编辑模式：Monaco（自带行号/折叠/括号匹配，Monarch 词法高亮） -->
            <div v-show="!previewMode" ref="monacoHost" class="monaco-host" />
            <!-- 预览模式：md → markdown 文档渲染；其他 → highlight.js 高亮 -->
            <div v-if="previewMode" class="editor-preview">
              <div v-if="isMarkdownPath(currentPath)" class="md-doc" v-html="previewHtml"></div>
              <pre v-else><code class="hljs" v-html="previewHtml"></code></pre>
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

.code-view.embedded { border: none; border-radius: 0; }

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

.scene-badge {
  font-size: 12px;
  color: var(--dsh-accent);
  background: var(--dsh-accent-soft);
  border: 1px solid var(--dsh-border);
  border-radius: 6px;
  padding: 2px 10px;
  white-space: nowrap;
}

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
.tree-node.is-source-root > .tree-name { font-weight: 600; }
.tree-node.is-package > .tree-name {
  color: var(--dsh-accent);
  font-family: ui-monospace, 'JetBrains Mono', Consolas, monospace;
}
.tree-node.is-chain > .tree-name { color: var(--dsh-fg-2); font-style: italic; }
.tree-node.is-structural > .tree-name { color: var(--dsh-fg-2); }
.tree-icon { flex: none; }
.tree-name { overflow: hidden; text-overflow: ellipsis; }
.tree-size { margin-left: auto; color: var(--dsh-fg-3); font-size: 11px; }
.empty-hint { font-size: 12px; color: var(--dsh-fg-3); max-width: 420px; text-align: center; }

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
  position: relative; /* Monaco host 定位基准 */
}

/* Monaco 编辑区：绝对定位铺满 editor-body */
.monaco-host {
  position: absolute;
  inset: 0;
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

/* md 文档渲染：内边距 + 代码块间距（全局 .md-doc 样式在 styles.css） */
.editor-preview .md-doc {
  padding: 4px 8px 12px;
}
.editor-preview .md-doc pre {
  margin: 0.8em 0;
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
