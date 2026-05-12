import { useCallback } from 'react';
import type { ActivityData } from '@/types/apos';
import { OperationIcon } from './OperationIcon';
import { SignalBadge } from './SignalBadge';

interface ActivityCardL1Props {
  activity: ActivityData;
  isExpanded: boolean;
  isSelected: boolean;
  batchMode: boolean;
  onClick: () => void;
  onToggleSelect: () => void;
}

function formatRelativeTime(timestamp: number): string {
  const diff = Date.now() - timestamp;
  const seconds = Math.floor(diff / 1000);
  if (seconds < 60) return `${seconds}s ago`;
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.floor(hours / 24);
  return `${days}d ago`;
}

export function ActivityCardL1({
  activity,
  isExpanded,
  isSelected,
  batchMode,
  onClick,
  onToggleSelect,
}: ActivityCardL1Props) {
  const handleCheckboxClick = useCallback(
    (e: React.MouseEvent) => {
      e.stopPropagation();
      onToggleSelect();
    },
    [onToggleSelect]
  );

  const signal = activity.insight?.signal
    ?? (activity.status === 'completed' || activity.status === 'error' ? 'unavailable' : 'loading');

  return (
    <div
      data-testid="activity-card-l1"
      onClick={onClick}
      className={`h-[52px] flex items-center gap-3 px-3 cursor-pointer border-b border-[var(--border)] transition-colors
        ${isExpanded ? 'bg-[var(--bg-hover)]' : 'hover:bg-[var(--bg-hover)]'}
        ${isSelected ? 'bg-blue-500/10 border-l-2 border-l-blue-500' : ''}
      `}
    >
      {/* Left: Checkbox (batch mode) + Operation Icon */}
      <div className="flex items-center gap-2 flex-shrink-0">
        {batchMode && (
          <input
            type="checkbox"
            checked={isSelected}
            onClick={handleCheckboxClick}
            onChange={() => {}}
            className="w-4 h-4 rounded border-gray-600 bg-gray-800 text-blue-500 focus:ring-blue-500 focus:ring-offset-0 cursor-pointer"
          />
        )}
        <OperationIcon type={activity.operationType} size={18} />
      </div>

      {/* Middle: Summary + Agent */}
      <div className="flex-1 min-w-0">
        <p className="text-sm text-[var(--text-primary)] truncate leading-tight">
          {activity.summary}
        </p>
        <p className="text-xs text-[var(--text-muted)] truncate leading-tight">
          {activity.fileCount ?? activity.changedFiles.length} 文件
          {activity.duration ? ` · ${(activity.duration / 1000).toFixed(1)}s` : ''}
        </p>
      </div>

      {/* Right: Signal Badge + Timestamp */}
      <div className="flex items-center gap-2 flex-shrink-0">
        <SignalBadge
          signal={signal}
          size="sm"
          reason={activity.insight?.summary}
        />
        <span className="text-xs text-[var(--text-muted)] whitespace-nowrap shrink-0">
          {formatRelativeTime(activity.timestamp)}
        </span>
      </div>
    </div>
  );
}
