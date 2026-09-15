import { describe, it, expect, beforeEach } from 'vitest';
import {
    useTurnViewStore,
    resolveSectionExpanded,
    turnExpandKey,
    sectionExpandKey,
    prepExpandKey,
    MAX_OVERRIDE_SESSIONS,
} from '../turnViewStore';

beforeEach(() => {
    localStorage.clear();
    useTurnViewStore.setState({ density: 'compact', expandOverrides: {} });
});

describe('展开 key 规则（分节粒度 string key）', () => {
    it('轮级 / 分节 / 准备段 key 格式', () => {
        expect(turnExpandKey(2)).toBe('2');
        expect(sectionExpandKey(1, 3)).toBe('1:3');
        expect(prepExpandKey(1)).toBe('1:prep');
    });
});

describe('resolveSectionExpanded', () => {
    it('override 优先于一切密度默认（含 balanced 运行中分节）', () => {
        expect(resolveSectionExpanded('detailed', '0:1', { '0:1': false })).toBe(false);
        expect(resolveSectionExpanded('compact', '0', { 0: true })).toBe(true);
        // balanced 运行中分节被用户显式折叠 → 尊重 override
        expect(resolveSectionExpanded('balanced', '0:0', { '0:0': false }, { runningSection: true }))
            .toBe(false);
    });

    it('detailed：无 override 默认全展开（轮级/分节/准备段同规则）', () => {
        expect(resolveSectionExpanded('detailed', '0')).toBe(true);
        expect(resolveSectionExpanded('detailed', '0:0')).toBe(true);
        expect(resolveSectionExpanded('detailed', '0:prep')).toBe(true);
    });

    it('balanced：默认仅运行中分节展开（任务完成后自动折回）', () => {
        expect(resolveSectionExpanded('balanced', '0:0')).toBe(false);
        expect(resolveSectionExpanded('balanced', '0:0', undefined, { runningSection: true }))
            .toBe(true);
        expect(resolveSectionExpanded('balanced', '0:0', undefined, { runningSection: false }))
            .toBe(false);
    });

    it('compact：默认全折叠（运行中也不强制展开）', () => {
        expect(resolveSectionExpanded('compact', '0')).toBe(false);
        expect(resolveSectionExpanded('compact', '0', undefined, { runningSection: true }))
            .toBe(false);
    });

    it('overridesForSession 缺省或未命中 key → 走密度默认', () => {
        expect(resolveSectionExpanded('detailed', '1:0', {})).toBe(true);
        expect(resolveSectionExpanded('compact', '1:0', { '2:0': true })).toBe(false);
    });
});

