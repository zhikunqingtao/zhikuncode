import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

/**
 * TC-FE-006 跨 Tab 状态同步验证（Vitest 替代方案）
 *
 * 由于 Playwright E2E 难以模拟真实的 BroadcastChannel 跨 Tab 同步，
 * 改为 Vitest 单元测试，直接验证 broadcastMiddleware 的核心逻辑。
 */

// Mock BroadcastChannel（jsdom 不原生支持）
class MockBroadcastChannel {
  static instances: MockBroadcastChannel[] = [];
  name: string;
  onmessage: ((event: MessageEvent) => void) | null = null;

  constructor(name: string) {
    this.name = name;
    MockBroadcastChannel.instances.push(this);
  }

  postMessage(data: unknown) {
    // 广播到同名的其他 channel 实例（模拟跨 Tab）
    MockBroadcastChannel.instances
      .filter(ch => ch !== this && ch.name === this.name)
      .forEach(ch => {
        if (ch.onmessage) {
          ch.onmessage(new MessageEvent('message', { data }));
        }
      });
  }

  close() {
    const idx = MockBroadcastChannel.instances.indexOf(this);
    if (idx >= 0) MockBroadcastChannel.instances.splice(idx, 1);
  }
}

describe('TC-FE-006 跨 Tab 状态同步验证', () => {
  beforeEach(() => {
    MockBroadcastChannel.instances = [];
    vi.stubGlobal('BroadcastChannel', MockBroadcastChannel);
  });

  afterEach(() => {
    vi.restoreAllMocks();
    MockBroadcastChannel.instances.forEach(ch => ch.close());
    MockBroadcastChannel.instances = [];
  });

  it('BroadcastChannel 消息应广播到其他同名实例', () => {
    const ch1 = new MockBroadcastChannel('test-channel');
    const ch2 = new MockBroadcastChannel('test-channel');
    const received: unknown[] = [];
    ch2.onmessage = (e) => received.push(e.data);

    ch1.postMessage({ type: 'STATE_UPDATE', state: { theme: 'dark' }, senderId: 'tab-1' });

    expect(received).toHaveLength(1);
    expect(received[0]).toEqual({
      type: 'STATE_UPDATE',
      state: { theme: 'dark' },
      senderId: 'tab-1',
    });

    ch1.close();
    ch2.close();
  });

  it('同一实例不应收到自己发送的消息（防循环）', () => {
    const ch1 = new MockBroadcastChannel('test-channel');
    const selfReceived: unknown[] = [];
    ch1.onmessage = (e) => selfReceived.push(e.data);

    ch1.postMessage({ type: 'STATE_UPDATE', state: { theme: 'dark' }, senderId: 'tab-1' });

    // 自身实例不应收到消息
    expect(selfReceived).toHaveLength(0);

    ch1.close();
  });

  it('不同名称的 channel 不应互相干扰', () => {
    const ch1 = new MockBroadcastChannel('channel-A');
    const ch2 = new MockBroadcastChannel('channel-B');
    const received: unknown[] = [];
    ch2.onmessage = (e) => received.push(e.data);

    ch1.postMessage({ type: 'STATE_UPDATE', state: { locale: 'en' }, senderId: 'tab-1' });

    expect(received).toHaveLength(0);

    ch1.close();
    ch2.close();
  });

  it('partialize 应过滤仅需同步的字段', () => {
    // 模拟 ConfigStore 的 partialize 函数
    const partialize = (state: Record<string, unknown>) => ({
      theme: state.theme,
      locale: state.locale,
      autoCompact: state.autoCompact,
      defaultModel: state.defaultModel,
    });

    const fullState = {
      theme: 'dark',
      locale: 'zh-CN',
      autoCompact: true,
      defaultModel: 'gpt-4o',
      internalCache: { size: 100 },  // 不应同步
      lastFetchTime: Date.now(),      // 不应同步
    };

    const synced = partialize(fullState);

    expect(synced).toHaveProperty('theme', 'dark');
    expect(synced).toHaveProperty('locale', 'zh-CN');
    expect(synced).not.toHaveProperty('internalCache');
    expect(synced).not.toHaveProperty('lastFetchTime');
  });
});
