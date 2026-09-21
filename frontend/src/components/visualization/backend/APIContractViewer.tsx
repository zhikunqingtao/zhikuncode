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
    // 每项自带文字色，按深色主题下的底色明度配对前景（text-app2 = 页面底色，深色主题为深字）：
    // - sunken2 浅底 → t1 深字（白字在浅色主题对比度不足）；
    // - ok/warn 是「文字色」语义 token，深色主题为亮色值（#7BC79A/#E0A94A），作背景时白字仅
    //   2.0:1/2.1:1（WCAG AA 需 4.5:1），须 dark:text-app2 换深字（浅色白字 5.8:1+，深色深字 7.2:1+）；
    // - errstrong/accent2-strong 深色主题仍为深底（#C4453F/#0C7563），白字 4.9:1/5.6:1 达标，
    //   深字反而仅 3.2:1/2.8:1 会 FAIL —— 保持白字。
    get:    'bg-ok text-white dark:text-app2',
    post:   'bg-accent2-strong text-white',
    put:    'bg-warn text-white dark:text-app2',
    delete: 'bg-errstrong text-white',
    patch:  'bg-accent2-strong text-white',
    head:   'bg-sunken2 text-t1',
    options:'bg-sunken2 text-t1',
};

const METHOD_BORDER: Record<string, string> = {
    get:    'border-ok',
    post:   'border-accent2',
    put:    'border-warn',
    delete: 'border-err',
    patch:  'border-accent2',
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
    <span className={`shrink-0 inline-flex items-center justify-center w-16 px-1.5 py-0.5 rounded text-[13px] font-bold uppercase ${METHOD_COLORS[method] ?? 'bg-sunken2 text-t1'}`}>
        {method}
    </span>
);

// ── 骨架屏 ──

const Skeleton: React.FC = () => (
    <div className="space-y-3 p-4 animate-pulse">
        <div className="h-4 bg-[var(--v2-bg-sunken)] rounded w-1/3" />
        {[...Array(6)].map((_, i) => (
            <div key={i} className="flex items-center gap-2">
                <div className="h-5 w-14 bg-[var(--v2-bg-sunken)] rounded" />
                <div className="h-4 bg-[var(--v2-bg-sunken)] rounded flex-1" />
            </div>
        ))}
    </div>
);

// ── 参数表格 ──

