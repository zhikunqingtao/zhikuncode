import { test, expect } from '@playwright/test';

/**
 * P2b-2b 移动虚拟键盘接线回归（mobile project 393×852）
 *
 * 覆盖任务验收点：
 *  T1. 键盘弹起模拟（视口 852→500 触发 visualViewport.resize 路径）：
 *      ① --keyboard-height 被写入非 0 值（352px = 852-500）
 *      ② .prompt-input-container padding-bottom 增大（352 + 12 基线 = 364）
 *      ③ 输入条未被"压出"可视区（boundingBox 完整落在视口内）
 *      ④ 恢复视口高度后 --keyboard-height 归零、布局回弹
 *  T2. 键盘弹起瞬间消息流一次性滚底（MessageList scrollToBottom API，
 *      852→640 压缩以保证消息区仍有可视高度），且布局沉降期间
 *      （padding 0.25s 过渡 + Virtuoso 重测）持续锚底 —— ~2s 多次采样
 *      距底全部 ≤80px，而非单一瞬间达标
 *
 * 模拟原理：useVirtualKeyboard 监听 window.visualViewport 的 resize/scroll，
 * Playwright setViewportSize 会同步改变 visualViewport.height 并派发 resize，
 * 与真实设备键盘弹起引发的 visualViewport 收缩同路径（852-500=352 > 150 阈值）。
 */

const SHOT_DIR = '/tmp/zhikun-p2b2b-probe';

const readKeyboardVar = (page: import('@playwright/test').Page) =>
  page.evaluate(() =>
    document.documentElement.style.getPropertyValue('--keyboard-height'));

const readContainerPaddingBottom = (page: import('@playwright/test').Page) =>
  page.locator('.prompt-input-container')
    .evaluate(el => parseFloat(getComputedStyle(el).paddingBottom));

