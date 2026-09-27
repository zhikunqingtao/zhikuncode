/**
 * /review 斜杠命令参数保真测试。
 *
 * 渲染真实 App 组件（重型子组件与 hooks 打桩），通过 App 传给 PromptInput 的
 * onSlashCommand 回调驱动 handleSlashCommand，断言 sendSlashCommand 收到的
 * (command, args)：
 * - /review 保留首个 token 之后的原始内部格式（换行/连续空格/引号/= 号）；
 * - /Review、/REVIEW 统一发送规范名 'review'；
 * - 非 review 命令仍按旧的 split/join 逻辑；
 * - 忙碌时拦截发送；发送失败时报错且不回报“执行命令”。
 */
import React from 'react';
import { act, render } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import App from './App';
import { sendSlashCommand } from '@/api/stompClient';
import { useMessageStore } from '@/store/messageStore';
import { useNotificationStore } from '@/store/notificationStore';
import { useSessionStore } from '@/store/sessionStore';

// ───── 捕获 App 传给 PromptInput 的 props ─────
const captured = vi.hoisted(() => ({
  promptInputProps: null as null | {
    onSlashCommand: (command: string) => Promise<boolean>;
  },
}));

vi.mock('@/api/stompClient', () => ({
  sendToServer: vi.fn(),
  sendRunInput: vi.fn(),
  sendSlashCommand: vi.fn(() => true),
}));

vi.mock('@/components/input', () => ({
  PromptInput: (props: {
    onSlashCommand: (command: string) => Promise<boolean>;
  }) => {
    captured.promptInputProps = props;
    return null;
  },
}));

// ───── 重型子组件打桩 ─────
vi.mock('@/components/layout', () => ({
  AppLayout: ({ children }: { children?: React.ReactNode }) => <>{children}</>,
}));
vi.mock('@/components/message', () => ({
  MessageList: React.forwardRef(() => null),
}));
vi.mock('@/components/message/EmptyHero', () => ({ EmptyHero: () => null }));
vi.mock('@/components/verify/JourneyVerifyPanel', () => ({ JourneyVerifyPanel: () => null }));
vi.mock('@/components/DialogManager', () => ({ DialogManager: () => null }));
vi.mock('@/components/skills/SkillDetailModal', () => ({ SkillDetailModal: () => null }));
vi.mock('@/components/verify/MobileApprovalSheet', () => ({ MobileApprovalSheet: () => null }));
vi.mock('@/components/project/ProjectSelectionDialog', () => ({ ProjectSelectionDialog: () => null }));
vi.mock('@/components/dialog/InterruptConfirmDialog', () => ({ InterruptConfirmDialog: () => null }));
vi.mock('@/components/workbench/SimpleWorkbench', () => ({ SimpleWorkbench: () => null }));
vi.mock('@/components/session/SessionMergePanel', () => ({ SessionMergePanel: () => null }));

// ───── 浏览器能力 / 副作用 hooks 打桩 ─────
vi.mock('@/hooks/useAPOSInitialization', () => ({ useAPOSInitialization: () => {} }));
vi.mock('@/hooks/usePageExitGuard', () => ({ usePageExitGuard: () => {} }));
vi.mock('@/hooks/useTabStatus', () => ({ useTabStatus: () => {} }));
vi.mock('@/hooks/useResponsive', () => ({
  useResponsive: () => ({ isMobile: false, isTablet: false }),
}));
vi.mock('@/hooks/useVirtualKeyboard', () => ({
  useVirtualKeyboard: () => ({ keyboardHeight: 0 }),
}));

// ───── 会话就绪链路打桩（store 中预置 sessionId，直接激活） ─────
vi.mock('@/services/authorizedSession', () => ({
  NEW_AUTHORIZED_SESSION_EVENT: 'new-authorized-session-event',
  requestAuthorizedSession: vi.fn(async () => 'session-under-test'),
}));
vi.mock('@/services/sessionActivation', () => ({
  activateSessionCandidate: vi.fn(async () => ({ status: 'activated' })),
  getPendingSessionActivation: vi.fn(() => null),
}));

// ───── 发布工具打桩（App 启动时会探测本地文件引用能力） ─────
vi.mock('@/utils/pasteImagePublisher', () => ({
  publishPastedImages: vi.fn(async () => ({ status: 'skipped' })),
}));
vi.mock('@/utils/localFilePublisher', () => ({
  loadFileReferenceCapability: vi.fn(async () => ({ mode: 'unavailable' })),
  publishLocalFile: vi.fn(),
}));

