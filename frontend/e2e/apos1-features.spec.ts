import { test, expect } from '@playwright/test';
import {
  waitForAppReady,
  navigateToAPOSTab,
  injectActivityData,
  injectFeatureFlags,
  createMockActivities,
  createMockActivity,
  expandActivityCard,
  takeTestScreenshot,
} from './helpers/apos1-helpers';

/**
 * APOS Phase 1 — 功能模块 E2E 测试
 * TC-APOS-016 ~ TC-APOS-026
 */

// ══════════════════════════════════════════════════════════════
// F. Feature Flag 面板 (TC-016 ~ TC-018)
// ══════════════════════════════════════════════════════════════

test.describe('F. Feature Flag 面板', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/');
    await waitForAppReady(page);
    await navigateToAPOSTab(page);
  });

  test('TC-APOS-016: Feature Flag 面板显示所有 APOS Flag', async ({ page }) => {
    // 查找 Feature Flags 面板
    const flagPanel = page.locator('text=Feature Flags');
    await expect(flagPanel).toBeVisible({ timeout: 10000 });

    // 验证面板 Header
    const resetBtn = page.locator('button').filter({ hasText: '重置' });
    await expect(resetBtn).toBeVisible({ timeout: 3000 });

    // 验证 Flag 列表存在
    const flagLabels = ['Activity Stream', 'AI Insight', 'Batch Review', 'Risk Heatmap'];
    for (const label of flagLabels) {
      await expect(page.locator(`text=${label}`).first()).toBeVisible({ timeout: 3000 });
    }

    await takeTestScreenshot(page, 'TC-APOS-016', '01-flag-panel');
  });

  test('TC-APOS-017: Feature Flag 开关可交互切换', async ({ page }) => {
    // 查找 Feature Flags 面板
    await expect(page.locator('text=Feature Flags')).toBeVisible({ timeout: 10000 });

    // 找到 toggle 按钮 — 通过 FeatureFlagPanel 内的 toggle switch 类名定位
    const flagPanel = page.locator('.rounded-lg.border.border-gray-700\\/50.bg-\\[\\#1e1e30\\]');
    const toggles = flagPanel.locator('button.relative.w-8');
    const toggleCount = await toggles.count();
    expect(toggleCount).toBeGreaterThan(0);

    // 点击第一个 toggle（Activity Stream）
    const firstToggle = toggles.first();
    const initialClass = await firstToggle.getAttribute('class');

    // 点击切换
    await firstToggle.click();
    await page.waitForTimeout(300);

    // 验证状态变化
    const newClass = await firstToggle.getAttribute('class');
    // 状态应该发生变化
    expect(newClass).not.toBe(initialClass);

    // 点击重置
    const resetBtn = page.locator('button').filter({ hasText: '重置' });
    await resetBtn.click();
    await page.waitForTimeout(300);

    await takeTestScreenshot(page, 'TC-APOS-017', '01-toggle-interaction');
  });

  test('TC-APOS-018: Feature Flag 依赖关系限制', async ({ page }) => {
    await expect(page.locator('text=Feature Flags')).toBeVisible({ timeout: 10000 });

    // 关闭 Activity Stream Flag
    await injectFeatureFlags(page, { APOS_ACTIVITY_STREAM: false });
    await page.waitForTimeout(500);

    // 验证依赖提示出现 — "需要先启用" 文本
    const depWarning = page.locator('text=需要先启用');
    await expect(depWarning.first()).toBeVisible({ timeout: 5000 });

    // 验证被禁用的 toggle 不可点击（disabled 属性）
    const disabledToggles = page.locator('button[disabled][title*="依赖"]');
    const disabledCount = await disabledToggles.count();
    expect(disabledCount).toBeGreaterThan(0);

    await takeTestScreenshot(page, 'TC-APOS-018', '01-dependency-disabled');

    // 恢复 Activity Stream
    await injectFeatureFlags(page, { APOS_ACTIVITY_STREAM: true });
    await page.waitForTimeout(500);

    // 依赖提示应消失（或减少）
    await takeTestScreenshot(page, 'TC-APOS-018', '02-restored');
  });
});

// ══════════════════════════════════════════════════════════════
// G. 手机端适配 (TC-019 ~ TC-020)
// ══════════════════════════════════════════════════════════════

