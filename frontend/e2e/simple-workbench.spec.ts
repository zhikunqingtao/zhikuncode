import { expect, test } from '@playwright/test';

async function bootWithLegacySimplePreference(page: import('@playwright/test').Page) {
  await page.addInitScript(() => {
    localStorage.setItem('zhikun.workbench.enabled', 'true');
    localStorage.setItem('zhikun.workbench.default-view', 'simple');
  });
  await page.goto('/', { waitUntil: 'domcontentloaded' });
}

test.describe('Developer workbench with legacy preferences', () => {
  test('uses the developer view despite a legacy simple preference', async ({ page }) => {
    await bootWithLegacySimplePreference(page);
    await expect(page.getByRole('heading', { name: '今天想构建什么？' })).toBeVisible();
    await expect(page.getByRole('tablist', { name: '工作台视图' })).toHaveCount(0);
    const input = page.locator('textarea[aria-label="输入消息"]');
    await expect(input).toHaveAttribute('placeholder', /输入消息/);
    await input.fill('保留这段未发送内容');
    await expect(input).toHaveValue('保留这段未发送内容');
  });

  test('keeps the page within the viewport at supported widths', async ({ page }) => {
    await bootWithLegacySimplePreference(page);
    for (const viewport of [
      { width: 1440, height: 900 },
      { width: 1280, height: 720 },
      { width: 1024, height: 768 },
      { width: 900, height: 720 },
      { width: 768, height: 720 },
      { width: 640, height: 720 },
      { width: 390, height: 844 },
    ]) {
      await page.setViewportSize(viewport);
      const dimensions = await page.evaluate(() => ({
        documentWidth: document.documentElement.scrollWidth,
        viewportWidth: document.documentElement.clientWidth,
      }));
      expect(dimensions.documentWidth).toBeLessThanOrEqual(dimensions.viewportWidth);
    }
  });

  test('keeps the view switch hidden and preserves the draft across viewport changes', async ({ page }) => {
    await bootWithLegacySimplePreference(page);
    await expect(page.getByRole('heading', { name: '今天想构建什么？' })).toBeVisible();
    const input = page.locator('textarea[aria-label="输入消息"]');
    await input.fill('跨屏幕保留草稿');
    for (const width of [1440, 1024, 768, 640, 390, 1440]) {
      await page.setViewportSize({ width, height: 900 });
      await expect(page.getByRole('tablist', { name: '工作台视图' })).toHaveCount(0);
      await expect(input).toBeVisible();
      await expect(input).toHaveValue('跨屏幕保留草稿');
    }
  });

  test('keeps session creation in the sidebar when the feature flag is disabled', async ({ page }) => {
    await page.addInitScript(() => {
      localStorage.setItem('zhikun.workbench.enabled', 'false');
      localStorage.setItem('zhikun.workbench.default-view', 'simple');
    });
    await page.goto('/', { waitUntil: 'domcontentloaded' });

    await expect(page.getByRole('tablist', { name: '工作台视图' })).toHaveCount(0);
    await expect(page.getByRole('banner').getByText('zhikuncode', { exact: true })).toBeVisible();
    await expect(page.getByRole('banner').getByRole('button', { name: '新建会话', exact: true })).toHaveCount(0);
    await expect(page.getByRole('button', { name: '新建会话', exact: true })).toBeVisible();
    // §7.1 空态 Hero（P3 起替换「开始对话」占位）
    await expect(page.getByRole('heading', { name: '今天想构建什么？' })).toBeVisible();
  });
});
