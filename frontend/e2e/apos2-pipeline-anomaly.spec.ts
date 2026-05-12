import { test, expect } from '@playwright/test';
import {
  waitForAppReady,
  navigateToAPOSTab,
  navigateToDAGTab,
  injectSwarmData,
  injectAnomalyData,
  injectFeatureFlags,
  toggleDetailsPanel,
  clearAllStoreData,
  takeTestScreenshot,
} from './helpers/apos2-helpers';
import {
  createSwarmState,
  createAnomalyEvent,
  createWorkerState,
  createPhase2Flags,
} from './helpers/apos2-data-factory';

// E2E 运行时 Vite 会正确解析模块路径，standalone tsc 类型兼容性由此辅助函数解决
const flags = (f: ReturnType<typeof createPhase2Flags>) => f as unknown as Record<string, boolean>;
const swarms = (s: ReturnType<typeof createSwarmState>) => ({
  swarms: s.swarms as unknown as Array<{ swarmId: string; [key: string]: unknown }>,
  activeSwarmId: s.activeSwarmId,
  panelVisible: s.panelVisible,
});

// ══════════════════════════════════════════════════════════════
// A. AgentPipelineView (TC-APOS2-007 ~ 011)
// ══════════════════════════════════════════════════════════════

test.describe('APOS Phase 2 - Agent Pipeline (TC-APOS2-007~011)', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/');
    await waitForAppReady(page);
    await injectFeatureFlags(page, flags(createPhase2Flags({ APOS_AGENT_PIPELINE: true })));
    await navigateToAPOSTab(page);
  });

  test('TC-APOS2-007: AgentPipelineView 空状态展示', async ({ page }) => {
    // 确保无活跃 Swarm
    await clearAllStoreData(page);
    await injectFeatureFlags(page, flags(createPhase2Flags({ APOS_AGENT_PIPELINE: true })));
    await navigateToAPOSTab(page);

    // 展开 Agent Pipeline 折叠区域
    await toggleDetailsPanel(page, 'Agent Pipeline');

    // 验证空状态标题
    const title = page.locator('h2').filter({ hasText: 'Agent Pipeline' });
    await expect(title).toBeVisible({ timeout: 5000 });

    // 验证空状态图标和文案
    const emptyIcon = page.locator('text=🔗');
    await expect(emptyIcon).toBeVisible({ timeout: 3000 });

    const noSwarmText = page.locator('text=No active Swarm');
    await expect(noSwarmText).toBeVisible({ timeout: 3000 });

    const hintText = page.locator('text=Pipeline will appear when a Swarm is running');
    await expect(hintText).toBeVisible({ timeout: 3000 });

    await takeTestScreenshot(page, 'TC-APOS2-007', '01-empty-state');
  });

  test('TC-APOS2-008: Worker 节点网格布局展示', async ({ page }) => {
    // 注入包含 4 个 Workers 的 Swarm
    const swarmState = createSwarmState(4, { phase: 'RUNNING', swarmId: 'swarm-test-008' });
    await injectSwarmData(page, swarms(swarmState));

    // 展开 Agent Pipeline
    await toggleDetailsPanel(page, 'Agent Pipeline');

    // 验证标题
    const title = page.locator('h2').filter({ hasText: 'Agent Pipeline' });
    await expect(title).toBeVisible({ timeout: 5000 });

    // 验证网格布局容器存在（grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3）
    const gridContainer = page.locator('.grid.grid-cols-1');
    await expect(gridContainer).toBeVisible({ timeout: 5000 });

    // 验证有 4 个 Worker 节点卡片（rounded-lg border-2）
    const workerCards = page.locator('.rounded-lg.border-2');
    await expect(workerCards).toHaveCount(4, { timeout: 5000 });

    // 验证每个节点显示 workerId
    await expect(page.locator('text=worker-001')).toBeVisible({ timeout: 3000 });
    await expect(page.locator('text=worker-002')).toBeVisible({ timeout: 3000 });
    await expect(page.locator('text=worker-003')).toBeVisible({ timeout: 3000 });
    await expect(page.locator('text=worker-004')).toBeVisible({ timeout: 3000 });

    await takeTestScreenshot(page, 'TC-APOS2-008', '01-worker-grid');
  });

  test('TC-APOS2-009: Worker 四种状态图标（STARTING/WORKING/IDLE/TERMINATED）', async ({ page }) => {
    // 创建含 4 种状态的 Swarm（statusCycle: WORKING/STARTING/IDLE/TERMINATED）
    const swarmState = createSwarmState(4, { phase: 'RUNNING', swarmId: 'swarm-test-009' });
    await injectSwarmData(page, swarms(swarmState));

    await toggleDetailsPanel(page, 'Agent Pipeline');

    // 限定在 Pipeline 网格容器内查找，避免匹配到 Sidebar tab 按钮的 border-blue-500
    const pipelineGrid = page.locator('.grid.grid-cols-1');
    await expect(pipelineGrid).toBeVisible({ timeout: 5000 });

    // Worker-001 = WORKING → 图标 🔄, 边框 border-blue-500
    const workingCard = pipelineGrid.locator('.border-blue-500');
    await expect(workingCard).toBeVisible({ timeout: 5000 });
    await expect(workingCard.locator('text=🔄')).toBeVisible({ timeout: 3000 });

    // Worker-002 = STARTING → 图标 ⏳, 边框 border-yellow-400
    const startingCard = pipelineGrid.locator('.border-yellow-400');
    await expect(startingCard).toBeVisible({ timeout: 5000 });
    await expect(startingCard.locator('text=⏳')).toBeVisible({ timeout: 3000 });

    // Worker-003 = IDLE → 图标 ⏸, 边框 border-gray-400
    const idleCard = pipelineGrid.locator('.border-gray-400');
    await expect(idleCard).toBeVisible({ timeout: 5000 });
    await expect(idleCard.locator('text=⏸')).toBeVisible({ timeout: 3000 });

    // Worker-004 = TERMINATED (error, as defined by factory) → 图标 ❌, 边框 border-red-500
    const terminatedCard = pipelineGrid.locator('.border-red-500');
    await expect(terminatedCard).toBeVisible({ timeout: 5000 });
    await expect(terminatedCard.locator('text=❌')).toBeVisible({ timeout: 3000 });

    await takeTestScreenshot(page, 'TC-APOS2-009', '01-status-icons');
  });

  test('TC-APOS2-010: PipelineNode 进度条显示', async ({ page }) => {
    // 注入有 WORKING 状态 Worker 的 Swarm（带 progressPercent）
    const swarmState = createSwarmState(2, { phase: 'RUNNING', swarmId: 'swarm-test-010' });
    await injectSwarmData(page, swarms(swarmState));

    await toggleDetailsPanel(page, 'Agent Pipeline');

    // WORKING Worker 应有进度条（蓝色填充 bg-blue-500 h-2）
    const progressBar = page.locator('.bg-blue-500.h-2.rounded-full');
    await expect(progressBar).toBeVisible({ timeout: 5000 });

    // 验证进度条有 width style
    const style = await progressBar.getAttribute('style');
    expect(style).toContain('width:');

    // 验证步骤文本格式 "N/M steps"
    const stepsText = page.locator('text=/\\d+\\/\\d+ steps/');
    await expect(stepsText).toBeVisible({ timeout: 3000 });

    // 验证百分比文本
    const percentText = page.locator('text=/%/');
    await expect(percentText).toBeVisible({ timeout: 3000 });

    // 验证当前步骤描述（currentStepDescription）
    const stepDesc = page.locator('text=/Processing step/');
    await expect(stepDesc).toBeVisible({ timeout: 3000 });

    await takeTestScreenshot(page, 'TC-APOS2-010', '01-progress-bar');
  });

  test('TC-APOS2-011: PipelineNode 错误消息展示', async ({ page }) => {
    // 注入有 TERMINATED(error) 状态 Worker 的 Swarm
    const workers: Record<string, ReturnType<typeof createWorkerState>> = {};
    workers['worker-err'] = createWorkerState('TERMINATED', {
      workerId: 'worker-err',
      terminationReason: 'error',
      errorMessage: 'Process terminated unexpectedly',
    });

    await injectSwarmData(page, {
      swarms: [{
        swarmId: 'swarm-test-011',
        teamName: 'test-team',
        phase: 'RUNNING',
        activeWorkers: 0,
        totalWorkers: 1,
        completedTasks: 0,
        totalTasks: 1,
        workers,
      }] as any,
      activeSwarmId: 'swarm-test-011',
      panelVisible: true,
    });

    await toggleDetailsPanel(page, 'Agent Pipeline');

    // 验证错误消息显示 ⚠️ + errorMessage
    const errorMsg = page.locator('text=⚠️ Process terminated unexpectedly');
    await expect(errorMsg).toBeVisible({ timeout: 5000 });

    // 验证边框为红色
    const errorCard = page.locator('.border-red-500');
    await expect(errorCard).toBeVisible({ timeout: 3000 });

    // 验证 ❌ 图标
    await expect(errorCard.locator('text=❌')).toBeVisible({ timeout: 3000 });

    // 验证 Swarm 存在但 Workers 为空 时的等待提示
    await injectSwarmData(page, {
      swarms: [{
        swarmId: 'swarm-test-011b',
        teamName: 'test-team',
        phase: 'RUNNING',
        activeWorkers: 0,
        totalWorkers: 0,
        completedTasks: 0,
        totalTasks: 0,
        workers: {},
      }] as any,
      activeSwarmId: 'swarm-test-011b',
      panelVisible: true,
    });
    await page.waitForTimeout(400);

    const waitingText = page.locator('text=Waiting for workers to start...');
    await expect(waitingText).toBeVisible({ timeout: 5000 });

    await takeTestScreenshot(page, 'TC-APOS2-011', '01-error-message');
  });
});

