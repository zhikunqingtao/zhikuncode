import { isTopModal, useModalBehavior } from '@/hooks/useModalBehavior';
/**
 * PermissionDialog — 权限确认对话框
 *
 * SPEC: §8.2.6a.9 PermissionDialog 完整 UI
 * 三级风险展示:
 * - Low:    蓝色信息图标，默认"允许"
 * - Medium: 橙色提示图标，无默认选择
 * - High:   红色警告图标，默认"拒绝"
 *
 * 键盘快捷键: Y=Allow, N=Deny, Escape=Deny
 * "Remember" 选项只展示服务端允许的范围。
 */

import React, { useState, useCallback, useEffect, useRef, useMemo } from 'react';
import { ShieldAlert, Info, X } from 'lucide-react';
import type { PermissionRequest, PermissionDecision, PermissionRememberScope } from '@/types';
import { CodeBlock } from '@/components/message';

interface PermissionDialogProps {
    request: PermissionRequest;
    onDecision: (decision: PermissionDecision) => Promise<void>;
}

const RISK_CONFIG = {
    low: {
        bg: 'bg-accent2-soft',
        border: 'border-accent2',
        badge: 'bg-accent2-soft text-accent2-ink dark:text-accent2-ink',
        iconClass: 'text-accent2-ink dark:text-accent2-ink',
        icon: Info,
        label: 'Low Risk',
        btnClass: 'bg-accent2-strong hover:bg-accent2-hover',
    },
    medium: {
        bg: 'bg-warnsoft',
        border: 'border-warn',
        badge: 'bg-warnsoft text-warnstrong dark:text-warn',
        iconClass: 'text-warnstrong dark:text-warn',
        icon: ShieldAlert,
        label: 'Medium Risk',
        btnClass: 'bg-warn hover:opacity-90',
    },
    high: {
        bg: 'bg-errsoft',
        border: 'border-err',
        badge: 'bg-errsoft text-errstrong dark:text-err',
        iconClass: 'text-errstrong dark:text-err',
        icon: ShieldAlert,
        label: 'High Risk',
        btnClass: 'bg-err hover:opacity-90',
    },
} as const;

const DEFAULT_TIMEOUT_SECONDS = 300;

