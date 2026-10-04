/**
 * inkMountExport — 波次3③ 装裱分享 · 纯 Canvas 2D 装裱绘制逻辑（禁第三方截图库）
 *
 * 「装裱间」导出：把当前会话标题 + 题跋 + 干支落款 + 朱印「知」绘制成
 * 三种传统装裱形制的单张卡片 PNG——
 *   立轴 scroll：竖长 3:4，天杆/地杆（上下横杆）+ 两侧绫边渐变；
 *   手卷 handscroll：横长 16:9，左右轴杆（竖杆带轴头）+ 隔水渐变带；
 *   册页 album：近方形，双线版框（外粗内细）+ 版心鱼尾中缝。
 * 字体用主题已加载的 BrushKaiti（@font-face 于 ink-havoc.css，导出前
 * document.fonts.load 确保就绪）；导出 2x 清晰度（组件侧 ctx.scale(2,2)）。
 * 本文件全部纯函数/纯绘制，不触 store/DOM，便于单测（stub ctx 记录调用）。
 */

/** 装裱模板键 */
export type MountTemplate = 'scroll' | 'handscroll' | 'album';

export const MOUNT_TEMPLATES: { value: MountTemplate; label: string }[] = [
    { value: 'scroll', label: '立轴' },
    { value: 'handscroll', label: '手卷' },
    { value: 'album', label: '册页' },
];

/** 各模板设计尺寸（CSS px 设计空间；导出时整体 ×2） */
export const MOUNT_SIZE: Record<MountTemplate, { width: number; height: number }> = {
    scroll: { width: 600, height: 800 },      // 3:4 竖长
    handscroll: { width: 880, height: 495 },  // 16:9 横长
    album: { width: 640, height: 600 },       // 近方形
};

/** 装裱配色（按 ink 浅/深主题分档，组件侧按 mode 选择传入） */
export interface MountPalette {
    paper: string;      // 宣纸底
    card: string;       // 画心
    ink: string;        // 墨色正文
    inkSoft: string;    // 淡墨题跋/落款
    seal: string;       // 印泥朱砂
    sealText: string;   // 印文压色
    gold: string;       // 描金线
    rod: string;        // 轴杆木色
    rodCap: string;     // 轴头
    brocadeFrom: string;// 绫边/隔水渐变起
    brocadeTo: string;  // 绫边/隔水渐变止
}

/** 花果晨（浅）：绢纸暖白 + 墨字朱砂 */
export const MOUNT_PALETTE_LIGHT: MountPalette = {
    paper: '#F7F0DC', card: '#FDF8E8', ink: '#2E2822', inkSoft: '#6B5F4C',
    seal: '#C03A2B', sealText: '#FFF8E7', gold: '#C9A227',
    rod: '#8A6B4A', rodCap: '#5E4630', brocadeFrom: '#E8D9AE', brocadeTo: '#D3BD82',
};

/** 灵霄夜（深）：群青夜底 + 月白字鎏金线 */
export const MOUNT_PALETTE_DARK: MountPalette = {
    paper: '#1A2334', card: '#2B3850', ink: '#EDE5D0', inkSoft: '#B8C0D2',
    seal: '#E85D4A', sealText: '#FFF8E7', gold: '#C9A227',
    rod: '#4A3A28', rodCap: '#2E2418', brocadeFrom: '#31405C', brocadeTo: '#222D42',
};

/** 天干地支（干支年换算） */
const STEMS = '甲乙丙丁戊己庚辛壬癸';
const BRANCHES = '子丑寅卯辰巳午未申酉戌亥';

/** 公元年 → 干支（4 年为甲子基准；2025 → 乙巳） */
export function ganzhiYear(year: number): string {
    const idx = ((year - 4) % 60 + 60) % 60;
    return STEMS[idx % 10] + BRANCHES[idx % 12];
}

/** 落款日期：岁次干支 + 公历（农历风格前缀，免引入农历库） */
export function formatMountDate(d: Date): string {
    return `岁次${ganzhiYear(d.getFullYear())} · ${d.getFullYear()}年${d.getMonth() + 1}月${d.getDate()}日`;
}

/** 导出文件名：zhikuncode-装裱-YYYYMMDD.png */
export function buildMountFilename(d: Date): string {
    const p = (n: number) => String(n).padStart(2, '0');
    return `zhikuncode-装裱-${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}.png`;
}

interface Rect { x: number; y: number; w: number; h: number }

