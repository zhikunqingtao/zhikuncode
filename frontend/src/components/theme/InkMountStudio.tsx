/**
 * InkMountStudio — 波次3③ 「装裱间」弹窗（ink 双模式 · 双档可用）
 *
 * 单张卡片装裱导出（范围红线：不做长截图/聊天记录导出）：
 *   内容：当前会话标题（取首条用户消息，同 Header 约定）+ 干支落款 + 可编辑题跋
 *   形制：立轴（3:4 天杆地杆+绫边）/ 手卷（16:9 轴杆+隔水）/ 册页（双线版框+鱼尾中缝）
 *   落款：formatMountDate 干支日期 + 朱印「知」自动盖于左下/右下
 * 预览与导出共用同一 drawMountedCard（纯 Canvas 2D，零第三方库）：
 *   预览 1x 即绘即所得；导出 2x 清晰度 toBlob 触发下载，
 *   文件名 zhikuncode-装裱-YYYYMMDD.png。
 * BrushKaiti 经主题 @font-face 加载，绘制前 document.fonts.load 确保就绪。
 */

import { useCallback, useEffect, useRef, useState } from 'react';
import { Button, Dialog, Input, Tabs } from '@/components/ui';
import { useConfigStore } from '@/store/configStore';
import { useMessageStore } from '@/store/messageStore';
import {
    MOUNT_PALETTE_DARK,
    MOUNT_PALETTE_LIGHT,
    MOUNT_SIZE,
    MOUNT_TEMPLATES,
    buildMountFilename,
    drawMountedCard,
    formatMountDate,
    type MountTemplate,
} from './inkMountExport';

/** 默认判词（题跋占位，用户可改写） */
const DEFAULT_COLOPHON = '大闹天宫，落笔成章';

export function InkMountStudio({ open, onOpenChange }: { open: boolean; onOpenChange: (open: boolean) => void }) {
    const theme = useConfigStore(s => s.theme);
    // 会话标题：同 Header 约定取首条用户消息文本，无会话时给默认题名
    const sessionTitle = useMessageStore(s => {
        const block = s.messages.find(m => m.type === 'user')?.content.find(b => b.type === 'text');
        return block?.type === 'text' ? block.text : '';
    });

    const [template, setTemplate] = useState<MountTemplate>('scroll');
    const [colophon, setColophon] = useState(DEFAULT_COLOPHON);
    const [exporting, setExporting] = useState(false);
    const canvasRef = useRef<HTMLCanvasElement>(null);

    const isNight = theme.mode === 'ink-havoc-night';
    const palette = isNight ? MOUNT_PALETTE_DARK : MOUNT_PALETTE_LIGHT;
    /* 审查#6修复：装裱标题候选截断——会话标题取首条用户消息全文（可达上万字），
       预览/导出/题跋编辑每次重绘都要全量排版，长文本阻塞页面；画心大字区容量有限，
       候选先截 60 字（fitCanvasText 二分截断兜底精确省略） */
    const title = (sessionTitle.trim().slice(0, 60) || '未名研习');
    const size = MOUNT_SIZE[template];

    /** 统一绘制入口：预览 1x / 导出 2x 共用（导出侧先 scale(2,2) 再调） */
    const paint = useCallback((ctx: CanvasRenderingContext2D) => {
        drawMountedCard(ctx, {
            template,
            palette,
            title,
            colophon: colophon.trim() || DEFAULT_COLOPHON,
            dateLabel: formatMountDate(new Date()),
        });
    }, [template, palette, title, colophon]);

    // 预览重绘：弹窗打开 + 模板/题跋/标题/主题变化时；先确保行楷字体就绪
    useEffect(() => {
        if (!open) return;
        let cancelled = false;
        const render = () => {
            const canvas = canvasRef.current;
            const ctx = canvas?.getContext('2d');
            if (!canvas || !ctx || cancelled) return;
            canvas.width = size.width;
            canvas.height = size.height;
            ctx.clearRect(0, 0, size.width, size.height);
            paint(ctx);
        };
        // BrushKaiti 已在 ink-havoc.css @font-face 注册；load 失败也照常绘制（回退系统楷体）
        if (typeof document !== 'undefined' && document.fonts?.load) {
            void document.fonts.load('44px "BrushKaiti"').then(render, render);
        } else {
            render();
        }
        return () => { cancelled = true; };
    }, [open, paint, size]);

    /** 导出 PNG：2x 离屏 canvas → toBlob → 触发下载（纯 Canvas 2D，无第三方库） */
    const handleExport = useCallback(() => {
        const canvas = document.createElement('canvas');
        canvas.width = size.width * 2;
        canvas.height = size.height * 2;
        const ctx = canvas.getContext('2d');
        if (!ctx) return;
        setExporting(true);
        ctx.scale(2, 2);
        const finish = (blob: Blob | null) => {
            setExporting(false);
            if (!blob) return;
            const url = URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url;
            a.download = buildMountFilename(new Date());
            a.click();
            URL.revokeObjectURL(url);
        };
        if (typeof document !== 'undefined' && document.fonts?.load) {
            void document.fonts.load('44px "BrushKaiti"').then(
                () => { paint(ctx); canvas.toBlob(finish, 'image/png'); },
                () => { paint(ctx); canvas.toBlob(finish, 'image/png'); },
            );
        } else {
            paint(ctx);
            canvas.toBlob(finish, 'image/png');
        }
    }, [paint, size]);

    return (
        <Dialog
            open={open}
            onOpenChange={onOpenChange}
            title="装裱间"
            className="w-[92vw] max-w-[940px]"
            containerClassName="z-[10010]"
        >
            <div className="flex flex-col gap-4">
                {/* 形制切换：立轴 / 手卷 / 册页 */}
                <div className="flex items-center justify-between gap-3 max-md:flex-col max-md:items-start">
                    <Tabs
                        aria-label="装裱形制"
                        className="w-auto shrink-0"
                        items={MOUNT_TEMPLATES}
                        value={template}
                        onValueChange={(v) => setTemplate(v as MountTemplate)}
                    />
                    <div className="text-xs text-t3">题跋落款将随装裱一并钤印</div>
                </div>

                {/* 预览画布：绘制与导出同一实现，即绘即所得 */}
                <div className="flex justify-center rounded-panel border border-hairline bg-sunken2 p-3">
                    <canvas
                        ref={canvasRef}
                        className="max-h-[46vh] max-w-full rounded-[2px] shadow-e2"
                        style={{ aspectRatio: `${size.width} / ${size.height}` }}
                        aria-label={`装裱预览 · ${MOUNT_TEMPLATES.find(t => t.value === template)?.label ?? ''}`}
                    />
                </div>

                {/* 题跋（可编辑一句；空则落默认判词） */}
                <label className="flex items-center gap-3">
                    <span className="shrink-0 text-sm text-t2">题跋</span>
                    <Input
                        value={colophon}
                        onChange={(e) => setColophon(e.target.value)}
                        maxLength={40}
                        placeholder={DEFAULT_COLOPHON}
                        aria-label="题跋"
                    />
                </label>

                <div className="flex items-center justify-between gap-3">
                    <div className="text-xs text-t3">
                        {formatMountDate(new Date())} · 朱印「知」
                    </div>
                    <Button variant="primary" size="sm" onClick={handleExport} disabled={exporting}>
                        {exporting ? '装裱中…' : '导出 PNG'}
                    </Button>
                </div>
            </div>
        </Dialog>
    );
}

export default InkMountStudio;
