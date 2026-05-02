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
    const [showButtons, setShowButtons] = useState(false);

    // Cache: code+theme → svg
    const cacheRef = useRef<Map<string, string>>(new Map());

    const theme = useConfigStore(s => s.theme);
    const isDark = useMemo(() => {
        return theme.mode === 'dark' ||
            theme.mode === 'glass' ||
            (theme.mode === 'system' && typeof window !== 'undefined' && window.matchMedia('(prefers-color-scheme: dark)').matches);
    }, [theme.mode]);

    const incomplete = useMemo(() => looksIncomplete(code), [code]);

    useEffect(() => {
        if (incomplete) {
            setSvg(null);
            setError(null);
            return;
        }

        const cacheKey = `${isDark ? 'd' : 'l'}:${code}`;
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
                initMermaid(isDark);
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
    }, [code, isDark, incomplete]);

    const handleCopySvg = useCallback(async () => {
        if (!svg) return;
        await navigator.clipboard.writeText(svg);
        setCopied(true);
        setTimeout(() => setCopied(false), 2000);
    }, [svg]);

    const handleDownloadPng = useCallback(async () => {
        if (!svg) return;
        const svgBlob = new Blob([svg], { type: 'image/svg+xml;charset=utf-8' });
        const url = URL.createObjectURL(svgBlob);
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
            URL.revokeObjectURL(url);
        };
        img.src = url;
    }, [svg]);

    // --- Loading state (streaming incomplete) ---
    if (incomplete) {
        return (
            <div className="rounded-lg border border-[var(--border)] bg-[var(--bg-secondary)] p-6 flex items-center justify-center gap-2">
                <div className="w-4 h-4 rounded-full bg-blue-400 animate-pulse" />
                <span className="text-sm text-[var(--text-secondary)]">Mermaid 图表加载中…</span>
            </div>
        );
    }

    // --- Error state ---
    if (error) {
        return (
            <div className="rounded-lg border border-red-500/50 bg-red-500/5 overflow-hidden">
                <div className="flex items-center gap-2 px-4 py-2 bg-red-500/10 border-b border-red-500/30 text-xs text-red-400">
                    <AlertTriangle size={14} />
                    <span>Mermaid 渲染失败: {error}</span>
                </div>
                <pre className="p-4 text-sm text-[var(--text-primary)] overflow-x-auto whitespace-pre font-mono">
                    {code}
                </pre>
            </div>
        );
    }

    // --- SVG rendered ---
    if (svg) {
        return (
            <div
                className="relative rounded-lg border border-[var(--border)] bg-[var(--bg-secondary)] overflow-hidden group"
                onMouseEnter={() => setShowButtons(true)}
                onMouseLeave={() => setShowButtons(false)}
            >
                {/* Export buttons */}
                <div
                    className={`absolute top-2 right-2 flex gap-1 z-10 transition-opacity ${showButtons ? 'opacity-100' : 'opacity-0'}`}
                >
                    <button
                        onClick={handleCopySvg}
                        className="p-1.5 rounded-md bg-[var(--bg-primary)]/80 border border-[var(--border)] hover:bg-[var(--bg-hover)] text-[var(--text-secondary)] hover:text-[var(--text-primary)] transition-colors backdrop-blur-sm"
                        title="复制 SVG"
                    >
                        {copied ? <Check size={14} /> : <Copy size={14} />}
                    </button>
                    <button
                        onClick={handleDownloadPng}
                        className="p-1.5 rounded-md bg-[var(--bg-primary)]/80 border border-[var(--border)] hover:bg-[var(--bg-hover)] text-[var(--text-secondary)] hover:text-[var(--text-primary)] transition-colors backdrop-blur-sm"
                        title="下载 PNG"
                    >
                        <Download size={14} />
                    </button>
                </div>

                {/* SVG container */}
                <div
                    ref={containerRef}
                    className="p-4 flex justify-center overflow-x-auto [&>svg]:max-w-full"
                    dangerouslySetInnerHTML={{ __html: svg }}
                />
            </div>
        );
    }

    // --- Initial render / loading ---
    return (
        <div className="rounded-lg border border-[var(--border)] bg-[var(--bg-secondary)] p-6 flex items-center justify-center">
            <div className="w-4 h-4 rounded-full bg-blue-400 animate-pulse" />
        </div>
    );
};

export default React.memo(MermaidBlock);
