import { test, expect } from '@playwright/test';
import {
  waitForAppReady,
  injectSwarmData,
  injectAnomalyData,
  injectChangeImpactData,
  injectFeatureFlags,
  takeTestScreenshot,
} from './helpers/apos2-helpers';
import {
  createSwarmState,
  createAnomalyEvent,
  createChangeImpactState,
  createPhase2Flags,
} from './helpers/apos2-data-factory';

/**
 * APOS Phase 2 — 移动端适配和响应式布局 E2E 测试
 * TC-APOS2-029 ~ TC-APOS2-037
 */

// E2E 运行时 Vite 会正确解析模块路径，standalone tsc 类型兼容性由此辅助函数解决
const flags = (f: ReturnType<typeof createPhase2Flags>) => f as unknown as Record<string, boolean>;
const swarms = (s: ReturnType<typeof createSwarmState>) => ({
  swarms: s.swarms as unknown as Array<{ swarmId: string; [key: string]: unknown }>,
  activeSwarmId: s.activeSwarmId,
  panelVisible: s.panelVisible,
});
const impact = (s: ReturnType<typeof createChangeImpactState>) => ({
  aggregatedChanges: s.aggregatedChanges as unknown[],
  riskSummary: s.riskSummary as unknown as Record<string, unknown>,
  selectedFilePath: s.selectedFilePath,
});

const VIEWPORTS = {
  mobile: { width: 393, height: 852 },   // iPhone 14 Pro
  tablet: { width: 800, height: 1024 },  // iPad Mini
  desktop: { width: 1280, height: 800 }, // Standard desktop
};

