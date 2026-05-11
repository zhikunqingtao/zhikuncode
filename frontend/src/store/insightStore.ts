import { create } from 'zustand';
import { immer } from 'zustand/middleware/immer';
import { subscribeWithSelector } from 'zustand/middleware';
import type { RiskAssessment, SelfReviewResult, Signal, InsightSummary, BatchApprovalStrategy, AISuggestion } from '@/types/apos';

interface InsightStoreState {
  assessments: Map<string, RiskAssessment>;
  selfReviews: Map<string, SelfReviewResult>;
  sessionSummary: InsightSummary;
  autoApproveEnabled: boolean;
  approvalStrategy: BatchApprovalStrategy;
  globalSuggestions: AISuggestion[];

  addAssessment: (operationId: string, assessment: RiskAssessment) => void;
  updateAssessment: (operationId: string, partial: Partial<RiskAssessment>) => void;
  addSelfReview: (operationId: string, review: SelfReviewResult) => void;
  computeSessionSummary: () => void;
  addGlobalSuggestion: (suggestion: AISuggestion) => void;
  dismissSuggestion: (index: number) => void;
  setAutoApproveEnabled: (enabled: boolean) => void;
  updateApprovalStrategy: (strategy: Partial<BatchApprovalStrategy>) => void;
  clearAll: () => void;
}

/** computeSignal() — 纯规则引擎，零 LLM 依赖 */
export function computeSignal(assessment: Pick<RiskAssessment, 'deterministic' | 'heuristic'>): { signal: Signal; reason: string } {
  const { deterministic, heuristic } = assessment;

  // 黑名单：任何确定性检查失败 → blocked
  if (deterministic.typeCheck.errorCount > 0) return { signal: 'blocked', reason: `TypeScript ${deterministic.typeCheck.errorCount} 个类型错误` };
  if (deterministic.lint.errorCount > 0) return { signal: 'blocked', reason: `ESLint ${deterministic.lint.errorCount} 个错误` };
  if (deterministic.tests.failedCount > 0) return { signal: 'blocked', reason: `${deterministic.tests.failedCount} 个测试失败` };
  if (heuristic.truncated) return { signal: 'blocked', reason: '影响分析超时截断，无法确认安全' };

  // 黄灯：存在风险信号但未失败
  if (heuristic.affectedApiCount > 0) return { signal: 'review_recommended', reason: `影响 ${heuristic.affectedApiCount} 个公开 API` };
  if (heuristic.indirectImpactCount > 3) return { signal: 'review_recommended', reason: `间接影响 ${heuristic.indirectImpactCount} 个文件` };
  if (deterministic.tests.passedCount === 0) return { signal: 'review_recommended', reason: '无匹配的测试文件' };
  if (deterministic.lint.warningCount > 0) return { signal: 'review_recommended', reason: `ESLint ${deterministic.lint.warningCount} 个警告` };

  // 绿灯：全部通过 + 影响范围小
  if (deterministic.typeCheck.passed && deterministic.lint.passed && deterministic.tests.passedCount > 0 &&
      heuristic.indirectImpactCount <= 2 && heuristic.affectedApiCount === 0) {
    return { signal: 'auto_approve', reason: '全部验证通过，影响范围可控' };
  }

  return { signal: 'manual_required', reason: '需要人工判断' };
}

export const useInsightStore = create<InsightStoreState>()(
  subscribeWithSelector(immer((set) => ({
    assessments: new Map(),
    selfReviews: new Map(),
    sessionSummary: { totalOperations: 0, signalCounts: { auto_approve: 0, review_recommended: 0, manual_required: 0, blocked: 0 }, lastUpdated: Date.now() },
    autoApproveEnabled: true,
    approvalStrategy: { autoApproveOnGreen: false, requireConfirmOnYellow: true, blockOnRed: true },
    globalSuggestions: [],

    addAssessment: (operationId, assessment) => set(d => {
      d.assessments.set(operationId, assessment);
      // Auto-update session summary
      const counts = { auto_approve: 0, review_recommended: 0, manual_required: 0, blocked: 0 };
      d.assessments.forEach(a => { counts[a.signal]++; });
      d.sessionSummary = { totalOperations: d.assessments.size, signalCounts: counts, lastUpdated: Date.now() };
    }),
    updateAssessment: (operationId, partial) => set(d => {
      const existing = d.assessments.get(operationId);
      if (existing) Object.assign(existing, partial);
    }),
    addSelfReview: (operationId, review) => set(d => { d.selfReviews.set(operationId, review); }),
    computeSessionSummary: () => set(d => {
      const counts = { auto_approve: 0, review_recommended: 0, manual_required: 0, blocked: 0 };
      d.assessments.forEach(a => { counts[a.signal]++; });
      d.sessionSummary = { totalOperations: d.assessments.size, signalCounts: counts, lastUpdated: Date.now() };
    }),
    addGlobalSuggestion: (suggestion) => set(d => { d.globalSuggestions.push(suggestion); }),
    dismissSuggestion: (index) => set(d => { d.globalSuggestions.splice(index, 1); }),
    setAutoApproveEnabled: (enabled) => set(d => { d.autoApproveEnabled = enabled; }),
    updateApprovalStrategy: (strategy) => set(d => { Object.assign(d.approvalStrategy, strategy); }),
    clearAll: () => set(d => {
      d.assessments.clear();
      d.selfReviews.clear();
      d.globalSuggestions = [];
      d.sessionSummary = { totalOperations: 0, signalCounts: { auto_approve: 0, review_recommended: 0, manual_required: 0, blocked: 0 }, lastUpdated: Date.now() };
    }),
  })))
);
