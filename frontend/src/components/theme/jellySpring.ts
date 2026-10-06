/**
 * jellySpring — 果冻主题 Q 弹引擎（spring 形变内核 · TS 化）
 *
 * 移植自「果冻主题皮肤-demo.html v4」IIFE 内的 spring 引擎，物理行为原样保留：
 *   半隐式欧拉积分 + 全局共享 rAF（无活动元素自动停帧；awake 上限 24）；
 *   每个元素一对耦合 spring（sx/sy）+ ty + skewX；
 *   静止判定 |v|<0.001 且 |x-t|<0.001 → 从活动集摘除；
 *   强劲水感形变上限（v2 级 duang）：sy∈[0.78,1.22] · sx∈[0.82,1.18]。
 *
 * 门控（沿用 demo richOn 语义，改读 zhikuncode 配置）：
 *   isJellyRich()       —— jelly 模式 + jellyFx.cinematic + 系统未要求减弱动态
 *   isJellyMotionFull() —— 浓郁档且动效档位 full（装饰循环/视差等永续动画）
 *   springsActive()     —— 浓郁档且动效非 off（按压/悬停等事件瞬态反馈；wake 总闸）
 * calm 档 / 非 jelly 模式 / motion-off / prefers-reduced-motion 下 wake 一键失活，
 * 组件挂载分支本就不渲染；切档时由 releaseSpring()/teardownAll() 复位清零，零残留。
 *
 * 性能纪律：只写 transform（translate3d/scale/skewX）与 willChange；空闲即停 rAF。
 */

import { defaultJellyFx, useConfigStore } from '@/store/configStore';

/* ---------- 类型 ---------- */

/** 单轴 spring 状态（位置 / 速度 / 目标） */
export interface SpringAxis {
    x: number;
    v: number;
    t: number;
}

/** 顶部摆动包络：skewX = A·sin(ωt)·e^(−λt)（低阻尼久颤的果冻尾巴） */
export interface SpringWobble {
    /** 起摆时基（ms，与 rAF 时间戳同源） */
    t0: number;
    /** 初始振幅（deg） */
    A: number;
    /** 衰减系数 λ */
    lam: number;
    /** 角频率 ω */
    om: number;
}

/** 环境调制函数的单帧输出（缺省字段按中性值 1/1/0） */
export interface SpringModResult {
    sx?: number;
    sy?: number;
    ty?: number;
}

/** 环境调制：呼吸 / 漂浮 / 循环动画（返回每帧形变系数） */
export type SpringMod = (now: number) => SpringModResult;

export interface SpringOptions {
    /** 劲度系数 k（默认 300） */
    k?: number;
    /** 阻尼系数 c（默认 18） */
    c?: number;
    /** transform-origin（默认 '50% 100%' 底部粘住·顶部摆） */
    origin?: string;
    /** 初始挂载的环境调制（null = 不启用，运行时由调用方挂 s.mod） */
    mod?: SpringMod | null;
    /**
     * 事件监听统一注销信号（useEffect 清理：abort 即摘除全部监听，
     * 防 StrictMode 双挂；不传则不注册 AbortSignal）
     */
    signal?: AbortSignal;
}

export interface PressOptions extends SpringOptions {
    /** 回弹 wobble 初始振幅（deg，默认 6） */
    wobA?: number;
}

export interface HoverOptions extends SpringOptions {
    /** hover 抬升位移（px，默认 -2） */
    lift?: number;
    /** hover 横向微弹（默认 1.015） */
    hx?: number;
    /** hover 纵向微弹（默认 0.99） */
    hy?: number;
}