test.describe('P2b-2b 移动虚拟键盘接线 probe', () => {

  test('T1: 键盘弹起 → --keyboard-height 写入 / 输入条上浮且在视口内 / 恢复后回弹', async ({ page }) => {
    await page.goto('/', { waitUntil: 'networkidle' });

    const bar = page.getByTestId('mobile-prompt-bar');
    await expect(bar).toBeVisible({ timeout: 15000 });
    const container = page.locator('.prompt-input-container');
    await expect(container).toBeVisible();

    // ── 0. 基线：键盘收起，padding-bottom = 12px 基线间距 ──
    const baselinePb = await readContainerPaddingBottom(page);
    expect(baselinePb).toBeLessThanOrEqual(13);
    const baselineBox = await bar.boundingBox();
    expect(baselineBox).not.toBeNull();

    // ── 1. 聚焦输入卡片（真实键盘弹起的前置动作） ──
    const textarea = page.locator('textarea[aria-label="输入消息"]');
    await textarea.tap();
    await expect(textarea).toBeFocused();

    // ── 2. 模拟键盘弹起：可视区 852→500 ──
    await page.setViewportSize({ width: 393, height: 500 });

    // ① --keyboard-height 写入非 0（hook 有 100ms 防抖，poll 等待）
    await expect.poll(() => readKeyboardVar(page), { timeout: 5000 }).toBe('352px');

    // ② 容器 padding-bottom = 352 键盘 + 12 基线
    await expect
      .poll(() => readContainerPaddingBottom(page), { timeout: 5000 })
      .toBeCloseTo(364, 0);

    // ③ 输入条上浮且完整落在压缩后的可视区内
    const raisedBox = await bar.boundingBox();
    expect(raisedBox).not.toBeNull();
    expect(raisedBox!.y).toBeGreaterThanOrEqual(0);
    expect(raisedBox!.y + raisedBox!.height).toBeLessThanOrEqual(500);
    // 确实上浮了（底缘离开视口底部 = 键盘高度区域）
    expect(raisedBox!.y + raisedBox!.height)
      .toBeLessThan(baselineBox!.y + baselineBox!.height);
    await page.screenshot({ path: `${SHOT_DIR}/01-keyboard-open.png` });

    // ── 3. 恢复视口 → 变量归零、布局回弹 ──
    await page.setViewportSize({ width: 393, height: 852 });
    await expect.poll(() => readKeyboardVar(page), { timeout: 5000 }).toBe('0px');
    await expect
      .poll(() => readContainerPaddingBottom(page), { timeout: 5000 })
      .toBeLessThanOrEqual(13);
    const restoredBox = await bar.boundingBox();
    expect(restoredBox).not.toBeNull();
    // 回到视口底部区（底缘 ≈ 852 - 12 基线）
    expect(restoredBox!.y + restoredBox!.height).toBeGreaterThan(800);
    await page.screenshot({ path: `${SHOT_DIR}/02-keyboard-closed-rebound.png` });
  });

  test('T2: 键盘弹起瞬间消息流一次性滚底（scrollToBottom API）', async ({ page }) => {
    await page.goto('/', { waitUntil: 'networkidle' });
    await expect(page.getByTestId('mobile-prompt-bar')).toBeVisible({ timeout: 15000 });

    // ── 注入 40 条消息使消息流可滚动 ──
    await page.evaluate(async () => {
      const mod = await import('/src/store/messageStore.ts');
      const messages = Array.from({ length: 40 }, (_, i) => ({
        uuid: `kb-scroll-${i}`,
        type: 'user',
        timestamp: Date.now() + i,
        content: [{ type: 'text', text: `kb-scroll-${i}：滚底验证消息` }],
      }));
      (mod as any).useMessageStore.setState({ messages });
    });
    const list = page.locator('.message-list');
    await expect(list).toBeVisible({ timeout: 10000 });
    await expect(page.getByText('kb-scroll-39：滚底验证消息')).toBeVisible();

    // 消息区滚动元素（Virtuoso scroller，不依赖内部 DOM 结构）
    const readScroll = () => page.evaluate(() => {
      const root = document.querySelector('.message-list');
      if (!root) return null;
      const all = [root as HTMLElement,
        ...Array.from(root.querySelectorAll<HTMLElement>('*'))];
      const scroller = all.find(e => e.scrollHeight - e.clientHeight > 100);
      if (!scroller) return null;
      return {
        top: scroller.scrollTop,
        height: scroller.clientHeight,
        full: scroller.scrollHeight,
      };
    });

    // ── 归一化为"用户上翻"状态（初始置顶，followOutput 不接管） ──
    await page.evaluate(() => {
      const root = document.querySelector('.message-list');
      const all = [root as HTMLElement,
        ...Array.from(root!.querySelectorAll<HTMLElement>('*'))];
      const scroller = all.find(e => e.scrollHeight - e.clientHeight > 100);
      if (scroller) scroller.scrollTop = 0;
    });
    const before = await readScroll();
    expect(before).not.toBeNull();
    expect(before!.top).toBe(0);

    // ── 模拟键盘弹起（852→640，压缩 212px > 150 阈值，且消息区保留可视高度） ──
    await page.setViewportSize({ width: 393, height: 640 });
    await expect.poll(() => readKeyboardVar(page), { timeout: 5000 }).toBe('212px');

    // ── 弹起瞬间触发一次性滚底：scroller 抵近底部（Virtuoso 测量容差 80px） ──
    await expect.poll(async () => {
      const s = await readScroll();
      return s ? s.full - s.top - s.height : Number.MAX_SAFE_INTEGER;
    }, { timeout: 8000 }).toBeLessThanOrEqual(80);
    const after = await readScroll();
    expect(after!.top).toBeGreaterThan(0); // 确实发生了滚动
    await page.screenshot({ path: `${SHOT_DIR}/03-scrolled-to-bottom.png` });

    // ── 稳定性：布局沉降期间持续锚底 —— ~2s 内 10 次采样，距底全部 ≤80px ──
    // 回归点：键盘压缩视口后 padding-bottom 有 0.25s CSS 过渡、Virtuoso
    // 行高/总高重测亦异步多帧完成，一次性 scrollBy 会残留距底缺口（曾实测
    // 276px）；补偿须在沉降全程持续生效，而非单一瞬间达标。
    const gaps: number[] = [];
    for (let i = 0; i < 10; i += 1) {
      const s = await readScroll();
      expect(s).not.toBeNull();
      gaps.push(s!.full - s!.top - s!.height);
      await page.waitForTimeout(200);
    }
    expect(
      Math.max(...gaps),
      `距底 10 次采样须全部 ≤80px，实际：${gaps.join(', ')}`,
    ).toBeLessThanOrEqual(80);
    await page.screenshot({ path: `${SHOT_DIR}/04-bottom-stable-after-settle.png` });
  });
});
