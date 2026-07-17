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
 * "Remember" 选项只展示服务端允许的 session/workspace 范围。
 */

import React, { useState, useCallback, useEffect, useRef, useMemo } from 'react';
import { ShieldAlert, Info, X } from 'lucide-react';
import type { PermissionRequest, PermissionDecision } from '@/types';
import { CodeBlock } from '@/components/message';

interface PermissionDialogProps {
    request: PermissionRequest;
    onDecision: (decision: PermissionDecision) => void;
}

const RISK_CONFIG = {
    low: {
        bg: 'bg-blue-900/20',
        border: 'border-blue-500/50',
        badge: 'bg-blue-500/20 text-blue-400',
        icon: Info,
        label: 'Low Risk',
        btnClass: 'bg-blue-600 hover:bg-blue-700',
    },
    medium: {
        bg: 'bg-yellow-900/20',
        border: 'border-yellow-500/50',
        badge: 'bg-yellow-500/20 text-yellow-400',
        icon: ShieldAlert,
        label: 'Medium Risk',
        btnClass: 'bg-yellow-600 hover:bg-yellow-700',
    },
    high: {
        bg: 'bg-red-900/20',
        border: 'border-red-500/50',
        badge: 'bg-red-500/20 text-red-400',
        icon: ShieldAlert,
        label: 'High Risk',
        btnClass: 'bg-red-600 hover:bg-red-700',
    },
} as const;

const DEFAULT_TIMEOUT_SECONDS = 300;

