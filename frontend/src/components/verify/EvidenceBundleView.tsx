/**
 * EvidenceBundleView — RV-4 证据包详情展示
 *
 * 设计风格对齐 JourneyVerifyPanel：
 * - 紧凑面板：border rounded-lg p-4
 * - 状态色：verified=green / failed=red / inconclusive=amber / running=blue
 * - 按 EvidenceItem.type 分组展示，提供 tabs 切换
 *
 * 数据来源：useEvidenceStore.currentBundle，组件挂载时按需触发 fetchBundle。
 */

import React, { useEffect, useMemo, useState } from 'react';
import { useEvidenceStore } from '@/store/evidenceStore';
import type { EvidenceBundle, EvidenceItem } from '@/store/evidenceStore';

interface EvidenceBundleViewProps {
    bundleId: string;
}

const ITEM_TYPE_LABELS: Record<string, string> = {
    screenshot: 'Screenshots',
    command: 'Commands',
    console: 'Console',
    test: 'Tests',
    video: 'Videos',
    har: 'Network',
    diff: 'Diffs',
};

export const EvidenceBundleView: React.FC<EvidenceBundleViewProps> = ({ bundleId }) => {
    const currentBundle = useEvidenceStore((s) => s.currentBundle);
    const loading = useEvidenceStore((s) => s.loading);
    const error = useEvidenceStore((s) => s.error);
    const fetchBundle = useEvidenceStore((s) => s.fetchBundle);

    useEffect(() => {
        if (!bundleId) return;
        if (currentBundle?.bundleId !== bundleId) {
            void fetchBundle(bundleId);
        }
    }, [bundleId, currentBundle?.bundleId, fetchBundle]);

    const grouped = useMemo(() => groupByType(currentBundle?.items ?? []), [currentBundle]);
    const groupKeys = useMemo(() => Object.keys(grouped), [grouped]);
    const [activeTab, setActiveTab] = useState<string | null>(null);

    useEffect(() => {
        if (groupKeys.length > 0 && (activeTab === null || !groupKeys.includes(activeTab))) {
            setActiveTab(groupKeys[0]);
        }
    }, [groupKeys, activeTab]);

    if (loading && (!currentBundle || currentBundle.bundleId !== bundleId)) {
        return (
            <div className="evidence-bundle-view border rounded-lg p-4 mt-2 text-xs text-gray-500">
                Loading evidence bundle…
            </div>
        );
    }

    if (error && !currentBundle) {
        return (
            <div className="evidence-bundle-view border rounded-lg p-4 mt-2 text-xs text-red-600 bg-red-50">
                Failed to load evidence bundle: {error}
            </div>
        );
    }

    if (!currentBundle) return null;

    const activeItems = activeTab ? grouped[activeTab] ?? [] : [];

    return (
        <div className="evidence-bundle-view border rounded-lg p-4 mt-2">
            <Header bundle={currentBundle} />

            {groupKeys.length === 0 ? (
                <div className="mt-3 text-xs text-gray-400">No evidence items.</div>
            ) : (
                <>
                    <div className="flex flex-wrap gap-1 mt-3 border-b pb-2">
                        {groupKeys.map((key) => (
                            <button
                                key={key}
                                type="button"
                                onClick={() => setActiveTab(key)}
                                className={
                                    'px-2 py-0.5 text-xs rounded transition-colors ' +
                                    (activeTab === key
                                        ? 'bg-blue-100 text-blue-700 font-medium'
                                        : 'text-gray-500 hover:bg-gray-100')
                                }
                            >
                                {(ITEM_TYPE_LABELS[key] ?? capitalize(key))}
                                <span className="ml-1 text-gray-400">
                                    ({grouped[key].length})
                                </span>
                            </button>
                        ))}
                    </div>

                    <div className="mt-3">
                        <ItemGroupRenderer type={activeTab ?? ''} items={activeItems} />
                    </div>
                </>
            )}
        </div>
    );
};

// ==================== Header ====================

