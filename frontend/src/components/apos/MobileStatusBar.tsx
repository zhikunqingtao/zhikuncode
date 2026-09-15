/**
 * MobileStatusBar — 底部固定状态细条（§8.5）
 * 固定在屏幕底部，展示 Pipeline 简化摘要 + 异常计数徽章
 * 点击展开 §8.4 MobileBottomSheet 查看详情（高风险文件 / 最新 Activity / 审批操作）
 *
 * 令牌化：全部颜色走 v2 令牌（surfacev2 / t1..t3 / hairline / errsoft / shadow-e1）
 * 形态：细条视觉高度 ≤36px（h-9），通过 ::after 上下各扩 4px 补足 ≥44px 点击区（§8.6）
 */

import { useSessionStore } from '@/store/sessionStore';
import { useMemo, useState } from 'react';
import { AlertTriangle, ChevronUp } from 'lucide-react';
import { useSwarmStore } from '@/store/swarmStore';
import { useAnomalyStore } from '@/store/anomalyStore';
import { useActivityStore } from '@/store/activityStore';
import type { ActivityData } from '@/types/apos';
import type { WorkerInfo } from '@/types';
import { MobilePipelineSummary } from './MobilePipelineSummary';
import { MobileBottomSheet } from './MobileBottomSheet';

export interface MobileStatusBarProps {
  onExpandDetails?: () => void;
}

export function MobileStatusBar({ onExpandDetails }: MobileStatusBarProps) {
  const [sheetOpen, setSheetOpen] = useState(false);

  // Swarm pipeline 数据
  const activeSwarmId = useSwarmStore((s) => s.activeSwarmId);
  const swarm = useSwarmStore((s) =>
    s.activeSwarmId ? s.swarms.get(s.activeSwarmId) : undefined
  );

  // 异常计数
  const anomalyCount = useAnomalyStore((s) => s.activeAnomalies.length);

  // 最新 Activity —— sheet 的详情对象（§8.4 接线）
  const sessionId = useSessionStore(s => s.sessionId);
  const sessionStatus = useSessionStore(s => s.status);
  const activities = useActivityStore((s) => s.activities);
  const latestActivity = useMemo(() => {
    let latest: ActivityData | undefined;
    let pending: ActivityData | undefined;
    activities.forEach((a) => {
      if (sessionId && a.sessionId !== sessionId) return;
      if (!latest || a.timestamp > latest.timestamp) latest = a;
      if (!a.decision && a.insight && a.insight.signal !== 'auto_approve' && (!pending || a.timestamp > pending.timestamp)) pending = a;
    });
    return pending ?? latest;
  }, [activities, sessionId]);

  // 获取 Worker 列表
  const workers: WorkerInfo[] = swarm
    ? Object.values(swarm.workers)
    : [];

  const handleBarClick = () => {
    if (onExpandDetails) {
      onExpandDetails();
    } else {
      setSheetOpen((v) => !v);
    }
  };

  const handleApprove = (id: string) => {
    useActivityStore.getState().approveActivity(id);
  };
  const handleReject = (id: string) => {
    useActivityStore.getState().rejectActivity(id);
  };
  const handleViewDetails = (id: string) => {
    useActivityStore.getState().setL3ActivityId(id);
    setSheetOpen(false);
  };

  const pendingApproval = Boolean(latestActivity && !latestActivity.decision && latestActivity.insight && latestActivity.insight.signal !== 'auto_approve');
  const activityRunning = latestActivity?.status === 'running';
  const activityError = latestActivity?.status === 'error' || latestActivity?.status === 'failed' || latestActivity?.toolResult?.isError;
  const swarmRunning = workers.some(worker => ['STARTING', 'WORKING'].includes(worker.status));
  const visible = sessionStatus !== 'idle' || pendingApproval || activityRunning || activityError || anomalyCount > 0 || swarmRunning;
  if (!visible && !sheetOpen) return null;

  return (
    <>
      {/* §8.4 Bottom Sheet — 点击状态细条展开 */}
      <MobileBottomSheet
        isOpen={sheetOpen}
        onClose={() => setSheetOpen(false)}
        activity={latestActivity}
        onApprove={handleApprove}
        onReject={handleReject}
        onViewDetails={handleViewDetails}
      />

      {/* 固定底部状态细条 — ≤36px 视觉高度，44px 点击区 */}
      <div
        className="shrink-0 relative z-50 bg-[color:color-mix(in_srgb,var(--v2-bg-surface)_95%,transparent)] backdrop-blur-sm border-t border-hairline shadow-e1"
        style={{ paddingBottom: 'env(safe-area-inset-bottom)' }}
      >
        <button
          onClick={handleBarClick}
          aria-expanded={sheetOpen}
          className="panel-control relative flex items-center justify-between w-full h-9 px-3 gap-2 active:bg-hover2 transition-colors duration-fast
            after:absolute after:inset-x-0 after:-inset-y-1 after:content-['']"
          aria-label="展开状态详情"
        >
          {/* 左侧：Pipeline 摘要 */}
          <div className="flex items-center gap-2 flex-1 min-w-0">
            {activeSwarmId && workers.length > 0 ? (
              <MobilePipelineSummary workers={workers} />
            ) : (
              <span className="text-[13px] text-t2">{pendingApproval || sessionStatus === 'waiting_permission' ? '待审批' : activityError || anomalyCount > 0 ? '有异常待查看' : sessionStatus === 'compacting' ? '压缩中' : sessionStatus === 'streaming' || activityRunning ? '运行中' : '任务详情'}</span>
            )}
          </div>

          {/* 右侧：异常计数徽章 + 展开箭头 */}
          <div className="flex items-center gap-2 flex-shrink-0">
            {anomalyCount > 0 && (
              <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full bg-errsoft text-errstrong text-[13px] font-medium">
                <AlertTriangle size={12} />
                {anomalyCount}
              </span>
            )}
            <ChevronUp
              size={16}
              className={`text-t3 transition-transform duration-base ${sheetOpen ? 'rotate-180' : ''}`}
            />
          </div>
        </button>
      </div>
    </>
  );
}
