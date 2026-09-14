/**
 * TurnViewStore — 轮次视图偏好（密度 + 手动展开/折叠覆盖）
 * SPEC: 消息流轮次分组聚合方案 P0 状态层
 * 持久化: localStorage (persist middleware, key 'zhikun.turn-view.v1')
 *
 * 与轮次投影（store/selectors/turnProjection）配合：
 * - expandOverrides 以 turnIndex（跨 reconcile 稳定坐标）键控，不用消息 uuid；
 * - 展开态统一经 resolveTurnExpanded 求值：override 优先，密度兜底，
 *   compact 下「最后一轮且运行中」强制展开例外最优先（保证运行进度永远可见）。
 */

import { create } from 'zustand';
import { persist, createJSONStorage } from 'zustand/middleware';
import { immer } from 'zustand/middleware/immer';

export type TurnDensity = 'compact' | 'balanced' | 'detailed';

/** expandOverrides[sessionId][turnIndex] = 手动展开偏好 */
export type TurnExpandOverrides = Record<string, Record<number, boolean>>;

/** expandOverrides 最多保留的 session 数（按最近写入 LRU 修剪） */
export const MAX_OVERRIDE_SESSIONS = 20;

export interface TurnViewStoreState {
    // 状态
    density: TurnDensity;
    expandOverrides: TurnExpandOverrides;

    // Actions
    /** 设置密度，并清空 currentSessionId 的 overrides（切换密度即放弃手动偏好） */
    setDensity: (density: TurnDensity, currentSessionId?: string) => void;
    setTurnExpanded: (sessionId: string, turnIndex: number, expanded: boolean) => void;
    expandAll: (sessionId: string, indexes: number[]) => void;
    collapseAll: (sessionId: string, indexes: number[]) => void;
}

/**
 * LRU touch（immer draft 内操作）：将 sessionId 移到「最近写入」末尾并返回其记录；
 * 超出 MAX_OVERRIDE_SESSIONS 时删除最久未写入的 session。
 * 依赖 JS 对象字符串键的插入序（JSON 持久化往返后仍保持）。
 */
function touchSessionRecord(
    overrides: TurnExpandOverrides,
    sessionId: string,
): Record<number, boolean> {
    const existing = overrides[sessionId];
    if (existing) delete overrides[sessionId];
    const record = existing ?? {};
    overrides[sessionId] = record;
    const keys = Object.keys(overrides);
    while (keys.length > MAX_OVERRIDE_SESSIONS) {
        const oldest = keys.shift();
        if (oldest === undefined) break;
        delete overrides[oldest];
    }
    return record;
}

/**
 * 求某一轮的展开态。
 *
 * 优先级（高 → 低）：
 * 1. compact 例外：density === 'compact' 且为最后一轮且 isRunActive → 强制展开
 *    （优先于 override 的折叠，保证运行进度永远可见）；
 * 2. overridesForSession[turnIndex] 手动偏好；
 * 3. 密度默认：detailed → 全展开；balanced → 仅最后一轮展开；compact → 全折叠。
 */
export function resolveTurnExpanded(
    density: TurnDensity,
    turnIndex: number,
    turnCount: number,
    isRunActive: boolean,
    overridesForSession?: Record<number, boolean>,
): boolean {
    const isLastTurn = turnIndex === turnCount - 1;
    if (density === 'compact' && isLastTurn && isRunActive) return true;
    const override = overridesForSession?.[turnIndex];
    if (override !== undefined) return override;
    if (density === 'detailed') return true;
    if (density === 'balanced') return isLastTurn;
    return false;
}

export const useTurnViewStore = create<TurnViewStoreState>()(
    persist(
        immer((set) => ({
            density: 'balanced',
            expandOverrides: {},

            setDensity: (density, currentSessionId) => set(d => {
                d.density = density;
                // 切换密度即放弃当前会话的手动展开偏好（其他会话保留）
                if (currentSessionId) delete d.expandOverrides[currentSessionId];
            }),
            setTurnExpanded: (sessionId, turnIndex, expanded) => set(d => {
                if (!sessionId) return;
                const record = touchSessionRecord(d.expandOverrides, sessionId);
                record[turnIndex] = expanded;
            }),
            expandAll: (sessionId, indexes) => set(d => {
                if (!sessionId || indexes.length === 0) return;
                const record = touchSessionRecord(d.expandOverrides, sessionId);
                for (const index of indexes) record[index] = true;
            }),
            collapseAll: (sessionId, indexes) => set(d => {
                if (!sessionId || indexes.length === 0) return;
                const record = touchSessionRecord(d.expandOverrides, sessionId);
                for (const index of indexes) record[index] = false;
            }),
        })),
        {
            name: 'zhikun.turn-view.v1',
            storage: createJSONStorage(() => localStorage),
            partialize: (s) => ({
                density: s.density,
                expandOverrides: s.expandOverrides,
            }),
            version: 1,
        },
    ),
);
