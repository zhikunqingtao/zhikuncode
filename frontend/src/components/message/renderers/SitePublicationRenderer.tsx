import React, { useState } from 'react';
import { Copy, ExternalLink, Globe } from 'lucide-react';
import type { SitePublicationResult } from '@/types';

const states: Record<SitePublicationResult['state'], string> = {
    public_verified: '已发布 · 匿名公网访问已验证',
    deployed_unverified: '已部署 · 公网访问待验证',
    failed: '发布失败',
    unknown: '远端结果待确认',
};

export const SitePublicationRenderer: React.FC<{ publication: SitePublicationResult }> = ({ publication: p }) => {
    const [copyState, setCopyState] = useState('复制链接');
    const copy = async () => {
        if (!p.url) return;
        try {
            await navigator.clipboard.writeText(p.url);
            setCopyState('已复制');
        } catch {
            setCopyState('复制失败，请复制下方链接');
        }
    };
    return (
        <div className="rounded-[14px] border border-ok bg-oksoft p-3" data-testid="site-publication-card">
            <div className="flex items-center gap-2 font-medium"><Globe size={18} />{p.label}</div>
            <div className="mt-1 text-[13px] text-[var(--v2-text-2)]">
                秒悟 · {p.runtime === 'static' ? '静态网站' : '全栈应用'}{p.version ? ` · v${p.version}` : ''}
            </div>
            <p className="mt-2 text-sm" role="status">{states[p.state]}</p>
            {p.state === 'public_verified' && <p className="mt-1 text-[13px]">任何获得链接的人均可访问。本次为独立新站点，旧站点不会被覆盖。</p>}
            {p.state === 'deployed_unverified' && <p className="mt-1 text-[13px]">请检查项目访问权限；尚不能确认所有人均可访问。</p>}
            {p.state === 'unknown' && <p className="mt-1 text-[13px]">云端可能仍在执行。请检查项目状态，不会自动重发或删除项目。</p>}
            {p.errorCode && <p className="mt-1 text-[13px] text-warn">{p.errorCode}</p>}
            {p.guidance && p.state !== 'public_verified' && <p className="mt-1 text-[13px]">{p.guidance}</p>}
            {p.url && <a className="mt-2 block break-all text-[13px] underline" href={p.url} target="_blank" rel="noopener noreferrer" referrerPolicy="no-referrer">{p.url}</a>}
            <div className="mt-3 flex flex-wrap items-center gap-3 text-sm">
                {p.url && <>
                    <a className="inline-flex items-center gap-1" href={p.url} target="_blank" rel="noopener noreferrer" referrerPolicy="no-referrer"><ExternalLink size={14} />打开网站</a>
                    <button type="button" className="inline-flex items-center gap-1" onClick={copy}><Copy size={14} />{copyState}</button>
                </>}
                {p.projectUrl && <a href={p.projectUrl} target="_blank" rel="noopener noreferrer" referrerPolicy="no-referrer">秒悟项目设置</a>}
            </div>
        </div>
    );
};
export default SitePublicationRenderer;
