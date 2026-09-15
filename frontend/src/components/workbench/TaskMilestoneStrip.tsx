import { Check } from 'lucide-react';
import type { CurrentWorkbenchView } from '@/hooks/useSimpleWorkbenchData';

/**
 * TaskMilestoneStrip — §7.2 milestone 卡（记忆点②）
 * Label（任务进度 · xxx，11px 大写 tracking-wider text-t3）+ 胶囊链：
 *   done = bg-accent2-soft text-accent2-ink + ✓
 *   now  = bg-accent2 实底白字 + ring-accent2-ring 光晕
 *   todo = bg-sunken2 text-t4
 * 连接线 22×2（done = accent 50%）；横向可滚（scrollbar 隐藏）。
 * 诚实性规则：执行阶段无真实分母，一律不显示百分比。
 */

type StageState = 'done' | 'now' | 'todo';

interface Stage {
    key: string;
    label: string;
    /** 该阶段的实时描述（tooltip + 当前阶段汇总进 Label） */
    value: string;
    done: boolean;
    /** 正在进行信号（运行中/部分完成），优先标 now */
    active: boolean;
}

const PILL_CLASS: Record<StageState, string> = {
    done: 'bg-accent2-soft text-accent2-ink',
    now: 'bg-accent2 text-white ring-2 ring-accent2-ring',
    todo: 'bg-sunken2 text-t4',
};

export function TaskMilestoneStrip({ current }: { current: CurrentWorkbenchView | null }) {
    const run = current?.rootRun;
    const verification = current?.verification.overallStatus ?? 'NOT_VERIFIED';
    const runFinished = ['COMPLETED', 'FAILED', 'CANCELLED', 'INTERRUPTED'].includes(run?.status ?? '');
    const execution = run?.status === 'COMPLETED' ? '本轮执行已结束'
        : runFinished ? '本轮执行未成功'
            : run ? '本轮正在执行' : '等待开始执行';
    const verificationLabels = { PASSED: '要求已检查通过', FAILED: '检查发现问题', PARTIAL: '部分要求已检查', NOT_VERIFIED: '尚未完成检查' } as const;

    const stages: Stage[] = [
        {
            key: 'goal', label: '目标',
            value: current?.request ? '本次要求已记录' : '等待输入目标',
            done: !!current?.request, active: false,
        },
        {
            key: 'run', label: '执行',
            value: execution,
            done: run?.status === 'COMPLETED',
            active: !!run && !runFinished,
        },
        {
            key: 'delivery', label: '交付',
            value: current?.delivery.totalFiles ? `本轮记录 ${current.delivery.totalFiles} 个文件` : '本轮尚无结构化交付',
            done: !!current?.delivery.totalFiles, active: false,
        },
        {
            key: 'verify', label: '核验',
            value: verificationLabels[verification],
            done: verification === 'PASSED',
            active: verification === 'PARTIAL',
        },
    ];

    // 三态推导：active 优先 now；否则首个未完成（且前序全部完成）的阶段为 now，其余 todo
    let nowAssigned = false;
    const states: StageState[] = stages.map((stage, i) => {
        if (stage.done) return 'done';
        const predecessorsDone = stages.slice(0, i).every(s => s.done);
        if (!nowAssigned && (stage.active || predecessorsDone)) {
            nowAssigned = true;
            return 'now';
        }
        return 'todo';
    });

    const currentStage = stages[states.findIndex(s => s === 'now')]
        ?? (states.every(s => s === 'done') ? stages[stages.length - 1] : null);

    return (
        <section aria-label="任务里程碑" className="min-w-0">
            <p className="mb-2 text-[13px] font-semibold uppercase tracking-wider text-t3">
                任务进度{currentStage ? ` · ${currentStage.value}` : ''}
            </p>
            <div className="flex items-center overflow-x-auto pb-1 [scrollbar-width:none] [-ms-overflow-style:none] [&::-webkit-scrollbar]:hidden">
                {stages.map((stage, i) => {
                    const state = states[i];
                    return (
                        <div key={stage.key} className="flex shrink-0 items-center">
                            {i > 0 && (
                                <span
                                    aria-hidden="true"
                                    className="mx-1 h-[2px] w-[22px] shrink-0 rounded-full"
                                    style={{
                                        backgroundColor: states[i - 1] === 'done'
                                            ? 'color-mix(in srgb, var(--v2-accent) 50%, transparent)'
                                            : 'var(--v2-border-hairline)',
                                    }}
                                />
                            )}
                            <span
                                title={stage.value}
                                className={`flex items-center gap-1 whitespace-nowrap rounded-full px-3 py-1 text-[13px] font-medium ${PILL_CLASS[state]}`}
                            >
                                {state === 'done' && <Check size={12} aria-hidden="true" />}
                                {stage.label}
                            </span>
                        </div>
                    );
                })}
            </div>
        </section>
    );
}
