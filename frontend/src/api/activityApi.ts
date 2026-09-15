import { sendToServer } from './stompClient';
import type { ActivityData } from '@/types/apos';

/**
 * 保存完整 Activity 到后端（创建或全量更新）
 */
export function saveActivity(activity: ActivityData): void {
  try {
    sendToServer('/app/activity-save', activity);
  } catch (e) {
    console.warn('[ActivityAPI] save failed:', e);
  }
}

/**
 * 更新 Activity 的 decision 字段
 */
export async function updateActivityDecision(id: string, decision: 'approved' | 'rejected', sessionId: string): Promise<void> {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 15_000);
  try {
    const response = await fetch(`/api/sessions/${encodeURIComponent(sessionId)}/activities/${encodeURIComponent(id)}/decision`, {
      method: 'PUT', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ decision }), signal: controller.signal,
    });
    if (!response.ok) throw new Error(`保存失败（HTTP ${response.status}）`);
    const result = await response.json();
    if (result.id !== id || result.sessionId !== sessionId || result.decision !== decision) {
      throw new Error('服务端确认与当前操作不一致');
    }
  } finally { clearTimeout(timeout); }
}

/**
 * 更新 Activity 的 insight 字段
 */
export function updateActivityInsight(id: string, insight: ActivityData['insight']): void {
  try {
    sendToServer('/app/activity-update', { id, insight });
  } catch (e) {
    console.warn('[ActivityAPI] updateInsight failed:', e);
  }
}
