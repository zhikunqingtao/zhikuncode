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
      return 'bg-t3';
    case 'WORKING':
      return 'bg-accent motion-safe:animate-pulse';
    case 'IDLE':
      return 'bg-warn';
    case 'TERMINATED':
      if (worker.terminationReason === 'completed') return 'bg-ok';
      if (worker.terminationReason === 'error') return 'bg-err';
      return 'bg-t2';
    default:
      return 'bg-t3';
  }
}

export function MobilePipelineSummary({ workers }: MobilePipelineSummaryProps) {
  const completed = workers.filter(
    (w) => w.status === 'TERMINATED' && w.terminationReason === 'completed'
  ).length;

  return (
    <div className="flex items-center gap-2 min-w-[44px] px-2">
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
      <span className="text-[13px] text-t2 whitespace-nowrap">
        {completed}/{workers.length} 完成
      </span>
    </div>
  );
}
