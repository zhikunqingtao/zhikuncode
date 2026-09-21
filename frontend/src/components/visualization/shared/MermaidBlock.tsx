/**
 * MermaidBlock — Mermaid 图表渲染组件
 *
 * 将 mermaid 代码渲染为 SVG 图表，支持：
 * - 浅色/深色主题自动切换
 * - SVG 缓存避免重复渲染
 * - 流式输入时的 loading 状态
 * - 复制 SVG / 下载 PNG 导出
 */

import React, { useEffect, useRef, useState, useCallback, useMemo } from 'react';
import { Copy, Check, Download, AlertTriangle } from 'lucide-react';
import { useConfigStore } from '@/store/configStore';
import { initMermaid, renderMermaid } from '@/utils/mermaid-config';
import { resolveTheme } from '@/styles/design-tokens';

interface MermaidBlockProps {
    code: string;
}

/** Simple heuristic: if the code looks incomplete, skip rendering */
function looksIncomplete(code: string): boolean {
    const trimmed = code.trim();
    if (!trimmed) return true;
    // No diagram type keyword on first line
    const firstLine = trimmed.split('\n')[0].trim().toLowerCase();
    const diagramTypes = [
        'graph', 'flowchart', 'sequencediagram', 'sequence', 'classdiagram', 'class',
        'statediagram', 'state', 'erdiagram', 'er', 'gantt', 'pie', 'journey',
        'gitgraph', 'mindmap', 'timeline', 'sankey', 'quadrantchart', 'xychart',
        'block', 'packet', 'kanban', 'architecture',
    ];
    const hasType = diagramTypes.some(t => firstLine.startsWith(t));
    if (!hasType) return true;
    // Very short content (only type keyword, no body)
    if (trimmed.split('\n').length < 2) return true;
    return false;
}

let idCounter = 0;

