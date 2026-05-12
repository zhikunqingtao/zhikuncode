/**
 * MobileStatusBar — 底部固定状态条
 * 固定在屏幕底部，展示 Pipeline 简化摘要 + 异常计数徽章
 * 点击可展开 MobileBottomSheet 查看详情
 */

import { useState } from 'react';
import { AlertTriangle, ChevronUp } from 'lucide-react';
import { useSwarmStore } from '@/store/swarmStore';
import { useAnomalyStore } from '@/store/anomalyStore';
import type { WorkerInfo } from '@/types';
import { MobilePipelineSummary } from './MobilePipelineSummary';
import { MobileImpactList } from './MobileImpactList';

export interface MobileStatusBarProps {
  onExpandDetails?: () => void;
}

export function MobileStatusBar({ onExpandDetails }: MobileStatusBarProps) {
  const [expanded, setExpanded] = useState(false);

  // Swarm pipeline 数据
  const activeSwarmId = useSwarmStore((s) => s.activeSwarmId);
  const swarm = useSwarmStore((s) =>
    s.activeSwarmId ? s.swarms.get(s.activeSwarmId) : undefined
  );

  // 异常计数
  const anomalyCount = useAnomalyStore((s) => s.activeAnomalies.length);

  // 获取 Worker 列表
  const workers: WorkerInfo[] = swarm
    ? Object.values(swarm.workers)
    : [];

  const handleBarClick = () => {
    if (onExpandDetails) {
      onExpandDetails();
    } else {
      setExpanded(!expanded);
    }
  };

  return (
    <>
      {/* 展开面板 */}
      {expanded && (
        <div className="fixed bottom-[44px] left-0 right-0 z-40 bg-[#12121a]/95 backdrop-blur-sm border-t border-gray-700/50 rounded-t-xl max-h-[60vh] overflow-y-auto pb-[env(safe-area-inset-bottom)]">
          <div className="py-3">
            <h4 className="text-xs font-semibold text-gray-400 uppercase tracking-wide px-3 mb-2">
              高风险文件
            </h4>
            <MobileImpactList onViewAll={() => setExpanded(false)} />
          </div>
        </div>
      )}

      {/* 固定底部状态条 */}
      <div
        className="fixed bottom-0 left-0 right-0 z-50 bg-[#12121a]/95 backdrop-blur-sm border-t border-gray-700/50"
        style={{ paddingBottom: 'env(safe-area-inset-bottom)' }}
      >
        <button
          onClick={handleBarClick}
          className="flex items-center justify-between w-full min-h-[44px] px-3 gap-2 active:bg-gray-700/30 transition-colors"
          aria-label="展开状态详情"
        >
          {/* 左侧：Pipeline 摘要 */}
          <div className="flex items-center gap-2 flex-1 min-w-0">
            {activeSwarmId && workers.length > 0 ? (
              <MobilePipelineSummary workers={workers} />
            ) : (
              <span className="text-xs text-gray-500">无活动 Pipeline</span>
            )}
          </div>

          {/* 右侧：异常计数徽章 + 展开箭头 */}
          <div className="flex items-center gap-2 flex-shrink-0">
            {anomalyCount > 0 && (
              <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full bg-red-500/20 text-red-300 text-xs font-medium">
                <AlertTriangle size={12} />
                {anomalyCount}
              </span>
            )}
            <ChevronUp
              size={16}
              className={`text-gray-500 transition-transform ${expanded ? 'rotate-180' : ''}`}
            />
          </div>
        </button>
      </div>
    </>
  );
}
