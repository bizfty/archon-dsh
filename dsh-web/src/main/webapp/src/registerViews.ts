/**
 * registerViews.ts —— module 级视图注册（B/L3：App.vue 拆槽后的装配来源）。
 *
 * 副作用注册到应用级 viewRegistry 单例（组合式注入：store reactive 单例 appState 共享）。
 * main.ts 首行 import 本模块即完成装配；App.vue / MainShell 只按注册表遍历渲染。
 *
 * 新增「槽/视图」无需改 App.vue —— 在此 register 后自动出现：
 *   - shell: 'main'      → MainShell 的 nav.tabs 与 body 视图（tab:true 才有 tab）
 *   - shell: 'standalone'→ App.vue 顶层按 view 分发（settings / 各工具页）
 */
import { viewRegistry } from './registry';
import { appState } from './store';
import { refreshPlanState } from './planMode';
import { refreshGoal, switchTrajectory } from './sessionActs';
import MsgView from './components/MsgView.vue';
import PlanBody from './components/PlanBody.vue';
import GoalView from './components/GoalView.vue';
import TrajectoryView from './components/TrajectoryView.vue';
import SettingsPage from './components/SettingsPage.vue';
import ToolsPage from './components/ToolsPage.vue';

// ---- main 会话壳 body 视图（复用 tabs + composer dock）----
viewRegistry.register({
  key: 'chat', component: MsgView, shell: 'main', label: '💬 对话', tab: true,
  activate: () => { appState.view = 'chat'; },
});
viewRegistry.register({
  key: 'plan', component: PlanBody, shell: 'main', label: '📋 计划', tab: true,
  activate: () => {
    appState.view = 'plan';
    if (appState.sessionId) void refreshPlanState(appState.sessionId);
  },
});
viewRegistry.register({
  key: 'goal', component: GoalView, shell: 'main', label: '🎯 目标', tab: true,
  activate: () => {
    appState.view = 'goal';
    if (appState.sessionId) void refreshGoal(appState.sessionId);
  },
});
viewRegistry.register({
  key: 'trajectory', component: TrajectoryView, shell: 'main', label: '🛤 轨迹', tab: true,
  activate: () => { switchTrajectory(); },
});

// ---- standalone 独立页（保留侧边栏，无 main 页签/输入 dock）----
viewRegistry.register({ key: 'settings', component: SettingsPage, shell: 'standalone' });
for (const tool of ['mcp', 'skills', 'jobs', 'coder', 'self', 'expert']) {
  viewRegistry.register({ key: tool, component: ToolsPage, shell: 'standalone' });
}
