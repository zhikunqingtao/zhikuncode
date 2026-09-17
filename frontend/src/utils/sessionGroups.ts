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
    /** 服务端标记：最新根 Run 未达终态 = 正在运行（用于列表"运行中"状态展示） */
    running?: boolean;
}

export interface SessionFolderGroup {
    directory: string;
    name: string;
    sessions: SessionSummary[];
}

/** 完整授权路径作为组标识；组和会话都按真实活动时间排序。 */
export function groupSessionsByDirectory(sessions: SessionSummary[], preserveOrder = false): SessionFolderGroup[] {
    const activity = (session: SessionSummary) => Date.parse(session.updatedAt) || Date.parse(session.createdAt) || 0;
    const sorted = preserveOrder ? sessions : [...sessions].sort((a, b) => activity(b) - activity(a) || a.id.localeCompare(b.id));
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

/**
 * 列表项"运行中"展示判定：当前会话取前端实时 store 状态（即时），
 * 其余会话取服务端 running 标记（后台运行中的会话）。其他状态一律不展示。
 */
export function isSessionGenerating(
    session: Pick<SessionSummary, 'id' | 'running'>,
    currentSessionId: string | null,
    currentStatus: string,
): boolean {
    return session.id === currentSessionId
        ? currentStatus === 'streaming'
        : session.running === true;
}
