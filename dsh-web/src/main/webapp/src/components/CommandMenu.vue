<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, nextTick } from 'vue';

export interface CommandItem {
  id: string;
  label: string;
  detail?: string;
  icon: string;
  group?: string;
  action: () => void;
}

const props = defineProps<{
  commands: CommandItem[];
}>();

const emit = defineEmits<{
  (e: 'close'): void;
}>();

const search = ref('');
const activeIndex = ref(0);
const containerRef = ref<HTMLElement | null>(null);
const searchRef = ref<HTMLInputElement | null>(null);

const filtered = computed(() => {
  const q = search.value.toLowerCase().trim();
  if (!q) return props.commands;
  return props.commands.filter(c =>
    c.label.toLowerCase().includes(q) || (c.detail?.toLowerCase().includes(q) ?? false),
  );
});

const groupedFiltered = computed<Record<string, CommandItem[]>>(() => {
  const result: Record<string, CommandItem[]> = {};
  for (const cmd of filtered.value) {
    const key = cmd.group || '';
    if (!result[key]) result[key] = [];
    result[key].push(cmd);
  }
  return result;
});

function flatIndex(groups: Record<string, CommandItem[]>, groupKey: string, innerIdx: number): number {
  let idx = 0;
  for (const [k, v] of Object.entries(groups)) {
    if (k === groupKey) return idx + innerIdx;
    idx += v.length;
  }
  return 0;
}

function onKeyDown(e: KeyboardEvent): void {
  if (e.key === 'ArrowDown') {
    e.preventDefault();
    activeIndex.value = Math.min(activeIndex.value + 1, filtered.value.length - 1);
    scrollToActive();
  } else if (e.key === 'ArrowUp') {
    e.preventDefault();
    activeIndex.value = Math.max(activeIndex.value - 1, 0);
    scrollToActive();
  } else if (e.key === 'Enter') {
    e.preventDefault();
    const cmd = filtered.value[activeIndex.value];
    if (cmd) selectCommand(cmd);
  } else if (e.key === 'Escape') {
    e.preventDefault();
    emit('close');
  }
}

function scrollToActive(): void {
  nextTick(() => {
    const el = containerRef.value?.querySelector(`[data-idx="${activeIndex.value}"]`) as HTMLElement | null;
    el?.scrollIntoView({ block: 'nearest' });
  });
}

function selectCommand(cmd: CommandItem): void {
  cmd.action();
  emit('close');
}

function onRowClick(idx: number): void {
  const cmd = filtered.value[idx];
  if (cmd) selectCommand(cmd);
}

function onRowHover(idx: number): void {
  activeIndex.value = idx;
}

function onDocPointerDown(e: PointerEvent): void {
  if (containerRef.value && !containerRef.value.contains(e.target as Node)) {
    emit('close');
  }
}

onMounted(() => {
  document.addEventListener('pointerdown', onDocPointerDown, true);
  nextTick(() => {
    searchRef.value?.focus();
  });
});

onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', onDocPointerDown, true);
});
</script>

<template>
  <div class="command-menu" ref="containerRef">
    <div class="search-box">
      <input
        ref="searchRef"
        v-model="search"
        class="search-input"
        type="text"
        placeholder="搜索命令..."
        @keydown="onKeyDown"
      />
    </div>
    <div class="list">
      <template v-for="(group, groupKey) in groupedFiltered" :key="groupKey">
        <div v-if="groupKey" class="group-title">{{ groupKey }}</div>
        <div
          v-for="(cmd, idx) in group"
          :key="cmd.id"
          class="row"
          :class="{ active: flatIndex(groupedFiltered, groupKey, idx) === activeIndex }"
          :data-idx="flatIndex(groupedFiltered, groupKey, idx)"
          @click="onRowClick(flatIndex(groupedFiltered, groupKey, idx))"
          @mouseenter="onRowHover(flatIndex(groupedFiltered, groupKey, idx))"
        >
          <span class="icon">{{ cmd.icon }}</span>
          <span class="label">{{ cmd.label }}</span>
          <span v-if="cmd.detail" class="detail">{{ cmd.detail }}</span>
        </div>
      </template>
      <div v-if="filtered.length === 0" class="empty">无匹配命令</div>
    </div>
  </div>
</template>

<style scoped>
.command-menu {
  position: absolute;
  bottom: calc(100% + 8px);
  left: 0;
  width: 360px;
  background: var(--dsh-bg-2);
  border: 1px solid var(--dsh-border);
  border-radius: 12px;
  box-shadow: 0 -4px 24px rgba(0, 0, 0, 0.15);
  z-index: 100;
  overflow: hidden;
  display: flex;
  flex-direction: column;
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
  max-height: 260px;
  overflow-y: auto;
  padding: 6px;
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
.icon { font-size: 16px; width: 20px; text-align: center; }
.label { font-size: 13px; color: var(--dsh-fg-0); flex: 1; }
.detail { font-size: 12px; color: var(--dsh-fg-2); }
.group-title {
  padding: 8px 12px 4px;
  font-size: 11px;
  font-weight: 600;
  color: var(--dsh-fg-2);
  text-transform: uppercase;
  letter-spacing: 0.5px;
  border-top: 1px solid var(--dsh-border);
  margin-top: 4px;
}
.group-title:first-child { border-top: none; margin-top: 0; }
.empty {
  padding: 24px;
  text-align: center;
  color: var(--dsh-fg-2);
  font-size: 13px;
}
</style>