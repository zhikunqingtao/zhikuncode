/**
 * APIContractViewer — API 契约可视化主容器组件
 *
 * 自研轻量 OpenAPI 解析器，支持：
 * - 多数据源切换（merged / java / python）
 * - 按 Tag 分组的端点列表 + 全文搜索
 * - 桌面双栏 / 移动单栏自适应布局
 * - 端点详情（Parameters、Request Body、Responses）
 * - 骨架屏加载、错误/空状态、警告横幅
 */

import React, { useEffect, useMemo, useState, useCallback } from 'react';
import {
    Search, RefreshCw, AlertTriangle, ChevronRight, ChevronDown,
    Globe, Server, Cpu, FileWarning,
} from 'lucide-react';
import { useApiContractStore } from '@/store/apiContractStore';
import type {
    DataSource, EndpointDetail, ParameterObject, SchemaObject,
} from '@/store/apiContractStore';
import SchemaViewer from '@/components/visualization/backend/SchemaViewer';

// ── HTTP 方法颜色 ──

const METHOD_COLORS: Record<string, string> = {
    get:    'bg-green-500',
    post:   'bg-blue-500',
    put:    'bg-orange-500',
    delete: 'bg-red-500',
    patch:  'bg-purple-500',
    head:   'bg-gray-500',
    options:'bg-gray-500',
};

const METHOD_BORDER: Record<string, string> = {
    get:    'border-green-500/30',
    post:   'border-blue-500/30',
    put:    'border-orange-500/30',
    delete: 'border-red-500/30',
    patch:  'border-purple-500/30',
};

// ── 数据源 Tab 配置 ──

const SOURCE_TABS: { key: DataSource; label: string; icon: React.ReactNode }[] = [
    { key: 'merged', label: 'All', icon: <Globe size={13} /> },
    { key: 'java',   label: 'Java Backend', icon: <Server size={13} /> },
    { key: 'python', label: 'Python Service', icon: <Cpu size={13} /> },
];

// ── 辅助类型 ──

interface EndpointItem {
    path: string;
    method: string;
    detail: EndpointDetail;
}

interface TagGroup {
    tag: string;
    description?: string;
    endpoints: EndpointItem[];
}

// ── HTTP 方法 Badge ──

const MethodBadge: React.FC<{ method: string }> = ({ method }) => (
    <span className={`shrink-0 inline-flex items-center justify-center w-16 px-1.5 py-0.5 rounded text-[10px] font-bold uppercase text-white ${METHOD_COLORS[method] ?? 'bg-gray-500'}`}>
        {method}
    </span>
);

// ── 骨架屏 ──

const Skeleton: React.FC = () => (
    <div className="space-y-3 p-4 animate-pulse">
        <div className="h-4 bg-[var(--bg-secondary)] rounded w-1/3" />
        {[...Array(6)].map((_, i) => (
            <div key={i} className="flex items-center gap-2">
                <div className="h-5 w-14 bg-[var(--bg-secondary)] rounded" />
                <div className="h-4 bg-[var(--bg-secondary)] rounded flex-1" />
            </div>
        ))}
    </div>
);

// ── 参数表格 ──

const ParametersTable: React.FC<{ parameters: ParameterObject[] }> = ({ parameters }) => {
    if (parameters.length === 0) return null;
    return (
        <div>
            <h4 className="text-xs font-semibold text-[var(--text-primary)] mb-2">Parameters</h4>
            <div className="overflow-x-auto rounded-lg border border-[var(--border)]">
                <table className="w-full text-xs">
                    <thead>
                        <tr className="bg-[var(--bg-secondary)]">
                            <th className="text-left px-3 py-1.5 text-[var(--text-muted)] font-medium">Name</th>
                            <th className="text-left px-3 py-1.5 text-[var(--text-muted)] font-medium">In</th>
                            <th className="text-left px-3 py-1.5 text-[var(--text-muted)] font-medium">Type</th>
                            <th className="text-left px-3 py-1.5 text-[var(--text-muted)] font-medium">Required</th>
                            <th className="text-left px-3 py-1.5 text-[var(--text-muted)] font-medium">Description</th>
                        </tr>
                    </thead>
                    <tbody>
                        {parameters.map((p, i) => (
                            <tr key={i} className="border-t border-[var(--border)]">
                                <td className="px-3 py-1.5 font-mono text-[var(--text-primary)]">{p.name}</td>
                                <td className="px-3 py-1.5 text-[var(--text-secondary)]">{p.in}</td>
                                <td className="px-3 py-1.5 text-[var(--text-secondary)] font-mono">{p.schema?.type ?? '—'}</td>
                                <td className="px-3 py-1.5">{p.required ? <span className="text-red-500">Yes</span> : <span className="text-[var(--text-muted)]">No</span>}</td>
                                <td className="px-3 py-1.5 text-[var(--text-muted)]">{p.description ?? '—'}</td>
                            </tr>
                        ))}
                    </tbody>
                </table>
            </div>
        </div>
    );
};

