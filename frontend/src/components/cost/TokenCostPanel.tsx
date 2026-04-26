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
        <div className="rounded-lg border border-[var(--border)] bg-[var(--bg-secondary)] p-4">
            <h3 className="text-sm font-medium text-[var(--text-primary)] mb-3">
                Token & Cost
            </h3>

            {/* Cost Summary */}
            <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 mb-4">
                <div className="bg-blue-500/10 rounded-lg p-3">
                    <div className="text-xs text-blue-400">Session Cost</div>
                    <div className="text-lg font-semibold text-blue-300">
                        {formatCost(sessionCost)}
                    </div>
                </div>
                <div className="bg-green-500/10 rounded-lg p-3">
                    <div className="text-xs text-green-400">Total Cost</div>
                    <div className="text-lg font-semibold text-green-300">
                        {formatCost(totalCost)}
                    </div>
                </div>
                <div className="bg-purple-500/10 rounded-lg p-3">
                    <div className="text-xs text-purple-400">Total Tokens</div>
                    <div className="text-lg font-semibold text-purple-300">
                        {formatTokens(totalTokens)}
                    </div>
                </div>
                <div className="bg-amber-500/10 rounded-lg p-3">
                    <div className="text-xs text-amber-400">Cache Hit</div>
                    <div className="text-lg font-semibold text-amber-300">
                        {formatTokens(usage.cacheReadInputTokens ?? 0)}
                    </div>
                </div>
            </div>

            {/* Token Usage Bar */}
            <div className="space-y-2">
                <div className="flex justify-between text-xs text-[var(--text-muted)]">
                    <span>Input: {formatTokens(usage.inputTokens)}</span>
                    <span>Output: {formatTokens(usage.outputTokens)}</span>
                </div>
                <div className="h-2 bg-[var(--bg-primary)] rounded-full overflow-hidden flex">
                    <div
                        className="bg-blue-500 h-full"
                        style={{ width: `${pct(usage.inputTokens)}%` }}
                    />
                    <div
                        className="bg-green-500 h-full"
                        style={{ width: `${pct(usage.outputTokens)}%` }}
                    />
                    {(usage.cacheReadInputTokens ?? 0) > 0 && (
                        <div
                            className="bg-amber-500 h-full"
                            style={{ width: `${pct(usage.cacheReadInputTokens!)}%` }}
                        />
                    )}
                </div>
                <div className="flex gap-4 text-xs text-[var(--text-muted)]">
                    <span className="flex items-center gap-1">
                        <span className="w-2 h-2 bg-blue-500 rounded-full inline-block" />
                        Input
                    </span>
                    <span className="flex items-center gap-1">
                        <span className="w-2 h-2 bg-green-500 rounded-full inline-block" />
                        Output
                    </span>
                    <span className="flex items-center gap-1">
                        <span className="w-2 h-2 bg-amber-500 rounded-full inline-block" />
                        Cache
                    </span>
                </div>
            </div>
        </div>
    );
}
