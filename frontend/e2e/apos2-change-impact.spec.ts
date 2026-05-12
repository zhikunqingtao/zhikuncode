import { test, expect } from '@playwright/test';
import {
  waitForAppReady,
  navigateToAPOSTab,
  injectChangeImpactData,
  toggleDetailsPanel,
  takeTestScreenshot,
} from './helpers/apos2-helpers';
import {
  createChangeImpactState,
  createFileChangeData,
} from './helpers/apos2-data-factory';

/**
 * APOS Phase 2 — 变更影响全景面板 E2E 测试
 * TC-APOS2-001 ~ TC-APOS2-006
 */
test.describe('APOS Phase 2 - ChangeImpactPanel', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/');
    await waitForAppReady(page);
    await navigateToAPOSTab(page);
    // 展开 "变更影响全景" 折叠区域
    await toggleDetailsPanel(page, '变更影响全景');
  });

  // ────────────────────────────────────────────────────
  // TC-APOS2-001: ChangeImpactPanel 空状态展示
  // ────────────────────────────────────────────────────
  test('TC-APOS2-001: ChangeImpactPanel 空状态展示', async ({ page }) => {
    // 注入空状态数据
    await injectChangeImpactData(page, {
      aggregatedChanges: [],
      riskSummary: {
        totalFiles: 0,
        highRiskCount: 0,
        testCoverageGapCount: 0,
        indirectImpactCount: 0,
      },
      selectedFilePath: null,
    });

    // 验证空状态图标可见（SVG with opacity-40）
    const emptyIcon = page.locator('svg.opacity-40');
    await expect(emptyIcon).toBeVisible({ timeout: 5000 });

    // 验证主提示文本
    const mainText = page.getByText('暂无变更影响数据');
    await expect(mainText).toBeVisible({ timeout: 3000 });

    // 验证副提示文本
    const subText = page.getByText('当会话产生代码变更后，此处将展示聚合分析');
    await expect(subText).toBeVisible({ timeout: 3000 });

    await takeTestScreenshot(page, 'TC-APOS2-001', '01-empty-state');
  });

  // ────────────────────────────────────────────────────
  // TC-APOS2-002: 风险摘要卡片展示
  // ────────────────────────────────────────────────────
  test('TC-APOS2-002: 风险摘要卡片展示', async ({ page }) => {
    // 注入含高风险 + 测试缺口 + 间接影响的数据
    const state = createChangeImpactState(5, {
      withHighRisk: true,
      withTestGap: true,
      withIndirectImpact: true,
    });
    await injectChangeImpactData(page, state);

    // 验证 4 列网格布局存在
    const summaryGrid = page.locator('.grid.grid-cols-2');
    await expect(summaryGrid).toBeVisible({ timeout: 5000 });

    // 验证 "总文件数" 卡片 — 显示 5
    const totalFilesCard = summaryGrid.locator('div').filter({ hasText: '总文件数' });
    await expect(totalFilesCard).toBeVisible({ timeout: 3000 });
    await expect(totalFilesCard.locator('p.text-lg')).toHaveText(
      String(state.riskSummary.totalFiles),
    );

    // 验证 "高风险" 卡片 — 红色系边框
    const highRiskCard = summaryGrid.locator('div').filter({ hasText: '高风险' });
    await expect(highRiskCard).toBeVisible({ timeout: 3000 });
    await expect(highRiskCard.locator('p.text-lg')).toHaveText(
      String(state.riskSummary.highRiskCount),
    );
    // 验证红色样式 class
    const highRiskOuter = summaryGrid.locator('div.border-red-500\\/30');
    await expect(highRiskOuter).toBeVisible({ timeout: 3000 });

    // 验证 "测试缺口" 卡片 — 黄色系边框
    const testGapCard = summaryGrid.locator('div').filter({ hasText: '测试缺口' });
    await expect(testGapCard).toBeVisible({ timeout: 3000 });
    await expect(testGapCard.locator('p.text-lg')).toHaveText(
      String(state.riskSummary.testCoverageGapCount),
    );
    const testGapOuter = summaryGrid.locator('div.border-yellow-500\\/30');
    await expect(testGapOuter).toBeVisible({ timeout: 3000 });

    // 验证 "间接影响" 卡片 — 蓝色系边框
    const indirectCard = summaryGrid.locator('div').filter({ hasText: '间接影响' });
    await expect(indirectCard).toBeVisible({ timeout: 3000 });
    await expect(indirectCard.locator('p.text-lg')).toHaveText(
      String(state.riskSummary.indirectImpactCount),
    );
    const indirectOuter = summaryGrid.locator('div.border-blue-500\\/30');
    await expect(indirectOuter).toBeVisible({ timeout: 3000 });

    await takeTestScreenshot(page, 'TC-APOS2-002', '01-risk-summary');
  });

  // ────────────────────────────────────────────────────
  // TC-APOS2-003: FileChangeItem 列表按风险等级降序排列
  // ────────────────────────────────────────────────────
  test('TC-APOS2-003: FileChangeItem 列表按风险等级降序排列', async ({ page }) => {
    // 注入包含 danger + warning + safe 的数据（createChangeImpactState 会自动排序）
    const state = createChangeImpactState(5, { withHighRisk: true });
    await injectChangeImpactData(page, state);

    // 获取所有文件卡片（FileChangeItem 使用 border + rounded-md + px-3 py-2）
    const fileItems = page.locator('.flex.flex-col.gap-1\\.5 > div.rounded-md.border');
    await expect(fileItems.first()).toBeVisible({ timeout: 5000 });

    const itemCount = await fileItems.count();
    expect(itemCount).toBe(5);

    // 验证第一个文件是 danger 级别（红色背景）
    const firstItem = fileItems.nth(0);
    await expect(firstItem).toHaveClass(/bg-red-500\/10/);

    // 验证第二个文件是 warning 级别（黄色背景）
    const secondItem = fileItems.nth(1);
    await expect(secondItem).toHaveClass(/bg-yellow-500\/10/);

    // 验证最后一个文件是 safe 级别（bg-transparent）
    const lastItem = fileItems.nth(itemCount - 1);
    await expect(lastItem).toHaveClass(/bg-transparent/);

    // 验证变更类型图标（第一个文件为 modified → "~"）
    const modifiedIcon = firstItem.locator('span.font-mono');
    await expect(modifiedIcon).toHaveText('~');
    await expect(modifiedIcon).toHaveClass(/text-orange-400/);

    // 验证 touchCount 徽章（danger 文件 touchCount=4，应显示 ×4）
    const touchBadge = firstItem.locator('span').filter({ hasText: '×4' });
    await expect(touchBadge).toBeVisible({ timeout: 3000 });

    // 验证增删行数
    const additions = firstItem.locator('span.text-green-400');
    await expect(additions).toBeVisible({ timeout: 3000 });
    const deletions = firstItem.locator('span.text-red-400').first();
    await expect(deletions).toBeVisible({ timeout: 3000 });

    await takeTestScreenshot(page, 'TC-APOS2-003', '01-sorted-list');
  });

  // ────────────────────────────────────────────────────
  // TC-APOS2-004: FileChangeItem 四种风险等级背景色映射
  // ────────────────────────────────────────────────────
  test('TC-APOS2-004: FileChangeItem 四种风险等级背景色映射', async ({ page }) => {
    // 构造包含全部四种风险等级的数据
    const changes = [
      createFileChangeData({
        filePath: 'src/components/Danger.tsx',
        riskLevel: 'danger',
        touchCount: 4,
      }),
      createFileChangeData({
        filePath: 'src/services/Warning.ts',
        riskLevel: 'warning',
        touchCount: 2,
      }),
      createFileChangeData({
        filePath: 'src/store/Review.ts',
        riskLevel: 'review',
        totalAdditions: 60,
        totalDeletions: 10,
      }),
      createFileChangeData({
        filePath: 'src/utils/Safe.ts',
        riskLevel: 'safe',
        touchCount: 1,
      }),
    ];

    await injectChangeImpactData(page, {
      aggregatedChanges: changes,
      riskSummary: {
        totalFiles: 4,
        highRiskCount: 2,
        testCoverageGapCount: 0,
        indirectImpactCount: 0,
      },
      selectedFilePath: null,
    });

    const fileItems = page.locator('.flex.flex-col.gap-1\\.5 > div.rounded-md.border');
    await expect(fileItems.first()).toBeVisible({ timeout: 5000 });

    // danger: bg-red-500/10 border-red-500/30
    const dangerItem = fileItems.nth(0);
    await expect(dangerItem).toHaveClass(/bg-red-500\/10/);
    await expect(dangerItem).toHaveClass(/border-red-500\/30/);

    // warning: bg-yellow-500/10 border-yellow-500/30
    const warningItem = fileItems.nth(1);
    await expect(warningItem).toHaveClass(/bg-yellow-500\/10/);
    await expect(warningItem).toHaveClass(/border-yellow-500\/30/);

    // review: bg-blue-500/10 border-blue-500/30
    const reviewItem = fileItems.nth(2);
    await expect(reviewItem).toHaveClass(/bg-blue-500\/10/);
    await expect(reviewItem).toHaveClass(/border-blue-500\/30/);

    // safe: bg-transparent border-[var(--border)]
    const safeItem = fileItems.nth(3);
    await expect(safeItem).toHaveClass(/bg-transparent/);

    await takeTestScreenshot(page, 'TC-APOS2-004', '01-risk-colors');
  });

  // ────────────────────────────────────────────────────
  // TC-APOS2-005: 间接影响展开/收起交互
  // ────────────────────────────────────────────────────
  test('TC-APOS2-005: 间接影响展开/收起交互', async ({ page }) => {
    // 注入包含间接影响的数据（第一个文件有 3 个间接影响）
    const state = createChangeImpactState(3, {
      withHighRisk: true,
      withIndirectImpact: true,
    });
    await injectChangeImpactData(page, state);

    // 找到间接影响展开按钮 — "▸ 间接影响 (3)"
    const expandBtn = page.locator('button').filter({ hasText: /间接影响 \(3\)/ });
    await expect(expandBtn).toBeVisible({ timeout: 5000 });

    // 初始状态：间接影响列表不可见
    const impactList = page.locator('ul.space-y-0\\.5');
    await expect(impactList).not.toBeVisible();

    // 步骤2: 点击展开
    await expandBtn.click();
    await page.waitForTimeout(300);

    // 验证列表展开 — 列表可见
    await expect(impactList).toBeVisible({ timeout: 3000 });

    // 按钮文本变为 "▾ 间接影响 (3)"
    await expect(expandBtn).toContainText('▾');

    // 验证间接影响列表项数量
    const impactItems = impactList.locator('li');
    await expect(impactItems).toHaveCount(3);

    // 验证严重性颜色映射
    // high → bg-red-400, medium → bg-yellow-400, low → bg-blue-400
    const severityDots = impactList.locator('span.rounded-full');
    await expect(severityDots.nth(0)).toHaveClass(/bg-red-400/);
    await expect(severityDots.nth(1)).toHaveClass(/bg-yellow-400/);
    await expect(severityDots.nth(2)).toHaveClass(/bg-blue-400/);

    await takeTestScreenshot(page, 'TC-APOS2-005', '01-expanded');

    // 步骤4: 再次点击收起
    await expandBtn.click();
    await page.waitForTimeout(300);

    // 验证列表收起
    await expect(impactList).not.toBeVisible();
    await expect(expandBtn).toContainText('▸');

    await takeTestScreenshot(page, 'TC-APOS2-005', '02-collapsed');
  });

  // ────────────────────────────────────────────────────
  // TC-APOS2-006: 测试覆盖缺口标签
  // ────────────────────────────────────────────────────
  test('TC-APOS2-006: 测试覆盖缺口标签', async ({ page }) => {
    // 注入包含 testCoverageGap=true 的数据
    const state = createChangeImpactState(5, {
      withHighRisk: true,
      withTestGap: true,
    });
    await injectChangeImpactData(page, state);

    // 验证 "缺少测试覆盖" 标签可见（testCoverageGap=true 的前 2 个文件）
    const testGapLabels = page.locator('span').filter({ hasText: '缺少测试覆盖' });
    await expect(testGapLabels.first()).toBeVisible({ timeout: 5000 });

    // 验证标签数量 = testCoverageGapCount（前2个文件有 testCoverageGap）
    await expect(testGapLabels).toHaveCount(state.riskSummary.testCoverageGapCount);

    // 验证标签样式：bg-red-500/20 text-red-300
    const firstLabel = testGapLabels.first();
    await expect(firstLabel).toHaveClass(/bg-red-500\/20/);
    await expect(firstLabel).toHaveClass(/text-red-300/);

    // 验证无 testCoverageGap 的文件不显示标签
    const fileItems = page.locator('.flex.flex-col.gap-1\\.5 > div.rounded-md.border');
    const lastItem = fileItems.nth(4); // 第5个文件无 testCoverageGap
    const lastItemLabel = lastItem.locator('span').filter({ hasText: '缺少测试覆盖' });
    await expect(lastItemLabel).toHaveCount(0);

    await takeTestScreenshot(page, 'TC-APOS2-006', '01-test-gap-labels');
  });
});
