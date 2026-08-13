import { beforeEach, describe, expect, it } from 'vitest';
import { useWorkbenchViewStore } from './workbenchViewStore';

describe('workbenchViewStore view boundary', () => {
    beforeEach(() => {
        useWorkbenchViewStore.setState({
            activeSessionId: null,
            defaultView: 'simple',
            viewMode: 'simple',
            pendingMessageId: null,
        });
    });

    it('opens an exact message in the developer view and consumes the target once', () => {
        useWorkbenchViewStore.getState().setActiveSession('session-a');
        useWorkbenchViewStore.getState().openMessageInDevelopment('message-42');

        expect(useWorkbenchViewStore.getState()).toMatchObject({
            viewMode: 'development',
            pendingMessageId: 'message-42',
        });

        useWorkbenchViewStore.getState().consumePendingMessage();
        expect(useWorkbenchViewStore.getState().pendingMessageId).toBeNull();
    });
});
