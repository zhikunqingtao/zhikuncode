import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, afterEach, expect, it, vi } from 'vitest';
import { SessionMergePanel } from './SessionMergePanel';
import { isMergeSource, useSessionMergeStore, type MergeOperation } from '@/store/sessionMergeStore';
import { useSessionStore } from '@/store/sessionStore';
import { useModelStore } from '@/store/modelStore';
import { activateSessionCandidate } from '@/services/sessionActivation';
import type { SessionSummary } from '@/utils/sessionGroups';

vi.mock('@/components/ui/Dialog', () => ({ Dialog: ({ open, onClose, children }: any) => open
    ? <div role="dialog"><button onClick={onClose}>关闭面板</button>{children}</div> : null }));
vi.mock('@/services/sessionActivation', () => ({ activateSessionCandidate: vi.fn(), getPendingSessionActivation: () => null }));
const a: SessionSummary = { id: 'A', title: '开发 A', model: 'm', workingDirectory: '/project-a', messageCount: 1, costUsd: 0, createdAt: '', updatedAt: '' };
const b = { ...a, id: 'B', title: '开发 B', workingDirectory: '/project-b' };
const request = { sourceSessionIds: ['A', 'B'], primarySessionId: 'A', title: '', model: 'm' };
const operation: MergeOperation = { operationId: 'op', targetSessionId: 'E', status: 'preparing', stage: 'summarizing', lockedSourceSessionIds: ['A', 'B'], request, result: {} };
beforeEach(() => {
    localStorage.clear(); vi.clearAllMocks();
    useSessionMergeStore.setState({ pending: null, open: false, source: null, error: null, storageWarning: null, recoveryNotice: null, submitting: false });
    useSessionStore.setState({ sessionId: 'A', status: 'idle' });
    useModelStore.setState({ models: [{ id: 'm', displayName: 'Model', supportsImages: false, maxImages: 0 }], loaded: true });
    vi.mocked(activateSessionCandidate).mockResolvedValue({ status: 'activated', sessionId: 'E' });
    vi.stubGlobal('fetch', vi.fn().mockImplementation(async (url: string) => url === '/api/session-merges/active' ? { ok: true, status: 204 } : ({ ok: true, json: async () =>
        url === '/api/sessions/merge' || url === '/api/session-merges/op' ? operation : url === '/api/models'
            ? { models: useModelStore.getState().models } : { sessions: [a, b, { ...b, id: 'running', title: '正在执行', running: true }], hasMore: false } })));
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals(); vi.useRealTimers(); });
async function begin() {
    render(<SessionMergePanel />);
    act(() => useSessionMergeStore.getState().openDialog(a));
    fireEvent.click(await screen.findByRole('button', { name: /开发 B/ }));
    expect(screen.getByRole('button', { name: /正在执行/ })).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: '开始合并' }));
    await screen.findByText('生成交接摘要');
}
function complete() {
    useSessionMergeStore.setState({ pending: { key: 'key', request, operation: { ...operation, lockedSourceSessionIds: [], status: 'completed', stage: 'completed',
        result: { copiedCount: 2, warningCount: 1, warnings: [{ originalPath: '/missing.txt', status: 'missing', reason: '文件缺失' }] } } } });
}
it('opens E through existing activation only while the initiating panel remains open', async () => {
    await begin();
    act(complete);
    await waitFor(() => expect(activateSessionCandidate).toHaveBeenCalledWith('E'));
});
it('does not steal focus after switching away, even if the user returns to A', async () => {
    await begin();
    act(() => useSessionStore.setState({ sessionId: 'C' }));
    act(() => useSessionStore.setState({ sessionId: 'A' }));
    act(complete);
    expect(activateSessionCandidate).not.toHaveBeenCalled();
    expect(screen.getByText('/missing.txt：文件缺失')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '打开新会话' }));
    await waitFor(() => expect(activateSessionCandidate).toHaveBeenCalledWith('E'));
});
it('closing the panel keeps recovery available and prevents automatic activation', async () => {
    await begin();
    fireEvent.click(screen.getByRole('button', { name: '关闭面板' }));
    act(complete);
    expect(activateSessionCandidate).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: '合并结果' }));
    expect(screen.getByRole('button', { name: '打开新会话' })).toBeInTheDocument();
    expect(activateSessionCandidate).not.toHaveBeenCalled();
});
it.each([true, false])('shows rejected submission when the panel closes before the response=%s', async closeBeforeResponse => {
    let respond!: (response: Response) => void;
    const rejection = new Promise<Response>(resolve => { respond = resolve; });
    const defaultFetch = vi.mocked(fetch).getMockImplementation()!;
    vi.mocked(fetch).mockImplementation((url, options) => url === '/api/sessions/merge'
        ? rejection : defaultFetch(url, options));
    render(<SessionMergePanel />);
    act(() => useSessionMergeStore.getState().openDialog(a));
    let submission!: Promise<void>;
    act(() => { submission = useSessionMergeStore.getState().submit(request); });
    if (closeBeforeResponse) fireEvent.click(screen.getByRole('button', { name: '关闭面板' }));
    await act(async () => {
        respond({ ok: false, status: 409, json: async () => ({ error: { message: '来源会话正在执行，暂不能合并' } }) } as Response);
        await submission;
    });
    if (!closeBeforeResponse) fireEvent.click(screen.getByRole('button', { name: '关闭面板' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent('来源会话正在执行，暂不能合并');
    expect(screen.getByRole('alert')).toBeVisible();
    expect(useSessionMergeStore.getState().pending).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '关闭合并错误提示' }));
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
});
it('restores completed results without automatically changing the active session', () => {
    complete();
    render(<SessionMergePanel />);
    expect(screen.getByRole('button', { name: '合并结果' })).toBeInTheDocument();
    expect(activateSessionCandidate).not.toHaveBeenCalled();
});

