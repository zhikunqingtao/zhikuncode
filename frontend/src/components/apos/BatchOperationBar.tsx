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
    <div className="flex items-center gap-3 px-3 py-2 bg-[var(--v2-bg-sunken)] border-b border-[var(--v2-border-hairline)] flex-shrink-0">
      {/* Count */}
      <span className="text-[13px] text-[var(--v2-text-2)]">
        已选 <strong className="text-accent2-ink">{count}</strong> 项
      </span>

      {/* Select All (safe) */}
      <button
        onClick={selectAllSafe}
        className="panel-control text-[13px] text-[var(--v2-text-2)] hover:text-[var(--v2-text-1)] underline underline-offset-2 transition-colors"
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
        className="panel-control inline-flex items-center gap-1 px-2.5 py-1 text-[13px] font-medium rounded bg-oksoft text-ok hover:bg-oksoft disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
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
        className="panel-control inline-flex items-center gap-1 px-2.5 py-1 text-[13px] font-medium rounded bg-errsoft text-err hover:bg-errsoft disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
      >
        <X size={12} /> 批量拒绝
      </button>

      {/* Cancel */}
      <button
        onClick={() => {
          clearSelection();
          setBatchMode(false);
        }}
        className="panel-control ml-auto text-[13px] text-[var(--v2-text-2)] hover:text-[var(--v2-text-1)] transition-colors"
      >
        取消选择
      </button>
    </div>
  );
}
