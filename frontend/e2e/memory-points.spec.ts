import { test, expect } from '@playwright/test';

/**
 * 六记忆点截图归档（改造指南 §1.2，P4 交付物）
 * 归档目录：docs/test-results/memory-points/
 * 用途：设计亮点资产 + 后续回归参照。
 *
 * ① 空态 Hero ② milestone 胶囊 ③ 移动胶囊输入条 ④ 状态物理化 ⑤ 玻璃主题 ⑥ ⌘K 命令面板
 */

const OUT = '../docs/test-results/memory-points';

async function seedTheme(page: any, mode: 'light' | 'dark' | 'glass') {
    const theme = { mode, accentColor: '#6366F1', fontSize: 'medium', fontFamily: 'monospace', borderRadius: 'md' };
    await page.addInitScript((t: any) => {
        try {
            window.localStorage.setItem('ai-coder-config', JSON.stringify({
                state: { theme: t, locale: 'zh-CN', autoCompact: { enabled: true, threshold: 80 }, verbose: false, expandedView: false, outputStyle: { availableStyles: [], activeStyleName: null }, defaultModel: 'qwen3.8-max-0902' },
                version: 2,
            }));
        } catch { /* 默认兜底 */ }
    }, theme);
    await page.route('**/api/config', (r: any) => r.request().method() === 'GET'
        ? r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ theme, locale: 'zh-CN' }) })
        : r.fulfill({ status: 200, contentType: 'application/json', body: '{}' }));
    await page.route('**/api/sessions**', (r: any) => r.request().method() === 'GET'
        ? r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [], hasMore: false, nextCursor: null }) })
        : r.continue());
}

test.describe('六记忆点截图归档', () => {

    test('① 空态 Hero「今天想构建什么？」', async ({ page }) => {
        await seedTheme(page, 'light');
        await page.goto('/');
        await expect(page.getByText('今天想')).toBeVisible({ timeout: 15000 });
        await page.waitForTimeout(400);
        await page.screenshot({ path: `${OUT}/memory-01-空态Hero.png` });
    });

    test('② milestone 胶囊（工作台任务进度）', async ({ page }) => {
        await seedTheme(page, 'light');
        await page.goto('/');
        // 简单工作台视图（TaskMilestoneStrip 挂载点）；无真实任务时记录工作台首屏进度区
        const strip = page.locator('text=/任务进度/').first();
        const has = await strip.isVisible().catch(() => false);
        if (has) {
            await expect(strip).toBeVisible();
            await strip.screenshot({ path: `${OUT}/memory-02-milestone胶囊.png` });
        } else {
            // 无真实分母时按指南不显编造进度，记录工作台首屏（含空态）
            await page.screenshot({ path: `${OUT}/memory-02-工作台首屏.png` });
        }
    });

    test('③ 移动胶囊输入条（393×852）', async ({ page }) => {
        await page.setViewportSize({ width: 393, height: 852 });
        await page.goto('/');
        const bar = page.getByTestId('mobile-prompt-bar');
        await expect(bar).toBeVisible({ timeout: 15000 });
        await bar.screenshot({ path: `${OUT}/memory-03-移动胶囊输入条.png` });
        // 聚焦展开卡片态（chromium 项目无触控，用 click 代替 tap）
        await page.locator('textarea[aria-label="输入消息"]').click();
        await page.waitForTimeout(300);
        await bar.screenshot({ path: `${OUT}/memory-03b-移动输入条展开.png` });
    });

    test('④ 状态物理化（/design 画廊基元三态）', async ({ page }) => {
        await page.goto('/design');
        await expect(page.locator('[data-design-gallery]')).toBeVisible({ timeout: 15000 });
        // Button 区（variant/size/states）+ Toggle 区
        const btnSection = page.locator('section', { hasText: '01 · Button' }).first();
        await expect(btnSection).toBeVisible();
        await btnSection.screenshot({ path: `${OUT}/memory-04-状态物理化-Button.png` });
        const toggleSection = page.locator('section', { hasText: '06 · Toggle' }).first();
        await expect(toggleSection).toBeVisible();
        await toggleSection.screenshot({ path: `${OUT}/memory-04b-状态物理化-Toggle.png` });
    });

    test('⑤ 玻璃主题主界面', async ({ page }) => {
        await seedTheme(page, 'glass');
        await page.goto('/');
        await page.locator('html.glass').waitFor({ state: 'attached', timeout: 15000 });
        await page.waitForTimeout(500);
        await page.screenshot({ path: `${OUT}/memory-05-玻璃主题.png` });
    });

    test('⑥ ⌘K 命令面板', async ({ page }) => {
        await seedTheme(page, 'light');
        await page.goto('/');
        await page.locator('header').first().waitFor();
        await page.keyboard.press('Control+k');
        const palette = page.locator('input[role="combobox"]');
        await expect(palette).toBeVisible({ timeout: 5000 });
        await page.waitForTimeout(300);
        await page.screenshot({ path: `${OUT}/memory-06-命令面板.png` });
    });
});
