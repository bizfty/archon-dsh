<script setup lang="ts">
// PlanTasksPage.vue — 工具「计划任务」工作台。
// 批量提交多个任务 → 入队；本工作区常驻 Worker 空闲自动认领并并行执行（子代理）；
// 需确认的问题以清单呈现（任务详情内 + 顶部全局汇总），用户回答后任务继续。
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { appState, workspaceLabel } from '../store';
import {
  listPlanTasks, submitPlanTasks, killPlanTask, rerunPlanTask,
  pendingPlanTaskQuestions, answerQuestion, recoverPlanTask, getPlanTaskWorkers,
  type PlanTaskView, type PlanTaskStatus, type PlanTaskQuestion,
  type PlanTaskLiveness, type PlanTaskWorkers,
} from '../api';

// ---- 工作区选择（缺省最近工作区）----
const workspaces = computed(() => appState.workspaces);
const wsId = ref<string>(appState.recentWorkspaceId ?? workspaces.value[0]?.id ?? '');
watch(
  () => [appState.recentWorkspaceId, appState.workspaces] as const,
  () => {
    if (!wsId.value || !workspaces.value.some((w) => w.id === wsId.value)) {
      const fallback = appState.workspaces.find((w) => w.id === appState.recentWorkspaceId)
        ?? appState.workspaces[0];
      if (fallback) wsId.value = fallback.id;
    }
  },
  { immediate: true },
);
const wsLabel = computed(() => {
  const w = appState.workspaces.find((x) => x.id === wsId.value);
  return w ? workspaceLabel(w) : wsId.value;
});

// ---- 任务池 ----
const tasks = ref<PlanTaskView[]>([]);
const loading = ref(false);
const error = ref<string | null>(null);
const workers = ref<PlanTaskWorkers | null>(null);
let timer: number | null = null;

async function refresh(): Promise<void> {
  if (!wsId.value) return;
  loading.value = true;
  error.value = null;
  try {
    tasks.value = await listPlanTasks(wsId.value);
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    loading.value = false;
  }
  try { workers.value = await getPlanTaskWorkers(wsId.value); }
  catch { /* 概览失败静默 */ }
}

function startPolling(): void {
  stopPolling();
  if (!wsId.value) return;
  void refresh();
  timer = window.setInterval(() => { if (wsId.value) void refresh(); }, 2500);
}
function stopPolling(): void {
  if (timer !== null) { window.clearInterval(timer); timer = null; }
}
watch(wsId, () => startPolling(), { immediate: true });
onBeforeUnmount(stopPolling);

// ---- 批量提交 ----
const draft = ref('');
const submitting = ref(false);
async function submit(): Promise<void> {
  const lines = draft.value.split('\n').map((s) => s.trim()).filter(Boolean);
  if (lines.length === 0 || !wsId.value) { ElMessage.warning('请至少输入一个任务'); return; }
  submitting.value = true;
  try {
    const res = await submitPlanTasks(wsId.value, lines);
    ElMessage.success(`已提交 ${res.tasks.length} 个任务`);
    draft.value = '';
    await refresh();
  } catch (e) {
    ElMessage.error('提交失败: ' + (e as Error).message);
  } finally {
    submitting.value = false;
  }
}

// ---- 任务操作 ----
async function doKill(t: PlanTaskView): Promise<void> {
  try { await killPlanTask(wsId.value, t.id); await refresh(); }
  catch (e) { ElMessage.error('终止失败: ' + (e as Error).message); }
}
async function doRerun(t: PlanTaskView): Promise<void> {
  try { await rerunPlanTask(wsId.value, t.id); await refresh(); }
  catch (e) { ElMessage.error('重跑失败: ' + (e as Error).message); }
}
async function doRecover(t: PlanTaskView): Promise<void> {
  try {
    const res = await recoverPlanTask(wsId.value, t.id);
    if (res.ok) { ElMessage.success('已回收，等待重新认领'); await refresh(); }
    else { ElMessage.warning(res.error ?? '回收被拒绝'); await refresh(); }
  } catch (e) { ElMessage.error('回收失败: ' + (e as Error).message); }
}

// ---- 待确认问题（任务级 + 全局汇总）----
const questions = ref<PlanTaskQuestion[]>([]);
const questionDrawerOpen = ref(false);
const drawerMode = ref<'global' | 'task'>('global');
const drawerTaskId = ref<string | null>(null);