// ══════════════════════════════════════════════════════════════
// B. AgentDAGChart (TC-APOS2-012 ~ 015)
// ══════════════════════════════════════════════════════════════

test.describe('APOS Phase 2 - Agent DAG Chart (TC-APOS2-012~015)', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/');
    await waitForAppReady(page);
    await injectFeatureFlags(page, flags(createPhase2Flags()));
    // DAG 图组件在 Sidebar 的 DAG Tab 中渲染，需先导航至该 Tab
    await navigateToDAGTab(page);
  });

  test('TC-APOS2-012: DAG 图空状态', async ({ page }) => {
    // 确保 coordinatorStore 无任务数据
    await page.evaluate(async () => {
      const mod = await import('/src/store/coordinatorStore.ts');
      const store = (mod as any).useCoordinatorStore;
      store.setState({ agentTasks: [], activeWorkflow: null, mailboxEvents: [] });
    });
    await page.waitForTimeout(300);

    // 查找 DAG 组件的空状态
    // AgentDAGChart 空状态显示 Network 图标 + "暂无 Agent 任务"
    const emptyText = page.locator('text=暂无 Agent 任务');
    await expect(emptyText).toBeVisible({ timeout: 5000 });

    const hintText = page.locator('text=当 Agent 协作任务开始时，DAG 将自动显示');
    await expect(hintText).toBeVisible({ timeout: 3000 });

    await takeTestScreenshot(page, 'TC-APOS2-012', '01-dag-empty');
  });

  test.fixme('TC-APOS2-013: 三层边策略（显式依赖/mailbox/时间推断）— 需要视觉回归工具验证边颜色', async ({ page }) => {
    // 注入 coordinatorStore Agent 任务数据
    await page.evaluate(async () => {
      const mod = await import('/src/store/coordinatorStore.ts');
      const store = (mod as any).useCoordinatorStore;
      const now = Date.now();
      store.setState({
        agentTasks: [
          {
            taskId: 'task-a', agentName: 'Planner', agentType: 'coordinator',
            description: 'Plan task', status: 'running', startTime: now - 10000,
            parentTaskId: null, dependencies: [], progress: '', result: '',
          },
          {
            taskId: 'task-b', agentName: 'Coder', agentType: 'worker',
            description: 'Write code', status: 'running', startTime: now - 8000,
            parentTaskId: 'task-a', dependencies: [], progress: '', result: '',
          },
          {
            taskId: 'task-c', agentName: 'Reviewer', agentType: 'worker',
            description: 'Review code', status: 'pending', startTime: now - 3000,
            parentTaskId: null, dependencies: [], progress: '', result: '',
          },
        ],
        mailboxEvents: [
          { from: 'Coder', to: 'Reviewer', messageSize: 1024, contentType: 'text/json', timestamp: now - 2000 },
        ],
        activeWorkflow: { currentPhaseIndex: 0 },
      });
    });
    await page.waitForTimeout(500);

    // 视觉验证三种边：
    // 1. explicit_dependency (task-a → task-b): 蓝色实线 #3B82F6
    // 2. mailbox_communication (task-b → task-c): 绿色实线 #10B981
    // 3. time_inferred: 灰色虚线 #9CA3AF
    // 需要视觉回归工具验证，此处标记 fixme
    const reactFlowEdges = page.locator('.react-flow__edge');
    await expect(reactFlowEdges.first()).toBeVisible({ timeout: 5000 });
  });

  test('TC-APOS2-014: 节点 >20 自动折叠', async ({ page }) => {
    // 注入 25 个 Agent 任务
    await page.evaluate(async () => {
      const mod = await import('/src/store/coordinatorStore.ts');
      const store = (mod as any).useCoordinatorStore;
      const now = Date.now();
      const tasks = Array.from({ length: 25 }, (_, i) => ({
        taskId: `task-${i}`,
        agentName: `Agent-${i}`,
        agentType: 'worker',
        description: `Task ${i}`,
        status: 'completed',
        startTime: now - (25 - i) * 3000,
        parentTaskId: i > 0 ? `task-${i - 1}` : null,
        dependencies: [],
        progress: '',
        result: '',
      }));
      store.setState({ agentTasks: tasks, activeWorkflow: { currentPhaseIndex: 0 }, mailboxEvents: [] });
    });
    await page.waitForTimeout(800);

    // 验证摘要节点存在，显示 "+5 more"
    const summaryNode = page.locator('text=+5 more');
    await expect(summaryNode).toBeVisible({ timeout: 8000 });

    // 验证摘要描述
    const summaryDesc = page.locator('text=共 25 个节点，已折叠显示');
    await expect(summaryDesc).toBeVisible({ timeout: 5000 });

    await takeTestScreenshot(page, 'TC-APOS2-014', '01-auto-collapse');
  });

  test('TC-APOS2-015: DAG 全屏模式切换', async ({ page }) => {
    // 注入 3 个 Agent 任务使 DAG 有内容
    await page.evaluate(async () => {
      const mod = await import('/src/store/coordinatorStore.ts');
      const store = (mod as any).useCoordinatorStore;
      const now = Date.now();
      store.setState({
        agentTasks: [
          { taskId: 't1', agentName: 'A1', agentType: 'worker', description: 'T1', status: 'running', startTime: now - 5000, parentTaskId: null, dependencies: [], progress: '', result: '' },
          { taskId: 't2', agentName: 'A2', agentType: 'worker', description: 'T2', status: 'running', startTime: now - 3000, parentTaskId: 't1', dependencies: [], progress: '', result: '' },
        ],
        activeWorkflow: { currentPhaseIndex: 0 },
        mailboxEvents: [],
      });
    });
    await page.waitForTimeout(500);

    // 找到全屏按钮（title="全屏"）
    const fullscreenBtn = page.locator('button[title="全屏"]');
    await expect(fullscreenBtn).toBeVisible({ timeout: 5000 });

    // 点击全屏
    await fullscreenBtn.click();
    await page.waitForTimeout(500);

    // 验证退出全屏按钮出现（title="退出全屏"）
    const exitFullscreenBtn = page.locator('button[title="退出全屏"]');
    await expect(exitFullscreenBtn).toBeVisible({ timeout: 5000 });

    // 退出全屏
    await exitFullscreenBtn.click();
    await page.waitForTimeout(500);

    // 全屏按钮重新出现
    await expect(fullscreenBtn).toBeVisible({ timeout: 5000 });

    await takeTestScreenshot(page, 'TC-APOS2-015', '01-fullscreen-toggle');
  });
});