it('uses the same goal preview as the session list when a session has no explicit title', async () => {
    const unnamed = { ...b, title: null, goalPreview: '继续实现订单校验' };
    vi.mocked(fetch).mockResolvedValue({ ok: true, json: async () => ({ sessions: [unnamed], hasMore: false }) } as Response);
    render(<SessionMergePanel />);
    act(() => useSessionMergeStore.getState().openDialog({ ...a, title: null, goalPreview: '修复订单查询' }));
    expect(within(screen.getByRole('group', { name: '已选来源会话' })).getByText(/修复订单查询/)).toBeInTheDocument();
    fireEvent.click(await screen.findByRole('button', { name: /继续实现订单校验/ }));
    expect(screen.getByRole('option', { name: '继续实现订单校验' })).toBeInTheDocument();
});

it.each(['/project-b', '/project-a'])('keeps displayed directories consistent with the selected primary when B uses %s', async directory => {
    const candidate = { ...b, workingDirectory: directory };
    vi.mocked(fetch).mockImplementation(async (url) => ({ ok: true, json: async () =>
        url === '/api/sessions/merge' ? operation : url === '/api/models'
            ? { models: useModelStore.getState().models } : { sessions: [candidate], hasMore: false } }) as Response);
    render(<SessionMergePanel />);
    act(() => useSessionMergeStore.getState().openDialog(a));
    fireEvent.click(await screen.findByRole('button', { name: /开发 B/ }));
    const details = within(screen.getByRole('group', { name: '新会话目录与权限' }));
    expect(details.getByText('新会话的主工作目录：').closest('p')).toHaveTextContent('/project-a');
    if (directory !== a.workingDirectory) {
        expect(details.getByText('外部引用目录：').closest('p')).toHaveTextContent(directory);
    } else {
        expect(details.getByText('所选来源会话使用同一工作目录。')).toBeInTheDocument();
        expect(details.queryByText(/外部引用目录：/)).not.toBeInTheDocument();
    }
    fireEvent.change(screen.getByRole('combobox', { name: '主会话（使用其工程目录）' }), { target: { value: 'B' } });
    expect(details.getByText('新会话的主工作目录：').closest('p')).toHaveTextContent(directory);
    if (directory !== a.workingDirectory) {
        expect(details.getByText('外部引用目录：').closest('p')).toHaveTextContent('/project-a');
    }
    expect(details.getByText('新会话将使用“完全访问权限”').closest('p')).toHaveTextContent('跨目录读写操作可能无需再次确认');
    fireEvent.click(screen.getByRole('button', { name: '开始合并' }));
    await screen.findByText('生成交接摘要');
    const submission = vi.mocked(fetch).mock.calls.find(([url]) => url === '/api/sessions/merge');
    expect(JSON.parse(submission![1]!.body as string).primarySessionId).toBe('B');
});