const STATUS_TEXT: Record<PlanTaskStatus, string> = {
  queued: '排队中', claimed: '已认领', running: '执行中',
  waiting_question: '需确认', done: '已完成', failed: '失败', killed: '已终止',
};

async function loadQuestions(): Promise<void> {
  if (!wsId.value) return;
  try { questions.value = await pendingPlanTaskQuestions(wsId.value); }
  catch { /* 静默 */ }
}

/** 任务是否有待确认问题（据此显示「需确认」提示）。 */
function taskWaiting(t: PlanTaskView): boolean {
  return questions.value.some((q) => q.task_id === t.id && t.status === 'running');
}

function openGlobalQuestions(): void {
  drawerMode.value = 'global';
  drawerTaskId.value = null;
  questionDrawerOpen.value = true;
  void loadQuestions();
}
function openTaskQuestions(t: PlanTaskView): void {
  drawerMode.value = 'task';
  drawerTaskId.value = t.id;
  questionDrawerOpen.value = true;
  void loadQuestions();
}
const drawerQuestions = computed(() =>
  drawerMode.value === 'task' && drawerTaskId.value
    ? questions.value.filter((q) => q.task_id === drawerTaskId.value)
    : questions.value,
);

// ---- 回答问题 ----
const answers = ref<Record<string, string>>({});
function qKey(q: PlanTaskQuestion): string { return q.question_id; }
async function doAnswer(q: PlanTaskQuestion): Promise<void> {
  const answer = answers.value[qKey(q)] ?? '';
  if (!answer.trim()) { ElMessage.warning('请填写回答'); return; }
  try {
    await answerQuestion(q.question_id, answer.trim());
    ElMessage.success('已回答');
    delete answers.value[qKey(q)];
    await loadQuestions();
    await refresh();
  } catch (e) {
    ElMessage.error('回答失败: ' + (e as Error).message);
  }
}

function fmtTime(ms: number): string {
  if (!ms) return '-';
  return new Date(ms).toLocaleTimeString('zh-CN', { hour12: false });
}
function statusClass(s: string): string {
  return ['done', 'failed', 'killed', 'running', 'waiting_question', 'queued', 'claimed'].includes(s) ? s : '';
}

// ---- 运行态（liveness）----
const LIVENESS_TEXT: Record<PlanTaskLiveness, string> = {
  alive: '运行中', stale: '疑似卡死', lost: '失联', 'n/a': '',
};
const LIVENESS_CLASS: Record<PlanTaskLiveness, string> = {
  alive: 'lv-alive', stale: 'lv-stale', lost: 'lv-lost', 'n/a': '',
};
/** 仅活跃态任务展示 liveness 徽标。 */
function showLiveness(t: PlanTaskView): boolean {
  return ['claimed', 'running', 'waiting_question'].includes(t.status)
    && t.liveness !== 'n/a';
}
/** 失联/疑似卡死才给「回收」按钮。 */
function canRecover(t: PlanTaskView): boolean {
  return showLiveness(t) && (t.liveness === 'lost' || t.liveness === 'stale');
}
function fmtRelative(ms: number): string {
  if (!ms) return '-';
  const sec = Math.max(0, Math.round((Date.now() - ms) / 1000));
  if (sec < 60) return `${sec}s 前`;
  if (sec < 3600) return `${Math.floor(sec / 60)}m 前`;
  return `${Math.floor(sec / 3600)}h 前`;
}
function fmtDuration(ms: number): string {
  if (!ms || ms < 0) return '-';
  const sec = Math.round(ms / 1000);
  if (sec < 60) return `${sec}s`;
  if (sec < 3600) return `${Math.floor(sec / 60)}m${sec % 60}s`;
  return `${Math.floor(sec / 3600)}h${Math.floor((sec % 3600) / 60)}m`;
}
</script>

