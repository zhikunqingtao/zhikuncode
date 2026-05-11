// ==================== 基础类型 ====================

export type OperationType =
  | 'file_edit' | 'file_create' | 'command_execute' | 'test_run'
  | 'git_commit' | 'refactor' | 'dependency' | 'config_change'
  | 'delete' | 'unknown';

export type VerificationStatus = 'all_pass' | 'has_warning' | 'has_error' | 'pending' | 'skipped' | 'failed';

export type Signal = 'auto_approve' | 'review_recommended' | 'manual_required' | 'blocked';

export type RiskLevel = 'safe' | 'review' | 'warning' | 'danger';

// ==================== Activity 数据结构 ====================

export interface FileChange {
  filePath: string;
  additions: number;
  deletions: number;
  diffContent?: string;
  changeType?: 'added' | 'modified' | 'deleted';
  riskScore?: number;
}

export interface RiskFactor {
  dimension: string;
  score: number; // 0-100
  reason: string;
}

export interface AISuggestion {
  type: 'info' | 'warning' | 'fix';
  message: string;
  filePath?: string;
  line?: number;
}

export interface ActivityData {
  id: string;
  sessionId?: string;
  operationType: OperationType;
  summary: string;
  status: 'completed' | 'awaiting_review' | 'error';
  timestamp: number;
  duration?: number;
  fileCount?: number;
  changedFiles: FileChange[];
  originalContent?: string;
  modifiedContent?: string;
  decision?: 'approved' | 'rejected';
  /** 工具执行结果（命令输出等） */
  toolResult?: {
    content: string;
    isError: boolean;
    metadata?: Record<string, unknown>;
  };
  insight?: {
    signal: Signal;
    riskLevel: RiskLevel;
    summary: string;
    factors: RiskFactor[];
    suggestions: AISuggestion[];
    verificationStatus: VerificationStatus;
  };
}

// ==================== 三层验证体系 ====================

