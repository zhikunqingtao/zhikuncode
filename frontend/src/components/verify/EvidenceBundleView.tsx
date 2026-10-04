/**
 * EvidenceBundleView — RV-4 证据包详情展示
 *
 * 设计风格对齐 JourneyVerifyPanel：
 * - 紧凑面板：border rounded-[10px] p-4
 * - 状态色：verified=green / failed=red / inconclusive=amber / running=blue
 * - 按 EvidenceItem.type 分组展示，提供 tabs 切换
 *
 * 每个详情实例独立管理请求与展示状态；按 ID 复用最近一个成功加载的证据包。
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

export const EvidenceBundleView: React.FC<EvidenceBundleViewProps> = ({ bundleId }) =>
    bundleId ? <EvidenceBundleLoader key={bundleId} bundleId={bundleId} /> : null;

type EvidenceLoadState =
    | { status: 'loading' }
    | { status: 'success'; bundle: EvidenceBundle }
    | { status: 'error'; message: string };

const EvidenceBundleLoader: React.FC<EvidenceBundleViewProps> = ({ bundleId }) => {
    // Freeze one cache snapshot for this mount; shared cache updates must not affect this viewer.
    const [cachedBundle] = useState(() => {
        const cached = useEvidenceStore.getState().currentBundle;
        return cached?.bundleId === bundleId ? cached : null;
    });
    const [state, setState] = useState<EvidenceLoadState>(() => cachedBundle
        ? { status: 'success', bundle: cachedBundle }
        : { status: 'loading' });

    useEffect(() => {
        if (cachedBundle) return;
        const controller = new AbortController();
        let active = true;
        void (async () => {
            try {
                const response = await fetch(`/api/evidence/${encodeURIComponent(bundleId)}`, {
                    signal: controller.signal,
                });
                if (!active) return;
                if (!response.ok) throw new Error(`HTTP ${response.status}`);
                const data: unknown = await response.json();
                if (!active) return;
                if (data === null || typeof data !== 'object'
                    || !('bundleId' in data) || data.bundleId !== bundleId) {
                    throw new Error('Evidence bundle identity mismatch');
                }
                setState({ status: 'success', bundle: data as EvidenceBundle });
                useEvidenceStore.setState({ currentBundle: data as EvidenceBundle });
            } catch (error) {
                if (active) {
                    setState({ status: 'error', message: error instanceof Error
                        ? error.message : 'Failed to load evidence bundle' });
                }
            }
        })();
        return () => {
            active = false;
            controller.abort();
        };
    }, [bundleId, cachedBundle]);

    const currentBundle = state.status === 'success' ? state.bundle : null;
    const grouped = useMemo(() => groupByType(currentBundle?.items ?? []), [currentBundle]);
    const groupKeys = useMemo(() => Object.keys(grouped), [grouped]);
    const [selectedTab, setSelectedTab] = useState<string | null>(null);
    const activeTab = selectedTab !== null && groupKeys.includes(selectedTab)
        ? selectedTab : groupKeys[0] ?? null;

    if (state.status === 'loading') {
        return (
            <div className="evidence-bundle-view border rounded-[14px] p-4 mt-2 text-[13px] text-t2">
                Loading evidence bundle…
            </div>
        );
    }

    if (state.status === 'error') {
        return (
            <div className="evidence-bundle-view border rounded-[14px] p-4 mt-2 text-[13px] text-err bg-errsoft">
                Failed to load evidence bundle: {state.message}
            </div>
        );
    }

    if (!currentBundle) return null;

    const activeItems = activeTab ? grouped[activeTab] ?? [] : [];

    return (
        <div className="evidence-bundle-view border rounded-[14px] p-4 mt-2">
            <Header bundle={currentBundle} />

            {groupKeys.length === 0 ? (
                <div className="mt-3 text-[13px] text-t2">No evidence items.</div>
            ) : (
                <>
                    <div className="flex flex-wrap gap-1 mt-3 border-b pb-2">
                        {groupKeys.map((key) => (
                            <button
                                key={key}
                                type="button"
                                onClick={() => setSelectedTab(key)}
                                className={
                                    'px-2 py-0.5 text-[13px] rounded transition-colors ' +
                                    (activeTab === key
                                        ? 'bg-accent2-soft text-accent2-ink font-medium'
                                        : 'text-t2 hover:bg-surface2')
                                }
                            >
                                {(ITEM_TYPE_LABELS[key] ?? capitalize(key))}
                                <span className="ml-1 text-t2">
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
    <div>
        <div className="flex items-center justify-between gap-2">
            <div className="min-w-0">
                <div className="flex items-center gap-2">
                    <h3 className="truncate text-base font-semibold">
                        {bundle.claim || `Evidence Bundle ${shortId(bundle.bundleId)}`}
                    </h3>
                    <span className="px-1.5 py-0.5 text-[13px] rounded bg-surface2 text-t2 uppercase">
                        {bundle.kind}
                    </span>
                </div>
                <div className="text-[13px] text-t2 mt-0.5 font-mono truncate">
                    {shortId(bundle.bundleId)} · {formatTimestamp(bundle.createdAt)}
                </div>
            </div>
            <VerdictBadge verdict={bundle.verdict} />
        </div>
        {scopeNote(bundle) && (
            <p className="mt-2 text-[13px] text-t2">{scopeNote(bundle)}</p>
        )}
    </div>
);

/** 有限范围口径：正向结论只限定在可识别的检查范围；未知值不崩溃、不显示 undefined。 */
function scopeNote(bundle: EvidenceBundle): string | null {
    const v = (bundle.verdict || '').toLowerCase();
    if (v === 'verified' || v === 'passed') {
        return bundle.kind === 'journey' && bundle.items?.length > 0
            ? '范围有限：仅表示所列步骤在该次执行中通过'
            : '该记录标记为通过；检查覆盖范围未知';
    }
    if (v === 'unavailable') return '该次检查未执行，不能据此判定通过';
    if (v === 'inconclusive') return '结论不确定，不能据此判定通过';
    if (v === 'failed') return null;
    return '范围未知，不能据此判定通过';
}