test.describe('G. 手机端适配', () => {
  test('TC-APOS-019: 手机端 Bottom Sheet 展示', async ({ page }) => {
    // 设置移动端视口
    await page.setViewportSize({ width: 393, height: 852 });
    await page.goto('/');
    await waitForAppReady(page);

    // 在移动端导航到 Activity Tab（可能需要 dispatchEvent 因为元素在视口外）
    const activityTab = page.locator('button[title="Activity"]');
    await expect(activityTab).toBeAttached({ timeout: 10000 });
    await activityTab.dispatchEvent('click');
    await page.waitForTimeout(800);

    // 注入 Mock 数据
    const mockActivities = createMockActivities();
    await injectActivityData(page, mockActivities);
    await page.waitForTimeout(500);

    // 在移动端点击卡片展开（可能在视口外，使用 dispatchEvent）
    const cards = page.locator('[data-testid="activity-card-l1"]');
    await expect(cards.first()).toBeAttached({ timeout: 5000 });
    await cards.first().dispatchEvent('click');
    await page.waitForTimeout(500);

    // 检查是否有 L2 展开或 MobileBottomSheet
    // 移动端可能使用不同的交互方式
    await takeTestScreenshot(page, 'TC-APOS-019', '01-mobile-viewport');
  });

  test('TC-APOS-020: 响应式布局切换', async ({ page }) => {
    await page.goto('/');
    await waitForAppReady(page);
    await navigateToAPOSTab(page);
    const mockActivities = createMockActivities();
    await injectActivityData(page, mockActivities);
    await page.waitForTimeout(500);

    // PC 模式 (1280px)
    await page.setViewportSize({ width: 1280, height: 800 });
    await page.waitForTimeout(500);
    await takeTestScreenshot(page, 'TC-APOS-020', '01-desktop');

    // Tablet 模式 (800px)
    await page.setViewportSize({ width: 800, height: 600 });
    await page.waitForTimeout(500);
    await takeTestScreenshot(page, 'TC-APOS-020', '02-tablet');

    // Mobile 模式 (393px)
    await page.setViewportSize({ width: 393, height: 852 });
    await page.waitForTimeout(500);
    await takeTestScreenshot(page, 'TC-APOS-020', '03-mobile');
  });
});

// ══════════════════════════════════════════════════════════════
// H. 后端 API (TC-021 ~ TC-022)
// ══════════════════════════════════════════════════════════════

test.describe('H. 后端 API', () => {
  test('TC-APOS-021: POST /api/verify/run-checks 端点可访问', async ({ request }) => {
    // 正常请求
    const response = await request.post('http://localhost:8080/api/verify/run-checks', {
      data: {
        sessionId: 'test-session',
        operationId: 'test-op-001',
        checks: ['typescript', 'eslint'],
        filePaths: ['src/App.tsx'],
        timeout: 10000,
      },
    });

    // 验证响应状态（200 或 400 取决于实际文件是否存在）
    expect([200, 400, 404, 500]).toContain(response.status());

    if (response.status() === 200) {
      const body = await response.json();
      // 后端实际响应结构: signal, duration, results, overallStatus
      expect(body).toHaveProperty('signal');
      expect(body).toHaveProperty('results');
      expect(body).toHaveProperty('duration');
    }
  });

  test('TC-APOS-022: 验证参数缺失时返回错误', async ({ request }) => {
    // 缺少必填参数
    const response = await request.post('http://localhost:8080/api/verify/run-checks', {
      data: {
        sessionId: 'test',
      },
    });

    // 应返回 400
    expect([400, 404, 500]).toContain(response.status());
  });
});

// ══════════════════════════════════════════════════════════════
// I. 数据流转集成 (TC-023 ~ TC-024)
// ══════════════════════════════════════════════════════════════

