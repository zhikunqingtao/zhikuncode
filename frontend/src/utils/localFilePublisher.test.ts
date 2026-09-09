import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  loadFileReferenceCapability,
  publishLocalFile,
} from './localFilePublisher';

describe('localFilePublisher', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('loads the request-aware OSS upload capability', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({
      mode: 'oss_upload',
      maxFileBytes: 104857600,
    }), { status: 200 })));

    await expect(loadFileReferenceCapability()).resolves.toEqual({
      mode: 'oss_upload',
      maxFileBytes: 104857600,
      error: undefined,
    });
  });

  it('uploads the raw File body with session and media metadata', async () => {
    const responseBody = {
      artifactId: 'local-1',
      fileName: '说明.txt',
      size: 5,
      sha256: 'abc123',
      url: 'https://bucket.oss-cn-beijing.aliyuncs.com/prefix/local-files/file.txt',
      mediaType: 'text/plain',
    };
    const fetchMock = vi.fn().mockResolvedValue(new Response(
      JSON.stringify(responseBody), { status: 201 },
    ));
    vi.stubGlobal('fetch', fetchMock);
    const file = new File(['hello'], '说明.txt', { type: 'text/plain' });

    await expect(publishLocalFile(file, vi.fn().mockResolvedValue('session-a')))
      .resolves.toEqual({
        artifactId: 'local-1',
        name: '说明.txt',
        size: 5,
        sha256: 'abc123',
        url: responseBody.url,
        mediaType: 'text/plain',
      });
    expect(fetchMock).toHaveBeenCalledWith(
      `/api/oss/local-files?fileName=${encodeURIComponent('说明.txt')}`,
      {
        method: 'POST',
        headers: {
          'X-Session-Id': 'session-a',
          'Content-Type': 'text/plain',
        },
        body: file,
      },
    );
  });

  it('maps the streamed size rejection to a stable user-facing error', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(
      JSON.stringify({ error: 'LOCAL_FILE_TOO_LARGE' }), { status: 413 },
    )));

    await expect(publishLocalFile(
      new File(['large'], 'large.bin'), vi.fn().mockResolvedValue('session-a'),
    )).rejects.toThrow('文件超过 OSS 单文件大小上限');
  });
});
