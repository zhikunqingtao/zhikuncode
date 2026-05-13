import { test, expect } from '@playwright/test';
import {
  waitForAppReady,
  navigateToAPOSTab,
  expandActivityCard,
  getSignalBadge,
  injectActivityData,
  injectFeatureFlags,
  setBatchMode,
  createMockActivities,
  takeTestScreenshot,
} from './helpers/apos1-helpers';

/**
 * APOS Phase 1 — 核心功能 E2E 测试
 * TC-APOS-001 ~ TC-APOS-015
 */

// ══════════════════════════════════════════════════════════════
// A. APOS Tab 基础功能 (TC-001 ~ TC-003)
// ══════════════════════════════════════════════════════════════

test.describe('A. APOS Tab 基础功能', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/');
    await waitForAppReady(page);
  });

  test('TC-APOS-001: APOS Activity Tab 在 Sidebar 中正确显示', async ({ page }) => {
    // 验证 Activity Tab 按钮存在
    const activityTab = page.locator('button[title="Activity"]');
    await expect(activityTab).toBeVisible({ timeout: 10000 });

    // 点击 Activity Tab
    await activityTab.click();
    await page.waitForTimeout(800);

    // 验证 ActivityStream 面板出现（空状态或有数据）
    const streamPanel = page.locator('.flex.flex-col.min-h-0.overflow-hidden');
    await expect(streamPanel).toBeVisible({ timeout: 5000 });

    await takeTestScreenshot(page, 'TC-APOS-001', '01-activity-tab');
  });

  test('TC-APOS-002: Feature Flag 控制 APOS Tab 显示/隐藏', async ({ page }) => {
    // 确认 Activity Tab 初始可见
    const activityTab = page.locator('button[title="Activity"]');
    await expect(activityTab).toBeVisible({ timeout: 10000 });

    // 通过 Store 关闭 APOS_ACTIVITY_STREAM
    await injectFeatureFlags(page, { APOS_ACTIVITY_STREAM: false });
    await page.waitForTimeout(500);

    // Activity Tab 应消失
    await expect(activityTab).not.toBeVisible({ timeout: 5000 });

    await takeTestScreenshot(page, 'TC-APOS-002', '01-tab-hidden');

    // 重新开启
    await injectFeatureFlags(page, { APOS_ACTIVITY_STREAM: true });
    await page.waitForTimeout(500);

    // Activity Tab 应重新出现
    await expect(activityTab).toBeVisible({ timeout: 5000 });

    await takeTestScreenshot(page, 'TC-APOS-002', '02-tab-restored');
  });

  test('TC-APOS-003: Mock 数据加载验证', async ({ page }) => {
    await navigateToAPOSTab(page);

    // 注入 Mock Activity 数据
    const mockActivities = createMockActivities();
    await injectActivityData(page, mockActivities);

    // 等待卡片渲染
    const cards = page.locator('[data-testid="activity-card-l1"]');
    await expect(cards.first()).toBeVisible({ timeout: 10000 });

    // 验证总共有 6 条
    const count = await cards.count();
    expect(count).toBe(6);

    // 验证第一条（最新）是 mock-activity-006
    const firstCard = cards.first();
    await expect(firstCard).toContainText('新增 WebSocket verify_progress 消息类型');

    // 验证最后一条是 mock-activity-001
    const lastCard = cards.nth(5);
    await expect(lastCard).toContainText('修复 typo in README.md');

    await takeTestScreenshot(page, 'TC-APOS-003', '01-mock-loaded');
  });
});

// ══════════════════════════════════════════════════════════════
// B. ActivityCard 三层展示 (TC-004 ~ TC-007)
// ══════════════════════════════════════════════════════════════

