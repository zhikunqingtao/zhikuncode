/**
 * APOS 类型定义
 * Phase 1: Activity, Insight, FeatureFlags, Verification
 * Phase 2: ChangeImpactPanel、异常检测、Pipeline、协作图、推送通知
 */

// ══════════════════════════════════════════════════════════════
// Phase 1: 核心类型
// ══════════════════════════════════════════════════════════════

// === 操作类型 ===

export type OperationType =
  | 'file_edit'
  | 'file_create'
  | 'command_execute'
  | 'test_run'
  | 'git_commit'
  | 'refactor'
  | 'dependency'
  | 'config_change'
  | 'delete'
  | 'unknown';

export type RiskLevel = 'safe' | 'review' | 'warning' | 'danger';

export type VerificationStatus = 'all_pass' | 'has_error' | 'has_warning' | 'pending' | 'skipped' | 'failed';

// === 文件变更 ===

export interface FileChange {
  filePath: string;
  additions: number;
  deletions: number;
  changeType: 'added' | 'modified' | 'deleted';
  diffContent?: string;
}

// === Activity ===

export interface ActivityData {
  id: string;
  sessionId?: string;
  operationType: OperationType;
  summary: string;
  toolName?: string;
  changedFiles: FileChange[];
  fileCount?: number;
  duration?: number;
  toolResult?: {
    content: string;
    isError: boolean;
    metadata?: Record<string, unknown>;
  };
  originalContent?: string;
  modifiedContent?: string;
  insight?: {
    signal: Signal;
    riskLevel: RiskLevel;
    summary: string;
    factors: string[];
    suggestions: string[];
    verificationStatus: VerificationStatus | string;
  };
  decision?: 'approved' | 'rejected';
  status: string;
  timestamp: number;
}

export interface ActivityFilter {
  signal?: Signal[];
  operationType?: OperationType;
  search?: string;
}

// === Risk Assessment (Insight) ===

export interface RiskAssessment {
  deterministic: {
    typeCheck: { passed: boolean; errorCount: number; details?: string };
    lint: { passed: boolean; errorCount: number; warningCount: number };
    tests: { passed: boolean; passedCount: number; failedCount: number; coveragePercent?: number };
  };
  heuristic: {
    affectedApiCount: number;
    indirectImpactCount: number;
    potentialImpactCount: number;
    hasHighConfidenceImpact: boolean;
    truncated: boolean;
    filesAffected: string[];
  };
  signal: Signal;
  reason: string;
}

export interface SelfReviewResult {
  operationId: string;
  passed: boolean;
  issues: string[];
  suggestions: string[];
}

export interface InsightSummary {
  totalOperations: number;
  signalCounts: Record<Signal, number>;
  lastUpdated: number;
}

export interface BatchApprovalStrategy {
  autoApproveOnGreen: boolean;
  requireConfirmOnYellow: boolean;
  blockOnRed: boolean;
}

export interface AISuggestion {
  id: string;
  message: string;
  type: 'optimization' | 'warning' | 'best_practice';
  operationId?: string;
}

// === Verification API ===

export interface RunChecksRequest {
  sessionId: string;
  operationId: string;
  checks: string[];
  filePaths: string[];
  timeout: number;
}

export interface RunChecksResponse {
  status: 'all_pass' | 'has_warning' | 'has_error';
  results: Array<{
    check: string;
    passed: boolean;
    errors?: Array<{ file: string; line: number; message: string }>;
    warnings?: Array<{ file: string; line?: number; message?: string }>;
  }>;
}

// === Feature Flags ===

export interface APOSFeatureFlags {
  APOS_ACTIVITY_STREAM: boolean;
  APOS_AI_INSIGHT: boolean;
  APOS_BATCH_REVIEW: boolean;
  APOS_RISK_HEATMAP: boolean;
  APOS_CHANGE_IMPACT: boolean;
  APOS_AGENT_PIPELINE: boolean;
  APOS_ANOMALY_ALERT: boolean;
  APOS_MOBILE_STATUS: boolean;
}

export const APOS_FLAG_DEFAULTS: APOSFeatureFlags = {
  APOS_ACTIVITY_STREAM: true,
  APOS_AI_INSIGHT: true,
  APOS_BATCH_REVIEW: true,
  APOS_RISK_HEATMAP: false,
  APOS_CHANGE_IMPACT: true,
  APOS_AGENT_PIPELINE: true,
  APOS_ANOMALY_ALERT: true,
  APOS_MOBILE_STATUS: true,
};

export const APOS_FLAG_DEPENDENCIES: Record<string, string[]> = {
  APOS_ACTIVITY_STREAM: [],
  APOS_AI_INSIGHT: ['APOS_ACTIVITY_STREAM'],
  APOS_BATCH_REVIEW: ['APOS_ACTIVITY_STREAM'],
  APOS_RISK_HEATMAP: ['APOS_AI_INSIGHT'],
  APOS_CHANGE_IMPACT: ['APOS_ACTIVITY_STREAM'],
  APOS_AGENT_PIPELINE: ['APOS_ACTIVITY_STREAM'],
  APOS_ANOMALY_ALERT: ['APOS_AGENT_PIPELINE'],
  APOS_MOBILE_STATUS: ['APOS_ACTIVITY_STREAM'],
};

// === Signal Config ===

