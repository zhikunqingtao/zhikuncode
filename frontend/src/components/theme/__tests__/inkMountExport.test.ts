/**
 * inkMountExport 单测：干支/文件名/版式几何/绘制调用（stub ctx 记录）
 */
import { describe, expect, it } from 'vitest';
import {
    MOUNT_PALETTE_LIGHT,
    buildMountFilename,
    drawMountedCard,
    fitCanvasText,
    formatMountDate,
    ganzhiYear,
    mountLayout,
    wrapCanvasText,
    type MountTemplate,
} from '../inkMountExport';

describe('ganzhiYear / formatMountDate / buildMountFilename', () => {
    it.each([
        [2025, '乙巳'],
        [2024, '甲辰'],
        [1984, '甲子'],  // 甲子基准年
        [4, '甲子'],
    ])('%d 年 → %s', (year, gz) => {
        expect(ganzhiYear(year)).toBe(gz);
    });

    it('formatMountDate：岁次干支 + 公历月日', () => {
        expect(formatMountDate(new Date(2025, 9, 3))).toBe('岁次乙巳 · 2025年10月3日');
    });

    it('buildMountFilename：zhikuncode-装裱-YYYYMMDD.png（月日补零）', () => {
        expect(buildMountFilename(new Date(2025, 0, 5))).toBe('zhikuncode-装裱-20250105.png');
        expect(buildMountFilename(new Date(2025, 11, 31))).toBe('zhikuncode-装裱-20251231.png');
    });
});

describe('mountLayout 版式几何', () => {
    it('立轴：3:4 竖长，天杆地杆横杆 ×2 + 两侧绫边，印位左下', () => {
        const l = mountLayout('scroll');
        expect(l.width / l.height).toBeCloseTo(3 / 4);
        expect(l.rods).toHaveLength(2);
        expect(l.rods.every(r => r.horizontal)).toBe(true);
        expect(l.brocades).toHaveLength(2);
        expect(l.brocades.every(b => b.vertical)).toBe(true);
        expect(l.frames).toHaveLength(0);
        expect(l.fishtail).toBeNull();
        expect(l.sealRect.x).toBeLessThan(l.width / 2);  // 左下
        expect(l.sealRect.y).toBeGreaterThan(l.height / 2);
    });

    it('手卷：16:9 横长，左右轴杆竖杆 ×2 + 隔水 ×2，印位右下', () => {
        const l = mountLayout('handscroll');
        expect(l.width / l.height).toBeCloseTo(16 / 9);
        expect(l.rods).toHaveLength(2);
        expect(l.rods.every(r => !r.horizontal)).toBe(true);
        expect(l.brocades).toHaveLength(2);
        expect(l.sealRect.x).toBeGreaterThan(l.width / 2);  // 右下
    });

    it('册页：近方形，双线版框（外内两级 inset）+ 鱼尾中缝锚点', () => {
        const l = mountLayout('album');
        expect(l.width / l.height).toBeGreaterThan(1);
        expect(l.width / l.height).toBeLessThan(1.2);
        expect(l.rods).toHaveLength(0);
        expect(l.frames).toHaveLength(2);
        expect(l.frames[0]).toBeLessThan(l.frames[1]);  // 外框内缩 < 内框内缩
        expect(l.fishtail).not.toBeNull();
        expect(l.fishtail!.x).toBe(l.width / 2);  // 版心居中
    });

    it.each(['scroll', 'handscroll', 'album'] as MountTemplate[])('%s：画心恒在画布内且左右留白对称', (t) => {
        const l = mountLayout(t);
        expect(l.card.x).toBeGreaterThanOrEqual(0);
        expect(l.card.y).toBeGreaterThanOrEqual(0);
        expect(l.card.x + l.card.w).toBeLessThanOrEqual(l.width);
        expect(l.card.y + l.card.h).toBeLessThanOrEqual(l.height);
        // 画心水平居中（左右留白相等）
        expect(l.card.x).toBe(l.width - l.card.x - l.card.w);
    });
});

/** 文本测量 stub：每字 10px（定宽近似），足以驱动省略/换行分支 */
function stubMeasureCtx() {
    return {
        measureText: (t: string) => ({ width: t.length * 10 }),
    } as Pick<CanvasRenderingContext2D, 'measureText'>;
}

