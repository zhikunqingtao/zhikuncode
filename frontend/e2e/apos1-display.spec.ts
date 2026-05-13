import { test, expect } from '@playwright/test';
import {
  waitForAppReady,
  navigateToAPOSTab,
  getSignalBadge,
  injectActivityData,
  injectFeatureFlags,
  createMockActivities,
  takeTestScreenshot,
} from './helpers/apos1-helpers';

/**
 * APOS Phase 1 — 展示层 E2E 测试
 * TC-APOS-003 ~ TC-APOS-015（共13条用例）
 *
 * 聚焦 UI 展示正确性：Mock 数据注入、卡片三层渲染、
 * SignalBadge、筛选栏、Feature Flag 即时响应。
 */

// ══════════════════════════════════════════════════════════════
// A. Mock 数据加载（TC-APOS-003）
// ══════════════════════════════════════════════════════════════

test.describe('A. Mock 数据加载验证', () => {
  test('TC-APOS-003: Mock 数据正确注入并按时间倒序展示', async ({ page }) => {
    await page.goto('/');
    await waitForAppReady(page);
    await navigateToAPOSTab(page);

    // 注入 Mock 数据
    const mockActivities = createMockActivities();
    await injectActivityData(page, mockActivities);

    // 等待卡片渲染
    const cards = page.locator('[data-testid="activity-card-l1"]');
    await expect(cards.first()).toBeVisible({ timeout: 10000 });

    // 验证总共 6 条
    const count = await cards.count();
    expect(count).toBe(6);

    // 验证倒序：第一条是最新的 mock-activity-006
    await expect(cards.first()).toContainText('新增 WebSocket verify_progress 消息类型');

    // 验证第二条是 mock-activity-005
    await expect(cards.nth(1)).toContainText('更新 package.json 依赖版本');

    // 验证最后一条是最早的 mock-activity-001
    await expect(cards.nth(5)).toContainText('修复 typo in README.md');

    await takeTestScreenshot(page, 'TC-APOS-003', 'display-01-mock-loaded');
  });
});

// ══════════════════════════════════════════════════════════════
// B. ActivityCard 三层展示 (TC-APOS-004 ~ TC-APOS-007)
// ══════════════════════════════════════════════════════════════

