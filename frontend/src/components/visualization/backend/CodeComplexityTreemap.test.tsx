import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { cloneElement, type ReactElement, type ReactNode } from 'react';
import { CodeComplexityTreemap } from './CodeComplexityTreemap';
import { useComplexityStore, type ComplexityNode } from '@/store/complexityStore';

vi.mock('recharts', () => ({
  ResponsiveContainer: ({ children }: { children: ReactNode }) => <>{children}</>,
  Tooltip: () => null,
  Treemap: ({ data, content }: { data: Record<string, unknown>[]; content: ReactElement }) => (
    <svg>{data.map((node, index) => cloneElement(content, { ...node, key: index, width: 220, height: 140 }))}</svg>
  ),
}));

beforeEach(() => {
  useComplexityStore.getState().reset();
  const file: ComplexityNode = { name: 'demo.ts', type: 'file', loc: 100, cc: 3, mi: 70, risk_level: 'A', language: 'TypeScript' };
  const root: ComplexityNode = { ...file, name: 'project', type: 'project', children: [
    { ...file, name: 'src', type: 'directory', children: [file] },
    { ...file, name: 'server.py', language: 'Python', risk_level: 'E' },
  ] };
  useComplexityStore.setState({ complexityTree: root, currentNode: root, currentDrillPath: [root] });
});

describe('complexity navigation and filters', () => {
  it('supports keyboard drill and breadcrumb return without changing the root data', () => {
    render(<CodeComplexityTreemap />);
    fireEvent.keyDown(screen.getByRole('button', { name: '展开 src' }), { key: 'Enter' });
    expect(screen.getByText('demo.ts')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'project' }));
    expect(screen.getByRole('button', { name: '展开 src' })).toBeInTheDocument();
    expect(useComplexityStore.getState().complexityTree?.children).toHaveLength(2);
  });

  it('combines language and risk filters and restores matches when risk is cleared', () => {
    render(<CodeComplexityTreemap />);
    fireEvent.click(screen.getByRole('button', { name: '所有语言' }));
    fireEvent.click(screen.getByRole('button', { name: 'Python' }));
    expect(screen.queryByRole('button', { name: '展开 src' })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '风险等级' }));
    fireEvent.click(screen.getByRole('checkbox', { name: 'A - 低风险' }));
    expect(screen.getByText('当前过滤条件下无匹配文件')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '清除' }));
    expect(screen.getByText('server.py')).toBeInTheDocument();
  });
});
