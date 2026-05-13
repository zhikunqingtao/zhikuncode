import { Page, expect } from '@playwright/test';
import path from 'path';
import fs from 'fs';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const SCREENSHOT_DIR = path.resolve(__dirname, '../../../docs/test-results/screenshots/apos-phase1');

// ══════════════════════════════════════════════════════════════
// Navigation & Page Ready
// ══════════════════════════════════════════════════════════════

/**
 * 等待页面加载完成（含 WebSocket 连接就绪）
 */
export async function waitForAppReady(page: Page): Promise<void> {
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(2000);
}

/**
 * 导航到 APOS Activity Tab
 */
export async function navigateToAPOSTab(page: Page): Promise<void> {
  const activityTab = page.locator('button[title="Activity"]');
  await expect(activityTab).toBeVisible({ timeout: 10000 });
  await activityTab.click();
  await page.waitForTimeout(800);
}

/**
 * 等待 Activity 数据加载（等待 activityStore 有数据）
 */
export async function waitForActivities(page: Page, minCount = 1, timeout = 30000): Promise<void> {
  await page.waitForFunction(
    async ({ min }) => {
      try {
        const mod = await import('/src/store/activityStore.ts');
        const store = (mod as any).useActivityStore;
        return store.getState().activities.size >= min;
      } catch {
        return false;
      }
    },
    { min: minCount },
    { timeout },
  );
}

/**
 * 展开指定 Activity 卡片（点击 L1 → 显示 L2）
 */
export async function expandActivityCard(page: Page, index = 0): Promise<void> {
  const cards = page.locator('[data-testid="activity-card-l1"]');
  await expect(cards.nth(index)).toBeVisible({ timeout: 5000 });
  await cards.nth(index).click();
  await page.waitForTimeout(400);
}

/**
 * 获取 SignalBadge 元素
 */
export function getSignalBadge(page: Page, cardIndex = 0) {
  const cards = page.locator('[data-testid="activity-card-l1"]');
  return cards.nth(cardIndex).locator('span.inline-flex.items-center.rounded-full');
}

/**
 * 注入 Mock 数据模式（通过 Feature Flag Store）
 */
export async function enableMockMode(page: Page): Promise<void> {
  await page.evaluate(async () => {
    const mod = await import('/src/store/featureFlagStore.ts');
    const store = (mod as any).useFeatureFlagStore;
    store.getState().setFlag('APOS_USE_MOCK', true);
  });
  await page.waitForTimeout(500);
}

/**
 * 注入 Feature Flags
 */
export async function injectFeatureFlags(
  page: Page,
  flags: Record<string, boolean>,
): Promise<void> {
  await page.evaluate(async (injectedFlags) => {
    const mod = await import('/src/store/featureFlagStore.ts');
    const store = (mod as any).useFeatureFlagStore;
    const currentFlags = store.getState().flags;
    store.setState({ flags: { ...currentFlags, ...injectedFlags } });
  }, flags);
  await page.waitForTimeout(300);
}

/**
 * 注入 Activity 数据到 Store
 */
export async function injectActivityData(
  page: Page,
  activities: unknown[],
): Promise<void> {
  await page.evaluate(async (injectedActivities) => {
    const mod = await import('/src/store/activityStore.ts');
    const store = (mod as any).useActivityStore;
    for (const activity of injectedActivities as Array<{ id: string; [k: string]: unknown }>) {
      store.getState().addActivity(activity);
    }
  }, activities);
  await page.waitForTimeout(300);
}

/**
 * 设置批量模式
 */
export async function setBatchMode(page: Page, enabled: boolean): Promise<void> {
  await page.evaluate(async (isEnabled) => {
    const mod = await import('/src/store/activityStore.ts');
    const store = (mod as any).useActivityStore;
    store.getState().setBatchMode(isEnabled);
  }, enabled);
  await page.waitForTimeout(300);
}

/**
 * 获取 Activity Store 状态
 */
export async function getActivityStoreState(page: Page): Promise<any> {
  return page.evaluate(async () => {
    const mod = await import('/src/store/activityStore.ts');
    const store = (mod as any).useActivityStore;
    const state = store.getState();
    return {
      activitiesCount: state.activities.size,
      expandedId: state.expandedId,
      l3ActivityId: state.l3ActivityId,
      batchMode: state.batchMode,
      selectedCount: state.selectedIds.size,
    };
  });
}

/**
 * 获取 Feature Flag Store 状态
 */
export async function getFeatureFlagState(page: Page): Promise<Record<string, boolean>> {
  return page.evaluate(async () => {
    const mod = await import('/src/store/featureFlagStore.ts');
    const store = (mod as any).useFeatureFlagStore;
    return { ...store.getState().flags };
  });
}