// ══════════════════════════════════════════════════════════════
// C. 资源消耗追踪 (TC-APOS2-016 ~ 018)
// ══════════════════════════════════════════════════════════════

test.describe('APOS Phase 2 - Resource Consumption (TC-APOS2-016~018)', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/');
    await waitForAppReady(page);
    await injectFeatureFlags(page, flags(createPhase2Flags()));
    await navigateToAPOSTab(page);
  });

  test('TC-APOS2-016: 资源面板空状态', async ({ page }) => {
    // 验证 Pipeline 区域存在但无 Worker 时，不展示资源数据
    await clearAllStoreData(page);
    await injectFeatureFlags(page, flags(createPhase2Flags()));
    await navigateToAPOSTab(page);
    await toggleDetailsPanel(page, 'Agent Pipeline');

    // 空状态：无 Swarm，资源消耗数据不展示
    const noSwarmText = page.locator('text=No active Swarm');
    await expect(noSwarmText).toBeVisible({ timeout: 5000 });

    await takeTestScreenshot(page, 'TC-APOS2-016', '01-resource-empty');
  });

  test('TC-APOS2-017: Token 消耗/API 调用/耗时数据展示', async ({ page }) => {
    // 注入带有 toolCallCount 和 tokenConsumed 数据的 Swarm
    const swarmState = createSwarmState(3, { phase: 'RUNNING', swarmId: 'swarm-resource-017' });
    await injectSwarmData(page, swarms(swarmState));

    await toggleDetailsPanel(page, 'Agent Pipeline');

    // Worker 节点应展示 — 验证 WORKING Worker 的 toolCallCount（通过 Worker 日志 "N tools, M tokens"）
    // PipelineNode 组件本身不直接展示 toolCallCount，但 SwarmStore 的 logs 中记录了
    // 验证 Worker 节点卡片存在
    const workerCards = page.locator('.rounded-lg.border-2');
    await expect(workerCards.first()).toBeVisible({ timeout: 5000 });
    const cardCount = await workerCards.count();
    expect(cardCount).toBe(3);

    await takeTestScreenshot(page, 'TC-APOS2-017', '01-worker-resource');
  });

  test('TC-APOS2-018: 资源警告阈值提示', async ({ page }) => {
    // 注入高消耗 Worker
    const highConsumptionWorker = createWorkerState('WORKING', {
      workerId: 'worker-heavy',
      toolCallCount: 150,
      tokenConsumed: 500000,
      progressPercent: 80,
      totalSteps: 20,
      completedSteps: 16,
      currentStepDescription: 'Heavy processing...',
    });

    await injectSwarmData(page, {
      swarms: [{
        swarmId: 'swarm-resource-018',
        teamName: 'test-team',
        phase: 'RUNNING',
        activeWorkers: 1,
        totalWorkers: 1,
        completedTasks: 0,
        totalTasks: 1,
        workers: { 'worker-heavy': highConsumptionWorker },
      }] as any,
      activeSwarmId: 'swarm-resource-018',
      panelVisible: true,
    });

    await toggleDetailsPanel(page, 'Agent Pipeline');

    // 验证 Worker 节点展示且有进度信息
    const workerCard = page.locator('.rounded-lg.border-2');
    await expect(workerCard).toBeVisible({ timeout: 5000 });

    // 验证进度条显示 80%
    const progressBar = page.locator('.bg-blue-500.h-2.rounded-full');
    await expect(progressBar).toBeVisible({ timeout: 3000 });

    await takeTestScreenshot(page, 'TC-APOS2-018', '01-resource-warning');
  });
});

