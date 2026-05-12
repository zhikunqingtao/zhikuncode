import { useState, useCallback } from 'react';
import type { AggregatedFileChange } from '@/types/apos';

interface FileChangeItemProps {
  file: AggregatedFileChange;
  onClick: (filePath: string) => void;
}

const RISK_BG: Record<AggregatedFileChange['riskLevel'], string> = {
  danger: 'bg-red-500/10 border-red-500/30',
  warning: 'bg-yellow-500/10 border-yellow-500/30',
  review: 'bg-blue-500/10 border-blue-500/30',
  safe: 'bg-transparent border-[var(--border)]',
};

const CHANGE_TYPE_CONFIG: Record<AggregatedFileChange['changeType'], { icon: string; color: string }> = {
  added: { icon: '+', color: 'text-green-400' },
  modified: { icon: '~', color: 'text-orange-400' },
  deleted: { icon: '-', color: 'text-red-400' },
};

function truncatePath(filePath: string): string {
  const segments = filePath.split('/');
  if (segments.length <= 3) return filePath;
  return '…/' + segments.slice(-3).join('/');
}

export function FileChangeItem({ file, onClick }: FileChangeItemProps) {
  const [expanded, setExpanded] = useState(false);

  const handleClick = useCallback(() => {
    onClick(file.filePath);
  }, [onClick, file.filePath]);

  const handleExpandToggle = useCallback((e: React.MouseEvent) => {
    e.stopPropagation();
    setExpanded(prev => !prev);
  }, []);

  const changeConfig = CHANGE_TYPE_CONFIG[file.changeType];
  const riskBg = RISK_BG[file.riskLevel];

  return (
    <div
      onClick={handleClick}
      className={`rounded-md border px-3 py-2 cursor-pointer transition-colors hover:opacity-90 ${riskBg}`}
    >
      {/* Top row: icon + path + badges */}
      <div className="flex items-center gap-2">
        {/* Change type icon */}
        <span className={`font-mono text-base font-bold ${changeConfig.color} w-5 text-center flex-shrink-0`}>
          {changeConfig.icon}
        </span>

        {/* File path */}
        <span className="text-sm text-[var(--text-primary)] truncate flex-1 min-w-0" title={file.filePath}>
          {truncatePath(file.filePath)}
        </span>

        {/* Touch count badge */}
        {file.touchCount > 1 && (
          <span className="text-xs px-1.5 py-0.5 rounded bg-[var(--bg-hover)] text-[var(--text-muted)] flex-shrink-0">
            ×{file.touchCount}
          </span>
        )}

        {/* Test coverage gap label */}
        {file.testCoverageGap && (
          <span className="text-xs px-1.5 py-0.5 rounded bg-red-500/20 text-red-300 flex-shrink-0">
            缺少测试覆盖
          </span>
        )}
      </div>

      {/* Additions / Deletions summary */}
      <div className="flex items-center gap-3 mt-1 ml-7">
        <span className="text-xs text-green-400">+{file.totalAdditions}</span>
        <span className="text-xs text-red-400">-{file.totalDeletions}</span>
        {file.riskReason && (
          <span className="text-xs text-[var(--text-muted)] truncate">{file.riskReason}</span>
        )}
      </div>

      {/* Indirect impacts collapsible section */}
      {file.indirectImpacts.length > 0 && (
        <div className="mt-1.5 ml-7">
          <button
            onClick={handleExpandToggle}
            className="text-xs text-blue-400 hover:text-blue-300 transition-colors"
          >
            {expanded ? '▾' : '▸'} 间接影响 ({file.indirectImpacts.length})
          </button>

          {expanded && (
            <ul className="mt-1 space-y-0.5">
              {file.indirectImpacts.map((impact, idx) => (
                <li key={idx} className="text-xs text-[var(--text-muted)] flex items-start gap-1.5">
                  <span className={`flex-shrink-0 mt-0.5 w-1.5 h-1.5 rounded-full ${
                    impact.severity === 'high' ? 'bg-red-400' :
                    impact.severity === 'medium' ? 'bg-yellow-400' : 'bg-blue-400'
                  }`} />
                  <span className="truncate" title={impact.filePath}>
                    {truncatePath(impact.filePath)} — {impact.reason}
                  </span>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  );
}
