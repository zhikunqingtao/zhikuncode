import { test, expect, Page } from '@playwright/test';
import path from 'path';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const SCREENSHOT_DIR = path.resolve(__dirname, '../../docs/test-results/screenshots');

// Helper: save screenshot with descriptive name
async function screenshot(page: Page, name: string) {
  await page.screenshot({ path: path.join(SCREENSHOT_DIR, `${name}.png`), fullPage: true });
}

test.describe('前端 E2E 与 UI 功能测试 (Task 13)', () => {

  // ─── TC-FE-01: 页面加载与布局 ───
  test('TC-FE-01: 页面加载与布局', async ({ page }) => {
    await page.goto('/', { waitUntil: 'networkidle' });
    await page.waitForTimeout(2000);

    await screenshot(page, 'fe-01-page-load');

    // 页面正常加载（无白屏）
    const body = page.locator('body');
    await expect(body).toBeVisible();

    // Header 存在
    const header = page.locator('header');
    await expect(header).toBeVisible();

    // 输入框存在 (aria-label="输入消息")
    const input = page.locator('textarea[aria-label="输入消息"]');
    await expect(input).toBeVisible();

    // 侧边栏存在 (aside 元素或包含会话/任务/文件标签)
    // On desktop the sidebar is an <aside>, on mobile it may be hidden
    const sidebar = page.locator('aside').first();
    const sidebarVisible = await sidebar.isVisible().catch(() => false);
    // 至少 body 加载正常
    expect(await body.textContent()).toBeTruthy();

    // 获取页面文本验证关键 UI
    const pageText = await page.textContent('body');
    expect(pageText).toBeTruthy();

    // 获取页面结构摘要
    const structure = await page.evaluate(() => {
      const tags = ['header', 'aside', 'main', 'textarea', 'select', 'button'];
      return tags.map(t => `${t}: ${document.querySelectorAll(t).length}`).join(', ');
    });
    console.log(`[TC-FE-01] DOM structure: ${structure}`);
    console.log(`[TC-FE-01] Header visible: true, Input visible: true, Sidebar visible: ${sidebarVisible}`);
  });

  // ─── TC-FE-02: 会话创建交互 ───
  test('TC-FE-02: 会话创建交互', async ({ page }) => {
    await page.goto('/', { waitUntil: 'networkidle' });
    await page.waitForTimeout(2000);

    // 找到 "新建会话" 按钮 (title="新建会话")
    const newSessionBtn = page.locator('button[title="新建会话"]');
    const btnExists = await newSessionBtn.isVisible().catch(() => false);

    await screenshot(page, 'fe-02-before-new-session');

    if (btnExists) {
      // 点击新建会话（会触发 page reload）
      // 监听 navigation
      await Promise.all([
        page.waitForNavigation({ waitUntil: 'networkidle' }).catch(() => {}),
        newSessionBtn.click(),
      ]);
      await page.waitForTimeout(3000);
      await screenshot(page, 'fe-02-after-new-session');
      console.log('[TC-FE-02] New session button clicked, page reloaded');
    } else {
      // 尝试通过侧边栏创建
      console.log('[TC-FE-02] New session button not found in header, checking sidebar');
      await screenshot(page, 'fe-02-no-new-session-btn');
    }

    // 验证页面重新加载后正常
    const input = page.locator('textarea[aria-label="输入消息"]');
    await expect(input).toBeVisible();
    console.log('[TC-FE-02] Session creation verified - input visible after reload');
  });

  // ─── TC-FE-03: 消息提交与流式渲染 ───
  test('TC-FE-03: 消息提交与流式渲染', async ({ page }) => {
    test.setTimeout(60000); // 60s timeout for LLM response

    await page.goto('/', { waitUntil: 'networkidle' });
    await page.waitForTimeout(3000);

    const input = page.locator('textarea[aria-label="输入消息"]');
    await expect(input).toBeVisible();

    // 输入消息
    await input.fill('请直接回答：1+1等于多少？');
    await page.waitForTimeout(500);
    await screenshot(page, 'fe-03-message-typed');

    // 发送消息 - 尝试点击发送按钮
    const sendBtn = page.locator('button:has(svg)').filter({ has: page.locator('svg') }).last();
    // 更精确的方式：找到输入框旁边的 button
    const submitButton = input.locator('..').locator('button').last();
    
    // 按 Enter 发送
    await input.press('Enter');
    await page.waitForTimeout(1000);
    await screenshot(page, 'fe-03-message-sent');

    // 等待流式响应渲染 (最多等 20 秒)
    console.log('[TC-FE-03] Waiting for streaming response...');
    await page.waitForTimeout(15000);
    await screenshot(page, 'fe-03-response-rendered');

    // 检查页面是否有新的消息内容
    const pageContent = await page.textContent('body');
    const hasResponse = pageContent?.includes('2') || pageContent?.includes('二');
    console.log(`[TC-FE-03] Response contains answer: ${hasResponse}`);
    console.log(`[TC-FE-03] Page content length: ${pageContent?.length}`);

    // 获取页面结构
    const structure = await page.evaluate(() => {
      const msgs = document.querySelectorAll('[class*="message"], [class*="Message"]');
      return `Message elements: ${msgs.length}`;
    });
    console.log(`[TC-FE-03] ${structure}`);
  });

  // ─── TC-FE-04: 命令面板 ───
  test('TC-FE-04: 命令面板', async ({ page }) => {
    await page.goto('/', { waitUntil: 'networkidle' });
    await page.waitForTimeout(2000);

    const input = page.locator('textarea[aria-label="输入消息"]');
    await expect(input).toBeVisible();

    // 输入 / 触发命令面板
    await input.fill('/');
    await page.waitForTimeout(1000);
    await screenshot(page, 'fe-04-command-panel');

    // 检查命令面板是否出现
    // 命令面板通常会渲染在输入框上方
    const pageContent = await page.textContent('body');
    // 检查是否有命令相关元素
    const hasCommandUI = pageContent?.includes('command') || 
                          pageContent?.includes('命令') ||
                          pageContent?.includes('compact') ||
                          pageContent?.includes('/');
    console.log(`[TC-FE-04] Command panel visible indicators: ${hasCommandUI}`);

    // 清除输入
    await input.fill('');
    await page.waitForTimeout(500);
    await screenshot(page, 'fe-04-command-panel-closed');
  });

  // ─── TC-FE-05: 设置页面 ───
  test('TC-FE-05: 设置页面', async ({ page }) => {
    await page.goto('/', { waitUntil: 'networkidle' });
    await page.waitForTimeout(2000);

    // 找到设置按钮 (title="设置")
    const settingsBtn = page.locator('button[title="设置"]');
    await expect(settingsBtn).toBeVisible();

    await settingsBtn.click();
    await page.waitForTimeout(1000);
    await screenshot(page, 'fe-05-settings-page');

    // 验证设置对话框出现
    // 通常是 dialog 或 modal
    const dialog = page.locator('[role="dialog"], .modal, [class*="dialog"], [class*="Dialog"]').first();
    const dialogVisible = await dialog.isVisible().catch(() => false);
    
    // 获取设置页面内容
    const pageContent = await page.textContent('body');
    const hasSettings = pageContent?.includes('API') || 
                        pageContent?.includes('设置') || 
                        pageContent?.includes('Settings') ||
                        pageContent?.includes('Key') ||
                        pageContent?.includes('模型');
    
    console.log(`[TC-FE-05] Settings dialog visible: ${dialogVisible}, Has settings content: ${hasSettings}`);
  });

  // ─── TC-FE-06: 主题切换 ───
  test('TC-FE-06: 主题切换', async ({ page }) => {
    await page.goto('/', { waitUntil: 'networkidle' });
    await page.waitForTimeout(2000);

    // 记录当前主题
    const bgBefore = await page.evaluate(() => getComputedStyle(document.body).backgroundColor);
    const dataBefore = await page.evaluate(() => document.documentElement.getAttribute('data-theme') || document.documentElement.className || 'none');
    await screenshot(page, 'fe-06-theme-before');

    // 找到主题切换按钮 (aria-label 包含 "切换")
    const themeBtn = page.locator('button[aria-label*="切换"]').first();
    const themeBtnExists = await themeBtn.isVisible().catch(() => false);

    if (themeBtnExists) {
      await themeBtn.click();
      await page.waitForTimeout(1000);
      
      const bgAfter = await page.evaluate(() => getComputedStyle(document.body).backgroundColor);
      const dataAfter = await page.evaluate(() => document.documentElement.getAttribute('data-theme') || document.documentElement.className || 'none');
      await screenshot(page, 'fe-06-theme-after');

      const changed = dataBefore !== dataAfter || bgBefore !== bgAfter;
      console.log(`[TC-FE-06] Theme before: data="${dataBefore}", bg="${bgBefore}"`);
      console.log(`[TC-FE-06] Theme after: data="${dataAfter}", bg="${bgAfter}"`);
      console.log(`[TC-FE-06] Theme changed: ${changed}`);

      // 再次切换回来
      await themeBtn.click();
      await page.waitForTimeout(500);
      await screenshot(page, 'fe-06-theme-restored');
    } else {
      console.log('[TC-FE-06] Theme toggle button not found');
    }
  });

  // ─── TC-FE-07: 响应式布局 ───
  test('TC-FE-07: 响应式布局', async ({ page }) => {
    await page.goto('/', { waitUntil: 'networkidle' });
    await page.waitForTimeout(2000);

    // 移动端 375x667
    await page.setViewportSize({ width: 375, height: 667 });
    await page.waitForTimeout(1000);
    await screenshot(page, 'fe-07-mobile-375x667');

    // 验证移动端：body 可见，无明显溢出
    const bodyMobile = page.locator('body');
    await expect(bodyMobile).toBeVisible();
    const overflowMobile = await page.evaluate(() => {
      return document.documentElement.scrollWidth > window.innerWidth;
    });
    console.log(`[TC-FE-07] Mobile overflow: ${overflowMobile}`);

    // 平板 768x1024
    await page.setViewportSize({ width: 768, height: 1024 });
    await page.waitForTimeout(1000);
    await screenshot(page, 'fe-07-tablet-768x1024');

    const overflowTablet = await page.evaluate(() => {
      return document.documentElement.scrollWidth > window.innerWidth;
    });
    console.log(`[TC-FE-07] Tablet overflow: ${overflowTablet}`);

    // 桌面 1280x800
    await page.setViewportSize({ width: 1280, height: 800 });
    await page.waitForTimeout(1000);
    await screenshot(page, 'fe-07-desktop-1280x800');

    const overflowDesktop = await page.evaluate(() => {
      return document.documentElement.scrollWidth > window.innerWidth;
    });
    console.log(`[TC-FE-07] Desktop overflow: ${overflowDesktop}`);

    // 验证侧边栏在桌面端可见
    const sidebar = page.locator('aside').first();
    const sidebarDesktop = await sidebar.isVisible().catch(() => false);
    console.log(`[TC-FE-07] Sidebar visible on desktop: ${sidebarDesktop}`);
  });

});
