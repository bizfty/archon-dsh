<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, nextTick, watch } from 'vue';
import { appState } from '../store';

const props = defineProps<{
  visible: boolean;
  anchorEl?: HTMLElement | null;
}>();

const emit = defineEmits<{
  (e: 'close'): void;
  (e: 'select', modelId: string): void;
}>();

const containerRef = ref<HTMLElement | null>(null);
const posStyle = ref<Record<string, string>>({});
const mountReady = ref(false);
const ignoredEl = ref<HTMLElement | null>(null);

const groupedModels = computed(() => {
  const groups = new Map<string, typeof appState.models>();
  for (const m of appState.models) {
    if (!groups.has(m.group)) groups.set(m.group, []);
    groups.get(m.group)!.push(m);
  }
  const result = [...groups.entries()];
  console.log('[ModelPicker] groupedModels 计算完成，分组数:', result.length,
    result.map(([g, ms]) => `${g}(${ms.length})`).join(', '));
  return result;
});

watch(
  () => appState.models,
  (models) => {
    if (models.length > 0) {
      console.log('[ModelPicker] 初始化模型清单:', models);
    }
  },
  { immediate: true },
);

watch(
  () => groupedModels.value,
  (g) => {
    if (g && g.length > 0 && props.visible) {
      nextTick(() => {
        const rows = containerRef.value?.querySelectorAll('.option')?.length ?? 0;
        console.log('[ModelPicker] 模板已渲染，DOM 行数:', rows);
      });
    }
  },
);

const currentModel = computed(() =>
  appState.models.find(m => m.id === appState.model),
);

function updatePosition(): void {
  if (props.anchorEl) {
    const rect = props.anchorEl.getBoundingClientRect();
    posStyle.value = {
      position: 'fixed',
      bottom: `${window.innerHeight - rect.top + 4}px`,
      right: `${window.innerWidth - rect.right}px`,
      minWidth: `${rect.width}px`,
    };
  } else {
    posStyle.value = {
      position: 'fixed',
      bottom: '80px',
      right: '20px',
    };
  }
}

watch(() => props.visible, (v) => {
  if (v) {
    ignoredEl.value = props.anchorEl || null;
    nextTick(() => updatePosition());
  } else {
    ignoredEl.value = null;
  }
});

function onRowClick(id: string): void {
  emit('select', id);
  emit('close');
}

function onDocPointerDown(e: PointerEvent): void {
  const target = e.target as Node;
  if (ignoredEl.value && ignoredEl.value.contains(target)) return;
  if (containerRef.value && !containerRef.value.contains(target)) {
    emit('close');
  }
}

function onWindowResize(): void {
  if (props.visible) updatePosition();
}

onMounted(() => {
  document.addEventListener('pointerdown', onDocPointerDown, true);
  window.addEventListener('resize', onWindowResize);
  mountReady.value = true;
});

onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', onDocPointerDown, true);
  window.removeEventListener('resize', onWindowResize);
});
</script>

<template>
  <Teleport to="body" :disabled="false">
    <div
      v-if="visible && mountReady"
      ref="containerRef"
      class="model-picker"
      :style="posStyle"
    >
      <template v-if="appState.modelsPhase === 'pending'">
        <div class="empty">加载模型列表...</div>
      </template>
      <template v-else-if="appState.modelsError">
        <div class="empty">⚠️ {{ appState.modelsError }}</div>
      </template>
      <template v-else-if="groupedModels.length === 0">
        <div class="empty">暂无可用模型</div>
      </template>
      <template v-else>
        <div class="list">
          <div v-for="[group, models] in groupedModels" :key="group" class="group">
            <div class="group-title">{{ group }}</div>
            <button
              v-for="m in models"
              :key="m.id"
              class="option"
              :class="{ selected: m.id === appState.model }"
              @click="onRowClick(m.id)"
            >
              <span class="option-name">{{ m.name }}</span>
              <span v-if="m.id === appState.model" class="check">✓</span>
            </button>
          </div>
        </div>
      </template>
    </div>
  </Teleport>
</template>

<style scoped>
.model-picker {
  z-index: 1000;
  width: 280px;
  background: var(--dsh-bg-2);
  border: 1px solid var(--dsh-border);
  border-radius: 12px;
  box-shadow: 0 -4px 24px rgba(0, 0, 0, 0.15);
  overflow: hidden;
  display: flex;
  flex-direction: column;
  max-height: 320px;
  padding: 8px;
}
.list {
  flex: 1;
  overflow-y: auto;
  padding: 0;
  text-align: left;
}
.group { margin-bottom: 8px; text-align: left; }
.group:last-child { margin-bottom: 0; }
.group-title {
  font-size: 11px; color: var(--dsh-fg-2);
  padding: 4px 8px; text-transform: uppercase;
  letter-spacing: 0.5px;
  text-align: left;
}
.option {
  width: 100%;
  display: flex; align-items: center; justify-content: space-between;
  padding: 7px 10px 7px 20px; border-radius: 8px;
  background: none; border: none; color: var(--dsh-fg-0);
  cursor: pointer; font-size: 13px; font-family: inherit;
  transition: background-color .1s;
  text-align: left;
}
.option:hover { background: var(--dsh-accent-soft); }
.option.selected { color: var(--dsh-accent); font-weight: 500; }
.option-name { flex: 1; text-align: left; }
.check { color: var(--dsh-accent); font-size: 13px; flex-shrink: 0; }
.empty {
  padding: 28px 12px;
  text-align: center;
  color: var(--dsh-fg-2);
  font-size: 13px;
}
</style>