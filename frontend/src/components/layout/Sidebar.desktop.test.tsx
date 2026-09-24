import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { Sidebar, resetDeleteConfirmRequiredCacheForTest } from './Sidebar';
import { useAppUiStore } from '@/store/appUiStore';
import { useSessionStore } from '@/store/sessionStore';

function mockSessionListFetch() {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => ({
        sessions: [{ id: 'target', title: '目标会话', model: 'test', workingDirectory: '/workspace', updatedAt: new Date().toISOString(), messageCount: 1 }],
        hasMore: false,
        groups: [],
    }) }));
}

/** 两条会话（target 为当前会话，other 未选中），供选中态 pill 测试使用 */
function mockTwoSessionsFetch() {
    const row = (id: string, title: string) => ({ id, title, model: 'test', workingDirectory: '/workspace', updatedAt: new Date().toISOString(), messageCount: 1 });
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => ({
        sessions: [row('target', '目标会话'), row('other', '其他会话')],
        hasMore: false,
    }) }));
}

beforeEach(() => {
    localStorage.clear();
    resetDeleteConfirmRequiredCacheForTest();
    useSessionStore.setState({ sessionId: null });
    mockSessionListFetch();
    // framer-motion useReducedMotion 依赖 matchMedia（jsdom 未实现）
    window.matchMedia = vi.fn().mockImplementation((query: string) => ({
        matches: false,
        media: query,
        onchange: null,
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
        addListener: vi.fn(),
        removeListener: vi.fn(),
        dispatchEvent: vi.fn(),
    })) as unknown as typeof window.matchMedia;
});
afterEach(() => vi.unstubAllGlobals());

it('桌面端不再渲染图标轨，默认直接呈现会话列表面板', async () => {
    render(<Sidebar />);
    expect(document.querySelector('.sidebar-rail')).toBeNull();
    expect(await screen.findByText('目标会话')).toBeInTheDocument();
});

it('搜索会话：防抖 250ms 后携带 query 参数请求服务端，且结果不做客户端二次过滤', async () => {
    const fetchMock = vi.fn().mockImplementation(async (input: RequestInfo | URL) => {
        const url = String(input);
        // 带 query= 的是服务端搜索请求：返回一个标题不含搜索词的会话，
        // 若客户端仍做二次过滤，这条结果会被错误地过滤掉
        const sessions = url.includes('query=')
            ? [{ id: 'srv-hit', title: '标题不含搜索词', model: 'test', workingDirectory: '/workspace', updatedAt: new Date().toISOString(), messageCount: 1 }]
            : [{ id: 'target', title: '目标会话', model: 'test', workingDirectory: '/workspace', updatedAt: new Date().toISOString(), messageCount: 1 }];
        return { ok: true, json: async () => ({ sessions, hasMore: false }) };
    });
    vi.stubGlobal('fetch', fetchMock);

    render(<Sidebar />);
    await screen.findByText('目标会话');

    vi.useFakeTimers();
    try {
        fireEvent.change(screen.getByLabelText('搜索会话'), { target: { value: '订单' } });
        // 防抖窗口内不应立刻发出带 query 的请求
        expect(fetchMock.mock.calls.filter(c => String(c[0]).includes('query='))).toHaveLength(0);
        await act(async () => { vi.advanceTimersByTime(300); });
    } finally {
        vi.useRealTimers();
    }

    // 防抖后恰好发出一次带 query 参数的请求
    const searchCalls = fetchMock.mock.calls.filter(c => String(c[0]).includes('query='));
    expect(searchCalls).toHaveLength(1);
    expect(decodeURIComponent(String(searchCalls[0][0]))).toContain('query=订单');

    // 服务端命中的会话照常渲染（无客户端二次过滤），且列表被搜索结果整体替换
    expect(await screen.findByText('标题不含搜索词')).toBeInTheDocument();
    expect(screen.queryByText('目标会话')).not.toBeInTheDocument();
});

