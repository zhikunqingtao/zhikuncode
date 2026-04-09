/**
 * fuzzyMatch — 命令模糊匹配算法
 * SPEC: §4.4 命令自动补全
 */

export interface CommandItem {
    name: string;
    description: string;
    usage?: string;
    category: 'builtin' | 'skill' | 'plugin';
}

export function fuzzyMatch(query: string, items: CommandItem[]): CommandItem[] {
    if (!query) return items.slice(0, 10);
    const lq = query.toLowerCase();
    return items
        .map(item => ({
            item,
            score: computeScore(lq, item.name.toLowerCase()),
        }))
        .filter(({ score }) => score > 0)
        .sort((a, b) => b.score - a.score)
        .map(({ item }) => item);
}

function computeScore(query: string, target: string): number {
    // 前缀匹配最高优先级
    if (target.startsWith(query)) return 100 + (target.length === query.length ? 50 : 0);
    // 包含匹配
    if (target.includes(query)) return 50;
    // 子序列匹配
    let qi = 0, score = 0;
    for (let ti = 0; ti < target.length && qi < query.length; ti++) {
        if (target[ti] === query[qi]) { qi++; score += 1; }
    }
    return qi === query.length ? score : 0;
}