test.describe('B. ActivityCard 三层展示', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/');
    await waitForAppReady(page);
    await navigateToAPOSTab(page);
    // 注入 Mock 数据
    const mockActivities = createMockActivities();
    await injectActivityData(page, mockActivities);
    await page.waitForTimeout(500);
  });

  test('TC-APOS-004: L1 紧凑卡片正确展示', async ({ page }) => {
    const cards = page.locator('[data-testid="activity-card-l1"]');
    await expect(cards.first()).toBeVisible({ timeout: 5000 });

    // 验证卡片高度为 52px
    const firstCard = cards.first();
    await expect(firstCard).toHaveClass(/h-\[52px\]/);

    // 验证包含摘要文本
    await expect(firstCard).toContainText('新增 WebSocket verify_progress 消息类型');

    // 验证包含文件数
    await expect(firstCard).toContainText('3 文件');

    // 验证包含 SignalBadge
    const badge = getSignalBadge(page, 0);
    await expect(badge).toBeVisible({ timeout: 3000 });

    // 验证时间戳格式
    const timestamp = firstCard.locator('span.text-xs.whitespace-nowrap');
    await expect(timestamp).toBeVisible();

    await takeTestScreenshot(page, 'TC-APOS-004', '01-l1-compact');
  });

  test('TC-APOS-005: L1→L2 点击展开', async ({ page }) => {
    const cards = page.locator('[data-testid="activity-card-l1"]');
    await expect(cards.first()).toBeVisible({ timeout: 5000 });

    // 点击第5条卡片 (mock-activity-002 重构 UserService) 展开
    // mock-activity-002 is at index 4 (sorted by timestamp desc: 006,005,004,003,002,001)
    await cards.nth(4).click();
    await page.waitForTimeout(500);

    // 验证 L2 展开 — 受影响文件区域可见
    const l2Section = page.locator('h4').filter({ hasText: '受影响文件' });
    await expect(l2Section.first()).toBeVisible({ timeout: 5000 });

    // 验证文件列表
    const fileItems = page.locator('.font-mono').filter({ hasText: 'src/services/UserService.ts' });
    await expect(fileItems).toBeVisible({ timeout: 3000 });

    // 验证操作按钮
    const detailBtn = page.locator('button').filter({ hasText: '详情' });
    await expect(detailBtn).toBeVisible({ timeout: 3000 });

    await takeTestScreenshot(page, 'TC-APOS-005', '01-l2-expand');
  });

  test('TC-APOS-006: L2→L3 Portal 全屏浮层', async ({ page }) => {
    const cards = page.locator('[data-testid="activity-card-l1"]');
    await expect(cards.first()).toBeVisible({ timeout: 5000 });

    // 展开 mock-activity-002
    await cards.nth(4).click();
    await page.waitForTimeout(500);

    // 点击详情按钮
    const detailBtn = page.locator('button').filter({ hasText: '详情' });
    await expect(detailBtn).toBeVisible({ timeout: 5000 });
    await detailBtn.click();
    await page.waitForTimeout(500);

    // 验证 L3 Portal 遮罩层
    const backdrop = page.locator('.absolute.inset-0.bg-black\\/60');
    await expect(backdrop).toBeVisible({ timeout: 5000 });

    // 验证 L3 面板标题
    await expect(page.locator('h2').filter({ hasText: '重构 UserService 认证逻辑' })).toBeVisible({ timeout: 3000 });

    await takeTestScreenshot(page, 'TC-APOS-006', '01-l3-portal');

    // ESC 关闭
    await page.keyboard.press('Escape');
    await page.waitForTimeout(500);

    // 验证 L3 关闭
    await expect(backdrop).not.toBeVisible({ timeout: 3000 });

    await takeTestScreenshot(page, 'TC-APOS-006', '02-l3-closed');
  });

  test('TC-APOS-007: L2 折叠回 L1', async ({ page }) => {
    const cards = page.locator('[data-testid="activity-card-l1"]');
    await expect(cards.first()).toBeVisible({ timeout: 5000 });

    // 展开第一张卡片
    await cards.first().click();
    await page.waitForTimeout(500);

    // 验证 L2 可见
    const l2Section = page.locator('h4').filter({ hasText: '受影响文件' });
    await expect(l2Section.first()).toBeVisible({ timeout: 5000 });

    // 再次点击折叠
    await cards.first().click();
    await page.waitForTimeout(500);

    // L2 消失
    await expect(l2Section.first()).not.toBeVisible({ timeout: 3000 });

    // 点击另一张卡片展开
    await cards.nth(2).click();
    await page.waitForTimeout(500);

    await takeTestScreenshot(page, 'TC-APOS-007', '01-switch-card');
  });
});

// ══════════════════════════════════════════════════════════════
// C. SignalBadge 信号系统 (TC-008 ~ TC-009)
// ══════════════════════════════════════════════════════════════

