/**
 * ToolRunBlock 组件测试
 * 覆盖：L1 摘要（计数/top 工具名/耗时/结果状态）、L2 工具列表（状态图标/
 * 主目标/耗时）、L3 内嵌 ToolCallBlock 受控展开、运行变体（执行中文案 +
 * 呼吸点 + L2/L3 自动展开）、>20 截断与「显示全部」、取消态计数。
 */

import { fireEvent, render, screen } from '@testing-library/react';
import { beforeAll, describe, expect, it } from 'vitest';
import type { ToolCallState, ToolResult } from '@/types';
import ToolRunBlock from '../ToolRunBlock';
import type { ToolUseBlock } from '../../toolCallState';

// jsdom 无 matchMedia 实现，CodeBlock 的 resolveTheme('system') 依赖它
beforeAll(() => {
    if (typeof window.matchMedia !== 'function') {
        window.matchMedia = ((query: string) => ({
            matches: false,
            media: query,
            onchange: null,
            addListener: () => {},
            removeListener: () => {},
            addEventListener: () => {},
            removeEventListener: () => {},
            dispatchEvent: () => false,
        })) as unknown as typeof window.matchMedia;
    }
});

function toolUse(
    id: string,
    toolName: string,
    input: Record<string, unknown> = {},
    result?: ToolResult,
): ToolUseBlock {
    return {
        type: 'tool_use',
        toolUseId: id,
        toolName,
        input,
        ...(result ? { result } : {}),
    };
}

const okResult = (content = 'done'): ToolResult => ({ content, isError: false });

describe('ToolRunBlock L1 摘要行', () => {
    it('完成态默认折叠：N 次工具调用 + top 工具名计数 + 全部成功 ok 标记', () => {
        render(
            <ToolRunBlock
                blocks={[
                    toolUse('t1', 'Read', { file_path: '/a.ts' }, okResult()),
                    toolUse('t2', 'Read', { file_path: '/b.ts' }, okResult()),
                    toolUse('t3', 'Edit', { file_path: '/a.ts' }, okResult()),
                ]}
            />,
        );
        expect(screen.getByText('3 次工具调用')).toBeInTheDocument();
        expect(screen.getByText('Read×2 · Edit×1')).toBeInTheDocument();
        expect(screen.getByRole('img', { name: '全部成功' })).toBeInTheDocument();
        // L2 默认折叠
        expect(screen.queryByTestId('tool-run-list')).not.toBeInTheDocument();
    });

    it('有失败 → err 色失败计数，不显示全部成功标记', () => {
        render(
            <ToolRunBlock
                blocks={[
                    toolUse('t1', 'Read', {}, okResult()),
                    toolUse('t2', 'Bash', {}, { content: 'boom', isError: true }),
                ]}
            />,
        );
        expect(screen.getByText('1 失败')).toBeInTheDocument();
        expect(screen.queryByRole('img', { name: '全部成功' })).not.toBeInTheDocument();
    });

    it('有取消（metadata.executionStatus=cancelled）→ warn 色取消计数且不计失败', () => {
        render(
            <ToolRunBlock
                blocks={[
                    toolUse('t1', 'Read', {}, { content: 'aborted', isError: true, metadata: { executionStatus: 'cancelled' } }),
                ]}
            />,
        );
        expect(screen.getByText('1 取消')).toBeInTheDocument();
        expect(screen.queryByText(/失败/)).not.toBeInTheDocument();
    });

    it('总耗时：仅 activeToolCalls 实时条目的 duration 累加显示', () => {
        const live = new Map<string, ToolCallState>([
            ['t1', { toolName: 'Read', input: {}, status: 'completed', startTime: 0, duration: 1500 }],
            ['t2', { toolName: 'Edit', input: {}, status: 'completed', startTime: 0, duration: 2500 }],
        ]);
        render(
            <ToolRunBlock
                blocks={[
                    toolUse('t1', 'Read', {}, okResult()),
                    toolUse('t2', 'Edit', {}, okResult()),
                ]}
                activeToolCalls={live}
            />,
        );
        expect(screen.getByText('4s')).toBeInTheDocument();
    });
});