describe('fitCanvasText / wrapCanvasText', () => {
    it('未超宽原样返回；超宽收敛省略号结尾', () => {
        const ctx = stubMeasureCtx();
        expect(fitCanvasText(ctx, '短题', 100)).toBe('短题');
        const out = fitCanvasText(ctx, '一'.repeat(30), 100);
        expect(out.endsWith('…')).toBe(true);
        expect(out.length).toBeLessThan(31);
    });

    it('二分截断给出最长可容前缀（审查#6修复：stub 宽=字长×10，100px 应容 9 字+…）', () => {
        const ctx = stubMeasureCtx();
        const out = fitCanvasText(ctx, '一'.repeat(30), 100);
        // (9+1)×10 = 100 ≤ maxWidth；若取 10 字则 (10+1)×10 = 110 > 100
        expect(out).toBe(`${'一'.repeat(9)}…`);
    });

    it('长文本测量次数有界（审查#6：二分 O(log n)，10000 字不超过 30 次测量）', () => {
        let calls = 0;
        const ctx = {
            measureText: (t: string) => { calls += 1; return { width: t.length * 10 }; },
        } as Pick<CanvasRenderingContext2D, 'measureText'>;
        const out = fitCanvasText(ctx, '一'.repeat(10000), 500);
        expect(out.endsWith('…')).toBe(true);
        expect(calls).toBeLessThanOrEqual(30);
    });

    it('逐字换行至多 maxLines 行，溢出末行省略', () => {
        const ctx = stubMeasureCtx();
        expect(wrapCanvasText(ctx, '一二三四', 40, 2)).toEqual(['一二三四']);
        const lines = wrapCanvasText(ctx, '一'.repeat(25), 40, 2);
        expect(lines).toHaveLength(2);
        expect(lines[1].endsWith('…')).toBe(true);
    });
});

/** Canvas 2D 调用记录 stub（jsdom 无真实 canvas；验证模板分支绘制路径） */
function stubCtx() {
    const calls: string[] = [];
    const gradient = { addColorStop: () => undefined };
    const ctx = {
        calls,
        fillStyle: '' as unknown,
        strokeStyle: '' as unknown,
        lineWidth: 1,
        textAlign: 'left' as CanvasTextAlign,
        textBaseline: 'alphabetic' as CanvasTextBaseline,
        font: '',
        createLinearGradient: () => gradient,
        fillRect: () => calls.push('fillRect'),
        strokeRect: () => calls.push('strokeRect'),
        beginPath: () => calls.push('beginPath'),
        rect: () => calls.push('rect'),
        arc: () => calls.push('arc'),
        moveTo: () => calls.push('moveTo'),
        lineTo: () => calls.push('lineTo'),
        closePath: () => calls.push('closePath'),
        fill: () => calls.push('fill'),
        stroke: () => calls.push('stroke'),
        fillText: () => calls.push('fillText'),
        measureText: (t: string) => ({ width: t.length * 10 }),
        roundRect: undefined as unknown as CanvasRenderingContext2D['roundRect'],
    };
    // roundRect 置 undefined 走 rect 兜底分支
    return ctx as unknown as CanvasRenderingContext2D & { calls: string[] };
}

describe('drawMountedCard（stub ctx 验证模板绘制路径）', () => {
    const base = {
        palette: MOUNT_PALETTE_LIGHT,
        title: '大闹天宫',
        colophon: '落笔成章',
        dateLabel: '岁次乙巳 · 2025年10月3日',
    };

    it.each(['scroll', 'handscroll', 'album'] as MountTemplate[])('%s：必绘标题/题跋/落款/朱印', (template) => {
        const ctx = stubCtx();
        drawMountedCard(ctx, { ...base, template });
        // 标题 + 题跋(1行) + 落款 + 印文「知」 = ≥4 次 fillText
        expect(ctx.calls.filter(c => c === 'fillText').length).toBeGreaterThanOrEqual(4);
    });

    it('立轴/手卷：绘轴杆（arc 轴头）；册页：绘双线版框（strokeRect ×3）与鱼尾', () => {
        const scroll = stubCtx();
        drawMountedCard(scroll, { ...base, template: 'scroll' });
        expect(scroll.calls).toContain('arc');  // 轴头

        const album = stubCtx();
        drawMountedCard(album, { ...base, template: 'album' });
        // 画心描金 1 + 双线版框 2 = 3 次 strokeRect
        expect(album.calls.filter(c => c === 'strokeRect').length).toBe(3);
        expect(album.calls).not.toContain('arc');  // 册页无轴杆
        expect(album.calls).toContain('lineTo');   // 鱼尾三角/中缝
    });

    it('返回版式与 mountLayout 一致（供调用方复用锚点）', () => {
        const ctx = stubCtx();
        const layout = drawMountedCard(ctx, { ...base, template: 'handscroll' });
        expect(layout).toEqual(mountLayout('handscroll'));
    });
});