/** 一个元素的全量 spring 状态（sx/sy 耦合对 + ty + skewX + wobble 包络） */
export interface SpringState {
    el: HTMLElement;
    /** 仅恢复本 spring 接管前的 inline 样式，不擦除调用方原有样式。 */
    originalStyle: Pick<CSSStyleDeclaration, 'transform' | 'transformOrigin' | 'willChange'>;
    k: number;
    c: number;
    sx: SpringAxis;
    sy: SpringAxis;
    ty: SpringAxis;
    sk: SpringAxis;
    wob: SpringWobble | null;
    /** 注册时登记的环境调制（teardownAll 后置 null 可复位） */
    modFn: SpringMod | null;
    /** 当前生效的环境调制（null = 无；挂载即永续活动直至卸载） */
    mod: SpringMod | null;
}

/** 弹簧实时读数（自检/测试用） */
export interface SpringReading {
    sx: number;
    sy: number;
    ty: number;
    sk: number;
    wob: boolean;
}

/* ---------- 常量 ---------- */

/** 同时活动元素上限（demo：24） */
const MAX_AWAKE = 24;
/** 单帧 dt 钳制（s）：掉帧/后台标签页回来时防积分爆炸 */
const DT_MAX = 0.05;
/** 静止判定阈值 */
const REST_EPSILON = 0.001;
/** wobble 振幅低于该值即收尾（deg） */
const WOBBLE_MIN_AMP = 0.05;
/** 强劲水感形变上限（demo v4 最终版）：sy∈[0.78,1.22] · sx∈[0.82,1.18] */
export const SPRING_CLAMP = { sxMin: 0.82, sxMax: 1.18, syMin: 0.78, syMax: 1.22 } as const;

/* ---------- 注册表与共享 rAF ---------- */

const REG = new Map<HTMLElement, SpringState>();
const awake = new Set<SpringState>();
let rafId = 0;
let lastT = 0;

/**
 * 引擎时钟：统一用 Date.now()（而非 rAF 回调时间戳 / performance.now）——
 * rAF 时间戳与 Wall-Clock 不同源时 dt 会失真；Date.now() 在真实浏览器与
 * 测试假时钟（vi.useFakeTimers 伪造 Date）下都单调推进，dt 钳制 [0, 0.05] 兜底。
 */
function nowMs(): number {
    return Date.now();
}

/** 引擎时基：rAF 在跑时复用最近一帧时钟，否则取当前时钟 */
function timebase(): number {
    return rafId ? lastT : nowMs();
}

/** 引擎时基（对外）：wobble 起摆时间等与 rAF 时间戳同源的时间戳 */
export function engineNow(): number {
    return timebase();
}

/** 建一根轴（静止在 x） */
function mk(x: number): SpringAxis {
    return { x, v: 0, t: x };
}

function clamp(v: number, a: number, b: number): number {
    return v < a ? a : (v > b ? b : v);
}

/** 形变上限钳制：sy∈[0.78,1.22] · sx∈[0.82,1.18]（导出供测试/自检） */
export function clampTransform(sx: number, sy: number): { sx: number; sy: number } {
    return {
        sx: clamp(sx, SPRING_CLAMP.sxMin, SPRING_CLAMP.sxMax),
        sy: clamp(sy, SPRING_CLAMP.syMin, SPRING_CLAMP.syMax),
    };
}

/** 立即收敛到目标态（键值实时对齐，清 wobble） */
function snap(s: SpringState): void {
    s.sx.x = s.sx.t; s.sx.v = 0;
    s.sy.x = s.sy.t; s.sy.v = 0;
    s.ty.x = s.ty.t; s.ty.v = 0;
    s.sk.x = s.sk.t; s.sk.v = 0;
    s.wob = null;
}

/* ---------- 门控 ---------- */

/** 系统是否要求减弱动态（prefers-reduced-motion: reduce） */
export function prefersReducedMotion(): boolean {
    return typeof window !== 'undefined'
        && typeof window.matchMedia === 'function'
        && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}

/** 浓郁档门控：jelly 模式 + cinematic + 系统未要求减弱动态（装饰渲染与 spring 总门） */
export function isJellyRich(): boolean {
    if (prefersReducedMotion()) return false;
    const theme = useConfigStore.getState().theme;
    if (theme.mode !== 'jelly') return false;
    return (theme.jellyFx ?? defaultJellyFx()).cinematic;
}