describe('ToolRunBlock L2/L3 展开', () => {
    it('点 L1 展开工具列表：状态图标 + 工具名 + 主目标 + 耗时', () => {
        const live = new Map<string, ToolCallState>([
            ['t1', { toolName: 'Read', input: {}, status: 'completed', startTime: 0, duration: 1200 }],
        ]);
        render(
            <ToolRunBlock
                blocks={[toolUse('t1', 'Read', { file_path: '/src/App.tsx' }, okResult())]}
                activeToolCalls={live}
            />,
        );
        fireEvent.click(screen.getByRole('button', { name: /1 次工具调用/ }));
        expect(screen.getByTestId('tool-run-list')).toBeInTheDocument();
        // L2 行按钮的可访问名以工具名开头（L1 摘要为「1 次工具调用 Read×1…」）
        expect(screen.getByRole('button', { name: /^Read/ })).toBeInTheDocument();
        expect(screen.getByText('/src/App.tsx')).toBeInTheDocument();
        // L1 总耗时 + L2 行耗时各一份
        expect(screen.getAllByText('1s')).toHaveLength(2);
    });

    it('点 L2 行展开 L3：内嵌完整 ToolCallBlock（受控 expanded，含 Input/Result 区）', () => {
        render(
            <ToolRunBlock
                blocks={[toolUse('t1', 'Read', { file_path: '/a.ts' }, okResult('file-content'))]}
            />,
        );
        fireEvent.click(screen.getByRole('button', { name: /1 次工具调用/ }));
        // L3 未展开时无 Input 区
        expect(screen.queryByText('Input')).not.toBeInTheDocument();
        fireEvent.click(screen.getByRole('button', { name: /^Read/ }));
        const detail = document.querySelector('[data-tool-use-id="t1"]');
        expect(detail).not.toBeNull();
        // 受控 expanded=true：Input / Result 区直接可见
        expect(screen.getByText('Input')).toBeInTheDocument();
        expect(screen.getByText('Result')).toBeInTheDocument();
    });

    it('L3 的 result 配对：activeToolCalls 未命中时回退 block.result（与 AssistantMessage 一致）', () => {
        render(
            <ToolRunBlock
                blocks={[toolUse('t1', 'Read', {}, { content: 'paired-content', isError: false })]}
            />,
        );
        fireEvent.click(screen.getByRole('button', { name: /1 次工具调用/ }));
        fireEvent.click(screen.getByRole('button', { name: /^Read/ }));
        fireEvent.click(screen.getByRole('button', { name: /Result/ }));
        expect(screen.getByText(/paired-content/)).toBeInTheDocument();
    });

    it('单段 >20 个工具时 L2 默认只显示前 20 行 + 「显示全部 N 个」', () => {
        const blocks = Array.from({ length: 25 }, (_, i) =>
            toolUse(`t${i}`, 'Read', { file_path: `/f/${i}.ts` }, okResult()));
        render(<ToolRunBlock blocks={blocks} />);
        fireEvent.click(screen.getByRole('button', { name: /25 次工具调用/ }));
        expect(screen.getByText('/f/19.ts')).toBeInTheDocument();
        expect(screen.queryByText('/f/20.ts')).not.toBeInTheDocument();
        fireEvent.click(screen.getByRole('button', { name: '显示全部 25 个' }));
        expect(screen.getByText('/f/24.ts')).toBeInTheDocument();
    });
});

describe('ToolRunBlock 运行变体', () => {
    it('段内含 running 块：L1 显示「执行中 · 当前工具名 (x/y)」+ accent 呼吸点', () => {
        const live = new Map<string, ToolCallState>([
            ['t2', { toolName: 'Bash', input: { command: 'npm test' }, status: 'running', startTime: Date.now() }],
        ]);
        render(
            <ToolRunBlock
                blocks={[
                    toolUse('t1', 'Read', {}, okResult()),
                    toolUse('t2', 'Bash', { command: 'npm test' }),
                ]}
                activeToolCalls={live}
            />,
        );
        expect(screen.getByText(/执行中 ·/)).toBeInTheDocument();
        expect(screen.getByText('(2/2)')).toBeInTheDocument();
        expect(screen.getByTestId('tool-run-active-dot')).toBeInTheDocument();
        // 无结果状态汇总（未全部完成）
        expect(screen.queryByRole('img', { name: '全部成功' })).not.toBeInTheDocument();
    });

    it('运行中 → L2 自动展开，当前运行工具自动展开 L3 详情', () => {
        const live = new Map<string, ToolCallState>([
            ['t1', { toolName: 'Bash', input: { command: 'sleep 5' }, status: 'running', startTime: Date.now() }],
        ]);
        render(
            <ToolRunBlock
                blocks={[toolUse('t1', 'Bash', { command: 'sleep 5' })]}
                activeToolCalls={live}
            />,
        );
        // L2 自动展开
        expect(screen.getByTestId('tool-run-list')).toBeInTheDocument();
        // L3 自动展开（Input 区可见）
        expect(screen.getByText('Input')).toBeInTheDocument();
    });

    it('block 无 result 且 activeToolCalls 未命中 → 视为 running（不误标完成）', () => {
        render(<ToolRunBlock blocks={[toolUse('t1', 'Read')]} />);
        expect(screen.getByText(/执行中 ·/)).toBeInTheDocument();
    });
});
