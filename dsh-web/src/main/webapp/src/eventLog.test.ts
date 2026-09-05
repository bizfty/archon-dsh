// B 档 L1 有序交付层单测（vitest，node 环境，纯逻辑无 DOM）。
import { describe, expect, it } from 'vitest';
import { createEventLog, type EventLogHandle, type LoggedFrame } from './eventLog';

function frame(sessionId: string, seq: number): LoggedFrame {
  return { sessionId, seq, event: { eventType: 'EVT', data: { seq } } };
}

/** accept 一帧并断言状态与按序就绪帧的 seq 列表。 */
function expectAccept(
  log: EventLogHandle,
  sessionId: string,
  seq: number,
  status: 'applied' | 'buffered' | 'duplicate',
  ready: number[] = [],
): void {
  const out = log.accept(frame(sessionId, seq));
  expect(out.status).toBe(status);
  expect(out.ready.map((f) => f.seq)).toEqual(ready);
}

describe('eventLog 有序交付', () => {
  it('顺序帧逐个 applied，按序就绪', () => {
    const log = createEventLog();
    expectAccept(log, 's', 1, 'applied', [1]);
    expectAccept(log, 's', 2, 'applied', [2]);
    expectAccept(log, 's', 3, 'applied', [3]);
  });

  it('缺口帧滞留缓冲，前序到达后链式排空（顺序正确）', () => {
    // 首帧建立基线（重连后首帧即新水位，直接 applied）；随后乱序缺口帧滞留。
    const log = createEventLog();
    expectAccept(log, 's', 1, 'applied', [1]);
    expectAccept(log, 's', 3, 'buffered');
    expect(log.status('s')).toEqual({ watermark: 1, buffered: 1 });
    expectAccept(log, 's', 2, 'applied', [2, 3]);
  });

  it('重复帧去重：已应用与缓冲中均判 duplicate', () => {
    const log = createEventLog();
    expectAccept(log, 's', 1, 'applied', [1]);
    expectAccept(log, 's', 1, 'duplicate');
    expectAccept(log, 's', 3, 'buffered'); // 缺口 2
    expectAccept(log, 's', 3, 'duplicate'); // 缓冲中重复
    expectAccept(log, 's', 2, 'applied', [2, 3]); // 排空
    expectAccept(log, 's', 3, 'duplicate'); // 已应用后重复
  });

  it('空洞补齐：缺口期间缓冲等待，逐个就绪', () => {
    const log = createEventLog();
    expectAccept(log, 's', 1, 'applied', [1]);
    expectAccept(log, 's', 2, 'applied', [2]);
    expectAccept(log, 's', 5, 'buffered');
    expectAccept(log, 's', 3, 'applied', [3]); // 5 仍滞留
    expectAccept(log, 's', 4, 'applied', [4, 5]); // 补齐后链式排空
  });

  it('首帧即水位：reset 后新基线；旧水位以下帧 duplicate', () => {
    const log = createEventLog();
    log.reset('s');
    expectAccept(log, 's', 10, 'applied', [10]);
    expect(log.status('s').watermark).toBe(10);
    expectAccept(log, 's', 9, 'duplicate');
    expectAccept(log, 's', 11, 'applied', [11]);
  });

  it('跨会话隔离：A 缺口滞留不影响 B 直接 applied', () => {
    const log = createEventLog();
    expectAccept(log, 'A', 1, 'applied', [1]);
    expectAccept(log, 'A', 3, 'buffered'); // 缺口 2 滞留
    expectAccept(log, 'B', 1, 'applied', [1]);
    expectAccept(log, 'B', 2, 'applied', [2]);
    expect(log.status('A')).toEqual({ watermark: 1, buffered: 1 });
  });

  it('reset / resetAll 清空全部状态，后续从新水位续', () => {
    const log = createEventLog();
    expectAccept(log, 's', 1, 'applied', [1]);
    expectAccept(log, 's', 3, 'buffered');
    log.reset('s');
    expect(log.status('s')).toEqual({ watermark: null, buffered: 0 });
    expectAccept(log, 's', 100, 'applied', [100]);
    expectAccept(log, 't', 7, 'applied', [7]);
    log.resetAll();
    expect(log.status('s')).toEqual({ watermark: null, buffered: 0 });
    expect(log.status('t')).toEqual({ watermark: null, buffered: 0 });
    expectAccept(log, 't', 50, 'applied', [50]);
  });

  it('setWatermark 推进基线（后端增量端点预留）：<=seq duplicate、清缓冲、seq+1 续流', () => {
    const log = createEventLog();
    expectAccept(log, 's', 1, 'applied', [1]);
    expectAccept(log, 's', 55, 'buffered');
    log.setWatermark('s', 50);
    expect(log.status('s')).toEqual({ watermark: 50, buffered: 0 });
    expectAccept(log, 's', 50, 'duplicate');
    expectAccept(log, 's', 49, 'duplicate');
    expectAccept(log, 's', 51, 'applied', [51]);
  });

  it('防御：缺口缓冲超上限自动 reset（保守丢弃，靠全量重拉兜底）', () => {
    const log = createEventLog(3);
    expectAccept(log, 's', 10, 'applied', [10]);
    expectAccept(log, 's', 12, 'buffered');
    expectAccept(log, 's', 13, 'buffered');
    expectAccept(log, 's', 14, 'buffered');
    // 第 4 个缺口帧超过上限 → 该会话缓冲整体重置（帧被保守丢弃，返回 buffered）
    expectAccept(log, 's', 15, 'buffered');
    expect(log.status('s')).toEqual({ watermark: null, buffered: 0 });
    expectAccept(log, 's', 20, 'applied', [20]);
  });

  it('防御：非正整数 seq 判 duplicate，不污染水位', () => {
    const log = createEventLog();
    expect(
      log.accept({ sessionId: 's', seq: 0, event: { eventType: 'X', data: {} } }).status,
    ).toBe('duplicate');
    expect(
      log.accept({ sessionId: 's', seq: 1.5, event: { eventType: 'X', data: {} } }).status,
    ).toBe('duplicate');
    expectAccept(log, 's', 1, 'applied', [1]);
    expect(log.status('s').watermark).toBe(1);
  });
});
