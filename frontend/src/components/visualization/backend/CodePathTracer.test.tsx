import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { type ReactNode } from 'react';
import { CodePathTracer } from './CodePathTracer';
import { useCodePathStore } from '@/store/codePathStore';

vi.mock('@xyflow/react', async () => {
  const { useState } = await import('react');
  const noop = () => {};
  return {
    ReactFlowProvider: ({ children }: { children: ReactNode }) => <>{children}</>,
    ReactFlow: ({ nodes, onNodeClick, children }: { nodes: { id: string }[]; onNodeClick: (event: unknown, node: { id: string }) => void; children: ReactNode }) => (
      <div>{nodes.map(node => <button key={node.id} onClick={() => onNodeClick({}, node)}>节点 {node.id}</button>)}{children}</div>
    ),
    useReactFlow: () => ({ fitView: noop }),
    useNodesState: (nodes: unknown[]) => [...useState(nodes), noop],
    useEdgesState: (edges: unknown[]) => [...useState(edges), noop],
    MiniMap: () => null, Controls: () => null, Background: () => null, Handle: () => null,
    Position: { Top: 'top', Bottom: 'bottom' }, BackgroundVariant: { Dots: 'dots' },
  };
});
const endpoint = { httpMethod: 'GET', path: '/api/demo', handlerFunction: 'listItems', handlerClass: 'DemoController', filePath: 'src/DemoController.java', lineNumber: 12, language: 'Java', parameters: [] };
const node = { id: 'controller', name: 'listItems', className: 'DemoController', filePath: endpoint.filePath, lineRange: [12, 28], layer: 'controller' as const, nodeType: 'method', annotations: [], parameters: [], returnType: 'List<Item>' };

beforeEach(() => {
  useCodePathStore.getState().reset();
  useCodePathStore.setState({ endpoints: [], projectRoot: '', endpointsLoading: false });
});
afterEach(() => vi.unstubAllGlobals());

describe('code path panel', () => {
  it('preserves scan and trace payloads through search and endpoint selection', async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce({ json: async () => ({ endpoints: [endpoint] }) }).mockResolvedValueOnce({ json: async () => ({ nodes: [node], edges: [], layers: [] }) });
    vi.stubGlobal('fetch', fetchMock);
    render(<CodePathTracer />);
    fireEvent.change(screen.getByRole('textbox', { name: '项目路径' }), { target: { value: '/tmp/demo' } });
    fireEvent.click(screen.getByRole('button', { name: '扫描' }));
    await screen.findByRole('button', { name: /\/api\/demo/ });
    fireEvent.change(screen.getByRole('textbox', { name: '搜索端点' }), { target: { value: 'absent' } });
    expect(screen.getByText('无匹配端点')).toBeInTheDocument();
    fireEvent.change(screen.getByRole('textbox', { name: '搜索端点' }), { target: { value: 'demo' } });
    fireEvent.click(screen.getByRole('button', { name: /\/api\/demo/ }));
    await screen.findByRole('button', { name: '节点 controller' });
    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({ projectRoot: '/tmp/demo' });
    expect(JSON.parse(fetchMock.mock.calls[1][1].body)).toEqual({ projectRoot: '/tmp/demo', entryFile: endpoint.filePath, entryFunction: endpoint.handlerFunction, maxDepth: 10 });
    fireEvent.click(screen.getByRole('button', { name: '节点 controller' }));
    expect(screen.getByText(endpoint.filePath)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '关闭节点详情' }));
    expect(screen.queryByText('节点详情')).not.toBeInTheDocument();
  });

  it('keeps a scan failure visible and re-enables retry', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('扫描失败示例')));
    render(<CodePathTracer />);
    fireEvent.click(screen.getByRole('button', { name: '扫描' }));
    await screen.findByText('扫描失败示例');
    await waitFor(() => expect(screen.getByRole('button', { name: '扫描' })).toBeEnabled());
  });
});
