<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, nextTick, watch } from 'vue';
import { listSkills, type SkillInfo } from '../api';

const props = defineProps<{
  visible: boolean;
  anchorEl?: HTMLElement | null;
}>();

const emit = defineEmits<{
  (e: 'close'): void;
  (e: 'select', skill: SkillInfo): void;
}>();

const skills = ref<SkillInfo[]>([]);
const loading = ref(false);
const search = ref('');
const activeIndex = ref(0);
const containerRef = ref<HTMLElement | null>(null);
const posStyle = ref<Record<string, string>>({});
const mountReady = ref(false);
const ignoredEl = ref<HTMLElement | null>(null);

const filtered = computed(() => {
  const q = search.value.toLowerCase().trim();
  if (!q) return skills.value;
  return skills.value.filter(s =>
    s.name.toLowerCase().includes(q) ||
    (s.description?.toLowerCase().includes(q) ?? false)
  );
});

watch(() => props.visible, (v) => {
  if (v && skills.value.length === 0) {
    void loadSkills();
  }
  if (v) {
    ignoredEl.value = props.anchorEl || null;
    search.value = '';
    activeIndex.value = 0;
    nextTick(() => updatePosition());
  } else {
    ignoredEl.value = null;
  }
});

function updatePosition(): void {
  if (props.anchorEl) {
    const rect = props.anchorEl.getBoundingClientRect();
    posStyle.value = {
      position: 'fixed',
      bottom: `${window.innerHeight - rect.top + 4}px`,
      left: `${rect.left}px`,
      minWidth: `${rect.width}px`,
    };
  } else {
    const composer = document.querySelector('.composer') as HTMLElement | null;
    if (composer) {
      const r = composer.getBoundingClientRect();
      posStyle.value = {
        position: 'fixed',
        bottom: `${window.innerHeight - r.top + 4}px`,
        left: `${r.left + 20}px`,
        minWidth: `${Math.min(r.width - 40, 400)}px`,
        maxWidth: '480px',
      };
    } else {
      posStyle.value = {
        position: 'fixed',
        bottom: '80px',
        left: '50%',
        transform: 'translateX(-50%)',
      };
    }
  }
}

async function loadSkills(): Promise<void> {
  loading.value = true;
  try {
    skills.value = await listSkills();
  } catch {
    skills.value = [];
  } finally {
    loading.value = false;
  }
}

function onKeyDown(e: KeyboardEvent): void {
  if (e.key === 'ArrowDown') {
    e.preventDefault();
    activeIndex.value = Math.min(activeIndex.value + 1, filtered.value.length - 1);
  } else if (e.key === 'ArrowUp') {
    e.preventDefault();
    activeIndex.value = Math.max(activeIndex.value - 1, 0);
  } else if (e.key === 'Enter') {
    e.preventDefault();
    const skill = filtered.value[activeIndex.value];
    if (skill) select(skill);
  } else if (e.key === 'Escape') {
    e.preventDefault();
    emit('close');
  }
}

function select(skill: SkillInfo): void {
  emit('select', skill);
  emit('close');
}

function onRowClick(idx: number): void {
  const skill = filtered.value[idx];
  if (skill) select(skill);
}

function onRowHover(idx: number): void {
  activeIndex.value = idx;
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
  nextTick(() => updatePosition());
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
      class="skill-picker"
      :style="posStyle"
      tabindex="-1"
      @keydown="onKeyDown"
    >
      <div class="search-box">
        <input
          v-model="search"
          class="search-input"
          type="text"
          placeholder="搜索技能..."
          autofocus
        />
      </div>
      <div class="list">
        <template v-if="loading">
          <div class="empty">加载技能列表...</div>
        </template>
        <template v-else-if="filtered.length === 0">
          <div class="empty">暂无可用技能</div>
        </template>
        <template v-else>
          <div
            v-for="(skill, idx) in filtered"
            :key="skill.name"
            class="row"
            :class="{ active: idx === activeIndex }"
            @click="onRowClick(idx)"
            @mouseenter="onRowHover(idx)"
          >
            <span class="icon">⚡</span>
            <div class="info">
              <div class="name">{{ skill.name }}</div>
              <div v-if="skill.description" class="desc">{{ skill.description }}</div>
            </div>
            <span v-if="skill.tools" class="tools-chip">{{ skill.tools }}</span>
          </div>
        </template>
      </div>
      <div class="footer">
        <span>↑↓ 选择 · Enter 确认 · Esc 取消</span>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.skill-picker {
  z-index: 1000;
  background: var(--dsh-bg-2);
  border: 1px solid var(--dsh-border);
  border-radius: 12px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.2);
  overflow: hidden;
  display: flex;
  flex-direction: column;
  max-height: 380px;
  outline: none;
}
.search-box {
  padding: 10px 12px;
  border-bottom: 1px solid var(--dsh-border);
}
.search-input {
  width: 100%;
  box-sizing: border-box;
  padding: 8px 12px;
  background: var(--dsh-bg-0);
  border: 1px solid var(--dsh-border);
  border-radius: 8px;
  color: var(--dsh-fg-0);
  font-size: 13px;
  outline: none;
  transition: border-color .15s;
  font-family: inherit;
}
.search-input:focus { border-color: var(--dsh-accent); }
.list {
  flex: 1;
  overflow-y: auto;
  padding: 6px;
  min-height: 100px;
}
.row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  border-radius: 8px;
  cursor: pointer;
  transition: background-color .1s;
}
.row:hover, .row.active {
  background: var(--dsh-accent-soft);
}
.icon { font-size: 16px; width: 20px; text-align: center; flex-shrink: 0; }
.info { flex: 1; min-width: 0; }
.name { font-size: 13px; font-weight: 500; color: var(--dsh-fg-0); }
.desc { font-size: 12px; color: var(--dsh-fg-2); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.tools-chip {
  font-size: 11px;
  padding: 2px 6px;
  border-radius: 4px;
  background: var(--dsh-code-bg);
  color: var(--dsh-fg-2);
  flex-shrink: 0;
}
.empty {
  padding: 32px;
  text-align: center;
  color: var(--dsh-fg-2);
  font-size: 13px;
}
.footer {
  padding: 6px 12px;
  border-top: 1px solid var(--dsh-border);
  font-size: 11px;
  color: var(--dsh-fg-2);
  text-align: center;
}
</style>