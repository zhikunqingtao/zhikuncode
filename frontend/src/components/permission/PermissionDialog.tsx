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
 * "Remember" 选项带范围选择 (session/project/global)
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

const PermissionDialog: React.FC<PermissionDialogProps> = ({ request, onDecision }) => {
    const [remember, setRemember] = useState(false);
    const [scope, setScope] = useState<'session' | 'project' | 'global'>('session');
    const dialogRef = useRef<HTMLDivElement>(null);
    const riskLevel = (request.riskLevel || 'medium').toLowerCase() as keyof typeof RISK_CONFIG;
    const risk = RISK_CONFIG[riskLevel] ?? RISK_CONFIG.medium;
    const RiskIcon = risk.icon;

    // Keyboard shortcuts: Y=allow, N=deny, Escape=deny
    useEffect(() => {
        const handler = (e: KeyboardEvent) => {
            if (e.key === 'y' || e.key === 'Y') {
                onDecision({ toolUseId: request.toolUseId, decision: 'allow', remember, scope });
            } else if (e.key === 'n' || e.key === 'N' || e.key === 'Escape') {
                onDecision({ toolUseId: request.toolUseId, decision: 'deny', remember: false });
            }
        };
        window.addEventListener('keydown', handler);
        return () => window.removeEventListener('keydown', handler);
    }, [remember, scope, onDecision, request.toolUseId]);

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
        onDecision({ toolUseId: request.toolUseId, decision: 'allow', remember, scope });
    }, [onDecision, request.toolUseId, remember, scope]);

    const handleDeny = useCallback(() => {
        onDecision({ toolUseId: request.toolUseId, decision: 'deny', remember: false });
    }, [onDecision, request.toolUseId]);

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

                {/* Actions */}
                <div className="px-5 py-3 border-t border-gray-700/50 space-y-3">
                    {/* Remember option */}
                    <label className="flex items-center gap-2 text-xs text-gray-400">
                        <input
                            type="checkbox"
                            checked={remember}
                            onChange={e => setRemember(e.target.checked)}
                            className="rounded border-gray-600"
                        />
                        Remember this decision
                        {remember && (
                            <select
                                value={scope}
                                onChange={e => setScope(e.target.value as typeof scope)}
                                className="ml-2 text-xs rounded border border-gray-600 bg-gray-800
                                           text-gray-300 px-1.5 py-0.5"
                            >
                                <option value="session">This session</option>
                                <option value="project">This project</option>
                                <option value="global">Always</option>
                            </select>
                        )}
                    </label>

                    {/* Buttons */}
                    <div className="flex justify-end gap-2">
                        <button
                            onClick={handleDeny}
                            className="px-4 py-2 rounded-lg text-sm border border-gray-600
                                       text-gray-300 hover:bg-gray-800 transition-colors"
                        >
                            Deny (N)
                        </button>
                        <button
                            onClick={handleAllow}
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
