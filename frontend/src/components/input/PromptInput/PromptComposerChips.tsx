/**
 * PromptComposerChips — 桌面输入区 mini-chip 行（权限模式 + 模型选择）
 *
 * 仅桌面分支渲染（index.tsx isMobile=false）；每个 chip 内嵌透明原生
 * select 承担交互，视觉为令牌化胶囊。
 *
 * 行为复用出处：
 * - 权限切换：与设置页共用服务端确认流程，保存确认前保留当前显示值。
 * - 模型切换：与 Header 共用会话详情/连接/绑定检查，仅修改当前会话。
 * 运行中/压缩中 chips 不禁用（与 Header 模型 select 现状一致）。
 */

import React, { useEffect, useState } from 'react';
import { SheetShell } from '@/components/apos/MobileBottomSheet';
import { PermissionMenu } from './PermissionMenu';
import { ChevronDown, Cpu } from 'lucide-react';
import { PERMISSION_MODES, type PermissionMode } from '@/types';
import { useSessionPermissionSelection } from '@/hooks/useSessionPermissionSelection';
import { useSessionStore } from '@/store/sessionStore';
import { useModelStore } from '@/store/modelStore';
import { useSessionModelSelection } from '@/hooks/useSessionModelSelection';
import { getPermissionModeLabel, getPermissionModeDescription } from '@/components/layout/StatusBar';
import { SessionStatusIcon } from '@/components/status/SessionStatusIcon';

/** 两个 chip 共用的胶囊壳（v2 令牌：hairline + surface2 + hover2 + accent2-ring） */
const CHIP_SHELL =
    'relative inline-flex h-7 items-center gap-1.5 rounded-full border border-hairline ' +
    'bg-surfacev2 px-2.5 text-[13px] text-t2 shadow-raised transition-interactive duration-fast ' +
    'hover:bg-hover2 hover:text-t1 hover:shadow-raised-hover active:shadow-pressed focus-within:ring-[3px] focus-within:ring-accent2-ring';

/** chip 内嵌的透明原生 select（承担全部交互与键盘可达性） */
const OVERLAY_SELECT =
    'absolute inset-0 w-full cursor-pointer opacity-0 disabled:cursor-not-allowed';

/** 权限模式 chip：Shield + 当前模式名 + ChevronDown */
export const PermissionModeChip: React.FC<{ mobile?: boolean }> = ({ mobile = false }) => {
    const { permissionMode, selectMode, pending, disabled, message } = useSessionPermissionSelection();
    return <div className="flex items-center gap-2">
        <fieldset disabled={disabled} className="min-w-0">
            {mobile
                ? <MobileChoice disabled={disabled} label="权限" showCurrent leading={<SessionStatusIcon />} value={permissionMode} options={PERMISSION_MODES.map(value => ({ value, label: getPermissionModeLabel(value), description: getPermissionModeDescription(value) }))} onChange={value => selectMode(value as PermissionMode)} />
                : <PermissionMenu disabled={disabled} value={permissionMode} onChange={selectMode} />}
        </fieldset>
        {(pending || message) && <span role="status" className="text-xs text-t3">{pending ? '正在切换' : message}</span>}
    </div>;
};

/** 模型 chip：accent 点 + 当前模型名 + ChevronDown；mobileRow 保留"更多"面板整行形态（能力保留），默认紧凑形态供手机导航栏使用 */
export const ModelChip: React.FC<{ mobile?: boolean; mobileRow?: boolean }> = ({ mobile = false, mobileRow = false }) => {
    const model = useSessionStore(s => s.model);
    const modelSelection = useSessionModelSelection();
    const {
        models: availableModels,
        loading: modelsLoading,
        error: modelsError,
        fetchModels,
    } = useModelStore();
    const currentLabel = availableModels.find(m => m.id === model)?.displayName
        ?? model
        ?? (modelsLoading
            ? '模型加载中…'
            : modelsError
            ? '模型列表加载失败'
            : '暂无可用模型');

    // 模型列表加载失败时的重试入口（原 Header 重试钮迁入输入区；
    // Header effect 不会因失败自动重跑，已有会话此前无恢复路径）
    const retryable = Boolean(modelsError) && availableModels.length === 0 && !modelsLoading;
    if (retryable) {
        return (
            <button
                type="button"
                onClick={() => void fetchModels()}
                className={mobile
                    ? 'flex min-h-11 w-full items-center gap-3 rounded-[14px] border border-hairline bg-surface2 px-3 py-3 text-left text-sm text-t2 hover:bg-hover2 focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent2-ink'
                    : CHIP_SHELL}
                aria-label="重新加载模型列表"
                title="模型列表加载失败，点击重试"
            >
                {mobile ? <Cpu size={20} className="shrink-0" aria-hidden="true" />
                    : <span className="h-1.5 w-1.5 shrink-0 rounded-full" style={{ backgroundColor: 'var(--v2-err)' }} aria-hidden="true" />}
                {mobile ? <span><span className="block text-[13px]">模型列表加载失败</span><span className="mt-0.5 block font-medium text-t1">重新加载</span></span>
                    : '重新加载'}
            </button>
        );
    }

    // 手机导航栏 4 项共存：模型名限宽截断，为密度/"更多"留足展示空间（完整名仍在 aria-label/选择面板中）
    if (mobile) return <MobileChoice label="模型" row={mobileRow} showCurrent={!mobileRow} maxTextWidth="max-w-[104px]" value={model ?? ''} disabled={modelSelection.disabled} options={availableModels.map(m => ({ value: m.id, label: m.displayName }))} onChange={modelSelection.selectModel} title={modelSelection.disabledReason} />;

    return (
        <span className={`${CHIP_SHELL} ${modelSelection.disabled ? 'opacity-50' : ''}`} title={modelSelection.disabledReason}>
            <span className="h-1.5 w-1.5 shrink-0 rounded-full bg-accent2" aria-hidden="true" />
            <span className="max-w-[140px] truncate">{currentLabel}</span>
            <ChevronDown size={12} aria-hidden="true" />
            <select
                aria-label="模型选择"
                value={model || ''}
                onChange={(e) => modelSelection.selectModel(e.target.value)}
                title={modelSelection.disabledReason}
                disabled={modelSelection.disabled}
                className={OVERLAY_SELECT}
            >
                {availableModels.length === 0 && (
                    <option value="">
                        {modelsLoading ? '模型加载中…'
                            : modelsError ? '模型列表加载失败' : '暂无可用模型'}
                    </option>
                )}
                {availableModels.map(m => (
                    <option key={m.id} value={m.id}>{m.displayName}</option>
                ))}
            </select>
        </span>
    );
};