it('收起后左侧保留固定展开条，点击恢复展开', async () => {
    render(<Sidebar />);
    await screen.findByText('目标会话');
    fireEvent.click(screen.getByRole('button', { name: '收起整个对话列表' }));
    // 面板内容隐藏，但展开条常驻在左侧栏内（可发现性）
    expect(screen.queryByText('目标会话')).not.toBeInTheDocument();
    const expand = await screen.findByRole('button', { name: '展开侧栏列表' });
    expect(expand.closest('.app-sidebar')).not.toBeNull();
    expect(expand).toHaveTextContent('展开列表');
    fireEvent.click(expand);
    await screen.findByText('目标会话');
});

it('内部路径切入其他面板时提供返回会话列表出口', async () => {
    render(<Sidebar />);
    await screen.findByText('目标会话');
    act(() => useAppUiStore.getState().requestVisualizationTab('tasks'));
    const back = await screen.findByRole('button', { name: '返回会话列表' });
    fireEvent.click(back);
    await screen.findByText('目标会话');
    expect(screen.queryByRole('button', { name: '返回会话列表' })).not.toBeInTheDocument();
});

it('搜索立即失效旧响应，首页挂起时不能分页，分页中不能重复请求', async () => {
    const row = (id: string) => ({ id, title: id, model: 'test', workingDirectory: '/workspace', updatedAt: new Date().toISOString(), messageCount: 1 });
    const response = (id: string, hasMore = true) => ({ ok: true, json: async () => ({ sessions: [row(id)], hasMore, nextCursor: id }) });
    const pending: Array<{ url: string; resolve: (value: ReturnType<typeof response>) => void }> = [];
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL) => {
        const url = String(input);
        if (!url.startsWith('/api/sessions?')) return Promise.resolve(response('unused', false));
        if (!url.includes('query=') && !pending.length && fetchMock.mock.calls.filter(c => String(c[0]).startsWith('/api/sessions?')).length === 1) {
            return Promise.resolve(response('old'));
        }
        return new Promise(resolve => pending.push({ url, resolve }));
    });
    vi.stubGlobal('fetch', fetchMock);
    render(<Sidebar />);
    await screen.findByText('old');
    // A refresh of the old query is already in flight when typing starts.
    act(() => window.dispatchEvent(new Event('session-list-updated')));
    expect(pending).toHaveLength(1);
    vi.useFakeTimers();
    try {
        const input = screen.getByLabelText('搜索会话');
        input.focus();
        fireEvent.change(input, { target: { value: 'new' } });
        expect(input).toHaveFocus();
        expect(screen.queryByText('old')).not.toBeInTheDocument();
        expect(screen.queryByRole('button', { name: '加载更多...' })).not.toBeInTheDocument();
        await act(async () => pending[0].resolve(response('stale')));
        expect(screen.queryByText('stale')).not.toBeInTheDocument();
        await act(async () => { vi.advanceTimersByTime(250); });
        expect(pending).toHaveLength(2);
        expect(pending[1].url).toContain('query=new');
        expect(pending[1].url).not.toContain('cursor=');
        expect(screen.queryByRole('button', { name: '加载更多...' })).not.toBeInTheDocument();
        await act(async () => pending[1].resolve(response('new-first')));
        const more = screen.getByRole('button', { name: '加载更多...' });
        fireEvent.click(more);
        fireEvent.click(more);
        expect(pending).toHaveLength(3);
        expect(more).toBeDisabled();
        expect(pending[2].url).toContain('cursor=new-first');
        // Changing query also rejects a pending page from the previous search.
        fireEvent.change(input, { target: { value: 'next' } });
        await act(async () => pending[2].resolve(response('stale-page')));
        expect(screen.queryByText('stale-page')).not.toBeInTheDocument();
        await act(async () => { vi.advanceTimersByTime(250); });
        await act(async () => pending[3].resolve(response('next-first')));
        fireEvent.click(screen.getByRole('button', { name: '加载更多...' }));
        await act(async () => pending[4].resolve(response('next-second', false)));
        expect(screen.getByText('next-first')).toBeInTheDocument();
        expect(screen.getByText('next-second')).toBeInTheDocument();
        expect(screen.queryByText('new-first')).not.toBeInTheDocument();
    } finally {
        vi.useRealTimers();
    }
});

