import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { SidebarTabContent } from './Sidebar';
import { activateSessionCandidate } from '@/services/sessionActivation';
import { useSessionStore } from '@/store/sessionStore';
import { useWorkbenchViewStore } from '@/store/workbenchViewStore';

vi.mock('@/services/sessionActivation', () => ({ activateSessionCandidate: vi.fn() }));
beforeEach(() => {
    localStorage.clear();
    vi.clearAllMocks();
    useSessionStore.setState({ sessionId: 'old' });
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => ({
        sessions: [{ id: 'target', title: '目标会话', model: 'test', workingDirectory: '/workspace', updatedAt: new Date().toISOString(), messageCount: 1 }],
        hasMore: false,
        groups: [{ status: 'REVIEWABLE', label: '待查看', tasks: [{ sessionId: 'target', title: '目标会话', folderName: 'workspace', status: 'REVIEWABLE', updatedAt: new Date().toISOString(), hint: '' }] }],
    }) }));
});
afterEach(() => vi.unstubAllGlobals());

it.each([false, true])('会话选择仅在当前会话或成功激活时返回聊天（简洁模式 %s）', async simple => {
    useWorkbenchViewStore.setState({ enabled: true, viewMode: simple ? 'simple' : 'development' });
    const onSessionActivated = vi.fn();
    render(<SidebarTabContent activeTab="sessions" onSessionActivated={onSessionActivated} />);
    const target = await screen.findByText('目标会话');
    vi.mocked(activateSessionCandidate).mockResolvedValue({ status: 'failed', sessionId: 'target', error: new Error('连接失败') });
    fireEvent.click(target);
    await waitFor(() => expect(activateSessionCandidate).toHaveBeenCalledTimes(1));
    expect(onSessionActivated).not.toHaveBeenCalled();
    vi.mocked(activateSessionCandidate).mockResolvedValue({ status: 'superseded', sessionId: 'target' });
    fireEvent.click(target);
    await waitFor(() => expect(activateSessionCandidate).toHaveBeenCalledTimes(2));
    expect(onSessionActivated).not.toHaveBeenCalled();
    vi.mocked(activateSessionCandidate).mockResolvedValue({ status: 'activated', sessionId: 'target' });
    fireEvent.click(target);
    await waitFor(() => expect(onSessionActivated).toHaveBeenCalledTimes(1));
    act(() => useSessionStore.setState({ sessionId: 'target' }));
    await waitFor(() => expect(screen.getByText('目标会话')).toBeInTheDocument());
    fireEvent.click(target);
    await waitFor(() => expect(onSessionActivated).toHaveBeenCalledTimes(2));
    expect(activateSessionCandidate).toHaveBeenCalledTimes(3);
});