test.describe('B. ActivityCard 三层展示', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/');
    await waitForAppReady(page);
    await navigateToAPOSTab(page);
    const mockActivities = createMockActivities();
    await injectActivityData(page, mockActivities);
    await page.waitForTimeout(500);
  });

  test('TC-APOS-004: L1 卡片正确渲染标题/状态/信号徽章', async ({ page }) => {
    const cards = page.locator('[data-testid="activity-card-l1"]');
    await expect(cards.first()).toBeVisible({ timeout: 5000 });

    // 验证第一条卡片（mock-activity-006）内容
    const firstCard = cards.first();
    // 摘要文本
    await expect(firstCard).toContainText('新增 WebSocket verify_progress 消息类型');
    // 文件数
    await expect(firstCard).toContainText('3 文件');
    // SignalBadge 存在
    const badge = getSignalBadge(page, 0);
    await expect(badge).toBeVisible({ timeout: 3000 });
    // 时间戳区域可见
    const timestamp = firstCard.locator('span.text-xs.whitespace-nowrap');
    await expect(timestamp).toBeVisible();

    // 验证 blocked 卡片（mock-activity-004, index 2）
    const blockedCard = cards.nth(2);
    await expect(blockedCard).toContainText('删除 AuthController 核心模块');
    const redBadge = getSignalBadge(page, 2);
    await expect(redBadge).toBeVisible();

    // 验证 auto_approve 卡片（mock-activity-001, index 5）
    const greenCard = cards.nth(5);
    await expect(greenCard).toContainText('修复 typo in README.md');
    await expect(greenCard).toContainText('1 文件');

    await takeTestScreenshot(page, 'TC-APOS-004', 'display-01-l1-cards');
  });

  test('TC-APOS-005: L2 展开详情面板', async ({ page }) => {
    const cards = page.locator('[data-testid="activity-card-l1"]');
    await expect(cards.first()).toBeVisible({ timeout: 5000 });

    // 点击 mock-activity-002（index 4，重构 UserService）
    await cards.nth(4).click();
    await page.waitForTimeout(500);

    // L2 受影响文件区域可见
    const l2Section = page.locator('h4').filter({ hasText: '受影响文件' });
    await expect(l2Section.first()).toBeVisible({ timeout: 5000 });

    // 验证文件列表包含关键文件
    const fileItem = page.locator('.font-mono').filter({ hasText: 'src/services/UserService.ts' });
    await expect(fileItem).toBeVisible({ timeout: 3000 });

    // 验证操作按钮存在
    const detailBtn = page.locator('button').filter({ hasText: '详情' });
    await expect(detailBtn).toBeVisible({ timeout: 3000 });

    // 验证仅当前卡片展开，其他保持 L1
    const otherCard = cards.first();
    // 第一张卡片不应有 L2 内容（排除已展开的卡片区域）
    const firstCardL2 = cards.first().locator('h4').filter({ hasText: '受影响文件' });
    expect(await firstCardL2.count()).toBe(0);

    await takeTestScreenshot(page, 'TC-APOS-005', 'display-01-l2-expand');
  });

  test('TC-APOS-006: L3 Portal 面板打开与关闭', async ({ page }) => {
    const cards = page.locator('[data-testid="activity-card-l1"]');
    await expect(cards.first()).toBeVisible({ timeout: 5000 });

    // 展开 mock-activity-002
    await cards.nth(4).click();
    await page.waitForTimeout(500);

    // 点击详情按钮打开 L3
    const detailBtn = page.locator('button').filter({ hasText: '详情' });
    await expect(detailBtn).toBeVisible({ timeout: 5000 });
    await detailBtn.click();
    await page.waitForTimeout(500);

    // 验证 L3 Portal 遮罩层出现
    const backdrop = page.locator('.absolute.inset-0.bg-black\\/60');
    await expect(backdrop).toBeVisible({ timeout: 5000 });

    // 验证 L3 面板标题
    const title = page.locator('h2').filter({ hasText: '重构 UserService 认证逻辑' });
    await expect(title).toBeVisible({ timeout: 3000 });

    await takeTestScreenshot(page, 'TC-APOS-006', 'display-01-l3-portal');

    // ESC 关闭 L3
    await page.keyboard.press('Escape');
    await page.waitForTimeout(500);
    await expect(backdrop).not.toBeVisible({ timeout: 3000 });

    await takeTestScreenshot(page, 'TC-APOS-006', 'display-02-l3-closed');
  });

  test('TC-APOS-007: L1 折叠恢复', async ({ page }) => {
    const cards = page.locator('[data-testid="activity-card-l1"]');
    await expect(cards.first()).toBeVisible({ timeout: 5000 });

    // 展开第一张
    await cards.first().click();
    await page.waitForTimeout(500);

    // L2 可见
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

    // 新卡片的 L2 可见
    await expect(l2Section.first()).toBeVisible({ timeout: 5000 });

    await takeTestScreenshot(page, 'TC-APOS-007', 'display-01-collapse-switch');
  });
});

// ══════════════════════════════════════════════════════════════
// C. SignalBadge 信号系统 (TC-APOS-008 ~ TC-APOS-009)
// ══════════════════════════════════════════════════════════════

test.describe('C. SignalBadge 渲染与交互', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/');
    await waitForAppReady(page);
    await navigateToAPOSTab(page);
    const mockActivities = createMockActivities();
    await injectActivityData(page, mockActivities);
    await page.waitForTimeout(500);
  });

  test('TC-APOS-008: SignalBadge 四种信号颜色与图标正确显示', async ({ page }) => {
    const cards = page.locator('[data-testid="activity-card-l1"]');
    await expect(cards.first()).toBeVisible({ timeout: 5000 });

    // mock-activity-001 (index 5) = auto_approve → 绿色
    const greenBadge = getSignalBadge(page, 5);
    await expect(greenBadge).toBeVisible();
    await expect(greenBadge).toHaveClass(/text-green-400/);
    await expect(greenBadge).toHaveClass(/bg-green-500\/15/);

    // mock-activity-006 (index 0) = review_recommended → 黄色
    const yellowBadge = getSignalBadge(page, 0);
    await expect(yellowBadge).toBeVisible();
    await expect(yellowBadge).toHaveClass(/text-yellow-400/);
    await expect(yellowBadge).toHaveClass(/bg-yellow-500\/15/);

    // mock-activity-003 (index 3) = manual_required → 蓝色
    const blueBadge = getSignalBadge(page, 3);
    await expect(blueBadge).toBeVisible();
    await expect(blueBadge).toHaveClass(/text-blue-400/);
    await expect(blueBadge).toHaveClass(/bg-blue-500\/15/);

    // mock-activity-004 (index 2) = blocked → 红色
    const redBadge = getSignalBadge(page, 2);
    await expect(redBadge).toBeVisible();
    await expect(redBadge).toHaveClass(/text-red-400/);
    await expect(redBadge).toHaveClass(/bg-red-500\/15/);

    await takeTestScreenshot(page, 'TC-APOS-008', 'display-01-signal-colors');
  });

  test('TC-APOS-009: SignalBadge hover 显示 Tooltip', async ({ page }) => {
    const cards = page.locator('[data-testid="activity-card-l1"]');
    await expect(cards.first()).toBeVisible({ timeout: 5000 });

    // auto_approve tooltip（mock-activity-001, index 5）
    const greenBadge = getSignalBadge(page, 5);
    const greenTitle = await greenBadge.getAttribute('title');
    expect(greenTitle).toContain('仅修改文档 typo');

    // review_recommended tooltip（mock-activity-006, index 0）
    const yellowBadge = getSignalBadge(page, 0);
    const yellowTitle = await yellowBadge.getAttribute('title');
    expect(yellowTitle).toContain('新增消息类型需要审查');

    // blocked tooltip（mock-activity-004, index 2）
    const redBadge = getSignalBadge(page, 2);
    const redTitle = await redBadge.getAttribute('title');
    expect(redTitle).toContain('删除核心认证模块将导致 12 个测试失败');

    // manual_required tooltip（mock-activity-003, index 3）
    const blueBadge = getSignalBadge(page, 3);
    const blueTitle = await blueBadge.getAttribute('title');
    expect(blueTitle).toContain('数据库迁移需手动确认');

    await takeTestScreenshot(page, 'TC-APOS-009', 'display-01-tooltips');
  });
});

