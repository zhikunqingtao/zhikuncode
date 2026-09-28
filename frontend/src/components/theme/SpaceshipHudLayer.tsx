/**
 * SpaceshipHudLayer — 星舰 HUD 电影级装饰层（v3）
 * 设计移植自 spaceship-hud-theme-demo.html：
 *   四角机械角标 / 顶边刻度尺 / 罗盘雷达 / 扫描线 / 透视网格地板 / 滚动数字带
 *
 * 门控：仅 theme.mode==='spaceship' 且 spaceshipFx.cinematic 时渲染（否则 null）；
 * 样式全部在 styles/spaceship.css「Cinematic Layer」区块，本组件只输出语义化 class。
 * 动效三档由 html.motion-* 收口（雷达/扫描线/脉冲环/数字带仅 motion-full 播放）。
 */

import { defaultSpaceshipFx, useConfigStore } from '@/store/configStore';

/** 刻度尺数字标记：0..3800 每 50 一格（svg 定宽 3840，容器 overflow hidden 裁剪） */
const RULER_NUMBERS = Array.from({ length: 77 }, (_, i) => i * 50);

/** 滚动数字带：8 行十六进制 ×2 复制，translateY(-50%) 无缝循环 */
const HEX_ROWS = [
    '0x3F2A·7C', '9E·11·D4', '0x7B2F·A1', 'C4·08·3E',
    '0x91DD·02', '5A·F7·19', '0x22C8·6B', 'E0·4A·90',
];

export function SpaceshipHudLayer() {
    const theme = useConfigStore(s => s.theme);
    // normalizeTheme 保证持久化后恒有值；未持久化前的瞬态用默认值兜底（同 SettingsPanel）
    const fx = theme.spaceshipFx ?? defaultSpaceshipFx();
    if (theme.mode !== 'spaceship' || !fx.cinematic) return null;

    return (
        <div className="hud-layer" aria-hidden="true">
            {/* 四角机械角标：双线 L 形 + 铆钉圆点（肘部亮青 / 臂端琥珀） */}
            <div className="hud-corners">
                <s className="tl" /><s className="tr" /><s className="bl" /><s className="br" />
            </div>

            {/* 顶边 HUD 刻度尺：格距 10px，每 5 格长刻度 + monospace 数字 */}
            <div className="hud-ruler">
                <svg width="3840" height="13" focusable="false">
                    <defs>
                        <pattern id="hud-rt-min" width="10" height="13" patternUnits="userSpaceOnUse">
                            <line x1="0" y1="13" x2="0" y2="8" stroke="rgba(0,229,255,.55)" strokeWidth="1" />
                        </pattern>
                        <pattern id="hud-rt-maj" width="50" height="13" patternUnits="userSpaceOnUse">
                            <line x1="0" y1="13" x2="0" y2="3" stroke="rgba(0,229,255,.9)" strokeWidth="1" />
                        </pattern>
                    </defs>
                    <rect width="3840" height="13" fill="url(#hud-rt-min)" />
                    <rect width="3840" height="13" fill="url(#hud-rt-maj)" />
                    <g fontFamily="JetBrains Mono, SF Mono, Menlo, monospace" fontSize="6.5"
                        fill="rgba(127,223,255,.95)" letterSpacing=".5">
                        {RULER_NUMBERS.map(n => (
                            <text key={n} x={n * 1 + 3} y="7.5">{n}</text>
                        ))}
                    </g>
                </svg>
            </div>

            {/* 罗盘雷达：外环刻度+N/E/S/W 顺时针 20s，内环扫描针逆时针 8s（仅 motion-full 旋转） */}
            <svg className="hud-radar" viewBox="0 0 44 44" focusable="false">
                <circle cx="22" cy="22" r="20" className="hud-rr1" />
                <g className="hud-radar-rotor-outer">
                    <circle cx="22" cy="22" r="16.5" className="hud-rr-tick" />
                    <text x="22" y="9.2" className="hud-rlab">N</text>
                    <text x="35.6" y="24" className="hud-rlab">E</text>
                    <text x="22" y="39.4" className="hud-rlab">S</text>
                    <text x="8.4" y="24" className="hud-rlab">W</text>
                </g>
                <circle cx="22" cy="22" r="11" className="hud-rr2" />
                <g className="hud-radar-rotor-inner">
                    <path d="M22 22 L22 9 A13 13 0 0 1 28.5 11 Z" className="hud-sweep" />
                    <line x1="22" y1="22" x2="22" y2="9" className="hud-needle" />
                </g>
                <line x1="22" y1="19.2" x2="22" y2="24.8" className="hud-cross" />
                <line x1="19.2" y1="22" x2="24.8" y2="22" className="hud-cross" />
                <circle cx="22" cy="22" r="1.4" className="hud-rc" />
                <circle cx="31" cy="15" r="1.2" className="hud-blip" />
            </svg>

            {/* 扫描线：2px 青色光带 10s 上下循环（仅 motion-full） */}
            <div className="hud-scanline" />

            {/* 透视网格地板：静态保留三档；脉冲环 ::after 仅 motion-full */}
            <div className="hud-floor" />

            {/* 角落滚动十六进制数字带（仅 motion-full） */}
            <div className="hud-numscroll">
                <div className="hud-numscroll-track">
                    {[...HEX_ROWS, ...HEX_ROWS].map((row, i) => <span key={i}>{row}</span>)}
                </div>
            </div>
        </div>
    );
}

export default SpaceshipHudLayer;