const PermissionDialog: React.FC<PermissionDialogProps> = ({ request, onDecision }) => {
    const [remember, setRemember] = useState(false);
    const scopeOptions: ReadonlyArray<'session' | 'workspace'> =
        request.scopeOptions ?? [];
    const canRemember = scopeOptions.length > 0;
    const [scope, setScope] = useState<'session' | 'workspace'>(
        scopeOptions.includes('session') ? 'session' : (scopeOptions[0] ?? 'session'));
    const initialRemaining = () => request.decisionDeadlineAt
        ? Math.max(0, Math.ceil((request.decisionDeadlineAt - Date.now()) / 1000))
        : null;
    const [remainingSeconds, setRemainingSeconds] = useState<number | null>(initialRemaining);
    const [decided, setDecided] = useState(false);
    const dialogRef = useRef<HTMLDivElement>(null);
    const riskLevel = (request.riskLevel || 'medium').toLowerCase() as keyof typeof RISK_CONFIG;
    const risk = RISK_CONFIG[riskLevel] ?? RISK_CONFIG.medium;
    const RiskIcon = risk.icon;

    // Focus trap
    useEffect(() => { dialogRef.current?.focus(); }, []);

    // Format tool input for readable display
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

    const handleAllow = useCallback(() => {
        if (decided || remainingSeconds === null || remainingSeconds <= 0) return;
        setDecided(true);
        onDecision({
            toolUseId: request.toolUseId,
            decision: 'allow',
            remember: canRemember && remember,
            ...(canRemember && remember ? { scope } : {}),
        });
    }, [canRemember, decided, onDecision, request.toolUseId, remember, scope, remainingSeconds]);

    const handleDeny = useCallback(() => {
        if (decided || remainingSeconds === null || remainingSeconds <= 0) return;
        setDecided(true);
        onDecision({ toolUseId: request.toolUseId, decision: 'deny', remember: false });
    }, [decided, onDecision, request.toolUseId, remainingSeconds]);

    // Keyboard shortcuts: Y=allow, N=deny, Escape=deny
    useEffect(() => {
        const handler = (e: KeyboardEvent) => {
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
        setDecided(false);
        setRemainingSeconds(request.decisionDeadlineAt
            ? Math.max(0, Math.ceil((request.decisionDeadlineAt - Date.now()) / 1000))
            : null);
        setRemember(false);
        setScope('session');
    }, [request.toolUseId, request.decisionDeadlineAt]);

    // Recompute from the absolute server deadline. Browser timer throttling must
    // never extend the user's decision window.
    useEffect(() => {
        const timer = setInterval(() => {
            setRemainingSeconds(request.decisionDeadlineAt
                ? Math.max(0, Math.ceil((request.decisionDeadlineAt - Date.now()) / 1000))
                : null);
        }, 1000);
        return () => clearInterval(timer);
    }, [request.toolUseId, request.decisionDeadlineAt]);

    // The countdown is display-only. The server is the sole timeout authority.

    const deadlineConfirmed = remainingSeconds !== null;
    const expired = deadlineConfirmed && remainingSeconds <= 0;
    const timerUrgent = deadlineConfirmed && remainingSeconds <= 30;
    const timerCritical = deadlineConfirmed && remainingSeconds <= 10;

    return (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm">
            <div
                ref={dialogRef}
                tabIndex={-1}
                role="alertdialog"
                aria-modal="true"
                aria-labelledby="permission-title"
                aria-describedby="permission-desc"
                className={`w-full max-w-lg mx-4 rounded-xl border-2 ${risk.border} ${risk.bg}
                            shadow-2xl overflow-hidden outline-none`}
            >
                {/* Title bar */}
                <div className="px-5 py-3 border-b border-gray-700/50 flex items-center gap-3">
                    <RiskIcon size={20} className={risk.badge.split(' ')[1]} />
                    <div className="flex-1">
                        <div id="permission-title" className="font-semibold text-sm text-gray-200 flex items-center gap-2">
                            {request.toolName}
                            {request.source === 'subagent' && (
                                <span className="inline-block text-xs px-1.5 py-0.5 rounded bg-purple-500/20 text-purple-400">
                                    Sub-Agent
                                </span>
                            )}
                        </div>
                        <div className="flex items-center gap-2 mt-0.5">
                            <span className={`inline-block text-xs px-1.5 py-0.5 rounded ${risk.badge}`}>
                                {risk.label}
                            </span>
                            {request.source === 'subagent' && request.childSessionId && (
                                <span className="text-xs text-gray-500">
                                    Forwarded from: {request.childSessionId.length > 12
                                        ? `${request.childSessionId.slice(0, 12)}…`
                                        : request.childSessionId}
                                </span>
                            )}
                        </div>
                    </div>
                    <button
                        onClick={handleDeny}
                        disabled={!deadlineConfirmed || expired || decided}
                        className="p-1 rounded text-gray-500 hover:text-gray-300"
                    >
                        <X size={16} />
                    </button>
                </div>

                {/* Content */}
                <div className="px-5 py-4 space-y-3">
                    <p id="permission-desc" className="text-sm text-gray-400">{request.reason}</p>

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
                <div className="px-5 pt-3">
                    <div className="flex items-center justify-between mb-1.5">
                        <span className={`text-xs ${
                            timerUrgent ? 'text-red-400 font-bold' : 'text-gray-500'
                        } ${timerCritical ? 'animate-pulse' : ''}`}>
                            {deadlineConfirmed ? `${remainingSeconds}s remaining` : 'Waiting for delivery confirmation'}
                        </span>
                        <span className="text-xs text-gray-600">
                            {expired ? 'Waiting for server status' : 'Server decision deadline'}
                        </span>
                    </div>
                    <div className="w-full h-1.5 bg-gray-700/50 rounded-full overflow-hidden">
                        <div
                            className={`h-full rounded-full transition-all duration-1000 ease-linear ${
                                timerUrgent ? 'bg-red-500' : 'bg-blue-500'
                            } ${timerCritical ? 'animate-pulse' : ''}`}
                            style={{ width: `${deadlineConfirmed
                                ? Math.min(100, (remainingSeconds / DEFAULT_TIMEOUT_SECONDS) * 100)
                                : 0}%` }}
                        />
                    </div>
                </div>

                {/* Actions */}
                <div className="px-5 py-3 border-t border-gray-700/50 space-y-3">
                    {/* Remember option */}
                    {canRemember && <label className="flex items-center gap-2 text-xs text-gray-400">
                        <input
                            type="checkbox"
                            disabled={!deadlineConfirmed || expired || decided}
                            checked={remember}
                            onChange={e => setRemember(e.target.checked)}
                            className="rounded border-gray-600"
                        />
                        Remember this decision
                        {remember && (
                            <select
                                value={scope}
                                disabled={!deadlineConfirmed || expired || decided}
                                onChange={e => setScope(e.target.value as typeof scope)}
                                className="ml-2 text-xs rounded border border-gray-600 bg-gray-800
                                           text-gray-300 px-1.5 py-0.5"
                            >
                                {scopeOptions.includes('session') && <option value="session">This session</option>}
                                {scopeOptions.includes('workspace') && <option value="workspace">This workspace</option>}
                            </select>
                        )}
                    </label>}

                    {/* Buttons */}
                    <div className="flex justify-end gap-2">
                        <button
                            onClick={handleDeny}
                            disabled={!deadlineConfirmed || expired || decided}
                            className="px-4 py-2 rounded-lg text-sm border border-gray-600
                                       text-gray-300 hover:bg-gray-800 transition-colors"
                        >
                            Deny (N)
                        </button>
                        <button
                            onClick={handleAllow}
                            disabled={!deadlineConfirmed || expired || decided}
                            className={`px-4 py-2 rounded-lg text-sm text-white transition-colors
                                ${risk.btnClass}`}
                        >
                            Allow (Y)
                        </button>
                    </div>
                </div>
            </div>
        </div>
    );
};

export default React.memo(PermissionDialog);
