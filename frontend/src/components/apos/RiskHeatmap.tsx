import { useMemo } from 'react';
import { useActivityStore } from '@store/activityStore';
import { useSessionStore } from '@/store/sessionStore';
import { useFeatureFlagStore } from '@store/featureFlagStore';
import { SIGNAL_CONFIG, type Signal } from '@/types/apos';

const SIGNAL_ORDER: Signal[] = ['auto_approve', 'review_recommended', 'manual_required', 'blocked'];

export function RiskHeatmap() {
  const activities = useActivityStore((s) => s.activities);
  const currentSessionId = useSessionStore((s) => s.sessionId);
  const heatmapEnabled = useFeatureFlagStore((s) => s.flags.APOS_RISK_HEATMAP);

  const counts = useMemo(() => {
    const result: Record<Signal, number> = {
      auto_approve: 0,
      review_recommended: 0,
      manual_required: 0,
      blocked: 0,
    };
    activities.forEach((activity) => {
      if (activity.sessionId !== currentSessionId) return;
      const signal = activity.insight?.signal;
      if (signal && signal in result) {
        result[signal]++;
      }
    });
    return result;
  }, [activities, currentSessionId]);

  const total = useMemo(
    () => Object.values(counts).reduce((a, b) => a + b, 0),
    [counts]
  );

  if (!heatmapEnabled) return null;
  if (total === 0) return null;

  return (
    <div className="px-3 py-2 border-b border-[var(--border)]">
      {/* Header */}
      <div className="flex items-center justify-between mb-1">
        <span className="text-[10px] text-[var(--text-muted)] uppercase tracking-wider">风险分布</span>
        <span className="text-[10px] text-[var(--text-muted)]">{total} ops</span>
      </div>

      {/* Bar */}
      <div className="flex h-2 rounded-sm overflow-hidden gap-px">
        {SIGNAL_ORDER.map((signal) => {
          const count = counts[signal];
          if (count === 0) return null;
          const width = Math.max((count / total) * 100, 2);
          return (
            <div
              key={signal}
              className={`${SIGNAL_CONFIG[signal].color} rounded-sm`}
              style={{ width: `${width}%` }}
            />
          );
        })}
      </div>

      {/* Legend */}
      <div className="flex gap-3 mt-1">
        {SIGNAL_ORDER.map((signal) => {
          const count = counts[signal];
          if (count === 0) return null;
          return (
            <div key={signal} className="flex items-center gap-1">
              <div className={`w-2 h-2 rounded-sm ${SIGNAL_CONFIG[signal].color}`} />
              <span className="text-[10px] text-[var(--text-secondary)]">{count}</span>
            </div>
          );
        })}
      </div>
    </div>
  );
}
