/**
 * planMode.ts —— 计划模式状态与动作（B/L3 拆槽：App.vue 计划逻辑下沉）。
 *
 * 原内联于 App.vue（planText/planBusy/togglePlanModeBackend/doSubmitPlan/refreshPlan）。
 * 独立为组合式注入模块：MainShell（计划 toolbar）与 sessionActs（openSession 同步）共用。
 * appState.planMode 已在 store；本模块补 planText/planBusy + 动作。
 */
import { reactive } from 'vue';
import { appState, setPlanMode, pushNotice } from './store';
import { getPlanMode, enterPlanMode, exitPlanMode, submitPlanMode } from './api';

export const planState = reactive({
  /** 已提交的计划文本（供展示与继续执行参考）。 */
  text: '',
  /** 计划提交/切换是否进行中。 */
  busy: false,
});

/** 打开会话时同步后端计划状态。 */
export async function refreshPlanState(sessionId: string): Promise<void> {
  try {
    const state = await getPlanMode(sessionId);
    setPlanMode(state.active);
    planState.text = state.planText;
  } catch {
    /* 计划状态同步失败不阻塞 */
  }
}

/** 切换计划模式：真实调用后端 enter/exit，成功后更新本地。 */
export async function togglePlanModeBackend(): Promise<void> {
  const id = appState.sessionId;
  if (!id || planState.busy) return;
  planState.busy = true;
  try {
    if (appState.planMode) {
      await exitPlanMode(id);
      setPlanMode(false);
      pushNotice('已退出计划模式，可开始执行');
    } else {
      await enterPlanMode(id);
      setPlanMode(true);
      pushNotice('已进入计划模式：只规划，不实现');
    }
  } catch (e) {
    pushNotice('切换计划模式失败: ' + (e as Error).message);
  } finally {
    planState.busy = false;
  }
}

/** 人类提交/更新计划（保存计划文本并退出计划模式）。 */
export async function doSubmitPlan(): Promise<void> {
  const id = appState.sessionId;
  const text = planState.text.trim();
  if (!id) return;
  if (!text) { pushNotice('计划内容不能为空'); return; }
  planState.busy = true;
  try {
    const state = await submitPlanMode(id, text);
    setPlanMode(false);
    planState.text = state.planText;
    pushNotice('计划已提交，退出计划模式');
  } catch (e) {
    pushNotice('提交计划失败: ' + (e as Error).message);
  } finally {
    planState.busy = false;
  }
}