// ── 端点详情面板 ──

const EndpointDetailPanel: React.FC<{
    path: string;
    method: string;
    detail: EndpointDetail;
    allSchemas?: Record<string, SchemaObject>;
}> = ({ path, method, detail, allSchemas }) => {
    const requestSchema = useMemo(() => {
        if (!detail.requestBody?.content) return null;
        const mediaType = detail.requestBody.content['application/json']
            ?? Object.values(detail.requestBody.content)[0];
        return mediaType?.schema ?? null;
    }, [detail.requestBody]);

    return (
        <div className="space-y-4 p-4">
            {/* Header */}
            <div>
                <div className="flex items-center gap-2 flex-wrap">
                    <MethodBadge method={method} />
                    <span className="font-mono text-sm font-semibold text-[var(--text-primary)] break-all">{path}</span>
                    {detail.deprecated && (
                        <span className="px-1.5 py-0.5 rounded bg-yellow-500/15 text-yellow-600 dark:text-yellow-400 text-[10px] font-medium">
                            Deprecated
                        </span>
                    )}
                </div>
                {detail.summary && (
                    <p className="text-sm text-[var(--text-secondary)] mt-1">{detail.summary}</p>
                )}
                {detail.description && detail.description !== detail.summary && (
                    <p className="text-xs text-[var(--text-muted)] mt-1">{detail.description}</p>
                )}
            </div>

            {/* Parameters */}
            {detail.parameters && detail.parameters.length > 0 && (
                <ParametersTable parameters={detail.parameters} />
            )}

            {/* Request Body */}
            {requestSchema && (
                <div>
                    <h4 className="text-xs font-semibold text-[var(--text-primary)] mb-2">
                        Request Body
                        {detail.requestBody?.required && <span className="text-red-500 ml-1">*</span>}
                    </h4>
                    {detail.requestBody?.description && (
                        <p className="text-[11px] text-[var(--text-muted)] mb-1">{detail.requestBody.description}</p>
                    )}
                    <div className="rounded-lg border border-[var(--border)] p-3 bg-[var(--bg-secondary)]">
                        <SchemaViewer schema={requestSchema} allSchemas={allSchemas} />
                    </div>
                </div>
            )}

            {/* Responses */}
            {detail.responses && Object.keys(detail.responses).length > 0 && (
                <div>
                    <h4 className="text-xs font-semibold text-[var(--text-primary)] mb-2">Responses</h4>
                    <div className="space-y-2">
                        {Object.entries(detail.responses).map(([code, resp]) => {
                            const respSchema = resp.content?.['application/json']?.schema
                                ?? (resp.content ? Object.values(resp.content)[0]?.schema : null);
                            return (
                                <div key={code} className="rounded-lg border border-[var(--border)] overflow-hidden">
                                    <div className={`flex items-center gap-2 px-3 py-1.5 bg-[var(--bg-secondary)] border-b border-[var(--border)]`}>
                                        <span className={`font-mono text-xs font-bold ${code.startsWith('2') ? 'text-green-500' : code.startsWith('4') ? 'text-yellow-500' : code.startsWith('5') ? 'text-red-500' : 'text-[var(--text-secondary)]'}`}>
                                            {code}
                                        </span>
                                        {resp.description && (
                                            <span className="text-[11px] text-[var(--text-muted)]">{resp.description}</span>
                                        )}
                                    </div>
                                    {respSchema && (
                                        <div className="p-3">
                                            <SchemaViewer schema={respSchema} allSchemas={allSchemas} />
                                        </div>
                                    )}
                                </div>
                            );
                        })}
                    </div>
                </div>
            )}
        </div>
    );
};

