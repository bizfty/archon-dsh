// 会话事件有序交付层（B 档 L1，design-client-vue.md §8 P2）：把 ws 下行帧按
// 「会话内 seq 单调升序」投递到 UI 状态 —— seq 去重、乱序缺口缓冲、链式排空、
// 水位管理、断线 reset + 全量 resync 兜底。
//
// 设计约束：
// - 纯 TS、零框架依赖（不 import Vue/DOM/vendor），便于 vitest 纯逻辑单测；
// - 模块级单例 sessionEventLog 供 App.vue 使用；createEventLog 工厂供单测与
//   未来多实例/后端增量端点对接；
// - 后端事件总线 seq 为会话内单调升序整数（跨会话独立、可留洞）；断线重连不
//   重放历史帧 → 重连后首帧即新水位，缺口由调用方全量 resync 覆盖。

export interface LoggedFrame {
  sessionId: string;
  seq: number;
  event: { eventType: string; data: Record<string, unknown> };
}

/**
 * accept 结果：
 * - 'applied'：当前帧（及 ready 中可能排空的缓冲帧）已按序可应用；
 * - 'buffered'：当前帧因会话内 seq 缺口滞留缓冲，待前序帧到达后排空；
 * - 'duplicate'：seq <= 水位或缓冲中已存在，重复帧丢弃。
 */
export type AcceptResult = 'applied' | 'buffered' | 'duplicate';

export interface AcceptOutcome {
  status: AcceptResult;
  /** 本次 accept 推进后可按序应用的帧（含当前帧；顺序即应用顺序）。调用方须按序应用。 */
  ready: LoggedFrame[];
}

interface SessionSlot {
  /** 已应用的最大 seq（含）；null = 该会话尚无基线（首帧到达后建立）。 */
  watermark: number | null;
  /** 缺口滞留帧：seq → frame（仅存 watermark 之后的乱序/缺口帧）。 */
  buffered: Map<number, LoggedFrame>;
  /** 已见过的 seq（已应用或已入缓冲），用于缺口帧重复到达时去重。 */
  seen: Set<number>;
}

export interface EventLogHandle {
  accept(frame: LoggedFrame): AcceptOutcome;
  /** 清空该会话全部状态（buffered/seen/watermark=null）：断线/溢出保守丢弃后重立基线。 */
  reset(sessionId: string): void;
  /** 清空全部会话状态（断线重连统一调用）。 */
  resetAll(): void;
  /**
   * 水位直接推进到 seq（并清缓冲）——为后端增量 resync 端点预留：
   * 基线=seq 后，<=seq 的帧一律 duplicate，seq+1 起正常续流。本 PR 仅提供 API + 单测。
   */
  setWatermark(sessionId: string, seq: number): void;
  status(sessionId: string): { watermark: number | null; buffered: number };
}

/** 单会话缺口缓冲上限：异常 seq 洪水（缺口永不补齐）时保守丢弃该会话缓冲，靠全量重拉兜底。 */
const DEFAULT_BUFFER_LIMIT = 1000;

export function createEventLog(bufferLimit: number = DEFAULT_BUFFER_LIMIT): EventLogHandle {
  const slots = new Map<string, SessionSlot>();

  function slotOf(sessionId: string): SessionSlot {
    let slot = slots.get(sessionId);
    if (!slot) {
      slot = { watermark: null, buffered: new Map(), seen: new Set() };
      slots.set(sessionId, slot);
    }
    return slot;
  }

  function resetSlot(slot: SessionSlot): void {
    slot.watermark = null;
    slot.buffered.clear();
    slot.seen.clear();
  }

  /** 从 watermark+1 起链式排空连续就绪的缓冲帧，返回按序帧列表。 */
  function drain(slot: SessionSlot): LoggedFrame[] {
    const ready: LoggedFrame[] = [];
    while (slot.watermark !== null) {
      const next = slot.buffered.get(slot.watermark + 1);
      if (!next) break;
      slot.buffered.delete(slot.watermark + 1);
      slot.watermark += 1;
      ready.push(next);
    }
    return ready;
  }

  function accept(frame: LoggedFrame): AcceptOutcome {
    const seq = frame.seq;
    // 防御：seq 必须是正整数（后端总线约束；脏帧丢弃，不污染水位）。
    if (!Number.isInteger(seq) || seq <= 0) return { status: 'duplicate', ready: [] };

    const slot = slotOf(frame.sessionId);

    // 首帧 / reset 之后：以当前帧建立新水位（重连不重放 → 首帧即新基线）。
    if (slot.watermark === null) {
      slot.watermark = seq;
      return { status: 'applied', ready: [frame] };
    }

    // 历史/重复：seq <= 已应用水位。
    if (seq <= slot.watermark) return { status: 'duplicate', ready: [] };

    // 恰好接续当前水位：应用，并链式排空此前滞留的连续缓冲。
    if (seq === slot.watermark + 1) {
      slot.watermark = seq;
      return { status: 'applied', ready: [frame, ...drain(slot)] };
    }

    // 缺口（seq > watermark + 1）：滞留缓冲等待前序帧；同 seq 重复到达去重。
    if (slot.seen.has(seq)) return { status: 'duplicate', ready: [] };
    slot.seen.add(seq);
    slot.buffered.set(seq, frame);

    if (slot.buffered.size > bufferLimit) {
      // 防御：缺口永不补齐的异常洪水 → 保守丢弃该会话全部缓冲并重立基线。
      // 该帧与滞留帧等同被丢弃，最终一致由调用方全量 resync（TURN_END/断线）兜底。
      resetSlot(slot);
      return { status: 'buffered', ready: [] };
    }

    return { status: 'buffered', ready: [] };
  }

  function reset(sessionId: string): void {
    slots.delete(sessionId);
  }

  function resetAll(): void {
    slots.clear();
  }

  function setWatermark(sessionId: string, seq: number): void {
    const slot = slotOf(sessionId);
    slot.watermark = seq;
    slot.buffered.clear();
    slot.seen.clear();
  }

  function status(sessionId: string): { watermark: number | null; buffered: number } {
    const slot = slots.get(sessionId);
    return slot
      ? { watermark: slot.watermark, buffered: slot.buffered.size }
      : { watermark: null, buffered: 0 };
  }

  return { accept, reset, resetAll, setWatermark, status };
}

/** 应用全局单例（App.vue 接线用）。 */
export const sessionEventLog: EventLogHandle = createEventLog();