it('搜索首页和分页保留服务端顺序，清空搜索后仍使用普通列表稳定排序', async () => {
    const row = (id: string, day: number) => ({ id, title: id, model: 'test', workingDirectory: '/sort-test', updatedAt: `2026-09-${day}T00:00:00Z`, messageCount: 1 });
    const newer = row('sort-newer', 17);
    const middle = row('sort-middle', 16);
    const oldest = row('sort-oldest', 15);
    let plainRequests = 0;
    vi.stubGlobal('fetch', vi.fn().mockImplementation(async (input: RequestInfo | URL) => {
        const url = new URL(String(input), 'http://localhost');
        const searching = url.searchParams.has('query');
        const paging = url.searchParams.has('cursor');
        const sessions = searching
            ? paging ? [middle, oldest] : [newer, middle]
            : ++plainRequests === 1 ? [newer, oldest] : [oldest, newer];
        return { ok: true, json: async () => ({ sessions, hasMore: searching && !paging, nextCursor: middle.id }) };
    }));
    render(<Sidebar />);
    await screen.findByText(newer.title);
    const displayed = () => screen.getAllByText(/^sort-(newer|middle|oldest)$/).map(node => node.textContent);
    expect(displayed()).toEqual([newer.title, oldest.title]);
    vi.useFakeTimers();
    try {
        fireEvent.change(screen.getByLabelText('搜索会话'), { target: { value: 'sort' } });
        await act(async () => { vi.advanceTimersByTime(250); });
        expect(displayed()).toEqual([newer.title, middle.title]);
        await act(async () => { fireEvent.click(screen.getByRole('button', { name: '加载更多...' })); });
        expect(displayed()).toEqual([newer.title, middle.title, oldest.title]);
        fireEvent.change(screen.getByLabelText('搜索会话'), { target: { value: '' } });
        await act(async () => { vi.advanceTimersByTime(1); });
        expect(displayed()).toEqual([newer.title, oldest.title]);
    } finally {
        vi.useRealTimers();
    }
});

it('当前会话卡渲染 .session-active-pill 且标题为 font-semibold', async () => {
    mockTwoSessionsFetch();
    useSessionStore.setState({ sessionId: 'target' });
    render(<Sidebar />);
    const title = await screen.findByText('目标会话');
    const card = title.closest('.session-card');
    expect(card).not.toBeNull();
    expect(card).toHaveAttribute('data-active', 'true');
    expect(card!.querySelector('.session-active-pill')).not.toBeNull();
    expect(title).toHaveClass('font-semibold');
});

it('非选中卡不渲染 pill', async () => {
    mockTwoSessionsFetch();
    useSessionStore.setState({ sessionId: 'target' });
    render(<Sidebar />);
    await screen.findByText('目标会话');
    const otherTitle = screen.getByText('其他会话');
    const otherCard = otherTitle.closest('.session-card');
    expect(otherCard).not.toBeNull();
    expect(otherCard).toHaveAttribute('data-active', 'false');
    expect(otherCard!.querySelector('.session-active-pill')).toBeNull();
    expect(otherTitle).toHaveClass('font-medium');
});

// ═══ 删除会话二次确认气泡 ═══

/** mock 会话列表 + delete-confirm 配置 + DELETE 响应（deleteStatuses 依次消费） */
function mockDeleteFlowFetch(options?: { required?: boolean; deleteStatuses?: number[] }) {
    const statuses = [...(options?.deleteStatuses ?? [200])];
    const fetchMock = vi.fn().mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input);
        if (init?.method === 'DELETE') {
            const status = statuses.length > 1 ? statuses.shift()! : statuses[0];
            return { ok: status >= 200 && status < 300, status, json: async () => ({ success: status < 300 }) };
        }
        if (url.startsWith('/api/config/delete-confirm')) {
            return { ok: true, status: 200, json: async () => ({ required: options?.required ?? false }) };
        }
        return { ok: true, status: 200, json: async () => ({
            sessions: [{ id: 'target', title: '目标会话', model: 'test', workingDirectory: '/workspace', updatedAt: new Date().toISOString(), messageCount: 1 }],
            hasMore: false,
        }) };
    });
    vi.stubGlobal('fetch', fetchMock);
    return fetchMock;
}