// ── Tag 分组折叠面板 ──

const TagGroupPanel: React.FC<{
    group: TagGroup;
    selectedEndpoint: { path: string; method: string } | null;
    onSelect: (ep: { path: string; method: string }) => void;
    /** 移动端模式下展开详情 */
    isMobile?: boolean;
    allSchemas?: Record<string, SchemaObject>;
}> = ({ group, selectedEndpoint, onSelect, isMobile, allSchemas }) => {
    const [expanded, setExpanded] = useState(true);

    return (
        <div className="border-b border-[var(--border)] last:border-b-0">
            {/* Tag header */}
            <button
                onClick={() => setExpanded(e => !e)}
                className="w-full flex items-center gap-2 px-3 py-2 hover:bg-[var(--bg-hover)] transition-colors"
            >
                {expanded ? <ChevronDown size={14} className="text-[var(--text-muted)]" /> : <ChevronRight size={14} className="text-[var(--text-muted)]" />}
                <span className="text-xs font-semibold text-[var(--text-primary)]">{group.tag}</span>
                <span className="text-[10px] text-[var(--text-muted)]">({group.endpoints.length})</span>
                {group.description && (
                    <span className="text-[10px] text-[var(--text-muted)] truncate ml-1 hidden sm:inline">— {group.description}</span>
                )}
            </button>

            {/* Endpoints */}
            {expanded && (
                <div>
                    {group.endpoints.map(ep => {
                        const isSelected = selectedEndpoint?.path === ep.path && selectedEndpoint?.method === ep.method;
                        return (
                            <div key={`${ep.method}-${ep.path}`}>
                                <button
                                    onClick={() => onSelect({ path: ep.path, method: ep.method })}
                                    className={`w-full flex items-center gap-2 px-4 py-1.5 text-left hover:bg-[var(--bg-hover)] transition-colors
                                        ${isSelected ? 'bg-blue-500/8 border-l-2 border-l-blue-500' : 'border-l-2 border-l-transparent'}`}
                                >
                                    <MethodBadge method={ep.method} />
                                    <span className="font-mono text-xs text-[var(--text-primary)] truncate">{ep.path}</span>
                                    {ep.detail.summary && (
                                        <span className="text-[10px] text-[var(--text-muted)] truncate ml-auto hidden md:inline">
                                            {ep.detail.summary}
                                        </span>
                                    )}
                                </button>
                                {/* 移动端：选中时展开详情 */}
                                {isMobile && isSelected && (
                                    <div className={`border-t border-[var(--border)] ${METHOD_BORDER[ep.method] ?? ''}`}>
                                        <EndpointDetailPanel
                                            path={ep.path}
                                            method={ep.method}
                                            detail={ep.detail}
                                            allSchemas={allSchemas}
                                        />
                                    </div>
                                )}
                            </div>
                        );
                    })}
                </div>
            )}
        </div>
    );
};

// ── 主组件 ──

interface APIContractViewerProps {
    source?: DataSource;
}

const HTTP_METHODS = ['get', 'post', 'put', 'delete', 'patch', 'head', 'options'];

