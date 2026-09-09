import type {
  FileReferenceCapability,
  PublishedLocalFile,
} from '@/types';

export async function loadFileReferenceCapability(): Promise<FileReferenceCapability> {
  const response = await fetch('/api/files/reference-capability');
  if (!response.ok) {
    throw new Error('无法获取本地文件引用能力');
  }
  const body = await response.json() as Partial<FileReferenceCapability>;
  if (body.mode !== 'native_path'
    && body.mode !== 'oss_upload'
    && body.mode !== 'unavailable') {
    throw new Error('服务端返回了无效的本地文件引用能力');
  }
  if (body.mode === 'oss_upload'
    && (typeof body.maxFileBytes !== 'number' || body.maxFileBytes < 1)) {
    throw new Error('服务端返回了无效的文件大小上限');
  }
  return {
    mode: body.mode,
    maxFileBytes: body.maxFileBytes,
    error: typeof body.error === 'string' ? body.error : undefined,
  };
}

export async function publishLocalFile(
  file: File,
  ensureSessionReady: () => Promise<string | null>,
): Promise<PublishedLocalFile> {
  const sessionId = await ensureSessionReady();
  if (!sessionId) throw new Error('无法准备会话，文件未上传');

  const response = await fetch(
    `/api/oss/local-files?fileName=${encodeURIComponent(file.name)}`,
    {
      method: 'POST',
      headers: {
        'X-Session-Id': sessionId,
        'Content-Type': file.type || 'application/octet-stream',
      },
      body: file,
    },
  );
  const body = await response.json().catch(() => ({})) as {
    artifactId?: unknown;
    fileName?: unknown;
    size?: unknown;
    sha256?: unknown;
    url?: unknown;
    mediaType?: unknown;
    error?: unknown;
  };
  const errorCode = typeof body.error === 'string' ? body.error : '';
  if (!response.ok) {
    if (response.status === 413 || errorCode === 'LOCAL_FILE_TOO_LARGE') {
      throw new Error('文件超过 OSS 单文件大小上限');
    }
    if (errorCode === 'OSS_CREDENTIALS_UNAVAILABLE'
      || errorCode === 'OSS_INSTANCE_ROLE_UNAVAILABLE'
      || errorCode === 'OSS_ECS_ROLE_REQUIRED'
      || errorCode === 'OSS_CREDENTIAL_SOURCE_FORBIDDEN') {
      throw new Error('OSS 凭据不可用，文件未上传');
    }
    if (errorCode === 'SESSION_ACCESS_DENIED') {
      throw new Error('当前会话无权上传该文件');
    }
    throw new Error(`本地文件上传 OSS 失败${errorCode ? `：${errorCode}` : ''}`);
  }
  if (typeof body.artifactId !== 'string'
    || typeof body.fileName !== 'string'
    || typeof body.size !== 'number'
    || typeof body.sha256 !== 'string'
    || typeof body.url !== 'string'
    || typeof body.mediaType !== 'string') {
    throw new Error('服务端返回了无效的 OSS 文件信息');
  }
  return {
    artifactId: body.artifactId,
    name: body.fileName,
    size: body.size,
    sha256: body.sha256,
    url: body.url,
    mediaType: body.mediaType,
  };
}