const deleteCallsOf = (fetchMock: ReturnType<typeof vi.fn>) =>
    fetchMock.mock.calls.filter(call => (call[1] as RequestInit | undefined)?.method === 'DELETE');

const deleteConfirmCallsOf = (fetchMock: ReturnType<typeof vi.fn>) =>
    fetchMock.mock.calls.filter(call => String(call[0]).startsWith('/api/config/delete-confirm'));

/**
 * mock 会话列表，且 delete-confirm 配置响应可通过 returnedConfig 在两次挂载之间切换：
 * - { ok: false } 模拟 HTTP 错误（如 502）；'reject' 模拟网络层失败；否则返回 required 配置。
 */
function mockSwitchableDeleteConfirmFetch() {
    const state: { config: { ok: false; status: number } | 'reject' | { ok: true; status: number; required: boolean } } = {
        config: { ok: true, status: 200, required: false },
    };
    const fetchMock = vi.fn().mockImplementation(async (input: RequestInfo | URL) => {
        const url = String(input);
        if (url.startsWith('/api/config/delete-confirm')) {
            const config = state.config;
            if (config === 'reject') throw new Error('network down');
            if (!config.ok) return { ok: false, status: config.status, json: async () => ({}) };
            return { ok: true, status: config.status, json: async () => ({ required: config.required }) };
        }
        return { ok: true, status: 200, json: async () => ({
            sessions: [{ id: 'target', title: '目标会话', model: 'test', workingDirectory: '/workspace', updatedAt: new Date().toISOString(), messageCount: 1 }],
            hasMore: false,
        }) };
    });
    vi.stubGlobal('fetch', fetchMock);
    return { fetchMock, state };
}

it('点击垃圾桶弹出确认气泡，此时尚未发出 DELETE 请求', async () => {
    const fetchMock = mockDeleteFlowFetch();
    render(<Sidebar />);
    await screen.findByText('目标会话');
    fireEvent.click(screen.getByRole('button', { name: '删除会话' }));
    expect(await screen.findByText('确定删除该会话？')).toBeInTheDocument();
    expect(screen.getByText('删除后不可恢复')).toBeInTheDocument();
    expect(deleteCallsOf(fetchMock)).toHaveLength(0);
});

it('点取消关闭气泡且不删除会话', async () => {
    const fetchMock = mockDeleteFlowFetch();
    render(<Sidebar />);
    await screen.findByText('目标会话');
    fireEvent.click(screen.getByRole('button', { name: '删除会话' }));
    await screen.findByText('确定删除该会话？');
    fireEvent.click(screen.getByRole('button', { name: '取消' }));
    expect(screen.queryByText('确定删除该会话？')).not.toBeInTheDocument();
    expect(deleteCallsOf(fetchMock)).toHaveLength(0);
    expect(screen.getByText('目标会话')).toBeInTheDocument();
});

it('未配置验证码时确认删除直接发出 DELETE（不带验证码头）', async () => {
    const fetchMock = mockDeleteFlowFetch();
    render(<Sidebar />);
    await screen.findByText('目标会话');
    fireEvent.click(screen.getByRole('button', { name: '删除会话' }));
    await screen.findByText('确定删除该会话？');
    expect(screen.queryByLabelText('删除验证码')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '确认删除' }));
    await waitFor(() => expect(screen.queryByText('目标会话')).not.toBeInTheDocument());
    expect(deleteCallsOf(fetchMock)).toHaveLength(1);
    expect(String(deleteCallsOf(fetchMock)[0][0])).toBe('/api/sessions/target');
    expect((deleteCallsOf(fetchMock)[0][1] as RequestInit).headers).toBeUndefined();
    expect(screen.queryByText('确定删除该会话？')).not.toBeInTheDocument();
});

