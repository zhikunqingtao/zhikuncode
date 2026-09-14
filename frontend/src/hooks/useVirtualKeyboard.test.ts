/**
 * useKeyboardScrollCompensation — §8.8.3 移动键盘滚动补偿（bug B 修复核心语义）
 * - atBottom=true：键盘弹起把可视高度压缩后，把增高 delta 追加到 scrollTop，
 *   滚动位置重新锚定真实底部；键盘收起（delta<0）不回拉；
 * - atBottom=false（用户上翻）：不动 scrollTop，保留阅读位置（显式需求：
 *   用户上翻时不得触发补偿滚动）；
 * - prevKeyboardHeight 无条件跟踪：atBottom 翻转后不跨缺口二次补偿。
 */

import { renderHook } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { useKeyboardScrollCompensation } from './useVirtualKeyboard';

function makeList(scrollTop: number) {
    const el = document.createElement('div');
    el.scrollTop = scrollTop;
    return { listRef: { current: el }, el };
}

function renderCompensation(listRef: { current: HTMLDivElement }, h: number, atBottom: boolean) {
    return renderHook(
        ({ height, at }: { height: number; at: boolean }) =>
            useKeyboardScrollCompensation(listRef, height, at),
        { initialProps: { height: h, at: atBottom } },
    );
}

describe('useKeyboardScrollCompensation', () => {
    it('atBottom=true：键盘弹起 delta 追加到 scrollTop（锚定真实底部），收起不回拉', () => {
        const { listRef, el } = makeList(100);
        const { rerender } = renderCompensation(listRef, 0, true);

        rerender({ height: 276, at: true });
        expect(el.scrollTop).toBe(376);

        // 键盘收起（delta<0）不反向回拉
        rerender({ height: 0, at: true });
        expect(el.scrollTop).toBe(376);
    });

    it('atBottom=false（用户上翻）：不补偿，保留阅读位置', () => {
        const { listRef, el } = makeList(500);
        const { rerender } = renderCompensation(listRef, 0, false);

        rerender({ height: 276, at: false });
        expect(el.scrollTop).toBe(500);
    });

    it('atBottom 翻转后无跨缺口补偿（prev 无条件跟踪，delta=0 不突跳）', () => {
        const { listRef, el } = makeList(500);
        const { rerender } = renderCompensation(listRef, 0, false);

        // 上翻期间键盘弹起：不补偿，但 prev 已跟踪到 276
        rerender({ height: 276, at: false });
        expect(el.scrollTop).toBe(500);

        // 回到底部、高度未再变化：delta=0，不二次补偿
        rerender({ height: 276, at: true });
        expect(el.scrollTop).toBe(500);
    });
});
