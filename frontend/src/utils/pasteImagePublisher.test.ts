/**
 * pasteImagePublisher 契约锁定测试
 *
 * 锁定降级触发条件与 OSS 上传路径行为:
 * - configured:false / status 请求失败 / 非 ok / 非 JSON → { mode: 'base64' }
 * - configured:true → 走 OSS 上传（X-Session-Id 头、逐张 POST）返回 { mode: 'oss', items }
 * - 会话不可用与 OSS 凭据错误分支的中文文案保持不变
 */

import { afterEach, describe, expect, it, vi } from 'vitest';
import { publishPastedImages } from './pasteImagePublisher';

function jsonResponse(body: unknown, ok = true): Response {
    return {
        ok,
        json: () => Promise.resolve(body),
    } as Response;
}

function brokenJsonResponse(ok = true): Response {
    return {
        ok,
        json: () => Promise.reject(new SyntaxError('Unexpected token')),
    } as Response;
}

const pngFile = (name = 'clipboard.png') =>
    new File([new Uint8Array(8)], name, { type: 'image/png' });

describe('publishPastedImages', () => {
    afterEach(() => {
        vi.unstubAllGlobals();
        vi.restoreAllMocks();
    });

    it('returns base64 mode when OSS status reports configured:false', async () => {
        const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ configured: false }));
        vi.stubGlobal('fetch', fetchMock);
        const ensureSessionReady = vi.fn().mockResolvedValue('session-1');

        const result = await publishPastedImages([pngFile()], ensureSessionReady);

        expect(result).toEqual({ mode: 'base64' });
        expect(fetchMock).toHaveBeenCalledTimes(1);
        expect(fetchMock).toHaveBeenCalledWith('/api/oss/status');
        // 降级路径不应准备会话，也不应发起任何上传请求
        expect(ensureSessionReady).not.toHaveBeenCalled();
    });

    it('returns base64 mode when the OSS status request rejects (network error)', async () => {
        const fetchMock = vi.fn().mockRejectedValue(new TypeError('Failed to fetch'));
        vi.stubGlobal('fetch', fetchMock);
        const ensureSessionReady = vi.fn().mockResolvedValue('session-1');

        await expect(publishPastedImages([pngFile()], ensureSessionReady))
            .resolves.toEqual({ mode: 'base64' });
        expect(ensureSessionReady).not.toHaveBeenCalled();
    });

    it('returns base64 mode when the OSS status response is not ok', async () => {
        vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({ error: 'boom' }, false)));

        await expect(publishPastedImages([pngFile()], vi.fn().mockResolvedValue('session-1')))
            .resolves.toEqual({ mode: 'base64' });
    });

    it('returns base64 mode when the OSS status body is not valid JSON', async () => {
        vi.stubGlobal('fetch', vi.fn().mockResolvedValue(brokenJsonResponse()));

        await expect(publishPastedImages([pngFile()], vi.fn().mockResolvedValue('session-1')))
            .resolves.toEqual({ mode: 'base64' });
    });

    it('uploads through OSS with the session header when configured:true', async () => {
        const fetchMock = vi.fn()
            .mockResolvedValueOnce(jsonResponse({ configured: true }))
            .mockResolvedValueOnce(jsonResponse({
                fileName: 'a-on-oss.png',
                size: 16,
                mediaType: 'image/png',
                url: 'https://bucket.oss-cn-beijing.aliyuncs.com/prefix/clipboard/a.png',
            }))
            .mockResolvedValueOnce(jsonResponse({
                url: 'https://bucket.oss-cn-beijing.aliyuncs.com/prefix/clipboard/b.png',
            }));
        vi.stubGlobal('fetch', fetchMock);
        const ensureSessionReady = vi.fn().mockResolvedValue('session-42');
        const fileA = pngFile('a.png');
        const fileB = pngFile('b.png');

        const result = await publishPastedImages([fileA, fileB], ensureSessionReady);

        expect(ensureSessionReady).toHaveBeenCalledTimes(1);
        expect(fetchMock).toHaveBeenCalledTimes(3);
        const [uploadUrl, uploadInit] = fetchMock.mock.calls[1] as [string, RequestInit];
        expect(uploadUrl).toBe('/api/oss/clipboard-images');
        expect(uploadInit.method).toBe('POST');
        expect(uploadInit.headers).toEqual({ 'X-Session-Id': 'session-42' });
        expect(uploadInit.body).toBeInstanceOf(FormData);
        expect((uploadInit.body as FormData).get('file')).toBeInstanceOf(File);
        expect(result).toEqual({
            mode: 'oss',
            items: [
                {
                    name: 'a-on-oss.png',
                    size: 16,
                    mediaType: 'image/png',
                    url: 'https://bucket.oss-cn-beijing.aliyuncs.com/prefix/clipboard/a.png',
                },
                {
                    // 后端未回填的字段回落到本地 File 信息
                    name: 'b.png',
                    size: 8,
                    mediaType: 'image/png',
                    url: 'https://bucket.oss-cn-beijing.aliyuncs.com/prefix/clipboard/b.png',
                },
            ],
        });
    });

    it('throws when the session cannot be prepared while OSS is configured', async () => {
        vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({ configured: true })));

        await expect(publishPastedImages([pngFile()], vi.fn().mockResolvedValue(null)))
            .rejects.toThrow('无法准备会话，图片未上传');
    });

    it('throws the credential-specific message on OSS credential errors', async () => {
        vi.stubGlobal('fetch', vi.fn()
            .mockResolvedValueOnce(jsonResponse({ configured: true }))
            .mockResolvedValueOnce(jsonResponse({ error: 'OSS_CREDENTIALS_UNAVAILABLE' }, false)));

        await expect(publishPastedImages([pngFile()], vi.fn().mockResolvedValue('session-1')))
            .rejects.toThrow('OSS 凭据不可用，无法粘贴图片。请先在 .env 配置本地凭据，或为 ECS 绑定可用的 RAM 角色。');
    });

    it('throws the generic upload failure message with the backend error code', async () => {
        vi.stubGlobal('fetch', vi.fn()
            .mockResolvedValueOnce(jsonResponse({ configured: true }))
            .mockResolvedValueOnce(jsonResponse({ error: 'OSS_UPLOAD_TIMEOUT' }, false)));

        await expect(publishPastedImages([pngFile()], vi.fn().mockResolvedValue('session-1')))
            .rejects.toThrow('粘贴图片上传 OSS 失败：OSS_UPLOAD_TIMEOUT');
    });
});
