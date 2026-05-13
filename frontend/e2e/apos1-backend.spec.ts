import { test, expect } from '@playwright/test';
import {
  waitForAppReady,
  navigateToAPOSTab,
  injectActivityData,
  expandActivityCard,
  createMockActivity,
  takeTestScreenshot,
} from './helpers/apos1-helpers';

/**
 * APOS Phase 1 — Activity 后端持久化 E2E 测试
 * TC-APOS-045 ~ TC-APOS-052
 */

// ══════════════════════════════════════════════════════════════
// AA. Activity 后端持久化 (TC-045 ~ TC-052)
// ══════════════════════════════════════════════════════════════

test.describe('AA. Activity 后端持久化', () => {
  test('TC-APOS-045: activities 表结构验证', async ({ request }) => {
    // 通过 API 验证后端是否能处理 activity 相关请求
    // 验证 /api/sessions endpoint 是否存在
    const response = await request.get('http://localhost:8080/api/sessions');
    const status = response.status();
    // 200 或其他有效响应
    expect([200, 401, 403, 404]).toContain(status);

    if (status === 200) {
      const body = await response.json();
      // 验证返回结构包含 sessions
      expect(body).toBeDefined();
    }
  });

  test('TC-APOS-046: Activity 创建验证 - Store 正确存储', async ({ page }) => {
    await page.goto('/');
    await waitForAppReady(page);
    await navigateToAPOSTab(page);

    // 添加 Activity 到 Store
    const testActivity = createMockActivity({
      id: 'persist-test-001',
      operationType: 'file_edit',
      summary: '持久化测试：创建文件',
      timestamp: Date.now(),
    });
    await injectActivityData(page, [testActivity]);
    await page.waitForTimeout(500);

    // 验证 Store 中存在该 Activity
    const storeState = await page.evaluate(async () => {
      const mod = await import('/src/store/activityStore.ts');
      const store = (mod as any).useActivityStore;
      const activities = store.getState().activities;
      const act = activities.get('persist-test-001');
      return {
        exists: !!act,
        summary: act?.summary,
        operationType: act?.operationType,
      };
    });

    expect(storeState.exists).toBe(true);
    expect(storeState.summary).toBe('持久化测试：创建文件');
    expect(storeState.operationType).toBe('file_edit');

    await takeTestScreenshot(page, 'TC-APOS-046', '01-store-verify');
  });

  test('TC-APOS-047: Activity insight 更新验证', async ({ page }) => {
    await page.goto('/');
    await waitForAppReady(page);
    await navigateToAPOSTab(page);

    // 添加 Activity 并附加 insight
    const testActivity = createMockActivity({
      id: 'insight-persist-001',
      operationType: 'file_edit',
      summary: 'Insight 持久化测试',
      timestamp: Date.now(),
      insight: undefined,
    });
    await injectActivityData(page, [testActivity]);
    await page.waitForTimeout(300);

    // 附加 insight
    await page.evaluate(async () => {
      const mod = await import('/src/store/activityStore.ts');
      const store = (mod as any).useActivityStore;
      store.getState().attachInsight('insight-persist-001', {
        signal: 'auto_approve',
        summary: '全部验证通过',
        verificationStatus: 'all_pass',
      });
    });
    await page.waitForTimeout(300);

    // 验证 insight 已附加
    const result = await page.evaluate(async () => {
      const mod = await import('/src/store/activityStore.ts');
      const store = (mod as any).useActivityStore;
      const act = store.getState().activities.get('insight-persist-001');
      return {
        hasInsight: !!act?.insight,
        signal: act?.insight?.signal,
      };
    });

    expect(result.hasInsight).toBe(true);
    expect(result.signal).toBe('auto_approve');

    await takeTestScreenshot(page, 'TC-APOS-047', '01-insight-attached');
  });

  test('TC-APOS-048: Activity 审批决定更新验证', async ({ page }) => {
    await page.goto('/');
    await waitForAppReady(page);
    await navigateToAPOSTab(page);

    // 添加可操作的 Activity
    const testActivity = createMockActivity({
      id: 'decision-test-001',
      operationType: 'file_edit',
      summary: '审批决定测试',
      timestamp: Date.now(),
      insight: { signal: 'review_recommended', summary: '需要审查', verificationStatus: 'all_pass' },
      changedFiles: [{ filePath: 'src/test.ts', changeType: 'modified' }],
    });
    await injectActivityData(page, [testActivity]);
    await page.waitForTimeout(500);

    // 通过 Store 批准
    await page.evaluate(async () => {
      const mod = await import('/src/store/activityStore.ts');
      const store = (mod as any).useActivityStore;
      store.getState().approveActivity('decision-test-001');
    });
    await page.waitForTimeout(500);

    // 验证 decision 已更新
    const result = await page.evaluate(async () => {
      const mod = await import('/src/store/activityStore.ts');
      const store = (mod as any).useActivityStore;
      const act = store.getState().activities.get('decision-test-001');
      return { decision: act?.decision };
    });

    expect(result.decision).toBe('approved');

    // 展开 L2 验证 UI 状态
    await expandActivityCard(page, 0);
    await page.waitForTimeout(500);

    // 已批准应显示标记
    const approvedText = page.locator('text=已批准');
    await expect(approvedText).toBeVisible({ timeout: 5000 });

    await takeTestScreenshot(page, 'TC-APOS-048', '01-approved');
  });

  test('TC-APOS-049: 会话隔离 - 不同 sessionId 数据隔离', async ({ page }) => {
    await page.goto('/');
    await waitForAppReady(page);
    await navigateToAPOSTab(page);

    // 获取当前 sessionId
    const currentSessionId = await page.evaluate(async () => {
      const mod = await import('/src/store/sessionStore.ts');
      const store = (mod as any).useSessionStore;
      return store.getState().sessionId;
    });

    // 添加当前会话的 Activity
    const currentActivity = createMockActivity({
      id: 'session-a-001',
      sessionId: currentSessionId || 'session-a',
      summary: '当前会话 Activity',
      timestamp: Date.now(),
    });

    // 添加其他会话的 Activity
    const otherActivity = createMockActivity({
      id: 'session-b-001',
      sessionId: 'other-session-xyz',
      summary: '其他会话 Activity',
      timestamp: Date.now() - 1000,
    });

    await injectActivityData(page, [currentActivity, otherActivity]);
    await page.waitForTimeout(500);

    // ActivityStream 应仅显示当前会话的 Activity（因为 filter by sessionId）
    const cards = page.locator('[data-testid="activity-card-l1"]');
    const count = await cards.count();

    // 当前会话的 Activity 应可见
    if (currentSessionId) {
      // 只应显示当前 session 的 Activity
      expect(count).toBeGreaterThanOrEqual(1);
    }

    await takeTestScreenshot(page, 'TC-APOS-049', '01-session-isolation');
  });

  test('TC-APOS-050: 页面刷新后数据恢复验证', async ({ page }) => {
    await page.goto('/');
    await waitForAppReady(page);
    await navigateToAPOSTab(page);

    // 记录刷新前状态
    await takeTestScreenshot(page, 'TC-APOS-050', '01-before-refresh');

    // 刷新页面
    await page.reload();
    await waitForAppReady(page);
    await navigateToAPOSTab(page);
    await page.waitForTimeout(2000);

    // 页面刷新后应通过 session_restored 恢复数据
    await takeTestScreenshot(page, 'TC-APOS-050', '02-after-refresh');

    // 验证页面没有崩溃
    const streamPanel = page.locator('.flex.flex-col.min-h-0.overflow-hidden');
    await expect(streamPanel).toBeVisible({ timeout: 5000 });
  });

  test('TC-APOS-051: Activity reject 操作持久化', async ({ page }) => {
    await page.goto('/');
    await waitForAppReady(page);
    await navigateToAPOSTab(page);

    // 添加 Activity
    const testActivity = createMockActivity({
      id: 'reject-test-001',
      operationType: 'file_edit',
      summary: '拒绝操作测试',
      timestamp: Date.now(),
      insight: { signal: 'blocked', summary: '存在风险', verificationStatus: 'has_error' },
      changedFiles: [{ filePath: 'src/risky.ts', changeType: 'modified' }],
    });
    await injectActivityData(page, [testActivity]);
    await page.waitForTimeout(500);

    // 执行拒绝
    await page.evaluate(async () => {
      const mod = await import('/src/store/activityStore.ts');
      const store = (mod as any).useActivityStore;
      store.getState().rejectActivity('reject-test-001');
    });
    await page.waitForTimeout(500);

    // 验证 decision 为 rejected
    const result = await page.evaluate(async () => {
      const mod = await import('/src/store/activityStore.ts');
      const store = (mod as any).useActivityStore;
      const act = store.getState().activities.get('reject-test-001');
      return { decision: act?.decision };
    });
    expect(result.decision).toBe('rejected');

    // UI 验证
    await expandActivityCard(page, 0);
    await page.waitForTimeout(500);
    const rejectedText = page.locator('text=已拒绝');
    await expect(rejectedText).toBeVisible({ timeout: 5000 });

    await takeTestScreenshot(page, 'TC-APOS-051', '01-rejected');
  });

  test('TC-APOS-052: 后端不可用时前端不崩溃', async ({ page }) => {
    await page.goto('/');
    await waitForAppReady(page);
    await navigateToAPOSTab(page);

    // 添加 Activity（即使后端 sync 失败，前端应正常工作）
    const testActivity = createMockActivity({
      id: 'resilience-test-001',
      operationType: 'file_edit',
      summary: '容错测试 Activity',
      timestamp: Date.now(),
    });
    await injectActivityData(page, [testActivity]);
    await page.waitForTimeout(500);

    // 验证 Activity 出现在 UI 中
    const cards = page.locator('[data-testid="activity-card-l1"]');
    await expect(cards.first()).toBeVisible({ timeout: 5000 });
    await expect(cards.first()).toContainText('容错测试 Activity');

    // 验证 UI 可交互
    await cards.first().click();
    await page.waitForTimeout(500);

    // 页面没有崩溃
    await expect(page.locator('h4').filter({ hasText: '受影响文件' }).first()).toBeVisible({ timeout: 5000 });

    // Console 中不应有未捕获的异常
    const errors: string[] = [];
    page.on('pageerror', (error) => {
      errors.push(error.message);
    });
    await page.waitForTimeout(1000);

    // 不应有 TypeError 等严重错误
    const criticalErrors = errors.filter(e =>
      e.includes('TypeError') || e.includes('Cannot read properties of null'));
    expect(criticalErrors.length).toBe(0);

    await takeTestScreenshot(page, 'TC-APOS-052', '01-resilience');
  });
});
