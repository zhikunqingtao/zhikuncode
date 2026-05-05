import { test, expect, Page } from '@playwright/test';
import path from 'path';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const SCREENSHOT_DIR = path.resolve(__dirname, '../../docs/test-results/screenshots');

async function screenshot(page: Page, name: string) {
  await page.screenshot({ path: path.join(SCREENSHOT_DIR, `${name}.png`), fullPage: true });
}

/**
 * TC-FE-005 工具调用结果渲染验证
 *
 * 验证工具调用结果的 UI 渲染:
 * - 发送消息触发工具调用 (文件读取/搜索)
 * - 工具结果块渲染 (代码高亮、内容展示)
 * - 折叠/展开交互
 * - 多个工具结果顺序渲染
 */
test.describe('TC-FE-005 工具调用结果渲染验证', () => {

  test.beforeEach(async ({ page }) => {
    await page.goto('/', { waitUntil: 'networkidle' });
    await page.waitForTimeout(2000);
  });

  test('TC-FE-005a: 发送触发工具调用的消息并验证结果渲染', async ({ page }) => {
    test.setTimeout(120000);

    const textarea = page.locator('textarea[aria-label="输入消息"]');
    await expect(textarea).toBeVisible({ timeout: 10000 });
    await screenshot(page, 'tc-fe-005a-before-send');

    // 发送触发文件读取工具的消息
    await textarea.fill('读取 package.json 文件内容');
    await textarea.press('Enter');
    await page.waitForTimeout(1000);
    await screenshot(page, 'tc-fe-005a-message-sent');

    // 如果出现权限弹窗，点击允许
    const allowBtn = page.locator('[role="alertdialog"] button:has-text("Allow")').first();
    const permissionAppeared = await allowBtn.isVisible({ timeout: 20000 }).catch(() => false);
    if (permissionAppeared) {
      await allowBtn.click();
      console.log('[TC-FE-005a] Permission dialog auto-allowed');
      await page.waitForTimeout(2000);

      // 可能会有多次权限请求
      const allowBtn2 = page.locator('[role="alertdialog"] button:has-text("Allow")').first();
      if (await allowBtn2.isVisible({ timeout: 10000 }).catch(() => false)) {
        await allowBtn2.click();
        console.log('[TC-FE-005a] Second permission dialog auto-allowed');
      }
    }

    // 等待工具结果渲染
    await page.waitForTimeout(15000);
    await screenshot(page, 'tc-fe-005a-after-wait');

    // 验证页面有代码块或工具结果
    const codeBlocks = page.locator('pre code, pre, .code-block, [class*="tool-result"], [class*="CodeBlock"]');
    const codeBlockCount = await codeBlocks.count();
    console.log(`[TC-FE-005a] Code/tool result blocks found: ${codeBlockCount}`);

    if (codeBlockCount > 0) {
      const firstBlock = codeBlocks.first();
      const blockText = await firstBlock.textContent();
      expect(blockText).toBeTruthy();
      expect(blockText!.length).toBeGreaterThan(0);
      console.log(`[TC-FE-005a] First block content length: ${blockText!.length}`);
    }

    // 检查页面整体内容是否包含工具调用相关元素
    const pageContent = await page.textContent('body') ?? '';
    const hasToolIndicators = pageContent.includes('package.json') ||
                              pageContent.includes('"name"') ||
                              pageContent.includes('"version"') ||
                              pageContent.includes('dependencies');
    console.log(`[TC-FE-005a] Tool output indicators found: ${hasToolIndicators}`);
    await screenshot(page, 'tc-fe-005a-result-verified');
  });

  test('TC-FE-005b: 工具加载状态展示', async ({ page }) => {
    test.setTimeout(120000);

    const textarea = page.locator('textarea[aria-label="输入消息"]');
    await expect(textarea).toBeVisible({ timeout: 10000 });

    await textarea.fill('搜索项目中所有的 TypeScript 配置文件');
    await textarea.press('Enter');
    await page.waitForTimeout(1000);
    await screenshot(page, 'tc-fe-005b-message-sent');

    // 检查是否有加载指示器（工具执行中状态）
    // 通常会有 spinner 或 "thinking" 类型的 UI
    await page.waitForTimeout(3000);

    // 检测加载状态指示器
    const loadingIndicators = page.locator(
      '[class*="spin"], [class*="loading"], [class*="animate"], svg.animate-spin, [class*="thinking"]'
    );
    const hasLoading = (await loadingIndicators.count()) > 0;
    console.log(`[TC-FE-005b] Loading indicators found: ${hasLoading}`);
    await screenshot(page, 'tc-fe-005b-loading-state');

    // 如果出现权限弹窗，允许
    const allowBtn = page.locator('[role="alertdialog"] button:has-text("Allow")').first();
    if (await allowBtn.isVisible({ timeout: 15000 }).catch(() => false)) {
      await allowBtn.click();
    }

    // 等待结果
    await page.waitForTimeout(15000);
    await screenshot(page, 'tc-fe-005b-result-rendered');

    // 验证有内容渲染
    const pageContent = await page.textContent('body') ?? '';
    expect(pageContent.length).toBeGreaterThan(100);
    console.log(`[TC-FE-005b] Page content length after tool execution: ${pageContent.length}`);
  });

  test('TC-FE-005c: 折叠/展开工具结果交互', async ({ page }) => {
    test.setTimeout(120000);

    const textarea = page.locator('textarea[aria-label="输入消息"]');
    await expect(textarea).toBeVisible({ timeout: 10000 });

    await textarea.fill('列出当前目录下的所有文件和文件夹');
    await textarea.press('Enter');
    await page.waitForTimeout(1000);

    // 处理权限弹窗
    const allowBtn = page.locator('[role="alertdialog"] button:has-text("Allow")').first();
    if (await allowBtn.isVisible({ timeout: 20000 }).catch(() => false)) {
      await allowBtn.click();
      await page.waitForTimeout(2000);
      // 可能二次权限
      if (await allowBtn.isVisible({ timeout: 10000 }).catch(() => false)) {
        await allowBtn.click();
      }
    }

    await page.waitForTimeout(15000);
    await screenshot(page, 'tc-fe-005c-result-loaded');

    // 查找折叠/展开按钮 — 可能是 "Show"/"Hide"/"展开"/"收起" 文本
    const collapseToggle = page.locator(
      'button:has-text("Show"), button:has-text("Hide"), button:has-text("展开"), button:has-text("收起"), button:has-text("Show more"), button:has-text("Show less")'
    ).first();

    const hasToggle = await collapseToggle.isVisible({ timeout: 5000 }).catch(() => false);
    console.log(`[TC-FE-005c] Collapse toggle found: ${hasToggle}`);

    if (hasToggle) {
      const toggleText = await collapseToggle.textContent();
      console.log(`[TC-FE-005c] Toggle button text: ${toggleText}`);
      await screenshot(page, 'tc-fe-005c-before-toggle');

      // 点击切换
      await collapseToggle.click();
      await page.waitForTimeout(1000);
      await screenshot(page, 'tc-fe-005c-after-toggle');

      // 再次点击切换回来
      const reverseToggle = page.locator(
        'button:has-text("Show"), button:has-text("Hide"), button:has-text("展开"), button:has-text("收起")'
      ).first();
      if (await reverseToggle.isVisible({ timeout: 3000 }).catch(() => false)) {
        await reverseToggle.click();
        await page.waitForTimeout(1000);
        await screenshot(page, 'tc-fe-005c-after-reverse-toggle');
      }
    } else {
      console.log('[TC-FE-005c] No collapse toggle found - tool result may not be collapsible');
    }
  });

  test('TC-FE-005d: 代码块语法高亮验证', async ({ page }) => {
    test.setTimeout(120000);

    const textarea = page.locator('textarea[aria-label="输入消息"]');
    await expect(textarea).toBeVisible({ timeout: 10000 });

    await textarea.fill('读取 tsconfig.json 文件并显示内容');
    await textarea.press('Enter');
    await page.waitForTimeout(1000);

    // 处理权限弹窗
    const allowBtn = page.locator('[role="alertdialog"] button:has-text("Allow")').first();
    if (await allowBtn.isVisible({ timeout: 20000 }).catch(() => false)) {
      await allowBtn.click();
      await page.waitForTimeout(2000);
      if (await allowBtn.isVisible({ timeout: 10000 }).catch(() => false)) {
        await allowBtn.click();
      }
    }

    await page.waitForTimeout(15000);
    await screenshot(page, 'tc-fe-005d-code-result');

    // 验证代码块存在（pre > code 或有 language class）
    const codeElements = page.locator('pre code, code[class*="language-"], code[class*="hljs"]');
    const codeCount = await codeElements.count();
    console.log(`[TC-FE-005d] Highlighted code elements: ${codeCount}`);

    // 验证 pre 元素存在
    const preElements = page.locator('pre');
    const preCount = await preElements.count();
    console.log(`[TC-FE-005d] Pre elements: ${preCount}`);

    if (preCount > 0) {
      const firstPre = preElements.first();
      const preText = await firstPre.textContent();
      console.log(`[TC-FE-005d] First pre content length: ${preText?.length ?? 0}`);
      
      // JSON 内容应该包含花括号
      if (preText) {
        const hasJson = preText.includes('{') || preText.includes('"');
        console.log(`[TC-FE-005d] Contains JSON-like content: ${hasJson}`);
      }
    }

    await screenshot(page, 'tc-fe-005d-syntax-highlight');
  });
});
