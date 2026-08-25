/**
 * pasteImagePublisher — 粘贴图片发布核心逻辑（从 App.tsx 抽取，便于单元测试锁定契约）
 *
 * 契约:
 * - OSS 未配置、/api/oss/status 请求失败/非 ok/非 JSON → 返回 { mode: 'base64' }，
 *   由 PromptInput 降级为与按钮/拖拽上传一致的 Base64 直传路径；
 * - configured=true → 逐张 POST /api/oss/clipboard-images（带 X-Session-Id 头），
 *   返回 { mode: 'oss', items }，行为与原 App.tsx 内联实现完全一致。
 */

import type { PastePublishResult, PublishedPastedImage } from '@/types';

export async function publishPastedImages(
  files: File[],
  ensureSessionReady: () => Promise<string | null>,
): Promise<PastePublishResult> {
  // OSS 未配置或状态查询失败（网络错误等）时，降级为与按钮/拖拽上传一致的
  // Base64 直传路径，保证粘贴图片功能始终可用；仅在确认已配置时走 OSS 上传。
  let configured = false;
  try {
    const statusResponse = await fetch('/api/oss/status');
    if (statusResponse.ok) {
      const status = await statusResponse.json() as {
        configured?: boolean;
        error?: string;
      };
      configured = status.configured === true;
    }
  } catch {
    configured = false;
  }
  if (!configured) {
    return { mode: 'base64' };
  }

  const currentSessionId = await ensureSessionReady();
  if (!currentSessionId) throw new Error('无法准备会话，图片未上传');

  const published: PublishedPastedImage[] = [];
  for (const file of files) {
    const form = new FormData();
    form.append('file', file, file.name || 'clipboard-image');
    const response = await fetch('/api/oss/clipboard-images', {
      method: 'POST',
      headers: { 'X-Session-Id': currentSessionId },
      body: form,
    });
    const body = await response.json().catch(() => ({})) as {
      fileName?: string;
      size?: number;
      mediaType?: string;
      url?: string;
      error?: string;
    };
    if (!response.ok || !body.url) {
      if (body.error === 'OSS_CREDENTIALS_UNAVAILABLE'
        || body.error === 'OSS_INSTANCE_ROLE_UNAVAILABLE'
        || body.error === 'OSS_ECS_ROLE_REQUIRED'
        || body.error === 'OSS_CREDENTIAL_SOURCE_FORBIDDEN') {
        throw new Error('OSS 凭据不可用，无法粘贴图片。请先在 .env 配置本地凭据，或为 ECS 绑定可用的 RAM 角色。');
      }
      throw new Error(`粘贴图片上传 OSS 失败${body.error ? `：${body.error}` : ''}`);
    }
    published.push({
      name: body.fileName || file.name || 'clipboard-image',
      size: body.size ?? file.size,
      mediaType: body.mediaType || file.type || 'image/png',
      url: body.url,
    });
  }
  return { mode: 'oss', items: published };
}
