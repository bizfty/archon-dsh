import { describe, it, expect, beforeEach } from 'vitest';
import { createRegistry, type ViewModule } from './registry';

/** 假组件（纯 TS 单测不 import .vue；component 字段只作占位断言）。 */
const FAKE = (id: string) => ({ __fake: id });

function chat(): ViewModule {
  return { key: 'chat', component: FAKE('chat'), shell: 'main', label: '💬 对话', tab: true };
}
function goal(): ViewModule {
  return { key: 'goal', component: FAKE('goal'), shell: 'main', label: '🎯 目标', tab: true };
}
function settings(): ViewModule {
  return { key: 'settings', component: FAKE('settings'), shell: 'standalone' };
}

describe('registry', () => {
  let reg: ReturnType<typeof createRegistry>;
  beforeEach(() => {
    reg = createRegistry();
  });

  it('register 后 get 命中并保序', () => {
    reg.register(chat());
    reg.register(goal());
    expect(reg.get('chat')).toBeDefined();
    expect(reg.get('chat')!.label).toBe('💬 对话');
    expect(reg.size).toBe(2);
    expect(reg.keys()).toEqual(['chat', 'goal']);
  });

  it('同 key 后注册覆盖先注册（模块可替换）', () => {
    reg.register(chat());
    reg.register({ ...chat(), component: FAKE('chat-v2') });
    expect(reg.size).toBe(1);
    expect(reg.get('chat')!.component).toEqual(FAKE('chat-v2'));
  });

  it('unregister 移除后 get 返回 undefined', () => {
    reg.register(chat());
    reg.unregister('chat');
    expect(reg.get('chat')).toBeUndefined();
    expect(reg.size).toBe(0);
  });

  it('list(shell) 按 shell 过滤、保注册序', () => {
    reg.register(chat());      // main
    reg.register(settings());  // standalone
    reg.register(goal());      // main
    const main = reg.list('main');
    expect(main.map((m) => m.key)).toEqual(['chat', 'goal']);
    const std = reg.list('standalone');
    expect(std.map((m) => m.key)).toEqual(['settings']);
  });

  it('clear 清空全部', () => {
    reg.register(chat());
    reg.register(settings());
    reg.clear();
    expect(reg.size).toBe(0);
    expect(reg.keys()).toEqual([]);
  });

  it('get 不存在的 key 返回 undefined（不抛错）', () => {
    expect(reg.get('nope')).toBeUndefined();
    expect(reg.list('main')).toEqual([]);
  });
});