test.describe('APOS Phase 2 - Mobile Responsive (TC-APOS2-029~037)', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/');
    await waitForAppReady(page);
  });

  // ────────────────────────────────────────────────────
  // TC-APOS2-029: 响应式断点切换
  // ────────────────────────────────────────────────────
  test('TC-APOS2-029: 响应式断点切换', async ({ page }) => {
    // 确保 Feature Flags 启用
    await injectFeatureFlags(page, flags(createPhase2Flags()));

    // 1. Desktop viewport (>= 1024px) — 桌面 Sidebar 可见, MobileStatusBar 不可见
    await page.setViewportSize(VIEWPORTS.desktop);
    await page.waitForTimeout(500);

    // 桌面模式下，Desktop Sidebar 应渲染（!isMobile 时）
    const desktopSidebar = page.locator('aside, [class*="shrink-0"]').first();
    await expect(desktopSidebar).toBeVisible({ timeout: 5000 });

    // MobileStatusBar 在桌面不应渲染
    const mobileStatusBar = page.locator('button[aria-label="展开状态详情"]');
    await expect(mobileStatusBar).toHaveCount(0);

    // 2. Compact viewport (768-1023px) — §8.1 断点统一后属 compact（isMobile=false）：
    //    保留桌面侧栏，MobileStatusBar 不可见（断言自旧 <1024 口径反转）
    await page.setViewportSize(VIEWPORTS.tablet);
    await page.waitForTimeout(500);

    await expect(mobileStatusBar).toHaveCount(0);
    await expect(desktopSidebar).toBeVisible({ timeout: 5000 });

    // 3. Mobile viewport (<= 767px) — 纯移动模式
    await page.setViewportSize(VIEWPORTS.mobile);
    await page.waitForTimeout(500);

    // MobileStatusBar 应可见
    await expect(mobileStatusBar).toBeVisible({ timeout: 5000 });

    // 4. 临界值验证：1024px → 桌面模式
    await page.setViewportSize({ width: 1024, height: 800 });
    await page.waitForTimeout(500);
    await expect(mobileStatusBar).toHaveCount(0);

    // 5. 临界值验证：1023px → compact 上限（isMobile=false，MobileStatusBar 不可见）
    await page.setViewportSize({ width: 1023, height: 800 });
    await page.waitForTimeout(500);
    await expect(mobileStatusBar).toHaveCount(0);

    // 6. 临界值验证：767px → mobile 上限（MobileStatusBar 可见）
    await page.setViewportSize({ width: 767, height: 800 });
    await page.waitForTimeout(500);
    await expect(mobileStatusBar).toBeVisible({ timeout: 5000 });

    // 7. 临界值验证：768px → compact 下限（isMobile=false，MobileStatusBar 不可见）
    await page.setViewportSize({ width: 768, height: 800 });
    await page.waitForTimeout(500);
    await expect(mobileStatusBar).toHaveCount(0);

    await takeTestScreenshot(page, 'TC-APOS2-029', '01-responsive-breakpoints');
  });

  // ────────────────────────────────────────────────────
  // TC-APOS2-030: MobileStatusBar 固定底部展示
  // ────────────────────────────────────────────────────
  test('TC-APOS2-030: MobileStatusBar 固定底部展示', async ({ page }) => {
    // §8.7 移动视口由 mobile project（393×852）承担，移除临时 setViewportSize

    // 启用所有相关 Feature Flags
    await injectFeatureFlags(page, flags(createPhase2Flags()));

    // 注入 Swarm 数据使 StatusBar 有内容
    const swarmState = createSwarmState(3);
    await injectSwarmData(page, swarms(swarmState));

    // 验证 MobileStatusBar 可见
    const statusBar = page.locator('button[aria-label="展开状态详情"]');
    await expect(statusBar).toBeVisible({ timeout: 5000 });

    // 验证固定定位（fixed bottom-0）— 检查父容器的 CSS
    const statusBarContainer = page.locator('.fixed.bottom-0.left-0.right-0.z-50');
    await expect(statusBarContainer).toBeVisible({ timeout: 3000 });

    // 验证背景样式（§8.5 令牌化：bg-[#12121a] → bg-surfacev2/95）
    await expect(statusBarContainer).toHaveClass(/bg-surfacev2/);
    await expect(statusBarContainer).toHaveClass(/backdrop-blur-sm/);

    // 验证 z-index 层级（z-50）
    await expect(statusBarContainer).toHaveClass(/z-50/);

    await takeTestScreenshot(page, 'TC-APOS2-030', '01-fixed-bottom');
  });

  // ────────────────────────────────────────────────────
  // TC-APOS2-031: MobileStatusBar Pipeline 摘要信息展示
  // ────────────────────────────────────────────────────
  test('TC-APOS2-031: MobileStatusBar Pipeline 摘要信息展示', async ({ page }) => {
    // §8.7 移动视口由 mobile project（393×852）承担，移除临时 setViewportSize
    await injectFeatureFlags(page, flags(createPhase2Flags()));

    // 场景 1：无活跃 Swarm 时显示 "无活动 Pipeline"
    const noActivityText = page.getByText('无活动 Pipeline');
    await expect(noActivityText).toBeVisible({ timeout: 5000 });

    // 场景 2：注入 Swarm 数据后，显示 MobilePipelineSummary
    const swarmState = createSwarmState(4);
    await injectSwarmData(page, swarms(swarmState));

    // "无活动 Pipeline" 应消失
    await expect(noActivityText).not.toBeVisible({ timeout: 3000 });

    // 应显示 Worker 状态圆点（w-2.5 h-2.5 rounded-full）
    const workerDots = page.locator('span.inline-block.rounded-full[class*="w-2.5"][class*="h-2.5"]');
    await expect(workerDots).toHaveCount(4, { timeout: 5000 });

    // 应显示进度文本 "N/M 完成"
    const progressText = page.locator('text=/\\d+\\/\\d+ 完成/');
    await expect(progressText).toBeVisible({ timeout: 3000 });

    await takeTestScreenshot(page, 'TC-APOS2-031', '01-pipeline-summary');
  });

  // ────────────────────────────────────────────────────
  // TC-APOS2-032: MobileStatusBar 异常计数徽章
  // ────────────────────────────────────────────────────
  test('TC-APOS2-032: MobileStatusBar 异常计数徽章', async ({ page }) => {
    // §8.7 移动视口由 mobile project（393×852）承担，移除临时 setViewportSize
    await injectFeatureFlags(page, flags(createPhase2Flags()));

    // 初始无异常 — 徽章不显示（§8.5 令牌化：bg-red-500/20 text-red-300 → errsoft/errstrong）
    const anomalyBadge = page.locator('.bg-errsoft.text-errstrong');
    await expect(anomalyBadge).toHaveCount(0);

    // 注入异常数据
    await injectAnomalyData(page, {
      activeAnomalies: [
        createAnomalyEvent('loop_detection', { workerId: 'worker-001', workerName: 'CodeWriter' }),
        createAnomalyEvent('stall_detection', { workerId: 'worker-002', workerName: 'TestRunner' }),
      ],
      resolvedHistory: [],
    });

    // 异常徽章应出现，显示数量 "2"
    await expect(anomalyBadge).toBeVisible({ timeout: 5000 });
    await expect(anomalyBadge).toContainText('2');

    // 验证 AlertTriangle 图标存在（lucide svg）
    const alertIcon = anomalyBadge.locator('svg');
    await expect(alertIcon).toBeVisible();

    // 追加 1 个异常，验证计数更新为 "3"
    await injectAnomalyData(page, {
      activeAnomalies: [
        createAnomalyEvent('loop_detection', { workerId: 'worker-001', workerName: 'CodeWriter' }),
        createAnomalyEvent('stall_detection', { workerId: 'worker-002', workerName: 'TestRunner' }),
        createAnomalyEvent('error_cascade', { workerId: 'worker-003', workerName: 'Reviewer' }),
      ],
      resolvedHistory: [],
    });

    await expect(anomalyBadge).toContainText('3', { timeout: 3000 });

    await takeTestScreenshot(page, 'TC-APOS2-032', '01-anomaly-badge');
  });

  // ────────────────────────────────────────────────────
  // TC-APOS2-033: MobileStatusBar 展开/收起交互
  // ────────────────────────────────────────────────────
  test('TC-APOS2-033: MobileStatusBar 展开/收起交互', async ({ page }) => {
    // §8.7 移动视口由 mobile project（393×852）承担，移除临时 setViewportSize
    await injectFeatureFlags(page, flags(createPhase2Flags()));

    // 注入变更影响数据使 sheet 有内容
    const impactState = createChangeImpactState(6, {
      withHighRisk: true,
      withTestGap: true,
    });
    await injectChangeImpactData(page, impact(impactState));

    // §8.4 接线后：点击状态细条展开 Bottom Sheet（旧内联展开面板已移除）
    const sheet = page.locator('[role="dialog"][aria-label="状态详情"]');
    await expect(sheet).toHaveCount(0);

    // 点击状态条展开 sheet
    const statusButton = page.locator('button[aria-label="展开状态详情"]');
    await statusButton.click();

    // sheet 应可见
    await expect(sheet).toBeVisible({ timeout: 5000 });

    // 验证 sheet 包含 "高风险文件" 标题
    const panelTitle = page.getByText('高风险文件');
    await expect(panelTitle).toBeVisible({ timeout: 3000 });

    // 验证 ChevronUp 图标旋转（添加 rotate-180）
    const chevron = page.locator('svg.rotate-180');
    await expect(chevron).toBeVisible({ timeout: 3000 });

    // Esc 关闭 sheet（§8.4 关闭方式之一）
    await page.keyboard.press('Escape');
    await page.waitForTimeout(500);

    // sheet 应不可见
    await expect(sheet).toHaveCount(0);

    // 焦点归还触发器（§10.7-④）
    await expect(statusButton).toBeFocused({ timeout: 3000 });

    await takeTestScreenshot(page, 'TC-APOS2-033', '01-expand-collapse');
  });

  // ────────────────────────────────────────────────────
  // TC-APOS2-034: MobilePipelineSummary Worker 状态统计
  // ────────────────────────────────────────────────────
  test('TC-APOS2-034: MobilePipelineSummary Worker 状态统计', async ({ page }) => {
    // §8.7 移动视口由 mobile project（393×852）承担，移除临时 setViewportSize
    await injectFeatureFlags(page, flags(createPhase2Flags()));

    // 注入 4 个 Worker（状态循环：WORKING, STARTING, IDLE, TERMINATED）
    const swarmState = createSwarmState(4);
    await injectSwarmData(page, swarms(swarmState));

    // 验证 Worker 圆点数量
    const workerDots = page.locator('span.inline-block.rounded-full[class*="w-2.5"][class*="h-2.5"]');
    await expect(workerDots).toHaveCount(4, { timeout: 5000 });

    // 验证各状态圆点颜色
    // WORKING (worker-001) → bg-blue-500 animate-pulse
    const workingDot = workerDots.filter({ has: page.locator('[class*="bg-blue-500"]') });
    // 使用 class 属性验证
    const firstDot = workerDots.nth(0);
    const firstDotClass = await firstDot.getAttribute('class');
    // Worker-001 应为 WORKING（蓝色 + 脉冲）
    expect(firstDotClass).toContain('bg-blue-500');
    expect(firstDotClass).toContain('animate-pulse');

    // STARTING (worker-002) → bg-gray-400
    const secondDot = workerDots.nth(1);
    const secondDotClass = await secondDot.getAttribute('class');
    expect(secondDotClass).toContain('bg-gray-400');

    // IDLE (worker-003) → bg-yellow-400
    const thirdDot = workerDots.nth(2);
    const thirdDotClass = await thirdDot.getAttribute('class');
    expect(thirdDotClass).toContain('bg-yellow-400');

    // TERMINATED (worker-004) → bg-red-500 (error) or bg-green-500 (completed)
    const fourthDot = workerDots.nth(3);
    const fourthDotClass = await fourthDot.getAttribute('class');
    // createSwarmState with 4 workers: last TERMINATED worker has error reason
    expect(fourthDotClass).toContain('bg-red-500');

    // 验证进度文本格式 "N/M 完成"
    // IDLE worker counts as completed with terminationReason='completed' in createSwarmState
    const progressText = page.locator('text=/\\d+\\/4 完成/');
    await expect(progressText).toBeVisible({ timeout: 3000 });

    await takeTestScreenshot(page, 'TC-APOS2-034', '01-worker-status');
  });

  // ────────────────────────────────────────────────────
  // TC-APOS2-035: MobileImpactList 文件路径截断
  // ────────────────────────────────────────────────────
  test('TC-APOS2-035: MobileImpactList 文件路径截断', async ({ page }) => {
    // §8.7 移动视口由 mobile project（393×852）承担，移除临时 setViewportSize
    await injectFeatureFlags(page, flags(createPhase2Flags()));

    // 注入含长路径的变更影响数据
    const impactState = createChangeImpactState(6, {
      withHighRisk: true,
      withTestGap: true,
      withIndirectImpact: true,
    });
    await injectChangeImpactData(page, impact(impactState));

    // 展开 MobileStatusBar 的 Bottom Sheet（§8.4 接线后 MobileImpactList 位于 sheet 内）
    const statusButton = page.locator('button[aria-label="展开状态详情"]');
    await statusButton.click();
    const sheet = page.locator('[role="dialog"][aria-label="状态详情"]');
    await expect(sheet).toBeVisible({ timeout: 5000 });

    // 验证 MobileImpactList 渲染了文件列表项
    const fileItems = page.locator('.flex.items-center.gap-2.rounded-lg');
    await expect(fileItems.first()).toBeVisible({ timeout: 5000 });

    // 验证文件路径使用截断（truncate class，只显示文件名最后一级）
    const fileNameElements = page.locator('.truncate.font-mono');
    const firstFileName = await fileNameElements.first().textContent();
    // MobileImpactList 使用 filePath.split('/').pop() 只取最后一级
    expect(firstFileName).not.toContain('/');

    // 验证 Top 5 限制（注入了 6 个文件，应只显示 5 个）
    const listItems = page.locator('.flex.flex-col.gap-2 > .flex.items-center.gap-2.rounded-lg');
    await expect(listItems).toHaveCount(5, { timeout: 3000 });

    // 验证 "查看全部" 按钮可见（因有 6 个文件 > 5）
    const viewAllButton = page.getByText(/查看全部 \(\d+\)/);
    await expect(viewAllButton).toBeVisible({ timeout: 3000 });

    // 验证风险色标（第一个应为 danger → bg-red-500）
    const firstRiskDot = listItems.first().locator('span.rounded-full[class*="w-2.5"]');
    const riskDotClass = await firstRiskDot.getAttribute('class');
    expect(riskDotClass).toContain('bg-red-500');

    // 验证 touchCount 显示
    const touchCountBadge = listItems.first().locator('text=/×\\d+/');
    await expect(touchCountBadge).toBeVisible({ timeout: 3000 });

    await takeTestScreenshot(page, 'TC-APOS2-035', '01-path-truncation');
  });

  // ────────────────────────────────────────────────────
  // TC-APOS2-036: MobileBottomSheet 拖拽关闭
  // ────────────────────────────────────────────────────
  test('TC-APOS2-036: MobileBottomSheet 拖拽关闭', async ({ page }) => {
    // §8.7 移动视口由 mobile project（393×852）承担，移除临时 setViewportSize
    await injectFeatureFlags(page, flags(createPhase2Flags()));

    // 注入 Activity 数据（§8.4 接线后：MobileStatusBar 点击展开 sheet 的详情对象）
    // 经 window.__e2eStores 读写应用同一 store 实例（规避 vite HMR ?t= 实例分裂）
    await page.evaluate(() => {
      const store = (window as any).__e2eStores.activityStore;
      const activities = new Map();
      activities.set('test-activity-drag', {
        id: 'test-activity-drag',
        type: 'tool_execution',
        sessionId: 'default',
        operationType: 'file_edit',
        summary: 'Drag Test Activity',
        toolName: 'write_to_file',
        timestamp: Date.now(),
        changedFiles: [
          { filePath: 'src/components/Test.tsx', changeType: 'modified', path: 'src/components/Test.tsx', additions: 5, deletions: 2 },
        ],
        fileCount: 1,
        duration: 1000,
        insight: { signal: 'review_recommended', riskLevel: 'low', summary: 'Test', factors: [], suggestions: [], verificationStatus: 'passed' },
        decision: null,
        status: 'completed',
      });
      store.setState({ activities });
    });
    // 设置 sessionId 以匹配 Activity 过滤（经 window.__e2eStores 同一实例）
    await page.evaluate(() => {
      (window as any).__e2eStores.sessionStore.setState({ sessionId: 'default' });
    });
    await page.waitForTimeout(500);

    // §8.4 接线：点击 MobileStatusBar 状态细条展开 Bottom Sheet（grabber 36×4 + overlay2 遮罩）
    const statusButton = page.locator('button[aria-label="展开状态详情"]');
    await expect(statusButton).toBeVisible({ timeout: 5000 });
    await statusButton.click();

    const sheet = page.locator('[role="dialog"][aria-label="状态详情"]');
    await expect(sheet).toBeVisible({ timeout: 5000 });
    await expect(sheet).toContainText('Drag Test Activity');

    const overlay = sheet.locator('div.bg-overlay2');
    await expect(overlay).toBeVisible();
    const dragHandle = sheet.locator('div.h-1.w-9.rounded-full');
    await expect(dragHandle).toBeVisible({ timeout: 3000 });

    // 短距慢速下拉（位移 <25% 面板高度且速度 <500px/s）→ 回弹不关闭
    const handleBox = await dragHandle.boundingBox();
    expect(handleBox).not.toBeNull();
    const startX = handleBox!.x + handleBox!.width / 2;
    const startY = handleBox!.y + handleBox!.height / 2;
    await page.mouse.move(startX, startY);
    await page.mouse.down();
    await page.mouse.move(startX, startY + 40, { steps: 10 });
    await page.waitForTimeout(200); // 静置使拖拽速度归零，仅距离参与判定（40px < 25% 高度）
    await page.mouse.up();
    await page.waitForTimeout(500);

    // Sheet 仍应打开（回弹）
    await expect(sheet).toBeVisible({ timeout: 3000 });

    // 下拉 >25% 面板高度 → 关闭
    // 第一次短拖拽松手后面板会弹回，必须重新读取手柄坐标，
    // 否则第二次拖拽仍使用动画前的旧位置，实际不会命中面板。
    const resetHandleBox = await dragHandle.boundingBox();
    expect(resetHandleBox).not.toBeNull();
    const resetX = resetHandleBox!.x + resetHandleBox!.width / 2;
    const resetY = resetHandleBox!.y + resetHandleBox!.height / 2;
    await page.mouse.move(resetX, resetY);
    await page.mouse.down();
    await page.mouse.move(resetX, resetY + 320, { steps: 15 });
    await page.mouse.up();
    await page.waitForTimeout(800);

    // Sheet 应关闭（遮罩与面板一并退场）
    await expect(sheet).not.toBeVisible({ timeout: 5000 });

    await takeTestScreenshot(page, 'TC-APOS2-036', '01-drag-close');
  });

  // ────────────────────────────────────────────────────
  // TC-APOS2-037: 移动端与桌面端组件互斥显示
  // ────────────────────────────────────────────────────
  test('TC-APOS2-037: 移动端与桌面端组件互斥显示', async ({ page }) => {
    await injectFeatureFlags(page, flags(createPhase2Flags()));
    const swarmState = createSwarmState(3);
    await injectSwarmData(page, swarms(swarmState));

    // 桌面状态条定位器
    const mobileStatusBar = page.locator('button[aria-label="展开状态详情"]');
    // 桌面 Sidebar — 使用 aside 元素更精确定位，避免 .shrink-0 匹配到其他元素
    const desktopSidebar = page.locator('aside.shrink-0');

    // 1. 桌面模式：Desktop Sidebar 可见，MobileStatusBar 不可见
    await page.setViewportSize(VIEWPORTS.desktop);
    await page.waitForTimeout(600);

    await expect(mobileStatusBar).toHaveCount(0);
    // 在桌面模式 Sidebar 可见（非 Drawer）
    await expect(desktopSidebar).toBeVisible({ timeout: 5000 });

    // 2. 切换到移动模式：MobileStatusBar 可见，Desktop Sidebar 不可见
    await page.setViewportSize(VIEWPORTS.mobile);
    await page.waitForTimeout(600);

    await expect(mobileStatusBar).toBeVisible({ timeout: 5000 });
    // 桌面 Sidebar 应不再渲染（AppLayout 中 !isMobile 条件）
    await expect(desktopSidebar).toHaveCount(0);

    // 3. 切换回桌面：恢复桌面布局
    await page.setViewportSize(VIEWPORTS.desktop);
    await page.waitForTimeout(600);

    await expect(mobileStatusBar).toHaveCount(0);
    await expect(desktopSidebar).toBeVisible({ timeout: 5000 });

    // 4. Compact 模式（768-1023px）：§8.1 断点统一后 isMobile=false ——
    //    MobileStatusBar 不可见，Desktop Sidebar 保留（断言自旧 <1024 口径反转）
    await page.setViewportSize(VIEWPORTS.tablet);
    await page.waitForTimeout(600);

    await expect(mobileStatusBar).toHaveCount(0);
    // Desktop Sidebar 可见（compact 保留桌面侧栏）
    await expect(desktopSidebar).toBeVisible({ timeout: 5000 });

    await takeTestScreenshot(page, 'TC-APOS2-037', '01-mutual-exclusion');
  });
});
