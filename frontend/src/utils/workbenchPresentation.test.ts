import { describe, expect, it } from 'vitest';
import type { Message } from '@/types';
import { taskTitle } from './workbenchPresentation';

function user(id: string, text: string): Message {
    return { uuid: id, type: 'user', timestamp: 1, content: [{ type: 'text', text }] };
}

describe('workbench presentation', () => {
    it('uses persisted title first and a readable first requirement as fallback', () => {
        const messages = [user('u-1', '评估这个项目能否部署到阿里云，并给出风险清单')];
        expect(taskTitle('正式标题', messages, '/workspace/king')).toBe('正式标题');
        expect(taskTitle(null, messages, '/workspace/king')).toBe('评估这个项目能否部署到阿里云，并给出风险清单');
        expect(taskTitle(null, [], '/workspace/king', '历史任务目标预览')).toBe('历史任务目标预览');
        expect(taskTitle(null, [], '/workspace/king')).toBe('king 任务');
    });
});