const ParametersTable: React.FC<{ parameters: ParameterObject[] }> = ({ parameters }) => {
    if (parameters.length === 0) return null;
    return (
        <div>
            <h4 className="text-[13px] font-semibold text-[var(--v2-text-1)] mb-2">Parameters</h4>
            <div className="overflow-x-auto rounded-[14px] border border-[var(--v2-border-hairline)]">
                <table className="w-full text-[13px]">
                    <thead>
                        <tr className="bg-[var(--v2-bg-sunken)]">
                            <th className="text-left px-3 py-1.5 text-[var(--v2-text-2)] font-medium">Name</th>
                            <th className="text-left px-3 py-1.5 text-[var(--v2-text-2)] font-medium">In</th>
                            <th className="text-left px-3 py-1.5 text-[var(--v2-text-2)] font-medium">Type</th>
                            <th className="text-left px-3 py-1.5 text-[var(--v2-text-2)] font-medium">Required</th>
                            <th className="text-left px-3 py-1.5 text-[var(--v2-text-2)] font-medium">Description</th>
                        </tr>
                    </thead>
                    <tbody>
                        {parameters.map((p, i) => (
                            <tr key={i} className="border-t border-[var(--v2-border-hairline)]">
                                <td className="px-3 py-1.5 font-mono text-[var(--v2-text-1)]">{p.name}</td>
                                <td className="px-3 py-1.5 text-[var(--v2-text-2)]">{p.in}</td>
                                <td className="px-3 py-1.5 text-[var(--v2-text-2)] font-mono">{p.schema?.type ?? '—'}</td>
                                <td className="px-3 py-1.5">{p.required ? <span className="text-err">Yes</span> : <span className="text-[var(--v2-text-2)]">No</span>}</td>
                                <td className="px-3 py-1.5 text-[var(--v2-text-2)]">{p.description ?? '—'}</td>
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
                    <span className="font-mono text-sm font-semibold text-[var(--v2-text-1)] break-all">{path}</span>
                    {detail.deprecated && (
                        <span className="px-1.5 py-0.5 rounded bg-warnsoft text-warn text-[13px] font-medium">
                            Deprecated
                        </span>
                    )}
                </div>
                {detail.summary && (
                    <p className="text-sm text-[var(--v2-text-2)] mt-1">{detail.summary}</p>
                )}
                {detail.description && detail.description !== detail.summary && (
                    <p className="text-[13px] text-[var(--v2-text-2)] mt-1">{detail.description}</p>
                )}
            </div>

            {/* Parameters */}
            {detail.parameters && detail.parameters.length > 0 && (
                <ParametersTable parameters={detail.parameters} />
            )}

            {/* Request Body */}
            {requestSchema && (
                <div>
                    <h4 className="text-[13px] font-semibold text-[var(--v2-text-1)] mb-2">
                        Request Body
                        {detail.requestBody?.required && <span className="text-err ml-1">*</span>}
                    </h4>
                    {detail.requestBody?.description && (
                        <p className="text-[13px] text-[var(--v2-text-2)] mb-1">{detail.requestBody.description}</p>
                    )}
                    <div className="rounded-[14px] border border-[var(--v2-border-hairline)] p-3 bg-[var(--v2-bg-sunken)]">
                        <SchemaViewer schema={requestSchema} allSchemas={allSchemas} />
                    </div>
                </div>
            )}

            {/* Responses */}
            {detail.responses && Object.keys(detail.responses).length > 0 && (
                <div>
                    <h4 className="text-[13px] font-semibold text-[var(--v2-text-1)] mb-2">Responses</h4>
                    <div className="space-y-2">
                        {Object.entries(detail.responses).map(([code, resp]) => {
                            const respSchema = resp.content?.['application/json']?.schema
                                ?? (resp.content ? Object.values(resp.content)[0]?.schema : null);
                            return (
                                <div key={code} className="rounded-[14px] border border-[var(--v2-border-hairline)] overflow-hidden">
                                    <div className={`flex items-center gap-2 px-3 py-1.5 bg-[var(--v2-bg-sunken)] border-b border-[var(--v2-border-hairline)]`}>
                                        <span className={`font-mono text-[13px] font-bold ${code.startsWith('2') ? 'text-ok' : code.startsWith('4') ? 'text-warn' : code.startsWith('5') ? 'text-err' : 'text-[var(--v2-text-2)]'}`}>
                                            {code}
                                        </span>
                                        {resp.description && (
                                            <span className="text-[13px] text-[var(--v2-text-2)]">{resp.description}</span>
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
        <div className="border-b border-[var(--v2-border-hairline)] last:border-b-0">
            {/* Tag header */}
            <button
                onClick={() => setExpanded(e => !e)}
                className="panel-control w-full flex items-center gap-2 px-3 py-2 hover:bg-[var(--v2-bg-hover)] transition-colors"
            >
                {expanded ? <ChevronDown size={14} className="text-[var(--v2-text-2)]" /> : <ChevronRight size={14} className="text-[var(--v2-text-2)]" />}
                <span className="text-[13px] font-semibold text-[var(--v2-text-1)]">{group.tag}</span>
                <span className="text-[13px] text-[var(--v2-text-2)]">({group.endpoints.length})</span>
                {group.description && (
                    <span className="text-[13px] text-[var(--v2-text-2)] truncate ml-1 hidden md:inline">— {group.description}</span>
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
                                    className={`panel-control w-full flex items-center gap-2 px-4 py-1.5 text-left hover:bg-[var(--v2-bg-hover)] transition-colors
                                        ${isSelected ? 'bg-accent2-soft border-l-2 border-l-accent2' : 'border-l-2 border-l-transparent'}`}
                                >
                                    <MethodBadge method={ep.method} />
                                    <span className="font-mono text-[13px] text-[var(--v2-text-1)] truncate">{ep.path}</span>
                                    {ep.detail.summary && (
                                        <span className="text-[13px] text-[var(--v2-text-2)] truncate ml-auto hidden md:inline">
                                            {ep.detail.summary}
                                        </span>
                                    )}
                                </button>
                                {/* 移动端：选中时展开详情 */}
                                {isMobile && isSelected && (
                                    <div className={`border-t border-[var(--v2-border-hairline)] ${METHOD_BORDER[ep.method] ?? ''}`}>
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
                <div className="flex items-start gap-2 px-3 py-2 bg-warnsoft border-b border-warn text-[13px] text-warn">
                    <AlertTriangle size={14} className="shrink-0 mt-0.5" />
                    <div className="space-y-0.5">
                        {warnings.map((w, i) => (
                            <p key={i}>{w}</p>
                        ))}
                    </div>
                </div>
            )}

            {/* 顶部工具栏 */}
            <div className="flex flex-wrap items-center gap-2 px-3 py-2 border-b border-[var(--v2-border-hairline)] shrink-0">
                {/* 数据源 Tab */}
                <div className="flex items-center rounded-[14px] border border-[var(--v2-border-hairline)] overflow-hidden">
                    {SOURCE_TABS.map(tab => (
                        <button
                            key={tab.key}
                            onClick={() => handleSourceChange(tab.key)}
                            aria-label={tab.label}
                            aria-pressed={source === tab.key}
                            className={`panel-control flex items-center gap-1 px-2.5 py-1.5 text-[13px] transition-colors
                                ${source === tab.key
                                    ? 'bg-accent2-soft text-accent2-ink font-medium'
                                    : 'text-[var(--v2-text-2)] hover:bg-[var(--v2-bg-hover)]'}`}
                        >
                            {tab.icon}
                            <span className="hidden md:inline">{tab.label}</span>
                        </button>
                    ))}
                </div>

                {/* 搜索框 */}
                <div className="relative flex-1 min-w-[160px] max-w-xs">
                    <Search size={13} className="absolute left-2.5 top-1/2 -translate-y-1/2 text-[var(--v2-text-2)]" />
                    <input
                        type="text"
                        value={localSearch}
                        onChange={e => setLocalSearch(e.target.value)}
                        aria-label="搜索 API 端点"
                        placeholder="搜索 API 端点..."
                        className="w-full pl-8 pr-3 py-1.5 text-[13px] rounded-md border border-[var(--v2-border-hairline)]
                            bg-[var(--v2-bg-surface)] text-[var(--v2-text-1)]
                            placeholder:text-[var(--v2-text-2)] focus:outline-none focus:ring-1 focus:ring-accent2-ring"
                    />
                </div>

                {/* 刷新 */}
                <button
                    onClick={handleRefresh}
                    disabled={isLoading}
                    className="panel-control p-1.5 rounded-md border border-[var(--v2-border-hairline)]
                        hover:bg-[var(--v2-bg-hover)] text-[var(--v2-text-2)] transition-colors disabled:opacity-50"
                    title="刷新 API 文档"
                >
                    <RefreshCw size={13} className={isLoading ? 'animate-spin' : ''} />
                </button>

                {/* 端点计数 */}
                <span className="ml-auto text-[13px] text-[var(--v2-text-2)]">
                    {totalEndpoints} 个端点
                </span>
            </div>

            {/* 内容区域 */}
            {isLoading ? (
                <Skeleton />
            ) : error ? (
                /* 错误状态 */
                <div className="flex flex-col items-center justify-center py-12 px-4 text-center">
                    <FileWarning className="w-10 h-10 text-err mb-3 opacity-60" />
                    <p className="text-sm text-[var(--v2-text-1)] font-medium mb-1">API 文档加载失败</p>
                    <p className="text-[13px] text-[var(--v2-text-2)] mb-4 max-w-sm">{error}</p>
                    <button
                        onClick={handleRefresh}
                        className="panel-control flex items-center gap-1.5 px-3 py-1.5 rounded-md text-[13px]
                            bg-accent2-soft text-accent2-ink hover:bg-accent2-soft transition-colors"
                    >
                        <RefreshCw size={12} />
                        重试
                    </button>
                </div>
            ) : totalEndpoints === 0 ? (
                /* 空状态 */
                <div className="flex flex-col items-center justify-center py-12 px-4 text-center">
                    <Globe className="w-10 h-10 text-[var(--v2-text-2)] mb-3 opacity-40" />
                    <p className="text-sm text-[var(--v2-text-2)]">未找到 API 端点</p>
                    <p className="text-[13px] text-[var(--v2-text-2)] mt-1 opacity-60">
                        {searchQuery ? '请调整搜索条件' : '当前来源没有 API 端点'}
                    </p>
                </div>
            ) : (
                /* 主内容 — 桌面双栏 / 移动单栏 */
                <div className="flex-1 min-h-0 min-w-0 overflow-hidden flex">
                    {/* 桌面：左侧端点列表 */}
                    <div className="w-full lg:w-[40%] overflow-y-auto border-r border-[var(--v2-border-hairline)] lg:block">
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
                    <div className="hidden lg:block flex-1 min-w-0 overflow-auto">
                        {selectedDetail && selectedEndpoint ? (
                            <EndpointDetailPanel
                                path={selectedEndpoint.path}
                                method={selectedEndpoint.method}
                                detail={selectedDetail}
                                allSchemas={allSchemas}
                            />
                        ) : (
                            <div className="flex flex-col items-center justify-center h-full text-center px-4">
                                <Search className="w-8 h-8 text-[var(--v2-text-2)] mb-2 opacity-30" />
                                <p className="text-sm text-[var(--v2-text-2)]">Select an endpoint to view details</p>
                            </div>
                        )}
                    </div>
                </div>
            )}
        </div>
    );
};

export default APIContractViewer;
