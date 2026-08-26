<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import mermaid from 'mermaid';
import { appState, pushNotice } from '../store';
import {
  getPlan, createPlan, updateStepStatus, reviewStep, completePlan, abandonPlan, getStepExecution,
  type PlanView as PlanDetailView, type PlanStep, type StepExecutionView,
} from '../api';

/** DAG 计划视图：步骤状态 + 依赖图（mermaid）+ 下一步可执行提示。
 *  计划由 agent 用 plan_create 建立，或由模型在 plan 模式下提交；本视图用于人类查看与推进。 */

const emit = defineEmits<{ (e: 'continue'): void }>();

const plan = ref<PlanDetailView | null>(null);
const loading = ref(false);
const error = ref('');
const dagSvg = ref('');
const dagError = ref('');

// 新建计划表单
const showCreate = ref(false);
const createTitle = ref('');
const createStepsText = ref('');
const createDepsText = ref('');

let mermaidInitialized = false;

const statusOrder = { pending: 0, in_progress: 1, failed: 2, cancelled: 3, skipped: 4, completed: 5 } as const;

const sortedSteps = computed<PlanStep[]>(() => {
  if (!plan.value) return [];
  return [...plan.value.steps].sort((a, b) => a.seq - b.seq);
});

const nextIds = computed<Set<string>>(() => {
  if (!plan.value) return new Set();
  return new Set(plan.value.nextSteps.map(s => s.id));
});

const progressText = computed(() => {
  if (!plan.value) return '';
  const done = plan.value.steps.filter(s => s.status === 'completed').length;
  return `${done}/${plan.value.steps.length}`;
});

function themeIsDark(): boolean {
  return appState.themeMode === 'dark';
}

function ensureMermaid(): void {
  const dark = themeIsDark();
  const accent = getComputedStyle(document.documentElement).getPropertyValue('--dsh-accent').trim() || '#4f7cff';
  const bg0 = getComputedStyle(document.documentElement).getPropertyValue('--dsh-bg-1').trim() || '#1e1f24';
  const bg2 = getComputedStyle(document.documentElement).getPropertyValue('--dsh-bg-2').trim() || '#26272e';
  const fg0 = getComputedStyle(document.documentElement).getPropertyValue('--dsh-fg-0').trim() || '#e6e6eb';
  const border = getComputedStyle(document.documentElement).getPropertyValue('--dsh-border').trim() || '#33343d';

  mermaid.initialize({
    startOnLoad: false,
    theme: dark ? 'dark' : 'default',
    darkMode: dark,
    themeVariables: {
      background: bg0,
      primaryColor: bg2,
      primaryTextColor: fg0,
      primaryBorderColor: border,
      lineColor: accent,
      fontFamily: '-apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", sans-serif',
      fontSize: '14px',
    },
    // useMaxWidth:false → 图按自然尺寸渲染（不缩到容器宽度），容器横向滚动可看全
    flowchart: { htmlLabels: true, curve: 'basis', useMaxWidth: false, padding: 12 },
  });
  mermaidInitialized = true;
}

function sanitize(label: string): string {
  return label.replace(/[^a-zA-Z0-9_\-\u4e00-\u9fff ]/g, '').slice(0, 40);
}

function stepClass(step: PlanStep): string {
  return step.status === 'completed' ? 'fill:#2ecc71,stroke:#1e8a50,stroke-width:2px,color:#fff'
    : step.status === 'in_progress' ? 'fill:#4f7cff,stroke:#2e5fd6,stroke-width:2px,color:#fff'
    : step.status === 'failed' ? 'fill:#e5484d,stroke:#b33636,stroke-width:2px,color:#fff'
    : step.status === 'cancelled' ? 'fill:#888,stroke:#666,stroke-width:2px,color:#ddd'
    : step.status === 'skipped' ? 'fill:#f5a623,stroke:#c8820f,stroke-width:2px,color:#fff'
    : nextIds.value.has(step.id) ? 'fill:#f5a623,stroke:#c8820f,stroke-width:2px,color:#fff'
    : 'fill:#33343d,stroke:#33343d,stroke-width:2px,color:#e6e6eb';
}