/** 装裱版式几何（设计空间 CSS px）：画心/轴杆/绫边/文字锚点/印位 */
export interface MountLayout {
    width: number;
    height: number;
    /** 画心（文字区） */
    card: Rect;
    /** 轴杆：horizontal=天杆地杆横杆 / false=手卷左右竖杆 */
    rods: (Rect & { horizontal: boolean })[];
    /** 绫边/隔水渐变带（vertical=两侧竖边 / false=上下横边） */
    brocades: (Rect & { vertical: boolean })[];
    /** 双线版框（册页）：外框+内框 inset 值；其他模板为空 */
    frames: number[];
    /** 鱼尾中缝（册页）：中缝顶端锚点；其他模板为 null */
    fishtail: { x: number; y: number } | null;
    titlePos: { x: number; y: number };
    colophonPos: { x: number; y: number };
    datePos: { x: number; y: number };
    sealRect: { x: number; y: number; size: number };
}

/** 计算模板版式：杆/边/框/中缝全部按形制几何推导，画心内边距留白 */
export function mountLayout(template: MountTemplate): MountLayout {
    const { width, height } = MOUNT_SIZE[template];

    if (template === 'scroll') {
        // 立轴：天杆（上细）/地杆（下粗带轴头感），两侧绫边
        const rodTop: Rect = { x: 14, y: 8, w: width - 28, h: 16 };
        const rodBottom: Rect = { x: 6, y: height - 30, w: width - 12, h: 24 };
        const sideL: Rect = { x: 30, y: 40, w: 52, h: height - 92 };
        const sideR: Rect = { x: width - 82, y: 40, w: 52, h: height - 92 };
        const card: Rect = { x: sideL.x + sideL.w + 8, y: 56, w: sideR.x - sideL.x - sideL.w - 16, h: height - 128 };
        return {
            width, height, card,
            rods: [{ ...rodTop, horizontal: true }, { ...rodBottom, horizontal: true }],
            brocades: [{ ...sideL, vertical: true }, { ...sideR, vertical: true }],
            frames: [], fishtail: null,
            titlePos: { x: width / 2, y: card.y + card.h * 0.34 },
            colophonPos: { x: width / 2, y: card.y + card.h * 0.34 + 64 },
            datePos: { x: card.x + 28, y: card.y + card.h - 74 },
            sealRect: { x: card.x + 24, y: card.y + card.h - 62, size: 40 },  // 左下落款+印
        };
    }

    if (template === 'handscroll') {
        // 手卷：左右轴杆（竖杆）+ 杆内隔水渐变带，画心横展
        const rodL: Rect = { x: 8, y: 10, w: 20, h: height - 20 };
        const rodR: Rect = { x: width - 28, y: 10, w: 20, h: height - 20 };
        const dipL: Rect = { x: rodL.x + rodL.w + 6, y: 22, w: 38, h: height - 44 };
        const dipR: Rect = { x: width - 28 - 44, y: 22, w: 38, h: height - 44 };
        const card: Rect = { x: dipL.x + dipL.w + 8, y: 34, w: dipR.x - dipL.x - dipL.w - 16, h: height - 68 };
        return {
            width, height, card,
            rods: [{ ...rodL, horizontal: false }, { ...rodR, horizontal: false }],
            brocades: [{ ...dipL, vertical: true }, { ...dipR, vertical: true }],
            frames: [], fishtail: null,
            titlePos: { x: width / 2, y: card.y + card.h * 0.36 },
            colophonPos: { x: width / 2, y: card.y + card.h * 0.36 + 56 },
            datePos: { x: card.x + card.w - 28, y: card.y + card.h - 64 },
            sealRect: { x: card.x + card.w - 64, y: card.y + card.h - 56, size: 38 },  // 右下落款+印
        };
    }

    // 册页：双线版框（外 2px 内 1px，间距 8）+ 版心鱼尾中缝；画心整幅
    const card: Rect = { x: 44, y: 40, w: width - 88, h: height - 80 };
    return {
        width, height, card,
        rods: [], brocades: [],
        frames: [18, 26],  // 外框/内框相对画心的内缩
        fishtail: { x: width / 2, y: card.y + 26 },
        titlePos: { x: width / 2, y: card.y + card.h * 0.32 },
        colophonPos: { x: width / 2, y: card.y + card.h * 0.32 + 60 },
        datePos: { x: card.x + 34, y: card.y + card.h - 78 },
        sealRect: { x: card.x + 30, y: card.y + card.h - 66, size: 40 },  // 左下
    };
}

