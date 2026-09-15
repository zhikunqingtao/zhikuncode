import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { APISequenceDiagram } from './APISequenceDiagram';
import { useMessageStore } from '@/store/messageStore';

vi.mock('@/components/visualization/shared/MermaidBlock', () => ({
  default: ({ code }: { code: string }) => <pre data-testid="diagram-source">{code}</pre>,
}));

beforeEach(() => {
  useMessageStore.setState({ messages: [
    { type: 'assistant', uuid: 'call', timestamp: 1000, stopReason: 'tool_use', usage: {inputTokens: 0, outputTokens: 0, cacheReadInputTokens: 0, cacheCreationInputTokens: 0}, content: [
      { type: 'tool_use', toolUseId: 'read', toolName: 'Read', input: { path: 'demo.ts' } },
      { type: 'tool_use', toolUseId: 'bash', toolName: 'Bash', input: { command: 'npm test' } },
    ] },
    { type: 'user', uuid: 'result', timestamp: 2000, content: [
      { type: 'tool_result', toolUseId: 'read', content: '示例文件内容', isError: false },
      { type: 'tool_result', toolUseId: 'bash', content: '示例命令失败', isError: true },
    ] },
  ] });
});

describe('API sequence panel', () => {
  it('filters the chart and records together, closes with Escape, and restores all calls', () => {
    render(<APISequenceDiagram />);
    fireEvent.click(screen.getByRole('button', { name: '过滤工具' }));
    fireEvent.click(screen.getByRole('checkbox', { name: 'Read' }));
    expect(screen.queryByRole('button', { name: /Bash\s*command/ })).not.toBeInTheDocument();
    expect(screen.getByTestId('diagram-source')).not.toHaveTextContent('Bash');
    fireEvent.keyDown(screen.getByRole('checkbox', { name: 'Read' }), { key: 'Escape' });
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /过滤工具/ }));
    fireEvent.click(screen.getByRole('button', { name: '清除全部' }));
    expect(screen.getByRole('button', { name: /Bash\s*command/ })).toBeInTheDocument();
  });

  it('retains raw inputs and failures in details and preserves them on refresh', () => {
    render(<APISequenceDiagram />);
    fireEvent.click(screen.getByRole('button', { name: /Bash\s*command/ }));
    expect(screen.getByText(/"command": "npm test"/)).toBeInTheDocument();
    expect(screen.getByText('示例命令失败')).toBeInTheDocument();
    expect(screen.getByText('（失败）')).toBeInTheDocument();
    fireEvent.click(screen.getByTitle('刷新'));
    expect(screen.getByText('示例命令失败')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '关闭调用详情' }));
    expect(screen.queryByText('Bash 详情')).not.toBeInTheDocument();
  });
});