async function renderDag(): Promise<void> {
  if (!plan.value || plan.value.steps.length === 0) return;
  mermaidInitialized = false;
  ensureMermaid();
  dagError.value = '';
  const lines: string[] = ['flowchart TD'];
  for (const step of plan.value.steps) {
    const id = 'st_' + step.id.replace(/[^a-zA-Z0-9_]/g, '_');
    const statusIcon = { pending: '⬜', in_progress: '🔵', completed: '✅', failed: '❌', cancelled: '➖', skipped: '⏭️' }[step.status] || '⬜';
    const opt = step.required ? '' : ' 可选';
    // 工件标记：doc 类（proposal/spec/design/doc）显示类型 + 审阅门状态（未批须批准后才可执行）
    const kindTag = step.kind !== 'task'
      ? ` ${step.kind}${step.reviewed ? '' : '(未批)'}`
      : '';
    const label = sanitize(`${statusIcon} ${step.title}${opt}${kindTag}`);
    lines.push(`  ${id}["${label}"]`);
    lines.push(`  style ${id} ${stepClass(step)}`);
  }
  for (const dep of plan.value.deps) {
    const from = 'st_' + dep.dependsOn.replace(/[^a-zA-Z0-9_]/g, '_');
    const to = 'st_' + dep.step.replace(/[^a-zA-Z0-9_]/g, '_');
    lines.push(`  ${from} --> ${to}`);
  }
  if (plan.value.deps.length === 0) {
    lines.push('  empty["(无依赖，按顺序执行)"]');
  }
  try {
    const id = 'dagplan-' + Date.now();
    const { svg } = await mermaid.render(id, lines.join('\n'));
    // 给每个节点 <g class="... node ... st_<id> ..."> 注入 data-step-id，供点击委托定位步骤
    // 匹配 node 节点组（class 含 node 且含我们的 st_ 前缀 token）
    dagSvg.value = svg.replace(
      /<g class="([^"]*\bnode\b[^"]*\bst_([a-zA-Z0-9_-]+)\b[^"]*)"/g,
      (_m, cls: string, stepId: string) => `<g class="${cls}" data-step-id="${stepId}"`,
    );
  } catch (e) {
    dagError.value = 'DAG 渲染失败: ' + (e as Error).message;
  }
}

async function refresh(): Promise<void> {
  const id = appState.sessionId;
  if (!id) return;
  loading.value = true;
  error.value = '';
  try {
    const view = await getPlan(id);
    plan.value = view.hasPlan ? view : null;
    await renderDag();
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    loading.value = false;
  }
}

async function doCreate(): Promise<void> {
  const id = appState.sessionId;
  if (!id || !createTitle.value.trim()) { pushNotice('计划标题不能为空'); return; }
  let steps: { id: string; title: string; description?: string }[] = [];
  try {
    steps = JSON.parse(createStepsText.value || '[]') as { id: string; title: string; description?: string }[];
  } catch {
    // 每行一个步骤的宽松格式：标题
    steps = createStepsText.value.split('\n').filter(l => l.trim()).map((l, i) => ({
      id: 's' + (i + 1), title: l.trim(),
    }));
  }
  if (steps.length === 0) { pushNotice('至少需要一个步骤'); return; }
  let deps: { step: string; dependsOn: string }[] = [];
  try {
    deps = JSON.parse(createDepsText.value || '[]') as { step: string; dependsOn: string }[];
  } catch {
    // 宽松格式：每行 "s2,s1"（s2 依赖 s1）
    deps = createDepsText.value.split('\n').filter(l => l.trim()).map(l => {
      const [step, dependsOn] = l.split(',').map(x => x.trim());
      return { step, dependsOn };
    });
  }
  try {
    plan.value = await createPlan(id, createTitle.value.trim(), steps, deps);
    showCreate.value = false;
    createTitle.value = '';
    createStepsText.value = '';
    createDepsText.value = '';
    await renderDag();
    pushNotice(`计划已创建（${steps.length} 步骤）`);
  } catch (e) {
    pushNotice('创建计划失败: ' + (e as Error).message);
  }
}

