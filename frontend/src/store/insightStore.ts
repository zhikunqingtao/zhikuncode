import { create } from 'zustand';
import { immer } from 'zustand/middleware/immer';
import { subscribeWithSelector } from 'zustand/middleware';
import type { RiskAssessment, SelfReviewResult, Signal, InsightSummary, BatchApprovalStrategy, AISuggestion, VerifyCheckResponse } from '@/types/apos';
import { notificationService } from '@/services/NotificationService';

interface InsightStoreState {
  assessments: Map<string, RiskAssessment>;
  selfReviews: Map<string, SelfReviewResult>;
  sessionSummary: InsightSummary;
  autoApproveEnabled: boolean;
  approvalStrategy: BatchApprovalStrategy;
  globalSuggestions: AISuggestion[];

  // Phase 2: 验证进度追踪
  isVerifying: boolean;
  verifyProgress: Map<string, 'running' | 'completed' | 'failed'>;
  lastVerifyResult: VerifyCheckResponse | null;
  verifySignal: Signal | null;
  verifySignalReason: string;

  addAssessment: (operationId: string, assessment: RiskAssessment) => void;
  updateAssessment: (operationId: string, partial: Partial<RiskAssessment>) => void;
  addSelfReview: (operationId: string, review: SelfReviewResult) => void;
  computeSessionSummary: () => void;
  addGlobalSuggestion: (suggestion: AISuggestion) => void;
  dismissSuggestion: (index: number) => void;
  setAutoApproveEnabled: (enabled: boolean) => void;
  updateApprovalStrategy: (strategy: Partial<BatchApprovalStrategy>) => void;

  // Phase 2: 验证进度 actions
  startVerification: (filePaths: string[]) => void;
  handleVerifyProgress: (data: { filePath: string; status: string }) => void;
  handleVerificationResult: (data: VerifyCheckResponse) => void;
  resetVerification: () => void;

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

    // Phase 2: 验证进度初始状态
    isVerifying: false,
    verifyProgress: new Map(),
    lastVerifyResult: null,
    verifySignal: null,
    verifySignalReason: '',

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

    // Phase 2: 验证进度 actions
    startVerification: (filePaths) => set(d => {
      d.isVerifying = true;
      d.verifyProgress = new Map(filePaths.map(f => [f, 'running']));
      d.lastVerifyResult = null;
      d.verifySignal = null;
      d.verifySignalReason = '';
    }),
    handleVerifyProgress: (data) => set(d => {
      d.verifyProgress.set(data.filePath, data.status as 'running' | 'completed' | 'failed');
    }),
    handleVerificationResult: (data) => {
      set(d => {
        d.isVerifying = false;
        d.lastVerifyResult = data;
        d.verifySignal = data.signal;
        d.verifySignalReason = data.signalReason;
      });

      // 推送通知：blocked/manual_required 信号触发
      if (data.signal === 'blocked' || data.signal === 'manual_required') {
        notificationService.send(
          `[验证] ${data.signal === 'blocked' ? '阻塞' : '需人工审查'}`,
          { body: data.signalReason, tag: 'verify-signal' }
        );
      }
    },
    resetVerification: () => set(d => {
      d.isVerifying = false;
      d.verifyProgress = new Map();
      d.lastVerifyResult = null;
      d.verifySignal = null;
      d.verifySignalReason = '';
    }),

    clearAll: () => set(d => {
      d.assessments.clear();
      d.selfReviews.clear();
      d.globalSuggestions = [];
      d.sessionSummary = { totalOperations: 0, signalCounts: { auto_approve: 0, review_recommended: 0, manual_required: 0, blocked: 0 }, lastUpdated: Date.now() };
      // Phase 2: reset verification state
      d.isVerifying = false;
      d.verifyProgress = new Map();
      d.lastVerifyResult = null;
      d.verifySignal = null;
      d.verifySignalReason = '';
    }),
  })))
);
