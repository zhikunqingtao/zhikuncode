import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useSkillStore, type SkillItem } from '@/store/skillStore';
import { SkillManagementPage } from './SkillManagementPage';

const skills: SkillItem[] = [
  { id: 'review-internal', name: '代码审查', description: '检查代码改动与风险', source: 'BUNDLED', enabled: true },
  { id: 'deploy ops', name: '发布检查', description: '检查部署环境', source: 'USER', enabled: false },
];

function response(payload: unknown, status = 200): Response {
  return { ok: status >= 200 && status < 300, status, json: async () => payload } as Response;
}

function stubFetch(handler?: (url: string, init?: RequestInit) => Response | Promise<Response> | undefined) {
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    const handled = handler?.(url, init);
    if (handled) return handled;
    if (url === '/api/skills/manage') return response({ skills, total: 2, enabledCount: 1 });
    throw new Error(`Unexpected request: ${url}`);
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

describe('SkillManagementPage', () => {
  beforeEach(() => {
    useSkillStore.setState({ skills: [], loaded: false, loading: false, error: null, stateError: null, pending: {} });
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('shows enabled and disabled skills and searches names, descriptions and localized sources', async () => {
    const fetchMock = stubFetch();
    render(<SkillManagementPage onClose={vi.fn()} />);

    expect(await screen.findByRole('switch', { name: '关闭 代码审查' })).toHaveAttribute('aria-checked', 'true');
    expect(screen.getByRole('switch', { name: '启用 发布检查' })).toHaveAttribute('aria-checked', 'false');
    expect(screen.getByText('2 个 Skill')).toBeInTheDocument();
    expect(screen.getByText('1 个已启用')).toBeInTheDocument();
    expect(screen.getByText(/所有设备和会话共享/)).toBeInTheDocument();
    expect(fetchMock.mock.calls.some(([url]) => String(url).includes('/manage/'))).toBe(false);

    const search = screen.getByRole('textbox', { name: '搜索 Skill' });
    fireEvent.change(search, { target: { value: '用户' } });
    expect(screen.queryByText('代码审查')).not.toBeInTheDocument();
    expect(screen.getByText('发布检查')).toBeInTheDocument();
    fireEvent.change(search, { target: { value: '风险' } });
    expect(screen.getByText('代码审查')).toBeInTheDocument();
    expect(screen.queryByText('发布检查')).not.toBeInTheDocument();
    fireEvent.change(search, { target: { value: '不存在' } });
    expect(screen.getByText('没有匹配的 Skill')).toBeInTheDocument();
  });

  it('waits for a successful update, prevents duplicate clicks and uses the registry id', async () => {
    let finish!: (value: Response) => void;
    const patch = new Promise<Response>(resolve => { finish = resolve; });
    const fetchMock = stubFetch((url, init) => {
      if (url === '/api/skills/manage/deploy%20ops/toggle?enabled=true' && init?.method === 'PATCH') return patch;
    });
    render(<SkillManagementPage onClose={vi.fn()} />);
    const toggle = await screen.findByRole('switch', { name: '启用 发布检查' });
    fireEvent.click(toggle);
    expect(toggle).toBeDisabled();
    expect(toggle).toHaveAttribute('aria-checked', 'false');
    fireEvent.click(toggle);
    expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'PATCH')).toHaveLength(1);

    await act(async () => { finish(response({ ...skills[1], enabled: true })); });
    expect(await screen.findByRole('switch', { name: '关闭 发布检查' })).toHaveAttribute('aria-checked', 'true');
    expect(screen.getByText('2 个已启用')).toBeInTheDocument();
  });

  it('retains the saved switch state when persistence fails', async () => {
    stubFetch((_url, init) => init?.method === 'PATCH' ? response({}, 500) : undefined);
    render(<SkillManagementPage onClose={vi.fn()} />);
    fireEvent.click(await screen.findByRole('switch', { name: '关闭 代码审查' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('500');
    expect(screen.getByRole('switch', { name: '关闭 代码审查' })).toHaveAttribute('aria-checked', 'true');
    expect(screen.getByRole('switch', { name: '关闭 代码审查' })).toBeEnabled();
    expect(screen.getByText('1 个已启用')).toBeInTheDocument();
  });

  it('loads disabled skill details on demand, retries failures and refreshes expanded details', async () => {
    let detailRequests = 0;
    stubFetch(url => {
      if (url !== '/api/skills/manage/deploy%20ops') return undefined;
      detailRequests++;
      if (detailRequests === 1) return response({}, 503);
      return response({ ...skills[1], content: `说明正文 ${detailRequests}`, filePath: '/skills/deploy/SKILL.md' });
    });
    render(<SkillManagementPage onClose={vi.fn()} />);
    fireEvent.click(await screen.findByRole('button', { name: '展开 发布检查 详情' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('503');
    fireEvent.click(screen.getByRole('button', { name: '重试读取详情' }));
    expect(await screen.findByText('说明正文 2')).toBeInTheDocument();
    expect(screen.getByText('/skills/deploy/SKILL.md')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '刷新 Skill 列表' }));
    expect(await screen.findByText('说明正文 3')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '收起 发布检查 详情' }));
    expect(screen.queryByText('说明正文 3')).not.toBeInTheDocument();
  });

  it('allows retry after the initial list fails', async () => {
    let attempts = 0;
    stubFetch(url => {
      if (url === '/api/skills/manage' && ++attempts === 1) return response({}, 503);
    });
    render(<SkillManagementPage onClose={vi.fn()} />);
    expect(await screen.findByRole('alert')).toHaveTextContent('503');
    fireEvent.click(screen.getByRole('button', { name: '刷新 Skill 列表' }));
    expect(await screen.findByText('代码审查')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('supports Escape dismissal and restores focus to the opener', async () => {
    stubFetch();
    const onClose = vi.fn();
    const opener = document.createElement('button');
    document.body.appendChild(opener);
    opener.focus();
    const view = render(<SkillManagementPage onClose={onClose} />);
    await screen.findByText('代码审查');
    const dialog = screen.getByRole('dialog', { name: 'Skill 管理' });
    await waitFor(() => expect(dialog).toHaveFocus());
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(onClose).toHaveBeenCalledOnce();
    view.unmount();
    expect(opener).toHaveFocus();
    opener.remove();
  });

  it.each(['constructor', '__proto__'])('keeps the %s skill switch actionable', async id => {
    const item = { ...skills[0], id, name: id };
    stubFetch((url, init) => {
      if (url === '/api/skills/manage') return response({ skills: [item] });
      if (init?.method === 'PATCH') return response({ ...item, enabled: false });
    });
    render(<SkillManagementPage onClose={vi.fn()} />);
    const toggle = await screen.findByRole('switch', { name: `关闭 ${id}` });
    expect(toggle).toBeEnabled();
    fireEvent.click(toggle);
    expect(await screen.findByRole('switch', { name: `启用 ${id}` })).toBeEnabled();
  });

  it('shows damaged server settings and disables switches while keeping refresh available', async () => {
    let damaged = true;
    const fetchMock = stubFetch(url => url === '/api/skills/manage'
      ? response({ skills, stateError: damaged ? '无法读取设置文件' : null }) : undefined);
    render(<SkillManagementPage onClose={vi.fn()} />);
    expect(await screen.findByRole('alert')).toHaveTextContent('无法读取设置文件');
    expect(screen.getByRole('alert')).toHaveTextContent('重启服务');
    screen.getAllByRole('switch').forEach(toggle => {
      expect(toggle).toBeDisabled();
      expect(toggle).toHaveAttribute('aria-checked', 'false');
    });
    expect(screen.getByRole('button', { name: '刷新 Skill 列表' })).toBeEnabled();
    expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'PATCH')).toHaveLength(0);
    damaged = false;
    fireEvent.click(screen.getByRole('button', { name: '刷新 Skill 列表' }));
    await waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument());
    expect(screen.getByRole('switch', { name: '关闭 代码审查' })).toBeEnabled();
  });

  it('rejects a malformed detail response and offers retry', async () => {
    stubFetch(url => url === '/api/skills/manage/deploy%20ops' ? response({ content: 'incomplete' }) : undefined);
    render(<SkillManagementPage onClose={vi.fn()} />);
    fireEvent.click(await screen.findByRole('button', { name: '展开 发布检查 详情' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('技能详情格式无效');
    expect(screen.getByRole('button', { name: '重试读取详情' })).toBeEnabled();
    expect(screen.queryByText('incomplete')).not.toBeInTheDocument();
  });
});