async function doStepStatus(step: PlanStep, status: string): Promise<void> {
  const id = appState.sessionId;
  if (!id || !plan.value) return;
  try {
    plan.value = await updateStepStatus(id, step.id, status, plan.value.plan.id);
    await renderDag();
  } catch (e) {
    pushNotice('更新步骤失败: ' + (e as Error).message);
  }
}

/** 审阅步骤（批准/撤回批准；doc 类步骤须批准后才可执行）。 */
async function doReview(step: PlanStep, reviewed: boolean): Promise<void> {
  const id = appState.sessionId;
  if (!id || !plan.value) return;
  try {
    plan.value = await reviewStep(id, step.id, reviewed, plan.value.plan.id);
    selectedStep.value = plan.value.steps.find(s => s.id === step.id) ?? null;
    await renderDag();
    pushNotice(reviewed ? `已批准「${step.title}」` : `已撤回「${step.title}」的批准`);
  } catch (e) {
    pushNotice('审阅步骤失败: ' + (e as Error).message);
  }
}

/** 工件类型是否需审阅门（doc 类）。 */
function needsReview(step: PlanStep): boolean {
  return step.kind !== 'task';
}

/** 当前选中（点击 DAG 节点）的步骤：详情 + 状态操作。 */
const selectedStep = ref<PlanStep | null>(null);

/** 已执行步骤的操作过程（执行关联表；并行执行各调用独立）。 */
const executionView = ref<StepExecutionView | null>(null);
const executionLoading = ref(false);

/** DAG 图节点点击委托：已执行（终态）节点 → 抽屉展示操作过程；否则弹详情。 */
function onDagNodeClick(e: MouseEvent): void {
  const target = (e.target as HTMLElement).closest?.('[data-step-id]') as HTMLElement | null;
  if (!target || !plan.value) return;
  const stepId = target.getAttribute('data-step-id');
  if (!stepId) return;
  const step = plan.value.steps.find(s => s.id === stepId) ?? null;
  if (!step) return;
  const terminal = ['completed', 'cancelled', 'skipped', 'failed'].includes(step.status);
  if (terminal) {
    void openExecution(step);
  } else {
    selectedStep.value = step;
  }
}

function closeStepDetail(): void {
  selectedStep.value = null;
}

/** 打开步骤执行抽屉：加载该步骤的工具调用序列。 */
async function openExecution(step: PlanStep): Promise<void> {
  const id = appState.sessionId;
  if (!id) return;
  executionView.value = null;
  executionLoading.value = true;
  try {
    executionView.value = await getStepExecution(id, step.id);
  } catch (e) {
    pushNotice('加载执行细节失败: ' + (e as Error).message);
  } finally {
    executionLoading.value = false;
  }
}

function closeExecution(): void {
  executionView.value = null;
}

async function doComplete(): Promise<void> {
  const id = appState.sessionId;
  if (!id || !plan.value) return;
  if (!window.confirm('标记整个计划完成？')) return;
  try {
    plan.value = await completePlan(id, plan.value.plan.id);
    await renderDag();
  } catch (e) {
    pushNotice('完成计划失败: ' + (e as Error).message);
  }
}

async function doAbandon(): Promise<void> {
  const id = appState.sessionId;
  if (!id || !plan.value) return;
  if (!window.confirm('放弃当前计划？')) return;
  try {
    plan.value = await abandonPlan(id, plan.value.plan.id);
    await renderDag();
  } catch (e) {
    pushNotice('放弃计划失败: ' + (e as Error).message);
  }
}

const STEP_ACTIONS: { status: string; label: string; type: 'primary' | 'success' | 'danger' | 'info' | 'warning' }[] = [
  { status: 'in_progress', label: '执行中', type: 'primary' },
  { status: 'completed', label: '完成', type: 'success' },
  { status: 'failed', label: '出错', type: 'danger' },
  { status: 'cancelled', label: '取消', type: 'info' },
  { status: 'skipped', label: '跳过', type: 'warning' },
  { status: 'pending', label: '重置', type: 'info' },
];

watch(() => appState.sessionId, () => { void refresh(); });
watch(() => appState.themeMode, () => { void renderDag(); });

// 离开计划 tab 时关闭步骤详情弹窗与执行抽屉（避免 teleport 弹层叠加在其他视图上 = 共存显示）
watch(() => appState.view, (v) => {
  if (v !== 'plan') {
    closeStepDetail();
    closeExecution();
  }
});

