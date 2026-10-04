/**
 * InkHavocFxLayer — 大闹天宫重彩风浓郁档装饰层
 * 设计移植自 中国传统动画风皮肤-demo-v3.html：
 *   顶边回纹分隔带 / 左右戏台帘幕
 *   （四角如意云纹角标曾因压字压控件被产品移除，纹样资产保留于 patterns/ 备用）
 *
 * 门控：装饰层（回纹带/帘幕）仅 theme.mode 为 ink-havoc/ink-havoc-night 且 inkHavocFx.cinematic
 * 时渲染；动态光影层 .ink-light-play（波次1增强⑤）ink 双模式即常驻（克制/浓郁双档）。
 * 波次3② 闭关模式（inkHavocFx.retreat）：上述装饰/光影全部退场不挂载，仅洇边 defs 常驻。
 * 样式全部在 styles/ink-havoc.css 对应区块，本组件只输出语义化 class。
 * 纹样取 assets/theme/ink-havoc/patterns/*.svg，以 CSS mask 染色跟随令牌
 * （--ink-cloud-color 描金 / --ink-fret-color 描金，浓郁档变量由 fx-ink-rich 收口）。
 * 入场仪式「开锣亮相」由 html.ink-boot 驱动（ThemeProvider 写入，1600ms 摘除）；
 * 动效三档由 html.motion-* 收口，prefers-reduced-motion 强制静止。
 *
 * 戏台锚定（惊艳度冲刺）：装饰层不再是全视口 overlay——挂载后持续同步
 * 主内容区 <main>（不含底部状态栏 .app-status）的包围盒到 inline style，
 * 四角云纹/帘幕/回纹带因此全部落在「戏台」内：
 * 左上角标避开侧栏「会话 N」文字区，右下角标避开状态栏，回纹带只作 Header 下缘一线。
 * 侧栏拖拽调宽 / 窗口缩放 / 状态栏显隐均由 ResizeObserver + MutationObserver 跟随。
 */

import { useLayoutEffect, useRef } from 'react';
import { defaultInkHavocFx, useConfigStore } from '@/store/configStore';

/** ink 双模式集合（门控判断用） */
const INK_MODES = new Set(['ink-havoc', 'ink-havoc-night']);

export function InkHavocFxLayer() {
    const theme = useConfigStore(s => s.theme);
    // normalizeTheme 保证持久化后恒有值；未持久化前的瞬态用默认值兜底（同 SpaceshipHudLayer）
    const fx = theme.inkHavocFx ?? defaultInkHavocFx();
    const isInk = INK_MODES.has(theme.mode);
    // 波次3② 闭关模式：装饰层退场不挂载（功能反馈 toast/妖气不在本层，保留）；
    // 仅保留洇边 filter defs 常驻——html.ink-retreat 下洇开动画已由 CSS 静止，
    // defs 不挂载亦可，但保留可避免 url(#ink-bleed-edge) 引用悬空的开销分支，零布局成本。
    // 可读性简化（产品决策）：云纹视差层/动态光影层/云海幽灵层已全部移除——
    // 背景只留 app-workspace 一层面纱压画意，不动、不花、不扰阅读。
    const enabled = isInk && fx.cinematic && !fx.retreat;
    const layerRef = useRef<HTMLDivElement>(null);

    // 戏台锚定：把装饰层包围盒同步为主内容区（<main> 减去状态栏高度）。
    // useLayoutEffect 保证首帧前落位，避免角标在视口角上闪一帧。
    useLayoutEffect(() => {
        if (!enabled) return;
        const el = layerRef.current;
        if (!el) return;
        const sync = () => {
            const main = document.querySelector('.app-workspace main');
            if (!main) {
                // 独立侧栏窗等无 <main> 形态：回退全视口（清除 inline 即还原 CSS inset:0）
                el.style.top = ''; el.style.left = '';
                el.style.width = ''; el.style.height = '';
                return;
            }
            const rect = main.getBoundingClientRect();
            const status = main.querySelector('.app-status');
            const bottom = status ? status.getBoundingClientRect().top : rect.bottom;
            el.style.top = `${rect.top}px`;
            el.style.left = `${rect.left}px`;
            el.style.width = `${rect.width}px`;
            el.style.height = `${Math.max(0, bottom - rect.top)}px`;
        };
        sync();
        const main = document.querySelector('.app-workspace main');
        const resizeObserver = new ResizeObserver(sync);
        if (main) resizeObserver.observe(main);
        // 状态栏显隐不改变 <main> 尺寸（flex 子项增减），需 childList 观察兜底
        const mutationObserver = new MutationObserver(sync);
        if (main) mutationObserver.observe(main, { childList: true });
        window.addEventListener('resize', sync);
        return () => {
            resizeObserver.disconnect();
            mutationObserver.disconnect();
            window.removeEventListener('resize', sync);
        };
    }, [enabled]);

    if (!isInk) return null;

    return (
        <>
            {/* 波次2④ 湿墨洇边 SVG filter（ink 双模式常驻 defs，宽高 0 不占布局）：
                feTurbulence+feDisplacementMap scale 4 制造洇边毛糙；由 ink-havoc.css
                洇开入场 keyframes 以 url(#ink-bleed-edge) 引用，动画 100% 帧
                filter:none 一次性摘除（性能红线）。 */}
            <svg aria-hidden="true" focusable="false" width="0" height="0" style={{ position: 'absolute' }}>
                <defs>
                    <filter id="ink-bleed-edge" x="-5%" y="-5%" width="110%" height="110%">
                        <feTurbulence type="fractalNoise" baseFrequency="0.9" numOctaves="2" seed="7" result="noise" />
                        <feDisplacementMap in="SourceGraphic" in2="noise" scale="4" xChannelSelector="R" yChannelSelector="G" />
                    </filter>
                </defs>
            </svg>

            {enabled && (
                <div ref={layerRef} className="ink-havoc-fx-layer" aria-hidden="true">
                    {/* 顶边回纹分隔带（Header 下沿平铺 repeat-x，描金，两端 80px 渐隐） */}
                    <div className="ink-huiwen-band band-top" />

                    {/* 左右戏台帘幕：默认 translateX(±100%) 屏外待命，ink-boot 时滑开亮相 */}
                    <div className="ink-curtain left" />
                    <div className="ink-curtain right" />
                </div>
            )}
        </>
    );
}

export default InkHavocFxLayer;