<template>
  <div class="plantasks">
    <!-- 工具栏 -->
    <div class="pt-toolbar">
      <el-select v-model="wsId" size="small" style="width: 200px" filterable placeholder="选择工作区">
        <el-option v-for="w in workspaces" :key="w.id" :value="w.id"
          :label="workspaceLabel(w)" />
      </el-select>
      <span class="pt-toolbar-hint">归属该工作区的 Worker 自动认领并并行执行</span>
      <span v-if="workers" class="pt-workers" :title="'运行中: ' + (workers.runningTaskIds.join(', ') || '无')">
        Worker {{ workers.inFlight }}/{{ workers.concurrency }} 执行中
      </span>
      <span style="flex: 1"></span>
      <el-button size="small" @click="refresh">刷新</el-button>
      <el-badge :value="questions.length" :hidden="questions.length === 0" type="warning">
        <el-button size="small" type="warning" plain @click="openGlobalQuestions">待确认问题</el-button>
      </el-badge>
    </div>

    <!-- 批量提交 -->
    <el-card shadow="never" class="pt-card">
      <template #header>
        <div class="pt-card-head">
          <b>批量提交任务</b>
          <span class="pt-hint">每行一个任务（可选粘贴多条）</span>
        </div>
      </template>
      <el-input v-model="draft" type="textarea" :rows="4" resize="vertical"
        placeholder="每行输入一个任务指令…&#10;例：&#10;为 README 补充部署说明&#10;重构 xxx 模块并补单测" />
      <div class="pt-actions">
        <el-button type="primary" :loading="submitting" :disabled="!draft.trim() || !wsId"
          @click="submit">提交</el-button>
      </div>
    </el-card>

    <!-- 任务池 -->
    <el-card shadow="never" class="pt-card">
      <template #header>
        <div class="pt-card-head">
          <b>任务池 · {{ wsLabel }}</b>
          <span class="pt-hint">每 2.5s 自动刷新</span>
        </div>
      </template>
      <el-alert v-if="error" type="error" :title="error" :closable="false" class="pt-mb" />
      <div v-loading="loading">
        <div v-if="tasks.length === 0 && !loading" class="pt-empty">暂无任务。提交一批任务后，Worker 会在此领取并执行。</div>
        <div v-for="t in tasks" :key="t.id" class="pt-task" :class="statusClass(t.status)">
          <div class="pt-task-top">
            <code class="pt-id">{{ t.id }}</code>
            <span class="pt-status" :class="statusClass(t.status)">
              {{ STATUS_TEXT[t.status] }}
              <span v-if="t.status === 'running' && taskWaiting(t)" class="pt-waiting-tag">· 待确认</span>
            </span>
            <span v-if="showLiveness(t)" class="pt-liveness" :class="LIVENESS_CLASS[t.liveness]">
              {{ LIVENESS_TEXT[t.liveness] }}
            </span>
            <span v-if="t.attempt > 1" class="pt-attempt" :title="'重跑次数'">×{{ t.attempt }}</span>
          </div>
          <div class="pt-prompt" :title="t.prompt">{{ t.prompt }}</div>
          <div class="pt-meta">
            <template v-if="t.claimedBy">领取者 {{ t.claimedBy }} · </template>
            <template v-if="t.claimedAt">领取 {{ fmtTime(t.claimedAt) }} · </template>
            <template v-if="t.finishedAt">结束 {{ fmtTime(t.finishedAt) }}</template>
          </div>
          <div v-if="showLiveness(t)" class="pt-runtime">
            <span v-if="t.elapsedMs">耗时 {{ fmtDuration(t.elapsedMs) }}</span>
            <span v-if="t.lastActivityAt"> · 最后活动 {{ fmtRelative(t.lastActivityAt) }}</span>
            <span v-if="t.lastHeartbeatAt"> · 心跳 {{ fmtRelative(t.lastHeartbeatAt) }}</span>
          </div>
          <div v-if="showLiveness(t) && t.activity" class="pt-activity">▶ {{ t.activity }}</div>
          <div v-if="t.result" class="pt-result">{{ t.result }}</div>
          <div v-if="t.error" class="pt-error">{{ t.error }}</div>
          <div class="pt-task-ops">
            <el-button v-if="t.status === 'running' && taskWaiting(t)" size="small" type="warning"
              plain @click="openTaskQuestions(t)">待确认问题</el-button>
            <el-button v-if="canRecover(t)" size="small" type="warning"
              plain @click="doRecover(t)">回收重排</el-button>
            <el-button v-if="!['done', 'failed', 'killed'].includes(t.status)" size="small" type="danger"
              plain @click="doKill(t)">终止</el-button>
            <el-button v-if="['done', 'failed', 'killed'].includes(t.status)" size="small"
              @click="doRerun(t)">重跑</el-button>
          </div>
        </div>
      </div>
    </el-card>

    <!-- 待确认问题抽屉（任务级 / 全局汇总共用） -->
    <el-drawer v-model="questionDrawerOpen" :title="drawerMode === 'task' ? '任务待确认问题' : '全局待确认问题汇总'"
      size="420px">
      <div v-if="drawerQuestions.length === 0" class="pt-empty">暂无待确认问题 🎉</div>
      <div v-for="q in drawerQuestions" :key="q.question_id" class="pt-qitem">
        <div class="pt-qprompt" v-if="drawerMode === 'global'">任务：{{ q.prompt }}</div>
        <div class="pt-qask">{{ q.question }}</div>
        <div v-if="q.options && q.options.length" class="pt-qopts">
          <el-radio-group v-model="answers[qKey(q)]">
            <el-radio v-for="op in q.options" :key="op" :label="op">{{ op }}</el-radio>
          </el-radio-group>
        </div>
        <el-input v-else v-model="answers[qKey(q)]" type="textarea" :rows="2"
          placeholder="输入你的回答…" />
        <div class="pt-actions">
          <el-button size="small" type="primary" @click="doAnswer(q)">回答并继续</el-button>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.plantasks { display: flex; flex-direction: column; gap: 12px; }