it('shows recovery storage warning even with the panel closed and lets the user dismiss it', () => {
    useSessionMergeStore.setState({ storageWarning: '恢复信息无法保存' });
    render(<SessionMergePanel />);
    expect(screen.getByRole('alert')).toHaveTextContent('恢复信息无法保存');
    fireEvent.click(screen.getByRole('button', { name: '关闭恢复提示' }));
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
});

it('limits selection to five, preserves it across search and pagination, and resets a removed primary', async () => {
    const c = { ...b, id: 'C', title: '开发 C', model: 'c-model' };
    const d = { ...b, id: 'D', title: '开发 D', workingDirectory: '/project-d' };
    const e = { ...d, id: 'E', title: '开发 E' };
    const f = { ...b, id: 'F', title: '开发 F' };
    vi.mocked(fetch).mockImplementation(async (url, options) => {
        const path = String(url);
        const params = new URL(path, 'http://test').searchParams;
        let body: unknown;
        if (path === '/api/models') body = { models: useModelStore.getState().models };
        else if (path === '/api/sessions/merge') {
            const submitted = JSON.parse(options!.body as string);
            body = { ...operation, request: submitted };
        } else if (params.get('query')) body = { sessions: [f], hasMore: false };
        else if (params.get('cursor')) body = { sessions: [d, e, f], hasMore: false };
        else body = { sessions: [b, c], hasMore: true, nextCursor: 'page2' };
        return { ok: true, json: async () => body } as Response;
    });
    render(<SessionMergePanel />);
    act(() => useSessionMergeStore.getState().openDialog(a));
    expect(screen.getByRole('button', { name: '开始合并' })).toBeDisabled();
    const candidates = () => within(screen.getByRole('group', { name: '选择来源会话' }));
    fireEvent.click(await candidates().findByRole('button', { name: /开发 B/ }));
    fireEvent.click(candidates().getByRole('button', { name: /开发 C/ }));
    fireEvent.click(screen.getByRole('button', { name: '加载更多会话' }));
    fireEvent.click(await candidates().findByRole('button', { name: /开发 D/ }));
    fireEvent.click(candidates().getByRole('button', { name: /开发 E/ }));
    expect(screen.getByText('已选 5/5')).toBeInTheDocument();
    expect(candidates().getByRole('button', { name: /开发 F/ })).toBeDisabled();
    expect(candidates().getByRole('button', { name: /开发 B/ })).toBeEnabled();
    const directories = within(screen.getByRole('group', { name: '新会话目录与权限' }));
    expect(directories.getAllByText('外部引用目录：')).toHaveLength(2);
    fireEvent.change(screen.getByRole('combobox', { name: '主会话（使用其工程目录）' }), { target: { value: 'C' } });
    expect(screen.getByRole('combobox', { name: '目标模型' })).toHaveValue('c-model');
    fireEvent.change(screen.getByRole('textbox', { name: '新会话标题' }), { target: { value: '保留标题' } });
    fireEvent.change(screen.getByRole('textbox', { name: '搜索来源会话' }), { target: { value: '开发 F' } });
    expect(await candidates().findByRole('button', { name: /开发 F/ })).toBeDisabled();
    expect(screen.getByText('已选 5/5')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '移除 开发 C' }));
    expect(screen.getByRole('combobox', { name: '主会话（使用其工程目录）' })).toHaveValue('A');
    expect(screen.getByRole('combobox', { name: '目标模型' })).toHaveValue('m');
    expect(screen.getByRole('textbox', { name: '新会话标题' })).toHaveValue('保留标题');
    fireEvent.click(candidates().getByRole('button', { name: /开发 F/ }));
    expect(screen.getByText('已选 5/5')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '移除 开发 A' })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '开始合并' }));
    await screen.findByText('2 个来源会话正在复制；快照封存后可继续使用来源。');
    const call = vi.mocked(fetch).mock.calls.find(([url]) => url === '/api/sessions/merge');
    expect(JSON.parse(call![1]!.body as string)).toMatchObject({ sourceSessionIds: ['A', 'B', 'D', 'E', 'F'], primarySessionId: 'A', title: '保留标题' });
});

