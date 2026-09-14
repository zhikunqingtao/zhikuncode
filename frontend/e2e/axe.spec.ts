import { test, expect, type Page } from '@playwright/test';
import { AxeBuilder, type AxeResults } from '@axe-core/playwright';

/**
 * axe.spec.ts — WCAG 2.1 A/AA 可访问性闸（指南 §10.6 / §12-4）
 *
 * 扫描面（4 个）：
 *   ① /design 画廊 light   ② /design 画廊 dark
 *   ③ 主界面（light）       ④ 设置面板打开态（light）
 *
 * 纪律：新增违规必须为 0。LEGACY_ALLOWLIST 仅登记"改造前已存在"的遗留违规
 * （逐条注明 ruleId + 目标节点签名 + 出处），任何未登记违规直接失败。
 * 登记口径：违规节点所在组件未被 P0/P1a/P1b 改动（以 git diff 为准）。
 */

/** 遗留违规登记：{ ruleId, target 片段, 原因 } —— 仅登记"改造前已存在"且所在组件未被 P0/P1 改动的违规 */
const LEGACY_ALLOWLIST: readonly { id: string; target: string; note: string }[] = [
    {
        id: 'color-contrast',
        target: 'var\\(--text-muted\\)',
        note: '旧变量 --text-muted(#94a3b8)：visualization 面板等未迁移旧组件（后续迁移时清除）；StatusBar/Header 成本显示已于 P3 迁移清除',
    },
];

const WCAG_TAGS = ['wcag2a', 'wcag2aa'];

interface FlatViolation {
    id: string;
    impact: string | null;
    target: string;
    summary: string;
}

function flatten(results: AxeResults): FlatViolation[] {
    return results.violations.flatMap((v) =>
        v.nodes.map((n) => ({
            id: v.id,
            impact: v.impact ?? null,
            target: n.target.join(' '),
            summary: `${v.help}（${v.id}，impact=${v.impact ?? 'n/a'}）→ ${n.target.join(' ')}`,
        })),
    );
}

function splitNewVsLegacy(violations: FlatViolation[]) {
    const legacy: FlatViolation[] = [];
    const fresh: FlatViolation[] = [];
    for (const v of violations) {
        const hit = LEGACY_ALLOWLIST.some(
            (a) => a.id === v.id && v.target.includes(a.target),
        );
        (hit ? legacy : fresh).push(v);
    }
    return { legacy, fresh };
}

async function scan(page: Page): Promise<AxeResults> {
    return new AxeBuilder({ page }).withTags(WCAG_TAGS).analyze();
}

/** 与 visual-regression 同源的主题播种（固定 light + 靛蓝），保证扫描确定性 */
async function seedLightTheme(page: Page): Promise<void> {
    const theme = {
        mode: 'light',
        accentColor: '#6366F1',
        fontSize: 'medium',
        fontFamily: 'monospace',
        borderRadius: 'md',
    };
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
            window.localStorage.setItem('config_cache', JSON.stringify({ theme: t, locale: 'zh-CN' }));
        } catch { /* 由 ThemeProvider 默认类兜底 */ }
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

async function openMain(page: Page): Promise<void> {
    await seedLightTheme(page);
    await page.goto('/', { waitUntil: 'domcontentloaded' });
    await page.locator('html.light').waitFor({ state: 'attached' });
    await expect(page.locator('header').first()).toBeVisible();
    await page.evaluate(() => (document.fonts ? document.fonts.ready : Promise.resolve()));
}

test.describe('axe WCAG 2.1 A/AA 闸', () => {
    for (const mode of ['light', 'dark'] as const) {
        test(`/design 画廊 - ${mode}`, async ({ page }) => {
            await page.goto('/design', { waitUntil: 'networkidle' });
            await expect(page.locator('[data-design-gallery]')).toBeVisible();
            if (mode === 'dark') {
                await page.getByRole('button', { name: 'Dark', exact: true }).click();
                await page.locator('html.dark').waitFor({ state: 'attached' });
            } else {
                await page.locator('html.light').waitFor({ state: 'attached' });
            }
            await page.evaluate(() => (document.fonts ? document.fonts.ready : Promise.resolve()));

            const results = await scan(page);
            const { legacy, fresh } = splitNewVsLegacy(flatten(results));
            if (legacy.length > 0) {
                console.info(`[axe][gallery-${mode}] 遗留违规（已登记）:\n${legacy.map((v) => v.summary).join('\n')}`);
            }
            expect(fresh.map((v) => v.summary)).toEqual([]);
        });
    }

    test('主界面 - light', async ({ page }) => {
        await openMain(page);
        const results = await scan(page);
        const { legacy, fresh } = splitNewVsLegacy(flatten(results));
        if (legacy.length > 0) {
            console.info(`[axe][main-light] 遗留违规（已登记）:\n${legacy.map((v) => v.summary).join('\n')}`);
        }
        expect(fresh.map((v) => v.summary)).toEqual([]);
    });

    test('设置面板打开态 - light', async ({ page }) => {
        await openMain(page);
        await page.locator('button[title="设置"]').click();
        await expect(page.getByRole('heading', { name: '设置' })).toBeVisible();

        const results = await scan(page);
        const { legacy, fresh } = splitNewVsLegacy(flatten(results));
        if (legacy.length > 0) {
            console.info(`[axe][settings-light] 遗留违规（已登记）:\n${legacy.map((v) => v.summary).join('\n')}`);
        }
        expect(fresh.map((v) => v.summary)).toEqual([]);
    });
});
