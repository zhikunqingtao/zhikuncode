import { test, expect } from '@playwright/test';

/**
 * Helper: Navigate to the Activity (APOS) tab and wait for content
 */
async function navigateToActivityTab(page: import('@playwright/test').Page) {
  // Click the Activity tab (icon-only button with title attribute)
  const activityTab = page.locator('button[title="Activity"]');
  await expect(activityTab).toBeVisible({ timeout: 5000 });
  await activityTab.click();
  // Wait for ActivityStream to mount and render
  await page.waitForTimeout(800);
}

/**
 * Helper: Locate activity cards by data-testid.
 */
function getActivityCards(page: import('@playwright/test').Page) {
  return page.locator('[data-testid="activity-card-l1"]');
}

test.describe('APOS Phase 2 - Activity Cards', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/');
    await page.waitForLoadState('networkidle');
    // Wait for mock data injection (500ms delay + buffer)
    await page.waitForTimeout(1500);
  });

  test('TC-P2-005: Activity cards display with mock data', async ({ page }) => {
    await navigateToActivityTab(page);

    // Wait for activity cards to appear (data-testid added to ActivityCardL1)
    const cards = getActivityCards(page);
    await expect(cards.first()).toBeVisible({ timeout: 10000 });

    // Verify empty state is NOT shown
    await expect(page.getByText('暂无活动记录')).not.toBeVisible();

    // Count activity cards
    const count = await cards.count();
    expect(count).toBeGreaterThanOrEqual(1);
    expect(count).toBeLessThanOrEqual(10);
  });

  test('TC-P2-006: Activity cards show correct count (5 mock activities)', async ({ page }) => {
    await navigateToActivityTab(page);

    // Wait for activity cards to appear
    const cards = getActivityCards(page);
    await expect(cards.first()).toBeVisible({ timeout: 10000 });

    // Verify 5 mock activities rendered
    await expect(cards).toHaveCount(5, { timeout: 5000 });
  });

  test('TC-P2-007: Activity cards survive session restore race condition', async ({ page }) => {
    // Wait extra time to allow session_restored to arrive and be handled
    await page.waitForTimeout(2000);

    await navigateToActivityTab(page);

    // Verify cards still visible (re-inject should have fired if needed)
    await expect(page.getByText('暂无活动记录')).not.toBeVisible({ timeout: 5000 });

    const cards = getActivityCards(page);
    await expect(cards.first()).toBeVisible({ timeout: 10000 });
    const count = await cards.count();
    expect(count).toBeGreaterThanOrEqual(1);
  });

  test('TC-P2-008: Signal filter bar works correctly', async ({ page }) => {
    await navigateToActivityTab(page);

    // Wait for all cards visible
    const cards = getActivityCards(page);
    await expect(cards).toHaveCount(5, { timeout: 10000 });

    // Click "可放行" filter (auto_approve: mock-act-001, mock-act-003 = 2 cards)
    const autoApproveFilter = page.locator('button').filter({ hasText: '可放行' });
    await autoApproveFilter.click();
    await page.waitForTimeout(500);

    await expect(cards).toHaveCount(2, { timeout: 5000 });

    // Click "全部" to restore
    const allFilter = page.locator('button').filter({ hasText: '全部' });
    await allFilter.click();
    await page.waitForTimeout(500);

    await expect(cards).toHaveCount(5, { timeout: 5000 });
  });

  test('TC-P2-009: Activity card L2 expansion on click', async ({ page }) => {
    await navigateToActivityTab(page);

    // Wait for cards
    const cards = getActivityCards(page);
    await expect(cards.first()).toBeVisible({ timeout: 10000 });

    // Click first card to expand L2
    await cards.first().click();
    await page.waitForTimeout(500);

    // L2 should show action buttons (“批准” or “查看详情”)
    const approveBtn = page.locator('button').filter({ hasText: '批准' });
    const detailBtn = page.locator('button').filter({ hasText: '查看详情' });
    await expect(approveBtn.or(detailBtn).first()).toBeVisible({ timeout: 5000 });
  });
});
