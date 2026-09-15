import React from 'react';
import { AlertTriangle, Download, File } from 'lucide-react';
import type { ExternalResourceResult } from '@/types';

interface ExternalResourceRendererProps {
    resource: ExternalResourceResult;
}

function formatBytes(bytes: number): string {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

export const ExternalResourceRenderer: React.FC<ExternalResourceRendererProps> = ({ resource }) => (
    <div
        className="rounded-[14px] border border-emerald-700/50 bg-emerald-950/20 p-3"
        data-testid="external-resource-card"
    >
        <div className="flex items-start gap-3">
            <div className="mt-0.5 rounded-md bg-emerald-900/40 p-2 text-ok">
                <File size={18} />
            </div>
            <div className="min-w-0 flex-1">
                <div className="font-medium text-[var(--v2-text-1)] break-all">
                    {resource.label}
                </div>
                <div className="mt-1 text-[13px] text-[var(--v2-text-2)]">
                    {formatBytes(resource.size)} · {resource.mimeType} · {resource.provider.toUpperCase()}
                </div>
                {resource.permanentlyPublic && (
                    <div className="mt-2 flex items-start gap-1.5 text-[13px] text-warn">
                        <AlertTriangle size={13} className="mt-0.5 shrink-0" />
                        <span>永久公开链接，任何获得地址的人都可以下载。</span>
                    </div>
                )}
                {resource.downloadExpected && resource.mimeType.startsWith('text/html') && (
                    <div className="mt-1 text-[13px] text-[var(--v2-text-2)]">
                        HTML 将作为附件下载，而不是在 OSS 默认域名中在线预览。
                    </div>
                )}
            </div>
            <a
                href={resource.url}
                target="_blank"
                rel="noopener noreferrer"
                referrerPolicy="no-referrer"
                className="inline-flex shrink-0 items-center gap-1.5 rounded-md bg-emerald-600 px-3 py-2 text-[13px] font-medium text-white hover:bg-emerald-500"
                aria-label={`下载 ${resource.label}`}
                data-testid="external-resource-download"
            >
                <Download size={14} />
                下载
            </a>
        </div>
    </div>
);

export default React.memo(ExternalResourceRenderer);