const Header: React.FC<{ bundle: EvidenceBundle }> = ({ bundle }) => (
    <div className="flex items-center justify-between gap-2">
        <div className="min-w-0">
            <div className="flex items-center gap-2">
                <h3 className="text-sm font-medium truncate">
                    {bundle.claim || `Evidence Bundle ${shortId(bundle.bundleId)}`}
                </h3>
                <span className="px-1.5 py-0.5 text-[10px] rounded bg-gray-100 text-gray-600 uppercase">
                    {bundle.kind}
                </span>
            </div>
            <div className="text-xs text-gray-400 mt-0.5 font-mono truncate">
                {shortId(bundle.bundleId)} · {formatTimestamp(bundle.createdAt)}
            </div>
        </div>
        <VerdictBadge verdict={bundle.verdict} />
    </div>
);

const VerdictBadge: React.FC<{ verdict: string }> = ({ verdict }) => {
    const v = (verdict || '').toLowerCase();
    if (v === 'verified' || v === 'passed') {
        return <span className="px-2 py-0.5 text-xs rounded bg-green-100 text-green-700">Verified</span>;
    }
    if (v === 'failed') {
        return <span className="px-2 py-0.5 text-xs rounded bg-red-100 text-red-700">Failed</span>;
    }
    if (v === 'inconclusive') {
        return <span className="px-2 py-0.5 text-xs rounded bg-amber-100 text-amber-700">Inconclusive</span>;
    }
    return <span className="px-2 py-0.5 text-xs rounded bg-blue-100 text-blue-700">{verdict || 'Unknown'}</span>;
};

// ==================== Item Group Renderer ====================

const ItemGroupRenderer: React.FC<{ type: string; items: EvidenceItem[] }> = ({ type, items }) => {
    if (items.length === 0) {
        return <div className="text-xs text-gray-400">No items.</div>;
    }
    switch (type) {
        case 'screenshot':
            return <ScreenshotGrid items={items} />;
        case 'command':
            return <CommandList items={items} />;
        case 'console':
            return <ConsoleList items={items} />;
        case 'test':
            return <TestList items={items} />;
        case 'video':
            return <VideoList items={items} />;
        case 'har':
            return <HarTable items={items} />;
        case 'diff':
            return <DiffList items={items} />;
        default:
            return <GenericList items={items} />;
    }
};

// ---- screenshot ----
const ScreenshotGrid: React.FC<{ items: EvidenceItem[] }> = ({ items }) => (
    <div className="grid grid-cols-2 sm:grid-cols-3 gap-2">
        {items.map((item) => {
            const src = pickImageSrc(item);
            return (
                <div key={item.id} className="border rounded overflow-hidden bg-gray-50">
                    {src ? (
                        <img
                            src={src}
                            alt={item.summary ?? 'screenshot'}
                            className="w-full h-24 object-cover"
                            loading="lazy"
                        />
                    ) : (
                        <div className="w-full h-24 flex items-center justify-center text-[10px] text-gray-400">
                            no preview
                        </div>
                    )}
                    <div className="px-1.5 py-1 text-[11px] text-gray-600 truncate">
                        {item.summary ?? shortId(item.id)}
                    </div>
                </div>
            );
        })}
    </div>
);

// ---- command ----
const CommandList: React.FC<{ items: EvidenceItem[] }> = ({ items }) => (
    <div className="space-y-2">
        {items.map((item) => {
            const exitCode = typeof item.meta?.exitCode === 'number' ? item.meta.exitCode : null;
            const cmd = (item.meta?.command as string | undefined) ?? item.summary ?? '';
            const stdout = (item.meta?.stdout as string | undefined) ?? '';
            return (
                <div key={item.id} className="border rounded">
                    <div className="flex items-center justify-between px-2 py-1 bg-gray-50 border-b text-xs">
                        <span className="font-mono truncate">{cmd || shortId(item.id)}</span>
                        {exitCode !== null && (
                            <span
                                className={
                                    'ml-2 px-1.5 py-0.5 text-[10px] rounded ' +
                                    (exitCode === 0
                                        ? 'bg-green-100 text-green-700'
                                        : 'bg-red-100 text-red-700')
                                }
                            >
                                exit {exitCode}
                            </span>
                        )}
                    </div>
                    {stdout && (
                        <pre className="p-2 text-[11px] font-mono bg-gray-50 max-h-32 overflow-auto whitespace-pre-wrap">
                            {stdout}
                        </pre>
                    )}
                </div>
            );
        })}
    </div>
);

