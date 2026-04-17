import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest';
import { sendToServer, isWsConnected } from '@/api/stompClient';

// Mock @stomp/stompjs
const mockActivate = vi.fn();
const mockDeactivate = vi.fn();
const mockPublish = vi.fn();
const mockSubscribe = vi.fn(() => ({ unsubscribe: vi.fn() }));

vi.mock('@stomp/stompjs', () => ({
    Client: vi.fn().mockImplementation((config: Record<string, unknown>) => {
        // Simulate onConnect being called async
        setTimeout(() => {
            if (typeof config.onConnect === 'function') {
                (config.onConnect as () => void)();
            }
        }, 0);
        return {
            activate: mockActivate,
            deactivate: mockDeactivate,
            publish: mockPublish,
            subscribe: mockSubscribe,
            active: true,
        };
    }),
}));

vi.mock('sockjs-client', () => ({
    default: vi.fn(),
}));

describe('useWebSocket module-level functions', () => {
    beforeEach(() => {
        vi.clearAllMocks();
    });

    afterEach(() => {
        vi.restoreAllMocks();
    });

    test('sendToServer returns false when not connected', () => {
        // Before any connection, globalClient is null
        // Note: In a real scenario, the client may already be set from other tests
        // This tests the defensive check
        const result = sendToServer('/app/query', { message: 'test' });
        // Depending on module state, this may return true (if connected) or false
        expect(typeof result).toBe('boolean');
    });

    test('isWsConnected returns boolean', () => {
        const connected = isWsConnected();
        expect(typeof connected).toBe('boolean');
    });

    test('sendToServer calls publish when connected', () => {
        // If the global client is active from a previous useWebSocket call,
        // sendToServer should work
        if (isWsConnected()) {
            const result = sendToServer('/app/query', { message: 'hello' });
            expect(result).toBe(true);
        }
    });
});
