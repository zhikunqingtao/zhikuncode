import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { McpManagementPage } from './McpManagementPage';
import { useMcpServiceStore } from '@/store/mcpServiceStore';

const service = {
  serverKey: 'context7',
  displayName: 'context7',
  description: '来自配置文件的 MCP 服务',
  domain: 'configured',
  source: 'configured',
  transportType: 'HTTP',
  enabled: false,
  status: 'disabled',
  readiness: 'ready',
  toolCount: 0,
  tools: [],
};

function response(payload: unknown): Response {
  return { ok: true, status: 200, json: async () => payload } as Response;
}

describe('McpManagementPage', () => {
  beforeEach(() => {
    useMcpServiceStore.setState({
      services: [], loading: false, error: null, total: 0, enabledCount: 0, pending: {},
    });
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('loads disabled services and enables one only after an explicit switch click', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url === '/api/mcp/services') {
        return response({ services: [service], total: 1, enabledCount: 0 });
      }
      if (url === '/api/mcp/services/context7/toggle?enabled=true' && init?.method === 'PATCH') {
        return response({ ...service, enabled: true, status: 'connected' });
      }
      throw new Error(`Unexpected request: ${url}`);
    });
    vi.stubGlobal('fetch', fetchMock);

    render(<McpManagementPage onClose={vi.fn()} />);
    const toggle = await screen.findByRole('switch', { name: '启用 context7' });
    expect(toggle).toHaveAttribute('aria-checked', 'false');

    fireEvent.click(toggle);
    await waitFor(() => expect(screen.getByRole('switch', { name: '停用 context7' }))
      .toHaveAttribute('aria-checked', 'true'));
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/mcp/services/context7/toggle?enabled=true',
      { method: 'PATCH' },
    );
  });
});