const VerdictBadge: React.FC<{ verdict: string }> = ({ verdict }) => {
    const v = (verdict || '').toLowerCase();
    if (v === 'verified' || v === 'passed') {
        return <span className="px-2 py-0.5 text-[13px] rounded bg-oksoft text-ok">Verified</span>;
    }
    if (v === 'failed') {
        return <span className="px-2 py-0.5 text-[13px] rounded bg-errsoft text-err">Failed</span>;
    }
    if (v === 'inconclusive') {
        return <span className="px-2 py-0.5 text-[13px] rounded bg-warnsoft text-warn">Inconclusive</span>;
    }
    if (v === 'unavailable') {
        return <span className="px-2 py-0.5 text-[13px] rounded bg-warnsoft text-warn">Unavailable</span>;
    }
    return <span className="px-2 py-0.5 text-[13px] rounded bg-accent2-soft text-accent2-ink">Unknown</span>;
};

// ==================== Item Group Renderer ====================

const ItemGroupRenderer: React.FC<{ type: string; items: EvidenceItem[] }> = ({ type, items }) => {
    if (items.length === 0) {
        return <div className="text-[13px] text-t2">No items.</div>;
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
    <div className="grid grid-cols-2 md:grid-cols-3 gap-2">
        {items.map((item) => <ScreenshotCard key={item.id} item={item} />)}
    </div>
);

const ScreenshotCard: React.FC<{ item: EvidenceItem }> = ({ item }) => {
    const src = pickImageSrc(item);
    const [failedSrc, setFailedSrc] = useState<string | null>(null);
    const unreadable = src !== null && src === failedSrc;
    return (
        <div className="border rounded overflow-hidden bg-surface2">
            {src && !unreadable ? (
                <img
                    src={src}
                    alt={item.summary ?? 'screenshot'}
                    className="w-full h-24 object-cover"
                    loading="lazy"
                    onError={() => setFailedSrc(src)}
                />
            ) : (
                <div className="w-full min-h-24 p-2 flex items-center justify-center text-[13px] text-t2">
                    {unreadable ? '截图不可读取：文件缺失、损坏或加载失败' : '无截图预览'}
                </div>
            )}
            <div className="px-1.5 py-1 text-[13px] text-t2 break-words">
                {item.summary ?? shortId(item.id)}
            </div>
            <StepEvidenceDetails item={item} missingScreenshot={!src} unreadableScreenshot={unreadable} />
        </div>
    );
};

/** Journey facts remain separate from the verdict, including when no image was saved. */
const StepEvidenceDetails: React.FC<{
    item: EvidenceItem;
    missingScreenshot?: boolean;
    unreadableScreenshot?: boolean;
}> = ({ item, missingScreenshot = false, unreadableScreenshot = false }) => {
    const meta = item.meta ?? {};
    const method = nonEmptyString(meta.method);
    const warning = nonEmptyString(meta.warning);
    const isInteraction = meta.action === 'click' || meta.action === 'type';
    const status = nonEmptyString(meta.screenshotStatus);
    const reason = nonEmptyString(meta.screenshotReason);
    const missing = missingScreenshot || (status !== null && status !== 'stored') || reason !== null;
    const mime = nonEmptyString(meta.mime);
    const bytes = typeof meta.bytes === 'number' && Number.isFinite(meta.bytes) && meta.bytes >= 0 ? meta.bytes : null;
    const screenshotDetails = [mime, bytes !== null ? `${bytes} 字节` : null].filter(Boolean).join('，');
    const gapLabel = status === 'invalid' ? '截图无效'
        : status === 'limit_exceeded' ? '截图未保存（超出限额）' : '截图缺失';
    if (!method && !warning && !isInteraction && !missing && !status) return null;

    return (
        <div className="px-2 py-1 space-y-1 text-[13px] text-t2 break-words">
            {(method || isInteraction) && <p>{method ? `执行方式：${method}` : '执行方式未记录'}</p>}
            {warning && <p className="text-warn">{warning}</p>}
            {method === 'js_fallback' && (
                <p className="text-warn">此次操作使用 JS fallback，不能单独证明原生用户交互可用。</p>
            )}
            {missing ? (
                <p className="text-warn">{gapLabel}：{screenshotReasonLabel(reason)}</p>
            ) : status === 'stored' && (
                <p>{unreadableScreenshot ? '归档记录存在，当前无法读取' : '截图已归档'}{screenshotDetails ? `（${screenshotDetails}）` : ''}</p>
            )}
        </div>
    );
};

// ---- command ----
const CommandList: React.FC<{ items: EvidenceItem[] }> = ({ items }) => (
    <div className="space-y-2">
        {items.map((item) => {
            const exitCode = typeof item.meta?.exitCode === 'number' ? item.meta.exitCode : null;
            const cmd = (item.meta?.command as string | undefined) ?? item.summary ?? '';
            const stdout = (item.meta?.stdout as string | undefined) ?? '';
            return (
                <div key={item.id} className="border rounded">
                    <div className="flex items-center justify-between px-2 py-1 bg-surface2 border-b text-[13px]">
                        <span className="font-mono truncate">{cmd || shortId(item.id)}</span>
                        {exitCode !== null && (
                            <span
                                className={
                                    'ml-2 px-1.5 py-0.5 text-[13px] rounded ' +
                                    (exitCode === 0
                                        ? 'bg-oksoft text-ok'
                                        : 'bg-errsoft text-err')
                                }
                            >
                                exit {exitCode}
                            </span>
                        )}
                    </div>
                    {stdout && (
                        <pre className="p-2 text-[13px] font-mono bg-surface2 max-h-32 overflow-auto whitespace-pre-wrap">
                            {stdout}
                        </pre>
                    )}
                    <StepEvidenceDetails item={item} />
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
                        'flex items-start gap-2 px-2 py-1 rounded text-[13px] ' +
                        (isError ? 'bg-errsoft text-err' : 'bg-warnsoft text-warn')
                    }
                >
                    <span className="font-mono uppercase text-[13px] shrink-0 mt-0.5">{level}</span>
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
                <div key={item.id} className="flex items-center gap-2 text-[13px]">
                    <span
                        className={ok ? 'text-ok' : fail ? 'text-err' : 'text-t2'}
                    >
                        {ok ? '✓' : fail ? '✗' : '·'}
                    </span>
                    <span className="font-mono truncate">
                        {item.summary ?? (item.meta?.name as string | undefined) ?? shortId(item.id)}
                    </span>
                    {typeof item.meta?.durationMs === 'number' && (
                        <span className="text-t2 ml-auto">{item.meta.durationMs}ms</span>
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
                <div key={item.id} className="border rounded p-2 text-[13px]">
                    <div className="text-t2 mb-1 truncate">
                        {item.summary ?? shortId(item.id)}
                    </div>
                    {url ? (
                        <video controls src={url} className="w-full max-h-48 bg-black rounded" />
                    ) : (
                        <span className="text-t2">no source</span>
                    )}
                </div>
            );
        })}
    </div>
);

// ---- har ----
const HarTable: React.FC<{ items: EvidenceItem[] }> = ({ items }) => (
    <div className="overflow-x-auto">
        <table className="min-w-full text-[13px]">
            <thead>
                <tr className="text-t2 border-b">
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
                                        ? 'text-t2'
                                        : ok
                                            ? 'text-ok'
                                            : 'text-err')
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
                        <div className="px-2 py-1 bg-surface2 border-b text-[13px] font-mono truncate">
                            {String(item.meta.path)}
                        </div>
                    ) : null}
                    <pre className="p-2 text-[13px] font-mono bg-surface2 max-h-48 overflow-auto whitespace-pre">
                        {renderDiffWithColor(patch)}
                    </pre>
                </div>
            );
        })}
    </div>
);