// ══════════════════════════════════════════════════════════════
// D. 筛选栏与筛选功能 (TC-APOS-010 ~ TC-APOS-014)
// ══════════════════════════════════════════════════════════════

test.describe('D. 筛选栏与筛选功能', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/');
    await waitForAppReady(page);
    await navigateToAPOSTab(page);
    const mockActivities = createMockActivities();
    await injectActivityData(page, mockActivities);
    await page.waitForTimeout(500);
  });

  test('TC-APOS-010: 筛选栏正确渲染所有筛选选项', async ({ page }) => {
    // 验证 5 个筛选按钮均存在
    const allBtn = page.locator('button').filter({ hasText: '全部' });
    await expect(allBtn).toBeVisible({ timeout: 5000 });

    const autoApproveBtn = page.locator('button').filter({ hasText: '可放行' });
    await expect(autoApproveBtn).toBeVisible();

    const reviewBtn = page.locator('button').filter({ hasText: '建议审查' });
    await expect(reviewBtn).toBeVisible();

    const manualBtn = page.locator('button').filter({ hasText: '需手动' });
    await expect(manualBtn).toBeVisible();

    const blockedBtn = page.locator('button').filter({ hasText: '已阻止' });
    await expect(blockedBtn).toBeVisible();

    await takeTestScreenshot(page, 'TC-APOS-010', 'display-01-filter-bar');
  });

  test('TC-APOS-011: 按状态筛选 Activity 列表', async ({ page }) => {
    const cards = page.locator('[data-testid="activity-card-l1"]');
    await expect(cards.first()).toBeVisible({ timeout: 5000 });

    // 初始全部 6 条
    expect(await cards.count()).toBe(6);

    // 点击 "可放行" → 只显示 auto_approve (001, 005) = 2 条
    await page.locator('button').filter({ hasText: '可放行' }).click();
    await page.waitForTimeout(500);
    expect(await cards.count()).toBe(2);
    await expect(cards.first()).toContainText('更新 package.json 依赖版本');
    await expect(cards.nth(1)).toContainText('修复 typo in README.md');

    await takeTestScreenshot(page, 'TC-APOS-011', 'display-01-filter-auto-approve');

    // 点击 "已阻止" → 只显示 blocked (004) = 1 条
    await page.locator('button').filter({ hasText: '已阻止' }).click();
    await page.waitForTimeout(500);
    expect(await cards.count()).toBe(1);
    await expect(cards.first()).toContainText('删除 AuthController 核心模块');

    await takeTestScreenshot(page, 'TC-APOS-011', 'display-02-filter-blocked');
  });

  test('TC-APOS-012: 按信号级别筛选', async ({ page }) => {
    const cards = page.locator('[data-testid="activity-card-l1"]');
    await expect(cards.first()).toBeVisible({ timeout: 5000 });

    // "建议审查" → review_recommended (002, 006) = 2 条
    await page.locator('button').filter({ hasText: '建议审查' }).click();
    await page.waitForTimeout(500);
    expect(await cards.count()).toBe(2);
    await expect(cards.first()).toContainText('新增 WebSocket verify_progress 消息类型');
    await expect(cards.nth(1)).toContainText('重构 UserService 认证逻辑');

    await takeTestScreenshot(page, 'TC-APOS-012', 'display-01-filter-review');

    // "需手动" → manual_required (003) = 1 条
    await page.locator('button').filter({ hasText: '需手动' }).click();
    await page.waitForTimeout(500);
    expect(await cards.count()).toBe(1);
    await expect(cards.first()).toContainText('数据库迁移脚本 v2.3');

    await takeTestScreenshot(page, 'TC-APOS-012', 'display-02-filter-manual');
  });

  test('TC-APOS-013: 组合筛选（通过 Store 设置多信号）', async ({ page }) => {
    const cards = page.locator('[data-testid="activity-card-l1"]');
    await expect(cards.first()).toBeVisible({ timeout: 5000 });

    // 通过 Store 直接设置组合筛选：auto_approve + blocked
    await page.evaluate(async () => {
      const mod = await import('/src/store/activityStore.ts');
      const store = (mod as any).useActivityStore;
      store.getState().setFilter({ signal: ['auto_approve', 'blocked'] });
    });
    await page.waitForTimeout(500);

    // 应显示 3 条：001(auto_approve) + 005(auto_approve) + 004(blocked)
    expect(await cards.count()).toBe(3);

    await takeTestScreenshot(page, 'TC-APOS-013', 'display-01-combined-filter');

    // 验证包含 auto_approve 和 blocked 的内容
    const allText = await cards.allTextContents();
    const combined = allText.join(' ');
    expect(combined).toContain('修复 typo in README.md');
    expect(combined).toContain('删除 AuthController 核心模块');
    expect(combined).toContain('更新 package.json 依赖版本');
  });

  test('TC-APOS-014: 筛选重置恢复全量展示', async ({ page }) => {
    const cards = page.locator('[data-testid="activity-card-l1"]');
    await expect(cards.first()).toBeVisible({ timeout: 5000 });

    // 先筛选为 "已阻止"
    await page.locator('button').filter({ hasText: '已阻止' }).click();
    await page.waitForTimeout(500);
    expect(await cards.count()).toBe(1);

    // 点击 "全部" 重置
    await page.locator('button').filter({ hasText: '全部' }).click();
    await page.waitForTimeout(500);

    // 恢复全部 6 条
    expect(await cards.count()).toBe(6);

    // 验证排序未被打乱（第一条仍是最新）
    await expect(cards.first()).toContainText('新增 WebSocket verify_progress 消息类型');

    await takeTestScreenshot(page, 'TC-APOS-014', 'display-01-filter-reset');
  });
});

