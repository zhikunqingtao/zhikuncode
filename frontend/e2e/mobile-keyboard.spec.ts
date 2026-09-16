import { test, expect, type Page } from '@playwright/test';

// All network traffic is mocked; no session creation, model calls or publishing.
test.beforeEach(async ({ page }) => {
  await page.route('**/api/**', route => {
    const path = new URL(route.request().url()).pathname;
    if (!path.startsWith('/api/')) return route.continue();
    return route.fulfill({ json: path === '/api/config' ? { theme: 'glass' }
      : path.startsWith('/api/sessions') ? { sessions: [], hasMore: false }
      : path.endsWith('/workbench/current') ? null : path === '/api/skills' ? [] : {} });
  });
  await page.route('**/ws/**', route => route.abort());
});

async function openConversation(page: Page) {
  await page.goto('/');
  await expect(page.locator('.chat-composer-dock')).toBeVisible();
  await page.evaluate(async () => {
    const path = '/src/store/messageStore.ts';
    const { useMessageStore } = await import(path);
    window.__e2eStores!.sessionStore.setState({ sessionId: 'viewport-probe', status: 'waiting_permission' });
    window.__e2eStores!.featureFlagStore.setState(s => ({ flags: {
      ...s.flags, APOS_ACTIVITY_STREAM: true, APOS_MOBILE_STATUS: true,
    } }));
    useMessageStore.setState({ messages: Array.from({ length: 40 }, (_, i) => ({
      uuid: `viewport-${i}`, type: 'user', timestamp: i,
      content: [{ type: 'text', text: `第 ${i + 1} 条：检查消息与输入区独立布局。` }],
    })) });
  });
  await expect(page.locator('.message-list')).toBeVisible();
}

async function expectSeparatedLayout(page: Page, height: number, top = 0) {
  await expect.poll(() => page.evaluate(({ height, top }) => {
    const root = document.querySelector('.app-root')!.getBoundingClientRect();
    const list = document.querySelector('.message-list')!.getBoundingClientRect();
    const composer = document.querySelector('.chat-composer-dock')!.getBoundingClientRect();
    const input = document.querySelector('textarea[aria-label="输入消息"]')!.getBoundingClientRect();
    return Math.abs(root.top - top) < 1 && Math.abs(root.height - height) < 1
      && list.height > 0 && list.bottom <= composer.top + 1
      && composer.bottom <= root.bottom + 1 && input.top >= root.top && input.bottom <= root.bottom;
  }, { height, top })).toBe(true);
}

for (const width of [393, 820]) {
  test(`消息与输入区分离，列表返回和布局视口缩放后仍可用 (${width}px)`, async ({ page }) => {
    await page.setViewportSize({ width, height: 852 });
    await openConversation(page);
    await expectSeparatedLayout(page, 852);
    await page.getByRole('button', { name: '打开会话列表', exact: true }).first().click();
    await expect(page.locator('.chat-composer-dock')).toHaveCount(0);
    await page.getByRole('button', { name: '返回', exact: true }).click();
    await expectSeparatedLayout(page, 852);
    // The capsule must remain above the composer, even after a remount.
    const scroller = page.locator('[data-virtuoso-scroller="true"]');
    await expect(page.getByText('第 40 条：检查消息与输入区独立布局。', { exact: true })).toBeVisible();
    await expect.poll(() => scroller.evaluate(el => el.scrollHeight - el.scrollTop - el.clientHeight)).toBeLessThanOrEqual(80);
    await scroller.hover();
    await page.mouse.wheel(0, -3000);
    const latest = page.getByTestId('back-to-latest');
    await expect(latest).toBeVisible();
    await expect(latest).toBeEnabled();
    const buttonBox = (await latest.boundingBox())!;
    expect(buttonBox.y + buttonBox.height).toBeLessThanOrEqual((await page.locator('.chat-composer-dock').boundingBox())!.y);
    await latest.click();
    await expect.poll(() => page.locator('[data-virtuoso-scroller="true"]').evaluate(el => el.scrollHeight - el.scrollTop - el.clientHeight)).toBeLessThanOrEqual(80);
    const input = page.getByRole('textbox', { name: '输入消息', exact: true });
    await input.fill('仍然可以编辑');
    await page.setViewportSize({ width, height: 500 });
    await expectSeparatedLayout(page, 500);
    await expect(input).toHaveValue('仍然可以编辑');
    if (width < 768) {
      await expect.poll(() => page.locator('.prompt-input-container').evaluate(el => parseFloat(getComputedStyle(el).paddingBottom))).toBe(12);
    }
    await input.blur();
    await page.setViewportSize({ width, height: 852 });
    await expectSeparatedLayout(page, 852);
    await expect.poll(() => page.evaluate(() => document.documentElement.style.getPropertyValue('--keyboard-height'))).toBe('0px');
    await page.screenshot({ path: `/tmp/zhikun-viewport-${width}.png` });
    // Returning to desktop must restore real measured spacing, not the old detached node.
    await page.setViewportSize({ width: 1440, height: 900 });
    await expect.poll(() => page.locator('.glass-chat-spacer').evaluate(el => el.getBoundingClientRect().height)).toBeGreaterThan(100);
    await expect.poll(() => page.evaluate(() => {
      const spacer = document.querySelector('.glass-chat-spacer')!.getBoundingClientRect().height;
      const composer = document.querySelector('.chat-composer-dock')!.getBoundingClientRect().height;
      return Math.abs(spacer - composer - 12);
    })).toBeLessThan(1);
  });
}

