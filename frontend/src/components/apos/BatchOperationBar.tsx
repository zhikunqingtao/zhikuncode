import { Check, X } from 'lucide-react';
import { useActivityStore } from '@store/activityStore';

export function BatchOperationBar() {
  const selectedIds = useActivityStore((s) => s.selectedIds);
  const selectAllSafe = useActivityStore((s) => s.selectAllSafe);
  const clearSelection = useActivityStore((s) => s.clearSelection);
  const setBatchMode = useActivityStore((s) => s.setBatchMode);
  const approveActivity = useActivityStore((s) => s.approveActivity);
  const rejectActivity = useActivityStore((s) => s.rejectActivity);

  const count = selectedIds.size;

  return (
    <div className="flex items-center gap-3 px-3 py-2 bg-[var(--bg-secondary)] border-b border-[var(--border)] flex-shrink-0">
      {/* Count */}
      <span className="text-xs text-[var(--text-secondary)]">
        已选 <strong className="text-blue-500 dark:text-blue-300">{count}</strong> 项
      </span>

      {/* Select All (safe) */}
      <button
        onClick={selectAllSafe}
        className="text-xs text-[var(--text-muted)] hover:text-[var(--text-primary)] underline underline-offset-2 transition-colors"
      >
        全选可操作
      </button>

      {/* Batch Approve */}
      <button
        disabled={count === 0}
        onClick={() => {
          selectedIds.forEach((id) => approveActivity(id));
          clearSelection();
          setBatchMode(false);
        }}
        className="inline-flex items-center gap-1 px-2.5 py-1 text-xs font-medium rounded bg-green-600/20 text-green-300 hover:bg-green-600/30 disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
      >
        <Check size={12} /> 批量批准
      </button>

      {/* Batch Reject */}
      <button
        disabled={count === 0}
        onClick={() => {
          selectedIds.forEach((id) => rejectActivity(id));
          clearSelection();
          setBatchMode(false);
        }}
        className="inline-flex items-center gap-1 px-2.5 py-1 text-xs font-medium rounded bg-red-600/20 text-red-300 hover:bg-red-600/30 disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
      >
        <X size={12} /> 批量拒绝
      </button>

      {/* Cancel */}
      <button
        onClick={() => {
          clearSelection();
          setBatchMode(false);
        }}
        className="ml-auto text-xs text-[var(--text-muted)] hover:text-[var(--text-primary)] transition-colors"
      >
        取消选择
      </button>
    </div>
  );
}
