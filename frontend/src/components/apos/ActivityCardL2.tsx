import { useActivityStore } from '@/store/activityStore';
import { ActivityDecisionStatus } from './ActivityDecisionStatus';
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
  direct: 'bg-errsoft text-err',
  indirect: 'bg-warnsoft text-warnstrong dark:text-warn',
  potential: 'bg-accent2-soft text-accent2-ink dark:text-accent2-ink',
};

export function ActivityCardL2({
  activity,
  assessment,
  isVisible,
  onApprove,
  onReject,
  onViewDetails,
}: ActivityCardL2Props) {
  const decisionPending = useActivityStore(s => s.decisionRequests.get(activity.id)?.pending ?? false);
  return (
    <div className="expand-collapse" data-open={isVisible} {...(!isVisible ? { inert: '' } : {})}>
      <div className="expand-collapse-inner">
        <div className={`px-4 py-3 bg-[var(--v2-bg-sunken)] space-y-3 ${isVisible ? 'border-b border-[var(--v2-border-hairline)]' : ''}`}>
            {/* Loading state when verification in progress */}
            {!assessment && activity.insight?.verificationStatus === 'pending' && (
              <div className="space-y-2">
                <h4 className="text-[13px] font-semibold text-[var(--v2-text-2)] uppercase tracking-wide">
                  确定性验证
                </h4>
                <div className="flex items-center gap-2 text-[13px] text-[var(--v2-text-2)]">
                  <Loader2 size={14} className="animate-spin text-accent2-ink dark:text-accent2-ink" />
                  <span>验证进行中...</span>
                </div>
              </div>
            )}

            {/* Deterministic Verification Results */}
            {assessment && (
              <div className="space-y-2">
                <h4 className="text-[13px] font-semibold text-[var(--v2-text-2)] uppercase tracking-wide">
                  确定性验证
                </h4>
                <div className="grid grid-cols-3 gap-2">
                  {/* TypeScript Check */}
                  <div className="flex items-center gap-1.5 text-[13px]">
                    <VerificationIcon
                      status={assessment.deterministic.typeCheck.passed ? 'all_pass' : 'has_error'}
                      size={14}
                    />
                    <span className="text-[var(--v2-text-1)]">tsc</span>
                    {assessment.deterministic.typeCheck.errorCount > 0 && (
                      <span className="text-err">
                        {assessment.deterministic.typeCheck.errorCount} 错误
                      </span>
                    )}
                    {assessment.deterministic.typeCheck.passed && (
                      <span className="text-ok">通过</span>
                    )}
                  </div>

                  {/* ESLint Check */}
                  <div className="flex items-center gap-1.5 text-[13px]">
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
                    <span className="text-[var(--v2-text-1)]">eslint</span>
                    {assessment.deterministic.lint.errorCount > 0 && (
                      <span className="text-err">{assessment.deterministic.lint.errorCount} 错误</span>
                    )}
                    {assessment.deterministic.lint.warningCount > 0 && assessment.deterministic.lint.errorCount === 0 && (
                      <span className="text-warnstrong dark:text-warn">{assessment.deterministic.lint.warningCount} 警告</span>
                    )}
                    {assessment.deterministic.lint.passed && assessment.deterministic.lint.warningCount === 0 && (
                      <span className="text-ok">通过</span>
                    )}
                  </div>

                  {/* Test Check */}
                  <div className="flex items-center gap-1.5 text-[13px]">
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
                    <span className="text-[var(--v2-text-1)]">test</span>
                    <span className={assessment.deterministic.tests.failedCount > 0 ? 'text-err' : 'text-ok'}>
                      {assessment.deterministic.tests.passedCount}/{assessment.deterministic.tests.passedCount + assessment.deterministic.tests.failedCount}
                    </span>
                  </div>
                </div>
              </div>
            )}

            {/* Heuristic Analysis */}
            {assessment && (
              <div className="space-y-1.5">
                <h4 className="text-[13px] font-semibold text-[var(--v2-text-2)] uppercase tracking-wide">
                  启发式分析
                </h4>
                <div className="flex gap-4 text-[13px] text-[var(--v2-text-2)]">
                  <span>影响 API: <strong className="text-[var(--v2-text-1)]">{assessment.heuristic.affectedApiCount}</strong></span>
                  <span>间接文件: <strong className="text-[var(--v2-text-1)]">{assessment.heuristic.indirectImpactCount}</strong></span>
                  <span>置信度: <strong className={assessment.heuristic.hasHighConfidenceImpact ? 'text-warnstrong dark:text-warn' : 'text-[var(--v2-text-1)]'}>
                    {assessment.heuristic.hasHighConfidenceImpact ? '高' : '低'}
                  </strong></span>
                </div>
              </div>
            )}

            {/* Affected Files (first 3) */}
            <div className="space-y-1.5">
              <h4 className="text-[13px] font-semibold text-[var(--v2-text-2)] uppercase tracking-wide">
                受影响文件
              </h4>
              <div className="space-y-1">
                {activity.changedFiles.slice(0, 3).map((file) => (
                  <div key={file.filePath} className="flex items-center gap-2 text-[13px]">
                    <span className="text-[var(--v2-text-1)] truncate flex-1 font-mono">
                      {file.filePath}
                    </span>
                    <span className={`px-1.5 py-0.5 rounded text-[13px] font-medium ${IMPACT_BADGE_COLORS[file.changeType === 'added' ? 'direct' : file.changeType === 'modified' ? 'direct' : 'potential'] ?? IMPACT_BADGE_COLORS.direct}`}>
                      {file.changeType ?? 'modified'}
                    </span>
                  </div>
                ))}
                {activity.changedFiles.length > 3 && (
                  <p className="text-[13px] text-[var(--v2-text-2)]">
                    +{activity.changedFiles.length - 3} 个文件...
                  </p>
                )}
              </div>
            </div>

            {/* Action Buttons */}
            <div className="flex items-center gap-2 pt-1">
              <ActivityDecisionStatus id={activity.id} />
          {activity.decision ? (
                <span className={`inline-flex items-center gap-1 px-3 py-1.5 text-[13px] font-medium rounded ${
                  activity.decision === 'approved'
                    ? 'bg-[color:color-mix(in_srgb,var(--v2-accent-strong)_10%,transparent)] text-ok'
                    : 'bg-errsoft text-err'
                }`}>
                  {activity.decision === 'approved' ? (
                    <><Check size={12} /> 已批准 ✓</>
                  ) : (
                    <><X size={12} /> 已拒绝 ✗</>
                  )}
                </span>
              ) : activity.insight?.signal === 'auto_approve' ? (
                <span className="inline-flex items-center gap-1 px-3 py-1.5 text-[13px] font-medium rounded bg-sunken2 text-t2">
                  <Check size={12} /> 已自动放行
                </span>
              ) : (
                <>
                  {(() => {
                    // 统一三重禁用判定（与 L3 保持一致）
                    const isDisabled = decisionPending || computeButtonDisabled(activity);
                    const disabledClass = isDisabled
                      ? 'opacity-40 cursor-not-allowed pointer-events-none'
                      : '';
                    return (
                      <>
                        <button
                          onClick={(e) => { e.stopPropagation(); onApprove(); }}
                          disabled={isDisabled}
                          className={`panel-control inline-flex items-center gap-1 px-3 py-1.5 text-[13px] font-medium rounded bg-oksoft text-ok hover:bg-hover2 transition-colors ${disabledClass}`}
                          title={isDisabled ? '等待文件变更数据或验证完成' : '批准此操作'}
                        >
                          <Check size={12} /> 批准
                        </button>
                        <button
                          onClick={(e) => { e.stopPropagation(); onReject(); }}
                          disabled={isDisabled}
                          className={`panel-control inline-flex items-center gap-1 px-3 py-1.5 text-[13px] font-medium rounded bg-errsoft text-err hover:bg-hover2 transition-colors ${disabledClass}`}
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
                className="panel-control inline-flex items-center gap-1 px-3 py-1.5 text-[13px] font-medium rounded bg-sunken2 text-[var(--v2-text-2)] hover:bg-hover2 transition-colors ml-auto"
              >
                详情 <ArrowRight size={12} />
              </button>
            </div>
          </div>
        </div>
      </div>
  );
}
