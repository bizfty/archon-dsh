<script setup lang="ts">
/**
 * PlanBody.vue —— main 槽「计划」body 模块（B/L3 拆槽：原 App.vue plan 视图内联区抽出）。
 *
 * 自包含：计划模式状态/动作经 planMode.ts（planState + togglePlanModeBackend/doSubmitPlan），
 * DAG 由 PlanView 自取。宿主(MainShell)无需为 plan 注入任何 handler。
 */
import { appState } from '../store';
import { planState, togglePlanModeBackend, doSubmitPlan } from '../planMode';
import PlanView from './PlanView.vue';
</script>

<template>
  <div class="plan-body">
    <div class="plan-toolbar">
      <el-button
        size="small"
        :type="appState.planMode ? 'warning' : 'primary'"
        :loading="planState.busy"
        @click="togglePlanModeBackend"
      >
        {{ appState.planMode ? '退出计划模式' : '进入计划模式' }}
      </el-button>
      <el-button size="small" type="success" :loading="planState.busy" @click="doSubmitPlan">提交文本计划</el-button>
    </div>
    <el-alert
      v-if="appState.planMode"
      type="warning"
      :closable="false"
      show-icon
      title="计划模式已激活：agent 只规划，不实现代码/不改文件"
    />
    <el-collapse class="plan-text-collapse">
      <el-collapse-item title="文本计划（markdown，兼容旧流程）">
        <el-input
          v-model="planState.text"
          type="textarea"
          :rows="8"
          placeholder="计划内容（markdown）。进入计划模式后让 agent 调研并规划，或用 exit_plan_mode 提交。"
        />
      </el-collapse-item>
    </el-collapse>
    <div class="plan-dag-title">📊 DAG 计划（步骤 + 依赖，按依赖顺序执行）</div>
    <PlanView />
  </div>
</template>

<style scoped>
.plan-body { flex: 1; display: flex; flex-direction: column; gap: 10px; padding: 16px 20px; overflow: hidden; min-height: 0; }
.plan-toolbar { display: flex; gap: 8px; align-items: center; }
.plan-body :deep(.el-textarea__inner) { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 13px; line-height: 1.6; }
.plan-text-collapse { border: 1px solid var(--dsh-border); border-radius: 10px; }
.plan-text-collapse :deep(.el-collapse-item__header) { background: var(--dsh-bg-2); color: var(--dsh-fg-0); font-size: 13px; }
.plan-text-collapse :deep(.el-collapse-item__content) { background: var(--dsh-bg-2); }
.plan-dag-title { font-size: 13px; color: var(--dsh-fg-2); margin-top: 4px; }
</style>
