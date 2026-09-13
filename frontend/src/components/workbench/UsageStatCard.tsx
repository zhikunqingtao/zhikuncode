/**
 * UsageStatCard — §7.3 真实数据可视化 ③：Token / 成本 stat 卡
 *
 * 数据沿用 StatusBar 同源（useCostStore，由 #15 cost_update 权威推送），
 * 零新接口、零虚构统计。无真实数据（Token 与成本全零）→ 不渲染
 * （诚实性规则：无就不显示，不落 0 值图表）。
 * 排印：Metric 32px / 650 / -0.02em + tabular-nums；sub 12px text-t3。
 */

import { useCostStore } from '@/store/costStore';

/** 是否有真实用量数据（SimpleWorkbench 据此决定 stat 卡槽位是否占位） */
export function hasUsageData(usage: { inputTokens: number; outputTokens: number }, sessionCost: number): boolean {
    return usage.inputTokens + usage.outputTokens > 0 || sessionCost > 0;
}

export function UsageStatCard() {
    const { sessionCost, totalCost, usage } = useCostStore();
    if (!hasUsageData(usage, sessionCost)) return null;

    const totalTokens = usage.inputTokens + usage.outputTokens;
    return (
        <section className="rounded-2xl border border-hairline bg-surfacev2 p-5 shadow-e2">
            <p className="text-[11px] font-semibold uppercase tracking-wider text-t3">用量与成本</p>
            <div className="mt-3 grid grid-cols-2 gap-4">
                <div>
                    <p className="text-[32px] font-[650] leading-none tracking-[-0.02em] tabular-nums text-t1">
                        {totalTokens.toLocaleString()}
                    </p>
                    <p className="mt-1.5 text-xs tabular-nums text-t3">
                        Tokens · ↑ {usage.inputTokens.toLocaleString()} ↓ {usage.outputTokens.toLocaleString()}
                    </p>
                </div>
                <div>
                    <p className="text-[32px] font-[650] leading-none tracking-[-0.02em] tabular-nums text-t1">
                        ${sessionCost.toFixed(3)}
                    </p>
                    <p className="mt-1.5 text-xs tabular-nums text-t3">
                        本次会话成本 · 累计 ${totalCost.toFixed(3)}
                    </p>
                </div>
            </div>
        </section>
    );
}