it('配置验证码后 DELETE 携带请求头，403 时气泡内提示错误且保持打开', async () => {
    const fetchMock = mockDeleteFlowFetch({ required: true, deleteStatuses: [403, 200] });
    render(<Sidebar />);
    await screen.findByText('目标会话');
    fireEvent.click(screen.getByRole('button', { name: '删除会话' }));
    // 验证码输入框出现，未输入时确认按钮禁用
    const codeInput = await screen.findByLabelText('删除验证码');
    expect(screen.getByRole('button', { name: '确认删除' })).toBeDisabled();
    fireEvent.change(codeInput, { target: { value: 'wrong' } });
    fireEvent.click(screen.getByRole('button', { name: '确认删除' }));
    // 403：气泡保持打开、红字提示、输入框清空，DELETE 已携带错误验证码
    expect(await screen.findByText('验证码错误，请重新输入')).toBeInTheDocument();
    expect(screen.getByText('确定删除该会话？')).toBeInTheDocument();
    expect(deleteCallsOf(fetchMock)).toHaveLength(1);
    expect((deleteCallsOf(fetchMock)[0][1] as RequestInit).headers).toEqual({ 'X-Delete-Confirm-Code': 'wrong' });
    // 重新输入正确验证码后删除成功，气泡关闭
    fireEvent.change(screen.getByLabelText('删除验证码'), { target: { value: 'zhikun' } });
    fireEvent.click(screen.getByRole('button', { name: '确认删除' }));
    await waitFor(() => expect(screen.queryByText('目标会话')).not.toBeInTheDocument());
    expect(deleteCallsOf(fetchMock)).toHaveLength(2);
    expect((deleteCallsOf(fetchMock)[1][1] as RequestInit).headers).toEqual({ 'X-Delete-Confirm-Code': 'zhikun' });
    expect(screen.queryByText('确定删除该会话？')).not.toBeInTheDocument();
});

it('delete-confirm 配置返回 HTTP 502 时降级为无验证码输入框，且失败结果不缓存（重挂载后重试成功）', async () => {
    const { fetchMock, state } = mockSwitchableDeleteConfirmFetch();
    state.config = { ok: false, status: 502 };

    // 首次挂载：配置请求 502 → 按"不需要验证码"降级，气泡内无验证码输入框
    const first = render(<Sidebar />);
    await screen.findByText('目标会话');
    fireEvent.click(screen.getByRole('button', { name: '删除会话' }));
    await screen.findByText('确定删除该会话？');
    await waitFor(() => expect(deleteConfirmCallsOf(fetchMock)).toHaveLength(1));
    expect(screen.queryByLabelText('删除验证码')).not.toBeInTheDocument();
    first.unmount();

    // 后端恢复 200 {"required":true}：失败结果未被缓存 → 重挂载重新发起配置请求 → 出现验证码输入框
    state.config = { ok: true, status: 200, required: true };
    render(<Sidebar />);
    await screen.findByText('目标会话');
    fireEvent.click(screen.getByRole('button', { name: '删除会话' }));
    expect(await screen.findByLabelText('删除验证码')).toBeInTheDocument();
    // 确实发生了第二次配置请求（证明 502 的失败结果被清除并重试，而非永久缓存为"不需要"）
    expect(deleteConfirmCallsOf(fetchMock)).toHaveLength(2);
});

it('delete-confirm 配置请求网络层失败时降级为无验证码输入框，且失败结果不缓存（重挂载后重试成功）', async () => {
    const { fetchMock, state } = mockSwitchableDeleteConfirmFetch();
    state.config = 'reject';

    // 首次挂载：配置请求 reject → 按"不需要验证码"降级，气泡内无验证码输入框
    const first = render(<Sidebar />);
    await screen.findByText('目标会话');
    fireEvent.click(screen.getByRole('button', { name: '删除会话' }));
    await screen.findByText('确定删除该会话？');
    await waitFor(() => expect(deleteConfirmCallsOf(fetchMock)).toHaveLength(1));
    expect(screen.queryByLabelText('删除验证码')).not.toBeInTheDocument();
    first.unmount();

    // 网络恢复且后端要求验证码：失败结果未被缓存 → 重挂载重新发起配置请求 → 出现验证码输入框
    state.config = { ok: true, status: 200, required: true };
    render(<Sidebar />);
    await screen.findByText('目标会话');
    fireEvent.click(screen.getByRole('button', { name: '删除会话' }));
    expect(await screen.findByLabelText('删除验证码')).toBeInTheDocument();
    expect(deleteConfirmCallsOf(fetchMock)).toHaveLength(2);
});
