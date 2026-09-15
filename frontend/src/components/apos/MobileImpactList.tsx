/**
 * MobileImpactList — Top 5 高风险文件列表
 * 按 riskLevel 降序 + touchCount 降序排列
 */

import { useChangeImpactAggStore } from '@/store/changeImpactStore';

export interface MobileImpactListProps {
  onViewAll?: () => void;
}

const RISK_COLORS: Record<string, string> = {
  danger: 'bg-red-500',
  warning: 'bg-yellow-500',
  review: 'bg-blue-400',
  safe: 'bg-green-500',
};

const RISK_ORDER: Record<string, number> = { danger: 0, warning: 1, review: 2, safe: 3 };

export function MobileImpactList({ onViewAll }: MobileImpactListProps) {
  const aggregatedChanges = useChangeImpactAggStore((s) => s.aggregatedChanges);

  // Top 5：按 riskLevel 降序，同级按 touchCount 降序
  const top5 = [...aggregatedChanges]
    .sort((a, b) => {
      const riskDiff = (RISK_ORDER[a.riskLevel] ?? 99) - (RISK_ORDER[b.riskLevel] ?? 99);
      if (riskDiff !== 0) return riskDiff;
      return b.touchCount - a.touchCount;
    })
    .slice(0, 5);

  if (top5.length === 0) {
    return (
      <div className="flex items-center justify-center min-h-[44px] px-3">
        <span className="text-[13px] text-t3">暂无变更影响数据</span>
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-2 px-3 py-2">
      {top5.map((file) => (
        <div
          key={file.filePath}
          className="flex items-center gap-2 min-h-[44px] rounded-lg bg-surface2 px-3 py-2"
        >
          {/* 风险色标 */}
          <span
            className={`flex-shrink-0 w-2.5 h-2.5 rounded-full ${RISK_COLORS[file.riskLevel] ?? RISK_COLORS.safe}`}
          />
          {/* 文件名 */}
          <span className="flex-1 text-[13px] text-t1 truncate font-mono">
            {file.filePath.split('/').pop() ?? file.filePath}
          </span>
          {/* touchCount */}
          <span className="flex-shrink-0 text-[13px] text-t3 tabular-nums">
            ×{file.touchCount}
          </span>
        </div>
      ))}

      {/* 查看全部按钮 */}
      {aggregatedChanges.length > 5 && onViewAll && (
        <button
          onClick={onViewAll}
          className="panel-control flex items-center justify-center min-h-[44px] min-w-[44px] rounded-lg text-[13px] text-accent2-ink hover:bg-accent2-soft active:bg-accent2-soft transition-colors duration-fast"
        >
          查看全部 ({aggregatedChanges.length})
        </button>
      )}
    </div>
  );
}