class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}
vi.stubGlobal('ResizeObserver', ResizeObserverStub);
vi.stubGlobal('fetch', vi.fn(async (input: unknown) => {
  const url = String(input);
  if (url.includes('/api/skills')) {
    return { ok: true, status: 200, json: async () => [] } as Response;
  }
  return { ok: false, status: 500, json: async () => ({}) } as Response;
}));

const sendSlashCommandMock = vi.mocked(sendSlashCommand);

async function runSlashCommand(input: string): Promise<boolean> {
  const handler = captured.promptInputProps?.onSlashCommand;
  expect(handler).toBeTypeOf('function');
  let result = false;
  await act(async () => {
    result = await handler!(input);
  });
  return result;
}

function lastSendCall(): [string, string] {
  const calls = sendSlashCommandMock.mock.calls;
  expect(calls.length).toBeGreaterThan(0);
  return calls[calls.length - 1] as [string, string];
}

describe('App handleSlashCommand — /review 参数保真', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    captured.promptInputProps = null;
    useSessionStore.setState({ sessionId: 'session-under-test', status: 'idle' });
    useMessageStore.setState({ messages: [] });
    useNotificationStore.setState({ notifications: [] });
    render(<App />);
  });

  it('保留多行参数中的换行', async () => {
    const ok = await runSlashCommand('/review 第一行：只看暂存区\n第二行：忽略 docs 目录');
    expect(ok).toBe(true);
    expect(lastSendCall()).toEqual([
      'review',
      '第一行：只看暂存区\n第二行：忽略 docs 目录',
    ]);
  });

  it('保留参数中的连续空格', async () => {
    await runSlashCommand('/review 比较  main...HEAD   排除  dist');
    expect(lastSendCall()).toEqual(['review', '比较  main...HEAD   排除  dist']);
  });

  it('保留带空格路径', async () => {
    await runSlashCommand('/review docs/keep out.txt 的变更');
    expect(lastSendCall()).toEqual(['review', 'docs/keep out.txt 的变更']);
  });

  it('保留引号', async () => {
    await runSlashCommand('/review 范围="src/main" 且排除 \'dist\'');
    expect(lastSendCall()).toEqual(['review', '范围="src/main" 且排除 \'dist\'']);
  });

  it('保留 = 号', async () => {
    await runSlashCommand('/review format=patch --since=2024-01-01');
    expect(lastSendCall()).toEqual(['review', 'format=patch --since=2024-01-01']);
  });

  it('/Review 混合大小写发送规范命令名 review', async () => {
    await runSlashCommand('/Review 只审暂存区');
    expect(lastSendCall()).toEqual(['review', '只审暂存区']);
  });

  it('/REVIEW 全大写发送规范命令名 review', async () => {
    await runSlashCommand('/REVIEW 比较 main...HEAD');
    expect(lastSendCall()).toEqual(['review', '比较 main...HEAD']);
  });

  it('实际用例：只审暂存区', async () => {
    await runSlashCommand('/review 只审暂存区');
    expect(lastSendCall()).toEqual(['review', '只审暂存区']);
    // 服务端受理后 UI 回报执行命令。
    const messages = useMessageStore.getState().messages;
    expect(messages.some(
      m => 'content' in m && m.content === '执行命令: /review 只审暂存区',
    )).toBe(true);
  });

  it('实际用例：比较 main...HEAD', async () => {
    await runSlashCommand('/review 比较 main...HEAD');
    expect(lastSendCall()).toEqual(['review', '比较 main...HEAD']);
  });

  it('空参数发送空字符串', async () => {
    await runSlashCommand('/review');
    expect(lastSendCall()).toEqual(['review', '']);
  });

  it('非 review 命令仍按旧的 split/join 逻辑折叠空白', async () => {
    await runSlashCommand('/diff  a   b');
    expect(lastSendCall()).toEqual(['diff', 'a b']);
  });

  it('会话忙碌时拦截发送', async () => {
    useSessionStore.getState().setStatus('streaming');
    const ok = await runSlashCommand('/review 只审暂存区');
    expect(ok).toBe(false);
    expect(sendSlashCommandMock).not.toHaveBeenCalled();
    expect(
      useNotificationStore.getState().notifications.some(
        n => n.key === 'command-blocked-while-running',
      ),
    ).toBe(true);
  });

  it('发送失败时提示错误且不回报执行命令', async () => {
    sendSlashCommandMock.mockReturnValueOnce(false);
    const ok = await runSlashCommand('/review 只审暂存区');
    expect(ok).toBe(false);
    const messages = useMessageStore.getState().messages;
    expect(messages.some(
      m => 'content' in m && typeof m.content === 'string' && m.content.includes('命令未发送'),
    )).toBe(true);
    expect(messages.some(
      m => 'content' in m && typeof m.content === 'string' && m.content.startsWith('执行命令'),
    )).toBe(false);
  });
});
