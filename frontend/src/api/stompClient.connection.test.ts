import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

let clientConfig: Record<string, unknown> | undefined;
let connected = false;

const mockClient = {
    activate: vi.fn(),
    deactivate: vi.fn(),
    publish: vi.fn(),
    subscribe: vi.fn(() => ({ unsubscribe: vi.fn() })),
    active: true,
    get connected() {
        return connected;
    },
};

vi.mock('@stomp/stompjs', () => ({
    Client: vi.fn().mockImplementation((config: Record<string, unknown>) => {
        clientConfig = config;
        return mockClient;
    }),
}));

vi.mock('sockjs-client', () => ({ default: vi.fn() }));

import {
    createStompClient,
    disconnectStomp,
    waitForWsConnection,
} from './stompClient';

describe('STOMP connection readiness', () => {
    beforeEach(() => {
        connected = false;
        clientConfig = undefined;
        vi.clearAllMocks();
    });

    afterEach(() => {
        disconnectStomp();
    });

    it('resumes waiters only after the user queue is subscribed', async () => {
        const readiness = waitForWsConnection();
        let settled = false;
        void readiness.then(() => { settled = true; });

        createStompClient('', '');
        expect(settled).toBe(false);

        connected = true;
        const onConnect = clientConfig?.onConnect as (() => void) | undefined;
        expect(onConnect).toBeTypeOf('function');
        onConnect?.();
        await readiness;

        expect(mockClient.subscribe).toHaveBeenCalledWith(
            '/user/queue/messages',
            expect.any(Function),
        );
        expect(settled).toBe(true);
    });

    it('allows a superseded caller to cancel its connection wait', async () => {
        const controller = new AbortController();
        const readiness = waitForWsConnection(controller.signal);

        controller.abort();

        await expect(readiness).rejects.toMatchObject({
            name: 'AbortError',
        });
    });
});
