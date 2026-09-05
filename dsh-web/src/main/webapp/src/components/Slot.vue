<script setup lang="ts">
/**
 * Slot.vue —— 插槽化宿主渲染器（design-client-vue-l3-slots.md §4）。
 *
 * 按注册表 + 当前选中键渲染单个视图模块：
 *   <Slot :registry="viewRegistry" :active="appState.view" @back="goChat" />
 *
 * - 组合式注入：组件不背状态，渲染目标由调用方传 active；宿主/父壳持注册表。
 * - 仅渲染「匹配 active 的模块」；无匹配渲染 null（调用方自决空态）。
 * - 透传 $attrs（含 emit）给目标组件 —— 业务动作留在宿主（owner），经 props/emit 注入。
 */
import { computed } from 'vue';
import type { Registry } from '../registry';

const props = defineProps<{
  registry: Registry;
  active?: string;
}>();

const module = computed(() => props.registry.get(props.active ?? ''));
</script>

<template>
  <component :is="module?.component" v-if="module" v-bind="$attrs" />
</template>
