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
export function updateActivityDecision(id: string, decision: 'approved' | 'rejected'): void {
  try {
    sendToServer('/app/activity-update', { id, decision });
  } catch (e) {
    console.warn('[ActivityAPI] updateDecision failed:', e);
  }
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