onMounted(() => { void refresh(); });

defineExpose({ refresh });
</script>

<template>
  <div class="dagplan">
    <div v-if="loading" v-loading="true" class="dagplan-loading" />
    <el-alert v-if="error" type="error" :title="error" :closable="false" class="mb" />

    <!-- 无计划：引导创建 -->
    <div v-if="!plan && !loading" class="empty-state">
      <p class="empty-title">还没有 DAG 计划</p>
      <p class="empty-sub">让 agent 用 plan_create 建立（步骤 + 依赖），或在这里手动创建。</p>
      <el-button type="primary" @click="showCreate = true">＋ 新建 DAG 计划</el-button>

      <div v-if="showCreate" class="create-form">
        <el-input v-model="createTitle" placeholder="计划标题" class="mb" />
        <el-input
          v-model="createStepsText" type="textarea" :rows="4"
          placeholder="步骤：每行一个（JSON 数组格式更佳：[{&quot;id&quot;:&quot;s1&quot;,&quot;title&quot;:&quot;...&quot;}]）"
          class="mb"
        />
        <el-input
          v-model="createDepsText" type="textarea" :rows="2"
          placeholder="依赖：JSON 数组 [{&quot;step&quot;:&quot;s2&quot;,&quot;dependsOn&quot;:&quot;s1&quot;}] 或每行 s2,s1"
          class="mb"
        />
        <div class="form-actions">
          <el-button type="success" @click="doCreate">创建</el-button>
          <el-button @click="showCreate = false">取消</el-button>
        </div>
      </div>
    </div>

    <!-- 有计划：状态 + 依赖图 + 步骤列表 -->
    <template v-if="plan">
      <div class="plan-head">
        <div>
          <div class="plan-title">{{ plan.plan.title }}</div>
          <div class="plan-meta">
            <span class="badge" :class="plan.plan.status">{{ plan.plan.status }}</span>
            <span class="progress">进度 {{ progressText }}</span>
            <span class="next-hint" v-if="nextIds.size > 0">下一步可执行：{{ plan.nextSteps.map(s => s.title).join('、') }}</span>
          </div>
        </div>
        <div class="head-actions">
          <el-button
            size="small"
            type="primary"
            :disabled="plan.plan.status !== 'active' || plan.nextSteps.length === 0"
            @click="emit('continue')"
          >🔄 继续执行</el-button>
          <el-button size="small" @click="refresh">刷新</el-button>
          <el-button size="small" type="success" @click="doComplete">完成计划</el-button>
          <el-button size="small" type="danger" plain @click="doAbandon">放弃</el-button>
        </div>
      </div>

      <div v-if="dagError" class="dag-error">{{ dagError }}</div>
      <!-- DAG 图：默认撑满展示；点击节点弹出该步骤详情与操作 -->
      <div
        v-else
        class="dag-canvas"
        v-html="dagSvg"
        @click="onDagNodeClick"
      />
      <div class="dag-tip">💡 点击 DAG 节点查看步骤详情与操作</div>
    </template>
  </div>

  <!-- 步骤详情弹层（点击 DAG 节点触发） -->
  <el-dialog
    :model-value="selectedStep !== null"
    :title="selectedStep ? '步骤 ' + selectedStep.id : ''"
    width="480px"
    @update:model-value="(v: boolean) => { if (!v) closeStepDetail() }"
    @close="closeStepDetail"
  >
    <template v-if="selectedStep">
      <div class="step-detail">
        <div class="step-detail-title">
          <span class="step-status-icon">
            {{ { pending: '⬜', in_progress: '🔵', completed: '✅', failed: '❌', cancelled: '➖', skipped: '⏭️' }[selectedStep.status] }}
          </span>
          <b>{{ selectedStep.title }}</b>
          <span class="step-status" :class="selectedStep.status">{{ selectedStep.status }}</span>
          <span v-if="nextIds.has(selectedStep.id) && selectedStep.status !== 'completed'" class="step-next">下一步</span>
          <span v-if="!selectedStep.required" class="step-optional">可选</span>
        </div>
        <div class="step-detail-meta">
          <span class="step-kind" :class="selectedStep.kind">类型: {{ selectedStep.kind }}</span>
          <span v-if="needsReview(selectedStep)" class="step-review" :class="selectedStep.reviewed ? 'approved' : 'pending'">
            {{ selectedStep.reviewed ? '✅ 已批准' : '⛔ 未批（批准后才可执行）' }}
          </span>
        </div>
        <div v-if="selectedStep.description" class="step-detail-desc">{{ selectedStep.description }}</div>
        <div class="step-detail-actions">
          <el-button
            v-if="needsReview(selectedStep) && !selectedStep.reviewed"
            type="success" @click="doReview(selectedStep, true)"
          >批准审阅</el-button>
          <el-button
            v-if="needsReview(selectedStep) && selectedStep.reviewed"
            type="warning" plain @click="doReview(selectedStep, false)"
          >撤回批准</el-button>
          <el-button
            v-for="a in STEP_ACTIONS" :key="a.status"
            :type="a.type" plain :disabled="selectedStep.status === a.status"
            @click="doStepStatus(selectedStep, a.status)"
          >{{ a.label }}</el-button>
        </div>
      </div>
    </template>
  </el-dialog>

  <!-- 已执行步骤：操作过程抽屉（执行关联表；并行执行各调用独立展示） -->
  <el-drawer
    :model-value="executionView !== null"
    :title="executionView ? '🔧 步骤执行：' + executionView.title : ''"
    size="46%"
    @update:model-value="(v: boolean) => { if (!v) closeExecution() }"
    @closed="closeExecution"
  >
    <div v-loading="executionLoading" class="execution-drawer">
      <el-alert v-if="executionView?.error" type="error" :title="executionView.error" :closable="false" />
      <template v-if="executionView && !executionView.error">
        <div class="execution-head">
          <span class="step-status" :class="executionView.status">{{ executionView.status }}</span>
          <span class="execution-count">{{ executionView.calls.length }} 次工具调用</span>
        </div>
        <div v-if="executionView.calls.length === 0" class="execution-empty">该步骤没有关联的工具调用（可能是模型直接输出）。</div>
        <div v-for="(c, i) in executionView.calls" :key="i" class="execution-call" :class="c.status">
          <div class="execution-line">
            <span class="execution-icon">{{ c.status === 'ok' ? '✓' : '✗' }}</span>
            <code class="execution-tool">{{ c.tool }}</code>
            <span class="execution-status">{{ c.status }}</span>
            <span class="execution-time">{{ new Date(c.at).toLocaleTimeString() }}</span>
          </div>
          <div v-if="c.args" class="execution-args">{{ c.args }}</div>
        </div>
      </template>
    </div>
  </el-drawer>
