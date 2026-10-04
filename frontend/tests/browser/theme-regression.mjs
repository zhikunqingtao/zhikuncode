/**
 * Offline browser regression for message reading surfaces.
 * npm run test:theme-regression
 *
 * Bundles the real renderers and full styles in memory, replacing only stores.
 * No application server, API, user storage or host clipboard is used. HTTP(S)
 * requests are blocked; failure screenshots go to a temporary directory.
 */
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';
import { chromium, expect } from '@playwright/test';
import postcss from 'postcss';
import tailwindcss from 'tailwindcss';
import loadConfig from 'tailwindcss/loadConfig.js';

const frontend = fileURLToPath(new URL('../../', import.meta.url));
const require = createRequire(import.meta.url);
// Use Vite's installed build dependency rather than downloading another tool.
const esbuild = createRequire(require.resolve('vite/package.json'))('esbuild');
const configMock = `
import {useSyncExternalStore} from 'react';
let state={theme:{mode:'light',accentColor:'#12967F',inkHavocFx:{cinematic:false,motion:'off',retreat:false}}};
const listeners=new Set();
const subscribe=fn=>{listeners.add(fn);return()=>listeners.delete(fn)};
export function useConfigStore(selector){return useSyncExternalStore(subscribe,()=>selector(state))}
useConfigStore.setState=update=>{state={...state,...update};for(const fn of listeners)fn()};
`;
const bundle = await esbuild.build({
    absWorkingDir: frontend,
    entryPoints: ['tests/browser/theme-fixture.tsx'],
    bundle: true, write: false, format: 'iife', jsx: 'automatic',
    define: { 'process.env.NODE_ENV': '"production"' },
    alias: { '@': path.join(frontend, 'src') },
    loader: { '.svg': 'dataurl' },
    plugins: [{ name: 'isolated-stores', setup(build) {
        build.onResolve({ filter: /^@\/store\/(configStore|sessionStore)$/ }, args => ({
            path: args.path.endsWith('configStore') ? 'config' : 'session', namespace: 'fixture',
        }));
        build.onLoad({ filter: /.*/, namespace: 'fixture' }, args => ({
            contents: args.path === 'config' ? configMock : 'export const useSessionStore=selector=>selector({sessionId:null});',
            loader: 'js', resolveDir: frontend,
        }));
    } }],
});
const styleDir = path.join(frontend, 'src/styles');
let sourceCss = await fs.readFile(path.join(styleDir, 'globals.css'), 'utf8');
for (const match of [...sourceCss.matchAll(/@import ['"]\.\/(.*?)['"];?/g)]) {
    sourceCss = sourceCss.replace(match[0], await fs.readFile(path.join(styleDir, match[1]), 'utf8'));
}
sourceCss += await fs.readFile(path.join(styleDir, 'interface-refinements.css'), 'utf8');
const tailwindConfig = loadConfig(path.join(frontend, 'tailwind.config.ts'));
tailwindConfig.content = [path.join(frontend, 'src/**/*.{ts,tsx}'), path.join(frontend, 'tests/browser/theme-fixture.tsx')];
const css = (await postcss([tailwindcss(tailwindConfig)]).process(sourceCss, { from: undefined })).css;
const artifacts = await fs.mkdtemp(path.join(os.tmpdir(), 'zhikuncode-theme-regression-'));
const browser = await chromium.launch({ headless: true });
let assertions = 0;

// Compute contrast from actual browser styles, compositing translucent surfaces.
async function readColors(locator) {
    return locator.evaluateAll(elements => {
        const canvas = document.createElement('canvas');
        canvas.width = canvas.height = 1;
        const ctx = canvas.getContext('2d');
        const rgba = color => {
            ctx.clearRect(0, 0, 1, 1);
            ctx.fillStyle = color;
            ctx.fillRect(0, 0, 1, 1);
            const [r, g, b, a] = ctx.getImageData(0, 0, 1, 1).data;
            return [r, g, b, a / 255];
        };
        const over = (fg, bg) => fg.slice(0, 3).map((v, i) => v * fg[3] + bg[i] * (1 - fg[3]));
        const luminance = rgb => rgb.map(v => v / 255).map(v => v <= .04045 ? v / 12.92 : ((v + .055) / 1.055) ** 2.4)
            .reduce((sum, v, i) => sum + v * [.2126, .7152, .0722][i], 0);
        return elements.map(el => {
            const ancestors = [];
            for (let node = el; node; node = node.parentElement) ancestors.unshift(node);
            let bg = [255, 255, 255];
            for (const node of ancestors) bg = over(rgba(getComputedStyle(node).backgroundColor), bg);
            const style = getComputedStyle(el);
            const fg = over(rgba(style.color), bg);
            const a = luminance(fg), b = luminance(bg);
            return { text: el.textContent?.slice(0, 45), foreground: fg, background: bg,
                ratio: (Math.max(a, b) + .05) / (Math.min(a, b) + .05) };
        });
    });
}

async function readable(page, selector, threshold = 4.5) {
    const colors = await readColors(page.locator(selector));
    assert.ok(colors.length, `Missing contrast target: ${selector}`);
    for (const color of colors) {
        assert.ok(color.ratio >= threshold - .01,
            `${selector}: ${color.ratio.toFixed(2)} < ${threshold}: ${JSON.stringify(color)}`);
        assertions += 1;
    }
}

async function sameCodeColors(page, inside, outside) {
    const selectors = ['.code-block', '.code-block > div:first-child > span:first-child', '.code-block button', '.code-block pre'];
    for (const suffix of selectors) {
        const a = await readColors(page.locator(`${inside} ${suffix}`));
        const b = await readColors(page.locator(`${outside} ${suffix}`));
        assert.deepEqual(a.map(({ foreground, background }) => ({ foreground, background })),
            b.map(({ foreground, background }) => ({ foreground, background })), `${inside}: ${suffix} inherits bubble colors`);
        assertions += 1;
    }
}

try {
    for (const width of [1440, 390]) {
        const context = await browser.newContext({ viewport: { width, height: 900 }, reducedMotion: 'reduce', serviceWorkers: 'block' });
        const page = await context.newPage();
        const errors = [];
        const businessRequests = [];
        page.on('pageerror', error => errors.push(error.message));
        await page.route('**/*', route => {
            if (/\/api\/|\/ws\//.test(route.request().url())) businessRequests.push(route.request().url());
            return route.abort();
        });
        await page.setContent(`<html><head><style>${css}</style></head><body><div id="root"></div></body></html>`);
        await page.addScriptTag({ content: bundle.outputFiles[0].text });
        for (const [mode, rich] of [
            ['ink-havoc', true], ['ink-havoc', false], ['ink-havoc-night', true], ['ink-havoc-night', false],
            ['light', false], ['dark', false], ['glass', false], ['spaceship', false],
        ]) {
            const label = `${mode}-${rich ? 'rich' : 'calm'}-${width}`;
            try {
                await page.evaluate(([mode, rich]) => window.configureTheme(mode, rich), [mode, rich]);
                await expect(page.locator('main')).toHaveAttribute('data-theme', mode);
                await expect(page.locator('main')).toHaveAttribute('data-rich', String(rich));
                await expect(page.locator('#long button', { hasText: 'Enable highlighting' })).toBeVisible();
                await expect(page.locator('#diagram [role="region"] svg')).toBeVisible();
                await expect(page.locator('#error')).toContainText('Mermaid 渲染失败');
                await expect(page.locator('#loading')).toContainText('Mermaid 图表加载中');
                if (mode.startsWith('ink-')) {
                    await sameCodeColors(page, '#short', '#standalone-short');
                    await sameCodeColors(page, '#long', '#standalone-long');
                    for (const selector of ['#prose .text-block > p', '#prose a', '#prose code', '#table th', '#table td',
                        '#table a', '#table code', '#short .token', '#short .react-syntax-highlighter-line-number',
                        '#long pre', '.code-block > div:first-child > span:first-child', '#long .code-block button']) {
                        await readable(page, selector);
                    }
                    await readable(page, '.code-block button[aria-label="Copy code"]', 3);
                    await readable(page, '#loading .mermaid-loading-label, #error .mermaid-error-heading, #error .mermaid-error-source');
                    await readable(page, '#diagram .mermaid-export-button', 3);
                    for (const selector of ['#table a', '#long button:has-text("Enable highlighting")']) {
                        await page.locator(selector).first().hover();
                        await readable(page, selector);
                    }
                    for (const selector of ['#long button[aria-label="Copy code"]', '#diagram .mermaid-export-button']) {
                        await page.locator(selector).first().hover();
                        await readable(page, selector, 3);
                    }
                    if (rich) {
                        for (const selector of ['#prose blockquote', '#prose h3', '#prose .message-timestamp',
                            '#mixed .message-copy-all', '#disclosure .user-message-disclosure > span', '#diagram .markdown-embed', '#loading .markdown-embed']) {
                            await readable(page, selector);
                        }
                        await readable(page, '#prose .markdown-task-marker', 3);
                        for (const selector of ['#prose a', '#mixed .message-copy-all', '#disclosure .user-message-disclosure']) {
                            await page.locator(selector).first().hover();
                            await readable(page, selector);
                        }
                        await page.locator('#prose .message-action-button').hover();
                        await readable(page, '#prose .message-action-button', 3);
                        // The embedding surface, rather than the bubble, must be opaque in every Mermaid state.
                        for (const id of ['diagram', 'loading', 'error']) {
                            const background = await page.locator(`#${id} .markdown-embed`).evaluate(el => getComputedStyle(el).backgroundColor);
                            assert.ok(!background.includes('rgba'), `${id} embedded background is not opaque: ${background}`);
                        }
                    }
                }
                await page.locator('#long button', { hasText: 'Enable highlighting' }).click();
                await expect(page.locator('#long .react-syntax-highlighter-line-number')).toHaveCount(100);
                if (mode.startsWith('ink-')) await readable(page, '#long .token, #long .react-syntax-highlighter-line-number');
                await page.locator('#long button[aria-label="Copy code"]').click();
                assert.ok((await page.evaluate(() => window.copiedText)).startsWith('// A readable explanation'));
                if (mode.startsWith('ink-')) await readable(page, '#long .lucide-check', 3);
                await page.locator('#diagram button[title="复制 SVG"]').click();
                assert.ok((await page.evaluate(() => window.copiedText)).includes('<svg'));
                const download = page.waitForEvent('download');
                await page.locator('#diagram button[title="下载 PNG"]').click();
                assert.equal((await download).suggestedFilename(), 'mermaid-diagram.png');
                await page.locator('#mixed [data-testid="message-copy-all-button"]').click();
                assert.ok((await page.evaluate(() => window.copiedText)).includes('图文消息'));
                if (mode !== 'spaceship') {
                    await page.locator('#mixed [aria-label="Zoom image"]').click();
                    await expect(page.locator('[aria-label="Close zoom"]')).toBeVisible();
                    if (mode.startsWith('ink-')) {
                        await readable(page, '[aria-label="Close zoom"], [aria-label="Copy image"]', 3);
                    }
                    const previousCopies = await page.evaluate(() => window.copiedImages);
                    await page.locator('[aria-label="Copy image"]').click();
                    await expect.poll(() => page.evaluate(() => window.copiedImages)).toBe(previousCopies + 1);
                    await page.locator('[aria-label="Close zoom"]').click();
                } else {
                    // Existing HEAD behavior: spaceship.css clips rounded message bubbles,
                    // including ImageBlock's inline fixed overlay. Both files are unchanged
                    // by this fix. Keep this limitation explicit instead of forcing a click.
                    console.log(`SKIP ${label} image overlay: existing spaceship clip-path clips its controls`);
                }
                await page.locator('#disclosure button[aria-expanded]').click();
                await expect(page.locator('#disclosure .text-block')).toBeVisible();
                await page.locator('#disclosure button[aria-expanded]').click();
                assert.deepEqual(errors, [], 'Unexpected browser exceptions');
                assert.deepEqual(businessRequests, [], 'Fixture attempted to contact a business service');
                console.log(`PASS ${label}`);
            } catch (error) {
                await page.screenshot({ path: path.join(artifacts, `${label}.png`), fullPage: true });
                throw new Error(`${label}: ${error.message}\nScreenshot directory: ${artifacts}`, { cause: error });
            }
        }
        await context.close();
    }
    console.log(`Theme regression passed: 16 scenarios, ${assertions} color/isolation assertions; no business requests.`);
} finally {
    await browser.close();
}
