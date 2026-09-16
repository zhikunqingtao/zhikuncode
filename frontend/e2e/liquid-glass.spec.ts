import { test, expect } from '@playwright/test';

async function openGlass(page: import('@playwright/test').Page) {
    await page.route('**/api/config', route => route.fulfill({ json: { theme: 'glass' } }));
    await page.route('**/api/models', route => route.fulfill({ json: { models: [{ id: 'glass-test', displayName: 'Test model' }], defaultModel: 'glass-test' } }));
    await page.route('**/api/sessions?**', route => route.fulfill({ json: { sessions: [], hasMore: false } }));
    await page.route('**/api/projects', route => route.fulfill({ json: [] }));
    await page.goto('/');
    await expect(page.locator('html')).toHaveClass(/glass/);
}

test('玻璃折射只改变真实背景的边缘，前景文字不变', async ({ page }) => {
    await openGlass(page);
    await page.evaluate(async () => {
        const reactPath = '/node_modules/.vite/deps/react.js';
        const clientPath = '/node_modules/.vite/deps/react-dom_client.js';
        const materialPath = '/src/components/theme/GlassMaterial.tsx';
        const { default: React } = await import(reactPath);
        const { default: client } = await import(clientPath);
        const { GlassMaterial } = await import(materialPath);
        const stage = document.createElement('div');
        stage.id = 'optical-stage';
        Object.assign(stage.style, {
            position: 'fixed', inset: '0', zIndex: '999', display: 'grid', placeItems: 'center',
            background: 'repeating-conic-gradient(#177699 0% 25%, #e2ab36 0% 50%) 0/32px 32px',
        });
        document.body.appendChild(stage);
        client.createRoot(stage).render(React.createElement('div', {
            className: 'glass-surface', id: 'optical-host',
            style: { position: 'relative', width: 440, height: 180, borderRadius: 32, display: 'grid', placeItems: 'center' },
        }, React.createElement(GlassMaterial, { kind: 'control' }),
        React.createElement('span', { id: 'optical-label', style: { color: '#202737', background: '#fff', padding: 8 } }, 'const readable = true;')));
    });
    const host = page.locator('#optical-host');
    await expect(host.locator('.liquid-glass-material')).toHaveAttribute('data-refracting', 'true');
    const before = await host.screenshot({ animations: 'disabled' });
    const labelBefore = await page.locator('#optical-label').screenshot();
    await host.locator('.glass-lens').evaluate((el: HTMLElement) => { el.style.backdropFilter = 'none'; });
    const after = await host.screenshot({ animations: 'disabled' });
    expect(await page.locator('#optical-label').screenshot()).toEqual(labelBefore);
    const difference = await page.evaluate(async ({ first, second }) => {
        const decode = async (base64: string) => {
            const image = new Image();
            image.src = `data:image/png;base64,${base64}`;
            await image.decode();
            const canvas = document.createElement('canvas');
            canvas.width = image.width; canvas.height = image.height;
            const context = canvas.getContext('2d')!;
            context.drawImage(image, 0, 0);
            return context.getImageData(0, 0, image.width, image.height);
        };
        const a = await decode(first), b = await decode(second);
        let edge = 0, center = 0;
        for (let y = 0; y < a.height; y++) for (let x = 0; x < a.width; x++) {
            const i = (y * a.width + x) * 4;
            const delta = [0, 1, 2].reduce((sum, k) => sum + Math.abs(a.data[i + k] - b.data[i + k]), 0);
            if (delta > 12) {
                if (x < 40 || x > a.width - 41 || y < 40 || y > a.height - 41) edge++;
                else center++;
            }
        }
        return { edge, center };
    }, { first: before.toString('base64'), second: after.toString('base64') });
    expect(difference.edge).toBeGreaterThan(100);
    expect(difference.center).toBe(0);
});

test('玻璃侧栏仍可收起恢复，弹层保持焦点与关闭行为', async ({ page }) => {
    await openGlass(page);
    await page.getByRole('button', { name: '收起整个对话列表' }).click();
    await expect(page.getByRole('textbox', { name: '搜索会话' })).toBeHidden();
    await page.getByRole('button', { name: '展开侧栏列表' }).click();
    await expect(page.getByRole('textbox', { name: '搜索会话' })).toBeVisible();
    const entry = page.getByRole('button', { name: '外观设置', exact: true });
    await entry.click();
    const dialog = page.getByRole('dialog', { name: '外观设置' });
    await expect(dialog).toBeVisible();
    await expect(dialog.locator('.liquid-glass-material')).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(dialog).toBeHidden();
    await expect(entry).toBeFocused();
    await page.getByRole('textbox', { name: '输入消息', exact: true }).fill('玻璃输入区提交验证');
    await page.getByRole('button', { name: '发送消息', exact: true }).click();
    await expect(page.getByText('选择文件夹授权', { exact: true })).toBeVisible();
});

test('增强对比度关闭光学滤镜并使用实色材质', async ({ page }) => {
    await page.emulateMedia({ contrast: 'more', reducedMotion: 'reduce' });
    await openGlass(page);
    const material = page.locator('.chat-composer-surface > .liquid-glass-material');
    await expect(material).toHaveAttribute('data-reduced', 'true');
    await expect(material.locator('.glass-lens')).toHaveCount(0);
    await expect(material.locator('.glass-scatter')).toHaveCSS('backdrop-filter', 'none');
});

test('移动玻璃输入区随键盘调整且不覆盖消息区', async ({ page }) => {
    await page.setViewportSize({ width: 393, height: 852 });
    await openGlass(page);
    await page.evaluate(async () => {
        const path = '/src/store/messageStore.ts';
        const { useMessageStore } = await import(path);
        window.__e2eStores!.sessionStore.setState({ sessionId: 'glass-mobile', status: 'idle' });
        useMessageStore.setState({ messages: Array.from({ length: 40 }, (_, i) => ({
            type: 'user', uuid: `glass-mobile-${i}`, timestamp: i,
            content: [{ type: 'text', text: `第 ${i + 1} 条：检查悬浮输入与滚动避让。` }],
        })) });
    });
    const composer = page.locator('.chat-composer-surface');
    await expect(composer).toBeVisible();
    await expect.poll(() => page.locator('.glass-chat-spacer').evaluate(el => el.getBoundingClientRect().height)).toBe(12);
    await page.getByRole('textbox', { name: '输入消息', exact: true }).focus();
    await page.setViewportSize({ width: 393, height: 640 });
    await expect.poll(() => page.evaluate(() => document.documentElement.style.getPropertyValue('--keyboard-height'))).toBe('212px');
    await expect.poll(async () => {
        const box = await composer.boundingBox();
        return Boolean(box && box.y >= 0 && box.y + box.height <= 640);
    }).toBe(true);
    await page.setViewportSize({ width: 393, height: 852 });
    await expect.poll(() => page.evaluate(() => document.documentElement.style.getPropertyValue('--keyboard-height'))).toBe('0px');
    await expect.poll(async () => (await composer.boundingBox())!.y).toBeGreaterThan(600);
});
