import { describe, it, expect, beforeEach } from 'vitest';
import {
    useTurnViewStore,
    resolveTurnExpanded,
    MAX_OVERRIDE_SESSIONS,
} from '../turnViewStore';

beforeEach(() => {
    localStorage.clear();
    useTurnViewStore.setState({ density: 'balanced', expandOverrides: {} });
});

describe('resolveTurnExpanded', () => {
    it('detailed：默认全展开；override=false 可折叠', () => {
        expect(resolveTurnExpanded('detailed', 0, 3, false)).toBe(true);
        expect(resolveTurnExpanded('detailed', 2, 3, false)).toBe(true);
        expect(resolveTurnExpanded('detailed', 0, 3, true)).toBe(true);
        expect(resolveTurnExpanded('detailed', 1, 3, false, { 1: false })).toBe(false);
    });

    it('balanced：默认仅最后一轮展开；override 可双向覆盖', () => {
        expect(resolveTurnExpanded('balanced', 0, 3, false)).toBe(false);
        expect(resolveTurnExpanded('balanced', 1, 3, false)).toBe(false);
        expect(resolveTurnExpanded('balanced', 2, 3, false)).toBe(true);
        expect(resolveTurnExpanded('balanced', 0, 3, false, { 0: true })).toBe(true);
        expect(resolveTurnExpanded('balanced', 2, 3, false, { 2: false })).toBe(false);
    });

    it('compact：默认全折叠；override=true 可展开', () => {
        expect(resolveTurnExpanded('compact', 0, 3, false)).toBe(false);
        expect(resolveTurnExpanded('compact', 2, 3, false)).toBe(false);
        expect(resolveTurnExpanded('compact', 1, 3, false, { 1: true })).toBe(true);
    });

    it('compact 例外：最后一轮且运行中 → 强制展开，优先于 override 的折叠', () => {
        expect(resolveTurnExpanded('compact', 2, 3, true)).toBe(true);
        expect(resolveTurnExpanded('compact', 2, 3, true, { 2: false })).toBe(true);
        // 非最后一轮或运行已结束时不适用例外
        expect(resolveTurnExpanded('compact', 1, 3, true)).toBe(false);
        expect(resolveTurnExpanded('compact', 2, 3, false)).toBe(false);
        expect(resolveTurnExpanded('compact', 2, 3, false, { 2: false })).toBe(false);
        // 例外仅属 compact：balanced 下用户显式折叠最后一轮仍被尊重
        expect(resolveTurnExpanded('balanced', 2, 3, true, { 2: false })).toBe(false);
    });

    it('单轮（turnCount=1）即最后一轮', () => {
        expect(resolveTurnExpanded('balanced', 0, 1, false)).toBe(true);
        expect(resolveTurnExpanded('compact', 0, 1, true)).toBe(true);
        expect(resolveTurnExpanded('compact', 0, 1, false)).toBe(false);
    });
});

describe('TurnViewStore', () => {
    it('默认 density=balanced，expandOverrides 为空', () => {
        const state = useTurnViewStore.getState();
        expect(state.density).toBe('balanced');
        expect(state.expandOverrides).toEqual({});
    });

    it('setTurnExpanded 写入指定 session 的轮次覆盖', () => {
        useTurnViewStore.getState().setTurnExpanded('s-1', 2, false);
        useTurnViewStore.getState().setTurnExpanded('s-1', 0, true);
        expect(useTurnViewStore.getState().expandOverrides['s-1']).toEqual({ 2: false, 0: true });
    });

    it('setDensity 设置密度并清空当前会话 overrides，其他会话保留', () => {
        const store = useTurnViewStore.getState();
        store.setTurnExpanded('s-1', 0, true);
        store.setTurnExpanded('s-2', 1, false);

        useTurnViewStore.getState().setDensity('compact', 's-1');
        const state = useTurnViewStore.getState();
        expect(state.density).toBe('compact');
        expect(state.expandOverrides['s-1']).toBeUndefined();
        expect(state.expandOverrides['s-2']).toEqual({ 1: false });
    });

    it('setDensity 不传 sessionId 时保留全部 overrides', () => {
        useTurnViewStore.getState().setTurnExpanded('s-1', 0, true);
        useTurnViewStore.getState().setDensity('detailed');
        expect(useTurnViewStore.getState().expandOverrides['s-1']).toEqual({ 0: true });
    });

    it('expandAll / collapseAll 批量写 overrides', () => {
        useTurnViewStore.getState().expandAll('s-1', [0, 1, 2]);
        expect(useTurnViewStore.getState().expandOverrides['s-1'])
            .toEqual({ 0: true, 1: true, 2: true });

        useTurnViewStore.getState().collapseAll('s-1', [1, 2]);
        expect(useTurnViewStore.getState().expandOverrides['s-1'])
            .toEqual({ 0: true, 1: false, 2: false });
    });

    it('expandAll/collapseAll 空 indexes 不产生 session 记录', () => {
        useTurnViewStore.getState().expandAll('s-1', []);
        expect(useTurnViewStore.getState().expandOverrides['s-1']).toBeUndefined();
    });

    it(`LRU 修剪：最多保留 ${MAX_OVERRIDE_SESSIONS} 个 session，超出删最旧`, () => {
        for (let i = 0; i < MAX_OVERRIDE_SESSIONS; i++) {
            useTurnViewStore.getState().setTurnExpanded(`s-${i}`, 0, true);
        }
        useTurnViewStore.getState().setTurnExpanded('s-new', 0, true);
        const keys = Object.keys(useTurnViewStore.getState().expandOverrides);
        expect(keys).toHaveLength(MAX_OVERRIDE_SESSIONS);
        expect(keys).not.toContain('s-0');
        expect(keys).toContain('s-new');
    });

    it('LRU：重复写入同一 session 视为最近使用，不被修剪', () => {
        for (let i = 0; i < MAX_OVERRIDE_SESSIONS; i++) {
            useTurnViewStore.getState().setTurnExpanded(`s-${i}`, 0, true);
        }
        // touch s-0（再次写入 → 变为最近使用）
        useTurnViewStore.getState().setTurnExpanded('s-0', 1, false);
        useTurnViewStore.getState().setTurnExpanded('s-new', 0, true);
        const overrides = useTurnViewStore.getState().expandOverrides;
        const keys = Object.keys(overrides);
        expect(keys).toHaveLength(MAX_OVERRIDE_SESSIONS);
        expect(keys).toContain('s-0');
        expect(keys).not.toContain('s-1');
        expect(overrides['s-0']).toEqual({ 0: true, 1: false });
    });

    it('persist：仅持久化 density 与 expandOverrides（key=zhikun.turn-view.v1）', () => {
        useTurnViewStore.getState().setDensity('detailed');
        useTurnViewStore.getState().setTurnExpanded('s-1', 2, false);

        const raw = localStorage.getItem('zhikun.turn-view.v1');
        expect(raw).toBeTruthy();
        const persisted = JSON.parse(raw as string);
        expect(Object.keys(persisted.state).sort()).toEqual(['density', 'expandOverrides']);
        expect(persisted.state.density).toBe('detailed');
        expect(persisted.state.expandOverrides).toEqual({ 's-1': { 2: false } });
        expect(persisted.version).toBe(1);
    });
});