test('内置浏览器：首次可视高度小于布局视口，键盘仅缩小可视视口并平移', async ({ page }) => {
  await page.addInitScript(() => {
    Object.defineProperties(window.visualViewport!, {
      height: { configurable: true, get: () => 700 },
      offsetTop: { configurable: true, get: () => 0 },
    });
  });
  await openConversation(page);
  await expectSeparatedLayout(page, 700);
  await page.getByRole('textbox', { name: '输入消息', exact: true }).fill('测试键盘');
  await page.evaluate(() => {
    Object.defineProperties(window.visualViewport!, {
      height: { configurable: true, get: () => 400 },
      offsetTop: { configurable: true, get: () => 30 },
    });
    window.visualViewport!.dispatchEvent(new Event('resize'));
  });
  await expectSeparatedLayout(page, 400, 30);
  // Closing the keyboard restores visible height without reloading the page.
  await page.evaluate(() => {
    (document.activeElement as HTMLElement).blur();
    Object.defineProperties(window.visualViewport!, {
      height: { configurable: true, get: () => 700 },
      offsetTop: { configurable: true, get: () => 0 },
    });
    window.visualViewport!.dispatchEvent(new Event('resize'));
  });
  await expectSeparatedLayout(page, 700);
  await expect.poll(() => page.evaluate(() => document.documentElement.style.getPropertyValue('--keyboard-height'))).toBe('0px');
});


test('旧 WebView 回退、长输入与横竖屏切换', async ({ page }) => {
  await page.addInitScript(() => { Object.defineProperty(window, 'visualViewport', { value: undefined }); });
  await page.setViewportSize({ width: 393, height: 700 });
  await openConversation(page);
  await expectSeparatedLayout(page, 700);
  const input = page.getByRole('textbox', { name: '输入消息', exact: true });
  await input.fill('这是多行输入，检查输入区仍可编辑。\n'.repeat(30));
  await page.setViewportSize({ width: 393, height: 500 });
  await expectSeparatedLayout(page, 500);
  await page.setViewportSize({ width: 820, height: 393 });
  await expectSeparatedLayout(page, 393);
  await expect(input).toBeEditable();
  await page.setViewportSize({ width: 393, height: 700 });
  await expectSeparatedLayout(page, 700);
});


for (const width of [393, 820]) {
  test(`长验证结果内部滚动，键盘弹出后输入控件仍完整可见 (${width}px)`, async ({ page }) => {
    await page.setViewportSize({ width, height: 700 });
    await openConversation(page);
    await page.evaluate(async () => {
      const path = '/src/store/journeyVerifyStore.ts';
      const { useJourneyVerifyStore } = await import(path);
      const turnPath = '/src/store/turnViewStore.ts';
      const { useTurnViewStore } = await import(turnPath);
      useTurnViewStore.getState().setDensity('detailed');
      useJourneyVerifyStore.setState({
        status: 'failed',
        steps: Array.from({ length: 30 }, (_, stepIndex) => ({ stepIndex, action: 'click', ok: true, durationMs: 10 })),
        errorMessage: '这是用于检查长验证结果的错误详情。'.repeat(30) + '验证详情结束',
      });
    });
    const panel = page.locator('.journey-verify-panel');
    await expect(panel).toBeVisible();
    const input = page.getByRole('textbox', { name: '输入消息', exact: true });
    await input.fill('检查长验证结果时仍可输入');
    for (const height of [700, 400, 700]) {
      await page.setViewportSize({ width, height });
      if (width === 393 && height === 400) {
        // A tall attachment preview must scroll inside the text area, not raise the card's minimum.
        await page.getByTestId('mobile-prompt-text-area').evaluate(el => {
          const preview = document.createElement('div');
          preview.style.height = '600px';
          preview.textContent = '附件预览占位';
          el.appendChild(preview);
        });
      }
      await expectSeparatedLayout(page, height);
      await expect.poll(() => page.locator('[data-virtuoso-scroller="true"]').evaluate(el => el.clientHeight)).toBeGreaterThan(0);
      // Check the whole input surface, including send/navigation controls, not just the textarea.
      await expect.poll(() => page.evaluate(() => {
        const root = document.querySelector('.app-root')!.getBoundingClientRect();
        const surface = document.querySelector('.chat-composer-surface')!.getBoundingClientRect();
        const controls = document.querySelectorAll('.chat-composer-inset button');
        return surface.bottom <= root.bottom && Array.from(controls).every(button => {
          const rect = button.getBoundingClientRect();
          return rect.height === 0 || (rect.top >= surface.top && rect.bottom <= surface.bottom + 1);
        });
      })).toBe(true);
      await expect.poll(() => panel.evaluate(el => el.scrollHeight > el.clientHeight)).toBe(true);
      await panel.evaluate(el => { el.scrollTop = el.scrollHeight; });
      await expect.poll(() => panel.evaluate(el => el.scrollHeight - el.scrollTop - el.clientHeight)).toBeLessThanOrEqual(1);
      await expect(input).toBeEditable();
    }
    await page.screenshot({ path: `/tmp/zhikun-verify-panel-${width}.png` });
  });
}