describe('TurnViewStore', () => {
    it('默认 density=compact，expandOverrides 为空', () => {
        const state = useTurnViewStore.getState();
        expect(state.density).toBe('compact');
        expect(state.expandOverrides).toEqual({});
    });

    it('setSectionExpanded 写入指定 session 的分节粒度 string key 覆盖', () => {
        useTurnViewStore.getState().setSectionExpanded('s-1', turnExpandKey(0), true);
        useTurnViewStore.getState().setSectionExpanded('s-1', sectionExpandKey(0, 1), false);
        useTurnViewStore.getState().setSectionExpanded('s-1', prepExpandKey(0), true);
        expect(useTurnViewStore.getState().expandOverrides['s-1'])
            .toEqual({ 0: true, '0:1': false, '0:prep': true });
    });

    it('setSectionExpanded 空 sessionId 不产生记录', () => {
        useTurnViewStore.getState().setSectionExpanded('', '0', true);
        expect(useTurnViewStore.getState().expandOverrides).toEqual({});
    });

    it('setDensity 设置密度并清空当前会话 overrides，其他会话保留', () => {
        const store = useTurnViewStore.getState();
        store.setSectionExpanded('s-1', '0', true);
        store.setSectionExpanded('s-2', '1:0', false);

        useTurnViewStore.getState().setDensity('balanced', 's-1');
        const state = useTurnViewStore.getState();
        expect(state.density).toBe('balanced');
        expect(state.expandOverrides['s-1']).toBeUndefined();
        expect(state.expandOverrides['s-2']).toEqual({ '1:0': false });
    });

    it('setDensity 不传 sessionId 时保留全部 overrides', () => {
        useTurnViewStore.getState().setSectionExpanded('s-1', '0', true);
        useTurnViewStore.getState().setDensity('detailed');
        expect(useTurnViewStore.getState().expandOverrides['s-1']).toEqual({ 0: true });
    });

    it('collapseAll 批量折叠并保留其他 key', () => {
        for (const key of ['0', '0:0', '0:prep']) {
            useTurnViewStore.getState().setSectionExpanded('s-1', key, true);
        }
        expect(useTurnViewStore.getState().expandOverrides['s-1'])
            .toEqual({ 0: true, '0:0': true, '0:prep': true });

        useTurnViewStore.getState().collapseAll('s-1', ['0:0', '0:prep']);
        expect(useTurnViewStore.getState().expandOverrides['s-1'])
            .toEqual({ 0: true, '0:0': false, '0:prep': false });
    });

    it('collapseAll 空 keys 或空 sessionId 不产生 session 记录', () => {
        useTurnViewStore.getState().collapseAll('s-1', []);
        useTurnViewStore.getState().collapseAll('', ['0']);
        expect(useTurnViewStore.getState().expandOverrides['s-1']).toBeUndefined();
        expect(useTurnViewStore.getState().expandOverrides).toEqual({});
    });

    it(`LRU 修剪：最多保留 ${MAX_OVERRIDE_SESSIONS} 个 session，超出删最旧`, () => {
        for (let i = 0; i < MAX_OVERRIDE_SESSIONS; i++) {
            useTurnViewStore.getState().setSectionExpanded(`s-${i}`, '0', true);
        }
        useTurnViewStore.getState().setSectionExpanded('s-new', '0', true);
        const keys = Object.keys(useTurnViewStore.getState().expandOverrides);
        expect(keys).toHaveLength(MAX_OVERRIDE_SESSIONS);
        expect(keys).not.toContain('s-0');
        expect(keys).toContain('s-new');
    });

    it('LRU：重复写入同一 session 视为最近使用，不被修剪', () => {
        for (let i = 0; i < MAX_OVERRIDE_SESSIONS; i++) {
            useTurnViewStore.getState().setSectionExpanded(`s-${i}`, '0', true);
        }
        // touch s-0（再次写入 → 变为最近使用）
        useTurnViewStore.getState().setSectionExpanded('s-0', '0:1', false);
        useTurnViewStore.getState().setSectionExpanded('s-new', '0', true);
        const overrides = useTurnViewStore.getState().expandOverrides;
        const keys = Object.keys(overrides);
        expect(keys).toHaveLength(MAX_OVERRIDE_SESSIONS);
        expect(keys).toContain('s-0');
        expect(keys).not.toContain('s-1');
        expect(overrides['s-0']).toEqual({ 0: true, '0:1': false });
    });

    it('persist：仅持久化 density 与 expandOverrides（key=zhikun.turn-view.v1）', () => {
        useTurnViewStore.getState().setDensity('detailed');
        useTurnViewStore.getState().setSectionExpanded('s-1', sectionExpandKey(1, 2), false);

        const raw = localStorage.getItem('zhikun.turn-view.v1');
        expect(raw).toBeTruthy();
        const persisted = JSON.parse(raw as string);
        expect(Object.keys(persisted.state).sort()).toEqual(['density', 'expandOverrides']);
        expect(persisted.state.density).toBe('detailed');
        expect(persisted.state.expandOverrides).toEqual({ 's-1': { '1:2': false } });
        expect(persisted.version).toBe(2);
    });

    it('persist v2 迁移：v1 轮级 number key overrides 丢弃，density 保留', () => {
        const migrate = useTurnViewStore.persist.getOptions().migrate;
        expect(migrate).toBeTypeOf('function');
        // v1 存储：expandOverrides 为「轮」粒度 number key（JSON 读出为 string）
        const migrated = migrate?.(
            { density: 'detailed', expandOverrides: { 's-1': { 0: true } } },
            1,
        );
        expect(migrated).toEqual({ density: 'detailed', expandOverrides: {} });
        // 无 density 的损坏负载 → 回退默认 compact
        const fallback = migrate?.({}, 0);
        expect(fallback).toEqual({ density: 'compact', expandOverrides: {} });
        // v2 负载原样保留
        const v2 = { density: 'balanced', expandOverrides: { 's-1': { '0:0': true } } };
        expect(migrate?.(v2, 2)).toEqual(v2);
    });
});
