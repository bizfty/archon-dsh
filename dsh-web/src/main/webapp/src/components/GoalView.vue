<script setup lang="ts">
// 目标视图（Element Plus）：创建 / 查看 / 更新（CAS）。
import { reactive, watch } from 'vue';
import { appState } from '../store';

const emit = defineEmits<{
  (e: 'create', objective: string, maxGoalRounds?: number): void;
  (e: 'update', action: string, extra?: Record<string, unknown>): void;
}>();

const form = reactive({ objective: '', maxRounds: 20 });

// 目标变化 → 同步表单（创建/更新后回显）
watch(
  () => appState.goal,
  (g) => {
    if (g) {
      form.objective = g.objective;
      form.maxRounds = g.maxGoalRounds;
    }
  },
  { immediate: true },
);

function create(): void {
  const objective = form.objective.trim();
  if (!objective) return;
  emit('create', objective, form.maxRounds > 0 ? form.maxRounds : undefined);
}

function edit(): void {
  emit('update', 'edit', {
    objective: form.objective.trim() || undefined,
    maxGoalRounds: form.maxRounds > 0 ? form.maxRounds : undefined,
  });
}

function blocked(): void {
  const reason = window.prompt('阻塞原因（blocked reason）:');
  if (reason) emit('update', 'blocked', { blockedCode: 'manual', blockedReason: reason });
}

const phaseTag = (p: string): 'success' | 'warning' | 'info' | 'danger' => {
  switch (p) {
    case 'active': return 'success';
    case 'paused': return 'warning';
    case 'blocked': return 'danger';
    default: return 'info';
  }
};
</script>

<template>
  <div class="goal">
    <h1>🎯 会话目标</h1>
    <p class="desc">持久化 same-session 目标：create / update（CAS）</p>

    <el-empty v-if="!appState.goal" description="当前会话还没有目标">
      <el-form label-position="top" class="form" @submit.prevent>
        <el-form-item label="目标文本">
          <el-input v-model="form.objective" placeholder="具体完成目标" />
        </el-form-item>
        <el-form-item label="轮数上限（可选）">
          <el-input-number v-model="form.maxRounds" :min="1" :step="1" />
        </el-form-item>
        <el-button type="primary" @click="create">创建目标</el-button>
      </el-form>
    </el-empty>

    <div v-else class="card">
      <div class="status">
        <strong>{{ appState.goal.objective }}</strong>
        <div class="meta">
          <el-tag :type="phaseTag(appState.goal.phase)" size="small">{{ appState.goal.phase }}</el-tag>
          <span>rounds: {{ appState.goal.roundsStarted }}/{{ appState.goal.maxGoalRounds }}</span>
          <span>revision: {{ appState.goal.revision }}</span>
          <span class="dim">id: {{ appState.goal.id }}</span>
        </div>
        <el-alert v-if="appState.goal.blockedReason" type="error" :closable="false" class="blocked"
          :title="`blocked: ${appState.goal.blockedReason}`" />
      </div>
      <el-form label-position="top" class="form" @submit.prevent>
        <el-form-item label="目标文本">
          <el-input v-model="form.objective" />
        </el-form-item>
        <el-form-item label="轮数上限">
          <el-input-number v-model="form.maxRounds" :min="1" :step="1" />
        </el-form-item>
      </el-form>
      <div class="actions">
        <el-button type="primary" @click="edit">保存修改</el-button>
        <el-button v-if="appState.goal.phase !== 'complete'" type="success" @click="emit('update', 'complete')">完成</el-button>
        <el-button v-if="appState.goal.phase !== 'paused'" @click="emit('update', 'pause')">暂停</el-button>
        <el-button v-else type="success" @click="emit('update', 'resume')">恢复</el-button>
        <el-button type="danger" @click="blocked">标记阻塞</el-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.goal { padding: 30px 24px; max-width: 720px; margin: 0 auto; width: 100%; }
h1 { font-size: 22px; margin: 0 0 6px; }
.desc { color: #9a9ba6; font-size: 13px; margin-bottom: 24px; }
.form { background: #26272e; border: 1px solid #33343d; border-radius: 12px; padding: 18px; }
.card { background: #26272e; border: 1px solid #33343d; border-radius: 12px; padding: 20px; }
.status { margin-bottom: 16px; }
.meta { display: flex; gap: 12px; align-items: center; margin-top: 8px; color: #9a9ba6; font-size: 12px; }
.blocked { margin-top: 10px; }
.actions { display: flex; gap: 10px; flex-wrap: wrap; margin-top: 16px; }
</style>