/** 装饰循环门控：浓郁档且动效档位 full（reduced 关装饰循环、保留事件反馈；off 全静止） */
export function isJellyMotionFull(): boolean {
    if (!isJellyRich()) return false;
    return (useConfigStore.getState().theme.jellyFx ?? defaultJellyFx()).motion === 'full';
}

/** spring 唤醒总闸：浓郁档且动效非 off（wake 的 richOn 等价物） */
export function springsActive(): boolean {
    if (!isJellyRich()) return false;
    return (useConfigStore.getState().theme.jellyFx ?? defaultJellyFx()).motion !== 'off';
}

/* ---------- 引擎核心 ---------- */

/** 注册元素（demo springy）：登记 spring 状态并设置形变原点，返回状态对象 */
export function registerSpring(el: HTMLElement, opts: SpringOptions = {}): SpringState {
    const previous = REG.get(el);
    if (previous) releaseSpring(previous);
    const s: SpringState = {
        el,
        originalStyle: {
            transform: el.style.transform,
            transformOrigin: el.style.transformOrigin,
            willChange: el.style.willChange,
        },
        k: opts.k ?? 300,
        c: opts.c ?? 18,
        sx: mk(1),
        sy: mk(1),
        ty: mk(0),
        sk: mk(0),
        wob: null,
        modFn: opts.mod ?? null,
        mod: null,
    };
    el.style.transformOrigin = opts.origin ?? '50% 100%';
    REG.set(el, s);
    return s;
}

/** 读取元素的 spring 状态对象（未注册返回 null） */
export function getSpring(el: HTMLElement): SpringState | null {
    return REG.get(el) ?? null;
}

/** 弹簧实时读数（自检/测试用） */
export function readSpring(el: HTMLElement): SpringReading | null {
    const s = REG.get(el);
    if (!s) return null;
    return {
        sx: Number(s.sx.x.toFixed(4)),
        sy: Number(s.sy.x.toFixed(4)),
        ty: Number(s.ty.x.toFixed(2)),
        sk: Number(s.sk.x.toFixed(2)),
        wob: s.wob !== null,
    };
}

/** 半隐式欧拉单步（导出供测试）：返回该轴是否仍在活动 */
export function step(sp: SpringAxis, k: number, c: number, dt: number): boolean {
    const a = -k * (sp.x - sp.t) - c * sp.v;  // m = 1
    sp.v += a * dt;
    sp.x += sp.v * dt;
    return Math.abs(sp.v) > REST_EPSILON || Math.abs(sp.x - sp.t) > REST_EPSILON;
}

/** 唤醒：加入活动集并确保共享 rAF 在跑（无元素时停帧，上限 24） */
export function wake(s: SpringState): void {
    if (REG.get(s.el) !== s || !springsActive()) return;
    if (!awake.has(s)) {
        if (awake.size >= MAX_AWAKE) { snap(s); return; }
        awake.add(s);
        s.el.style.willChange = 'transform';
    }
    if (!rafId) {
        lastT = nowMs();
        rafId = requestAnimationFrame(tick);
    }
}

/** 共享 rAF 帧：积分全部活动 spring → 钳制形变 → 写 transform；全静止即停帧
 *  （不取 rAF 回调时间戳：与引擎时钟 Date.now() 不同源会失真；见 nowMs 注释） */