it.each([true, false])('shows missing-operation recovery with panel open=%s without switching sessions', async open => {
    useSessionMergeStore.setState({ pending: { key: 'key', request, operation }, open });
    vi.mocked(fetch).mockResolvedValue({ ok: false, status: 404,
        json: async () => ({ error: { code: 'MERGE_OPERATION_NOT_FOUND' } }) } as Response);
    render(<SessionMergePanel />);
    expect(await screen.findByRole('alert')).toHaveTextContent('已解除本地占用');
    expect(useSessionMergeStore.getState().pending).toBeNull();
    expect(screen.queryByRole('button', { name: '合并中 · 查看进度' })).not.toBeInTheDocument();
    expect(activateSessionCandidate).not.toHaveBeenCalled();
    expect(useSessionStore.getState().sessionId).toBe('A');
    expect(fetch).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByRole('button', { name: '关闭合并恢复提示' }));
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
});

const progressResponse = (progress: MergeOperation) => ({ ok: true, json: async () => progress }) as Response;
const failedOperation: MergeOperation = { ...operation, lockedSourceSessionIds: [], status: 'failed', stage: 'failed', error: '合并总时限已到' };

it.each(['focus', 'online', 'session-list-updated', 'visible', 'reopen'] as const)(
    'recovers the server failure immediately on %s without resubmitting or switching sessions', async trigger => {
        vi.useFakeTimers();
        useSessionMergeStore.setState({ pending: { key: 'key', request, operation } });
        vi.mocked(fetch).mockResolvedValueOnce(progressResponse(operation))
            .mockResolvedValueOnce(progressResponse(failedOperation));
        await act(async () => { render(<SessionMergePanel />); });
        expect(fetch).toHaveBeenCalledTimes(1);
        expect(isMergeSource('A')).toBe(true);
        const listUpdated = vi.fn();
        window.addEventListener('session-list-updated', listUpdated);
        try {
            if (trigger === 'visible') {
                const visibility = vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden');
                await act(async () => { document.dispatchEvent(new Event('visibilitychange')); });
                expect(fetch).toHaveBeenCalledTimes(1);
                visibility.mockReturnValue('visible');
            }
            await act(async () => {
                if (trigger === 'reopen') fireEvent.click(screen.getByRole('button', { name: '合并中 · 查看进度' }));
                else if (trigger === 'visible') document.dispatchEvent(new Event('visibilitychange'));
                else window.dispatchEvent(new Event(trigger));
            });
            expect(useSessionMergeStore.getState().pending?.operation?.status).toBe('failed');
            expect(isMergeSource('A')).toBe(false);
            expect(isMergeSource('B')).toBe(false);
            expect(listUpdated).toHaveBeenCalledTimes(trigger === 'session-list-updated' ? 2 : 1);
            expect(screen.queryByRole('button', { name: '合并中 · 查看进度' })).not.toBeInTheDocument();
            if (trigger === 'reopen') expect(screen.getByRole('alert')).toHaveTextContent('合并总时限已到');
            else expect(screen.getByRole('button', { name: '合并结果' })).toBeInTheDocument();
            expect(fetch).toHaveBeenCalledTimes(2);
            for (const [url, options] of vi.mocked(fetch).mock.calls) {
                expect(url).toBe('/api/session-merges/op');
                expect(options?.method).not.toBe('POST');
            }
            expect(activateSessionCandidate).not.toHaveBeenCalled();
            expect(useSessionStore.getState().sessionId).toBe('A');
            await act(async () => {
                window.dispatchEvent(new Event('focus'));
                window.dispatchEvent(new Event('online'));
                window.dispatchEvent(new Event('session-list-updated'));
                await vi.advanceTimersByTimeAsync(2000);
            });
            expect(fetch).toHaveBeenCalledTimes(2);
        } finally { window.removeEventListener('session-list-updated', listUpdated); }
    },
);

