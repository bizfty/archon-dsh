<script setup lang="ts">
// ToolsPage.vue — 侧边栏「工具」菜单的独立页面壳。
// 每个工具（MCP / 技能 / 定时任务 / 专家套件 / 代码开发 / 自我完善）都是独立页面：
// 保留全局侧边栏，右侧内容区为独立页面布局（无 main 的页签 / 对话输入 dock）。
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { appState, pushNotice } from '../store';
import { listSkills, listJobs, killJob, type SkillInfo } from '../api';
import CodeView from './CodeView.vue';
import FloatingChat from './FloatingChat.vue';

const props = defineProps<{ view: string }>();
const chatRef = ref<InstanceType<typeof FloatingChat> | null>(null);
const emit = defineEmits<{ (e: 'back'): void }>();

/** 工具页面元数据（标题栏展示）。 */
const TOOL_META: Record<string, { icon: string; title: string; desc: string }> = {
  mcp:    { icon: '🔗', title: 'MCP',          desc: '模型上下文协议（Model Context Protocol）工具市场，规划中，敬请期待。' },
  skills: { icon: '⚙️', title: '技能',         desc: '技能（Skill）列表，可在对话中通过 /skill 或 + 按钮调用' },
  jobs:   { icon: '⏰', title: '定时任务',     desc: '当前会话的后台任务 · 每 3s 自动刷新 · 重启后清空（进程内存）' },
  expert: { icon: '🧩', title: '专家套件',     desc: '领域专家技能组合包，规划中，敬请期待。' },
  coder:  { icon: '💻', title: '代码开发',     desc: '在线代码开发：项目 + 文件树 + 编辑器' },
  self:   { icon: '🧬', title: '自我完善',     desc: '直接浏览 archon-dsh 源码并编辑保存（新建 / 删除可用）' },
};
const meta = computed(() => TOOL_META[props.view] || TOOL_META.mcp);
const isCoder = computed(() => props.view === 'coder' || props.view === 'self');
const isPlaceholder = computed(() => props.view === 'mcp' || props.view === 'expert');

// ---- 技能（⚙️ 技能页）----
const toolSkills = ref<SkillInfo[]>([]);
const toolSkillsLoading = ref(false);
const toolSkillsError = ref<string | null>(null);

async function loadToolSkills(): Promise<void> {
  if (toolSkills.value.length > 0 || toolSkillsLoading.value) return;
  toolSkillsLoading.value = true;
  toolSkillsError.value = null;
  try {
    toolSkills.value = await listSkills();
  } catch (e) {
    toolSkillsError.value = (e as Error).message;
  } finally {
    toolSkillsLoading.value = false;
  }
}

// ---- 定时任务（⏰ 后台任务列表）----
let jobsTimer: number | null = null;

const jobs = computed(() => appState.jobs);
const jobsPhase = computed(() => appState.jobsPhase);
const jobsError = computed(() => appState.jobsError);

async function refreshJobs(): Promise<void> {
  const id = appState.sessionId;
  if (!id) return;
  try {
    appState.jobs = await listJobs(id);
    appState.jobsPhase = 'ready';
    appState.jobsError = null;
  } catch (e) {
    appState.jobsError = (e as Error).message;
  }
}

function startJobsPolling(): void {
  stopJobsPolling();
  jobsTimer = window.setInterval(() => void refreshJobs(), 3000);
}

function stopJobsPolling(): void {
  if (jobsTimer !== null) {
    window.clearInterval(jobsTimer);
    jobsTimer = null;
  }
}

async function doKillJob(jobId: string): Promise<void> {
  const id = appState.sessionId;
  if (!id) return;
  try {
    await killJob(id, jobId);
    await refreshJobs();
  } catch (e) {
    pushNotice('终止任务失败: ' + (e as Error).message);
  }
}

function fmtDuration(ms: number): string {
  if (ms < 1000) return ms + 'ms';
  const s = Math.floor(ms / 1000);
  if (s < 60) return s + 's';
  const m = Math.floor(s / 60);
  const rest = s % 60;
  return rest === 0 ? m + 'm' : `${m}m ${rest}s`;
}

/** 任务状态徽标文案（对齐官方 running/done/failed/killed）。 */
function jobStatusText(j: { status: string }): string {
  switch (j.status) {
    case 'running': return 'running';
    case 'done': return 'done';
    case 'failed': return 'failed';
    case 'killed': return 'killed';
    default: return j.status;
  }
}