export const APIContractViewer: React.FC<APIContractViewerProps> = ({ source: initialSource = 'merged' }) => {
    const {
        openApiSpec, source, selectedEndpoint, searchQuery, isLoading, error, warnings,
        fetchOpenApiSpec, setSource, setSelectedEndpoint, setSearchQuery,
    } = useApiContractStore();

    const [localSearch, setLocalSearch] = useState('');

    // 初始化加载
    useEffect(() => {
        fetchOpenApiSpec(initialSource);
    }, [initialSource, fetchOpenApiSpec]);

    // 切换数据源
    const handleSourceChange = useCallback((s: DataSource) => {
        setSource(s);
        fetchOpenApiSpec(s);
    }, [setSource, fetchOpenApiSpec]);

    // 搜索处理（简单防抖）
    useEffect(() => {
        const timer = setTimeout(() => setSearchQuery(localSearch), 200);
        return () => clearTimeout(timer);
    }, [localSearch, setSearchQuery]);

    const handleRefresh = useCallback(() => {
        fetchOpenApiSpec(source);
    }, [fetchOpenApiSpec, source]);

    // 构建 tag 分组
    const tagGroups = useMemo((): TagGroup[] => {
        if (!openApiSpec?.paths) return [];

        const groups = new Map<string, EndpointItem[]>();
        const tagDescMap = new Map<string, string>();
        openApiSpec.tags?.forEach(t => tagDescMap.set(t.name, t.description ?? ''));

        const lowerQuery = searchQuery.toLowerCase().trim();

        for (const [path, methods] of Object.entries(openApiSpec.paths)) {
            for (const [method, detail] of Object.entries(methods)) {
                if (!HTTP_METHODS.includes(method)) continue;
                const ep: EndpointItem = { path, method, detail };

                // 搜索过滤
                if (lowerQuery) {
                    const haystack = [
                        path, method, detail.summary, detail.description, detail.operationId,
                        ...(detail.tags ?? []),
                    ].filter(Boolean).join(' ').toLowerCase();
                    if (!haystack.includes(lowerQuery)) continue;
                }

                const tags = detail.tags && detail.tags.length > 0 ? detail.tags : ['Untagged'];
                for (const tag of tags) {
                    if (!groups.has(tag)) groups.set(tag, []);
                    groups.get(tag)!.push(ep);
                }
            }
        }

        // 按 tag 在 spec.tags 中的顺序排列，Untagged 放最后
        const orderedTags = openApiSpec.tags?.map(t => t.name) ?? [];
        const result: TagGroup[] = [];

        for (const tag of orderedTags) {
            const eps = groups.get(tag);
            if (eps) {
                result.push({ tag, description: tagDescMap.get(tag), endpoints: eps });
                groups.delete(tag);
            }
        }
        // 剩余未在 spec.tags 中定义的 tag
        for (const [tag, eps] of groups.entries()) {
            result.push({ tag, description: tagDescMap.get(tag), endpoints: eps });
        }

        return result;
    }, [openApiSpec, searchQuery]);

    // 选中端点的详情
    const selectedDetail = useMemo(() => {
        if (!selectedEndpoint || !openApiSpec?.paths) return null;
        const methods = openApiSpec.paths[selectedEndpoint.path];
        if (!methods) return null;
        return methods[selectedEndpoint.method] ?? null;
    }, [selectedEndpoint, openApiSpec]);

    const allSchemas = openApiSpec?.components?.schemas;
    const totalEndpoints = tagGroups.reduce((sum, g) => sum + g.endpoints.length, 0);

    // ── 渲染 ──

    return (
        <div className="flex flex-col h-full">
            {/* 警告横幅 */}
            {warnings.length > 0 && (
                <div className="flex items-start gap-2 px-3 py-2 bg-yellow-500/10 border-b border-yellow-500/30 text-xs text-yellow-600 dark:text-yellow-400">
                    <AlertTriangle size={14} className="shrink-0 mt-0.5" />
                    <div className="space-y-0.5">
                        {warnings.map((w, i) => (
                            <p key={i}>{w}</p>
                        ))}
                    </div>
                </div>
            )}

            {/* 顶部工具栏 */}
            <div className="flex flex-wrap items-center gap-2 px-3 py-2 border-b border-[var(--border)] shrink-0">
                {/* 数据源 Tab */}
                <div className="flex items-center rounded-lg border border-[var(--border)] overflow-hidden">
                    {SOURCE_TABS.map(tab => (
                        <button
                            key={tab.key}
                            onClick={() => handleSourceChange(tab.key)}
                            className={`flex items-center gap-1 px-2.5 py-1.5 text-xs transition-colors
                                ${source === tab.key
                                    ? 'bg-blue-500/15 text-blue-600 dark:text-blue-400 font-medium'
                                    : 'text-[var(--text-secondary)] hover:bg-[var(--bg-hover)]'}`}
                        >
                            {tab.icon}
                            <span className="hidden sm:inline">{tab.label}</span>
                        </button>
                    ))}
                </div>

                {/* 搜索框 */}
                <div className="relative flex-1 min-w-[160px] max-w-xs">
                    <Search size={13} className="absolute left-2.5 top-1/2 -translate-y-1/2 text-[var(--text-muted)]" />
                    <input
                        type="text"
                        value={localSearch}
                        onChange={e => setLocalSearch(e.target.value)}
                        placeholder="Search endpoints..."
                        className="w-full pl-8 pr-3 py-1.5 text-xs rounded-md border border-[var(--border)]
                            bg-[var(--bg-primary)] text-[var(--text-primary)]
                            placeholder:text-[var(--text-muted)] focus:outline-none focus:ring-1 focus:ring-blue-500/50"
                    />
                </div>

                {/* 刷新 */}
                <button
                    onClick={handleRefresh}
                    disabled={isLoading}
                    className="p-1.5 rounded-md border border-[var(--border)]
                        hover:bg-[var(--bg-hover)] text-[var(--text-secondary)] transition-colors disabled:opacity-50"
                    title="Refresh"
                >
                    <RefreshCw size={13} className={isLoading ? 'animate-spin' : ''} />
                </button>

                {/* 端点计数 */}
                <span className="ml-auto text-[10px] text-[var(--text-muted)]">
                    {totalEndpoints} endpoints
                </span>
            </div>

            {/* 内容区域 */}
            {isLoading ? (
                <Skeleton />
            ) : error ? (
                /* 错误状态 */
                <div className="flex flex-col items-center justify-center py-12 px-4 text-center">
                    <FileWarning className="w-10 h-10 text-red-400 mb-3 opacity-60" />
                    <p className="text-sm text-[var(--text-primary)] font-medium mb-1">Failed to load API specification</p>
                    <p className="text-xs text-[var(--text-muted)] mb-4 max-w-sm">{error}</p>
                    <button
                        onClick={handleRefresh}
                        className="flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs
                            bg-blue-500/15 text-blue-600 dark:text-blue-400 hover:bg-blue-500/25 transition-colors"
                    >
                        <RefreshCw size={12} />
                        Retry
                    </button>
                </div>
            ) : totalEndpoints === 0 ? (
                /* 空状态 */
                <div className="flex flex-col items-center justify-center py-12 px-4 text-center">
                    <Globe className="w-10 h-10 text-[var(--text-muted)] mb-3 opacity-40" />
                    <p className="text-sm text-[var(--text-muted)]">No endpoints found</p>
                    <p className="text-xs text-[var(--text-muted)] mt-1 opacity-60">
                        {searchQuery ? 'Try adjusting your search query' : 'The selected source has no API endpoints'}
                    </p>
                </div>
            ) : (
                /* 主内容 — 桌面双栏 / 移动单栏 */
                <div className="flex-1 overflow-hidden flex">
                    {/* 桌面：左侧端点列表 */}
                    <div className="w-full lg:w-[40%] overflow-y-auto border-r border-[var(--border)] lg:block">
                        {/* 移动端：单栏 Accordion */}
                        <div className="lg:hidden">
                            {tagGroups.map(group => (
                                <TagGroupPanel
                                    key={group.tag}
                                    group={group}
                                    selectedEndpoint={selectedEndpoint}
                                    onSelect={ep => setSelectedEndpoint(
                                        selectedEndpoint?.path === ep.path && selectedEndpoint?.method === ep.method ? null : ep
                                    )}
                                    isMobile
                                    allSchemas={allSchemas}
                                />
                            ))}
                        </div>
                        {/* 桌面：列表 */}
                        <div className="hidden lg:block">
                            {tagGroups.map(group => (
                                <TagGroupPanel
                                    key={group.tag}
                                    group={group}
                                    selectedEndpoint={selectedEndpoint}
                                    onSelect={setSelectedEndpoint}
                                    allSchemas={allSchemas}
                                />
                            ))}
                        </div>
                    </div>

                    {/* 桌面：右侧详情面板 */}
                    <div className="hidden lg:block flex-1 overflow-y-auto">
                        {selectedDetail && selectedEndpoint ? (
                            <EndpointDetailPanel
                                path={selectedEndpoint.path}
                                method={selectedEndpoint.method}
                                detail={selectedDetail}
                                allSchemas={allSchemas}
                            />
                        ) : (
                            <div className="flex flex-col items-center justify-center h-full text-center px-4">
                                <Search className="w-8 h-8 text-[var(--text-muted)] mb-2 opacity-30" />
                                <p className="text-sm text-[var(--text-muted)]">Select an endpoint to view details</p>
                            </div>
                        )}
                    </div>
                </div>
            )}
        </div>
    );
};

export default APIContractViewer;
