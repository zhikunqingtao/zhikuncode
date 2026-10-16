import { act, cleanup, renderHook, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { dispatch, resetBoundSession } from '@/api/dispatch';
import { saveActivity, updateActivityInsight } from '@/api/activityApi';
import { useActivityStore } from '@/store/activityStore';
import { useMessageStore } from '@/store/messageStore';
import { useSessionStore } from '@/store/sessionStore';
import { useAPOSInitialization } from './useAPOSInitialization';

vi.mock('@/api/activityApi', () => ({
  saveActivity: vi.fn(), updateActivityInsight: vi.fn(), updateActivityDecision: vi.fn(),
}));
vi.mock('@/api/stompClient', () => ({ send: vi.fn(), sendToServer: vi.fn(() => true) }));

const interrupted = {
  type: 'error' as const, code: 'query_error', errorCode: 'MODEL_RESPONSE_INTERRUPTED' as const,
  message: '模型响应中断，上游结果未知。为避免重复请求，已停止自动重试。', retryable: false,
};

describe('APOS interrupted tool projections', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    resetBoundSession();
    useActivityStore.getState().clearAll();
    useMessageStore.getState().clearMessages();
    useSessionStore.setState({ sessionId: 'session-1', status: 'streaming' });
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => ({
      signal: 'review_recommended', overallStatus: 'pass', signalReason: 'verified',
    }) }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('saves and verifies a real completed write once', async () => {
    renderHook(() => useAPOSInitialization());
    act(() => {
      dispatch({ type: 'tool_use_start', toolUseId: 'submitted', toolName: 'Write', input: {} });
      dispatch({ type: 'tool_use_input', toolUseId: 'submitted', toolName: 'Write', input: { file_path: '/tmp/submitted.ts' } });
      dispatch({ type: 'tool_result', toolUseId: 'submitted', content: 'saved', isError: false });
    });
    await waitFor(() => expect(updateActivityInsight).toHaveBeenCalledOnce());
    expect(saveActivity).toHaveBeenCalledOnce();
    expect(saveActivity).toHaveBeenCalledWith(expect.objectContaining({
      id: 'submitted', status: 'completed', fileCount: 1,
      toolResult: { content: 'saved', isError: false, metadata: undefined },
    }));
    expect(fetch).toHaveBeenCalledOnce();
    expect(fetch).toHaveBeenCalledWith('/api/verify/run-checks', expect.objectContaining({ method: 'POST' }));
    act(() => dispatch({ type: 'tool_result', toolUseId: 'submitted', content: 'saved', isError: false }));
    expect(saveActivity).toHaveBeenCalledOnce();
    expect(fetch).toHaveBeenCalledOnce();
  });

  it('does not backfill an existing Activity from a synthetic result', () => {
    renderHook(() => useAPOSInitialization());
    act(() => {
      useActivityStore.getState().addActivity({
        id: 'tool', sessionId: 'session-1', operationType: 'file_create', summary: 'Write',
        status: 'running', timestamp: 1, fileCount: 0, changedFiles: [],
      });
      dispatch({ type: 'tool_use_start', toolUseId: 'tool', toolName: 'Write', input: {} });
      // failAllRunningToolCalls and finalizeStream run synchronously in handleError.
      useMessageStore.getState().failAllRunningToolCalls(interrupted.message, true);
      dispatch({ type: 'tool_use_input', toolUseId: 'tool', toolName: 'Write', input: { file_path: '/tmp/unconfirmed.ts' } });
    });
    expect(useActivityStore.getState().activities.get('tool')).toMatchObject({ changedFiles: [], fileCount: 0 });
    expect(useActivityStore.getState().activities.get('tool')?.toolResult).toBeUndefined();
    expect(saveActivity).not.toHaveBeenCalled();
    expect(fetch).not.toHaveBeenCalled();
  });

  it('does not save or verify interrupted tools or real results loaded by a same-session recovery snapshot', () => {
    renderHook(() => useAPOSInitialization());
    act(() => {
      dispatch({ type: 'tool_use_start', toolUseId: 'recovered', toolName: 'Write', input: {} });
      dispatch({ type: 'tool_use_input', toolUseId: 'recovered', toolName: 'Write', input: { file_path: '/tmp/recovered.ts' } });
      dispatch(interrupted);
    });
    expect(useMessageStore.getState().activeToolCalls.size).toBe(0);
    expect(useActivityStore.getState().activities.size).toBe(0);
    expect(saveActivity).not.toHaveBeenCalled();
    expect(updateActivityInsight).not.toHaveBeenCalled();
    expect(fetch).not.toHaveBeenCalled();

    act(() => {
      // session_restored replaces message history before loading persisted Activities.
      // Recovery must not be mistaken for a new tool_result event.
      useMessageStore.getState().restoreSessionSnapshot([
        {
          type: 'assistant', uuid: 'durable-assistant', timestamp: 1, stopReason: 'tool_use',
          usage: { inputTokens: 1, outputTokens: 1, cacheReadInputTokens: 0, cacheCreationInputTokens: 0 },
          content: [{ type: 'tool_use', toolUseId: 'recovered', toolName: 'Write', input: { file_path: '/tmp/recovered.ts' } }],
        },
        { type: 'user', uuid: 'durable-result', timestamp: 2,
          content: [{ type: 'tool_result', toolUseId: 'recovered', content: 'saved', isError: false }] },
      ], []);
    });
    const assistant = useMessageStore.getState().messages[0];
    expect(assistant.type === 'assistant' && assistant.content[0]).toMatchObject({
      type: 'tool_use', toolUseId: 'recovered', result: { content: 'saved', isError: false },
    });
    expect(useSessionStore.getState().sessionId).toBe('session-1');
    expect(saveActivity).not.toHaveBeenCalled();
    expect(updateActivityInsight).not.toHaveBeenCalled();
    expect(fetch).not.toHaveBeenCalled();
    expect(useActivityStore.getState().activities.size).toBe(0);
  });
});
