import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { findEnabledSkill, useSkillStore, type SkillItem } from '../skillStore';

const skill: SkillItem = { id: 'internal-skill', name: 'Display Alias', description: 'Useful workflow', source: 'PROJECT', enabled: true };
const response = (body: unknown, status = 200) => ({ ok: status < 400, status, json: async () => body }) as Response;
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>(done => { resolve = done; });
  return { promise, resolve };
}

describe('skill state synchronization', () => {
  beforeEach(() => {
    useSkillStore.setState({ skills: [skill], loaded: true, loading: false, pending: {}, error: null, stateError: null });
  });
  afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); vi.useRealTimers(); });

  it('does not fall through a disabled exact id to another skill alias', () => {
    const skills = [{ ...skill, id: 'a', name: 'b' }, { ...skill, id: 'b', name: 'B', enabled: false }];
    expect(findEnabledSkill(skills, 'b')).toBeUndefined();
    expect(findEnabledSkill(skills, 'a')?.id).toBe('a');
  });

  it('loads disabled management entries but resolves only enabled names and aliases', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => response({ skills: [skill, { ...skill, id: 'disabled', name: 'Hidden Alias', enabled: false }] })));
    await useSkillStore.getState().loadSkills();
    expect(useSkillStore.getState().skills).toHaveLength(2);
    expect(findEnabledSkill(useSkillStore.getState().skills, '/INTERNAL-SKILL')).toEqual(skill);
    expect(findEnabledSkill(useSkillStore.getState().skills, 'display alias')).toEqual(skill);
    expect(findEnabledSkill(useSkillStore.getState().skills, 'Hidden Alias')).toBeUndefined();
  });

  it('waits for a successful save and prevents duplicate clicks without unrelated command requests', async () => {
    const request = deferred<Response>();
    const fetchMock = vi.fn(() => request.promise);
    vi.stubGlobal('fetch', fetchMock);
    const save = useSkillStore.getState().toggleSkill(skill.id, false);
    await useSkillStore.getState().toggleSkill(skill.id, false);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(useSkillStore.getState().skills[0].enabled).toBe(true);
    expect(useSkillStore.getState().pending[skill.id]).toBe(true);
    request.resolve(response({ ...skill, enabled: false }));
    await save;
    expect(findEnabledSkill(useSkillStore.getState().skills, skill.name)).toBeUndefined();
    expect(useSkillStore.getState().pending[skill.id]).toBe(false);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('retains the previous state and presents a failed save', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => response({}, 500)));
    await useSkillStore.getState().toggleSkill(skill.id, false);
    expect(useSkillStore.getState().skills).toEqual([skill]);
    expect(useSkillStore.getState().error).toContain('保存技能设置失败');
    expect(useSkillStore.getState().pending[skill.id]).toBe(false);
  });

  it('preserves exact ids with leading whitespace when looking up an open detail', () => {
    const item = { ...skill, id: ' leading ' };
    expect(findEnabledSkill([item], item.id)).toBe(item);
    expect(findEnabledSkill([{ ...item, enabled: false }], item.id)).toBeUndefined();
  });

  it('shows the backend actionable save error without changing availability', async () => {
    const message = '保存技能设置失败，原有状态保持不变。请检查配置目录权限和磁盘空间后重试。';
    vi.stubGlobal('fetch', vi.fn(async () => response({ error: { message } }, 500)));
    await useSkillStore.getState().toggleSkill(skill.id, false);
    expect(useSkillStore.getState().error).toBe(message);
    expect(useSkillStore.getState().skills).toEqual([skill]);
  });

  it.each(['load', 'save'])('releases a hung %s request after its deadline and allows retry', async operation => {
    vi.useFakeTimers();
    const timeout = vi.spyOn(AbortSignal, 'timeout').mockImplementation(ms => {
      const controller = new AbortController();
      setTimeout(() => controller.abort(new DOMException('timed out', 'TimeoutError')), ms);
      return controller.signal;
    });
    const fetchMock = vi.fn((_url: string, init: RequestInit) => new Promise<Response>((_resolve, reject) => {
      init.signal!.addEventListener('abort', () => reject(init.signal!.reason), { once: true });
    }));
    vi.stubGlobal('fetch', fetchMock);
    const request = operation === 'load' ? useSkillStore.getState().loadSkills()
      : useSkillStore.getState().toggleSkill(skill.id, false);
    await Promise.resolve();
    expect(timeout).toHaveBeenCalledWith(10000);
    await vi.advanceTimersByTimeAsync(10000);
    await request;
    expect(useSkillStore.getState().loading).toBe(false);
    expect(useSkillStore.getState().pending[skill.id]).not.toBe(true);
    expect(useSkillStore.getState().error).toContain('超时');
    if (operation === 'save') expect(useSkillStore.getState().error).toContain('结果尚未确认');
    expect(useSkillStore.getState().skills).toEqual([skill]);
    fetchMock.mockResolvedValueOnce(response({ skills: [{ ...skill, enabled: false }] }));
    await useSkillStore.getState().loadSkills();
    expect(useSkillStore.getState().skills[0].enabled).toBe(false);
    expect(useSkillStore.getState().error).toBeNull();
  });

  it('ignores a stale list that completes after a successful toggle', async () => {
    const stale = deferred<Response>();
    vi.stubGlobal('fetch', vi.fn((url: string) => url.includes('/toggle')
      ? Promise.resolve(response({ ...skill, enabled: false })) : stale.promise));
    const load = useSkillStore.getState().loadSkills();
    await Promise.resolve();
    await useSkillStore.getState().toggleSkill(skill.id, false);
    stale.resolve(response({ skills: [skill] }));
    await load;
    expect(useSkillStore.getState().skills[0].enabled).toBe(false);
    expect(useSkillStore.getState().loading).toBe(false);
  });

  it('allows a new post-save refresh even when an older list is still in flight', async () => {
    const stale = deferred<Response>();
    const fetchMock = vi.fn()
      .mockImplementationOnce(() => stale.promise)
      .mockResolvedValueOnce(response({ ...skill, enabled: false }))
      .mockResolvedValueOnce(response({ skills: [{ ...skill, enabled: false }] }));
    vi.stubGlobal('fetch', fetchMock);
    const oldLoad = useSkillStore.getState().loadSkills();
    await Promise.resolve();
    await useSkillStore.getState().toggleSkill(skill.id, false);
    await useSkillStore.getState().loadSkills({ background: true });
    stale.resolve(response({ skills: [skill] }));
    await oldLoad;
    expect(fetchMock).toHaveBeenCalledTimes(3);
    expect(useSkillStore.getState().skills[0].enabled).toBe(false);
  });

  it('coalesces polling requests and applies changes made on another device', async () => {
    const list = deferred<Response>();
    const fetchMock = vi.fn(() => list.promise);
    vi.stubGlobal('fetch', fetchMock);
    const first = useSkillStore.getState().loadSkills({ background: true });
    const second = useSkillStore.getState().loadSkills();
    await Promise.resolve();
    expect(fetchMock).toHaveBeenCalledOnce();
    list.resolve(response({ skills: [{ ...skill, enabled: false }] }));
    await Promise.all([first, second]);
    expect(useSkillStore.getState().skills[0].enabled).toBe(false);
  });

  it('can retry a failed load without clearing known availability', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValueOnce(new Error('offline'))
      .mockResolvedValueOnce(response({ skills: [skill] })));
    await useSkillStore.getState().loadSkills();
    expect(useSkillStore.getState().skills).toEqual([skill]);
    expect(useSkillStore.getState().error).toBe('offline');
    await useSkillStore.getState().loadSkills();
    expect(useSkillStore.getState().error).toBeNull();
  });

  it.each(['constructor', '__proto__'])('can toggle the prototype-named skill %s', async id => {
    const item = { ...skill, id, name: id };
    useSkillStore.setState({ skills: [item] });
    const fetchMock = vi.fn(async () => response({ ...item, enabled: false }));
    vi.stubGlobal('fetch', fetchMock);
    await useSkillStore.getState().toggleSkill(id, false);
    expect(fetchMock).toHaveBeenCalledOnce();
    expect(useSkillStore.getState().skills[0].enabled).toBe(false);
    expect(useSkillStore.getState().pending[id]).toBe(false);
  });

  it('preserves independent parallel updates when responses complete in reverse order', async () => {
    const second = { ...skill, id: 'second' };
    useSkillStore.setState({ skills: [skill, second] });
    const firstSave = deferred<Response>();
    const secondSave = deferred<Response>();
    vi.stubGlobal('fetch', vi.fn((url: string) => url.includes('/second/') ? secondSave.promise : firstSave.promise));
    const first = useSkillStore.getState().toggleSkill(skill.id, false);
    const next = useSkillStore.getState().toggleSkill(second.id, false);
    secondSave.resolve(response({ ...second, enabled: false }));
    await next;
    expect(useSkillStore.getState().pending[skill.id]).toBe(true);
    firstSave.resolve(response({ ...skill, enabled: false }));
    await first;
    expect(useSkillStore.getState().skills.every(item => !item.enabled)).toBe(true);
  });

  it.each([
    { skills: [null] },
    { skills: [{ id: skill.id, enabled: true }] },
    { skills: [{ ...skill, name: 123 }] },
    { skills: [{ ...skill, source: null }] },
    { skills: [{ ...skill, description: undefined }] },
    { skills: [{ ...skill, enabled: 'false' }] },
    { skills: [skill, skill] },
    { skills: [skill], stateError: {} },
  ])('rejects a malformed list without publishing partial state: %j', async payload => {
    vi.stubGlobal('fetch', vi.fn(async () => response(payload)));
    await useSkillStore.getState().loadSkills();
    expect(useSkillStore.getState().skills).toEqual([skill]);
    expect(useSkillStore.getState().stateError).toBeNull();
    expect(useSkillStore.getState().error).toBeTruthy();
  });

  it.each([
    null,
    { id: skill.id, enabled: false },
    { ...skill, name: null, enabled: false },
    { ...skill, id: 'different', enabled: false },
  ])('rejects a malformed save without overwriting the last good record: %j', async payload => {
    vi.stubGlobal('fetch', vi.fn(async () => response(payload)));
    await useSkillStore.getState().toggleSkill(skill.id, false);
    expect(useSkillStore.getState().skills).toEqual([skill]);
    expect(useSkillStore.getState().pending[skill.id]).toBe(false);
    expect(useSkillStore.getState().error).toContain('保存结果无效');
  });

  it('closes skills and blocks writes for a server state error, then recovers on a valid refresh', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(response({ skills: [skill], stateError: '配置损坏，请修复后重启' }))
      .mockResolvedValueOnce(response({ skills: [skill], stateError: null }));
    vi.stubGlobal('fetch', fetchMock);
    await useSkillStore.getState().loadSkills();
    expect(useSkillStore.getState().skills[0].enabled).toBe(false);
    expect(useSkillStore.getState().stateError).toContain('配置损坏');
    await useSkillStore.getState().toggleSkill(skill.id, true);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    await useSkillStore.getState().loadSkills();
    expect(useSkillStore.getState().stateError).toBeNull();
    expect(useSkillStore.getState().skills[0].enabled).toBe(true);
  });

  it('recognizes an unavailable state during save and refreshes the authoritative list', async () => {
    const stateError = '配置损坏，请修复后重启';
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(response({ error: { code: 'SKILL_STATE_UNAVAILABLE', message: stateError } }, 503))
      .mockResolvedValueOnce(response({ skills: [{ ...skill, enabled: false }], stateError }));
    vi.stubGlobal('fetch', fetchMock);
    await useSkillStore.getState().toggleSkill(skill.id, false);
    expect(useSkillStore.getState().stateError).toBe(stateError);
    expect(useSkillStore.getState().skills[0].enabled).toBe(false);
    expect(fetchMock).toHaveBeenLastCalledWith('/api/skills/manage', expect.objectContaining({ cache: 'no-store', signal: expect.any(AbortSignal) }));
    await useSkillStore.getState().toggleSkill(skill.id, true);
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('keeps the last good state for an ordinary 503 instead of declaring settings damaged', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => response({ error: { message: '服务繁忙' } }, 503)));
    await useSkillStore.getState().toggleSkill(skill.id, false);
    expect(useSkillStore.getState().stateError).toBeNull();
    expect(useSkillStore.getState().skills).toEqual([skill]);
    expect(useSkillStore.getState().error).toBe('服务繁忙');
  });

  it('does not let a parallel late success re-enable skills after a state error', async () => {
    const second = { ...skill, id: 'second', enabled: false };
    useSkillStore.setState({ skills: [skill, second] });
    const unavailable = deferred<Response>();
    const lateSuccess = deferred<Response>();
    const stateError = '配置损坏，请修复后重启';
    vi.stubGlobal('fetch', vi.fn((url: string) => {
      if (url.includes('/second/')) return lateSuccess.promise;
      if (url.includes('/toggle')) return unavailable.promise;
      return Promise.resolve(response({ skills: [skill, second].map(item => ({ ...item, enabled: false })), stateError }));
    }));
    const first = useSkillStore.getState().toggleSkill(skill.id, false);
    const next = useSkillStore.getState().toggleSkill(second.id, true);
    unavailable.resolve(response({ error: { code: 'SKILL_STATE_UNAVAILABLE', message: stateError } }, 503));
    await first;
    lateSuccess.resolve(response({ ...second, enabled: true }));
    await next;
    expect(useSkillStore.getState().stateError).toBe(stateError);
    expect(useSkillStore.getState().skills.every(item => !item.enabled)).toBe(true);
  });
});
