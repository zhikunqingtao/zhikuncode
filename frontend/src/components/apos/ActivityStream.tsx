import { useMemo, useCallback } from 'react';
import { Virtuoso } from 'react-virtuoso';
import { Inbox } from 'lucide-react';
import { useActivityStore } from '@store/activityStore';
import { useInsightStore } from '@store/insightStore';
import { useSessionStore } from '@/store/sessionStore';
import type { ActivityData, Signal } from '@/types/apos';
import { ActivityCardL1 } from './ActivityCardL1';
import { ActivityCardL2 } from './ActivityCardL2';
import { ActivityCardL3 } from './ActivityCardL3';
import { BatchOperationBar } from './BatchOperationBar';
import { RiskHeatmap } from './RiskHeatmap';

const SIGNAL_FILTERS: { label: string; value: Signal | 'all' }[] = [
  { label: '全部', value: 'all' },
  { label: '可放行', value: 'auto_approve' },
  { label: '建议审查', value: 'review_recommended' },
  { label: '需手动', value: 'manual_required' },
  { label: '已阻止', value: 'blocked' },
];

export function ActivityStream() {
  const activities = useActivityStore((s) => s.activities);
  const currentSessionId = useSessionStore((s) => s.sessionId);
  const expandedId = useActivityStore((s) => s.expandedId);
  const l3ActivityId = useActivityStore((s) => s.l3ActivityId);
  const filter = useActivityStore((s) => s.filter);
  const selectedIds = useActivityStore((s) => s.selectedIds);
  const batchMode = useActivityStore((s) => s.batchMode);
  const setExpandedId = useActivityStore((s) => s.setExpandedId);
  const setL3ActivityId = useActivityStore((s) => s.setL3ActivityId);
  const setFilter = useActivityStore((s) => s.setFilter);
  const toggleSelect = useActivityStore((s) => s.toggleSelect);
  const approveActivity = useActivityStore((s) => s.approveActivity);
  const rejectActivity = useActivityStore((s) => s.rejectActivity);

  const assessments = useInsightStore((s) => s.assessments);

  // Convert map to sorted array, apply filter (session-scoped)
  const sortedActivities = useMemo(() => {
    const arr = Array.from(activities.values())
      .filter(a => a.sessionId === currentSessionId);
    const filtered = arr.filter((a) => {
      if (filter.signal && filter.signal.length > 0) {
        if (!a.insight?.signal || !filter.signal.includes(a.insight.signal)) return false;
      }
      if (filter.operationType && filter.operationType.length > 0) {
        if (!filter.operationType.includes(a.operationType)) return false;
      }
      return true;
    });
    return filtered.sort((a, b) => b.timestamp - a.timestamp);
  }, [activities, filter, currentSessionId]);

  const handleFilterChange = useCallback(
    (value: Signal | 'all') => {
      if (value === 'all') {
        setFilter({});
      } else {
        setFilter({ signal: [value] });
      }
    },
    [setFilter]
  );

  const activeFilterValue: Signal | 'all' =
    filter.signal && filter.signal.length === 1 ? filter.signal[0] : 'all';

  const l3Activity = l3ActivityId ? activities.get(l3ActivityId) : undefined;
  const l3Assessment = l3ActivityId ? assessments.get(l3ActivityId) : undefined;

  const renderItem = useCallback(
    (_index: number, activity: ActivityData) => {
      const isExpanded = expandedId === activity.id;
      const isSelected = selectedIds.has(activity.id);
      const assessment = assessments.get(activity.id);

      return (
        <div key={activity.id}>
          <ActivityCardL1
            activity={activity}
            isExpanded={isExpanded}
            isSelected={isSelected}
            batchMode={batchMode}
            onClick={() => setExpandedId(isExpanded ? null : activity.id)}
            onToggleSelect={() => toggleSelect(activity.id)}
          />
          <ActivityCardL2
            activity={activity}
            assessment={assessment}
            isVisible={isExpanded}
            onApprove={() => approveActivity(activity.id)}
            onReject={() => rejectActivity(activity.id)}
            onViewDetails={() => setL3ActivityId(activity.id)}
          />
        </div>
      );
    },
    [expandedId, selectedIds, batchMode, assessments, setExpandedId, toggleSelect, setL3ActivityId, approveActivity, rejectActivity]
  );

  return (
    <div className="flex flex-col h-full bg-[var(--bg-primary)]">
      {/* Filter Bar */}
      <div className="flex items-center gap-1 px-3 py-2 border-b border-[var(--border)] flex-shrink-0">
        {SIGNAL_FILTERS.map((f) => (
          <button
            key={f.value}
            onClick={() => handleFilterChange(f.value)}
            className={`px-2.5 py-1 text-xs rounded-full transition-colors ${
              activeFilterValue === f.value
                ? 'bg-blue-600/30 text-blue-600 dark:text-blue-300 font-medium'
                : 'text-[var(--text-muted)] hover:text-[var(--text-primary)] hover:bg-[var(--bg-hover)]'
            }`}
          >
            {f.label}
          </button>
        ))}
      </div>

      {/* Risk Heatmap */}
      <RiskHeatmap />

      {/* Batch Mode Bar */}
      {batchMode && <BatchOperationBar />}

      {/* Activity List */}
      {sortedActivities.length === 0 ? (
        <div className="flex-1 flex flex-col items-center justify-center text-[var(--text-muted)] gap-3">
          <Inbox size={40} className="text-[var(--text-muted)]" />
          <p className="text-sm">暂无活动记录</p>
        </div>
      ) : (
        <Virtuoso
          className="flex-1"
          data={sortedActivities}
          itemContent={renderItem}
          overscan={200}
        />
      )}

      {/* L3 Portal */}
      {l3Activity && (
        <ActivityCardL3
          activity={l3Activity}
          assessment={l3Assessment}
          onClose={() => setL3ActivityId(null)}
          onApprove={() => approveActivity(l3Activity.id)}
          onReject={() => rejectActivity(l3Activity.id)}
        />
      )}
    </div>
  );
}
