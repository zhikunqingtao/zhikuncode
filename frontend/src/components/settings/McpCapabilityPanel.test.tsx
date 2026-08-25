import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { McpCapabilityPanel } from './McpCapabilityPanel';
import { useMcpCapabilityStore } from '@/store/mcpCapabilityStore';

const CAPABILITY_ID = 'cap-image-gen';
const SERVER_URL = 'https://mcp.example.com/image-gen/sse';
const LEGACY_SSE_URL = 'https://legacy.example.com/image-gen/sse';

function jsonResponse(payload: unknown): Response {
    return {
        ok: true,
        status: 200,
        json: async () => payload,
    } as unknown as Response;
}

function makeCapability(overrides: Record<string, unknown> = {}): Record<string, unknown> {
    return {
        id: CAPABILITY_ID,
        name: '图像生成',
        toolName: 'image_generation',
        apiKeyConfig: 'IMAGE_API_KEY',
        domain: 'image_processing',
        category: 'tool',
        briefDescription: '生成图像',
        description: '生成图像的工具',
        input: {},
        output: {},
        timeoutMs: 30000,
        enabled: true,
        videoCallEnabled: false,
        ...overrides,
    };
}

/** 后端 MCP 能力接口 fetch mock：域列表 / 能力列表 / PUT 更新。 */
function stubBackendFetch(capabilities: Record<string, unknown>[]) {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input);
        const method = init?.method ?? 'GET';
        if (url === '/api/mcp/capabilities/domains') {
            return jsonResponse({ domains: ['image_processing'] });
        }
        if (url.startsWith(`/api/mcp/capabilities/${CAPABILITY_ID}`) && method === 'PUT') {
            return jsonResponse(makeCapability(JSON.parse(String(init?.body))));
        }
        if (url.startsWith('/api/mcp/capabilities')) {
            return jsonResponse({
                capabilities,
                total: capabilities.length,
                enabledCount: capabilities.filter(c => c.enabled).length,
            });
        }
        throw new Error(`Unexpected fetch ${method} ${url}`);
    });
    vi.stubGlobal('fetch', fetchMock);
    return fetchMock;
}

/** Field 组件中 label 与 input 为兄弟节点，按 label 文案取对应输入控件。 */
function getFieldControl(labelText: string): HTMLInputElement {
    const label = screen.getByText(labelText);
    const control = label.nextElementSibling;
    expect(control).toBeInstanceOf(HTMLInputElement);
    return control as HTMLInputElement;
}

async function renderPanelAndWaitForCapabilities() {
    render(<McpCapabilityPanel />);
    await waitFor(() => expect(screen.getByText('图像生成')).toBeInTheDocument());
}

function openEditDialog() {
    fireEvent.click(screen.getByRole('button', { name: '编辑' }));
    expect(screen.getByText('编辑 MCP 工具')).toBeInTheDocument();
}

function findPutCall(fetchMock: ReturnType<typeof stubBackendFetch>): unknown[] {
    const call = fetchMock.mock.calls.find(
        ([input, init]) =>
            String(input) === `/api/mcp/capabilities/${CAPABILITY_ID}` &&
            (init?.method ?? 'GET') === 'PUT',
    );
    expect(call).toBeDefined();
    return call as unknown[];
}

describe('McpCapabilityPanel url contract', () => {
    beforeEach(() => {
        useMcpCapabilityStore.setState({
            capabilities: [],
            domains: [],
            activeDomain: null,
            loading: false,
            total: 0,
            enabledCount: 0,
            testResults: {},
        });
    });

    afterEach(() => {
        cleanup();
        vi.unstubAllGlobals();
        vi.restoreAllMocks();
    });

    it('renders the edit dialog URL input from the url field', async () => {
        stubBackendFetch([makeCapability({ url: SERVER_URL })]);
        await renderPanelAndWaitForCapabilities();

        openEditDialog();

        expect(screen.getByText('Server URL')).toBeInTheDocument();
        expect(getFieldControl('Server URL')).toHaveValue(SERVER_URL);
    });

    it('keeps url in the PUT payload when only the name is edited', async () => {
        const fetchMock = stubBackendFetch([makeCapability({ url: SERVER_URL })]);
        await renderPanelAndWaitForCapabilities();

        openEditDialog();
        fireEvent.change(getFieldControl('名称'), { target: { value: '图像生成 Pro' } });
        fireEvent.click(screen.getByRole('button', { name: '保存' }));
        await waitFor(() =>
            expect(screen.queryByText('编辑 MCP 工具')).not.toBeInTheDocument());

        const putCall = findPutCall(fetchMock);
        const body = JSON.parse(String((putCall[1] as RequestInit).body));
        expect(body.name).toBe('图像生成 Pro');
        expect(body.url).toBe(SERVER_URL);
        expect(body).not.toHaveProperty('sseUrl');
    });

    it('falls back to legacy sseUrl data when rendering and saving', async () => {
        const fetchMock = stubBackendFetch([makeCapability({ sseUrl: LEGACY_SSE_URL })]);
        await renderPanelAndWaitForCapabilities();

        openEditDialog();

        expect(getFieldControl('Server URL')).toHaveValue(LEGACY_SSE_URL);

        fireEvent.click(screen.getByRole('button', { name: '保存' }));
        await waitFor(() =>
            expect(screen.queryByText('编辑 MCP 工具')).not.toBeInTheDocument());

        const putCall = findPutCall(fetchMock);
        const body = JSON.parse(String((putCall[1] as RequestInit).body));
        expect(body.url).toBe(LEGACY_SSE_URL);
        expect(body).not.toHaveProperty('sseUrl');
    });
});
