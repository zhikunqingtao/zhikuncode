import { AlertTriangle, CheckCircle2, CircleDashed, MinusCircle } from 'lucide-react';
import type { CriterionStatus, WorkbenchCriterion } from '@/hooks/useSimpleWorkbenchData';

const labels: Record<CriterionStatus, string> = { PASSED: '已通过', FAILED: '有问题', PARTIAL: '部分通过', NOT_VERIFIED: '未验证' };
const meta = {
    PASSED: { icon: CheckCircle2, color: 'text-green-500' },
    FAILED: { icon: AlertTriangle, color: 'text-red-500' },
    PARTIAL: { icon: MinusCircle, color: 'text-amber-500' },
    NOT_VERIFIED: { icon: CircleDashed, color: 'text-[var(--text-muted)]' },
} as const;

export function AcceptanceCriteriaView({ business, technical, overall }: {
    business: WorkbenchCriterion[];
    technical: WorkbenchCriterion[];
    overall: CriterionStatus;
}) {
    return <section className="rounded-2xl border border-[var(--border)] bg-[var(--bg-secondary)] p-5">
        <div className="flex items-center justify-between gap-3"><div><p className="text-xs font-medium uppercase tracking-wide text-amber-500">要求核验</p><h2 className="mt-1 font-semibold text-[var(--text-primary)]">这次要求检查到了哪里</h2></div><Status status={overall} /></div>
        {business.length > 0 ? <ul className="mt-4 space-y-2">{business.map((item, index) => <Criterion key={item.id ?? `${index}-${item.text}`} item={item} />)}</ul> : <div className="mt-4 rounded-xl border border-dashed border-[var(--border)] p-4 text-sm text-[var(--text-muted)]">本次要求中没有识别到高置信度的编号、项目符号或约束句，不会自动制造业务条款。</div>}
        <details className="mt-4 rounded-xl border border-[var(--border)]"><summary className="cursor-pointer px-3 py-2 text-xs font-medium text-[var(--text-secondary)] hover:bg-[var(--bg-hover)]">基础技术检查 · {technical.length} 项</summary><ul className="space-y-2 border-t border-[var(--border)] p-3">{technical.map(item => <Criterion key={item.id ?? item.text} item={item} />)}</ul></details>
        <p className="mt-3 text-xs leading-5 text-[var(--text-muted)]">条款只读。若要求有遗漏，请在下方输入补充要求，下一次执行会生成新的当前条款。</p>
    </section>;
}

function Criterion({ item }: { item: WorkbenchCriterion }) {
    const config = meta[item.status]; const Icon = config.icon;
    return <li className="flex items-start gap-3 rounded-xl bg-[var(--bg-primary)] p-3"><Icon className={`mt-0.5 h-4 w-4 shrink-0 ${config.color}`} /><div className="min-w-0 flex-1"><p className="text-sm leading-6 text-[var(--text-primary)]">{item.text}</p>{item.detail && <p className="mt-1 text-xs text-[var(--text-muted)]">{item.detail}</p>}</div><span className={`shrink-0 text-xs ${config.color}`}>{labels[item.status]}</span></li>;
}

function Status({ status }: { status: CriterionStatus }) { const config = meta[status]; const Icon = config.icon; return <span className={`inline-flex items-center gap-1.5 text-sm font-medium ${config.color}`}><Icon className="h-4 w-4" />{labels[status]}</span>; }
