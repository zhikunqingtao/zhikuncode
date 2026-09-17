/**
 * PromptComposerChips — 桌面输入区 mini-chip 行（权限模式 + 模型选择）
 *
 * 仅桌面分支渲染（index.tsx isMobile=false）；每个 chip 内嵌透明原生
 * select 承担交互，视觉为令牌化胶囊。
 *
 * 行为复用出处：
 * - 权限/模型切换：乐观更新后校验发送结果——无绑定会话或 WS 发送失败/抛错时
 *   回滚本地 store 并经 notificationStore 推送错误通知，失败处理与文案风格
 *   对齐 components/dialog/SettingsPanel.tsx handlePermissionModeChange，
 *   避免 chip 显示与服务端实际状态发散（后端枚举大写）；
 * - 模型切换：与 Header 共用会话详情/连接/绑定检查，仅修改当前会话。
 * 运行中/压缩中 chips 不禁用（与 Header 模型 select 现状一致）。
 */

import React, { useEffect, useState } from 'react';
import { SheetShell } from '@/components/apos/MobileBottomSheet';
import { PermissionMenu } from './PermissionMenu';
import { ChevronDown, Cpu } from 'lucide-react';
import { PERMISSION_MODES, type PermissionMode } from '@/types';
import { usePermissionStore } from '@/store/permissionStore';
import { useSessionStore } from '@/store/sessionStore';
import { useModelStore } from '@/store/modelStore';
import { useSessionModelSelection } from '@/hooks/useSessionModelSelection';
import { useNotificationStore } from '@/store/notificationStore';
import { sendSetPermissionMode } from '@/api/stompClient';
import { isSessionBound } from '@/api/dispatch';
import { getPermissionModeLabel, getPermissionModeDescription } from '@/components/layout/StatusBar';

/** 两个 chip 共用的胶囊壳（v2 令牌：hairline + surface2 + hover2 + accent2-ring） */
const CHIP_SHELL =
    'relative inline-flex h-7 items-center gap-1.5 rounded-full border border-hairline ' +
    'bg-surface2 px-2.5 text-[13px] text-t2 transition-interactive duration-fast ' +
    'hover:bg-hover2 hover:text-t1 focus-within:ring-[3px] focus-within:ring-accent2-ring';

/** chip 内嵌的透明原生 select（承担全部交互与键盘可达性） */
const OVERLAY_SELECT =
    'absolute inset-0 w-full cursor-pointer opacity-0 disabled:cursor-not-allowed';

/** 权限模式 chip：Shield + 当前模式名 + ChevronDown */
export const PermissionModeChip: React.FC<{ mobile?: boolean }> = ({ mobile = false }) => {
    const { permissionMode, setPermissionMode } = usePermissionStore();
    const sessionId = useSessionStore(s => s.sessionId);
    const addNotification = useNotificationStore(s => s.addNotification);
    const hasBoundSession = Boolean(sessionId && isSessionBound(sessionId));

    // 乐观更新 + 失败回滚（对齐 SettingsPanel.tsx handlePermissionModeChange，见文件头注释）
    const handleChange = (mode: PermissionMode) => {
        const prevMode = permissionMode;
        setPermissionMode(mode);
        if (!hasBoundSession) {
            setPermissionMode(prevMode);
            addNotification({
                key: 'permission-mode-no-session',
                level: 'error',
                message: '请先创建或选择会话，再切换权限模式',
            });
            return;
        }
        let sent = false;
        try {
            // 同步到后端，后端枚举使用大写值
            sent = sendSetPermissionMode(mode.toUpperCase());
        } catch {
            sent = false;
        }
        if (!sent) {
            setPermissionMode(prevMode);
            addNotification({
                key: 'permission-mode-send-failed',
                level: 'error',
                message: '权限模式切换发送失败，请检查连接后重试',
            });
        }
    };

    if (mobile) return <MobileChoice label="权限" showCurrent value={permissionMode} options={PERMISSION_MODES.map(value => ({ value, label: getPermissionModeLabel(value), description: getPermissionModeDescription(value) }))} onChange={value => handleChange(value as PermissionMode)} />;

    return <PermissionMenu value={permissionMode} onChange={handleChange} />;
};

/** 模型 chip：accent 点 + 当前模型名 + ChevronDown */
export const ModelChip: React.FC<{ mobile?: boolean }> = ({ mobile = false }) => {
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

    if (mobile) return <MobileChoice label="模型" row value={model ?? ''} disabled={modelSelection.disabled} options={availableModels.map(m => ({ value: m.id, label: m.displayName }))} onChange={modelSelection.selectModel} title={modelSelection.disabledReason} />;

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

function MobileChoice({ label, value, options, onChange, title, disabled = false, showCurrent = false, row = false }: { label: string; value: string; options: { value: string; label: string; description?: string }[]; onChange: (value: string) => void; title?: string; disabled?: boolean; showCurrent?: boolean; row?: boolean }) {
    const [open, setOpen] = useState(false);
    useEffect(() => { if (disabled) setOpen(false); }, [disabled]);
    return <><button type="button" aria-label={showCurrent ? `${label}：${options.find(option => option.value === value)?.label ?? value}，点击切换` : undefined} className="min-h-11 min-w-0 shrink-0 rounded-[10px] text-sm text-t2 hover:bg-hover2 disabled:opacity-50 focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent2-ink" aria-haspopup="dialog" aria-expanded={open} disabled={disabled} title={title} onClick={() => setOpen(true)}>{row ? <span className="flex w-full items-center gap-3 rounded-[14px] border border-hairline bg-surface2 px-3 py-3 text-left"><Cpu size={20} className="shrink-0 text-t2" /><span className="min-w-0 flex-1"><span className="block text-[13px] text-t2">模型</span><span className="mt-0.5 block truncate text-sm font-medium text-t1">{options.find(option => option.value === value)?.label ?? (disabled ? '模型暂不可用' : '选择模型')}</span></span><ChevronDown size={16} className="shrink-0" /></span> : showCurrent ? <MobileSelectionLabel label={label} value={options.find(option => option.value === value)?.label ?? value} /> : <>{label}⌄</>}</button>
        <SheetShell isOpen={open && !disabled} onClose={() => setOpen(false)} ariaLabel={`选择${label}`} header={<div className="flex items-center justify-between px-4"><h2 className="text-xl font-semibold">选择{label}</h2><button className="min-h-11 px-3" onClick={() => setOpen(false)}>完成</button></div>}>
            <div className="p-4 space-y-1">{options.map(option => <button key={option.value} disabled={disabled} className="flex min-h-11 w-full items-center justify-between rounded-[10px] px-3 py-2 text-left text-sm text-t1 hover:bg-hover2" aria-pressed={option.value === value} onClick={() => { onChange(option.value); setOpen(false); }}><span><span className="block">{option.label}</span>{option.description && <span className="block mt-1 text-[13px] text-t2">{option.description}</span>}</span>{option.value === value && <span aria-hidden="true">✓</span>}</button>)}</div>
        </SheetShell></>;
}

export function MobileSelectionLabel({ label, value }: { label: string; value: string }) {
    const text = label === '工作台' ? `${value}工作台`
        : label === '密度' ? `${value}消息密度`
        : value === '完全访问' ? '完全访问权限' : value;
    return <span className="inline-flex items-center justify-center gap-1 whitespace-nowrap text-sm font-medium text-t1">
        <span>{text}</span><ChevronDown size={14} className="shrink-0 text-t2" aria-hidden="true" />
    </span>;
}