export function MobileChoice({ label, value, options, onChange, title, disabled = false, showCurrent = false, row = false, leading, maxTextWidth }: { label: string; value: string; options: { value: string; label: string; description?: string }[]; onChange: (value: string) => void; title?: string; disabled?: boolean; showCurrent?: boolean; row?: boolean; leading?: React.ReactNode; maxTextWidth?: string }) {
    const [open, setOpen] = useState(false);
    useEffect(() => { if (disabled) setOpen(false); }, [disabled]);
    const currentLabel = options.find(option => option.value === value)?.label ?? value;
    const content = row
        ? <span className="flex w-full items-center gap-3 rounded-[14px] border border-hairline bg-surface2 px-3 py-3 text-left"><Cpu size={20} className="shrink-0 text-t2" /><span className="min-w-0 flex-1"><span className="block text-[13px] text-t2">模型</span><span className="mt-0.5 block truncate text-sm font-medium text-t1">{options.find(option => option.value === value)?.label ?? (disabled ? '模型暂不可用' : '选择模型')}</span></span><ChevronDown size={16} className="shrink-0" /></span>
        : showCurrent ? <MobileSelectionLabel label={label} value={currentLabel} maxTextWidth={maxTextWidth} /> : <>{label}⌄</>;
    return <><button type="button" aria-label={showCurrent ? `${label}：${currentLabel}，点击切换` : undefined} className="min-h-11 min-w-0 shrink-0 rounded-[10px] px-1.5 text-sm text-t2 transition-interactive duration-fast hover:bg-hover2 active:bg-hover2 disabled:opacity-50 focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent2-ink" aria-haspopup="dialog" aria-expanded={open} disabled={disabled} title={title} onClick={() => setOpen(true)}>{leading ? <span className="inline-flex items-center gap-1.5">{leading}{content}</span> : content}</button>
        <SheetShell isOpen={open && !disabled} onClose={() => setOpen(false)} ariaLabel={`选择${label}`} header={<div className="flex items-center justify-between px-4"><h2 className="text-xl font-semibold">选择{label}</h2><button className="min-h-11 px-3" onClick={() => setOpen(false)}>完成</button></div>}>
            <div className="p-4 space-y-1">{options.map(option => <button key={option.value} disabled={disabled} className="flex min-h-11 w-full items-center justify-between rounded-[10px] px-3 py-2 text-left text-sm text-t1 hover:bg-hover2" aria-pressed={option.value === value} onClick={() => { onChange(option.value); setOpen(false); }}><span><span className="block">{option.label}</span>{option.description && <span className="block mt-1 text-[13px] text-t2">{option.description}</span>}</span>{option.value === value && <span aria-hidden="true">✓</span>}</button>)}</div>
        </SheetShell></>;
}

export function MobileSelectionLabel({ label, value, maxTextWidth }: { label: string; value: string; maxTextWidth?: string }) {
    // 密度只显示当前档名（简洁/平衡/详细），省横向空间留给模型名
    const text = label === '工作台' ? `${value}工作台`
        : value === '完全访问' ? '完全访问权限' : value;
    return <span className="inline-flex items-center justify-center gap-1 whitespace-nowrap text-sm font-medium text-t1">
        <span className={maxTextWidth ? `truncate ${maxTextWidth}` : undefined}>{text}</span><ChevronDown size={12} className="shrink-0 text-t3" aria-hidden="true" />
    </span>;
}
