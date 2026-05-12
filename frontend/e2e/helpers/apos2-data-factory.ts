/**
 * APOS Phase 2 E2E 测试数据工厂
 * 所有数据通过工厂函数动态生成，禁止 Mock JSON 文件
 *
 * 类型来源：
 *   - frontend/src/types/apos.ts (AggregatedFileChange, IndirectImpact, RiskSummary, AnomalyEvent, etc.)
 *   - frontend/src/types/index.ts (SwarmInfo, WorkerInfo)
 *   - frontend/src/store/changeImpactStore.ts (ChangeImpactAggStoreState)
 *   - frontend/src/store/anomalyStore.ts (AnomalyStoreState)
 *   - frontend/src/store/swarmStore.ts (SwarmStoreState)
 *   - frontend/src/types/apos.ts (APOSFeatureFlags, APOS_FLAG_DEFAULTS)
 */

// ═══════════════════════════════════════
// Type re-declarations for E2E isolation
// （E2E 测试不走 Vite alias，此处声明等价类型以保持类型安全）
// ═══════════════════════════════════════

interface IndirectImpact {
  filePath: string;
  reason: string;
  severity: 'low' | 'medium' | 'high';
}

interface AggregatedFileChange {
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

interface RiskSummary {
  totalFiles: number;
  highRiskCount: number;
  testCoverageGapCount: number;
  indirectImpactCount: number;
}

interface AnomalyEvent {
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

interface WorkerInfo {
  workerId: string;
  status: 'STARTING' | 'WORKING' | 'IDLE' | 'TERMINATED';
  currentTask: string;
  toolCallCount: number;
  tokenConsumed: number;
  recentToolCalls: string[];
  progressPercent: number | null;
  totalSteps: number | null;
  completedSteps: number | null;
  errorMessage: string | null;
  currentStepDescription: string | null;
  terminationReason: 'completed' | 'error' | 'aborted' | null;
}

interface SwarmInfo {
  swarmId: string;
  teamName: string;
  phase: 'INITIALIZING' | 'RUNNING' | 'IDLE' | 'SHUTTING_DOWN' | 'TERMINATED';
  activeWorkers: number;
  totalWorkers: number;
  completedTasks: number;
  totalTasks: number;
  workers: Record<string, WorkerInfo>;
}

interface APOSFeatureFlags {
  APOS_ACTIVITY_STREAM: boolean;
  APOS_AI_INSIGHT: boolean;
  APOS_BATCH_REVIEW: boolean;
  APOS_RISK_HEATMAP: boolean;
  APOS_CHANGE_IMPACT: boolean;
  APOS_AGENT_PIPELINE: boolean;
  APOS_ANOMALY_ALERT: boolean;
  APOS_MOBILE_STATUS: boolean;
}

interface ChangeImpactAggState {
  aggregatedChanges: AggregatedFileChange[];
  riskSummary: RiskSummary;
  selectedFilePath: string | null;
}

interface SwarmState {
  swarms: Array<SwarmInfo>;
  activeSwarmId: string | null;
  panelVisible: boolean;
}

// ═══════════════════════════════════════
// UUID Helper
// ═══════════════════════════════════════

let _counter = 0;

function testUUID(): string {
  _counter += 1;
  return `test-${Date.now()}-${_counter.toString().padStart(4, '0')}`;
}

// ═══════════════════════════════════════
// 1. ChangeImpact Data Factory
// ═══════════════════════════════════════

/**
 * 生成单个 AggregatedFileChange
 */
export function createFileChangeData(
  overrides?: Partial<AggregatedFileChange>,
): AggregatedFileChange {
  return {
    filePath: 'src/components/App.tsx',
    changeType: 'modified',
    totalAdditions: 15,
    totalDeletions: 3,
    riskLevel: 'safe',
    testCoverageGap: false,
    touchCount: 1,
    indirectImpacts: [],
    ...overrides,
  };
}

/**
 * 生成完整的 ChangeImpactAggStore 状态
 * @param fileCount 文件变更数量
 * @param options 高风险 / 测试缺口 / 间接影响选项
 */
export function createChangeImpactState(
  fileCount: number,
  options?: {
    withHighRisk?: boolean;
    withTestGap?: boolean;
    withIndirectImpact?: boolean;
  },
): ChangeImpactAggState {
  const changes: AggregatedFileChange[] = [];

  for (let i = 0; i < fileCount; i++) {
    const ext = ['ts', 'tsx', 'java', 'py', 'json'][i % 5];
    const dir = ['components', 'services', 'store', 'utils', 'api'][i % 5];
    const fileName = `File${i + 1}.${ext}`;
    const filePath = `src/${dir}/${fileName}`;

    let riskLevel: AggregatedFileChange['riskLevel'] = 'safe';
    let touchCount = 1;
    let testCoverageGap = false;
    const indirectImpacts: IndirectImpact[] = [];

    // 第一个文件设为高风险（touchCount >= 3 → danger）
    if (options?.withHighRisk && i === 0) {
      riskLevel = 'danger';
      touchCount = 4;
    }

    // 第二个文件设为 warning（touchCount >= 2）
    if (options?.withHighRisk && i === 1) {
      riskLevel = 'warning';
      touchCount = 2;
    }

    // 测试缺口
    if (options?.withTestGap && i < 2) {
      testCoverageGap = true;
    }

    // 间接影响
    if (options?.withIndirectImpact && i === 0) {
      indirectImpacts.push(
        {
          filePath: 'src/api/client.ts',
          reason: 'API endpoint dependency',
          severity: 'high',
        },
        {
          filePath: 'src/utils/helpers.ts',
          reason: 'Utility function call',
          severity: 'medium',
        },
        {
          filePath: 'src/config/routes.ts',
          reason: 'Route configuration reference',
          severity: 'low',
        },
      );
    }

    changes.push(
      createFileChangeData({
        filePath,
        changeType: i === 0 ? 'modified' : i === fileCount - 1 ? 'added' : 'modified',
        totalAdditions: 10 + i * 5,
        totalDeletions: i * 2,
        riskLevel,
        touchCount,
        testCoverageGap,
        indirectImpacts,
      }),
    );
  }

  // 按 riskLevel 降序排列 (danger → warning → review → safe)
  const riskOrder: Record<string, number> = { danger: 0, warning: 1, review: 2, safe: 3 };
  changes.sort((a, b) => (riskOrder[a.riskLevel] ?? 99) - (riskOrder[b.riskLevel] ?? 99));

  const riskSummary: RiskSummary = {
    totalFiles: changes.length,
    highRiskCount: changes.filter((f) => f.riskLevel === 'danger' || f.riskLevel === 'warning')
      .length,
    testCoverageGapCount: changes.filter((f) => f.testCoverageGap).length,
    indirectImpactCount: changes.reduce((sum, f) => sum + f.indirectImpacts.length, 0),
  };

  return {
    aggregatedChanges: changes,
    riskSummary,
    selectedFilePath: null,
  };
}

// ═══════════════════════════════════════
// 2. Anomaly Event Factory
// ═══════════════════════════════════════

/**
 * 生成 AnomalyEvent 事件
 * @param ruleId 规则标识
 * @param overrides 覆盖字段
 */
export function createAnomalyEvent(
  ruleId: 'loop_detection' | 'stall_detection' | 'error_cascade',
  overrides?: Partial<AnomalyEvent>,
): AnomalyEvent {
  const ruleDefaults: Record<
    string,
    { severity: AnomalyEvent['severity']; message: string }
  > = {
    loop_detection: {
      severity: 'error',
      message: 'Worker worker-001 检测到重复调用模式（最近10次中50%+参数相同）',
    },
    stall_detection: {
      severity: 'critical',
      message: 'Worker worker-001 超过60s无任何工具调用活动',
    },
    error_cascade: {
      severity: 'error',
      message: 'Worker worker-001 连续失败（最近5次中3次错误）',
    },
  };

  const defaults = ruleDefaults[ruleId];

  return {
    id: testUUID(),
    swarmId: 'swarm-test-001',
    workerId: 'worker-001',
    workerName: 'TestWorker-1',
    ruleId,
    severity: defaults.severity,
    message: defaults.message,
    detectedAt: Date.now(),
    resolvedAt: null,
    resolution: null,
    ...overrides,
  };
}

// ═══════════════════════════════════════
// 3. Worker / Pipeline / Swarm Factory
// ═══════════════════════════════════════

/**
 * 生成单个 WorkerInfo
 * @param status Worker 状态
 * @param overrides 覆盖字段
 */
export function createWorkerState(
  status: 'STARTING' | 'WORKING' | 'IDLE' | 'TERMINATED',
  overrides?: Partial<WorkerInfo>,
): WorkerInfo {
  const statusDefaults: Record<string, Partial<WorkerInfo>> = {
    STARTING: {
      currentTask: 'Initializing workspace...',
      toolCallCount: 0,
      tokenConsumed: 0,
      progressPercent: null,
      totalSteps: null,
      completedSteps: null,
      errorMessage: null,
      currentStepDescription: null,
      terminationReason: null,
    },
    WORKING: {
      currentTask: 'Implementing feature module',
      toolCallCount: 12,
      tokenConsumed: 8500,
      progressPercent: 45,
      totalSteps: 10,
      completedSteps: 4,
      errorMessage: null,
      currentStepDescription: 'Running unit tests...',
      terminationReason: null,
    },
    IDLE: {
      currentTask: '',
      toolCallCount: 25,
      tokenConsumed: 18000,
      progressPercent: 100,
      totalSteps: 10,
      completedSteps: 10,
      errorMessage: null,
      currentStepDescription: null,
      terminationReason: null,
    },
    TERMINATED: {
      currentTask: '',
      toolCallCount: 8,
      tokenConsumed: 5200,
      progressPercent: null,
      totalSteps: null,
      completedSteps: null,
      errorMessage: null,
      currentStepDescription: null,
      terminationReason: 'completed',
    },
  };

  const defaults = statusDefaults[status] ?? {};
  const workerId = overrides?.workerId ?? `worker-${testUUID()}`;

  return {
    workerId,
    status,
    currentTask: '',
    toolCallCount: 0,
    tokenConsumed: 0,
    recentToolCalls: [],
    progressPercent: null,
    totalSteps: null,
    completedSteps: null,
    errorMessage: null,
    currentStepDescription: null,
    terminationReason: null,
    ...defaults,
    ...overrides,
  };
}

/**
 * 生成完整的 SwarmState（用于 injectSwarmData）
 * @param workerCount Worker 数量
 * @param options 额外选项
 */
export function createSwarmState(
  workerCount: number,
  options?: {
    withAnomaly?: boolean;
    phase?: SwarmInfo['phase'];
    swarmId?: string;
  },
): SwarmState {
  const swarmId = options?.swarmId ?? `swarm-${testUUID()}`;
  const phase = options?.phase ?? 'RUNNING';

  const workers: Record<string, WorkerInfo> = {};
  const statusCycle: WorkerInfo['status'][] = ['WORKING', 'STARTING', 'IDLE', 'TERMINATED'];

  for (let i = 0; i < workerCount; i++) {
    const workerId = `worker-${String(i + 1).padStart(3, '0')}`;
    const status = statusCycle[i % statusCycle.length];

    const worker = createWorkerState(status, {
      workerId,
      currentTask: status === 'WORKING' ? `Task ${i + 1}: Implement feature` : '',
      progressPercent: status === 'WORKING' ? 20 + i * 15 : null,
      completedSteps: status === 'WORKING' ? i + 1 : null,
      totalSteps: status === 'WORKING' ? workerCount + 2 : null,
      currentStepDescription:
        status === 'WORKING' ? `Processing step ${i + 1}...` : null,
      // 最后一个 TERMINATED worker 标记为 error
      terminationReason:
        status === 'TERMINATED' && i === workerCount - 1 ? 'error' : status === 'TERMINATED' ? 'completed' : null,
      errorMessage:
        status === 'TERMINATED' && i === workerCount - 1
          ? 'Process terminated unexpectedly'
          : null,
    });
    workers[workerId] = worker;
  }

  const activeWorkers = Object.values(workers).filter(
    (w) => w.status === 'WORKING' || w.status === 'STARTING',
  ).length;

  const swarm: SwarmInfo = {
    swarmId,
    teamName: 'test-team',
    phase,
    activeWorkers,
    totalWorkers: workerCount,
    completedTasks: Object.values(workers).filter(
      (w) => w.status === 'IDLE' || (w.status === 'TERMINATED' && w.terminationReason === 'completed'),
    ).length,
    totalTasks: workerCount,
    workers,
  };

  return {
    swarms: [swarm],
    activeSwarmId: swarmId,
    panelVisible: true,
  };
}

// ═══════════════════════════════════════
// 4. Feature Flag Factory
// ═══════════════════════════════════════

/** Phase 2 所有 Feature Flag 的默认值（与 APOS_FLAG_DEFAULTS 一致） */
const PHASE2_FLAG_DEFAULTS: APOSFeatureFlags = {
  APOS_ACTIVITY_STREAM: true,
  APOS_AI_INSIGHT: true,
  APOS_BATCH_REVIEW: true,
  APOS_RISK_HEATMAP: false,
  APOS_CHANGE_IMPACT: true,
  APOS_AGENT_PIPELINE: true,
  APOS_ANOMALY_ALERT: true,
  APOS_MOBILE_STATUS: true,
};

/**
 * 生成 Phase 2 Feature Flag 配置
 * @param overrides 覆盖特定 Flag
 */
export function createPhase2Flags(
  overrides?: Partial<APOSFeatureFlags>,
): APOSFeatureFlags {
  return {
    ...PHASE2_FLAG_DEFAULTS,
    ...overrides,
  };
}

// ═══════════════════════════════════════
// 5. Risk Summary Factory
// ═══════════════════════════════════════

/**
 * 生成 RiskSummary 数据
 */
export function createRiskSummary(overrides?: Partial<RiskSummary>): RiskSummary {
  return {
    totalFiles: 5,
    highRiskCount: 1,
    testCoverageGapCount: 2,
    indirectImpactCount: 3,
    ...overrides,
  };
}

// ═══════════════════════════════════════
// 6. Composite Scenario Builders
// ═══════════════════════════════════════

/**
 * 创建 "全场景" 测试数据集 — 包含 ChangeImpact + Swarm + Anomaly + Flags
 * 用于需要验证多模块联动的集成测试
 */
export function createFullPhase2Scenario() {
  return {
    changeImpact: createChangeImpactState(5, {
      withHighRisk: true,
      withTestGap: true,
      withIndirectImpact: true,
    }),
    swarm: createSwarmState(4, { phase: 'RUNNING' }),
    anomalies: {
      activeAnomalies: [
        createAnomalyEvent('loop_detection', { workerId: 'worker-001', workerName: 'CodeWriter' }),
        createAnomalyEvent('stall_detection', { workerId: 'worker-002', workerName: 'TestRunner' }),
      ],
      resolvedHistory: [
        createAnomalyEvent('error_cascade', {
          workerId: 'worker-003',
          workerName: 'Reviewer',
          resolvedAt: Date.now() - 60_000,
          resolution: 'dismiss',
        }),
      ],
    },
    flags: createPhase2Flags(),
  };
}

/**
 * 创建 "空状态" 测试数据集 — 所有 Store 为空
 * 用于验证各面板的空状态展示
 */
export function createEmptyPhase2Scenario() {
  return {
    changeImpact: {
      aggregatedChanges: [],
      riskSummary: { totalFiles: 0, highRiskCount: 0, testCoverageGapCount: 0, indirectImpactCount: 0 },
      selectedFilePath: null,
    } as ChangeImpactAggState,
    swarm: {
      swarms: [],
      activeSwarmId: null,
      panelVisible: false,
    } as SwarmState,
    anomalies: {
      activeAnomalies: [],
      resolvedHistory: [],
    },
    flags: createPhase2Flags(),
  };
}
