import { motion, AnimatePresence } from 'framer-motion';
import { Check, X, ArrowRight, Loader2 } from 'lucide-react';
import type { ActivityData, RiskAssessment } from '@/types/apos';
import { computeButtonDisabled } from '@/types/apos';
import { VerificationIcon } from './VerificationIcon';

interface ActivityCardL2Props {
  activity: ActivityData;
  assessment?: RiskAssessment;
  isVisible: boolean;
  onApprove: () => void;
  onReject: () => void;
  onViewDetails: () => void;
}

const IMPACT_BADGE_COLORS: Record<string, string> = {
  direct: 'bg-red-500/20 text-red-300',
  indirect: 'bg-yellow-500/20 text-yellow-300',
  potential: 'bg-blue-500/20 text-blue-300',
};

export function ActivityCardL2({
  activity,
  assessment,
  isVisible,
  onApprove,
  onReject,
  onViewDetails,
}: ActivityCardL2Props) {
  return (
    <AnimatePresence>
      {isVisible && (
        <motion.div
          initial={{ height: 0, opacity: 0 }}
          animate={{ height: 'auto', opacity: 1 }}
          exit={{ height: 0, opacity: 0 }}
          transition={{ duration: 0.3, ease: 'easeOut' }}
          className="overflow-hidden border-b border-[var(--border)]"
        >
          <div className="px-4 py-3 bg-[var(--bg-secondary)] space-y-3">
            {/* Loading state when verification in progress */}
            {!assessment && activity.insight?.verificationStatus === 'pending' && (
              <div className="space-y-2">
                <h4 className="text-xs font-semibold text-[var(--text-muted)] uppercase tracking-wide">
                  确定性验证
                </h4>
                <div className="flex items-center gap-2 text-xs text-[var(--text-secondary)]">
                  <Loader2 size={14} className="animate-spin text-blue-400" />
                  <span>验证进行中...</span>
                </div>
              </div>
            )}

            {/* Deterministic Verification Results */}
            {assessment && (
              <div className="space-y-2">
                <h4 className="text-xs font-semibold text-[var(--text-muted)] uppercase tracking-wide">
                  确定性验证
                </h4>
                <div className="grid grid-cols-3 gap-2">
                  {/* TypeScript Check */}
                  <div className="flex items-center gap-1.5 text-xs">
                    <VerificationIcon
                      status={assessment.deterministic.typeCheck.passed ? 'all_pass' : 'has_error'}
                      size={14}
                    />
                    <span className="text-[var(--text-primary)]">tsc</span>
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
                    <span className="text-[var(--text-primary)]">eslint</span>
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
                    <span className="text-[var(--text-primary)]">test</span>
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
                <h4 className="text-xs font-semibold text-[var(--text-muted)] uppercase tracking-wide">
                  启发式分析
                </h4>
                <div className="flex gap-4 text-xs text-[var(--text-secondary)]">
                  <span>影响 API: <strong className="text-[var(--text-primary)]">{assessment.heuristic.affectedApiCount}</strong></span>
                  <span>间接文件: <strong className="text-[var(--text-primary)]">{assessment.heuristic.indirectImpactCount}</strong></span>
                  <span>置信度: <strong className={assessment.heuristic.hasHighConfidenceImpact ? 'text-yellow-500 dark:text-yellow-300' : 'text-[var(--text-primary)]'}>
                    {assessment.heuristic.hasHighConfidenceImpact ? '高' : '低'}
                  </strong></span>
                </div>
              </div>
            )}

            {/* Affected Files (first 3) */}
            <div className="space-y-1.5">
              <h4 className="text-xs font-semibold text-[var(--text-muted)] uppercase tracking-wide">
                受影响文件
              </h4>
              <div className="space-y-1">
                {activity.changedFiles.slice(0, 3).map((file) => (
                  <div key={file.filePath} className="flex items-center gap-2 text-xs">
                    <span className="text-[var(--text-primary)] truncate flex-1 font-mono">
                      {file.filePath}
                    </span>
                    <span className={`px-1.5 py-0.5 rounded text-[10px] font-medium ${IMPACT_BADGE_COLORS[file.changeType === 'added' ? 'direct' : file.changeType === 'modified' ? 'direct' : 'potential'] ?? IMPACT_BADGE_COLORS.direct}`}>
                      {file.changeType ?? 'modified'}
                    </span>
                  </div>
                ))}
                {activity.changedFiles.length > 3 && (
                  <p className="text-xs text-[var(--text-muted)]">
                    +{activity.changedFiles.length - 3} 个文件...
                  </p>
                )}
              </div>
            </div>

            {/* Action Buttons */}
            <div className="flex items-center gap-2 pt-1">
              {activity.decision ? (
                <span className={`inline-flex items-center gap-1 px-3 py-1.5 text-xs font-medium rounded ${
                  activity.decision === 'approved'
                    ? 'bg-green-600/10 text-green-400/70'
                    : 'bg-red-600/10 text-red-400/70'
                }`}>
                  {activity.decision === 'approved' ? (
                    <><Check size={12} /> 已批准 ✓</>
                  ) : (
                    <><X size={12} /> 已拒绝 ✗</>
                  )}
                </span>
              ) : activity.insight?.signal === 'auto_approve' ? (
                <span className="inline-flex items-center gap-1 px-3 py-1.5 text-xs font-medium rounded bg-gray-600/10 text-gray-400">
                  <Check size={12} /> 已自动放行
                </span>
              ) : (
                <>
                  {(() => {
                    // 统一三重禁用判定（与 L3 保持一致）
                    const isDisabled = computeButtonDisabled(activity);
                    const disabledClass = isDisabled
                      ? 'opacity-40 cursor-not-allowed pointer-events-none'
                      : '';
                    return (
                      <>
                        <button
                          onClick={(e) => { e.stopPropagation(); onApprove(); }}
                          disabled={isDisabled}
                          className={`inline-flex items-center gap-1 px-3 py-1.5 text-xs font-medium rounded bg-green-600/20 text-green-300 hover:bg-green-600/30 transition-colors ${disabledClass}`}
                          title={isDisabled ? '等待文件变更数据或验证完成' : '批准此操作'}
                        >
                          <Check size={12} /> 批准
                        </button>
                        <button
                          onClick={(e) => { e.stopPropagation(); onReject(); }}
                          disabled={isDisabled}
                          className={`inline-flex items-center gap-1 px-3 py-1.5 text-xs font-medium rounded bg-red-600/20 text-red-300 hover:bg-red-600/30 transition-colors ${disabledClass}`}
                          title={isDisabled ? '等待文件变更数据或验证完成' : '拒绝此操作'}
                        >
                          <X size={12} /> 拒绝
                        </button>
                      </>
                    );
                  })()}
                </>
              )}
              <button
                onClick={(e) => { e.stopPropagation(); onViewDetails(); }}
                className="inline-flex items-center gap-1 px-3 py-1.5 text-xs font-medium rounded bg-gray-600/20 text-[var(--text-secondary)] hover:bg-gray-600/30 transition-colors ml-auto"
              >
                详情 <ArrowRight size={12} />
              </button>
            </div>
          </div>
        </motion.div>
      )}
    </AnimatePresence>
  );
}