// ---- console ----
const ConsoleList: React.FC<{ items: EvidenceItem[] }> = ({ items }) => (
    <div className="space-y-1">
        {items.map((item) => {
            const level = (item.meta?.level as string | undefined) ?? 'error';
            const isError = level === 'error' || level === 'severe';
            return (
                <div
                    key={item.id}
                    className={
                        'flex items-start gap-2 px-2 py-1 rounded text-xs ' +
                        (isError ? 'bg-red-50 text-red-700' : 'bg-amber-50 text-amber-700')
                    }
                >
                    <span className="font-mono uppercase text-[10px] shrink-0 mt-0.5">{level}</span>
                    <span className="font-mono break-all">
                        {item.summary ?? JSON.stringify(item.meta)}
                    </span>
                </div>
            );
        })}
    </div>
);

// ---- test ----
const TestList: React.FC<{ items: EvidenceItem[] }> = ({ items }) => (
    <div className="space-y-1">
        {items.map((item) => {
            const passed = pickBool(item.meta?.passed) ?? pickBool(item.meta?.ok) ?? null;
            const ok = passed === true;
            const fail = passed === false;
            return (
                <div key={item.id} className="flex items-center gap-2 text-xs">
                    <span
                        className={ok ? 'text-green-500' : fail ? 'text-red-500' : 'text-gray-400'}
                    >
                        {ok ? '✓' : fail ? '✗' : '·'}
                    </span>
                    <span className="font-mono truncate">
                        {item.summary ?? (item.meta?.name as string | undefined) ?? shortId(item.id)}
                    </span>
                    {typeof item.meta?.durationMs === 'number' && (
                        <span className="text-gray-400 ml-auto">{item.meta.durationMs}ms</span>
                    )}
                </div>
            );
        })}
    </div>
);

// ---- video ----
const VideoList: React.FC<{ items: EvidenceItem[] }> = ({ items }) => (
    <div className="space-y-2">
        {items.map((item) => {
            const url = (item.meta?.url as string | undefined) ?? pickBlobHref(item);
            return (
                <div key={item.id} className="border rounded p-2 text-xs">
                    <div className="text-gray-600 mb-1 truncate">
                        {item.summary ?? shortId(item.id)}
                    </div>
                    {url ? (
                        <video controls src={url} className="w-full max-h-48 bg-black rounded" />
                    ) : (
                        <span className="text-gray-400">no source</span>
                    )}
                </div>
            );
        })}
    </div>
);

// ---- har ----
const HarTable: React.FC<{ items: EvidenceItem[] }> = ({ items }) => (
    <div className="overflow-x-auto">
        <table className="min-w-full text-xs">
            <thead>
                <tr className="text-gray-500 border-b">
                    <th className="text-left px-2 py-1 font-medium">Method</th>
                    <th className="text-left px-2 py-1 font-medium">URL</th>
                    <th className="text-right px-2 py-1 font-medium">Status</th>
                </tr>
            </thead>
            <tbody>
                {items.map((item) => {
                    const method = (item.meta?.method as string | undefined) ?? 'GET';
                    const url = (item.meta?.url as string | undefined) ?? item.summary ?? '';
                    const status = item.meta?.status as number | undefined;
                    const ok = typeof status === 'number' && status >= 200 && status < 400;
                    return (
                        <tr key={item.id} className="border-b last:border-b-0">
                            <td className="px-2 py-1 font-mono">{method}</td>
                            <td className="px-2 py-1 font-mono truncate max-w-[280px]">{url}</td>
                            <td
                                className={
                                    'px-2 py-1 text-right font-mono ' +
                                    (status === undefined
                                        ? 'text-gray-400'
                                        : ok
                                            ? 'text-green-600'
                                            : 'text-red-600')
                                }
                            >
                                {status ?? '—'}
                            </td>
                        </tr>
                    );
                })}
            </tbody>
        </table>
    </div>
);