/** 供 stub 测试的最小 ctx 面（CanvasRenderingContext2D 子集） */
export interface MountDrawOptions {
    template: MountTemplate;
    palette: MountPalette;
    title: string;      // 会话标题（画心大字）
    colophon: string;   // 题跋（用户可编辑一句；默认判词）
    dateLabel: string;  // 落款（formatMountDate）
}

/** 文本超长省略（二分最长可容前缀 + 末尾补 …）。
    审查#6修复：旧实现逐字删除再全量 measureText（近似 O(n²)，
    10000 字实测约 9s 阻塞页面）；二分将测量次数降至 O(log n)。 */
export function fitCanvasText(
    ctx: Pick<CanvasRenderingContext2D, 'measureText'>,
    text: string,
    maxWidth: number,
): string {
    if (ctx.measureText(text).width <= maxWidth) return text;
    let lo = 0;
    let hi = text.length;
    while (lo < hi) {
        const mid = Math.ceil((lo + hi) / 2);
        if (ctx.measureText(`${text.slice(0, mid)}…`).width <= maxWidth) lo = mid;
        else hi = mid - 1;
    }
    /* 与原实现同边界：至少保留 1 个字符（连 1 字 + … 都超宽时退化输出 …） */
    return `${text.slice(0, Math.max(lo, 1))}…`;
}

/** 题跋逐字换行（CJK 无空格，按测量宽度断行；最多 maxLines 行，溢出省略） */
export function wrapCanvasText(
    ctx: Pick<CanvasRenderingContext2D, 'measureText'>,
    text: string,
    maxWidth: number,
    maxLines: number,
): string[] {
    const lines: string[] = [];
    let line = '';
    for (const ch of text) {
        if (ctx.measureText(line + ch).width > maxWidth && line) {
            lines.push(line);
            line = ch;
            if (lines.length === maxLines) break;
        } else {
            line += ch;
        }
    }
    if (lines.length < maxLines && line) lines.push(line);
    // 溢出收敛：末行补省略号
    if (lines.length === maxLines && line && lines[maxLines - 1] !== line) {
        lines[maxLines - 1] = fitCanvasText(ctx, `${lines[maxLines - 1]}…`, maxWidth);
    }
    return lines;
}

/** 圆角矩形路径（roundRect 兜底：老环境回退直角） */
function traceRoundRect(ctx: CanvasRenderingContext2D, x: number, y: number, w: number, h: number, r: number) {
    ctx.beginPath();
    if (typeof ctx.roundRect === 'function') {
        ctx.roundRect(x, y, w, h, r);
    } else {
        ctx.rect(x, y, w, h);
    }
}

/**
 * 主绘制：宣纸底 → 装裱形制（杆/绫/框/鱼尾）→ 画心 → 文字 → 朱印。
 * 调用侧负责 ctx 缩放（2x 导出：ctx.scale(2,2) 后按设计空间绘制）。
 */
