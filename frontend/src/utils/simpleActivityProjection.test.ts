import { describe, expect, it } from 'vitest';
import type { ActivityData } from '@/types/apos';
import { projectActivities } from './simpleActivityProjection';

function activity(overrides: Partial<ActivityData>): ActivityData {
    return {
        id: 'activity-1',
        sessionId: 'session-1',
        operationType: 'unknown',
        summary: '',
        changedFiles: [],
        status: 'completed',
        timestamp: 1,
        ...overrides,
    };
}

describe('projectActivities', () => {
    it('translates supported tools into result language', () => {
        const result = projectActivities([
            activity({ id: 'read', toolName: 'Read', timestamp: 1 }),
            activity({ id: 'browser', toolName: 'WebBrowser', timestamp: 2 }),
        ]);
        expect(result.map(item => item.label)).toEqual([
            '检查了页面运行情况',
            '查看了相关文件或内容',
        ]);
    });

    it('groups only adjacent successful events and preserves failures', () => {
        const result = projectActivities([
            activity({ id: 'edit-1', toolName: 'Edit', timestamp: 1 }),
            activity({ id: 'edit-2', toolName: 'Write', timestamp: 2 }),
            activity({
                id: 'edit-error',
                toolName: 'Edit',
                timestamp: 3,
                toolResult: { content: 'failed', isError: true },
            }),
        ]);
        expect(result).toHaveLength(2);
        expect(result[0]).toMatchObject({ failed: true, count: 1 });
        expect(result[1]).toMatchObject({ label: '修改或生成了文件', count: 2 });
    });

    it('does not invent file counts when no structured files exist', () => {
        const [result] = projectActivities([
            activity({ id: 'bash', operationType: 'command_execute', timestamp: 1 }),
        ]);
        expect(result.label).toBe('执行了一项本地任务');
        expect(result.files).toEqual([]);
    });

    it('keeps a concrete summary and structured file names visible', () => {
        const [result] = projectActivities([
            activity({
                id: 'test',
                operationType: 'test_run',
                summary: '浏览器回归通过，控制台没有新增错误',
                changedFiles: [{ filePath: 'src/App.tsx', additions: 4, deletions: 1, changeType: 'modified' }],
                timestamp: 1,
            }),
        ]);

        expect(result).toMatchObject({
            label: '运行了项目检查',
            detail: '浏览器回归通过，控制台没有新增错误',
            files: ['src/App.tsx'],
            activityIds: ['test'],
        });
    });

    it('does not surface raw shell commands as ordinary-user progress', () => {
        const [result] = projectActivities([
            activity({
                id: 'command',
                operationType: 'command_execute',
                summary: "执行 python3 - << 'EOF' open('/Users/example/private.txt')",
                timestamp: 1,
            }),
        ]);
        expect(result.detail).toBeNull();
        expect(result.label).toBe('执行了一项本地任务');
    });
});
