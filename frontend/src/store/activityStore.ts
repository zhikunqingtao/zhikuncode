import { create } from 'zustand';
import { immer } from 'zustand/middleware/immer';
import { subscribeWithSelector } from 'zustand/middleware';
import type { ActivityData, ActivityFilter, Signal } from '@/types/apos';
import { DEFAULT_RETENTION_CONFIG } from '@/types/apos';
import { updateActivityDecision } from '@/api/activityApi';
import { useSessionStore } from '@/store/sessionStore';

interface ActivityStoreState {
  activities: Map<string, ActivityData>;
  deniedToolUseIds: Set<string>;
  approvedToolUseIds: Set<string>;
  currentSessionId: string | null;
  expandedId: string | null;
  l3ActivityId: string | null;
  filter: ActivityFilter;
  selectedIds: Set<string>;
  batchMode: boolean;
  hasMoreHistory: boolean;
  totalActivityCount: number;

  setCurrentSessionId: (sessionId: string) => void;
  clearForNewSession: () => void;
  addActivity: (activity: ActivityData) => void;
  updateActivity: (id: string, partial: Partial<ActivityData>) => void;
  attachInsight: (activityId: string, insight: ActivityData['insight']) => void;
  approveActivity: (id: string) => void;
  rejectActivity: (id: string) => void;
  markToolUseDenied: (toolUseId: string) => void;
  isToolUseDenied: (toolUseId: string) => boolean;
  markToolUseApproved: (toolUseId: string) => void;
  isToolUseApproved: (toolUseId: string) => boolean;
  setExpandedId: (id: string | null) => void;
  setL3ActivityId: (id: string | null) => void;
  setFilter: (filter: ActivityFilter) => void;
  toggleSelect: (id: string) => void;
  selectAll: () => void;
  selectAllSafe: () => void; // only auto_approve + review_recommended
  clearSelection: () => void;
  setBatchMode: (enabled: boolean) => void;
  setHasMoreHistory: (hasMore: boolean, total?: number) => void;
  loadMoreActivities: () => Promise<void>;
  performRetention: () => void;
  clearAll: () => void;
}

