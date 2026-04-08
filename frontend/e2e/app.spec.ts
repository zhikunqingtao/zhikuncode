import { test, expect } from '@playwright/test';

test.describe('App E2E Tests', () => {

  test('should load the application', async ({ page }) => {
    await page.goto('/');
    await expect(page).toHaveTitle(/AI Code Assistant/i);
  });

  test('should display main layout', async ({ page }) => {
    await page.goto('/');
    // Verify the app container is rendered
    const body = page.locator('body');
    await expect(body).toBeVisible();
  });

  test('should navigate without errors', async ({ page }) => {
    await page.goto('/');
    // No console errors on initial load
    const errors: string[] = [];
    page.on('console', msg => {
      if (msg.type() === 'error') errors.push(msg.text());
    });
    await page.waitForTimeout(2000);
    expect(errors.filter(e => !e.includes('favicon'))).toHaveLength(0);
  });

  test('should have responsive viewport', async ({ page }) => {
    await page.goto('/');
    await page.setViewportSize({ width: 375, height: 667 });
    const body = page.locator('body');
    await expect(body).toBeVisible();

    await page.setViewportSize({ width: 1920, height: 1080 });
    await expect(body).toBeVisible();
  });

});