export const SIGNAL_CONFIG: Record<Signal, { color: string; label: string }> = {
  auto_approve: { color: 'bg-green-500', label: '自动通过' },
  review_recommended: { color: 'bg-yellow-500', label: '建议审查' },
  manual_required: { color: 'bg-orange-500', label: '需人工' },
  blocked: { color: 'bg-red-500', label: '阻塞' },
};

// === Retention Config ===

export interface RetentionConfig {
  maxCount: number;
  protectedSignals: Signal[];
  autoArchiveAfterMs: number;
}

export const DEFAULT_RETENTION_CONFIG: RetentionConfig = {
  maxCount: 100,
  protectedSignals: ['blocked', 'manual_required'],
  autoArchiveAfterMs: 24 * 60 * 60 * 1000, // 24h
};

// === Needs Verification Ops ===

export const NEEDS_VERIFICATION_OPS: OperationType[] = [
  'file_edit', 'file_create', 'command_execute', 'git_commit',
  'refactor', 'dependency', 'config_change', 'delete',
];

// === Insight Service Interface ===

export interface InsightServiceInterface {
  analyze(activity: ActivityData): Promise<RiskAssessment>;
  selfReview(activity: ActivityData): Promise<SelfReviewResult>;
}

// === Utility Functions ===

/**
 * 三重禁用判定：按钮是否应该被禁用
 * 1. 无 changedFiles 数据
 * 2. 验证状态为 pending
 * 3. 已有 decision
 * @param _hasResult - 可选，是否已有 toolResult（L3 场景使用）
 */
export function computeButtonDisabled(activity: ActivityData, _hasResult?: boolean): boolean {
  if (activity.decision !== undefined) return true;
  if (activity.changedFiles.length === 0 && activity.insight?.verificationStatus === 'pending') return true;
  if (activity.insight?.verificationStatus === 'pending') return true;
  return false;
}

// ══════════════════════════════════════════════════════════════
// Phase 2: 扩展类型
// ══════════════════════════════════════════════════════════════

// === 2.1 ChangeImpactPanel ===

export interface AggregatedFileChange {
    filePath: string;
    changeType: 'added' | 'modified' | 'deleted';
    totalAdditions: number;
    totalDeletions: number;
    riskLevel: 'safe' | 'review' | 'warning' | 'danger';
    riskReason?: string;
    testCoverageGap: boolean;
    touchCount: number;
    indirectImpacts: IndirectImpact[];
}

export interface IndirectImpact {
    filePath: string;
    reason: string;
    severity: 'low' | 'medium' | 'high';
}

export interface RiskSummary {
    totalFiles: number;
    highRiskCount: number;
    testCoverageGapCount: number;
    indirectImpactCount: number;
}

// === 2.6 异常检测 ===

export interface ToolCallRecord {
    toolName: string;
    paramsHash: string;
    status: 'success' | 'error' | 'timeout';
    timestamp: number;
    errorDetail?: string;
    durationMs?: number;
}

export interface AnomalyEvent {
    id: string;
    swarmId: string;
    workerId: string;
    workerName: string;
    ruleId: 'loop_detection' | 'stall_detection' | 'error_cascade';
    severity: 'error' | 'critical';
    message: string;
    detectedAt: number;
    resolvedAt: number | null;
    resolution: 'abort' | 'replan' | 'dismiss' | 'auto_resolved' | null;
}

// === 2.4 协作关系图 ===

export interface MailboxWriteEvent {
    from: string;
    to: string;
    messageSize: number;
    contentType: 'task_spec' | 'code_diff' | 'review_result' | 'test_report';
    timestamp: number;
}

export interface CollaborationEdge {
    id: string;
    source: string;
    target: string;
    type: 'explicit_dependency' | 'mailbox_communication' | 'time_inferred';
    dataSize?: number;
    contentType?: string;
    lastActivityTime: number;
    messageCount: number;
}

// === 后端验证 API ===

export type Signal = 'auto_approve' | 'review_recommended' | 'manual_required' | 'blocked';

export interface HeuristicAnalysis {
    affectedApiCount: number;
    indirectImpactCount: number;
    potentialImpactCount: number;
    hasHighConfidenceImpact: boolean;
    truncated: boolean;
    filesAffected: string[];
}

export interface CheckDetail {
    status: 'pass' | 'fail' | 'skipped';
    errorCount: number;
    warningCount: number;
    issues: CheckIssue[];
}

export interface CheckIssue {
    line: number;
    column: number;
    rule: string;
    severity: string;
    message: string;
    code?: string;
}

export interface TestCheckDetail {
    status: 'pass' | 'fail' | 'skipped' | 'no_tests';
    passedCount: number;
    failedCount: number;
    coveragePercent?: number;
    failures: TestFailure[];
}

export interface TestFailure {
    testName: string;
    message: string;
}

export interface FileCheckResult {
    filePath: string;
    typescript: CheckDetail;
    eslint: CheckDetail;
    vitest: TestCheckDetail;
}

export interface VerifyCheckResponse {
    results: FileCheckResult[];
    heuristic: HeuristicAnalysis;
    signal: Signal;
    signalReason: string;
    overallStatus: 'pass' | 'partial' | 'fail';
    duration: number;
    timestamp: string;
}

// === 2.9 推送通知 ===

export interface MobilePushConfig {
    triggers: Record<string, { title: string; vibrate: boolean }>;
    aggregation: {
        windowMs: number;
        template: string;
    };
}
