import { useCallback } from 'react';
import { useChangeImpactAggStore } from '@/store/changeImpactStore';
import { FileChangeItem } from './FileChangeItem';

export function ChangeImpactPanel() {
  const aggregatedChanges = useChangeImpactAggStore(s => s.aggregatedChanges);
  const riskSummary = useChangeImpactAggStore(s => s.riskSummary);
  const selectFile = useChangeImpactAggStore(s => s.selectFile);

  const handleFileClick = useCallback((filePath: string) => {
    selectFile(filePath);
  }, [selectFile]);

  // Empty state
  if (aggregatedChanges.length === 0) {
    return (
      <div className="flex flex-col items-center justify-center py-12 text-[var(--text-muted)]">
        <svg className="w-10 h-10 mb-3 opacity-40" fill="none" viewBox="0 0 24 24" stroke="currentColor">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5}
            d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
        </svg>
        <p className="text-sm">暂无变更影响数据</p>
        <p className="text-xs mt-1 opacity-70">当会话产生代码变更后，此处将展示聚合分析</p>
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-3 p-3">
      {/* Risk Summary Cards */}
      <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
        <SummaryCard label="总文件数" value={riskSummary.totalFiles} variant="default" />
        <SummaryCard label="高风险" value={riskSummary.highRiskCount} variant="danger" />
        <SummaryCard label="测试缺口" value={riskSummary.testCoverageGapCount} variant="warning" />
        <SummaryCard label="间接影响" value={riskSummary.indirectImpactCount} variant="info" />
      </div>

      {/* File List */}
      <div className="flex flex-col gap-1.5">
        {aggregatedChanges.map(file => (
          <FileChangeItem
            key={file.filePath}
            file={file}
            onClick={handleFileClick}
          />
        ))}
      </div>
    </div>
  );
}

// ── Internal Summary Card ──

interface SummaryCardProps {
  label: string;
  value: number;
  variant: 'default' | 'danger' | 'warning' | 'info';
}

const VARIANT_STYLES: Record<SummaryCardProps['variant'], string> = {
  default: 'border-[var(--border)] text-[var(--text-primary)]',
  danger: 'border-red-500/30 text-red-400',
  warning: 'border-yellow-500/30 text-yellow-400',
  info: 'border-blue-500/30 text-blue-400',
};

function SummaryCard({ label, value, variant }: SummaryCardProps) {
  return (
    <div className={`rounded-md border px-3 py-2 bg-[var(--bg-card)] ${VARIANT_STYLES[variant]}`}>
      <p className="text-lg font-semibold leading-tight">{value}</p>
      <p className="text-xs text-[var(--text-muted)] mt-0.5">{label}</p>
    </div>
  );
}