export const useActivityStore = create<ActivityStoreState>()(
  subscribeWithSelector(immer((set, get) => ({
    activities: new Map(),
    deniedToolUseIds: new Set(),
    approvedToolUseIds: new Set(),
    currentSessionId: null,
    expandedId: null,
    l3ActivityId: null,
    filter: {},
    selectedIds: new Set(),
    batchMode: false,
    hasMoreHistory: false,
    totalActivityCount: 0,

    setCurrentSessionId: (sessionId) => set(d => {
      d.currentSessionId = sessionId;
      d.expandedId = null;
      d.l3ActivityId = null;
      d.batchMode = false;
      d.selectedIds = new Set();
      // 不清空 activities — ActivityStream 通过 a.sessionId === currentSessionId 过滤，
      // 清空会与 handleSessionRestore 的 activity 恢复产生时序竞态
    }),
    clearForNewSession: () => set(d => {
      d.selectedIds.clear();
      d.expandedId = null;
      d.l3ActivityId = null;
      d.batchMode = false;
    }),
    addActivity: (activity) => set(d => {
      const existing = d.activities.get(activity.id);
      if (existing && existing.decision !== undefined && activity.decision === undefined) {
        // 保留已有决策，避免 HMR/remount 时决策被清空
        d.activities.set(activity.id, { ...activity, decision: existing.decision });
      } else {
        // 兖底检查：如果该 toolUseId 已被标记为 denied，强制清空 changedFiles 并标记 rejected
        if (d.deniedToolUseIds.has(activity.id)) {
          d.activities.set(activity.id, {
            ...activity,
            changedFiles: [],
            fileCount: 0,
            decision: 'rejected',
          });
        } else {
          d.activities.set(activity.id, activity);
        }
      }
    }),
    updateActivity: (id, partial) => set(d => {
      const existing = d.activities.get(id);
      if (existing) {
        // insight 字段需要完整替换以让 Immer 正确追踪嵌套变化
        if ('insight' in partial && partial.insight !== undefined) {
          existing.insight = partial.insight;
        }
        // changedFiles 字段也需要完整替换（数组类型）
        if ('changedFiles' in partial && partial.changedFiles !== undefined) {
          existing.changedFiles = partial.changedFiles;
        }
        // 其他基本类型字段用 Object.assign 正常合并
        const { insight, changedFiles, ...other } = partial;
        if (Object.keys(other).length > 0) {
          Object.assign(existing, other);
        }
      }
    }),
    attachInsight: (activityId, insight) => set(d => {
      const activity = d.activities.get(activityId);
      if (activity) activity.insight = insight;
    }),
    approveActivity: (id) => {
      set(d => {
        const activity = d.activities.get(id);
        if (activity) activity.decision = 'approved';
      });
      updateActivityDecision(id, 'approved');
    },
    rejectActivity: (id) => {
      set(d => {
        const activity = d.activities.get(id);
        if (activity) activity.decision = 'rejected';
      });
      updateActivityDecision(id, 'rejected');
    },
    markToolUseDenied: (toolUseId) => set(d => {
      d.deniedToolUseIds.add(toolUseId);
      // 如果 Activity 已存在，立即清除 changedFiles 并标记为拒绝
      const existing = d.activities.get(toolUseId);
      if (existing) {
        existing.changedFiles = [];
        existing.fileCount = 0;
        existing.decision = 'rejected';
      }
    }),
    isToolUseDenied: (toolUseId) => {
      return get().deniedToolUseIds.has(toolUseId);
    },
    markToolUseApproved: (toolUseId) => set(d => {
      d.approvedToolUseIds.add(toolUseId);
      // 如果 Activity 已存在且 decision 未设置，立即标记为 approved
      const existing = d.activities.get(toolUseId);
      if (existing && existing.decision === undefined) {
        existing.decision = 'approved';
      }
    }),
    isToolUseApproved: (toolUseId) => {
      return get().approvedToolUseIds.has(toolUseId);
    },
    setExpandedId: (id) => set(d => { d.expandedId = id; }),
    setL3ActivityId: (id) => set(d => { d.l3ActivityId = id; }),
    setFilter: (filter) => set(d => { d.filter = filter; }),
    toggleSelect: (id) => set(d => {
      if (d.selectedIds.has(id)) d.selectedIds.delete(id);
      else d.selectedIds.add(id);
    }),
    selectAll: () => set(d => {
      d.activities.forEach((_, id) => d.selectedIds.add(id));
    }),
    selectAllSafe: () => set(d => {
      d.activities.forEach((a, id) => {
        if (a.insight?.signal === 'auto_approve' || a.insight?.signal === 'review_recommended') {
          d.selectedIds.add(id);
        }
      });
    }),
    clearSelection: () => set(d => { d.selectedIds.clear(); }),
    setBatchMode: (enabled) => set(d => {
      d.batchMode = enabled;
      if (!enabled) d.selectedIds.clear();
    }),
    setHasMoreHistory: (hasMore, total) => set(d => {
      d.hasMoreHistory = hasMore;
      if (total !== undefined) d.totalActivityCount = total;
    }),

    /**
     * 按需加载更早的 Activity 历史（当 hasMore=true 时由 UI 触发）
     * 通过 REST API 分页获取，避免 WebSocket 大帧
     */
    loadMoreActivities: async () => {
      const sessionId = useSessionStore.getState().sessionId;
      if (!sessionId) return;
      try {
        const resp = await fetch(`/api/sessions/${sessionId}/activities?offset=${get().activities.size}&limit=50`);
        if (!resp.ok) return;
        const { activities: older, hasMore } = await resp.json();
        set(d => {
          older.forEach((a: ActivityData) => {
            if (!d.activities.has(a.id)) {
              d.activities.set(a.id, {
                ...a,
                changedFiles: Array.isArray(a.changedFiles) ? a.changedFiles : [],
              });
            }
          });
          d.hasMoreHistory = hasMore;
        });
      } catch (err) {
        console.error('[ActivityStore] loadMoreActivities failed:', err);
      }
    },
    performRetention: () => set(d => {
      const config = DEFAULT_RETENTION_CONFIG;
      const entries = Array.from(d.activities.entries());
      if (entries.length <= config.maxCount) return;

      const now = Date.now();
      // 保护条件：signal 为 blocked/manual_required 且 (未完成或24h内)
      const protectedIds = new Set<string>();
      const protectedEntries: [string, ActivityData][] = [];
      const maxProtected = Math.floor(config.maxCount * 0.5); // 保护条目上限：最多占 50%

      // 按 timestamp DESC 排序后再遍历，确保较新/更重要的条目优先被保护
      const sortedEntries = [...entries].sort((a, b) => b[1].timestamp - a[1].timestamp);

      for (const [id, a] of sortedEntries) {
        if (protectedIds.size >= maxProtected) break;
        const hasProtectedSignal = config.protectedSignals.includes(a.insight?.signal as Signal);
        const isActiveOrRecent = a.status !== 'completed' || (now - a.timestamp) < config.autoArchiveAfterMs;
        if (hasProtectedSignal && isActiveOrRecent) {
          protectedIds.add(id);
          protectedEntries.push([id, a]);
        }
      }

      // 非保护条目：使用 Set 查找 O(1)
      const unprotectedEntries = entries.filter(([id]) => !protectedIds.has(id));
      const keepCount = Math.max(0, config.maxCount - protectedEntries.length);
      const kept = unprotectedEntries.slice(-keepCount);

      d.activities = new Map([...protectedEntries, ...kept].sort((a, b) => a[1].timestamp - b[1].timestamp));
    }),
    clearAll: () => set(d => {
      d.activities.clear();
      d.deniedToolUseIds.clear();
      d.approvedToolUseIds.clear();
      d.selectedIds.clear();
      d.expandedId = null;
      d.l3ActivityId = null;
    }),
  })))
);
