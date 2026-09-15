import { useResponsive } from '@/hooks/useResponsive';
/**
 * FileTreePanel — 侧边栏文件树导航组件
 * 使用 react-arborist 实现虚拟滚动文件树
 */

import { useEffect, useCallback, useMemo, useRef } from 'react';
import { Tree, NodeRendererProps } from 'react-arborist';
import { Search, X, RefreshCw, Loader2, Copy, Check } from 'lucide-react';
import { useFileTreeStore, type FileTreeNode } from '@/store/fileTreeStore';
import { Chip, Kbd } from '@/components/ui';
import { useState } from 'react';

/** 面板标题与辅助说明使用统一字号。 */
const PANEL_LABEL_CLASS = 'text-[13px] font-semibold text-t2';

// ── react-arborist 数据格式 ──

interface ArboristNode {
    id: string;
    name: string;
    children?: ArboristNode[];
    // custom data
    fileType: 'file' | 'dir';
    extension?: string | null;
    size?: number | null;
    relativePath: string;
}

/** 获取文件类型图标 */
function getFileIcon(node: ArboristNode, isOpen: boolean): string {
    if (node.fileType === 'dir') return isOpen ? '📂' : '📁';
    switch (node.extension) {
        case '.ts':
        case '.tsx': return 'TS';
        case '.js':
        case '.jsx': return 'JS';
        case '.java': return '☕';
        case '.py': return '🐍';
        case '.json': return '{}';
        case '.md': return '📝';
        case '.css':
        case '.scss':
        case '.less': return '🎨';
        case '.html': return '🌐';
        case '.yaml':
        case '.yml': return '⚙️';
        case '.sh': return '💻';
        case '.sql': return '🗃️';
        default: return '📄';
    }
}

/** 图标文字样式：TS/JS 等文字类使用小字号 + 固定宽度 */
function FileIcon({ icon }: { icon: string }) {
    const isTextIcon = icon === 'TS' || icon === 'JS' || icon === '{}';
    if (isTextIcon) {
        return (
            <span className="inline-flex items-center justify-center w-4 h-4 text-[13px] font-bold rounded shrink-0"
                style={{
                    color: icon === 'TS' ? '#3178c6' : icon === 'JS' ? '#f7df1e' : 'var(--v2-text-3)',
                    backgroundColor: icon === 'TS' ? '#3178c620' : icon === 'JS' ? '#f7df1e20' : 'transparent',
                }}>
                {icon}
            </span>
        );
    }
    return <span className="inline-flex items-center justify-center w-4 h-4 text-sm shrink-0">{icon}</span>;
}

/** 将 FileTreeNode 转为 react-arborist 需要的格式 */
function convertToArboristData(node: FileTreeNode, query: string): ArboristNode[] | null {
    const lowerQuery = query.toLowerCase();

    function matches(n: FileTreeNode): boolean {
        if (n.name.toLowerCase().includes(lowerQuery)) return true;
        if (n.children) return n.children.some(matches);
        return false;
    }

    function convert(n: FileTreeNode): ArboristNode | null {
        if (query && !matches(n)) return null;

        const children = n.children
            ?.map(convert)
            .filter((c): c is ArboristNode => c !== null);

        return {
            id: n.path,
            name: n.name,
            children: n.type === 'dir' ? (children ?? []) : undefined,
            fileType: n.type as 'file' | 'dir',
            extension: n.extension,
            size: n.size,
            relativePath: n.path,
        };
    }

    if (!node.children) return [];
    return node.children
        .map(convert)
        .filter((c): c is ArboristNode => c !== null);
}

/** 自定义节点渲染 */
function FileNode({ node, style, dragHandle }: NodeRendererProps<ArboristNode>) {
    const selectedPath = useFileTreeStore(s => s.selectedPath);
    const setSelected = useFileTreeStore(s => s.setSelected);
    const [copied, setCopied] = useState(false);

    const isSelected = selectedPath === node.data.relativePath;
    const icon = getFileIcon(node.data, node.isOpen);

    const handleClick = useCallback(() => {
        if (node.isLeaf) {
            setSelected(node.data.relativePath);
        } else {
            node.toggle();
        }
    }, [node, setSelected]);

    const handleCopy = useCallback(async (e: React.MouseEvent) => {
        e.stopPropagation();
        try {
            await navigator.clipboard.writeText(node.data.relativePath);
            setCopied(true);
            setTimeout(() => setCopied(false), 1500);
        } catch {
            // fallback
        }
    }, [node.data.relativePath]);

    return (
        <div
            style={style}
            ref={dragHandle}
            onClick={handleClick}
            className={`group flex items-center gap-1.5 px-2 cursor-pointer rounded-md
                transition-interactive duration-fast
                ${isSelected
                    ? 'bg-accent2-soft text-t1'
                    : 'hover:bg-hover2 text-t2'}`}
        >
            {/* 展开/折叠指示器（目录） */}
            {!node.isLeaf && (
                <span className="text-[13px] text-t3 w-3 shrink-0">
                    {node.isOpen ? '▾' : '▸'}
                </span>
            )}
            {node.isLeaf && <span className="w-3 shrink-0" />}

            <FileIcon icon={icon} />

            <span className="truncate text-sm leading-7 flex-1">
                {node.data.name}
            </span>

            {/* 复制路径按钮 — 桌面悬停或聚焦显示，手机常驻 */}
            {node.isLeaf && (
                <button
                    onClick={handleCopy}
                    className="panel-control p-0.5 rounded opacity-0 group-hover:opacity-100 focus:opacity-100 max-md:opacity-100 max-md:min-h-11 max-md:min-w-11
                        hover:bg-hover2 text-t3 transition-interactive duration-fast"
                    title="复制路径"
                >
                    {copied
                        ? <Check className="w-[18px] h-[18px] text-ok" />
                        : <Copy className="w-[18px] h-[18px]" />
                    }
                </button>
            )}
        </div>
    );
}

