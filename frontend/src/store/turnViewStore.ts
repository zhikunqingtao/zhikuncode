/**
 * TurnViewStore — 轮次视图偏好（密度 + 手动展开/折叠覆盖）
 * SPEC: 消息流轮次分组聚合方案 P0 状态层
 * 持久化: localStorage (persist middleware, key 'zhikun.turn-view.v1')
 *
 * 三层轮模型（用户 query ｜ 过程区 ｜ 回复）下，compact 默认折叠三层；
 * balanced / detailed 保持问题与回复完整。过程区：compact = 轮级聚合条；balanced = 任务分节条；
 * detailed = 任务分节全展开。因此 expandOverrides 从「轮」粒度演进为
 * 「分节」粒度（v2），key 规则：
 * - `${turnIndex}`：轮级过程区（compact 聚合条，及无任务分节轮的回退聚合条）；
 * - `${turnIndex}:${sectionIndex}`：任务分节（balanced / detailed）；
 * - `${turnIndex}:prep`：「准备」段（首个任务起点之前的过程块，仅 detailed 展示）。
 * - `${turnIndex}:query` / `:answer` / `:steering-N`：简洁档的问题、回复、补充指令。
 * turnIndex 是跨 reconcile 稳定坐标（reconcileCommittedRun 会整体替换消息 uuid），
 * sectionIndex 由分节推导（store/selectors/turnSections）按消息序稳定产出。
 *
 * 展开态统一经 resolveSectionExpanded 求值：override 优先，密度兜底 ——
 * detailed 默认全展开；balanced 默认仅「运行中分节」展开（任务完成后自动折回，
 * 用户手动展开过的不折）；compact 默认全折叠。
 */

import { create } from 'zustand';
import { persist, createJSONStorage } from 'zustand/middleware';
import { immer } from 'zustand/middleware/immer';

export type TurnDensity = 'compact' | 'balanced' | 'detailed';

/** expandOverrides[sessionId][sectionKey] = 手动展开偏好（分节粒度 string key） */
export type TurnExpandOverrides = Record<string, Record<string, boolean>>;

/** expandOverrides 最多保留的 session 数（按最近写入 LRU 修剪） */
export const MAX_OVERRIDE_SESSIONS = 20;

// ==================== 展开 key 规则 ====================

/** 轮级过程区 key（compact 聚合条 / 无分节轮回退聚合条） */
export function turnExpandKey(turnIndex: number): string {
    return String(turnIndex);
}

/** 任务分节 key（balanced / detailed） */
export function sectionExpandKey(turnIndex: number, sectionIndex: number): string {
    return `${turnIndex}:${sectionIndex}`;
}

/** 「准备」段 key（仅 detailed 展示） */
export function prepExpandKey(turnIndex: number): string {
    return `${turnIndex}:prep`;
}

/** 简洁档消息正文 key；使用轮内位置，避免历史回填更换 uuid 后丢失展开状态。 */
export function turnMessageExpandKey(turnIndex: number, part: 'query' | 'answer' | `steering-${number}`): string {
    return `${turnIndex}:${part}`;
}

export interface TurnViewStoreState {
    // 状态
    density: TurnDensity;
    expandOverrides: TurnExpandOverrides;

    // Actions
    /** 设置密度，并清空 currentSessionId 的 overrides（切换密度即放弃手动偏好） */
    setDensity: (density: TurnDensity, currentSessionId?: string) => void;
    /** 写入某展开 key 的手动偏好（轮级 `${turnIndex}` / 分节 `${turnIndex}:${sectionIndex}`） */
    setSectionExpanded: (sessionId: string, expandKey: string, expanded: boolean) => void;
    // 新指令到达时自动折叠前轮的过程分节
    collapseAll: (sessionId: string, expandKeys: string[]) => void;
}

/**
 * LRU touch（immer draft 内操作）：将 sessionId 移到「最近写入」末尾并返回其记录；
 * 超出 MAX_OVERRIDE_SESSIONS 时删除最久未写入的 session。
 * 依赖 JS 对象字符串键的插入序（JSON 持久化往返后仍保持）。
 */
function touchSessionRecord(
    overrides: TurnExpandOverrides,
    sessionId: string,
): Record<string, boolean> {
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

export interface ResolveSectionExpandedOptions {
    /**
     * 该分节当前是否运行中（balanced 默认仅运行中分节展开；
     * 任务完成后默认折回，override 优先于该默认）。
     */
    runningSection?: boolean;
}

/**
 * 求某一展开 key 的展开态。
 *
 * 优先级（高 → 低）：
 * 1. overridesForSession[expandKey] 手动偏好；
 * 2. 密度默认：detailed → 展开；balanced → 仅运行中分节展开；compact → 折叠。
 */
export function resolveSectionExpanded(
    density: TurnDensity,
    expandKey: string,
    overridesForSession?: Record<string, boolean>,
    opts?: ResolveSectionExpandedOptions,
): boolean {
    const override = overridesForSession?.[expandKey];
    if (override !== undefined) return override;
    if (density === 'detailed') return true;
    if (density === 'balanced') return opts?.runningSection ?? false;
    return false;
}

export const useTurnViewStore = create<TurnViewStoreState>()(
    persist(
        immer((set) => ({
            density: 'compact',
            expandOverrides: {},

            setDensity: (density, currentSessionId) => set(d => {
                d.density = density;
                // 切换密度即放弃当前会话的手动展开偏好（其他会话保留）
                if (currentSessionId) delete d.expandOverrides[currentSessionId];
            }),
            setSectionExpanded: (sessionId, expandKey, expanded) => set(d => {
                if (!sessionId) return;
                const record = touchSessionRecord(d.expandOverrides, sessionId);
                record[expandKey] = expanded;
            }),
            collapseAll: (sessionId, expandKeys) => set(d => {
                if (!sessionId || expandKeys.length === 0) return;
                const record = touchSessionRecord(d.expandOverrides, sessionId);
                for (const key of expandKeys) record[key] = false;
            }),
        })),
        {
            name: 'zhikun.turn-view.v1',
            storage: createJSONStorage(() => localStorage),
            partialize: (s) => ({
                density: s.density,
                expandOverrides: s.expandOverrides,
            }),
            // v2：expandOverrides 由「轮」粒度 number key 演进为「分节」粒度 string key，
            // 旧 key 语义不兼容（number 会被 JSON 读成 string 与轮级 key 撞车），直接丢弃
            // （展开偏好为瞬态 UI 状态，可安全重建）；density 保留。
            version: 2,
            migrate: (persisted, version) => {
                if (version < 2) {
                    const state = persisted as { density?: TurnDensity } | undefined;
                    return {
                        density: state?.density ?? 'compact',
                        expandOverrides: {},
                    } as TurnViewStoreState;
                }
                return persisted as TurnViewStoreState;
            },
        },
    ),
);