.pt-toolbar { display: flex; align-items: center; gap: 10px; }
.pt-toolbar-hint { font-size: 11px; color: var(--dsh-fg-2); }
.pt-card { border-radius: 10px; }
.pt-card-head { display: flex; align-items: baseline; gap: 8px; }
.pt-card-head b { font-size: 14px; color: var(--dsh-fg-0); }
.pt-hint { font-size: 11px; color: var(--dsh-fg-2); }
.pt-actions { margin-top: 10px; text-align: right; }
.pt-mb { margin-bottom: 10px; }
.pt-empty { color: var(--dsh-fg-2); font-size: 12px; text-align: center; padding: 16px 0; }

.pt-task { border: 1px solid var(--dsh-border); border-radius: 8px; padding: 8px 10px; margin-bottom: 8px; background: var(--dsh-bg-2); }
.pt-task.running, .pt-task.waiting_question { border-color: var(--dsh-accent); }
.pt-task-top { display: flex; align-items: center; gap: 8px; }
.pt-id { font-size: 11px; color: var(--dsh-fg-2); }
.pt-status { font-size: 11px; padding: 1px 8px; border-radius: 10px; background: var(--dsh-bg-3); color: var(--dsh-fg-2); }
.pt-status.running { background: var(--dsh-accent-soft); color: var(--dsh-accent); }
.pt-status.done { background: rgba(46, 204, 113, .15); color: #2ecc71; }
.pt-status.failed { background: rgba(229, 72, 77, .15); color: #e5484d; }
.pt-status.killed { background: rgba(245, 166, 35, .15); color: #f5a623; }
.pt-status.waiting_question { background: rgba(245, 166, 35, .2); color: #d97b10; }
.pt-waiting-tag { font-weight: 600; }
.pt-workers { font-size: 11px; color: var(--dsh-fg-2); padding: 1px 8px; border-radius: 10px; background: var(--dsh-bg-3); }
.pt-liveness { font-size: 11px; padding: 1px 8px; border-radius: 10px; }
.pt-liveness.lv-alive { background: rgba(46, 204, 113, .15); color: #2ecc71; }
.pt-liveness.lv-stale { background: rgba(245, 166, 35, .18); color: #d97b10; }
.pt-liveness.lv-lost { background: rgba(229, 72, 77, .15); color: #e5484d; }
.pt-runtime { font-size: 11px; color: var(--dsh-fg-2); margin-top: 2px; }
.pt-activity { font-size: 12px; color: var(--dsh-accent); margin-top: 3px; word-break: break-word; }
.pt-attempt { font-size: 11px; color: var(--dsh-fg-2); }
.pt-prompt { font-size: 13px; color: var(--dsh-fg-0); margin: 4px 0; word-break: break-word; }
.pt-meta { font-size: 11px; color: var(--dsh-fg-2); }
.pt-result { margin-top: 4px; font-size: 12px; color: #2ecc71; white-space: pre-wrap; word-break: break-word; }
.pt-error { margin-top: 4px; font-size: 12px; color: #e5484d; word-break: break-word; }
.pt-task-ops { margin-top: 6px; text-align: right; }

.pt-qitem { border: 1px solid var(--dsh-border); border-radius: 8px; padding: 10px; margin-bottom: 10px; }
.pt-qprompt { font-size: 11px; color: var(--dsh-fg-2); margin-bottom: 4px; }
.pt-qask { font-size: 13px; color: var(--dsh-fg-0); margin-bottom: 6px; }
.pt-qopts { margin-bottom: 6px; }
</style>
