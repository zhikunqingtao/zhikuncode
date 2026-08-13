import { CheckCircle2, CircleDashed, FileCheck2, Flag, Loader2, ShieldCheck } from 'lucide-react';
import type { CurrentWorkbenchView } from '@/hooks/useSimpleWorkbenchData';

export function TaskMilestoneStrip({ current }: { current: CurrentWorkbenchView | null }) {
    const run = current?.rootRun;
    const verification = current?.verification.overallStatus ?? 'NOT_VERIFIED';
    const execution = run?.status === 'COMPLETED' ? '本轮执行已结束'
        : ['FAILED', 'CANCELLED', 'INTERRUPTED'].includes(run?.status ?? '') ? '本轮执行未成功'
            : run ? '本轮正在执行' : '等待开始执行';
    const verificationLabels = { PASSED: '要求已检查通过', FAILED: '检查发现问题', PARTIAL: '部分要求已检查', NOT_VERIFIED: '尚未完成检查' } as const;
    const milestones = [
        { label: '目标', value: current?.request ? '本次要求已记录' : '等待输入目标', icon: current?.request ? CheckCircle2 : CircleDashed, tone: current?.request ? 'text-green-500' : 'text-[var(--text-muted)]' },
        { label: '执行', value: execution, icon: run && !['COMPLETED', 'FAILED', 'CANCELLED', 'INTERRUPTED'].includes(run.status) ? Loader2 : Flag, tone: current?.currentFailure ? 'text-red-500' : run ? 'text-blue-500' : 'text-[var(--text-muted)]' },
        { label: '交付', value: current?.delivery.totalFiles ? `本轮记录 ${current.delivery.totalFiles} 个文件` : '本轮尚无结构化交付', icon: current?.delivery.totalFiles ? FileCheck2 : CircleDashed, tone: current?.delivery.totalFiles ? 'text-emerald-500' : 'text-[var(--text-muted)]' },
        { label: '核验', value: verificationLabels[verification], icon: verification === 'PASSED' ? ShieldCheck : CircleDashed, tone: verification === 'PASSED' ? 'text-green-500' : verification === 'FAILED' ? 'text-red-500' : verification === 'PARTIAL' ? 'text-amber-500' : 'text-[var(--text-muted)]' },
    ];
    return <section aria-label="任务里程碑" className="grid gap-2 sm:grid-cols-2 xl:grid-cols-4">{milestones.map(({ label, value, icon: Icon, tone }) => (
        <div key={label} className="flex items-center gap-3 rounded-xl border border-[var(--border)] bg-[var(--bg-secondary)] px-4 py-3">
            <Icon className={`h-4 w-4 shrink-0 ${tone}`} />
            <div className="min-w-0"><p className="text-[11px] font-medium uppercase tracking-wide text-[var(--text-muted)]">{label}</p><p className="truncate text-sm font-medium text-[var(--text-primary)]" title={value}>{value}</p></div>
        </div>
    ))}</section>;
}
