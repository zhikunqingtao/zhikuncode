import React from 'react';
import { AlertTriangle, Download, ExternalLink, File } from 'lucide-react';
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
        className="rounded-[14px] border border-ok bg-oksoft p-3"
        data-testid="external-resource-card"
    >
        <div className="flex items-start gap-3">
            <div className="mt-0.5 rounded-md bg-oksoft p-2 text-ok">
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
                        <span>永久公开链接，任何获得地址的人都可以访问。</span>
                    </div>
                )}
                {resource.downloadExpected && resource.mimeType.split(';', 1)[0].trim().toLowerCase() === 'text/html' && (
                    <div className="mt-1 text-[13px] text-[var(--v2-text-2)]">
                        是否预览或下载，以浏览器实际响应为准。
                    </div>
                )}
            </div>
            <a
                href={resource.url}
                target="_blank"
                rel="noopener noreferrer"
                referrerPolicy="no-referrer"
                className="inline-flex shrink-0 items-center gap-1.5 rounded-md bg-ok px-3 py-2 text-[13px] font-medium text-white dark:text-app2 hover:bg-ok"
                aria-label={`${resource.downloadExpected ? '下载' : '打开预览'} ${resource.label}`}
                data-testid="external-resource-download"
            >
                {resource.downloadExpected ? <Download size={14} /> : <ExternalLink size={14} />}
                {resource.downloadExpected ? '下载' : '打开预览'}
            </a>
        </div>
    </div>
);

export default React.memo(ExternalResourceRenderer);