export function tick(): void {
    rafId = 0;
    const t = nowMs();
    const dt = Math.min(Math.max((t - lastT) / 1000, 0), DT_MAX);
    lastT = t;
    awake.forEach((s) => {
        let live = step(s.sx, s.k, s.c, dt);
        live = step(s.sy, s.k, s.c, dt) || live;
        live = step(s.ty, s.k, s.c, dt) || live;
        live = step(s.sk, s.k, s.c, dt) || live;
        let skewValue = s.sk.x;
        if (s.wob) {
            // 顶部摆动：skewX = A·sin(ωt)·e^(−λt)（e 钳 ≥0，防时基不同源时负时间放大振幅）
            const e = Math.max(0, (t - s.wob.t0) / 1000);
            const amp = s.wob.A * Math.exp(-s.wob.lam * e);
            if (amp < WOBBLE_MIN_AMP) {
                s.wob = null;
            } else {
                skewValue = amp * Math.sin(s.wob.om * e);
                live = true;
            }
        }
        let modSx = 1;
        let modSy = 1;
        let modTy = 0;
        if (s.mod) {
            const m = s.mod(t);
            modSx = m.sx ?? 1;
            modSy = m.sy ?? 1;
            modTy = m.ty ?? 0;
            live = true;
        }
        const scaled = clampTransform(s.sx.x * modSx, s.sy.x * modSy);
        const ty = s.ty.x + modTy;
        s.el.style.transform = `translate3d(0,${ty.toFixed(2)}px,0) scale(${scaled.sx.toFixed(4)},${scaled.sy.toFixed(4)})`
            + (Math.abs(skewValue) > 0.01 ? ` skewX(${skewValue.toFixed(2)}deg)` : '');
        if (!live) {
            snap(s);
            awake.delete(s);
            s.el.style.willChange = s.originalStyle.willChange;
            if (s.sx.t === 1 && s.sy.t === 1 && s.ty.t === 0 && s.sk.t === 0) {
                s.el.style.transform = s.originalStyle.transform;
            } else {
                s.el.style.transform = `translate3d(0,${s.ty.t}px,0) scale(${s.sx.t},${s.sy.t})`;
            }
        }
    });
    if (awake.size) {
        rafId = requestAnimationFrame(tick);
    } else {
        lastT = 0;
    }
}

/** 引擎是否在跑（自检/测试用；空闲停帧后为 false） */
export function isTicking(): boolean {
    return rafId !== 0;
}

/** 活动元素数（自检/测试用；空闲停帧后为 0） */
export function awakeCount(): number {
    return awake.size;
}

/** 注册表规模（自检/测试用） */
export function registeredCount(): number {
    return REG.size;
}

/* ---------- 行为原语（法式优雅档） ---------- */

/** 提交/按下时的按压形变（demo 参数）：压下 squash(1.18, 0.82) → 释放回弹 + 微 wobble */
export function pressSpring(el: HTMLElement, opts: PressOptions = {}): SpringState {
    const s = registerSpring(el, opts);
    const listenerOpts: AddEventListenerOptions | undefined = opts.signal ? { signal: opts.signal } : undefined;
    let down = false;

    el.addEventListener('pointerdown', () => {
        if (!springsActive()) return;
        down = true;
        s.wob = null;
        s.sk.x = 0;
        s.sk.v = 0;
        s.sx.t = 1.18;  // 强劲：压得更扁更宽·保体积
        s.sy.t = 0.82;
        wake(s);
    }, listenerOpts);

    const up = (): void => {
        if (!down) return;
        down = false;
        if (!springsActive()) return;
        s.sx.t = 1;
        s.sy.t = 1;
        s.wob = { t0: timebase(), A: opts.wobA ?? 6, lam: 2.3, om: 13 };  // 更久的果冻颤尾巴
        wake(s);
    };
    el.addEventListener('pointerup', up, listenerOpts);
    el.addEventListener('pointercancel', up, listenerOpts);
    el.addEventListener('pointerleave', up, listenerOpts);
    return s;
}