// ---- diff ----
const DiffList: React.FC<{ items: EvidenceItem[] }> = ({ items }) => (
    <div className="space-y-2">
        {items.map((item) => {
            const patch = (item.meta?.patch as string | undefined) ?? item.summary ?? '';
            return (
                <div key={item.id} className="border rounded">
                    {item.meta?.path ? (
                        <div className="px-2 py-1 bg-gray-50 border-b text-[11px] font-mono truncate">
                            {String(item.meta.path)}
                        </div>
                    ) : null}
                    <pre className="p-2 text-[11px] font-mono bg-gray-50 max-h-48 overflow-auto whitespace-pre">
                        {renderDiffWithColor(patch)}
                    </pre>
                </div>
            );
        })}
    </div>
);

const renderDiffWithColor = (patch: string): React.ReactNode =>
    patch.split('\n').map((line, idx) => {
        let cls = 'text-gray-700';
        if (line.startsWith('+') && !line.startsWith('+++')) cls = 'text-green-600';
        else if (line.startsWith('-') && !line.startsWith('---')) cls = 'text-red-600';
        else if (line.startsWith('@@')) cls = 'text-blue-600';
        return (
            <span key={idx} className={cls}>
                {line + '\n'}
            </span>
        );
    });

// ---- generic ----
const GenericList: React.FC<{ items: EvidenceItem[] }> = ({ items }) => (
    <div className="space-y-1">
        {items.map((item) => (
            <div key={item.id} className="text-xs border rounded px-2 py-1">
                <div className="font-mono text-gray-700 truncate">
                    {item.summary ?? shortId(item.id)}
                </div>
                {Object.keys(item.meta ?? {}).length > 0 && (
                    <pre className="mt-1 text-[10px] text-gray-500 font-mono whitespace-pre-wrap break-all">
                        {safeJsonStringify(item.meta)}
                    </pre>
                )}
            </div>
        ))}
    </div>
);

// ==================== utils ====================

function groupByType(items: EvidenceItem[]): Record<string, EvidenceItem[]> {
    return items.reduce<Record<string, EvidenceItem[]>>((acc, item) => {
        const key = item.type || 'other';
        (acc[key] ??= []).push(item);
        return acc;
    }, {});
}

function shortId(id: string | null | undefined): string {
    if (!id) return '—';
    return id.length > 12 ? `${id.slice(0, 8)}…${id.slice(-3)}` : id;
}

function formatTimestamp(iso: string): string {
    const d = new Date(iso);
    if (isNaN(d.getTime())) return iso;
    return d.toLocaleString();
}

function capitalize(s: string): string {
    if (!s) return '';
    return s.charAt(0).toUpperCase() + s.slice(1);
}

function pickBool(v: unknown): boolean | null {
    if (v === true || v === false) return v;
    if (v === 'true' || v === 'pass' || v === 'passed') return true;
    if (v === 'false' || v === 'fail' || v === 'failed') return false;
    return null;
}

function pickImageSrc(item: EvidenceItem): string | null {
    const meta = item.meta ?? {};
    if (typeof meta.dataUrl === 'string' && meta.dataUrl.length > 0) return meta.dataUrl;
    if (typeof meta.url === 'string' && meta.url.length > 0) return meta.url;
    if (typeof meta.base64 === 'string' && meta.base64.length > 0) {
        const mime = (meta.mime as string | undefined) ?? 'image/png';
        return `data:${mime};base64,${meta.base64}`;
    }
    if (item.blobSha256) {
        return `/api/evidence/blob/${encodeURIComponent(item.blobSha256)}`;
    }
    return null;
}

function pickBlobHref(item: EvidenceItem): string | null {
    if (item.blobSha256) {
        return `/api/evidence/blob/${encodeURIComponent(item.blobSha256)}`;
    }
    return null;
}

function safeJsonStringify(v: unknown): string {
    try {
        return JSON.stringify(v, null, 2);
    } catch {
        return String(v);
    }
}

export default EvidenceBundleView;
