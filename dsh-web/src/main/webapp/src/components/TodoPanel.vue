<script setup lang="ts">
// 待办计划面板（对齐官方 web 的 TodoPanel/plan strip）：
// 模型调用 todo_write 后，会话头部下方渲染常驻计划清单与执行进度。
// 展示：完成/进行中/待办计数 + 逐项状态（对齐官方 snapshot：
//   panel=1 completed · 2 in progress · 1 pending
//   item=completed 梳理需求 ...）
import { computed } from 'vue';
import { appState } from '../store';
import type { TodosView } from '../api';

const props = defineProps<{ todos: TodosView }>();

const summary = computed(() => {
  const t = props.todos;
  return `${t.completed}/${t.total} completed`;
});

const countLine = computed(() => {
  const t = props.todos;
  const parts: string[] = [];
  if (t.completed > 0) parts.push(`${t.completed} completed`);
  if (t.inProgress > 0) parts.push(`${t.inProgress} in progress`);
  if (t.pending > 0) parts.push(`${t.pending} pending`);
  return parts.join(' · ');
});

const statusIcon = (s: string): string => {
  switch (s) {
    case 'completed': return '✅';
    case 'in_progress': return '🔄';
    case 'failed': return '❌';
    case 'cancelled': return '➖';
    case 'skipped': return '⏭️';
    default: return '⬜';
  }
};
</script>

<template>
  <div v-if="todos.hasTodos" class="todo-panel" data-testid="todo-panel">
    <div class="todo-head">
      <span class="todo-title">📋 计划</span>
      <span class="todo-summary">{{ summary }}</span>
      <span class="todo-count">{{ countLine }}</span>
    </div>
    <div class="todo-items">
      <div
        v-for="(item, i) in todos.items"
        :key="i"
        class="todo-item"
        :class="item.status"
        :title="item.description || ''"
      >
        <span class="todo-icon">{{ statusIcon(item.status) }}</span>
        <span class="todo-status">{{ item.status }}</span>
        <span class="todo-text">{{ item.title }}</span>
        <span v-if="!item.required" class="todo-optional" title="可选（可跳过）">可选</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.todo-panel {
  background: var(--dsh-bg-2);
  border: 1px solid var(--dsh-border);
  border-radius: 12px;
  padding: 10px 14px;
  flex-shrink: 0;
}
.todo-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  font-size: 12px;
  color: var(--dsh-fg-2);
}
.todo-title { font-weight: 600; color: var(--dsh-fg-0); font-size: 13px; }
.todo-summary { color: var(--dsh-accent); }
.todo-count { color: var(--dsh-fg-2); }
.todo-items {
  display: flex;
  flex-direction: column;
  gap: 3px;
  margin-top: 8px;
}
.todo-item {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  padding: 3px 6px;
  border-radius: 6px;
  color: var(--dsh-fg-0);
}
.todo-item.completed { color: var(--dsh-fg-2); text-decoration: line-through; }
.todo-item.in_progress { background: var(--dsh-accent-soft); }
.todo-icon { font-size: 12px; flex-shrink: 0; }
.todo-status {
  font-size: 11px;
  padding: 0 6px;
  border-radius: 8px;
  background: var(--dsh-bg-3);
  color: var(--dsh-fg-2);
  flex-shrink: 0;
  text-decoration: none;
}
.todo-item.completed .todo-status { color: #2ecc71; }
.todo-item.in_progress .todo-status { color: var(--dsh-accent); }
.todo-text { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.todo-optional {
  flex-shrink: 0; font-size: 10px; padding: 0 6px; border-radius: 8px;
  background: rgba(245, 166, 35, .15); color: #f5a623;
}
.todo-item.failed .todo-status { color: #e5484d; }
</style>