</template>

<style scoped>
/* 根不滚动：高度由 dag-canvas 独占滚动，避免双层滚动条 */
.dagplan {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 16px 20px;
  overflow: hidden;
  min-height: 0;
}
.mb { margin-bottom: 8px; }
.dagplan-loading { min-height: 120px; }
.empty-state { text-align: center; padding: 60px 0; color: var(--dsh-fg-2); }
.empty-title { font-size: 16px; color: var(--dsh-fg-0); margin-bottom: 6px; }
.empty-sub { font-size: 13px; margin-bottom: 16px; }
.create-form { max-width: 560px; margin: 20px auto 0; text-align: left; background: var(--dsh-bg-2); border: 1px solid var(--dsh-border); border-radius: 12px; padding: 16px; }
.form-actions { display: flex; gap: 8px; justify-content: flex-end; }

.plan-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; }
.plan-title { font-size: 16px; font-weight: 600; color: var(--dsh-fg-0); }
.plan-meta { display: flex; align-items: center; gap: 10px; margin-top: 6px; font-size: 12px; color: var(--dsh-fg-2); }
.badge { padding: 2px 10px; border-radius: 10px; background: var(--dsh-bg-3); }
.badge.active { color: var(--dsh-accent); }
.badge.completed { color: #2ecc71; }
.badge.abandoned { color: var(--dsh-fg-2); }
.next-hint { color: var(--dsh-accent); }
.head-actions { display: flex; gap: 6px; flex-shrink: 0; }

/* DAG 图：撑满展示（自然尺寸渲染，可滚动看全；步骤清单默认不显示） */
.dag-canvas {
  background: var(--dsh-bg-2);
  border: 1px solid var(--dsh-border);
  border-radius: 12px;
  padding: 24px;
  overflow-x: auto;
  overflow-y: auto;
  flex: 1;
  min-height: 480px;
  display: flex;
  align-items: flex-start;
  justify-content: center;
  cursor: pointer;
}
.dag-canvas :deep(svg) {
  max-width: none;
  height: auto;
}
.dag-canvas :deep(.node:hover) { filter: brightness(1.15); }
.dag-tip { font-size: 12px; color: var(--dsh-fg-2); text-align: center; padding-bottom: 4px; }
.dag-error { color: #e5484d; font-size: 13px; padding: 8px; }

/* 步骤详情弹层 */
.step-detail { display: flex; flex-direction: column; gap: 12px; }
.step-detail-title { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; font-size: 14px; color: var(--dsh-fg-0); }
.step-detail-desc { font-size: 13px; color: var(--dsh-fg-2); line-height: 1.6; }
.step-detail-actions { display: flex; gap: 8px; flex-wrap: wrap; }
.step-status-icon { font-size: 16px; }
.step-status { font-size: 11px; padding: 1px 8px; border-radius: 10px; background: var(--dsh-bg-3); color: var(--dsh-fg-2); }
.step-status.completed { color: #2ecc71; }
.step-status.failed { color: #e5484d; }
.step-status.cancelled { color: var(--dsh-fg-2); }
.step-status.skipped { color: #f5a623; }
.step-status.in_progress { color: var(--dsh-accent); }
.step-next { font-size: 11px; color: #f5a623; }
.step-optional { font-size: 11px; color: #f5a623; border: 1px solid #f5a623; border-radius: 8px; padding: 0 6px; }

/* 工件类型 + 审阅门状态 */
.step-detail-meta { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.step-kind { font-size: 11px; padding: 1px 8px; border-radius: 10px; background: var(--dsh-bg-3); color: var(--dsh-fg-2); }
.step-kind.spec { color: var(--dsh-accent); }
.step-kind.proposal { color: #8a5cf6; }
.step-kind.design { color: #f5a623; }
.step-kind.doc { color: var(--dsh-fg-2); }
.step-review { font-size: 11px; padding: 1px 8px; border-radius: 10px; }
.step-review.pending { color: #e5484d; background: rgba(229, 72, 77, .12); }
.step-review.approved { color: #2ecc71; background: rgba(46, 204, 113, .12); }

/* 已执行步骤操作过程抽屉 */
.execution-drawer { display: flex; flex-direction: column; gap: 8px; min-height: 200px; }
.execution-head { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; }
.execution-count { font-size: 12px; color: var(--dsh-fg-2); }
.execution-empty { text-align: center; color: var(--dsh-fg-2); padding: 30px 0; font-size: 13px; }
.execution-call {
  border: 1px solid var(--dsh-border); border-radius: 8px;
  padding: 8px 10px; background: var(--dsh-bg-2);
}
.execution-call.failed { border-left: 3px solid #e5484d; }
.execution-line { display: flex; align-items: center; gap: 8px; font-size: 12.5px; }
.execution-icon { color: #2ecc71; font-weight: 700; }
.execution-call.failed .execution-icon { color: #e5484d; }
.execution-tool {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  color: var(--dsh-accent); background: var(--dsh-bg-3);
  padding: 1px 6px; border-radius: 4px;
}
.execution-status { font-size: 11px; padding: 0 6px; border-radius: 8px; background: var(--dsh-bg-3); color: var(--dsh-fg-2); }
.execution-call.failed .execution-status { color: #e5484d; }
.execution-time { margin-left: auto; font-size: 11px; color: var(--dsh-fg-2); }
.execution-args {
  margin-top: 6px; font-size: 12px; color: var(--dsh-fg-2);
  background: var(--dsh-code-bg); border-radius: 6px; padding: 4px 8px;
  white-space: pre-wrap; word-break: break-word; max-height: 120px; overflow-y: auto;
}

</style>
