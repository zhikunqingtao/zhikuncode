import { fireEvent, render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import DetailNavigation from '../DetailNavigation';
import { buildTurnNavigation } from '../turnNavigation';
import { buildTurns } from '@/store/selectors/turnProjection';
import type { Message } from '@/types';

const entries = [
    { key: '0:0', title: '修复登录', turnIndex: 0, expandKey: '0:0' },
    { key: '0:1', title: '补充测试', turnIndex: 0, expandKey: '0:1' },
];
describe('详细档导航', () => {
    it('桌面显示当前任务并支持首项、任务、最新进展导航', () => {
        const onSelect = vi.fn(); const onLatest = vi.fn();
        render(<DetailNavigation entries={entries} activeKey="0:1" isMobile={false} onSelect={onSelect} onLatest={onLatest} />);
        expect(screen.getByRole('button', { name: '补充测试' })).toHaveAttribute('aria-current', 'location');
        fireEvent.click(screen.getByRole('button', { name: '第一个任务' }));
        expect(onSelect).toHaveBeenLastCalledWith(entries[0]);
        fireEvent.click(screen.getByRole('button', { name: '补充测试' }));
        expect(onSelect).toHaveBeenLastCalledWith(entries[1]);
        fireEvent.click(screen.getByRole('button', { name: '最新进展' }));
        expect(onLatest).toHaveBeenCalledOnce();
    });
    it('移动端显示位置，Sheet 选任务后关闭并跳转', () => {
        const onSelect = vi.fn();
        render(<DetailNavigation entries={entries} activeKey="0:1" isMobile onSelect={onSelect} onLatest={vi.fn()} />);
        const trigger = screen.getByRole('button', { name: '选择任务' });
        expect(trigger).toHaveTextContent('任务 2/2');
        fireEvent.click(trigger);
        const dialog = screen.getByRole('dialog', { name: '选择任务' });
        fireEvent.click(within(dialog).getByRole('button', { name: '修复登录' }));
        expect(onSelect).toHaveBeenCalledWith(entries[0]);
        expect(trigger).toHaveAttribute('aria-expanded', 'false');
    });
    it('混合会话保留无任务轮入口，序号不受准备消息影响', () => {
        const messages = [
            { type: 'system', uuid: 'pre', timestamp: 0, content: 'context' },
            { type: 'user', uuid: 'u1', timestamp: 1, content: [{ type: 'text', text: 'hello' }] },
            { type: 'user', uuid: 'u2', timestamp: 2, content: [{ type: 'text', text: 'work' }] },
            { type: 'system', uuid: 'b', timestamp: 3, content: '', subtype: 'task_boundary', metadata: { title: '执行任务', seq: 1 } },
        ] as Message[];
        expect(buildTurnNavigation(buildTurns(messages))).toEqual([
            { key: 'turn-0', title: '会话上下文', turnIndex: 0 },
            { key: 'turn-1', title: '第 1 轮', turnIndex: 1 },
            { key: '2:0', title: '执行任务', turnIndex: 2, expandKey: '2:0' },
        ]);
    });
});
