import { test, expect } from '@playwright/test';

test('移动端可视化跳转应用到主区后才消费请求', async ({ page }) => {
    await page.goto('/', { waitUntil: 'networkidle' });
    await page.evaluate(async () => {
        const modulePath = '/src/store/appUiStore.ts';
        const { useAppUiStore } = await import(modulePath);
        useAppUiStore.getState().requestVisualizationTab('diagram');
    });
    await expect(page.locator('main').getByText('图表生成', { exact: true }).first()).toBeVisible();
    await expect(page.locator('main').getByRole('button', { name: '返回', exact: true })).toBeVisible();
    const state = await page.evaluate(async () => {
        const modulePath = '/src/store/appUiStore.ts';
        const { useAppUiStore } = await import(modulePath);
        const { mobileNavTab, pendingVisualizationTab } = useAppUiStore.getState();
        return { mobileNavTab, pendingVisualizationTab };
    });
    expect(state).toEqual({ mobileNavTab: 'diagram', pendingVisualizationTab: null });
});

test('隐藏的回到最新按钮不截获点击，也不进入键盘焦点', async ({ page }) => {
    await page.goto('/', { waitUntil: 'networkidle' });
    await page.evaluate(async () => {
        const modulePath = '/src/store/messageStore.ts';
        const { useMessageStore } = await import(modulePath);
        useMessageStore.getState().addMessage({ type: 'user', uuid: 'pointer-probe', timestamp: Date.now(), content: [{ type: 'text', text: '点击区域验证' }] });
    });
    const button = page.getByTestId('back-to-latest');
    await expect(button).toBeDisabled();
    await expect(button).toHaveAttribute('tabindex', '-1');
    const hit = await button.evaluate(element => {
        const box = element.getBoundingClientRect();
        return {
            pointerEvents: getComputedStyle(element).pointerEvents,
            intercepts: element.contains(document.elementFromPoint(box.x + box.width / 2, box.y + box.height / 2)),
        };
    });
    expect(hit).toEqual({ pointerEvents: 'none', intercepts: false });
});
