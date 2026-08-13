import type { ActivityData } from '@/types/apos';

export interface SimpleActivityGroup {
    key: string;
    category: string;
    label: string;
    detail: string | null;
    count: number;
    failed: boolean;
    timestamp: number;
    files: string[];
    activityIds: string[];
}

function categoryFor(activity: ActivityData): string {
    if (activity.toolResult?.isError || activity.status === 'failed') {
        return 'error';
    }
    const source = `${activity.toolName ?? ''} ${activity.summary ?? ''}`.toLowerCase();
    if (source.includes('webbrowser') || source.includes('browser')) {
        return 'browser';
    }
    if (source.includes('askuserquestion') || source.includes('ask user')) {
        return 'decision';
    }
    if (source.includes('read') || source.includes('grep') || source.includes('读取')) {
        return 'read';
    }
    if (source.includes('edit') || source.includes('write')) {
        return 'write';
    }
    if (source.includes('agent')) return 'agent';
    switch (activity.operationType) {
        case 'file_edit':
        case 'file_create':
        case 'config_change':
        case 'refactor':
            return 'write';
        case 'test_run':
            return 'test';
        case 'command_execute':
            return 'command';
        default:
            return 'other';
    }
}

function labelFor(category: string, fileCount: number): string {
    switch (category) {
        case 'error': return '一项操作未完成';
        case 'browser': return '检查了页面运行情况';
        case 'decision': return '提出了一项需要确认的问题';
        case 'read': return fileCount > 0 ? `查看了 ${fileCount} 个相关文件` : '查看了相关文件或内容';
        case 'write': return fileCount > 0 ? `更新了 ${fileCount} 个文件` : '修改或生成了文件';
        case 'agent': return '后台协作返回了结果';
        case 'test': return '运行了项目检查';
        case 'command': return '执行了一项本地任务';
        default: return '完成了一项技术操作';
    }
}

function detailFor(activity: ActivityData): string | null {
    const summary = activity.summary?.replace(/\s+/g, ' ').trim();
    if (!summary) return null;
    const toolName = activity.toolName?.trim().toLowerCase();
    if (toolName && summary.toLowerCase() === toolName) return null;
    const rawTechnicalDetail = /(?:^(?:执行|读取|编辑|写入|搜索)\s|\/Users\/|\/private\/|\/tmp\/|<<\s*['"]?\w+|\bpython3?\b|\bsed\s+-|\bgrep\s+-|\brg\s+-|\bchmod\b|\bnpm\s+|\bmvnw?\b|\btoolUseId\b)/i;
    if (rawTechnicalDetail.test(summary)) return null;
    if (/^(WebBrowser|AskUserQuestion|Read|Edit|Write|Bash|Sleep)$/i.test(summary)) return null;
    return summary.length > 180 ? `${summary.slice(0, 180).trimEnd()}…` : summary;
}

export function projectActivities(
    activities: ActivityData[],
    limit = 20,
): SimpleActivityGroup[] {
    const ordered = activities
        .filter(item => Number.isFinite(item.timestamp))
        .sort((a, b) => a.timestamp - b.timestamp);
    const groups: SimpleActivityGroup[] = [];
    for (const activity of ordered) {
        const category = categoryFor(activity);
        const failed = Boolean(activity.toolResult?.isError || activity.status === 'failed');
        const files = (activity.changedFiles ?? [])
            .map(item => item.filePath)
            .filter(Boolean);
        const previous = groups.at(-1);
        if (previous && !failed && !previous.failed && previous.category === category) {
            previous.count += 1;
            previous.timestamp = activity.timestamp;
            previous.files = Array.from(new Set([...previous.files, ...files])).slice(0, 5);
            previous.label = labelFor(category, previous.files.length);
            previous.detail = detailFor(activity) ?? previous.detail;
            previous.activityIds.push(activity.id);
            continue;
        }
        groups.push({
            key: activity.id,
            category,
            label: labelFor(category, files.length),
            detail: detailFor(activity),
            count: 1,
            failed,
            timestamp: activity.timestamp,
            files: Array.from(new Set(files)).slice(0, 5),
            activityIds: [activity.id],
        });
    }
    return groups.slice(-limit).reverse();
}