const MermaidBlock: React.FC<MermaidBlockProps> = ({ code }) => {
    const containerRef = useRef<HTMLDivElement>(null);
    const [svg, setSvg] = useState<string | null>(null);
    const [error, setError] = useState<string | null>(null);
    const [copied, setCopied] = useState(false);

    // Cache: code+theme → svg
    const cacheRef = useRef<Map<string, string>>(new Map());

    const theme = useConfigStore(s => s.theme);
    // Glass diagrams use the light palette for a stable reading surface.
    const effectiveTheme = useMemo(() => resolveTheme(theme.mode), [theme.mode]);
    const accentColor = theme.accentColor;

    const diagramWidth = useMemo(() => {
        if (!svg) return undefined;
        const element = new DOMParser().parseFromString(svg, 'image/svg+xml').documentElement;
        const width = Number(element.getAttribute('viewBox')?.trim().split(/[\s,]+/)[2]);
        return Number.isFinite(width) && width > 0 ? `${width}px` : undefined;
    }, [svg]);

    const incomplete = useMemo(() => looksIncomplete(code), [code]);

    useEffect(() => {
        if (incomplete) {
            setSvg(null);
            setError(null);
            return;
        }

        const cacheKey = `${effectiveTheme === 'dark' ? 'd' : 'l'}:${accentColor ?? ''}:${code}`;
        const cached = cacheRef.current.get(cacheKey);
        if (cached) {
            setSvg(cached);
            setError(null);
            return;
        }

        let cancelled = false;
        const id = `mermaid-${Date.now()}-${++idCounter}`;

        (async () => {
            try {
                initMermaid(effectiveTheme, accentColor);
                const result = await renderMermaid(id, code);
                if (!cancelled) {
                    cacheRef.current.set(cacheKey, result.svg);
                    setSvg(result.svg);
                    setError(null);
                }
            } catch (err: unknown) {
                if (!cancelled) {
                    setSvg(null);
                    setError(err instanceof Error ? err.message : String(err));
                }
                // Clean up potentially orphaned element created by mermaid.render
                const orphan = document.getElementById(id);
                orphan?.remove();
            }
        })();

        return () => { cancelled = true; };
    }, [code, effectiveTheme, accentColor, incomplete]);

    const handleCopySvg = useCallback(async () => {
        if (!svg) return;
        await navigator.clipboard.writeText(svg);
        setCopied(true);
        setTimeout(() => setCopied(false), 2000);
    }, [svg]);

    const handleDownloadPng = useCallback(async () => {
        if (!svg) return;
        // Inline SVG images keep HTML labels exportable in Chrome's canvas.
        const url = `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`;
        const img = new Image();
        img.onload = () => {
            const scale = 2; // retina
            const canvas = document.createElement('canvas');
            canvas.width = img.naturalWidth * scale;
            canvas.height = img.naturalHeight * scale;
            const ctx = canvas.getContext('2d');
            if (ctx) {
                ctx.scale(scale, scale);
                ctx.drawImage(img, 0, 0);
            }
            const pngUrl = canvas.toDataURL('image/png');
            const a = document.createElement('a');
            a.href = pngUrl;
            a.download = 'mermaid-diagram.png';
            a.click();
        };
        img.src = url;
    }, [svg]);

    // --- Loading state (streaming incomplete) ---
    if (incomplete) {
        return (
            <div className="rounded-[14px] border border-[var(--v2-border-hairline)] bg-[var(--v2-bg-sunken)] p-6 flex items-center justify-center gap-2">
                <div className="w-4 h-4 rounded-full bg-accent2 animate-pulse" />
                <span className="text-sm text-[var(--v2-text-2)]">Mermaid 图表加载中…</span>
            </div>
        );
    }

    // --- Error state ---
    if (error) {
        return (
            <div className="rounded-[14px] border border-[color:color-mix(in_srgb,var(--v2-err)_50%,transparent)] bg-errsoft overflow-hidden">
                <div className="flex items-center gap-2 px-4 py-2 bg-errsoft border-b border-[color:color-mix(in_srgb,var(--v2-err)_30%,transparent)] text-[13px] text-err">
                    <AlertTriangle size={18} />
                    <span>Mermaid 渲染失败: {error}</span>
                </div>
                <pre className="p-4 panel-code text-[var(--v2-text-1)] overflow-x-auto whitespace-pre font-mono">
                    {code}
                </pre>
            </div>
        );
    }

    // --- SVG rendered ---
    if (svg) {
        return (
            <div
                data-testid="mermaid-block"
                className="relative rounded-[14px] border border-[var(--v2-border-hairline)] bg-[var(--v2-bg-sunken)] overflow-hidden group"
            >
                {/* Export buttons */}
                <div
                    className="flex justify-end gap-1 p-2 border-b border-border-hairline"
                >
                    <button
                        onClick={handleCopySvg}
                        className="panel-control p-1.5 rounded-md bg-[var(--v2-bg-surface)]/80 border border-[var(--v2-border-hairline)] hover:bg-[var(--v2-bg-hover)] text-[var(--v2-text-2)] hover:text-[var(--v2-text-1)] transition-colors backdrop-blur-sm"
                        title="复制 SVG"
                    >
                        {copied ? <Check size={18} /> : <Copy size={18} />}
                    </button>
                    <button
                        onClick={handleDownloadPng}
                        className="panel-control p-1.5 rounded-md bg-[var(--v2-bg-surface)]/80 border border-[var(--v2-border-hairline)] hover:bg-[var(--v2-bg-hover)] text-[var(--v2-text-2)] hover:text-[var(--v2-text-1)] transition-colors backdrop-blur-sm"
                        title="下载 PNG"
                    >
                        <Download size={18} />
                    </button>
                </div>

                {/* SVG container */}
                <div
                    ref={containerRef}
                    role="region"
                    aria-label="Mermaid 图表，可横向滚动"
                    tabIndex={0}
                    className="p-4 overflow-x-auto [&>svg]:mx-auto [&>svg]:min-w-[var(--mermaid-width)]"
                    style={{ '--mermaid-width': diagramWidth } as React.CSSProperties}
                    dangerouslySetInnerHTML={{ __html: svg }}
                />
            </div>
        );
    }

    // --- Initial render / loading ---
    return (
        <div className="rounded-[14px] border border-[var(--v2-border-hairline)] bg-[var(--v2-bg-sunken)] p-6 flex items-center justify-center">
            <div className="w-4 h-4 rounded-full bg-accent2 animate-pulse" />
        </div>
    );
};

export default React.memo(MermaidBlock);