export interface RiskAssessment {
  deterministic: {
    typeCheck: { passed: boolean; errorCount: number; details: string };
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

// ==================== AI 自审（Phase 2） ====================

export interface AIInsight {
  dimension: 'return_type' | 'dependency' | 'concurrency' | 'security' | 'coverage' | 'architecture';
  severity: 'info' | 'warning' | 'error';
  finding: string;
  suggestion: string;
  aiConfidence: 'high' | 'medium' | 'low';
}

export interface SelfReviewResult {
  operationId: string;
  overallAssessment: string;
  insights: AIInsight[];
  model: string;
  disclaimer: string;
  processingTimeMs: number;
}

// ==================== API 请求/响应 ====================

export interface RunChecksRequest {
  sessionId: string;
  operationId: string;
  checks: ('typescript' | 'eslint' | 'test_match' | 'build')[];
  filePaths: string[];
  timeout?: number;
}

export interface RunChecksResponse {
  operationId: string;
  status: 'all_pass' | 'has_warning' | 'has_error';
  results: {
    check: 'typescript' | 'eslint' | 'test_match' | 'build' | 'timeout' | 'error';
    passed: boolean;
    errors?: { file: string; line: number; column: number; message: string; rule?: string }[];
    warnings?: { file: string; line: number; column: number; message: string; rule?: string }[];
    duration: number;
  }[];
  totalDuration: number;
  timestamp: string;
}

export interface SelfReviewRequest {
  sessionId: string;
  operationId: string;
  operationType: OperationType;
  context: {
    filePath?: string;
    diff?: string;
    command?: string;
    exitCode?: number;
    recentMessages: { role: 'user' | 'assistant'; content: string }[];
    projectStructure?: string[];
    testFramework?: string;
  };
  model?: string;
}

// ==================== Signal 配置 ====================

export interface SignalConfig {
  label: string;
  color: string;
  iconName: string; // lucide icon name
}

export const SIGNAL_CONFIG: Record<Signal, SignalConfig> = {
  auto_approve:       { label: '可批准', color: 'bg-emerald-500', iconName: 'CheckCircle' },
  review_recommended: { label: '建议审查', color: 'bg-amber-500', iconName: 'AlertTriangle' },
  manual_required:    { label: '需审查', color: 'bg-orange-500', iconName: 'Eye' },
  blocked:            { label: '已阻断', color: 'bg-red-600', iconName: 'XCircle' },
};

// ==================== Feature Flag ====================

export interface APOSFeatureFlags {
  APOS_ACTIVITY_STREAM: boolean;
  APOS_AI_INSIGHT: boolean;
  APOS_BATCH_REVIEW: boolean;
  APOS_RISK_HEATMAP: boolean;
}

export const APOS_FLAG_DEFAULTS: APOSFeatureFlags = {
  APOS_ACTIVITY_STREAM: false,
  APOS_AI_INSIGHT: false,
  APOS_BATCH_REVIEW: false,
  APOS_RISK_HEATMAP: false,
};

export const APOS_FLAG_DEPENDENCIES: Record<string, string[]> = {
  APOS_AI_INSIGHT: ['APOS_ACTIVITY_STREAM'],
  APOS_BATCH_REVIEW: ['APOS_ACTIVITY_STREAM'],
  APOS_RISK_HEATMAP: ['APOS_AI_INSIGHT'],
};

// ==================== Activity 生命周期 ====================

export interface ActivityRetentionConfig {
  maxCount: number;
  cleanupStrategy: 'fifo_with_protection';
  cleanupBatchSize: number;
  protectedSignals: Signal[];
  autoArchiveAfterMs: number;
}

export const DEFAULT_RETENTION_CONFIG: ActivityRetentionConfig = {
  maxCount: 200,
  cleanupStrategy: 'fifo_with_protection',
  cleanupBatchSize: 50,
  protectedSignals: ['blocked', 'manual_required'],
  autoArchiveAfterMs: 1_800_000, // 30 minutes
};

// ==================== 验证工具配置 ====================

export const VERIFICATION_CONFIG = {
  mode: 'incremental' as const,
  timeout: 10_000,
  tools: {
    typescript: { command: 'npx tsc --noEmit --incremental', scope: 'changed_files_and_imports' },
    eslint: { command: 'npx eslint', scope: 'changed_files_only' },
    vitest: { command: 'npx vitest run --reporter=json', scope: 'matching_test_files', bail: true },
  },
};

// ==================== Store 状态接口 ====================

export type ActivityFilter = {
  signal?: Signal[];
  operationType?: OperationType[];
  status?: ('completed' | 'awaiting_review' | 'error')[];
};

export interface BatchApprovalStrategy {
  autoApproveOnGreen: boolean;
  requireConfirmOnYellow: boolean;
  blockOnRed: boolean;
}

export interface InsightSummary {
  totalOperations: number;
  signalCounts: Record<Signal, number>;
  lastUpdated: number;
}

// ==================== WebSocket 消息类型 ====================

export interface VerificationResultMessage {
  type: 'verification_result';
  sessionId: string;
  operationId: string;
  result: RunChecksResponse;
  timestamp: string;
}

export interface VerifyProgressMessage {
  type: 'verify_progress';
  sessionId: string;
  operationId: string;
  check: string;
  progress: number;
  timestamp: string;
}

// ==================== 服务接口 ====================

export interface InsightServiceInterface {
  requestSelfReview(req: SelfReviewRequest): Promise<SelfReviewResult>;
  runChecks(req: RunChecksRequest): Promise<RunChecksResponse>;
}

// ==================== 按钮禁用统一判定 ====================

/** 需要确定性验证的操作类型（排除 command_execute / test_run / unknown） */
export const NEEDS_VERIFICATION_OPS: OperationType[] = [
  'file_edit', 'file_create', 'delete', 'git_commit',
  'refactor', 'dependency', 'config_change',
];

/**
 * 统一按钮禁用三重判定规则（L2 / L3 / Mobile 共用）
 *
 * 禁用条件（任一为真则禁用）：
 * 1. 无数据 —
 *    - 命令执行类：无 toolResult
 *    - 文件操作类：无 changedFiles 且无 toolResult
 * 2. 验证未完成 — insight 不存在或 verificationStatus === 'pending'
 * 3. 已做决定 — decision 已设置（外层通常已分支，此处做兜底）
 *
 * @param activity     当前 Activity
 * @param hasResult    是否有执行结果（L3 可传入含 messageStore 降级的 resultData）
 */
export function computeButtonDisabled(
  activity: ActivityData,
  hasResult?: boolean,
): boolean {
  const effectiveHasResult = hasResult ?? !!activity.toolResult;

  // 3. 已做决定
  if (activity.decision !== undefined) return true;

  // 2. 验证未完成
  if (!activity.insight || activity.insight.verificationStatus === 'pending') return true;

  // 1. 无数据
  if (activity.operationType === 'command_execute') {
    if (!effectiveHasResult) return true;
  } else {
    if (activity.changedFiles.length === 0 && !effectiveHasResult) return true;
  }

  return false;
}
