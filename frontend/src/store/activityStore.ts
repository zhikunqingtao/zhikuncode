import { create } from 'zustand';
import { immer } from 'zustand/middleware/immer';
import { subscribeWithSelector } from 'zustand/middleware';
import type { ActivityData, ActivityFilter, Signal } from '@/types/apos';
import { DEFAULT_RETENTION_CONFIG } from '@/types/apos';
import { updateActivityDecision } from '@/api/activityApi';

interface ActivityStoreState {
  activities: Map<string, ActivityData>;
  currentSessionId: string | null;
  expandedId: string | null;
  l3ActivityId: string | null;
  filter: ActivityFilter;
  selectedIds: Set<string>;
  batchMode: boolean;

  setCurrentSessionId: (sessionId: string) => void;
  clearForNewSession: () => void;
  addActivity: (activity: ActivityData) => void;
  updateActivity: (id: string, partial: Partial<ActivityData>) => void;
  attachInsight: (activityId: string, insight: ActivityData['insight']) => void;
  approveActivity: (id: string) => void;
  rejectActivity: (id: string) => void;
  setExpandedId: (id: string | null) => void;
  setL3ActivityId: (id: string | null) => void;
  setFilter: (filter: ActivityFilter) => void;
  toggleSelect: (id: string) => void;
  selectAll: () => void;
  selectAllSafe: () => void; // only auto_approve + review_recommended
  clearSelection: () => void;
  setBatchMode: (enabled: boolean) => void;
  performRetention: () => void;
  clearAll: () => void;
}

export const useActivityStore = create<ActivityStoreState>()(
  subscribeWithSelector(immer((set) => ({
    activities: new Map(),
    currentSessionId: null,
    expandedId: null,
    l3ActivityId: null,
    filter: {},
    selectedIds: new Set(),
    batchMode: false,

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
        d.activities.set(activity.id, activity);
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
    performRetention: () => set(d => {
      const config = DEFAULT_RETENTION_CONFIG;
      const entries = Array.from(d.activities.entries());
      if (entries.length <= config.maxCount) return;

      const now = Date.now();
      const protectedEntries = entries.filter(([_, a]) =>
        config.protectedSignals.includes(a.insight?.signal as Signal) ||
        (a.status !== 'completed' || now - a.timestamp < config.autoArchiveAfterMs)
      );
      const unprotectedEntries = entries.filter(([_, a]) => !protectedEntries.find(([id]) => id === a.id));
      const kept = unprotectedEntries.slice(-(config.maxCount - protectedEntries.length));

      d.activities = new Map([...protectedEntries, ...kept].sort((a, b) => a[1].timestamp - b[1].timestamp));
    }),
    clearAll: () => set(d => {
      d.activities.clear();
      d.selectedIds.clear();
      d.expandedId = null;
      d.l3ActivityId = null;
    }),
  })))
);