// ── 默认 rootPath ──
const DEFAULT_ROOT = '.';

export function FileTreePanel({ sidebarWidth = 256 }: { sidebarWidth?: number }) {
    const { isMobile } = useResponsive();
    const { treeData, loading, error, searchQuery, fetchTree, setSearchQuery } = useFileTreeStore();
    const containerRef = useRef<HTMLDivElement>(null);
    const [containerHeight, setContainerHeight] = useState(500);

    // 初始化加载
    useEffect(() => {
        if (!treeData && !loading) {
            fetchTree(DEFAULT_ROOT);
        }
    }, [treeData, loading, fetchTree]);

    // 动态计算容器高度
    useEffect(() => {
        if (!containerRef.current) return;
        const observer = new ResizeObserver(entries => {
            for (const entry of entries) {
                setContainerHeight(entry.contentRect.height);
            }
        });
        observer.observe(containerRef.current);
        return () => observer.disconnect();
    }, []);

    // 刷新
    const handleRefresh = useCallback(() => {
        fetchTree(DEFAULT_ROOT);
    }, [fetchTree]);

    // 清除搜索
    const handleClearSearch = useCallback(() => {
        setSearchQuery('');
    }, [setSearchQuery]);

    // 将树数据转换为 react-arborist 格式
    const arboristData = useMemo(() => {
        if (!treeData) return [];
        return convertToArboristData(treeData, searchQuery) ?? [];
    }, [treeData, searchQuery]);

    // §7.5 计数 chip：全量文件数（不受搜索过滤影响）
    const totalFiles = useMemo(() => {
        if (!treeData) return 0;
        let count = 0;
        const walk = (n: FileTreeNode) => {
            if (n.type === 'file') { count += 1; return; }
            n.children?.forEach(walk);
        };
        treeData.children?.forEach(walk);
        return count;
    }, [treeData]);

    return (
        <div className="flex flex-col h-full">
            {/* §7.5 面板头：Label + 计数 chip */}
            <div className="flex items-center justify-between gap-2 px-3 pt-3 pb-2 flex-shrink-0">
                <span className={PANEL_LABEL_CLASS}>文件</span>
                <Chip variant="accent" className="tabular-nums">{totalFiles}</Chip>
            </div>

            {/* 顶部工具栏：搜索（sunken2 + well + ⌘K kbd 提示）+ 刷新 */}
            <div className="px-2 pb-2 border-b border-hairline flex items-center gap-1.5 flex-shrink-0">
                <div className="flex-1 relative">
                    <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 w-3.5 h-3.5 text-t3 pointer-events-none" />
                    <input
                        type="text"
                        value={searchQuery}
                        onChange={(e) => setSearchQuery(e.target.value)}
                        placeholder="搜索文件..."
                        aria-label="搜索文件"
                        className="w-full h-8 pl-8 pr-8 rounded-xl bg-sunken2 shadow-well
                            border border-transparent text-sm text-t1 placeholder:text-t4
                            transition-surface duration-fast
                            focus:outline-none focus:ring-[3px] focus:ring-accent2-ring"
                    />
                    {searchQuery ? (
                        <button
                            onClick={handleClearSearch}
                            aria-label="清除搜索"
                            className="panel-control absolute right-1.5 top-1/2 -translate-y-1/2 p-0.5 rounded-md
                                text-t3 hover:bg-hover2 hover:text-t1 transition-interactive duration-fast"
                        >
                            <X className="w-3.5 h-3.5" />
                        </button>
                    ) : (
                        <Kbd aria-hidden="true" className="absolute right-1.5 top-1/2 -translate-y-1/2 pointer-events-none">⌘K</Kbd>
                    )}
                </div>
                <button
                    onClick={handleRefresh}
                    disabled={loading}
                    className="panel-control p-1.5 rounded-lg hover:bg-hover2 text-t3 hover:text-t1
                        disabled:opacity-50 transition-interactive duration-fast shrink-0"
                    title="刷新文件树"
                >
                    <RefreshCw className={`w-3.5 h-3.5 ${loading ? 'animate-spin' : ''}`} />
                </button>
            </div>

            {/* 内容区域 */}
            <div ref={containerRef} className="flex-1 overflow-hidden">
                {loading && !treeData && (
                    <div className="flex items-center justify-center h-full">
                        <Loader2 className="w-5 h-5 animate-spin text-t3" />
                    </div>
                )}

                {error && (
                    <div className="p-4 text-center">
                        <p className="text-[13px] text-err mb-2">{error}</p>
                        <button
                            onClick={handleRefresh}
                            className="panel-control text-[13px] text-accent2-ink hover:underline"
                        >
                            重试
                        </button>
                    </div>
                )}

                {treeData && arboristData.length === 0 && searchQuery && (
                    <div className="p-4 text-center text-t2 text-[13px]">
                        未找到匹配 "{searchQuery}" 的文件
                    </div>
                )}

                {treeData && (arboristData.length > 0 || !searchQuery) && (
                    <Tree<ArboristNode>
                        data={arboristData}
                        width={Math.max(sidebarWidth - 16, 200)}
                        height={containerHeight}
                        rowHeight={isMobile ? 44 : 28}
                        indent={16}
                        openByDefault={false}
                        disableDrag
                        disableDrop
                    >
                        {FileNode}
                    </Tree>
                )}
            </div>
        </div>
    );
}
