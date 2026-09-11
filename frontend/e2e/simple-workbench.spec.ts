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

  test('keeps the view switch and model selector on a single header row at every width', async ({ page }) => {
    await bootSimpleWorkbench(page);
    const tablist = page.getByRole('tablist', { name: '工作台视图' });
    const modelSelect = page.getByRole('combobox', { name: '模型选择' });
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

      // 模型选择器在 simple 模式下常驻可见（修复二）
      await expect(modelSelect).toBeVisible();

      // tablist 永不竖排换行：收缩后单行高度约 34px，阈值 44px
      const tablistBox = await tablist.boundingBox();
      expect(tablistBox, `tablist missing at ${viewport.width}px`).toBeTruthy();
      expect(tablistBox!.height, `tablist wrapped at ${viewport.width}px`).toBeLessThanOrEqual(44);

      // tablist 与常驻模型选择器不重叠（挤压/溢出检测）
      const selectBox = await modelSelect.boundingBox();
      expect(selectBox, `model select missing at ${viewport.width}px`).toBeTruthy();
      expect(
        tablistBox!.x + tablistBox!.width,
        `tablist overlaps model select at ${viewport.width}px`,
      ).toBeLessThanOrEqual(selectBox!.x + 1);

      // 模型选择器右缘不超过右侧“新建”按钮左缘
      const newSessionBtn = page.getByRole('button', { name: /新建/ }).first();
      const newSessionBox = await newSessionBtn.boundingBox();
      expect(newSessionBox, `new session button missing at ${viewport.width}px`).toBeTruthy();
      expect(
        selectBox!.x + selectBox!.width,
        `model select overlaps new session button at ${viewport.width}px`,
      ).toBeLessThanOrEqual(newSessionBox!.x + 1);

      // 成本指示器：≥768px（md 断点）可见
      if (viewport.width >= 768) {
        await expect(page.getByText(/\$/).first()).toBeVisible();
      }

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
    await expect(page.getByText('AI Assistant', { exact: true })).toBeVisible();
    await expect(page.getByText('开始对话', { exact: true })).toBeVisible();
  });
});
