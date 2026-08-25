import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useMcpCapabilityStore, type McpCapabilityDefinition } from '../mcpCapabilityStore';

function jsonResponse(payload: unknown): Response {
    return {
        ok: true,
        status: 200,
        json: async () => payload,
    } as unknown as Response;
}

function makeCapability(overrides: Record<string, unknown> = {}): Record<string, unknown> {
    return {
        id: 'cap-1',
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

function asCapability(raw: Record<string, unknown>): McpCapabilityDefinition {
    return raw as unknown as McpCapabilityDefinition;
}

/** fetch mock：handler 返回响应 payload；返回 undefined 表示未预期的请求。 */
function stubFetch(handler: (url: string, init?: RequestInit) => Record<string, unknown> | undefined) {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const payload = handler(String(input), init);
        if (payload === undefined) {
            throw new Error(`Unexpected fetch ${init?.method ?? 'GET'} ${input}`);
        }
        return jsonResponse(payload);
    });
    vi.stubGlobal('fetch', fetchMock);
    return fetchMock;
}

function resetStore(capabilities: McpCapabilityDefinition[] = []) {
    useMcpCapabilityStore.setState({
        capabilities,
        domains: [],
        activeDomain: null,
        loading: false,
        total: capabilities.length,
        enabledCount: capabilities.filter(c => c.enabled).length,
        testResults: {},
    });
}

describe('McpCapabilityStore url contract', () => {
    beforeEach(() => {
        resetStore();
    });

    afterEach(() => {
        vi.unstubAllGlobals();
        vi.restoreAllMocks();
    });

    it('normalizes new-contract url payloads and drops the legacy sseUrl key', async () => {
        stubFetch(url => {
            if (url.startsWith('/api/mcp/capabilities?')) {
                return {
                    capabilities: [makeCapability({
                        url: 'https://mcp.example.com/sse',
                        sseUrl: 'https://legacy.example.com/sse',
                    })],
                    total: 1,
                    enabledCount: 1,
                };
            }
            return undefined;
        });

        await useMcpCapabilityStore.getState().loadCapabilities();

        const [cap] = useMcpCapabilityStore.getState().capabilities;
        expect(cap.url).toBe('https://mcp.example.com/sse');
        expect(cap).not.toHaveProperty('sseUrl');
    });

    it('maps legacy sseUrl-only payloads onto url when loading', async () => {
        stubFetch(url => {
            if (url.startsWith('/api/mcp/capabilities?')) {
                return {
                    capabilities: [makeCapability({ sseUrl: 'https://legacy.example.com/sse' })],
                    total: 1,
                    enabledCount: 1,
                };
            }
            return undefined;
        });

        await useMcpCapabilityStore.getState().loadCapabilities();

        const [cap] = useMcpCapabilityStore.getState().capabilities;
        expect(cap.url).toBe('https://legacy.example.com/sse');
        expect(cap).not.toHaveProperty('sseUrl');
    });

    it('sends only the url field in the updateCapability PUT body', async () => {
        const existing = asCapability(makeCapability({ url: 'https://mcp.example.com/sse' }));
        resetStore([existing]);
        const fetchMock = stubFetch(url => {
            if (url === '/api/mcp/capabilities/cap-1') {
                return makeCapability({ url: 'https://mcp.example.com/sse' });
            }
            return undefined;
        });

        await useMcpCapabilityStore.getState().updateCapability('cap-1', existing);

        const putCall = fetchMock.mock.calls.find(
            ([input, init]) => String(input) === '/api/mcp/capabilities/cap-1' && init?.method === 'PUT',
        );
        expect(putCall).toBeDefined();
        const body = JSON.parse(String((putCall![1] as RequestInit).body));
        expect(body.url).toBe('https://mcp.example.com/sse');
        expect(body).not.toHaveProperty('sseUrl');
    });

    it('normalizes the updateCapability PUT response back into state', async () => {
        const existing = asCapability(makeCapability({ url: 'https://mcp.example.com/sse' }));
        resetStore([existing]);
        stubFetch(() => makeCapability({ sseUrl: 'https://legacy.example.com/sse' }));

        await useMcpCapabilityStore.getState().updateCapability('cap-1', existing);

        const [cap] = useMcpCapabilityStore.getState().capabilities;
        expect(cap.url).toBe('https://legacy.example.com/sse');
        expect(cap).not.toHaveProperty('sseUrl');
    });

    it('sends url in the addCapability POST body and normalizes the response', async () => {
        const fetchMock = stubFetch(() => makeCapability({ id: 'cap-2', sseUrl: 'https://legacy.example.com/sse' }));

        const draft = asCapability(makeCapability({ id: 'cap-2', url: 'https://mcp.example.com/sse' }));
        await useMcpCapabilityStore.getState().addCapability(draft);

        const state = useMcpCapabilityStore.getState();
        expect(state.capabilities).toHaveLength(1);
        expect(state.capabilities[0].url).toBe('https://legacy.example.com/sse');
        expect(state.capabilities[0]).not.toHaveProperty('sseUrl');

        const postCall = fetchMock.mock.calls.find(
            ([input, init]) => String(input) === '/api/mcp/capabilities' && init?.method === 'POST',
        );
        expect(postCall).toBeDefined();
        const body = JSON.parse(String((postCall![1] as RequestInit).body));
        expect(body.url).toBe('https://mcp.example.com/sse');
        expect(body).not.toHaveProperty('sseUrl');
    });
});
