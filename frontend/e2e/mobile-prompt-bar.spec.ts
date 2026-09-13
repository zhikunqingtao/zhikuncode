import { test, expect } from '@playwright/test';

/**
 * MobilePromptBar 移动形态回归（mobile project 393×852，P2b-2a 交付物）
 *
 * 覆盖任务验收点：
 *  1. 胶囊条可见且悬浮（rounded-full + shadow-e3 + h-46）
 *  2. 点击聚焦上扩为 rounded-panel 卡片（露出完整 PromptToolbar）
 *  3. 输入后点发送走提交链路（无后端环境 → 进入会话授权对话框为确定性 UI 反馈）
 *  4. 底部渐隐遮罩存在（linear-gradient → var(--v2-bg-app)）
 *  5. 快捷条横滑可见 + chip 行为映射
 */

const SHOT_DIR = '/tmp/zhikun-p2b2a-probe';

test.describe('P2b-2a MobilePromptBar 移动形态 probe', () => {

  test('T1: 胶囊条/展开卡片/快捷条/+菜单/渐隐遮罩', async ({ page }) => {
    await page.goto('/', { waitUntil: 'networkidle' });

    // ── 1. 收起态：胶囊可见且悬浮 ─────────────────────────
    const bar = page.getByTestId('mobile-prompt-bar');
    await expect(bar).toBeVisible({ timeout: 15000 });
    const capsule = page.getByTestId('mobile-prompt-capsule');
    await expect(capsule).toBeVisible();
    await expect(capsule).toHaveClass(/rounded-full/);
    await expect(capsule).toHaveClass(/shadow-e3/);          // 悬浮阴影
    await expect(capsule).toHaveClass(/max-h-\[46px\]/);
    await expect(capsule).toHaveClass(/transition-\[border-radius,max-height\]/);
    const capsuleBox = await capsule.boundingBox();
    expect(capsuleBox).not.toBeNull();
    expect(capsuleBox!.height).toBeLessThanOrEqual(47);       // 胶囊 46px
    // [+ 圆形] 与 [发送 44px 圆形] 在位，触控目标达标
    const plusBtn = page.locator('button[aria-label="附件与文件"]');
    await expect(plusBtn).toBeVisible();
    const plusBox = await plusBtn.boundingBox();
    expect(plusBox!.width).toBeGreaterThanOrEqual(40);
    const sendBtn = page.locator('button[aria-label="发送消息"]');
    await expect(sendBtn).toBeVisible();
    const sendBox = await sendBtn.boundingBox();
    expect(sendBox!.width).toBeGreaterThanOrEqual(44);
    expect(sendBox!.height).toBeGreaterThanOrEqual(44);

    // ── 5a. 快捷条：4 chips 可见且可横滑 ──────────────────
    const chips = page.getByTestId('mobile-quick-actions');
    await expect(chips).toBeVisible();
    await expect(page.getByTestId('mobile-chip-screenshot')).toBeVisible();
    await expect(page.getByTestId('mobile-chip-file')).toBeVisible();
    await expect(page.getByTestId('mobile-chip-run-test')).toBeVisible();
    await expect(page.getByTestId('mobile-chip-commands')).toBeVisible();
    const scrollInfo = await chips.evaluate(el => ({
      scrollWidth: el.scrollWidth, clientWidth: el.clientWidth,
    }));
    expect(scrollInfo.scrollWidth).toBeGreaterThan(scrollInfo.clientWidth); // 可横滑
    const scrolled = await chips.evaluate(el => {
      el.scrollLeft = 120;
      return el.scrollLeft;
    });
    expect(scrolled).toBeGreaterThan(0);                      // 横滑生效
    await page.screenshot({ path: `${SHOT_DIR}/01-collapsed-capsule.png` });

    // ── 2. 聚焦上扩为 rounded-panel 卡片 ──────────────────
    const textarea = page.locator('textarea[aria-label="输入消息"]');
    await textarea.tap();
    await expect(textarea).toBeFocused();
    await expect(capsule).toHaveClass(/rounded-panel/);
    await expect(capsule).toHaveClass(/max-h-\[40dvh\]/);
    // 卡片内露出完整 PromptToolbar（本地文件引用 / 图片上传按钮）
    await expect(page.locator('button[aria-label="引用本地文件路径"]')).toBeVisible();
    await expect(page.locator('button[aria-label="上传图片"]')).toBeVisible();
    await page.waitForTimeout(250);                           // 等 180ms 形态动画
    const expandedBox = await capsule.boundingBox();
    expect(expandedBox!.height).toBeGreaterThan(47);          // 上扩
    await page.screenshot({ path: `${SHOT_DIR}/02-expanded-card.png` });

    // ── 多行内容保持展开，失焦单行收起 ─────────────────────
    await textarea.pressSequentially('单行草稿');
    await page.locator('header').first().tap();               // 焦点移到条外
    await expect(capsule).toHaveClass(/rounded-full/, { timeout: 5000 });

    // ── 5b. chip 行为：运行测试 = 填词聚焦不提交 ───────────
    await page.getByTestId('mobile-chip-run-test').tap();
    await expect(textarea).toHaveValue('运行测试');
    await expect(textarea).toBeFocused();                     // 聚焦即展开
    await expect(page.getByText('选择文件夹授权')).toHaveCount(0); // 未触发提交

    // ── 5c. chip 行为：命令面板 = 打开斜杠命令面板 ─────────
    await page.getByTestId('mobile-chip-commands').tap();
    await expect(textarea).toHaveValue('/');
    await expect(page.getByTestId('command-palette-footer')).toBeVisible({ timeout: 5000 });
    await textarea.press('Escape');
    await expect(page.getByTestId('command-palette-footer')).toBeHidden();

    // ── + 菜单：图片附件（handleFiles）/ 文件引用 ─────────
    await plusBtn.tap();
    const menu = page.getByRole('menu', { name: '附件与文件' });
    await expect(menu).toBeVisible();
    await expect(menu.getByText('图片附件')).toBeVisible();
    await expect(menu.getByText('文件引用')).toBeVisible();
    // 隐藏图片选择器就位（截图分析 chip 的实际动作目标）
    await expect(page.locator('input[data-mobile-image-input]'))
      .toHaveAttribute('accept', 'image/*');
    await page.mouse.click(200, 300);                         // 点遮罩关闭
    await expect(menu).toBeHidden();

    // ── 4. 底部渐隐遮罩（注入一条消息使 MessageList 渲染） ──
    await page.evaluate(async () => {
      const mod = await import('/src/store/messageStore.ts');
      (mod as any).useMessageStore.setState({
        messages: [{
          uuid: 'probe-fade-1',
          type: 'user',
          timestamp: Date.now(),
          content: [{ type: 'text', text: 'probe：验证底部渐隐遮罩' }],
        }],
      });
    });
    const fade = page.getByTestId('mobile-message-fade');
    await expect(fade).toBeVisible();
    const fadeStyle = await fade.evaluate(el => ({
      background: getComputedStyle(el).backgroundImage,
      pointerEvents: getComputedStyle(el).pointerEvents,
      height: el.getBoundingClientRect().height,
    }));
    expect(fadeStyle.background).toContain('linear-gradient');
    expect(fadeStyle.pointerEvents).toBe('none');
    expect(fadeStyle.height).toBe(96);                        // 96px 渐隐
    await page.screenshot({ path: `${SHOT_DIR}/03-message-fade.png` });
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
    await page.screenshot({ path: `${SHOT_DIR}/04-submit-chain-dialog.png` });

    // 取消授权 → 提交未成功，草稿保留（与桌面行为一致）
    await page.getByRole('button', { name: '取消本次选择' }).click();
    await expect(page.getByText('选择文件夹授权')).toBeHidden();
    await expect(textarea).toHaveValue('probe 提交链路验证');
  });
});
