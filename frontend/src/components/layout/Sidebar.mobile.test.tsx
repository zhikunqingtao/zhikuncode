import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { SidebarTabContent, resetDeleteConfirmRequiredCacheForTest } from './Sidebar';
import { activateSessionCandidate } from '@/services/sessionActivation';
import { useSessionStore } from '@/store/sessionStore';
import { useWorkbenchViewStore } from '@/store/workbenchViewStore';

vi.mock('@/services/sessionActivation', () => ({ activateSessionCandidate: vi.fn() }));
beforeEach(() => {
    localStorage.clear();
    resetDeleteConfirmRequiredCacheForTest();
    vi.clearAllMocks();
    useSessionStore.setState({ sessionId: 'old' });
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => ({
        sessions: [{ id: 'target', title: '目标会话', model: 'test', workingDirectory: '/workspace', updatedAt: new Date().toISOString(), messageCount: 1 }],
        hasMore: false,
        groups: [{ status: 'REVIEWABLE', label: '待查看', tasks: [{ sessionId: 'target', title: '目标会话', folderName: 'workspace', status: 'REVIEWABLE', updatedAt: new Date().toISOString(), hint: '' }] }],
    }) }));
});
afterEach(() => vi.unstubAllGlobals());

it.each([false, true])('移动列表在同一行显示返回、标题和数量（简洁模式 %s）', async simple => {
    useWorkbenchViewStore.setState({ enabled: true, viewMode: simple ? 'simple' : 'development' });
    const onBack = vi.fn();
    const { rerender } = render(<SidebarTabContent activeTab="sessions" onBack={onBack} />);
    await screen.findByText('目标会话');
    const label = screen.getByText(simple ? '任务' : '会话', { exact: true });
    expect(label.parentElement).toContainElement(screen.getByRole('button', { name: '返回' }));
    expect(label.parentElement).toHaveTextContent('1');
    fireEvent.click(screen.getByRole('button', { name: '返回' }));
    expect(onBack).toHaveBeenCalledOnce();
    rerender(<SidebarTabContent activeTab="sessions" onCollapse={() => {}} />);
    expect(screen.queryByRole('button', { name: '返回' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '收起整个对话列表' })).toBeInTheDocument();
});

it('会话列表加载中仍可返回', () => {
    useWorkbenchViewStore.setState({ enabled: true, viewMode: 'development' });
    vi.stubGlobal('fetch', vi.fn(() => new Promise(() => {})));
    const onBack = vi.fn();
    render(<SidebarTabContent activeTab="sessions" onBack={onBack} />);
    fireEvent.click(screen.getByRole('button', { name: '返回' }));
    expect(onBack).toHaveBeenCalledOnce();
});

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
