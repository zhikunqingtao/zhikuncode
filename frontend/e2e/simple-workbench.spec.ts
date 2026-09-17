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
    // 未选择会话时，两种工作台模式都显示欢迎页。
    await expect(page.getByRole('heading', { name: '今天想构建什么？' })).toBeVisible();

    const input = page.locator('textarea[aria-label="输入消息"]');
    await expect(input).toHaveAttribute('placeholder', '描述你希望完成或继续修改的事情…');
    await input.fill('保留这段未发送内容');
    await page.getByRole('tab', { name: '开发工作台' }).click();
    // §7.1 空态 Hero（P3 起替换「开始对话」占位）
    await expect(page.getByRole('heading', { name: '今天想构建什么？' })).toBeVisible();
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

  test('keeps the desktop view switch on one row and shows mobile navigation at narrow widths', async ({ page }) => {
    await bootSimpleWorkbench(page);
    const tablist = page.getByRole('tablist', { name: '工作台视图' });
    const simpleLabel = page.getByText('简洁工作台', { exact: true });

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

      if (viewport.width < 768) {
        await expect(tablist).toBeHidden();
        await expect(page.getByRole('button', { name: '打开会话列表', exact: true })).toBeVisible();
        await expect(page.getByRole('button', { name: '返回首页', exact: true })).toBeVisible();
        continue;
      }

      // tablist 永不竖排换行：收缩后单行高度约 34px，阈值 44px
      const tablistBox = await tablist.boundingBox();
      expect(tablistBox, `tablist missing at ${viewport.width}px`).toBeTruthy();
      expect(tablistBox!.height, `tablist wrapped at ${viewport.width}px`).toBeLessThanOrEqual(44);

      // 文字 label 完整可见（≥1024px）或图标模式（<1024px）
      if (viewport.width >= 1024) {
        await expect(simpleLabel).toBeVisible();
      } else {
        await expect(simpleLabel).toBeHidden();
      }
    }
  });

  test('restores the original developer UI when the feature flag is disabled', async ({ page }) => {
    await page.addInitScript(() => {
      localStorage.setItem('zhikun.workbench.enabled', 'false');
      localStorage.setItem('zhikun.workbench.default-view', 'simple');
    });
    await page.goto('/', { waitUntil: 'domcontentloaded' });

    await expect(page.getByRole('tablist', { name: '工作台视图' })).toHaveCount(0);
    await expect(page.getByRole('banner').getByText('zhikuncode', { exact: true })).toBeVisible();
    await expect(page.getByRole('banner').getByRole('button', { name: '新建会话', exact: true })).toBeVisible();
    // §7.1 空态 Hero（P3 起替换「开始对话」占位）
    await expect(page.getByRole('heading', { name: '今天想构建什么？' })).toBeVisible();
  });
});
