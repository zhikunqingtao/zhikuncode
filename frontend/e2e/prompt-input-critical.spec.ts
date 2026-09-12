import { test, expect } from '@playwright/test';

/**
 * P2 输入区关键路径 E2E（§8.3 拆分前补充基线）
 *
 * 目的：在 PromptInput 拆分重构前锁定既有 UI 接线行为，
 * 拆分（零行为变化）与移动形态改造后本组用例必须保持全绿。
 * 断言均不依赖 LLM 响应，以现有行为为准。
 */
test.describe('P2 PromptInput 关键路径', () => {

  test.beforeEach(async ({ page }) => {
    await page.goto('/', { waitUntil: 'networkidle' });
    await expect(page.locator('textarea[aria-label="输入消息"]'))
      .toBeVisible({ timeout: 15000 });
  });

  test('PI-01: 输入区结构 — textarea 自动聚焦 + 工具栏按钮在位', async ({ page }) => {
    const textarea = page.locator('textarea[aria-label="输入消息"]');
    // autoFocus 生效
    await expect(textarea).toBeFocused();

    // 发送按钮在位，空输入时禁用
    const sendBtn = page.locator('button[aria-label="发送消息"]');
    await expect(sendBtn).toBeVisible();
    await expect(sendBtn).toBeDisabled();

    // 本地文件引用按钮（aria-label 随能力二选一）
    const fileRefBtn = page.locator(
      'button[aria-label="引用本地文件路径"], button[aria-label="上传本地文件到 OSS"]',
    );
    await expect(fileRefBtn).toHaveCount(1);

    // 图片上传按钮
    await expect(page.locator('button[aria-label="上传图片"]')).toBeVisible();
  });

  test('PI-02: 发送按钮随输入内容启用/禁用', async ({ page }) => {
    const textarea = page.locator('textarea[aria-label="输入消息"]');
    const sendBtn = page.locator('button[aria-label="发送消息"]');

    await textarea.fill('hello');
    await expect(sendBtn).toBeEnabled();

    await textarea.fill('');
    await expect(sendBtn).toBeDisabled();

    // 纯空白不启用发送
    await textarea.fill('   ');
    await expect(sendBtn).toBeDisabled();
  });

  test('PI-03: Shift+Enter 换行且不发送', async ({ page }) => {
    const textarea = page.locator('textarea[aria-label="输入消息"]');

    await textarea.fill('line1');
    await textarea.press('Shift+Enter');
    await textarea.pressSequentially('line2');

    // 换行成功且草稿未被提交清空
    await expect(textarea).toHaveValue('line1\nline2');
  });

  test('PI-04: textarea 自动增高且封顶 200px', async ({ page }) => {
    const textarea = page.locator('textarea[aria-label="输入消息"]');

    const initialHeight = (await textarea.boundingBox())?.height ?? 0;
    expect(initialHeight).toBeGreaterThan(0);

    await textarea.fill(Array.from({ length: 20 }, (_, i) => `line ${i}`).join('\n'));
    await page.waitForTimeout(200);

    const grownHeight = (await textarea.boundingBox())?.height ?? 0;
    expect(grownHeight).toBeGreaterThan(initialHeight);
    // Auto-resize 逻辑封顶 Math.min(scrollHeight, 200)
    expect(grownHeight).toBeLessThanOrEqual(201);
  });

  test('PI-05: 输入 / 弹出命令补全，Escape 关闭且保留草稿', async ({ page }) => {
    const textarea = page.locator('textarea[aria-label="输入消息"]');

    await textarea.fill('/');
    // CommandPalette 底部固定提示（不依赖后端命令列表内容）
    const paletteFooter = page.getByText('↵ Select');
    await expect(paletteFooter).toBeVisible({ timeout: 5000 });

    await textarea.press('Escape');
    await expect(paletteFooter).toBeHidden();
    // Escape 只关面板，不清空输入
    await expect(textarea).toHaveValue('/');
  });
});
