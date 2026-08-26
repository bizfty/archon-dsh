// 常驻 WebSocket 下行客户端 — 对齐官方 ConnectionController 语义：
// 一条常驻连接接收全部会话事件；断线指数退避重连；connected/reconnecting 状态机。
// 上行（发送消息等）仍走 HTTP（api.ts），本模块只做下行。

export type WsConnectionState = 'connected' | 'reconnecting' | 'closed';

export interface SessionFrame {
  type: 'session';
  sessionId: string;
  seq: number;
  event: { eventType: string; data: Record<string, unknown> };
}

export interface WsClientOptions {
  /** 事件帧回调（按 sessionId 过滤由调用方处理）。 */
  onFrame?: (frame: SessionFrame) => void;
  /** 连接状态变化（去重：仅变化时触发）。 */
  onStateChange?: (state: WsConnectionState) => void;
  /** 重连成功后回调（调用方借此 resync 消息列表）。 */
  onReconnected?: () => void;
  /** 可用性变化：连续建连失败达阈值 → false（调用方可回退 SSE）；重连成功 → true。 */
  onAvailabilityChange?: (available: boolean) => void;
}

const DEFAULTS = {
  backoffBaseMs: 500,
  backoffFactor: 2,
  backoffMaxMs: 10_000,
  /** 连续建连失败达此阈值判定 WS 不可用（调用方回退 SSE）。 */
  unavailableThreshold: 5,
} as const;

/**
 * 常驻 WebSocket 客户端：connect() 开始连接/重连循环，close() 停止。
 * 与官方一致：generation 内部私有，attempt 指数退避，状态仅 connected/reconnecting/closed。
 */
export class WsClient {
  private ws: WebSocket | null = null;
  private running = false;
  private attempt = 0;
  private consecutiveFailures = 0;
  private available = true;
  private lastState: WsConnectionState | null = null;
  private readonly config = DEFAULTS;
  private readonly url: string;

  constructor(private readonly options: WsClientOptions = {}) {
    const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
    this.url = `${proto}//${location.host}/api/ws`;
  }

  /** 幂等：开始连接/重连循环。 */
  connect(): void {
    if (this.running) return;
    this.running = true;
    this.open();
  }

  /** 停止并关闭连接。 */
  close(): void {
    this.running = false;
    this.attempt = 0;
    if (this.ws) {
      const ws = this.ws;
      this.ws = null;
      try {
        ws.close();
      } catch {
        /* 忽略 */
      }
    }
    this.emitState('closed');
  }

  private open(): void {
    if (!this.running) return;
    this.emitState('reconnecting');
    let ws: WebSocket;
    try {
      ws = new WebSocket(this.url);
    } catch (e) {
      this.scheduleReconnect();
      return;
    }
    this.ws = ws;

    ws.onopen = () => {
      this.attempt = 0; // 连接成功：重置退避
      this.consecutiveFailures = 0;
      this.setAvailable(true);
      this.emitState('connected');
      this.options.onReconnected?.();
    };

    ws.onmessage = (event) => {
      try {
        const frame = JSON.parse(String(event.data)) as SessionFrame;
        if (frame && frame.type === 'session') {
          this.options.onFrame?.(frame);
        }
      } catch {
        /* 忽略无法解析的帧 */
      }
    };

    ws.onclose = () => {
      if (this.ws === ws) this.ws = null;
      if (this.running) this.scheduleReconnect();
      else this.emitState('closed');
    };

    ws.onerror = () => {
      // onclose 会跟随触发重连；这里只记录状态
      this.emitState('reconnecting');
    };
  }

  private scheduleReconnect(): void {
    if (!this.running) return;
    this.consecutiveFailures += 1;
    if (this.consecutiveFailures >= this.config.unavailableThreshold) {
      this.setAvailable(false);
    }
    const delay = Math.min(
      this.config.backoffMaxMs,
      this.config.backoffBaseMs * this.config.backoffFactor ** this.attempt,
    );
    this.attempt += 1;
    setTimeout(() => this.open(), delay);
  }

  private setAvailable(available: boolean): void {
    if (available === this.available) return;
    this.available = available;
    this.options.onAvailabilityChange?.(available);
  }

  private emitState(state: WsConnectionState): void {
    if (state === this.lastState) return;
    this.lastState = state;
    this.options.onStateChange?.(state);
  }
}

/** 连接状态字符串（用于 UI 展示）。 */
export function connectionLabel(state: 'connecting' | WsConnectionState): string {
  switch (state) {
    case 'connected': return '已连接';
    case 'reconnecting': return '重连中…';
    case 'connecting': return '连接中…';
    default: return '已断开';
  }
}
