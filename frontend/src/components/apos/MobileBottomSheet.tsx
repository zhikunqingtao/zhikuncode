import { useEffect, useCallback } from 'react';
import { createPortal } from 'react-dom';
import { motion, AnimatePresence, type PanInfo } from 'framer-motion';
import { X, Check, ArrowRight } from 'lucide-react';
import type { ActivityData, RiskAssessment } from '@/types/apos';
import { useActivityStore } from '@/store/activityStore';
import { useInsightStore } from '@/store/insightStore';
import { SignalBadge } from './SignalBadge';
import { VerificationIcon } from './VerificationIcon';

export interface MobileBottomSheetProps {
  isOpen: boolean;
  onClose: () => void;
  activity: ActivityData;
  assessment?: RiskAssessment;
  onApprove?: (id: string) => void;
  onReject?: (id: string) => void;
  onViewDetails?: (id: string) => void;
}

const DRAG_CLOSE_THRESHOLD = 100;

const IMPACT_BADGE_COLORS: Record<string, string> = {
  direct: 'bg-red-500/20 text-red-300',
  indirect: 'bg-yellow-500/20 text-yellow-300',
  potential: 'bg-blue-500/20 text-blue-300',
};

export function MobileBottomSheet({
  isOpen,
  onClose,
  activity: activityProp,
  assessment: assessmentProp,
  onApprove,
  onReject,
  onViewDetails,
}: MobileBottomSheetProps) {
  // 订阅 store 获取最新数据，prop 作为 fallback
  const liveActivity = useActivityStore(
    (s) => s.activities.get(activityProp.id)
  );
  const liveAssessment = useInsightStore((s) => s.assessments.get(activityProp.id));
  const activity = liveActivity ?? activityProp;
  const assessment = liveAssessment ?? assessmentProp;
  // ESC key close
  const handleKeyDown = useCallback(
    (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    },
    [onClose]
  );

  // Lock body scroll when open
  useEffect(() => {
    if (isOpen) {
      document.body.style.overflow = 'hidden';
      document.addEventListener('keydown', handleKeyDown);
    }
    return () => {
      document.body.style.overflow = '';
      document.removeEventListener('keydown', handleKeyDown);
    };
  }, [isOpen, handleKeyDown]);

  // Drag end handler — close if dragged down past threshold
  const handleDragEnd = (_event: MouseEvent | TouchEvent | PointerEvent, info: PanInfo) => {
    if (info.offset.y > DRAG_CLOSE_THRESHOLD) {
      onClose();
    }
  };

  const signal = activity.insight?.signal ?? 'auto_approve';

  return createPortal(
    <AnimatePresence>
      {isOpen && (
        <div className="fixed inset-0 z-[9999] flex items-end">
          {/* Overlay */}
          <motion.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            transition={{ duration: 0.2 }}
            className="absolute inset-0 bg-black/60"
            onClick={onClose}
          />

          {/* Bottom Sheet Panel */}
          <motion.div
            initial={{ y: '100%' }}
            animate={{ y: 0 }}
            exit={{ y: '100%' }}
            transition={{ type: 'spring', damping: 25, stiffness: 300 }}
            drag="y"
            dragConstraints={{ top: 0, bottom: 0 }}
            dragElastic={{ top: 0, bottom: 0.6 }}
            onDragEnd={handleDragEnd}
            className="relative w-full rounded-t-2xl bg-[#1e1e2e] shadow-2xl z-10"
            style={{ minHeight: '60vh', maxHeight: '90vh' }}
          >
            {/* Drag Handle */}
            <div className="flex justify-center pt-3 pb-1">
              <div className="w-10 h-1 rounded-full bg-gray-500" />
            </div>

            {/* Header */}
            <div className="flex items-center justify-between px-4 pb-3 border-b border-gray-700/50">
              <div className="flex items-center gap-2 min-w-0">
                <h3 className="text-sm font-semibold text-gray-100 truncate">
                  {activity.summary}
                </h3>
                <SignalBadge signal={signal} size="sm" />
              </div>
              <button
                onClick={onClose}
                className="flex-shrink-0 p-1.5 rounded-full hover:bg-gray-700/50 transition-colors"
                aria-label="关闭"
              >
                <X size={18} className="text-gray-400" />
              </button>
            </div>

            {/* Scrollable Body */}
            <div
              className="overflow-y-auto px-4 py-3 space-y-4"
              style={{ maxHeight: 'calc(90vh - 140px)' }}
            >
              {/* Deterministic Verification Results */}
              {assessment && (
                <div className="space-y-2">
                  <h4 className="text-xs font-semibold text-gray-400 uppercase tracking-wide">
                    确定性验证
                  </h4>
                  <div className="grid grid-cols-3 gap-2">
                    {/* TypeScript Check */}
                    <div className="flex items-center gap-1.5 text-xs">
                      <VerificationIcon
                        status={assessment.deterministic.typeCheck.passed ? 'all_pass' : 'has_error'}
                        size={14}
                      />
                      <span className="text-gray-300">tsc</span>
                      {assessment.deterministic.typeCheck.errorCount > 0 && (
                        <span className="text-red-400">
                          {assessment.deterministic.typeCheck.errorCount} 错误
                        </span>
                      )}
                      {assessment.deterministic.typeCheck.passed && (
                        <span className="text-green-400">通过</span>
                      )}
                    </div>

                    {/* ESLint Check */}
                    <div className="flex items-center gap-1.5 text-xs">
                      <VerificationIcon
                        status={
                          assessment.deterministic.lint.errorCount > 0
                            ? 'has_error'
                            : assessment.deterministic.lint.warningCount > 0
                              ? 'has_warning'
                              : 'all_pass'
                        }
                        size={14}
                      />
                      <span className="text-gray-300">eslint</span>
                      {assessment.deterministic.lint.errorCount > 0 && (
                        <span className="text-red-400">{assessment.deterministic.lint.errorCount} 错误</span>
                      )}
                      {assessment.deterministic.lint.warningCount > 0 && assessment.deterministic.lint.errorCount === 0 && (
                        <span className="text-yellow-400">{assessment.deterministic.lint.warningCount} 警告</span>
                      )}
                      {assessment.deterministic.lint.passed && assessment.deterministic.lint.warningCount === 0 && (
                        <span className="text-green-400">通过</span>
                      )}
                    </div>

                    {/* Test Check */}
                    <div className="flex items-center gap-1.5 text-xs">
                      <VerificationIcon
                        status={
                          assessment.deterministic.tests.failedCount > 0
                            ? 'has_error'
                            : assessment.deterministic.tests.passedCount > 0
                              ? 'all_pass'
                              : 'skipped'
                        }
                        size={14}
                      />
                      <span className="text-gray-300">test</span>
                      <span className={assessment.deterministic.tests.failedCount > 0 ? 'text-red-400' : 'text-green-400'}>
                        {assessment.deterministic.tests.passedCount}/{assessment.deterministic.tests.passedCount + assessment.deterministic.tests.failedCount}
                      </span>
                    </div>
                  </div>
                </div>
              )}

              {/* Heuristic Analysis */}
              {assessment && (
                <div className="space-y-1.5">
                  <h4 className="text-xs font-semibold text-gray-400 uppercase tracking-wide">
                    启发式分析
                  </h4>
                  <div className="flex gap-4 text-xs text-gray-400">
                    <span>影响 API: <strong className="text-gray-200">{assessment.heuristic.affectedApiCount}</strong></span>
                    <span>间接文件: <strong className="text-gray-200">{assessment.heuristic.indirectImpactCount}</strong></span>
                    <span>置信度: <strong className={assessment.heuristic.hasHighConfidenceImpact ? 'text-yellow-300' : 'text-gray-200'}>
                      {assessment.heuristic.hasHighConfidenceImpact ? '高' : '低'}
                    </strong></span>
                  </div>
                </div>
              )}

              {/* Affected Files */}
              <div className="space-y-1.5">
                <h4 className="text-xs font-semibold text-gray-400 uppercase tracking-wide">
                  受影响文件
                </h4>
                <div className="space-y-1">
                  {activity.changedFiles.slice(0, 5).map((file) => (
                    <div key={file.filePath} className="flex items-center gap-2 text-xs">
                      <span className="text-gray-300 truncate flex-1 font-mono">
                        {file.filePath}
                      </span>
                      <span className={`px-1.5 py-0.5 rounded text-[10px] font-medium ${IMPACT_BADGE_COLORS[file.changeType === 'added' ? 'direct' : file.changeType === 'modified' ? 'direct' : 'potential'] ?? IMPACT_BADGE_COLORS.direct}`}>
                        {file.changeType ?? 'modified'}
                      </span>
                    </div>
                  ))}
                  {activity.changedFiles.length > 5 && (
                    <p className="text-xs text-gray-500">
                      +{activity.changedFiles.length - 5} 个文件...
                    </p>
                  )}
                </div>
              </div>
            </div>

            {/* Footer Actions */}
            <div className="flex items-center gap-2 px-4 py-3 border-t border-gray-700/50 pb-[env(safe-area-inset-bottom)]">
              {activity.decision ? (
                <span className={`inline-flex items-center gap-1 px-4 py-2 text-xs font-medium rounded-lg ${
                  activity.decision === 'approved'
                    ? 'bg-green-600/10 text-green-400/70'
                    : 'bg-red-600/10 text-red-400/70'
                }`}>
                  {activity.decision === 'approved' ? (
                    <><Check size={14} /> 已批准 ✓</>
                  ) : (
                    <><X size={14} /> 已拒绝 ✗</>
                  )}
                </span>
              ) : activity.insight?.signal === 'auto_approve' ? (
                <span className="inline-flex items-center gap-1 px-4 py-2 text-xs font-medium rounded-lg bg-gray-600/10 text-gray-400">
                  <Check size={14} /> 已自动放行
                </span>
              ) : (
                <>
                  <button
                    onClick={() => onApprove?.(activity.id)}
                    className="inline-flex items-center gap-1 px-4 py-2 text-xs font-medium rounded-lg bg-green-600/20 text-green-300 hover:bg-green-600/30 active:bg-green-600/40 transition-colors"
                  >
                    <Check size={14} /> 批准
                  </button>
                  <button
                    onClick={() => onReject?.(activity.id)}
                    className="inline-flex items-center gap-1 px-4 py-2 text-xs font-medium rounded-lg bg-red-600/20 text-red-300 hover:bg-red-600/30 active:bg-red-600/40 transition-colors"
                  >
                    <X size={14} /> 拒绝
                  </button>
                </>
              )}
              <button
                onClick={() => onViewDetails?.(activity.id)}
                className="inline-flex items-center gap-1 px-4 py-2 text-xs font-medium rounded-lg bg-gray-600/20 text-gray-300 hover:bg-gray-600/30 active:bg-gray-600/40 transition-colors ml-auto"
              >
                详情 <ArrowRight size={14} />
              </button>
            </div>
          </motion.div>
        </div>
      )}
    </AnimatePresence>,
    document.body
  );
}