/** hover 小 spring 抬升 + 微 squash（demo 参数：lift -2 / hx 1.015 / hy 0.99） */
export function hoverSpring(el: HTMLElement, opts: HoverOptions = {}): SpringState {
    const s = registerSpring(el, opts);
    const listenerOpts: AddEventListenerOptions | undefined = opts.signal ? { signal: opts.signal } : undefined;

    el.addEventListener('pointerenter', () => {
        if (!springsActive()) return;
        s.ty.t = opts.lift ?? -2;
        s.sx.t = opts.hx ?? 1.015;
        s.sy.t = opts.hy ?? 0.99;
        wake(s);
    }, listenerOpts);
    el.addEventListener('pointerleave', () => {
        if (!springsActive()) return;
        s.ty.t = 0;
        s.sx.t = 1;
        s.sy.t = 1;
        wake(s);
    }, listenerOpts);
    return s;
}

/** 状态变化弹性跳一下（demo pop）：从 from 轻压态弹回 1 */
export function popSpring(s: SpringState, from = 0.7): void {
    if (!springsActive()) return;
    s.sx.x = from;
    s.sy.x = from;
    s.sx.v = 0;
    s.sy.v = 0;
    s.sx.t = 1;
    s.sy.t = 1;
    wake(s);
}

/** 入场 spring pop：轻压态(1.08, 0.86) → 过冲回 1（delay ms 后唤醒） */
export function enterSpring(s: SpringState, delay = 0): void {
    if (!springsActive()) return;
    s.sx.x = 1.08;
    s.sy.x = 0.86;
    s.sx.v = 0;
    s.sy.v = 0;
    s.sx.t = 1;
    s.sy.t = 1;
    s.el.style.transform = 'scale(1.08,0.86)';
    if (delay > 0) {
        window.setTimeout(() => {
            if (springsActive()) wake(s);
        }, delay);
    } else {
        wake(s);
    }
}

/* ---------- 环境调制（呼吸 / 漂浮） ---------- */

/** 呼吸：sy/sx 各绕基线正弦摆动（period ms） */
export function breathMod(
    period: number,
    syBase: number,
    syAmp: number,
    sxBase: number,
    sxAmp: number,
    phase = 0,
): SpringMod {
    return (t) => {
        const th = (2 * Math.PI * t) / period + phase;
        return {
            sy: syBase + syAmp * Math.sin(th),
            sx: sxBase + sxAmp * Math.sin(th),
        };
    };
}

/** 漂浮：第 i 个元素缓慢上下浮动（period/相位/振幅随序号错开） */
export function floatMod(i: number, periodBase = 7000, ampBase = 5): SpringMod {
    const p = periodBase + i * 2300;
    const ph = i * 2.2;
    const amp = ampBase + i * 2;
    return (t) => ({ ty: amp * Math.sin((2 * Math.PI * t) / p + ph) });
}

/* ---------- 清理（切档零残留） ---------- */

/** 单个 spring 退场：仅释放当前注册，复位键值并恢复原 inline 样式。 */
export function releaseSpring(s: SpringState): void {
    if (REG.get(s.el) !== s) return;
    awake.delete(s);
    REG.delete(s.el);
    s.mod = null;
    s.modFn = null;
    s.wob = null;
    s.sx.t = 1;
    s.sy.t = 1;
    s.ty.t = 0;
    s.sk.t = 0;
    snap(s);
    Object.assign(s.el.style, s.originalStyle);
    if (!awake.size && rafId) {
        cancelAnimationFrame(rafId);
        rafId = 0;
        lastT = 0;
    }
}

/** 全量清场（切档/卸载收口）：停 rAF，全部元素复位并清空注册表——零 spring 零残留 */
export function teardownAll(): void {
    if (rafId) {
        cancelAnimationFrame(rafId);
        rafId = 0;
    }
    lastT = 0;
    Array.from(REG.values()).forEach(releaseSpring);
}

/** 单个已注册元素立即收敛到中性态。 */
export function settleSpring(s: SpringState): void {
    if (REG.get(s.el) !== s) return;
    s.sx.t = 1;
    s.sy.t = 1;
    s.ty.t = 0;
    s.sk.t = 0;
    snap(s);
    awake.delete(s);
    s.el.style.willChange = s.originalStyle.willChange;
    s.el.style.transform = s.originalStyle.transform;
}
