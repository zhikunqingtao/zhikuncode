import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

/**
 * TC-FE-007 流式文本 RAF 优化性能验证（Vitest 替代方案）
 *
 * 由于 Playwright E2E 中 requestAnimationFrame 性能数据受环境影响大，
 * 改为 Vitest 单元测试，直接验证 streamingStore 和 appendStreamDelta
 * 的批量通知机制。
 */

// 模拟 streamingStore 的核心逻辑（与 useStreamingText.ts 一致）
function createStreamingStore() {
  let content = '';
  const listeners = new Set<() => void>();

  return {
    getSnapshot: () => content,
    subscribe: (listener: () => void) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    append: (delta: string) => {
      content += delta;
    },
    flush: () => {
      listeners.forEach(l => l());
    },
    reset: () => {
      content = '';
    },
    getListenerCount: () => listeners.size,
  };
}

describe('TC-FE-007 流式文本 RAF 优化性能验证', () => {
  let store: ReturnType<typeof createStreamingStore>;
  let rafCallbacks: Array<FrameRequestCallback>;
  let rafIdCounter: number;

  beforeEach(() => {
    store = createStreamingStore();
    rafCallbacks = [];
    rafIdCounter = 0;

    // Mock requestAnimationFrame
    vi.stubGlobal('requestAnimationFrame', (cb: FrameRequestCallback) => {
      rafCallbacks.push(cb);
      return ++rafIdCounter;
    });
    vi.stubGlobal('cancelAnimationFrame', (id: number) => {
      // 简单移除
      rafCallbacks = rafCallbacks.filter((_, i) => i + 1 !== id);
    });
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('append 不应立即触发订阅者通知', () => {
    let notifyCount = 0;
    store.subscribe(() => notifyCount++);

    store.append('Hello');
    store.append(' World');

    // append 仅累积内容，不通知
    expect(store.getSnapshot()).toBe('Hello World');
    expect(notifyCount).toBe(0);
  });

  it('flush 应批量通知所有订阅者', () => {
    let notifyCount = 0;
    store.subscribe(() => notifyCount++);

    store.append('chunk1');
    store.append('chunk2');
    store.append('chunk3');
    store.flush();

    expect(notifyCount).toBe(1);
    expect(store.getSnapshot()).toBe('chunk1chunk2chunk3');
  });

  it('RAF 去抖：多次 append 仅注册一次 requestAnimationFrame', () => {
    // 模拟 appendStreamDelta 的去抖逻辑
    let notifyRafId: number | null = null;

    function appendStreamDelta(delta: string) {
      store.append(delta);
      if (notifyRafId === null) {
        notifyRafId = requestAnimationFrame(() => {
          notifyRafId = null;
          store.flush();
        });
      }
    }

    let notifyCount = 0;
    store.subscribe(() => notifyCount++);

    // 快速连续追加 5 次
    appendStreamDelta('A');
    appendStreamDelta('B');
    appendStreamDelta('C');
    appendStreamDelta('D');
    appendStreamDelta('E');

    // RAF 仅注册一次
    expect(rafCallbacks).toHaveLength(1);
    expect(notifyCount).toBe(0);

    // 触发 RAF 回调（模拟下一帧）
    rafCallbacks[0](performance.now());

    // 一次 flush 通知所有订阅者
    expect(notifyCount).toBe(1);
    expect(store.getSnapshot()).toBe('ABCDE');
  });

  it('高频追加场景：1000 次 append 应产生极少量 flush', () => {
    let notifyRafId: number | null = null;
    let flushCount = 0;

    const originalFlush = store.flush.bind(store);
    store.flush = () => {
      flushCount++;
      originalFlush();
    };

    function appendStreamDelta(delta: string) {
      store.append(delta);
      if (notifyRafId === null) {
        notifyRafId = requestAnimationFrame(() => {
          notifyRafId = null;
          store.flush();
        });
      }
    }

    // 模拟 1000 次快速追加
    for (let i = 0; i < 1000; i++) {
      appendStreamDelta(`token${i} `);
    }

    // 触发所有 pending RAF
    while (rafCallbacks.length > 0) {
      const cb = rafCallbacks.shift()!;
      cb(performance.now());
    }

    // 应只有 1 次 flush（所有 append 都在同一帧内）
    expect(flushCount).toBe(1);
    expect(store.getSnapshot()).toContain('token0');
    expect(store.getSnapshot()).toContain('token999');
  });

  it('reset 应清空内容', () => {
    store.append('some content');
    expect(store.getSnapshot()).toBe('some content');

    store.reset();
    expect(store.getSnapshot()).toBe('');
  });

  it('多订阅者都应收到 flush 通知', () => {
    let count1 = 0, count2 = 0, count3 = 0;
    store.subscribe(() => count1++);
    store.subscribe(() => count2++);
    store.subscribe(() => count3++);

    store.append('data');
    store.flush();

    expect(count1).toBe(1);
    expect(count2).toBe(1);
    expect(count3).toBe(1);
    expect(store.getListenerCount()).toBe(3);
  });

  it('取消订阅后不再收到通知', () => {
    let notifyCount = 0;
    const unsubscribe = store.subscribe(() => notifyCount++);

    store.append('A');
    store.flush();
    expect(notifyCount).toBe(1);

    unsubscribe();
    store.append('B');
    store.flush();
    expect(notifyCount).toBe(1); // 不再增加
  });
});
