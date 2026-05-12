/**
 * MobilePipelineSummary — Pipeline 简化为水平状态点
 * 每个圆点代表一个 Worker，颜色映射其状态
 */

import type { WorkerInfo } from '@/types';

export interface MobilePipelineSummaryProps {
  workers: WorkerInfo[];
}

/** 状态 → 圆点颜色映射 */
function getWorkerDotClass(worker: WorkerInfo): string {
  switch (worker.status) {
    case 'STARTING':
      return 'bg-gray-400';
    case 'WORKING':
      return 'bg-blue-500 animate-pulse';
    case 'IDLE':
      return 'bg-yellow-400';
    case 'TERMINATED':
      if (worker.terminationReason === 'completed') return 'bg-green-500';
      if (worker.terminationReason === 'error') return 'bg-red-500';
      return 'bg-gray-500';
    default:
      return 'bg-gray-400';
  }
}

export function MobilePipelineSummary({ workers }: MobilePipelineSummaryProps) {
  const completed = workers.filter(
    (w) => w.status === 'TERMINATED' && w.terminationReason === 'completed'
  ).length;

  return (
    <div className="flex items-center gap-2 min-h-[44px] min-w-[44px] px-2">
      {/* 状态圆点 */}
      <div className="flex items-center gap-1.5">
        {workers.map((worker) => (
          <span
            key={worker.workerId}
            className={`inline-block w-2.5 h-2.5 rounded-full ${getWorkerDotClass(worker)}`}
            title={`${worker.workerId}: ${worker.status}`}
          />
        ))}
      </div>

      {/* 进度文本 */}
      <span className="text-xs text-gray-400 whitespace-nowrap">
        {completed}/{workers.length} 完成
      </span>
    </div>
  );
}
