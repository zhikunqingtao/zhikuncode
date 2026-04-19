import React, { useState } from 'react';
import { CheckCircle, AlertTriangle, XCircle, Activity, RefreshCw, Download } from 'lucide-react';

interface DiagnosticCheck {
    category: string;
    name: string;
    value: string;
    status: 'ok' | 'warn' | 'error';
    hint?: string;
    actions?: Record<string, string>;
}

interface DiagnosticSummary {
    ok: number;
    warn: number;
    error: number;
    total: number;
}

const StatusIcon: React.FC<{ status: DiagnosticCheck['status'] }> = ({ status }) => {
    switch (status) {
        case 'ok': return <CheckCircle size={14} className="text-green-400" />;
        case 'warn': return <AlertTriangle size={14} className="text-yellow-400" />;
        case 'error': return <XCircle size={14} className="text-red-400" />;
    }
};

const CATEGORY_LABELS: Record<string, string> = {
    runtime: '💻 运行时',
    llm: '🤖 LLM',
    env: '📁 环境',
    auth: '🔐 认证',
    session: '💬 会话',
    tool: '🛠️ 工具',
    service: '⚙️ 服务',
};

export const DiagnosticPanel: React.FC<{
    checks: DiagnosticCheck[];
    summary: DiagnosticSummary;
    onRecheck?: () => void;
    onAction?: (actionKey: string, actionValue: string) => void;
}> = ({ checks, summary, onRecheck, onAction }) => {
    const [exporting, setExporting] = useState(false);

    // 按 category 分组
    const grouped = checks.reduce((acc, check) => {
        (acc[check.category] ??= []).push(check);
        return acc;
    }, {} as Record<string, DiagnosticCheck[]>);

    const overallStatus = summary.error > 0 ? 'error' : summary.warn > 0 ? 'warn' : 'ok';
    const statusColor = {
        ok: 'text-green-400 border-green-600/30 bg-green-600/5',
        warn: 'text-yellow-400 border-yellow-600/30 bg-yellow-600/5',
        error: 'text-red-400 border-red-600/30 bg-red-600/5',
    }[overallStatus];

    const handleExport = () => {
        setExporting(true);
        const report = { timestamp: new Date().toISOString(), summary, checks };
        const blob = new Blob([JSON.stringify(report, null, 2)], { type: 'application/json' });
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `diagnostic-report-${new Date().toISOString().slice(0, 10)}.json`;
        a.click();
        URL.revokeObjectURL(url);
        setExporting(false);
    };

    return (
        <div className="rounded-lg border border-[var(--border)] bg-[var(--bg-secondary)] p-4 space-y-4">
            {/* Header + Summary + Actions */}
            <div className="flex flex-col sm:flex-row items-start sm:items-center justify-between gap-2">
                <div className="flex items-center gap-2">
                    <Activity size={18} className="text-blue-400" />
                    <span className="font-semibold text-[var(--text-primary)]">环境诊断报告</span>
                </div>
                <div className="flex items-center gap-2 flex-wrap">
                    <div className={`flex items-center gap-3 px-3 py-1 rounded-full border ${statusColor}`}>
                        <span className="text-xs">✅ {summary.ok}</span>
                        {summary.warn > 0 && <span className="text-xs">⚠️ {summary.warn}</span>}
                        {summary.error > 0 && <span className="text-xs">❌ {summary.error}</span>}
                    </div>
                    {onRecheck && (
                        <button onClick={onRecheck}
                            className="flex items-center gap-1 px-2 py-1 rounded text-xs bg-blue-600 hover:bg-blue-700 text-white">
                            <RefreshCw size={12} /> 重新检查
                        </button>
                    )}
                    <button onClick={handleExport} disabled={exporting}
                        className="flex items-center gap-1 px-2 py-1 rounded text-xs bg-[var(--bg-tertiary)] hover:bg-[var(--bg-primary)] text-[var(--text-secondary)]">
                        <Download size={12} /> 导出报告
                    </button>
                </div>
            </div>

            {/* Categorized checks grid */}
            <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
                {Object.entries(grouped).map(([category, items]) => (
                    <div key={category} className="space-y-1">
                        <div className="text-xs font-medium text-[var(--text-muted)] mb-1.5">
                            {CATEGORY_LABELS[category] ?? category}
                        </div>
                        <div className="space-y-1">
                            {items.map((check) => (
                                <div key={check.name}
                                     className="flex items-center justify-between px-3 py-1.5 rounded-md bg-[var(--bg-tertiary)]">
                                    <div className="flex items-center gap-2">
                                        <StatusIcon status={check.status} />
                                        <span className="text-sm text-[var(--text-primary)]">{check.name}</span>
                                    </div>
                                    <div className="text-right flex items-center gap-2">
                                        <div>
                                            <span className="text-xs text-[var(--text-secondary)]">{check.value}</span>
                                            {check.hint && (
                                                <div className="text-xs text-[var(--text-muted)] italic">{check.hint}</div>
                                            )}
                                        </div>
                                        {check.actions && Object.entries(check.actions).map(([label, action]) => (
                                            <button key={label}
                                                onClick={() => onAction?.(label, action)}
                                                className="text-xs text-blue-400 hover:text-blue-300 underline">
                                                {label}
                                            </button>
                                        ))}
                                    </div>
                                </div>
                            ))}
                        </div>
                    </div>
                ))}
            </div>
        </div>
    );
};