test.describe('C. SignalBadge 信号系统', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/');
    await waitForAppReady(page);
    await navigateToAPOSTab(page);
    const mockActivities = createMockActivities();
    await injectActivityData(page, mockActivities);
    await page.waitForTimeout(500);
  });

  test('TC-APOS-008: 四种信号颜色正确显示', async ({ page }) => {
    const cards = page.locator('[data-testid="activity-card-l1"]');
    await expect(cards.first()).toBeVisible({ timeout: 5000 });

    // mock-activity-001 (index 5) = auto_approve → green
    const greenBadge = getSignalBadge(page, 5);
    await expect(greenBadge).toHaveClass(/text-green-400/);
    await expect(greenBadge).toHaveClass(/bg-green-500\/15/);

    // mock-activity-002 (index 4) = review_recommended → yellow
    const yellowBadge = getSignalBadge(page, 4);
    await expect(yellowBadge).toHaveClass(/text-yellow-400/);
    await expect(yellowBadge).toHaveClass(/bg-yellow-500\/15/);

    // mock-activity-003 (index 3) = manual_required → blue
    const blueBadge = getSignalBadge(page, 3);
    await expect(blueBadge).toHaveClass(/text-blue-400/);
    await expect(blueBadge).toHaveClass(/bg-blue-500\/15/);

    // mock-activity-004 (index 2) = blocked → red
    const redBadge = getSignalBadge(page, 2);
    await expect(redBadge).toHaveClass(/text-red-400/);
    await expect(redBadge).toHaveClass(/bg-red-500\/15/);

    await takeTestScreenshot(page, 'TC-APOS-008', '01-signal-colors');
  });

  test('TC-APOS-009: SignalBadge hover 显示 tooltip', async ({ page }) => {
    const cards = page.locator('[data-testid="activity-card-l1"]');
    await expect(cards.first()).toBeVisible({ timeout: 5000 });

    // mock-activity-001 (index 5) tooltip
    const greenBadge = getSignalBadge(page, 5);
    const title = await greenBadge.getAttribute('title');
    expect(title).toContain('仅修改文档 typo');

    // mock-activity-004 (index 2) tooltip
    const redBadge = getSignalBadge(page, 2);
    const redTitle = await redBadge.getAttribute('title');
    expect(redTitle).toContain('删除核心认证模块将导致 12 个测试失败');

    await takeTestScreenshot(page, 'TC-APOS-009', '01-tooltips');
  });
});

// ══════════════════════════════════════════════════════════════
// D. 信号筛选 (TC-010 ~ TC-011)
// ══════════════════════════════════════════════════════════════

test.describe('D. 信号筛选', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/');
    await waitForAppReady(page);
    await navigateToAPOSTab(page);
    const mockActivities = createMockActivities();
    await injectActivityData(page, mockActivities);
    await page.waitForTimeout(500);
  });

  test('TC-APOS-010: 筛选条按 Signal 类型过滤 Activity', async ({ page }) => {
    // 验证筛选栏存在
    const filterBar = page.locator('button').filter({ hasText: '全部' });
    await expect(filterBar).toBeVisible({ timeout: 5000 });

    // 点击 "可放行" 筛选
    await page.locator('button').filter({ hasText: '可放行' }).click();
    await page.waitForTimeout(500);

    // 应只显示 auto_approve 的卡片 (001, 005)
    const cards = page.locator('[data-testid="activity-card-l1"]');
    const count = await cards.count();
    expect(count).toBe(2);

    await takeTestScreenshot(page, 'TC-APOS-010', '01-filter-auto-approve');

    // 点击 "已阻止" 筛选
    await page.locator('button').filter({ hasText: '已阻止' }).click();
    await page.waitForTimeout(500);

    const blockedCount = await cards.count();
    expect(blockedCount).toBe(1);
    await expect(cards.first()).toContainText('删除 AuthController 核心模块');

    await takeTestScreenshot(page, 'TC-APOS-010', '02-filter-blocked');
  });

  test('TC-APOS-011: "全部" 筛选显示所有 Activity', async ({ page }) => {
    // 先筛选为 "可放行"
    await page.locator('button').filter({ hasText: '可放行' }).click();
    await page.waitForTimeout(500);

    const cards = page.locator('[data-testid="activity-card-l1"]');
    expect(await cards.count()).toBe(2);

    // 点击 "全部"
    await page.locator('button').filter({ hasText: '全部' }).click();
    await page.waitForTimeout(500);

    // 恢复 6 条
    expect(await cards.count()).toBe(6);

    await takeTestScreenshot(page, 'TC-APOS-011', '01-filter-all');
  });
});