it('keeps unknown progress visible while closed and preserves occupancy until a manual query succeeds', async () => {
    vi.useFakeTimers();
    useSessionMergeStore.setState({ pending: { key: 'key', request, operation } });
    vi.mocked(fetch).mockRejectedValue(new Error('网络不可用'));
    await act(async () => { render(<SessionMergePanel />); });
    expect(screen.getByRole('alert')).toHaveTextContent('网络不可用');
    expect(screen.getByRole('button', { name: '合并状态待确认 · 查看进度' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '重试查询' })).toBeEnabled();
    expect(isMergeSource('A')).toBe(true);
    expect(isMergeSource('B')).toBe(true);
    await act(async () => {
        fireEvent.click(screen.getByRole('button', { name: '合并状态待确认 · 查看进度' }));
    });
    expect(screen.getByRole('status')).toHaveTextContent('合并状态待确认');
    expect(screen.getByText('进度同步失败，正在重试查询；来源占用以服务端为准。')).toBeInTheDocument();
    expect(screen.queryByText(/个来源会话暂被占用/)).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '关闭面板' }));

    let respond!: (response: Response) => void;
    vi.mocked(fetch).mockImplementationOnce(() => new Promise(resolve => { respond = resolve; }));
    fireEvent.click(screen.getByRole('button', { name: '重试查询' }));
    expect(screen.getByRole('button', { name: '重试查询' })).not.toBeDisabled();
    expect(isMergeSource('A')).toBe(true);
    await act(async () => { respond(progressResponse(operation)); });
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '合并中 · 查看进度' })).toBeInTheDocument();
    expect(useSessionMergeStore.getState().error).toBeNull();
    expect(isMergeSource('A')).toBe(true);
    expect(fetch).toHaveBeenCalledTimes(3);
    expect(vi.mocked(fetch).mock.calls.every(([url, options]) => url === '/api/session-merges/op' && options?.method !== 'POST')).toBe(true);
    expect(activateSessionCandidate).not.toHaveBeenCalled();
});

it('coalesces recovery signals with the active query and removes listeners and polling on unmount', async () => {
    vi.useFakeTimers();
    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible');
    useSessionMergeStore.setState({ pending: { key: 'key', request, operation } });
    let respond!: (response: Response) => void;
    vi.mocked(fetch).mockImplementationOnce(() => new Promise(resolve => { respond = resolve; }));
    const { unmount } = render(<SessionMergePanel />);
    const recoverySignals = () => {
        window.dispatchEvent(new Event('focus'));
        window.dispatchEvent(new Event('online'));
        window.dispatchEvent(new Event('session-list-updated'));
        document.dispatchEvent(new Event('visibilitychange'));
    };
    act(() => {
        recoverySignals();
        fireEvent.click(screen.getByRole('button', { name: '合并中 · 查看进度' }));
    });
    await act(async () => { await vi.advanceTimersByTimeAsync(2000); });
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(isMergeSource('A')).toBe(true);
    await act(async () => { respond(progressResponse(operation)); });
    await act(async () => { await vi.advanceTimersByTimeAsync(2000); });
    expect(fetch).toHaveBeenCalledTimes(2);
    unmount();
    await act(async () => {
        recoverySignals();
        await vi.advanceTimersByTimeAsync(4000);
    });
    expect(fetch).toHaveBeenCalledTimes(2);
    expect(isMergeSource('A')).toBe(true);
    expect(activateSessionCandidate).not.toHaveBeenCalled();
});