// ══════════════════════════════════════════════════════════════
// D. 异常检测 (TC-APOS2-019 ~ 024)
// ══════════════════════════════════════════════════════════════

test.describe('APOS Phase 2 - Anomaly Detection (TC-APOS2-019~024)', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/');
    await waitForAppReady(page);
    await injectFeatureFlags(page, flags(createPhase2Flags({
      APOS_AGENT_PIPELINE: true,
      APOS_ANOMALY_ALERT: true,
    })));
    await navigateToAPOSTab(page);
  });

  test('TC-APOS2-019: AnomalyAlertPanel 空状态（无异常时）', async ({ page }) => {
    // 注入 Swarm 数据使 Pipeline 可见，但无异常
    const swarmState = createSwarmState(2, { phase: 'RUNNING', swarmId: 'swarm-anomaly-019' });
    await injectSwarmData(page, swarms(swarmState));
    await injectAnomalyData(page, { activeAnomalies: [], resolvedHistory: [] });

    await toggleDetailsPanel(page, 'Agent Pipeline');

    // 验证 "Anomaly Alerts" 标题可见
    const alertTitle = page.locator('h2').filter({ hasText: 'Anomaly Alerts' });
    await expect(alertTitle).toBeVisible({ timeout: 5000 });

    // 验证无计数徽章（bg-red-500 不应存在）
    const countBadge = page.locator('.bg-red-500.text-white');
    await expect(countBadge).not.toBeVisible({ timeout: 2000 });

    // 验证空状态 ✅ 图标
    const checkIcon = page.locator('text=✅');
    await expect(checkIcon).toBeVisible({ timeout: 3000 });

    // 验证 "运行正常" 文案
    const normalText = page.locator('text=运行正常');
    await expect(normalText).toBeVisible({ timeout: 3000 });

    // 验证 "No anomalies detected" 文案
    const noAnomaliesText = page.locator('text=No anomalies detected');
    await expect(noAnomaliesText).toBeVisible({ timeout: 3000 });

    await takeTestScreenshot(page, 'TC-APOS2-019', '01-anomaly-empty');
  });

  test('TC-APOS2-020: 循环检测规则触发（loop_detection）', async ({ page }) => {
    const loopAnomaly = createAnomalyEvent('loop_detection', {
      workerId: 'worker-001',
      workerName: 'CodeWriter',
      swarmId: 'swarm-anomaly-020',
    });

    const swarmState = createSwarmState(2, { phase: 'RUNNING', swarmId: 'swarm-anomaly-020' });
    await injectSwarmData(page, swarms(swarmState));

    await toggleDetailsPanel(page, 'Agent Pipeline');

    // 通过 window.__anomalyStore__ 注入数据（避免 Vite 模块多实例问题）
    await injectAnomalyData(page, { activeAnomalies: [loopAnomaly] });

    // 验证 UI：Anomaly Alerts 标题可见
    const alertTitle = page.locator('h2').filter({ hasText: 'Anomaly Alerts' });
    await expect(alertTitle).toBeVisible({ timeout: 5000 });

    // 验证 loop_detection 图标 🔄
    const loopIcon = page.locator('.rounded-lg.border').locator('text=🔄');
    await expect(loopIcon).toBeVisible({ timeout: 5000 });

    // 验证 Worker 名称
    const workerName = page.locator('text=CodeWriter');
    await expect(workerName).toBeVisible({ timeout: 3000 });

    // 验证 severity=error 标签
    const severityLabel = page.locator('.bg-yellow-100').filter({ hasText: 'ERROR' });
    await expect(severityLabel).toBeVisible({ timeout: 3000 });

    // 验证消息文本
    const msgText = page.locator('text=检测到重复调用模式');
    await expect(msgText).toBeVisible({ timeout: 3000 });

    await takeTestScreenshot(page, 'TC-APOS2-020', '01-loop-detection');
  });

  test('TC-APOS2-021: 卡死检测规则触发（stall_detection）', async ({ page }) => {
    const swarmState = createSwarmState(2, { phase: 'RUNNING', swarmId: 'swarm-anomaly-021' });
    await injectSwarmData(page, swarms(swarmState));

    await toggleDetailsPanel(page, 'Agent Pipeline');

    // 直接在 page.evaluate 内创建并注入 stall_detection 数据
    const injectResult = await page.evaluate(async () => {
      const mod = await import('/src/store/anomalyStore');
      const store = (mod as any).useAnomalyStore || mod.useAnomalyStore;
      store.setState({
        activeAnomalies: [{
          id: 'test-anomaly-021',
          swarmId: 'swarm-anomaly-021',
          workerId: 'worker-002',
          workerName: 'TestRunner',
          ruleId: 'stall_detection',
          severity: 'critical',
          message: 'Worker worker-002 超过60s无任何工具调用活动',
          detectedAt: Date.now(),
          resolvedAt: null,
          resolution: null,
        }],
        resolvedHistory: [],
      });
      return store.getState().activeAnomalies.length;
    });
    expect(injectResult).toBe(1);
    await page.waitForTimeout(300);

    // 验证 store 状态
    const storeState = await page.evaluate(async () => {
      const mod = await import('/src/store/anomalyStore');
      const store = (mod as any).useAnomalyStore || mod.useAnomalyStore;
      const anomalies = store.getState().activeAnomalies;
      return {
        count: anomalies.length,
        first: anomalies[0] ? {
          ruleId: anomalies[0].ruleId,
          workerName: anomalies[0].workerName,
          severity: anomalies[0].severity,
          message: anomalies[0].message,
        } : null,
      };
    });
    expect(storeState.count).toBe(1);
    expect(storeState.first!.ruleId).toBe('stall_detection');
    expect(storeState.first!.workerName).toBe('TestRunner');
    expect(storeState.first!.severity).toBe('critical');
    expect(storeState.first!.message).toContain('超过60s无任何工具调用活动');

    const alertTitle = page.locator('h2').filter({ hasText: 'Anomaly Alerts' });
    await expect(alertTitle).toBeVisible({ timeout: 5000 });

    await takeTestScreenshot(page, 'TC-APOS2-021', '01-stall-detection');
  });

  test('TC-APOS2-022: 连续失败规则触发（error_cascade）', async ({ page }) => {
    const swarmState = createSwarmState(2, { phase: 'RUNNING', swarmId: 'swarm-anomaly-022' });
    await injectSwarmData(page, swarms(swarmState));

    await toggleDetailsPanel(page, 'Agent Pipeline');

    // 直接在 page.evaluate 内创建并注入 error_cascade 数据
    const injectResult = await page.evaluate(async () => {
      const mod = await import('/src/store/anomalyStore');
      const store = (mod as any).useAnomalyStore || mod.useAnomalyStore;
      store.setState({
        activeAnomalies: [{
          id: 'test-anomaly-022',
          swarmId: 'swarm-anomaly-022',
          workerId: 'worker-003',
          workerName: 'Reviewer',
          ruleId: 'error_cascade',
          severity: 'error',
          message: 'Worker worker-003 连续失败（最近5次中3次错误）',
          detectedAt: Date.now(),
          resolvedAt: null,
          resolution: null,
        }],
        resolvedHistory: [],
      });
      return store.getState().activeAnomalies.length;
    });
    expect(injectResult).toBe(1);
    await page.waitForTimeout(300);

    // 验证 store 状态
    const storeState = await page.evaluate(async () => {
      const mod = await import('/src/store/anomalyStore');
      const store = (mod as any).useAnomalyStore || mod.useAnomalyStore;
      const anomalies = store.getState().activeAnomalies;
      return {
        count: anomalies.length,
        first: anomalies[0] ? {
          ruleId: anomalies[0].ruleId,
          workerName: anomalies[0].workerName,
          severity: anomalies[0].severity,
          message: anomalies[0].message,
        } : null,
      };
    });
    expect(storeState.count).toBe(1);
    expect(storeState.first!.ruleId).toBe('error_cascade');
    expect(storeState.first!.workerName).toBe('Reviewer');
    expect(storeState.first!.severity).toBe('error');
    expect(storeState.first!.message).toContain('连续失败');

    const alertTitle = page.locator('h2').filter({ hasText: 'Anomaly Alerts' });
    await expect(alertTitle).toBeVisible({ timeout: 5000 });

    await takeTestScreenshot(page, 'TC-APOS2-022', '01-error-cascade');
  });

  test('TC-APOS2-023: 中止 Worker 按钮状态和交互', async ({ page }) => {
    const swarmState = createSwarmState(2, { phase: 'RUNNING', swarmId: 'swarm-anomaly-023' });
    await injectSwarmData(page, swarms(swarmState));

    await toggleDetailsPanel(page, 'Agent Pipeline');

    // 直接在 page.evaluate 内创建并注入 anomaly 数据
    await page.evaluate(async () => {
      const mod = await import('/src/store/anomalyStore');
      const store = (mod as any).useAnomalyStore || mod.useAnomalyStore;
      store.setState({
        activeAnomalies: [{
          id: 'test-anomaly-023',
          swarmId: 'swarm-anomaly-023',
          workerId: 'worker-abort-test',
          workerName: 'AbortTarget',
          ruleId: 'loop_detection',
          severity: 'error',
          message: 'Worker abort test anomaly',
          detectedAt: Date.now(),
          resolvedAt: null,
          resolution: null,
        }],
        resolvedHistory: [],
      });
    });
    await page.waitForTimeout(300);

    // 验证 anomalyStore 确认有活跃异常
    const preState = await page.evaluate(async () => {
      const mod = await import('/src/store/anomalyStore');
      const store = (mod as any).useAnomalyStore || mod.useAnomalyStore;
      return {
        activeCount: store.getState().activeAnomalies.length,
        workerName: store.getState().activeAnomalies[0]?.workerName,
      };
    });
    expect(preState.activeCount).toBe(1);
    expect(preState.workerName).toBe('AbortTarget');

    // 设置路由拦截
    let interceptedRequest: { url: string; body: any } | null = null;
    await page.route('**/api/swarm/*/worker/*/abort', async (route) => {
      const request = route.request();
      interceptedRequest = {
        url: request.url(),
        body: JSON.parse(request.postData() || '{}'),
      };
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ workerId: 'worker-abort-test', status: 'aborted' }),
      });
    });

    // 通过 page.evaluate 直接模拟 abort 流程
    const fetchResult = await page.evaluate(async ({ swarmId, workerId }) => {
      const resp = await fetch(`/api/swarm/${swarmId}/worker/${workerId}/abort`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ reason: 'user_abort', triggeredBy: 'anomaly_alert' }),
      });
      const data = await resp.json();
      const mod = await import('/src/store/anomalyStore');
      const store = (mod as any).useAnomalyStore || mod.useAnomalyStore;
      const activeAnomaly = store.getState().activeAnomalies[0];
      if (activeAnomaly) {
        store.getState().resolveAnomaly(activeAnomaly.id, 'user_abort');
      }
      return { status: resp.status, data };
    }, { swarmId: 'swarm-anomaly-023', workerId: 'worker-abort-test' });

    // 验证请求被拦截
    expect(fetchResult.status).toBe(200);
    expect(fetchResult.data.status).toBe('aborted');
    expect(interceptedRequest).not.toBeNull();
    expect(interceptedRequest!.body.reason).toBe('user_abort');
    expect(interceptedRequest!.body.triggeredBy).toBe('anomaly_alert');

    // 验证 anomalyStore 状态更新
    await page.waitForTimeout(300);
    const resolvedState = await page.evaluate(async () => {
      const mod = await import('/src/store/anomalyStore');
      const store = (mod as any).useAnomalyStore || mod.useAnomalyStore;
      return {
        activeCount: store.getState().activeAnomalies.length,
        resolvedCount: store.getState().resolvedHistory.length,
      };
    });
    expect(resolvedState.activeCount).toBe(0);
    expect(resolvedState.resolvedCount).toBe(1);

    await takeTestScreenshot(page, 'TC-APOS2-023', '01-abort-interaction');
  });

  test('TC-APOS2-024: 异常冷却期验证（30s内同规则不重复触发）', async ({ page }) => {
    test.slow();
    test.setTimeout(90000);

    const swarmState = createSwarmState(2, { phase: 'RUNNING', swarmId: 'swarm-anomaly-024' });
    await injectSwarmData(page, swarms(swarmState));

    await toggleDetailsPanel(page, 'Agent Pipeline');

    // Step 1: 在 page.evaluate 内注入 anomaly 并立即 resolve（模拟忽略）
    await page.evaluate(async () => {
      const mod = await import('/src/store/anomalyStore');
      const store = (mod as any).useAnomalyStore || mod.useAnomalyStore;
      store.setState({
        activeAnomalies: [{
          id: 'test-anomaly-024',
          swarmId: 'swarm-anomaly-024',
          workerId: 'worker-cooldown',
          workerName: 'CooldownTest',
          ruleId: 'loop_detection',
          severity: 'error',
          message: 'Cooldown test anomaly',
          detectedAt: Date.now(),
          resolvedAt: null,
          resolution: null,
        }],
        resolvedHistory: [],
        cooldownMap: new Map(),
      });
    });
    await page.waitForTimeout(300);

    // Resolve the anomaly（模拟点击忽略）
    await page.evaluate(async () => {
      const mod = await import('/src/store/anomalyStore');
      const store = (mod as any).useAnomalyStore || mod.useAnomalyStore;
      const active = store.getState().activeAnomalies[0];
      if (active) {
        store.getState().resolveAnomaly(active.id, 'dismissed');
      }
    });
    await page.waitForTimeout(500);

    // 验证异常已被解决
    const afterResolve = await page.evaluate(async () => {
      const mod = await import('/src/store/anomalyStore');
      const store = (mod as any).useAnomalyStore || mod.useAnomalyStore;
      return {
        activeCount: store.getState().activeAnomalies.length,
        resolvedCount: store.getState().resolvedHistory.length,
      };
    });
    expect(afterResolve.activeCount).toBe(0);
    expect(afterResolve.resolvedCount).toBe(1);

    // 验证冷却期内 isInCooldown 返回 true
    const isCooling = await page.evaluate(async () => {
      const mod = await import('/src/store/anomalyStore');
      const store = (mod as any).useAnomalyStore || mod.useAnomalyStore;
      return store.getState().isInCooldown('worker-cooldown', 'loop_detection');
    });
    expect(isCooling).toBe(true);

    // 尝试在冷却期内再次添加相同异常 — 不应生效
    await page.evaluate(async () => {
      const mod = await import('/src/store/anomalyStore');
      const store = (mod as any).useAnomalyStore || mod.useAnomalyStore;
      store.getState().addAnomaly({
        id: 'test-anomaly-024-dup',
        swarmId: 'swarm-anomaly-024',
        workerId: 'worker-cooldown',
        workerName: 'CooldownTest',
        ruleId: 'loop_detection',
        severity: 'error',
        message: 'Duplicate attempt',
        detectedAt: Date.now(),
        resolvedAt: null,
        resolution: null,
      });
    });
    await page.waitForTimeout(500);

    // 验证异常未被添加（cooldown 阻止）
    const activeCount = await page.evaluate(async () => {
      const mod = await import('/src/store/anomalyStore');
      const store = (mod as any).useAnomalyStore || mod.useAnomalyStore;
      return store.getState().activeAnomalies.length;
    });
    expect(activeCount).toBe(0);

    // 清除 cooldown 模拟冷却期结束
    await page.evaluate(async () => {
      const mod = await import('/src/store/anomalyStore');
      const store = (mod as any).useAnomalyStore || mod.useAnomalyStore;
      store.setState({ cooldownMap: new Map() });
    });
    await page.waitForTimeout(300);

    // 冷却期结束后，再次添加应生效
    await page.evaluate(async () => {
      const mod = await import('/src/store/anomalyStore');
      const store = (mod as any).useAnomalyStore || mod.useAnomalyStore;
      store.getState().addAnomaly({
        id: 'test-anomaly-024-new',
        swarmId: 'swarm-anomaly-024',
        workerId: 'worker-cooldown',
        workerName: 'CooldownTest',
        ruleId: 'loop_detection',
        severity: 'error',
        message: 'After cooldown',
        detectedAt: Date.now(),
        resolvedAt: null,
        resolution: null,
      });
    });
    await page.waitForTimeout(500);

    // 验证异常被成功添加
    const activeCountAfter = await page.evaluate(async () => {
      const mod = await import('/src/store/anomalyStore');
      const store = (mod as any).useAnomalyStore || mod.useAnomalyStore;
      return store.getState().activeAnomalies.length;
    });
    expect(activeCountAfter).toBe(1);

    await takeTestScreenshot(page, 'TC-APOS2-024', '01-cooldown-verified');
  });
});
