import { beforeEach, expect, it } from 'vitest';
import { bindSessionAndWait, dispatch, resetBoundSession } from '@/api/dispatch';
import { useMessageStore } from '../messageStore';
import type { Message } from '@/types';

const notice: Extract<Message, { type: 'system' }> = {
    type: 'system', uuid: 'image-notice', timestamp: 1, subtype: 'image_notice',
    content: '历史图片下载失败，本轮已省略，继续处理当前请求。',
};
beforeEach(() => { resetBoundSession(); useMessageStore.getState().clearMessages(); });

it('shows image notices immediately and deduplicates final committed replay', () => {
    dispatch({ type: 'system_message', message: notice });
    dispatch({ type: 'system_message', message: notice });
    expect(useMessageStore.getState().messages).toEqual([notice]);
    useMessageStore.getState().reconcileCommittedRun(null, [notice]);
    expect(useMessageStore.getState().messages).toEqual([notice]);
});

it('restores the persisted notice after reconnecting', async () => {
    let bindRequestId = ''; let bindingEpoch = 0;
    const bound = bindSessionAndWait('s1', payload => {
        bindRequestId = payload.bindRequestId; bindingEpoch = payload.bindingEpoch;
    });
    dispatch({ type: 'session_restored', bindRequestId, bindingEpoch, protocolVersion: 3,
        messages: [notice], metadata: { sessionId: 's1', model: 'kimi-k3', status: 'idle', permissionMode: 'default' },
    } as never);
    await expect(bound).resolves.toBe(true);
    expect(useMessageStore.getState().messages).toEqual([notice]);
});
