/**
 * registry.ts —— B/L3 插槽化宿主：module 级视图注册表（design-client-vue-l3-slots.md）。
 *
 * 把「view 值 → 页面组件」与「main 区 nav tab」的对应从 App.vue 硬编码 if/else 改为
 * 注册表装配：新增视图/槽只需 register，不改 App.vue 的骨架与分支结构。
 *
 * 克制红线：纯 TS 无 Vue 运行时依赖（component 字段用 unknown/抽象占位，便于单测）；
 * 不引 cordis 依赖注入、不搬 React 渲染器 —— 组合式注入（store reactive 单例 appState）
 * 由各组件自行消费，本注册表只管「装配来源」。
 */

export type ViewShell = 'main' | 'standalone';

/**
 * 一个可注册的视图模块。
 * - key：视图键（= appState.view 取值；settings/各工具页亦在此）。
 * - component：页面根组件（运行时由 <component :is> 渲染）。
 * - shell：'main' = 复用 main 的 tabs+composer 会话壳；'standalone' = 独立页(settings/tools)。
 * - tab / label：仅对 shell==='main' 的模块有意义 —— tab=true 时在 nav.tabs 出现该 tab。
 * - 覆盖语义：同 key 后注册覆盖先注册（模块可替换/重载）。
 */
export interface ViewModule {
  key: string;
  component: unknown;
  shell: ViewShell;
  label?: string;
  tab?: boolean;
  /** 选中该视图时触发（如切到 plan/goal/trajectory 时同步后端域）；chat 等无需。 */
  activate?: () => void;
}

export interface Registry {
  register(m: ViewModule): void;
  unregister(key: string): void;
  get(key: string): ViewModule | undefined;
  keys(): string[];
  /** 列出某 shell 下的全部模块（保注册序）。 */
  list(shell?: ViewShell): ViewModule[];
  clear(): void;
  readonly size: number;
}

export function createRegistry(): Registry {
  const modules = new Map<string, ViewModule>();
  return {
    register(m) {
      modules.set(m.key, m);
    },
    unregister(key) {
      modules.delete(key);
    },
    get(key) {
      return modules.get(key);
    },
    keys() {
      return [...modules.keys()];
    },
    list(shell) {
      const all = [...modules.values()];
      return shell ? all.filter((m) => m.shell === shell) : all;
    },
    clear() {
      modules.clear();
    },
    get size() {
      return modules.size;
    },
  };
}

/** 应用级单例（组合式注入，各模块/宿主共享同一实例）。 */
export const viewRegistry: Registry = createRegistry();
