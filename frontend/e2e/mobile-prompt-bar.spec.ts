import { test, expect } from '@playwright/test';

/** 常驻移动输入卡片：填词不发送，附件菜单可用，失焦保留草稿。 */
test.describe('MobilePromptBar 常驻卡片', () => {
  test('T1: 常驻操作、模板填词、附件菜单与失焦草稿', async ({ page }) => {
    await page.goto('/', { waitUntil: 'networkidle' });
    const bar = page.getByTestId('mobile-prompt-bar');
    const input = page.getByRole('textbox', { name: '输入消息' });
    const actions = page.getByTestId('mobile-persistent-actions');
    await expect(bar).toBeVisible();
    await expect(input).toBeVisible();
    await expect(actions).toBeVisible();
    await page.getByRole('button', { name: '生成 API 文档', exact: true }).tap();
    await expect(input).toHaveValue(/API/);
    await expect(input).toBeFocused();
    await expect(page.getByText('选择文件夹授权')).toHaveCount(0);
    await page.locator('header').first().tap();
    await expect(input).toHaveValue(/API/);
    await expect(actions).toBeVisible();

    const plus = page.getByRole('button', { name: '附件与工具', exact: true });
    await plus.tap();
    const menu = page.getByRole('dialog', { name: '附件与工具' });
    await expect(menu).toBeVisible();
    await expect(menu.getByRole('button', { name: '图片附件' })).toBeVisible();
    await expect(menu.getByRole('button', { name: /文件引用|上传本地文件/ })).toBeVisible();
    await menu.getByRole('button', { name: '运行测试' }).tap();
    await expect(input).toHaveValue('运行测试');
    await expect(input).toBeFocused();
    await expect(menu).toBeHidden();
    await expect(page.getByText('选择文件夹授权')).toHaveCount(0);

    await plus.tap();
    await menu.getByRole('button', { name: '命令面板' }).tap();
    await expect(input).toHaveValue('/');
    await expect(page.getByTestId('command-palette-footer')).toBeVisible();
    await input.press('Escape');
    await expect(page.getByTestId('command-palette-footer')).toBeHidden();
    await expect(page.locator('input[data-mobile-image-input]')).toHaveAttribute('accept', 'image/*');
    const sendBox = await page.getByRole('button', { name: '发送消息' }).boundingBox();
    expect(sendBox?.width).toBeGreaterThanOrEqual(44);
    expect(sendBox?.height).toBeGreaterThanOrEqual(44);
  });

  test('T2: 输入后点发送走提交链路', async ({ page }) => {
    await page.goto('/', { waitUntil: 'networkidle' });
    const textarea = page.locator('textarea[aria-label="输入消息"]');
    await expect(textarea).toBeVisible({ timeout: 15000 });

    await textarea.tap();
    await textarea.pressSequentially('probe 提交链路验证');
    const sendBtn = page.locator('button[aria-label="发送消息"]');
    await expect(sendBtn).toBeEnabled();
    await sendBtn.tap();

    // 无后端环境的确定性 UI 反馈：提交链路进入会话授权对话框
    // （handleSubmit → onSubmit → ensureSessionReady → 授权选择）
    await expect(page.getByText('选择文件夹授权')).toBeVisible({ timeout: 10000 });

    // 取消授权 → 提交未成功，草稿保留（与桌面行为一致）
    await page.getByRole('button', { name: '取消本次选择' }).click();
    await expect(page.getByText('选择文件夹授权')).toBeHidden();
    await expect(textarea).toHaveValue('probe 提交链路验证');
  });
});