export function drawMountedCard(ctx: CanvasRenderingContext2D, opts: MountDrawOptions): MountLayout {
    const { palette: P } = opts;
    const layout = mountLayout(opts.template);
    const { width, height, card } = layout;
    const FONT = '"BrushKaiti", "Kaiti SC", "STKaiti", serif';

    // 宣纸底（微渐变仿绢纸）
    const bg = ctx.createLinearGradient(0, 0, width, height);
    bg.addColorStop(0, P.paper);
    bg.addColorStop(1, P.card);
    ctx.fillStyle = bg;
    ctx.fillRect(0, 0, width, height);

    // 绫边/隔水渐变带
    for (const b of layout.brocades) {
        const g = b.vertical
            ? ctx.createLinearGradient(b.x, 0, b.x + b.w, 0)
            : ctx.createLinearGradient(0, b.y, 0, b.y + b.h);
        g.addColorStop(0, P.brocadeFrom);
        g.addColorStop(0.5, P.brocadeTo);
        g.addColorStop(1, P.brocadeFrom);
        ctx.fillStyle = g;
        ctx.fillRect(b.x, b.y, b.w, b.h);
        // 绫边掐金线（两侧各 1px）
        ctx.fillStyle = P.gold;
        if (b.vertical) {
            ctx.fillRect(b.x - 1, b.y, 1, b.h);
            ctx.fillRect(b.x + b.w, b.y, 1, b.h);
        } else {
            ctx.fillRect(b.x, b.y - 1, b.w, 1);
            ctx.fillRect(b.x, b.y + b.h, b.w, 1);
        }
    }

    // 轴杆（横杆两端出头作轴头；竖杆上下出轴头）
    for (const r of layout.rods) {
        ctx.fillStyle = P.rod;
        traceRoundRect(ctx, r.x, r.y, r.w, r.h, Math.min(r.w, r.h) / 2);
        ctx.fill();
        ctx.fillStyle = P.rodCap;
        if (r.horizontal) {
            ctx.beginPath(); ctx.arc(r.x, r.y + r.h / 2, r.h * 0.7, 0, Math.PI * 2); ctx.fill();
            ctx.beginPath(); ctx.arc(r.x + r.w, r.y + r.h / 2, r.h * 0.7, 0, Math.PI * 2); ctx.fill();
        } else {
            ctx.beginPath(); ctx.arc(r.x + r.w / 2, r.y, r.w * 0.7, 0, Math.PI * 2); ctx.fill();
            ctx.beginPath(); ctx.arc(r.x + r.w / 2, r.y + r.h, r.w * 0.7, 0, Math.PI * 2); ctx.fill();
        }
    }

    // 画心
    ctx.fillStyle = P.card;
    ctx.fillRect(card.x, card.y, card.w, card.h);
    ctx.strokeStyle = P.gold;
    ctx.lineWidth = 1;
    ctx.strokeRect(card.x + 0.5, card.y + 0.5, card.w - 1, card.h - 1);

    // 册页：双线版框 + 鱼尾中缝
    for (const inset of layout.frames) {
        ctx.strokeStyle = P.ink;
        ctx.lineWidth = inset === layout.frames[0] ? 2 : 1;
        ctx.strokeRect(card.x + inset, card.y + inset, card.w - inset * 2, card.h - inset * 2);
    }
    if (layout.fishtail) {
        const { x, y } = layout.fishtail;
        ctx.strokeStyle = P.ink;
        ctx.lineWidth = 1.5;
        // 版心中缝（上下各一段竖线）
        ctx.beginPath();
        ctx.moveTo(x, card.y + 26); ctx.lineTo(x, y + 44);
        ctx.moveTo(x, card.y + card.h - 70); ctx.lineTo(x, card.y + card.h - 26);
        ctx.stroke();
        // 鱼尾：中缝上端一对相向三角
        ctx.fillStyle = P.ink;
        ctx.beginPath();
        ctx.moveTo(x, y + 14);
        ctx.lineTo(x - 13, y); ctx.lineTo(x - 13, y + 28); ctx.closePath();
        ctx.moveTo(x, y + 14);
        ctx.lineTo(x + 13, y); ctx.lineTo(x + 13, y + 28); ctx.closePath();
        ctx.fill();
    }

    // 标题（画心大字，行楷；超限省略）
    ctx.fillStyle = P.ink;
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.font = `600 44px ${FONT}`;
    const title = fitCanvasText(ctx, opts.title, card.w - 96);
    ctx.fillText(title, layout.titlePos.x, layout.titlePos.y);

    // 题跋（淡墨小字，逐字换行至多 2 行）
    ctx.fillStyle = P.inkSoft;
    ctx.font = `22px ${FONT}`;
    const lines = wrapCanvasText(ctx, opts.colophon, card.w - 140, 2);
    lines.forEach((line, i) => {
        ctx.fillText(line, layout.colophonPos.x, layout.colophonPos.y + i * 34);
    });

    // 落款（干支日期，左下/右下随模板锚点）
    ctx.font = `18px ${FONT}`;
    ctx.textAlign = 'left';
    const anchorRight = opts.template === 'handscroll';
    if (anchorRight) {
        ctx.textAlign = 'right';
        ctx.fillText(opts.dateLabel, layout.datePos.x, layout.datePos.y);
    } else {
        ctx.fillText(opts.dateLabel, layout.datePos.x, layout.datePos.y);
    }

    // 朱印「知」：朱砂圆角方印 + 白文（落款旁自动盖印）
    const s = layout.sealRect;
    ctx.fillStyle = P.seal;
    traceRoundRect(ctx, s.x, s.y, s.size, s.size, 5);
    ctx.fill();
    ctx.strokeStyle = 'rgba(255,252,240,.85)';
    ctx.lineWidth = 2;
    traceRoundRect(ctx, s.x + 2, s.y + 2, s.size - 4, s.size - 4, 4);
    ctx.stroke();
    ctx.fillStyle = P.sealText;
    ctx.font = `${Math.round(s.size * 0.62)}px ${FONT}`;
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillText('知', s.x + s.size / 2, s.y + s.size / 2 + 1);

    return layout;
}
