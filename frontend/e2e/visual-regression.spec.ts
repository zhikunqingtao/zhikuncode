/**
 * visual-regression.spec.ts — P0 视觉基线（改造指南 §12-4）
 *
 * fixture：① 主界面空态 ② 设置面板打开态 ③ 393×852 移动主界面
 * 每个 fixture × { light, dark, glass } 三主题。
 *
 * 主题播种（双保险）：
 *  1. addInitScript 在页面脚本执行前写入 zustand persist 键 `ai-coder-config`
 *     （frontend/src/store/configStore.ts，version: 2）与降级缓存 `config_cache`；
 *  2. 拦截 /api/config —— 本地后端在线时会返回服务端主题并覆盖播种值，
 *     必须拦截才能保证基线确定性。
 *
 * 基线更新必须显式 --update-snapshots，禁止 CI 自动更新。
 */
import { test, expect, type Page, type Locator } from '@playwright/test';

type VisualTheme = 'light' | 'dark' | 'glass';
const THEMES: VisualTheme[] = ['light', 'dark', 'glass'];

interface PersistedTheme {
    mode: VisualTheme;
    accentColor: string;
    fontSize: string;
    fontFamily: string;
    borderRadius: string;
}

function buildTheme(mode: VisualTheme): PersistedTheme {
    return {
        mode,
        accentColor: '#3b82f6',
        fontSize: 'medium',
        fontFamily: 'monospace',
        borderRadius: 'md',
    };
}

/** 页面加载前播种主题 + 拦截配置接口，保证 html 落类确定性 */
async function seedTheme(page: Page, mode: VisualTheme): Promise<void> {
    const theme = buildTheme(mode);
    await page.addInitScript((t) => {
        try {
            window.localStorage.setItem('ai-coder-config', JSON.stringify({
                state: {
                    theme: t,
                    locale: 'zh-CN',
                    autoCompact: { enabled: true, threshold: 80 },
                    verbose: false,
                    expandedView: false,
                    outputStyle: { availableStyles: [], activeStyleName: null },
                    defaultModel: 'qwen3.8-max-0902',
                },
                version: 2,
            }));
            // configStore.loadConfig 失败降级路径读取的缓存
            window.localStorage.setItem('config_cache', JSON.stringify({ theme: t, locale: 'zh-CN' }));
        } catch {
            /* localStorage 不可用时由 ThemeProvider 默认类兜底 */
        }
    }, theme);
    await page.route('**/api/config', (route) => {
        if (route.request().method() === 'GET') {
            void route.fulfill({
                status: 200,
                contentType: 'application/json',
                body: JSON.stringify({ theme, locale: 'zh-CN' }),
            });
        } else {
            void route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
        }
    });
    // 会话列表固定为空：侧栏落「暂无会话记录」，基线不受本地后端存量会话
    // 及其相对时间戳（"N 分钟前" 随时间漂移）影响，保证逐像素确定性
    await page.route('**/api/sessions**', (route) => {
        if (route.request().method() === 'GET') {
            void route.fulfill({
                status: 200,
                contentType: 'application/json',
                body: JSON.stringify({ sessions: [], hasMore: false, nextCursor: null }),
            });
        } else {
            void route.continue();
        }
    });
}

/** 打开应用并等到主题类落上 <html>、主布局可见 */
async function openApp(page: Page, mode: VisualTheme): Promise<void> {
    await seedTheme(page, mode);
    await page.goto('/', { waitUntil: 'domcontentloaded' });
    await page.locator(`html.${mode}`).waitFor({ state: 'attached' });
    await expect(page.locator('header').first()).toBeVisible();
    // 等字体与首帧渲染稳定（系统字体栈，正常即刻就绪）
    await page.evaluate(() => (document.fonts ? document.fonts.ready : Promise.resolve()));
    await page.waitForTimeout(300);
}

/** 动态区域遮罩：连接状态/Token/成本数字、通知 Toast、断连提示、移动状态条 */
function dynamicMasks(page: Page): Locator[] {
    return [
        page.locator('footer'),                                   // StatusBar：连接点/延迟/Token/成本
        page.locator('div[aria-live="assertive"]'),               // ToastContainer：通知
        page.getByText('连接断开，正在重连...'),                    // 断连重连提示（可能出现）
        page.locator('div.fixed.bottom-0.left-0.right-0'),        // MobileStatusBar（移动态可能出现）
    ];
}

test.describe('视觉基线 P0（纯增量令牌层，零视觉变化）', () => {
    for (const theme of THEMES) {
        test(`①主界面空态 - ${theme}`, async ({ page }) => {
            await openApp(page, theme);
            await expect(page).toHaveScreenshot(`home-empty-${theme}.png`, {
                maxDiffPixelRatio: 0.01,
                animations: 'disabled',
                caret: 'hide',
                mask: dynamicMasks(page),
            });
        });

        test(`②设置面板打开态 - ${theme}`, async ({ page }) => {
            await openApp(page, theme);
            // Header 设置钮：title="设置"（无 aria-label，桌面视口可见）
            await page.locator('button[title="设置"]').click();
            await expect(page.getByRole('heading', { name: '设置' })).toBeVisible();
            await page.waitForTimeout(300);
            await expect(page).toHaveScreenshot(`settings-open-${theme}.png`, {
                maxDiffPixelRatio: 0.01,
                animations: 'disabled',
                caret: 'hide',
                mask: dynamicMasks(page),
            });
        });
    }

    test.describe('③移动主界面 393×852', () => {
        test.use({ viewport: { width: 393, height: 852 } });
        for (const theme of THEMES) {
            test(`③移动主界面 - ${theme}`, async ({ page }) => {
                await openApp(page, theme);
                await expect(page).toHaveScreenshot(`mobile-home-${theme}.png`, {
                    maxDiffPixelRatio: 0.01,
                    animations: 'disabled',
                    caret: 'hide',
                    mask: dynamicMasks(page),
                });
            });
        }
    });
});