// ══════════════════════════════════════════════════════════════
// Mock Data Factory
// ══════════════════════════════════════════════════════════════

export function createMockActivity(overrides: Record<string, unknown> = {}) {
  const now = Date.now();
  return {
    id: `test-activity-${Math.random().toString(36).slice(2, 10)}`,
    sessionId: 'test-session',
    operationType: 'file_edit',
    summary: 'Test activity',
    status: 'completed',
    timestamp: now,
    duration: 1500,
    fileCount: 1,
    changedFiles: [{ filePath: 'src/test.ts', changeType: 'modified' }],
    insight: {
      signal: 'auto_approve',
      summary: 'All checks passed',
      verificationStatus: 'all_pass',
    },
    ...overrides,
  };
}

export function createMockActivities() {
  const now = Date.now();
  return [
    createMockActivity({
      id: 'mock-activity-006',
      operationType: 'config_change',
      summary: '新增 WebSocket verify_progress 消息类型',
      timestamp: now - 1000,
      fileCount: 3,
      duration: 2100,
      changedFiles: [
        { filePath: 'src/api/stompClient.ts', changeType: 'modified' },
        { filePath: 'src/api/dispatch.ts', changeType: 'modified' },
        { filePath: 'src/types/apos.ts', changeType: 'modified' },
      ],
      insight: { signal: 'review_recommended', summary: '新增消息类型需要审查', verificationStatus: 'all_pass' },
    }),
    createMockActivity({
      id: 'mock-activity-005',
      operationType: 'dependency',
      summary: '更新 package.json 依赖版本',
      timestamp: now - 60000,
      fileCount: 1,
      duration: 1800,
      changedFiles: [{ filePath: 'package.json', changeType: 'modified' }],
      insight: { signal: 'auto_approve', summary: '依赖更新无风险', verificationStatus: 'all_pass' },
    }),
    createMockActivity({
      id: 'mock-activity-004',
      operationType: 'delete',
      summary: '删除 AuthController 核心模块',
      timestamp: now - 120000,
      fileCount: 1,
      duration: 500,
      changedFiles: [{ filePath: 'src/controllers/AuthController.ts', changeType: 'deleted' }],
      insight: { signal: 'blocked', summary: '删除核心认证模块将导致 12 个测试失败', verificationStatus: 'has_error' },
    }),
    createMockActivity({
      id: 'mock-activity-003',
      operationType: 'command_execute',
      summary: '数据库迁移脚本 v2.3',
      timestamp: now - 180000,
      fileCount: 2,
      duration: 3500,
      changedFiles: [
        { filePath: 'migrations/v2.3.sql', changeType: 'added' },
        { filePath: 'config/database.yml', changeType: 'modified' },
      ],
      insight: { signal: 'manual_required', summary: '数据库迁移需手动确认', verificationStatus: 'pending' },
      toolResult: { exitCode: 0, output: 'Migration completed' },
    }),
    createMockActivity({
      id: 'mock-activity-002',
      operationType: 'refactor',
      summary: '重构 UserService 认证逻辑',
      timestamp: now - 240000,
      fileCount: 4,
      duration: 4500,
      changedFiles: [
        { filePath: 'src/services/UserService.ts', changeType: 'modified' },
        { filePath: 'src/services/AuthProvider.ts', changeType: 'modified' },
        { filePath: 'src/types/auth.ts', changeType: 'modified' },
        { filePath: 'tests/UserService.test.ts', changeType: 'modified' },
      ],
      insight: { signal: 'review_recommended', summary: '认证逻辑重构涉及 2 个公开 API，建议审查', verificationStatus: 'has_warning' },
    }),
    createMockActivity({
      id: 'mock-activity-001',
      operationType: 'file_edit',
      summary: '修复 typo in README.md',
      timestamp: now - 300000,
      fileCount: 1,
      duration: 1200,
      changedFiles: [{ filePath: 'README.md', changeType: 'modified' }],
      insight: { signal: 'auto_approve', summary: '仅修改文档 typo，无代码逻辑变更', verificationStatus: 'all_pass' },
    }),
  ];
}

// ══════════════════════════════════════════════════════════════
// Screenshot Helpers
// ══════════════════════════════════════════════════════════════

/**
 * 截图辅助函数
 */
export async function takeTestScreenshot(
  page: Page,
  testId: string,
  step: string,
): Promise<string> {
  fs.mkdirSync(SCREENSHOT_DIR, { recursive: true });
  const fileName = `${testId}-${step}.png`;
  const filePath = path.join(SCREENSHOT_DIR, fileName);
  await page.screenshot({ path: filePath, fullPage: false });
  return filePath;
}