// ══════════════════════════════════════════════════════════════
// E. Feature Flag 切换 (TC-APOS-015)
// ══════════════════════════════════════════════════════════════

test.describe('E. Feature Flag 切换', () => {
  test('TC-APOS-015: Feature Flag 切换后 UI 即时响应', async ({ page }) => {
    await page.goto('/');
    await waitForAppReady(page);
    await navigateToAPOSTab(page);

    // 注入数据
    const mockActivities = createMockActivities();
    await injectActivityData(page, mockActivities);
    await page.waitForTimeout(500);

    // 验证 Activity Tab 可见且有数据
    const cards = page.locator('[data-testid="activity-card-l1"]');
    await expect(cards.first()).toBeVisible({ timeout: 5000 });
    expect(await cards.count()).toBe(6);

    // 关闭 APOS_ACTIVITY_STREAM Feature Flag
    await injectFeatureFlags(page, { APOS_ACTIVITY_STREAM: false });
    await page.waitForTimeout(500);

    // Activity Tab 按钮应消失
    const activityTab = page.locator('button[title="Activity"]');
    await expect(activityTab).not.toBeVisible({ timeout: 5000 });

    await takeTestScreenshot(page, 'TC-APOS-015', 'display-01-flag-off');

    // 重新开启
    await injectFeatureFlags(page, { APOS_ACTIVITY_STREAM: true });
    await page.waitForTimeout(500);

    // Activity Tab 恢复
    await expect(activityTab).toBeVisible({ timeout: 5000 });

    // 重新进入 Activity Tab 确认数据仍在
    await activityTab.click();
    await page.waitForTimeout(800);

    await expect(cards.first()).toBeVisible({ timeout: 5000 });

    await takeTestScreenshot(page, 'TC-APOS-015', 'display-02-flag-on');
  });
});
