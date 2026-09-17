import { describe, expect, it } from 'vitest';
import { groupSessionsByDirectory, isSessionGenerating, type SessionSummary } from './sessionGroups';

const session = (id: string, directory: string, day: number): SessionSummary => ({
    id, workingDirectory: directory, title: id, model: 'model', messageCount: 1, costUsd: 0,
    createdAt: '2026-09-01T00:00:00Z', updatedAt: `2026-09-${String(day).padStart(2, '0')}T00:00:00Z`,
});

describe('session folder groups', () => {
    it('orders groups by their newest conversation, and conversations by activity without mutating input', () => {
        const input = [session('old', '/dev/a', 1), session('b', '/dev/b', 10), session('new', '/dev/a/', 15)];
        const groups = groupSessionsByDirectory(input);
        expect(groups.map(group => group.directory)).toEqual(['/dev/a', '/dev/b']);
        expect(groups[0].sessions.map(item => item.id)).toEqual(['new', 'old']);
        expect(input.map(item => item.id)).toEqual(['old', 'b', 'new']);
        expect(groupSessionsByDirectory([...input, session('latest', '/dev/b', 16)])[0].directory).toBe('/dev/b');
    });

    it('keeps identical folder names at different paths separate and handles missing directories', () => {
        const groups = groupSessionsByDirectory([session('a', '/one/project', 5), session('b', '/two/project', 4), session('c', '', 3)]);
        expect(groups.map(group => group.name)).toEqual(['project', 'project', '未关联文件夹']);
        expect(new Set(groups.map(group => group.directory)).size).toBe(3);
    });
});

describe('isSessionGenerating', () => {
    it('uses the live store status for the current session', () => {
        const current = { id: 's1', running: false };
        expect(isSessionGenerating(current, 's1', 'streaming')).toBe(true);
        // 仅"运行中"展示；就绪/等待权限/压缩中等其他状态不展示
        expect(isSessionGenerating(current, 's1', 'idle')).toBe(false);
        expect(isSessionGenerating(current, 's1', 'waiting_permission')).toBe(false);
        expect(isSessionGenerating(current, 's1', 'compacting')).toBe(false);
    });

    it('uses the server running flag for background sessions', () => {
        expect(isSessionGenerating({ id: 's2', running: true }, 's1', 'idle')).toBe(true);
        expect(isSessionGenerating({ id: 's2', running: false }, 's1', 'streaming')).toBe(false);
        expect(isSessionGenerating({ id: 's2' }, 's1', 'idle')).toBe(false);
        expect(isSessionGenerating({ id: 's2', running: true }, null, 'idle')).toBe(true);
    });
});
