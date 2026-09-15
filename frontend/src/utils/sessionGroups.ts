export interface SessionSummary {
    id: string;
    title: string | null;
    goalPreview?: string | null;
    model: string;
    workingDirectory: string;
    messageCount: number;
    costUsd: number;
    createdAt: string;
    updatedAt: string;
}

export interface SessionFolderGroup {
    directory: string;
    name: string;
    sessions: SessionSummary[];
}

/** 完整授权路径作为组标识；组和会话都按真实活动时间排序。 */
export function groupSessionsByDirectory(sessions: SessionSummary[]): SessionFolderGroup[] {
    const activity = (session: SessionSummary) => Date.parse(session.updatedAt) || Date.parse(session.createdAt) || 0;
    const sorted = [...sessions].sort((a, b) => activity(b) - activity(a) || a.id.localeCompare(b.id));
    const groups = new Map<string, SessionFolderGroup>();
    for (const session of sorted) {
        const path = session.workingDirectory || '';
        const directory = path.replace(/[\\/]+$/, '') || (path ? '/' : '');
        let group = groups.get(directory);
        if (!group) {
            group = {
                directory,
                name: directory.split(/[\\/]/).filter(Boolean).at(-1) || (directory ? '/' : '未关联文件夹'),
                sessions: [],
            };
            groups.set(directory, group);
        }
        group.sessions.push(session);
    }
    return [...groups.values()];
}