// ══════════════════════════════════════════════════════════════
// E. 批量操作 (TC-012 ~ TC-015)
// ══════════════════════════════════════════════════════════════

test.describe('E. 批量操作', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/');
    await waitForAppReady(page);
    await navigateToAPOSTab(page);
    const mockActivities = createMockActivities();
    await injectActivityData(page, mockActivities);
    await page.waitForTimeout(500);
  });

  test('TC-APOS-012: 进入批量选择模式', async ({ page }) => {
    // 启用批量模式
    await setBatchMode(page, true);
    await page.waitForTimeout(300);

    // 验证 BatchOperationBar 出现
    const batchBar = page.locator('text=已选');
    await expect(batchBar).toBeVisible({ timeout: 5000 });

    // 验证复选框出现
    const checkboxes = page.locator('[data-testid="activity-card-l1"] input[type="checkbox"]');
    await expect(checkboxes.first()).toBeVisible({ timeout: 3000 });

    await takeTestScreenshot(page, 'TC-APOS-012', '01-batch-mode');
  });

  test('TC-APOS-013: 单选/全选 Activity', async ({ page }) => {
    await setBatchMode(page, true);
    await page.waitForTimeout(300);

    // 点击第一个复选框
    const checkboxes = page.locator('[data-testid="activity-card-l1"] input[type="checkbox"]');
    await expect(checkboxes.first()).toBeVisible({ timeout: 5000 });
    await checkboxes.first().click();
    await page.waitForTimeout(300);

    // 验证 "已选 1 项"
    await expect(page.locator('strong').filter({ hasText: '1' })).toBeVisible({ timeout: 3000 });

    // 点击 "全选可操作"
    await page.locator('button').filter({ hasText: '全选可操作' }).click();
    await page.waitForTimeout(300);

    // 应选中 auto_approve + review_recommended (001,002,005,006) = 4 条
    await expect(page.locator('strong').filter({ hasText: '4' })).toBeVisible({ timeout: 3000 });

    await takeTestScreenshot(page, 'TC-APOS-013', '01-select-all-safe');
  });

  test('TC-APOS-014: 批量批准操作', async ({ page }) => {
    await setBatchMode(page, true);
    await page.waitForTimeout(300);

    // 验证批量批准按钮禁用态（未选中时）
    const batchApproveBtn = page.locator('button').filter({ hasText: '批量批准' });
    await expect(batchApproveBtn).toBeVisible({ timeout: 5000 });
    await expect(batchApproveBtn).toBeDisabled();

    // 选中一条
    const checkboxes = page.locator('[data-testid="activity-card-l1"] input[type="checkbox"]');
    await checkboxes.first().click();
    await page.waitForTimeout(300);

    // 批量批准按钮可用
    await expect(batchApproveBtn).toBeEnabled();

    await takeTestScreenshot(page, 'TC-APOS-014', '01-batch-approve');
  });

  test('TC-APOS-015: 批量拒绝和取消操作', async ({ page }) => {
    await setBatchMode(page, true);
    await page.waitForTimeout(300);

    // 选中一条
    const checkboxes = page.locator('[data-testid="activity-card-l1"] input[type="checkbox"]');
    await expect(checkboxes.first()).toBeVisible({ timeout: 5000 });
    await checkboxes.first().click();
    await page.waitForTimeout(300);

    // 批量拒绝按钮可用
    const batchRejectBtn = page.locator('button').filter({ hasText: '批量拒绝' });
    await expect(batchRejectBtn).toBeEnabled();

    // 点击 "取消选择"
    const cancelBtn = page.locator('button').filter({ hasText: '取消选择' });
    await cancelBtn.click();
    await page.waitForTimeout(500);

    // 批量模式退出，BatchOperationBar 消失
    await expect(page.locator('text=已选')).not.toBeVisible({ timeout: 3000 });

    await takeTestScreenshot(page, 'TC-APOS-015', '01-cancel-batch');
  });
});
