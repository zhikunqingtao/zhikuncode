/** Offline interaction/lifecycle regression using real Jelly components and CSS.
 * node tests/browser/jelly-regression.mjs — no server, user data or host clipboard.
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
const esbuild = createRequire(require.resolve('vite/package.json'))('esbuild');
const configMock = `
import {useSyncExternalStore} from 'react';
let state={theme:{mode:'jelly',jellyFx:{cinematic:true,motion:'full'}}};
const listeners=new Set();
const subscribe=fn=>{listeners.add(fn);return()=>listeners.delete(fn)};
export function useConfigStore(selector){return useSyncExternalStore(subscribe,()=>selector(state))}
useConfigStore.setState=update=>{state={...state,...update};for(const fn of listeners)fn()};
useConfigStore.getState=()=>state;useConfigStore.subscribe=subscribe;
export const defaultJellyFx=()=>({cinematic:true,motion:'full'});
`;
const mocks = {
    configStore: configMock,
    sessionStore: 'export const useSessionStore=selector=>selector({sessionId:null});',
    activityStore: 'const state={decisionRequests:new Map()};export const useActivityStore=selector=>selector(state);',
    messageStore: 'const state={activeToolCalls:new Map()};export const useMessageStore=selector=>selector(state);',
};
const bundle = await esbuild.build({
    absWorkingDir: frontend, entryPoints: ['tests/browser/jelly-fixture.tsx'],
    bundle: true, write: false, format: 'iife', jsx: 'automatic',
    define: { 'process.env.NODE_ENV': '"production"' },
    alias: { '@': path.join(frontend, 'src') }, loader: { '.svg': 'dataurl' },
    plugins: [{ name: 'isolated-stores', setup(build) {
        build.onResolve({ filter: /^@\/store\/(configStore|sessionStore|activityStore|messageStore)$/ }, args => ({
            path: args.path.split('/').pop(), namespace: 'fixture',
        }));
        build.onLoad({ filter: /.*/, namespace: 'fixture' }, args => ({
            contents: mocks[args.path], loader: 'js', resolveDir: frontend,
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
tailwindConfig.content = [path.join(frontend, 'src/**/*.{ts,tsx}'), path.join(frontend, 'tests/browser/jelly-fixture.tsx')];
const css = (await postcss([tailwindcss(tailwindConfig)]).process(sourceCss, { from: undefined })).css;
const artifacts = await fs.mkdtemp(path.join(os.tmpdir(), 'zhikuncode-jelly-regression-'));
const browser = await chromium.launch({ headless: true });
let scenarios = 0;

async function configure(page, motion) {
    await page.evaluate(m => window.jellyFixture.configure(m), motion);
    await expect(page.locator('main')).toHaveAttribute('data-motion', motion);
}
async function engine(page) { return page.evaluate(() => window.jellyFixture.engine()); }
async function readable(locator) {
    const colors = await locator.evaluateAll(elements => {
        const canvas = document.createElement('canvas');
        canvas.width = canvas.height = 1;
        const ctx = canvas.getContext('2d');
        const rgba = color => {
            ctx.clearRect(0, 0, 1, 1); ctx.fillStyle = color; ctx.fillRect(0, 0, 1, 1);
            const [r, g, b, a] = ctx.getImageData(0, 0, 1, 1).data;
            return [r, g, b, a / 255];
        };
        const over = (fg, bg) => fg.slice(0, 3).map((v, i) => v * fg[3] + bg[i] * (1 - fg[3]));
        const luminance = rgb => rgb.map(v => v / 255).map(v => v <= .04045 ? v / 12.92 : ((v + .055) / 1.055) ** 2.4)
            .reduce((sum, v, i) => sum + v * [.2126, .7152, .0722][i], 0);
        return elements.map(el => {
            const ancestors = [];
            for (let node = el; node; node = node.parentElement) ancestors.unshift(node);
            let bg = [255, 255, 255], opacity = 1;
            for (const node of ancestors) {
                const style = getComputedStyle(node);
                bg = over(rgba(style.backgroundColor), bg); opacity *= Number(style.opacity);
            }
            const fg = rgba(getComputedStyle(el).color); fg[3] *= opacity;
            const a = luminance(over(fg, bg)), b = luminance(bg);
            return { text: el.textContent, ratio: (Math.max(a, b) + .05) / (Math.min(a, b) + .05) };
        });
    });
    assert.ok(colors.length, 'Missing contrast target');
    for (const color of colors) assert.ok(color.ratio >= 4.5, `Unreadable text: ${JSON.stringify(color)}`);
}
async function imagePreview(page, width, keyboard) {
    const zoom = page.locator('#image [aria-label="Zoom image"]');
    if (keyboard) { await zoom.focus(); await page.keyboard.press('Enter'); }
    else await zoom.click();
    const close = page.getByRole('button', { name: 'Close zoom' });
    await expect(close).toBeVisible();
    const overlay = close.locator('..').locator('..');
    const box = await overlay.boundingBox();
    assert.deepEqual(box, { x: 0, y: 0, width, height: 900 }, 'Image overlay must fill viewport');
    assert.ok(await overlay.evaluate(el => el.parentElement === document.body), 'Overlay must escape the message');
    await overlay.locator('img').click();
    await expect(close).toBeVisible();
    const before = await page.evaluate(() => window.jellyFixture.copiedImages);
    await page.getByRole('button', { name: 'Copy image', exact: true }).click();
    await expect.poll(() => page.evaluate(() => window.jellyFixture.copiedImages)).toBe(before + 1);
    if (keyboard) { await close.focus(); await page.keyboard.press('Enter'); }
    else await close.click();
    await expect(close).toHaveCount(0);
}

try {
    for (const width of [1440, 390]) {
        const context = await browser.newContext({ viewport: { width, height: 900 }, reducedMotion: 'no-preference', serviceWorkers: 'block' });
        const page = await context.newPage();
        const errors = [], businessRequests = [];
        let stage = 'startup';
        page.on('pageerror', error => errors.push(error.message));
        await page.route('**/*', route => {
            if (/\/api\/|\/ws\//.test(route.request().url())) businessRequests.push(route.request().url());
            return route.abort();
        });
        try {
            await page.setContent(`<html><head><style>${css}</style></head><body><div id="root"></div></body></html>`);
            await page.addScriptTag({ content: bundle.outputFiles[0].text });
            await expect(page.locator('main')).toHaveAttribute('data-motion', 'full');
            await expect.poll(async () => (await engine(page)).ticking).toBe(true);
            stage = 'send-edge';
            for (const variant of ['desktop', 'mobile']) {
                const send = page.locator(`[data-send="${variant}"] button`);
                for (const [edge, delay] of [[true, 250], [true, 400], [false, 250]]) {
                    await page.mouse.move(0, 0);
                    const box = await send.boundingBox();
                    await page.evaluate(() => { window.jellyFixture.sent = 0; });
                    await page.mouse.click(box.x + box.width / 2, box.y + (edge ? 2 : box.height / 2), { delay });
                    assert.equal(await page.evaluate(() => window.jellyFixture.sent), 1, `${variant}: ${edge ? 'edge' : 'center'} ${delay}ms`);
                    scenarios += 1;
                }
            }
            for (const motion of ['full', 'reduced', 'off']) {
                stage = `image-${motion}`;
                await configure(page, motion);
                await imagePreview(page, width, false);
                await imagePreview(page, width, true);
                scenarios += 2;
            }
            stage = 'actual-logs-and-diff';
            await page.locator('#open-activity').click();
            await page.getByText('synthetic.ts', { exact: true }).click();
            await page.getByRole('button', { name: 'TypeScript — 通过' }).click();
            await readable(page.getByText('Synthetic TypeScript log: no errors', { exact: true }));
            await readable(page.locator('.panel-diff .whitespace-pre'));
            await readable(page.locator('.panel-diff-line-number'));
            await page.getByRole('button', { name: '关闭详情', exact: true }).first().click();
            scenarios += 1;
            stage = 'decorative-motion-matrix';
            for (const systemMotion of ['no-preference', 'reduce']) {
                await page.emulateMedia({ reducedMotion: systemMotion });
                for (const motion of ['full', 'reduced', 'off']) {
                    await configure(page, motion);
                    const looping = systemMotion === 'no-preference' && motion === 'full';
                    await expect.poll(() => page.locator('.jelly-dew').evaluate(el => getComputedStyle(el).animationName !== 'none')).toBe(looping);
                    if (systemMotion === 'reduce' || motion === 'off') {
                        await expect.poll(async () => (await engine(page)).ticking).toBe(false);
                        assert.equal((await engine(page)).awake, 0);
                    }
                    scenarios += 1;
                }
            }
            stage = 'dynamic-reduced-motion-and-off';
            await page.emulateMedia({ reducedMotion: 'no-preference' });
            await configure(page, 'full');
            await expect.poll(async () => (await engine(page)).heroRegistered).toBe(true);
            await expect.poll(async () => (await engine(page)).ticking).toBe(true);
            await page.getByRole('textbox', { name: 'Synthetic input' }).focus();
            await expect.poll(() => page.locator('.chat-composer-surface').evaluate(el => el.style.transform)).not.toBe('');
            await page.emulateMedia({ reducedMotion: 'reduce' });
            await expect.poll(async () => (await engine(page)).ticking).toBe(false);
            for (const selector of ['.chat-composer-surface', '.jelly-tower', '.jelly-mac', '.jelly-mousse-hl']) {
                assert.ok((await page.locator(selector).evaluateAll(nodes => nodes.map(el => el.style.transform))).every(value => value === ''), `${selector}: residual transform`);
            }
            await page.emulateMedia({ reducedMotion: 'no-preference' });
            await expect.poll(async () => (await engine(page)).heroRegistered).toBe(true);
            await configure(page, 'off');
            await expect.poll(async () => (await engine(page)).ticking).toBe(false);
            assert.equal(await page.locator('.chat-composer-surface').evaluate(el => el.style.transform), '');
            scenarios += 1;
            stage = 'message-unmount-lifecycle';
            await configure(page, 'full');
            await expect.poll(async () => (await engine(page)).heroRegistered).toBe(true);
            const baseline = (await engine(page)).registered;
            for (let i = 0; i < 3; i++) {
                await page.locator('#replace-message').click();
                // Wait for the observer's batched scan, then compare with the same live fixture.
                await page.waitForTimeout(250);
                await expect.poll(async () => (await engine(page)).registered).toBe(baseline);
            }
            scenarios += 1;
            assert.deepEqual(errors, [], 'Unexpected browser exception');
            assert.deepEqual(businessRequests, [], 'Fixture contacted a business service');
            console.log(`PASS Jelly interactions ${width}px`);
        } catch (error) {
            await page.screenshot({ path: path.join(artifacts, `${width}-${stage}.png`), fullPage: true });
            throw new Error(`${width}px ${stage}: ${error.message}\nScreenshot directory: ${artifacts}`, { cause: error });
        } finally { await context.close(); }
    }
    console.log(`Jelly regression passed: ${scenarios} scenarios; isolated stores/clipboard; no business requests.`);
} finally { await browser.close(); }