// ---- 视图切换副作用：进入定时任务页启动轮询，离开停止；进入技能页加载一次 ----
watch(
  () => props.view,
  (v) => {
    if (v === 'jobs') {
      void refreshJobs();
      startJobsPolling();
    } else if (v === 'skills') {
      void loadToolSkills();
    } else {
      stopJobsPolling();
    }
  },
  { immediate: true },
);

onBeforeUnmount(() => stopJobsPolling());
</script>

<template>
  <main class="tool-page">
    <header class="tool-page-header">
      <div class="tool-page-title">
        <span class="tool-page-icon">{{ meta.icon }}</span>
        <b>{{ meta.title }}</b>
        <span class="tool-page-desc">{{ meta.desc }}</span>
      </div>
      <el-button v-if="isCoder" size="small" text class="tool-back" @click="chatRef?.toggle()" title="打开/收起浮动对话（边看代码边对话）">💬 对话</el-button>
      <el-button v-else size="small" text class="tool-back" @click="emit('back')" title="返回对话">← 返回对话</el-button>
    </header>

    <div class="tool-page-body">
      <!-- ⚙️ 技能 -->
      <template v-if="props.view === 'skills'">
        <div class="tools-view-head">
          <b>⚙️ 技能（Skill）</b>
          <span class="jobs-hint">可在对话中通过 /skill 或 + 按钮调用</span>
          <el-button size="small" @click="loadToolSkills">刷新</el-button>
        </div>
        <el-alert v-if="toolSkillsError" type="error" :title="toolSkillsError" :closable="false" />
        <div v-loading="toolSkillsLoading" class="skill-grid">
          <div v-for="sk in toolSkills" :key="sk.name" class="skill-card">
            <div class="skill-name">⚙️ {{ sk.name }}</div>
            <div class="skill-desc">{{ sk.description }}</div>
            <div class="skill-tools" v-if="sk.tools"><code>{{ sk.tools }}</code></div>
          </div>
          <div v-if="toolSkills.length === 0 && !toolSkillsLoading && !toolSkillsError" class="tools-empty">
            暂无可用技能
          </div>
        </div>
      </template>

      <!-- ⏰ 定时任务（后台任务列表） -->
      <template v-else-if="props.view === 'jobs'">
        <div class="tools-view-head">
          <b>⏰ 定时任务（当前会话）</b>
          <span class="jobs-hint">每 3s 自动刷新 · 重启后清空（进程内存）</span>
          <el-button size="small" @click="refreshJobs">刷新</el-button>
        </div>
        <el-alert v-if="jobsError" type="error" :title="jobsError" :closable="false" />
        <div v-if="jobs.length === 0 && jobsPhase === 'ready'" class="jobs-empty">暂无后台任务</div>
        <div v-loading="jobsPhase === 'pending'" class="jobs-view-list">
          <div v-for="j in jobs" :key="j.id" class="job-item" :class="j.status">
            <div class="job-line">
              <span class="job-kind">{{ j.kind }}</span>
              <code class="job-cmd" :title="j.command">{{ j.command }}</code>
              <span class="job-status">{{ jobStatusText(j) }}</span>
              <span class="job-duration">{{ fmtDuration(j.durationMs) }}</span>
            </div>
            <div class="job-meta" v-if="j.status !== 'running' && j.exitCode !== null">
              exit {{ j.exitCode }}
            </div>
            <div class="job-actions" v-if="j.status === 'running'">
              <el-button size="small" type="danger" plain @click="doKillJob(j.id)">终止</el-button>
            </div>
          </div>
        </div>
      </template>

      <!-- 💻 代码开发 / 🧬 自我完善（内嵌 CodeView，占满页面） -->
      <CodeView
        v-else-if="isCoder"
        :scene="props.view === 'self' ? 'self' : 'coder'"
        embedded
        @close="emit('back')"
      />
      <!-- 浮动对话窗：代码开发 / 自我完善页内嵌，替代“返回对话” -->
      <FloatingChat v-if="isCoder" ref="chatRef" />

      <!-- 🔗 MCP / 🧩 专家套件（规划中占位） -->
      <div v-else-if="isPlaceholder" class="tool-placeholder">
        <div class="placeholder-icon">{{ meta.icon }}</div>
        <div class="placeholder-title">{{ meta.title }}</div>
        <div class="placeholder-desc">{{ meta.desc }}</div>
      </div>

      <div v-else class="tools-empty">未知工具</div>
    </div>
  </main>