const renderDiffWithColor = (patch: string): React.ReactNode =>
    patch.split('\n').map((line, idx) => {
        let cls = 'text-t1';
        if (line.startsWith('+') && !line.startsWith('+++')) cls = 'text-ok';
        else if (line.startsWith('-') && !line.startsWith('---')) cls = 'text-err';
        else if (line.startsWith('@@')) cls = 'text-accent2-ink';
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
            <div key={item.id} className="text-[13px] border rounded px-2 py-1">
                <div className="font-mono text-t1 truncate">
                    {item.summary ?? shortId(item.id)}
                </div>
                {Object.keys(item.meta ?? {}).length > 0 && (
                    <pre className="mt-1 text-[13px] text-t2 font-mono whitespace-pre-wrap break-all">
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

function nonEmptyString(value: unknown): string | null {
    return typeof value === 'string' && value.trim().length > 0 ? value : null;
}

function screenshotReasonLabel(reason: string | null): string {
    switch (reason) {
        case null:
        case 'reason_not_recorded': return '原因未记录';
        case 'invalid_base64': return '截图编码无效';
        case 'single_image_limit_5_mib': return '单张截图超过 5 MiB 限额';
        case 'journey_image_limit_20_mib': return '本次 Journey 截图归档剩余额度不足（总限额 20 MiB）';
        case 'invalid_or_unsupported_image': return '截图无效或不是支持的 JPEG/PNG 图片';
        default: return reason;
    }
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
        return `/api/evidence/blob/${encodeURIComponent(item.blobSha256)}?preview=true`;
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