const PermissionDialog: React.FC<PermissionDialogProps> = ({ request, onDecision }) => {
    const [remember, setRemember] = useState(false);
    const scopeOptions: ReadonlyArray<PermissionRememberScope> =
        request.scopeOptions ?? [];
    const canRemember = scopeOptions.length > 0;
    const [scope, setScope] = useState<PermissionRememberScope>(
        scopeOptions.includes('session') ? 'session' : (scopeOptions[0] ?? 'session'));
    const initialRemaining = () => request.decisionDeadlineAt
        ? Math.max(0, Math.ceil((request.decisionDeadlineAt - Date.now()) / 1000))
        : null;
    const [remainingSeconds, setRemainingSeconds] = useState<number | null>(initialRemaining);
    const [submission, setSubmission] = useState<'idle' | 'submitting' | 'succeeded'>('idle');
    const [submissionError, setSubmissionError] = useState<string | null>(null);
    const decided = submission !== 'idle';
    const dialogRef = useRef<HTMLDivElement>(null);
    useModalBehavior(true, dialogRef, () => {}, false);
    const riskLevel = (request.riskLevel || 'medium').toLowerCase() as keyof typeof RISK_CONFIG;
    const risk = RISK_CONFIG[riskLevel] ?? RISK_CONFIG.medium;
    const RiskIcon = risk.icon;

    // Focus trap
    useEffect(() => { dialogRef.current?.focus(); }, []);

    // 将工具输入格式化为便于用户核对的展示文本。
    const formattedInput = useMemo(() => {
        if (request.toolName === 'BashTool' || request.toolName === 'Bash') {
            return (request.input.command as string) ?? JSON.stringify(request.input, null, 2);
        }
        if (request.toolName === 'FileEditTool' || request.toolName === 'FileWriteTool') {
            return `File: ${request.input.file_path ?? request.input.filePath ?? 'unknown'}`;
        }
        return JSON.stringify(request.input, null, 2);
    }, [request]);

    const inputLang = useMemo(() => {
        if (request.toolName === 'BashTool' || request.toolName === 'Bash') return 'bash';
        return 'json';
    }, [request.toolName]);

    const submit = useCallback(async (decision: PermissionDecision) => {
        setSubmission('submitting');
        setSubmissionError(null);
        try {
            await onDecision(decision);
            setSubmission('succeeded');
        } catch (error) {
            setSubmission('idle');
            setSubmissionError(error instanceof Error ? error.message : 'Permission decision failed');
        }
    }, [onDecision]);

    const handleAllow = useCallback(() => {
        if (decided || remainingSeconds === null || remainingSeconds <= 0 || !request.operationHash) return;
        const requestedScope = canRemember && remember ? scope : 'once';
        const selected = request.options?.find(option => option.decision === 'allow' && option.scope === requestedScope);
        if (!selected) return;
        void submit({
            toolUseId: request.toolUseId,
            decision: 'allow',
            remember: canRemember && remember,
            ...(canRemember && remember ? { scope } : {}),
            optionId: selected.optionId,
            operationHash: request.operationHash,
            deliveryGeneration: request.deliveryGeneration ?? -1,
        });
    }, [canRemember, decided, request, remember, scope, remainingSeconds, submit]);

    const handleDeny = useCallback(() => {
        if (decided || remainingSeconds === null || remainingSeconds <= 0 || !request.operationHash) return;
        const selected = request.options?.find(option => option.decision === 'deny');
        if (!selected) return;
        void submit({ toolUseId: request.toolUseId, decision: 'deny', remember: false,
            optionId: selected.optionId, operationHash: request.operationHash,
            deliveryGeneration: request.deliveryGeneration ?? -1 });
    }, [decided, request, remainingSeconds, submit]);

    // Keyboard shortcuts: Y=allow, N=deny, Escape=deny
    useEffect(() => {
        const handler = (e: KeyboardEvent) => {
            if (!isTopModal(dialogRef.current)) return;
            if (decided) return;
            if (e.key === 'y' || e.key === 'Y') {
                handleAllow();
            } else if (e.key === 'n' || e.key === 'N' || e.key === 'Escape') {
                handleDeny();
            }
        };
        window.addEventListener('keydown', handler);
        return () => window.removeEventListener('keydown', handler);
    }, [decided, handleAllow, handleDeny]);

    // Reset all internal state when request changes (dialog reopens)
    useEffect(() => {
        setSubmission('idle');
        setSubmissionError(null);
        setRemainingSeconds(request.decisionDeadlineAt
            ? Math.max(0, Math.ceil((request.decisionDeadlineAt - Date.now()) / 1000))
            : null);
        setRemember(false);
        const nextScopes = request.scopeOptions ?? [];
        setScope(nextScopes.includes('session') ? 'session' : (nextScopes[0] ?? 'session'));
    }, [request.toolUseId, request.decisionDeadlineAt, request.scopeOptions]);

    // 始终根据服务端绝对截止时间重新计算，浏览器定时器节流不能延长决策窗口。
    useEffect(() => {
        const timer = setInterval(() => {
            setRemainingSeconds(request.decisionDeadlineAt
                ? Math.max(0, Math.ceil((request.decisionDeadlineAt - Date.now()) / 1000))
                : null);
        }, 1000);
        return () => clearInterval(timer);
    }, [request.toolUseId, request.decisionDeadlineAt]);

    // 倒计时只用于展示，服务端是超时终态的唯一裁决权威。

    const deadlineConfirmed = remainingSeconds !== null;
    const expired = deadlineConfirmed && remainingSeconds <= 0;
    const timerUrgent = deadlineConfirmed && remainingSeconds <= 30;
    const timerCritical = deadlineConfirmed && remainingSeconds <= 10;

    return (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-overlay2 backdrop-blur-[3px]">
            <div
                ref={dialogRef}
                tabIndex={-1}
                role="alertdialog"
                data-interaction-id={request.interactionId ?? request.toolUseId}
                aria-modal="true"
                aria-labelledby="permission-title"
                aria-describedby="permission-desc"
                className={`w-full max-w-lg mx-4 rounded-panel border-2 ${risk.border} ${risk.bg}
                            shadow-e4 max-h-[calc(100dvh-32px)] overflow-y-auto outline-none motion-safe:animate-scale-in`}
            >
                {/* Title bar */}
                <div className="px-4 md:px-6 py-3 border-b border-hairline flex items-center gap-3">
                    <RiskIcon size={20} className={risk.iconClass} />
                    <div className="flex-1">
                        <div id="permission-title" className="font-semibold text-xl text-t1 flex items-center gap-2">
                            {request.toolName}
                            {(request.actorType === 'descendant' || request.source === 'descendant' || request.source === 'subagent') && (
                                <span className="inline-block text-[13px] px-1.5 py-0.5 rounded bg-accent2-soft text-accent2-ink dark:text-accent2-ink">
                                    Sub-Agent
                                </span>
                            )}
                        </div>
                        <div className="flex items-center gap-2 mt-0.5">
                            <span className={`inline-block text-[13px] px-1.5 py-0.5 rounded ${risk.badge}`}>
                                {risk.label}
                            </span>
                            {(request.actorType === 'descendant' || request.source === 'descendant' || request.source === 'subagent') && request.actorRunId && (
                                <span className="text-[13px] text-t3">
                                    Agent run: {request.actorRunId.length > 12
                                        ? `${request.actorRunId.slice(0, 12)}…`
                                        : request.actorRunId}
                                </span>
                            )}
                        </div>
                    </div>
                    <button
                        onClick={handleDeny}
                        disabled={!deadlineConfirmed || expired || decided}
                        className="dialog-control p-1 rounded text-t3 hover:text-t1 transition-interactive duration-fast
                                   focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-accent2-ring
                                   disabled:opacity-50 disabled:pointer-events-none"
                    >
                        <X size={16} />
                    </button>
                </div>

                {/* Content */}
                <div className="px-4 md:px-6 py-4 space-y-3">
                    <p id="permission-desc" className="text-sm text-t3">{request.reason}</p>

                    {/* Tool input preview */}
                    <div className="max-h-48 overflow-y-auto">
                        <CodeBlock
                            code={formattedInput}
                            language={inputLang}
                            showLineNumbers={false}
                            maxHeight={180}
                        />
                    </div>
                </div>

                {/* Countdown progress bar */}
                <div className="px-4 md:px-6 pt-3">
                    <div className="flex items-center justify-between mb-1.5">
                        <span className={`text-[13px] ${
                            timerUrgent ? 'text-err font-bold' : 'text-t3'
                        } ${timerCritical ? 'animate-pulse' : ''}`}>
                            {deadlineConfirmed ? `${remainingSeconds}s remaining` : 'Waiting for delivery confirmation'}
                        </span>
                        <span className="text-[13px] text-t4">
                            {expired ? 'Waiting for server status' : 'Server decision deadline'}
                        </span>
                    </div>
                    <div className="w-full h-1.5 bg-sunken2 shadow-well rounded-full overflow-hidden">
                        <div
                            className={`h-full rounded-full transition-[width] duration-1000 ease-linear ${
                                timerUrgent ? 'bg-err' : 'bg-accent2'
                            } ${timerCritical ? 'animate-pulse' : ''}`}
                            style={{ width: `${deadlineConfirmed
                                ? Math.min(100, (remainingSeconds / DEFAULT_TIMEOUT_SECONDS) * 100)
                                : 0}%` }}
                        />
                    </div>
                </div>

                {/* Actions */}
                <div className="px-4 md:px-6 py-3 border-t border-hairline space-y-3">
                    {submissionError && (
                        <div role="alert" className="text-[13px] text-err">
                            {submissionError}. You can retry while the request is pending.
                        </div>
                    )}
                    {/* Remember option */}
                    {canRemember && (
                        <div className="space-y-1.5">
                            <label className="flex items-center gap-2 text-[13px] text-t3">
                                <input
                                    type="checkbox"
                                    disabled={!deadlineConfirmed || expired || decided}
                                    checked={remember}
                                    onChange={e => setRemember(e.target.checked)}
                                    className="rounded border-hairline accent-accent2"
                                />
                                Remember this decision
                                {remember && (
                                    <select
                                        value={scope}
                                        disabled={!deadlineConfirmed || expired || decided}
                                        onChange={e => setScope(e.target.value as typeof scope)}
                                        className="panel-control ml-2 text-[13px] rounded border border-hairline bg-sunken2
                                                   text-t2 px-1.5 py-0.5 transition-surface duration-fast
                                                   focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-accent2-ring
                                                   disabled:opacity-50"
                                    >
                                        {scopeOptions.includes('run') && <option value="run">Only this agent/run</option>}
                                        {scopeOptions.includes('session') && <option value="session">This session and child agents</option>}
                                        {scopeOptions.includes('workspace') && <option value="workspace">This workspace</option>}
                                    </select>
                                )}
                            </label>
                            {request.rememberScopeDescription && (
                                <p className="pl-6 text-[13px] leading-relaxed text-t3">
                                    {request.rememberScopeDescription}
                                </p>
                            )}
                        </div>
                    )}

                    {/* Buttons */}
                    <div className="flex justify-end gap-2">
                        <button
                            onClick={handleDeny}
                            disabled={!deadlineConfirmed || expired || decided}
                            className="dialog-control px-4 py-2 rounded-[14px] text-sm border border-hairline
                                       text-t2 hover:bg-hover2 transition-interactive duration-fast
                                       focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-accent2-ring
                                       active:scale-[.98] disabled:opacity-50 disabled:pointer-events-none"
                        >
                            Deny (N)
                        </button>
                        <button
                            onClick={handleAllow}
                            disabled={!deadlineConfirmed || expired || decided}
                            className={`dialog-control px-4 py-2 rounded-[14px] text-sm text-white transition-interactive duration-fast
                                focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-accent2-ring
                                active:scale-[.98] disabled:opacity-50 disabled:pointer-events-none
                                ${risk.btnClass}`}
                        >
                            {submission === 'submitting' ? 'Submitting…' : 'Allow (Y)'}
                        </button>
                    </div>
                </div>
            </div>
        </div>
    );
};

export default React.memo(PermissionDialog);