</template>

<style scoped>
/* 独立页面壳：占满右侧内容区（无 main 页签 / 对话输入 dock） */
.tool-page {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  background: var(--dsh-bg-1);
}

.tool-page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 20px;
  border-bottom: 1px solid var(--dsh-border);
  background: var(--dsh-bg-0);
  flex-shrink: 0;
}

.tool-page-title {
  display: flex;
  align-items: baseline;
  gap: 8px;
  min-width: 0;
}

.tool-page-title b { font-size: 16px; color: var(--dsh-fg-0); white-space: nowrap; }

.tool-page-icon { font-size: 16px; }

.tool-page-desc {
  font-size: 12px;
  color: var(--dsh-fg-2);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tool-back { flex-shrink: 0; color: var(--dsh-fg-2); }

.tool-page-body {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 16px 20px;
  overflow-y: auto;
  min-height: 0;
}

/* CodeView 占满剩余高度（自身 embedded 无边框） */
.tool-page-body :deep(.code-view) { flex: 1; min-height: 0; }

.tools-view-head { display: flex; align-items: center; gap: 10px; font-size: 14px; color: var(--dsh-fg-0); }
.jobs-hint { font-size: 11px; color: var(--dsh-fg-2); }
.jobs-empty { text-align: center; color: var(--dsh-fg-2); padding: 16px 0; font-size: 12px; }
.jobs-view-list { display: flex; flex-direction: column; gap: 8px; }

.skill-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: 10px; }
.skill-card {
  border: 1px solid var(--dsh-border); border-radius: 10px; padding: 12px 14px;
  background: var(--dsh-bg-2); display: flex; flex-direction: column; gap: 6px;
  transition: border-color .15s, box-shadow .15s;
}
.skill-card:hover { border-color: var(--dsh-accent); box-shadow: 0 2px 12px rgba(0,0,0,.08); }
.skill-name { font-size: 14px; font-weight: 600; color: var(--dsh-fg-0); }
.skill-desc { font-size: 12px; color: var(--dsh-fg-2); line-height: 1.5; }
.skill-tools code { font-size: 11px; color: var(--dsh-accent); background: var(--dsh-bg-3); padding: 2px 6px; border-radius: 4px; }
.tools-empty { color: var(--dsh-fg-2); font-size: 13px; padding: 24px; text-align: center; }

/* 后台任务项 */
.job-item { border: 1px solid var(--dsh-border); border-radius: 8px; padding: 8px 10px; background: var(--dsh-bg-2); }
.job-item.running { border-color: var(--dsh-accent); }
.job-line { display: flex; align-items: center; gap: 8px; font-size: 12px; }
.job-kind { color: var(--dsh-fg-2); font-weight: 600; flex-shrink: 0; }
.job-cmd { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--dsh-fg-0); font-size: 12px; }
.job-status { flex-shrink: 0; font-size: 11px; padding: 1px 8px; border-radius: 10px; background: var(--dsh-bg-3); color: var(--dsh-fg-2); }
.job-item.running .job-status { background: var(--dsh-accent-soft); color: var(--dsh-accent); }
.job-item.done .job-status { background: rgba(46, 204, 113, .15); color: #2ecc71; }
.job-item.failed .job-status { background: rgba(229, 72, 77, .15); color: #e5484d; }
.job-item.killed .job-status { background: rgba(245, 166, 35, .15); color: #f5a623; }
.job-duration { flex-shrink: 0; color: var(--dsh-fg-2); font-size: 11px; }
.job-meta { margin-top: 4px; font-size: 11px; color: var(--dsh-fg-2); }
.job-actions { margin-top: 6px; text-align: right; }

/* MCP / 专家套件占位 */
.tool-placeholder {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  gap: 8px;
}
.placeholder-icon { font-size: 48px; }
.placeholder-title { font-size: 18px; font-weight: 600; color: var(--dsh-fg-0); }
.placeholder-desc { font-size: 13px; color: var(--dsh-fg-2); max-width: 420px; }
</style>
