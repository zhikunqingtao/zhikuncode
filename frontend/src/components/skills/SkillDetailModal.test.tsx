import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { SkillDetailModal } from './SkillDetailModal';
import { useSkillStore, type SkillDetail } from '@/store/skillStore';

const originalLoad = useSkillStore.getState().loadSkills;
const detail: SkillDetail = { id: 'demo', name: 'Demo', description: 'Demo workflow', source: 'PROJECT', enabled: true, content: 'Skill body', filePath: '/project/SKILL.md' };

describe('SkillDetailModal availability', () => {
  beforeEach(() => {
    useSkillStore.setState({ skills: [detail], loaded: true, pending: {}, error: null, loadSkills: vi.fn(async () => {}) });
    vi.stubGlobal('fetch', vi.fn(async () => ({ ok: true, json: async () => detail })));
  });
  afterEach(() => {
    cleanup();
    useSkillStore.setState({ loadSkills: originalLoad });
    vi.unstubAllGlobals();
  });

  it('disables execution in an already open detail when a remote client closes the skill', async () => {
    const execute = vi.fn();
    render(<SkillDetailModal skillName="Demo" onClose={vi.fn()} onExecute={execute} />);
    await screen.findByText('Skill body');
    expect(screen.getByRole('button', { name: '执行技能' })).toBeEnabled();
    act(() => useSkillStore.setState({ skills: [{ ...detail, enabled: false }] }));
    expect(screen.getByRole('button', { name: '执行技能' })).toBeDisabled();
    expect(screen.getByRole('status')).toHaveTextContent('该技能已关闭');
    expect(execute).not.toHaveBeenCalled();
  });

  it('checks the shared server state again before invoking a stale detail', async () => {
    const execute = vi.fn();
    useSkillStore.setState({ loadSkills: vi.fn(async () => {
      useSkillStore.setState({ skills: [{ ...detail, enabled: false }] });
    }) });
    render(<SkillDetailModal skillName="Demo" onClose={vi.fn()} onExecute={execute} />);
    await screen.findByText('Skill body');
    await act(async () => fireEvent.click(screen.getByRole('button', { name: '执行技能' })));
    expect(execute).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: '执行技能' })).toBeDisabled();
  });

  it('executes an enabled skill with the supplied arguments', async () => {
    const execute = vi.fn();
    render(<SkillDetailModal skillName="Demo" onClose={vi.fn()} onExecute={execute} />);
    await screen.findByText('Skill body');
    fireEvent.change(screen.getByLabelText('补充说明（可选）'), { target: { value: '  review this  ' } });
    await act(async () => fireEvent.click(screen.getByRole('button', { name: '执行技能' })));
    expect(execute).toHaveBeenCalledWith('demo', 'review this');
  });

  it('uses an unambiguous runtime detail path for a skill named manage', async () => {
    const managed = { ...detail, id: 'manage', name: 'manage' };
    useSkillStore.setState({ skills: [managed] });
    const fetchMock = vi.fn(async () => ({ ok: true, json: async () => managed }));
    vi.stubGlobal('fetch', fetchMock);
    const execute = vi.fn();
    render(<SkillDetailModal skillName="manage" onClose={vi.fn()} onExecute={execute} />);
    await screen.findByText('Skill body');
    expect(fetchMock).toHaveBeenCalledWith('/api/skills/detail/manage', expect.any(Object));
    await act(async () => fireEvent.click(screen.getByRole('button', { name: '执行技能' })));
    expect(execute).toHaveBeenCalledWith('manage', '');
  });

  it('rejects malformed detail responses without enabling execution', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => ({ ok: true, json: async () => ({ skills: [detail] }) })));
    render(<SkillDetailModal skillName="demo" onClose={vi.fn()} onExecute={vi.fn()} />);
    expect(await screen.findByRole('alert')).toHaveTextContent('技能详情格式无效');
    expect(screen.getByRole('button', { name: '执行技能' })).toBeDisabled();
  });

  it('uses the stable id for spaced aliases and stays disabled until async execution completes', async () => {
    let complete!: () => void;
    const execution = new Promise<void>(resolve => { complete = resolve; });
    const execute = vi.fn(() => execution);
    useSkillStore.setState({ skills: [{ ...detail, name: 'Display Alias' }] });
    render(<SkillDetailModal skillName="Display Alias" onClose={vi.fn()} onExecute={execute} />);
    await screen.findByText('Skill body');
    fireEvent.click(screen.getByRole('button', { name: '执行技能' }));
    await waitFor(() => expect(execute).toHaveBeenCalledWith('demo', ''));
    expect(screen.getByRole('button', { name: '检查技能状态…' })).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: '检查技能状态…' }));
    expect(execute).toHaveBeenCalledOnce();
    await act(async () => complete());
    expect(screen.getByRole('button', { name: '执行技能' })).toBeEnabled();
  });
});
