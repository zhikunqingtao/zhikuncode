/**
 * FileTreePanel — 侧边栏文件树导航组件
 * 使用 react-arborist 实现虚拟滚动文件树
 */

import { useEffect, useCallback, useMemo, useRef } from 'react';
import { Tree, NodeRendererProps } from 'react-arborist';
import { Search, X, RefreshCw, Loader2, Copy, Check } from 'lucide-react';
import { useFileTreeStore, type FileTreeNode } from '@/store/fileTreeStore';
import { useState } from 'react';

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
            <span className="inline-flex items-center justify-center w-4 h-4 text-[9px] font-bold rounded shrink-0"
                style={{
                    color: icon === 'TS' ? '#3178c6' : icon === 'JS' ? '#f7df1e' : 'var(--text-muted)',
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
            className={`group flex items-center gap-1.5 px-2 cursor-pointer rounded-sm transition-colors
                ${isSelected
                    ? 'bg-blue-500/15 text-[var(--text-primary)]'
                    : 'hover:bg-[var(--bg-hover)] text-[var(--text-secondary)]'}`}
        >
            {/* 展开/折叠指示器（目录） */}
            {!node.isLeaf && (
                <span className="text-[10px] text-[var(--text-muted)] w-3 shrink-0">
                    {node.isOpen ? '▾' : '▸'}
                </span>
            )}
            {node.isLeaf && <span className="w-3 shrink-0" />}

            <FileIcon icon={icon} />

            <span className="truncate text-[13px] leading-7 flex-1">
                {node.data.name}
            </span>

            {/* 复制路径按钮 — 仅文件，悬停时显示 */}
            {node.isLeaf && (
                <button
                    onClick={handleCopy}
                    className="p-0.5 rounded opacity-0 group-hover:opacity-100 transition-opacity
                        hover:bg-[var(--bg-hover)] text-[var(--text-muted)]"
                    title="复制路径"
                >
                    {copied
                        ? <Check className="w-3 h-3 text-green-500" />
                        : <Copy className="w-3 h-3" />
                    }
                </button>
            )}
        </div>
    );
}

// ── 默认 rootPath ──
const DEFAULT_ROOT = '.';

export function FileTreePanel() {
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

    return (
        <div className="flex flex-col h-full">
            {/* 顶部工具栏：搜索 + 刷新 */}
            <div className="p-2 border-b border-[var(--border)] flex items-center gap-1.5">
                <div className="flex-1 relative">
                    <Search className="absolute left-2 top-1/2 -translate-y-1/2 w-3.5 h-3.5 text-[var(--text-muted)]" />
                    <input
                        type="text"
                        value={searchQuery}
                        onChange={(e) => setSearchQuery(e.target.value)}
                        placeholder="搜索文件..."
                        className="w-full pl-7 pr-7 py-1.5 text-xs rounded
                            bg-[var(--bg-primary)] border border-[var(--border)]
                            text-[var(--text-primary)] placeholder:text-[var(--text-muted)]
                            focus:outline-none focus:border-blue-500/50 transition-colors"
                    />
                    {searchQuery && (
                        <button
                            onClick={handleClearSearch}
                            className="absolute right-1.5 top-1/2 -translate-y-1/2 p-0.5 rounded
                                hover:bg-[var(--bg-hover)] text-[var(--text-muted)]"
                        >
                            <X className="w-3.5 h-3.5" />
                        </button>
                    )}
                </div>
                <button
                    onClick={handleRefresh}
                    disabled={loading}
                    className="p-1.5 rounded hover:bg-[var(--bg-hover)] text-[var(--text-muted)]
                        disabled:opacity-50 transition-colors shrink-0"
                    title="刷新文件树"
                >
                    <RefreshCw className={`w-3.5 h-3.5 ${loading ? 'animate-spin' : ''}`} />
                </button>
            </div>

            {/* 内容区域 */}
            <div ref={containerRef} className="flex-1 overflow-hidden">
                {loading && !treeData && (
                    <div className="flex items-center justify-center h-full">
                        <Loader2 className="w-5 h-5 animate-spin text-[var(--text-muted)]" />
                    </div>
                )}

                {error && (
                    <div className="p-4 text-center">
                        <p className="text-xs text-red-500 mb-2">{error}</p>
                        <button
                            onClick={handleRefresh}
                            className="text-xs text-blue-500 hover:underline"
                        >
                            重试
                        </button>
                    </div>
                )}

                {treeData && arboristData.length === 0 && searchQuery && (
                    <div className="p-4 text-center text-[var(--text-muted)] text-xs">
                        未找到匹配 "{searchQuery}" 的文件
                    </div>
                )}

                {treeData && (arboristData.length > 0 || !searchQuery) && (
                    <Tree<ArboristNode>
                        data={arboristData}
                        width={256}
                        height={containerHeight}
                        rowHeight={28}
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
