import { expect, test } from '@playwright/test';

async function bootSimpleWorkbench(page: import('@playwright/test').Page) {
  await page.addInitScript(() => {
    localStorage.setItem('zhikun.workbench.enabled', 'true');
    localStorage.setItem('zhikun.workbench.default-view', 'simple');
  });
  await page.goto('/', { waitUntil: 'domcontentloaded' });
}

test.describe('Local simple workbench', () => {
  test('switches views without losing the input draft', async ({ page }) => {
    await bootSimpleWorkbench(page);

    await expect(page.getByRole('tab', { name: '简洁工作台' })).toHaveAttribute('aria-selected', 'true');
    for (const heading of ['当前任务', '本次结果', '当前交付', '待我处理', '要求核验']) {
      await expect(page.getByText(heading, { exact: true }).first()).toBeVisible();
    }

    const input = page.locator('textarea[aria-label="输入消息"]');
    await expect(input).toHaveAttribute('placeholder', '描述你希望完成或继续修改的事情…');
    await input.fill('保留这段未发送内容');
    await page.getByRole('tab', { name: '开发工作台' }).click();
    await expect(page.getByText('开始对话', { exact: true })).toBeVisible();
    await expect(input).toHaveValue('保留这段未发送内容');

    await page.getByRole('tab', { name: '简洁工作台' }).click();
    await expect(input).toHaveValue('保留这段未发送内容');
  });

  test('keeps the page within the viewport at supported widths', async ({ page }) => {
    await bootSimpleWorkbench(page);
    for (const viewport of [
      { width: 1440, height: 900 },
      { width: 1280, height: 720 },
      { width: 1024, height: 768 },
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

  test('restores the original developer UI when the feature flag is disabled', async ({ page }) => {
    await page.addInitScript(() => {
      localStorage.setItem('zhikun.workbench.enabled', 'false');
      localStorage.setItem('zhikun.workbench.default-view', 'simple');
    });
    await page.goto('/', { waitUntil: 'domcontentloaded' });

    await expect(page.getByRole('tablist', { name: '工作台视图' })).toHaveCount(0);
    await expect(page.getByText('AI Assistant', { exact: true })).toBeVisible();
    await expect(page.getByText('开始对话', { exact: true })).toBeVisible();
  });
});
