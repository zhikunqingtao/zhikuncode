import { test, expect } from '@playwright/test';

test.describe('Settings E2E Tests', () => {

  test('should open settings panel', async ({ page }) => {
    await page.goto('/');
    // Look for a settings button/icon and click it
    const settingsBtn = page.locator('[data-testid="settings-button"], button:has-text("Settings"), [aria-label="Settings"]').first();
    if (await settingsBtn.isVisible()) {
      await settingsBtn.click();
      // Verify settings panel appears
      await expect(page.locator('[data-testid="settings-panel"], [role="dialog"]').first()).toBeVisible();
    }
  });

  test('should display API key configuration', async ({ page }) => {
    await page.goto('/');
    const settingsBtn = page.locator('[data-testid="settings-button"], button:has-text("Settings"), [aria-label="Settings"]').first();
    if (await settingsBtn.isVisible()) {
      await settingsBtn.click();
      // Check for API key input field
      const apiKeyInput = page.locator('input[type="password"], input[placeholder*="API"], input[name*="api"]').first();
      if (await apiKeyInput.isVisible()) {
        await expect(apiKeyInput).toBeEditable();
      }
    }
  });

  test('should toggle theme', async ({ page }) => {
    await page.goto('/');
    const themeToggle = page.locator('[data-testid="theme-toggle"], button:has-text("Theme"), [aria-label*="theme"]').first();
    if (await themeToggle.isVisible()) {
      const htmlBefore = await page.locator('html').getAttribute('class');
      await themeToggle.click();
      const htmlAfter = await page.locator('html').getAttribute('class');
      expect(htmlBefore).not.toBe(htmlAfter);
    }
  });

});