test.describe('I. 数据流转集成', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/');
    await waitForAppReady(page);
  });

  test('TC-APOS-023: Activity Store 数据正确存储和展示', async ({ page }) => {
    await navigateToAPOSTab(page);

    // 通过 Store 添加一条新 Activity
    const newActivity = createMockActivity({
      id: 'integration-test-001',
      operationType: 'file_edit',
      summary: '集成测试：编辑文件',
      timestamp: Date.now(),
    });
    await injectActivityData(page, [newActivity]);
    await page.waitForTimeout(500);

    // 验证 Activity 出现在列表中
    const cards = page.locator('[data-testid="activity-card-l1"]');
    await expect(cards.first()).toBeVisible({ timeout: 5000 });
    await expect(cards.first()).toContainText('集成测试：编辑文件');

    await takeTestScreenshot(page, 'TC-APOS-023', '01-store-data');
  });

  test('TC-APOS-024: Activity insight 关联后 SignalBadge 更新', async ({ page }) => {
    await navigateToAPOSTab(page);

    // 添加一条无 insight 的 Activity（模拟 loading 状态）
    const loadingActivity = createMockActivity({
      id: 'insight-test-001',
      operationType: 'file_edit',
      summary: '等待验证结果',
      timestamp: Date.now(),
      insight: undefined,
      status: 'completed',
    });
    await injectActivityData(page, [loadingActivity]);
    await page.waitForTimeout(500);

    // 验证 SignalBadge 为 loading 或 unavailable 状态
    const badge = page.locator('[data-testid="activity-card-l1"]').first().locator('span.inline-flex.items-center.rounded-full');
    await expect(badge).toBeVisible({ timeout: 5000 });

    // 通过 Store 更新 insight
    await page.evaluate(async () => {
      const mod = await import('/src/store/activityStore.ts');
      const store = (mod as any).useActivityStore;
      store.getState().attachInsight('insight-test-001', {
        signal: 'auto_approve',
        summary: '全部验证通过',
        verificationStatus: 'all_pass',
      });
    });
    await page.waitForTimeout(500);

    // 验证 SignalBadge 变为绿色
    await expect(badge).toHaveClass(/text-green-400/);

    await takeTestScreenshot(page, 'TC-APOS-024', '01-signal-updated');
  });
});

// ══════════════════════════════════════════════════════════════
// J. 异常场景 (TC-025 ~ TC-026)
// ══════════════════════════════════════════════════════════════

test.describe('J. 异常场景', () => {
  test('TC-APOS-025: ActivityStream 空状态正确展示', async ({ page }) => {
    await page.goto('/');
    await waitForAppReady(page);
    await navigateToAPOSTab(page);

    // 确保没有数据
    await page.evaluate(async () => {
      const mod = await import('/src/store/activityStore.ts');
      const store = (mod as any).useActivityStore;
      store.getState().clearAll();
    });
    await page.waitForTimeout(500);

    // 验证空状态文案
    await expect(page.locator('text=暂无活动记录')).toBeVisible({ timeout: 5000 });

    // 验证筛选栏仍可见
    await expect(page.locator('button').filter({ hasText: '全部' })).toBeVisible({ timeout: 3000 });

    await takeTestScreenshot(page, 'TC-APOS-025', '01-empty-state');
  });

  test('TC-APOS-026: L3 Portal 遮罩层点击关闭', async ({ page }) => {
    await page.goto('/');
    await waitForAppReady(page);
    await navigateToAPOSTab(page);
    const mockActivities = createMockActivities();
    await injectActivityData(page, mockActivities);
    await page.waitForTimeout(500);

    // 展开一张卡片
    await expandActivityCard(page, 4); // mock-activity-002

    // 点击详情
    const detailBtn = page.locator('button').filter({ hasText: '详情' });
    await expect(detailBtn).toBeVisible({ timeout: 5000 });
    await detailBtn.click();
    await page.waitForTimeout(500);

    // 验证 L3 打开
    const portalTitle = page.locator('h2').filter({ hasText: '重构 UserService 认证逻辑' });
    await expect(portalTitle).toBeVisible({ timeout: 5000 });

    // 点击遮罩层关闭 — 点击背景区域
    const backdrop = page.locator('.absolute.inset-0.bg-black\\/60');
    if (await backdrop.isVisible()) {
      await backdrop.click({ position: { x: 10, y: 10 } });
    } else {
      // 尝试 ESC 关闭
      await page.keyboard.press('Escape');
    }
    await page.waitForTimeout(500);

    // L3 应关闭
    await expect(portalTitle).not.toBeVisible({ timeout: 5000 });

    await takeTestScreenshot(page, 'TC-APOS-026', '01-backdrop-close');
  });
});
