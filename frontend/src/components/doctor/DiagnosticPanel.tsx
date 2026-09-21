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
        case 'ok': return <CheckCircle size={14} className="text-ok" />;
        case 'warn': return <AlertTriangle size={14} className="text-warn" />;
        case 'error': return <XCircle size={14} className="text-err" />;
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
        ok: 'text-ok border-ok bg-oksoft',
        warn: 'text-warn border-warn bg-warnsoft',
        error: 'text-err border-err bg-errsoft',
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
        <div className="rounded-[14px] border border-[var(--v2-border-hairline)] bg-[var(--v2-bg-sunken)] p-4 space-y-4">
            {/* Header + Summary + Actions */}
            <div className="flex flex-col md:flex-row items-start md:items-center justify-between gap-2">
                <div className="flex items-center gap-2">
                    <Activity size={18} className="text-accent2-ink" />
                    <span className="font-semibold text-[var(--v2-text-1)]">环境诊断报告</span>
                </div>
                <div className="flex items-center gap-2 flex-wrap">
                    <div className={`flex items-center gap-3 px-3 py-1 rounded-full border ${statusColor}`}>
                        <span className="text-[13px]">✅ {summary.ok}</span>
                        {summary.warn > 0 && <span className="text-[13px]">⚠️ {summary.warn}</span>}
                        {summary.error > 0 && <span className="text-[13px]">❌ {summary.error}</span>}
                    </div>
                    {onRecheck && (
                        <button onClick={onRecheck}
                            className="panel-control flex items-center gap-1 px-2 py-1 rounded text-[13px] bg-accent2-strong hover:bg-accent2-strong text-white">
                            <RefreshCw size={12} /> 重新检查
                        </button>
                    )}
                    <button onClick={handleExport} disabled={exporting}
                        className="panel-control flex items-center gap-1 px-2 py-1 rounded text-[13px] bg-[var(--bg-tertiary)] hover:bg-[var(--v2-bg-surface)] text-[var(--v2-text-2)]">
                        <Download size={12} /> 导出报告
                    </button>
                </div>
            </div>

            {/* Categorized checks grid */}
            <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
                {Object.entries(grouped).map(([category, items]) => (
                    <div key={category} className="space-y-1">
                        <div className="text-[13px] font-medium text-[var(--v2-text-2)] mb-1.5">
                            {CATEGORY_LABELS[category] ?? category}
                        </div>
                        <div className="space-y-1">
                            {items.map((check) => (
                                <div key={check.name}
                                     className="flex items-center justify-between px-3 py-1.5 rounded-md bg-[var(--bg-tertiary)]">
                                    <div className="flex items-center gap-2">
                                        <StatusIcon status={check.status} />
                                        <span className="text-sm text-[var(--v2-text-1)]">{check.name}</span>
                                    </div>
                                    <div className="text-right flex items-center gap-2">
                                        <div>
                                            <span className="text-[13px] text-[var(--v2-text-2)]">{check.value}</span>
                                            {check.hint && (
                                                <div className="text-[13px] text-[var(--v2-text-2)] italic">{check.hint}</div>
                                            )}
                                        </div>
                                        {check.actions && Object.entries(check.actions).map(([label, action]) => (
                                            <button key={label}
                                                onClick={() => onAction?.(label, action)}
                                                className="panel-control text-[13px] text-accent2-ink hover:text-accent2-ink underline">
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
