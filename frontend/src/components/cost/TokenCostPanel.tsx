interface TokenUsage {
    inputTokens: number;
    outputTokens: number;
    cacheReadInputTokens?: number;
    cacheCreationInputTokens?: number;
}

export interface CostSnapshot {
    timestamp: number;
    cost: number;
    tokens: number;
}

interface TokenCostPanelProps {
    sessionCost: number;
    totalCost: number;
    usage: TokenUsage;
    history?: CostSnapshot[];
}

export function TokenCostPanel({ sessionCost, totalCost, usage, history: _history }: TokenCostPanelProps) {
    const formatCost = (cost: number) => `$${cost.toFixed(4)}`;
    const formatTokens = (tokens: number) => tokens.toLocaleString();

    const totalTokens =
        usage.inputTokens +
        usage.outputTokens +
        (usage.cacheReadInputTokens ?? 0) +
        (usage.cacheCreationInputTokens ?? 0);

    const pct = (value: number) =>
        totalTokens > 0 ? (value / totalTokens) * 100 : 0;

    return (
        <div className="rounded-[14px] border border-[var(--v2-border-hairline)] bg-[var(--v2-bg-sunken)] p-4">
            <h3 className="text-[var(--v2-text-1)] mb-3 text-base font-semibold">
                Token & Cost
            </h3>

            {/* Cost Summary */}
            <div className="grid grid-cols-2 md:grid-cols-4 gap-3 mb-4">
                <div className="bg-accent2-soft rounded-[10px] p-3">
                    <div className="text-[13px] text-accent2-ink">Session Cost</div>
                    <div className="text-lg font-semibold text-accent2-ink">
                        {formatCost(sessionCost)}
                    </div>
                </div>
                <div className="bg-oksoft rounded-[10px] p-3">
                    <div className="text-[13px] text-ok">Total Cost</div>
                    <div className="text-lg font-semibold text-ok">
                        {formatCost(totalCost)}
                    </div>
                </div>
                <div className="bg-accent2-soft rounded-[10px] p-3">
                    <div className="text-[13px] text-accent2-ink">Total Tokens</div>
                    <div className="text-lg font-semibold text-accent2-ink">
                        {formatTokens(totalTokens)}
                    </div>
                </div>
                <div className="bg-warnsoft rounded-[10px] p-3">
                    <div className="text-[13px] text-warn">Cache Hit</div>
                    <div className="text-lg font-semibold text-warn">
                        {formatTokens(usage.cacheReadInputTokens ?? 0)}
                    </div>
                </div>
            </div>

            {/* Token Usage Bar */}
            <div className="space-y-2">
                <div className="flex justify-between text-[13px] text-[var(--v2-text-2)]">
                    <span>Input: {formatTokens(usage.inputTokens)}</span>
                    <span>Output: {formatTokens(usage.outputTokens)}</span>
                </div>
                <div className="h-2 bg-[var(--v2-bg-surface)] rounded-full overflow-hidden flex">
                    <div
                        className="bg-accent2 h-full"
                        style={{ width: `${pct(usage.inputTokens)}%` }}
                    />
                    <div
                        className="bg-ok h-full"
                        style={{ width: `${pct(usage.outputTokens)}%` }}
                    />
                    {(usage.cacheReadInputTokens ?? 0) > 0 && (
                        <div
                            className="bg-warn h-full"
                            style={{ width: `${pct(usage.cacheReadInputTokens!)}%` }}
                        />
                    )}
                </div>
                <div className="flex gap-4 text-[13px] text-[var(--v2-text-2)]">
                    <span className="flex items-center gap-1">
                        <span className="w-2 h-2 bg-accent2 rounded-full inline-block" />
                        Input
                    </span>
                    <span className="flex items-center gap-1">
                        <span className="w-2 h-2 bg-ok rounded-full inline-block" />
                        Output
                    </span>
                    <span className="flex items-center gap-1">
                        <span className="w-2 h-2 bg-warn rounded-full inline-block" />
                        Cache
                    </span>
                </div>
            </div>
        </div>
    );
}
