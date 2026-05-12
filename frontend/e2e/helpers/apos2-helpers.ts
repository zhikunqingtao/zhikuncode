import { Page, expect } from '@playwright/test';
import path from 'path';
import fs from 'fs';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const SCREENSHOT_DIR = path.resolve(__dirname, '../../../docs/test-results/screenshots/apos-phase2');

// ══════════════════════════════════════════════════════════════
// Navigation & Page Ready
// ══════════════════════════════════════════════════════════════

/**
 * 等待页面加载完成（含 WebSocket 连接就绪）
 */
export async function waitForAppReady(page: Page): Promise<void> {
  await page.waitForLoadState('networkidle');
  // 等待 React hydration + WebSocket STOMP 连接建立
  await page.waitForTimeout(2000);
}

/**
 * 导航到 APOS Activity Tab
 */
export async function navigateToAPOSTab(page: Page): Promise<void> {
  const activityTab = page.locator('button[title="Activity"]');
  await expect(activityTab).toBeVisible({ timeout: 5000 });
  await activityTab.click();
  // 等待 ActivityStream 挂载并渲染
  await page.waitForTimeout(800);
}

/**
 * 导航到 Sidebar 的 DAG Tab
 */
export async function navigateToDAGTab(page: Page): Promise<void> {
  const dagTab = page.locator('button[title="DAG"]');
  await expect(dagTab).toBeVisible({ timeout: 5000 });
  await dagTab.click();
  // 等待 AgentDAGChart 挂载并渲染
  await page.waitForTimeout(800);
}

/**
 * 移动端模式下打开 Drawer（点击 Header 菜单按钮）
 */
export async function openMobileDrawer(page: Page): Promise<void> {
  const menuButton = page.locator('button[aria-label="打开侧边栏"]');
  await expect(menuButton).toBeVisible({ timeout: 5000 });
  await menuButton.click();
  await page.waitForTimeout(500);
}

// ══════════════════════════════════════════════════════════════
// Store Injection — 通过 Vite dynamic import 注入 Zustand store
// ══════════════════════════════════════════════════════════════

/**
 * 注入 ChangeImpact 聚合 Store 数据
 * 使用 useChangeImpactAggStore 的 setState 直接设置聚合变更和风险摘要
 */
export async function injectChangeImpactData(
  page: Page,
  data: {
    aggregatedChanges?: unknown[];
    riskSummary?: Record<string, unknown>;
    selectedFilePath?: string | null;
  },
): Promise<void> {
  await page.evaluate(async (injectedData) => {
    const mod = await import('/src/store/changeImpactStore.ts');
    (mod as any).useChangeImpactAggStore.setState({
      ...injectedData,
    });
  }, data);
  await page.waitForTimeout(300);
}

/**
 * 注入 Anomaly Store 数据
 * 设置 activeAnomalies 和 resolvedHistory
 */
export async function injectAnomalyData(
  page: Page,
  data: {
    activeAnomalies?: unknown[];
    resolvedHistory?: unknown[];
  },
): Promise<void> {
  await page.evaluate(async (injectedData) => {
    // 使用 window.__anomalyStore__ 确保访问与组件相同的 store 实例
    const store = (window as any).__anomalyStore__;
    if (store) {
      store.setState({
        activeAnomalies: injectedData.activeAnomalies ?? [],
        resolvedHistory: injectedData.resolvedHistory ?? [],
      });
    }
  }, data);
  await page.waitForTimeout(300);
}

/**
 * 注入 Swarm Store 数据
 * 设置活跃 Swarm 实例、Workers 和面板状态
 */
export async function injectSwarmData(
  page: Page,
  data: {
    swarms?: Array<{ swarmId: string; [key: string]: unknown }>;
    activeSwarmId?: string | null;
    panelVisible?: boolean;
    logs?: unknown[];
  },
): Promise<void> {
  await page.evaluate(async (injectedData) => {
    const mod = await import('/src/store/swarmStore.ts');
    const store = (mod as any).useSwarmStore;
    const swarmsMap = new Map();
    if (injectedData.swarms) {
      for (const swarm of injectedData.swarms) {
        swarmsMap.set(swarm.swarmId, swarm);
      }
    }
    store.setState({
      swarms: swarmsMap,
      activeSwarmId: injectedData.activeSwarmId ?? null,
      panelVisible: injectedData.panelVisible ?? true,
      logs: injectedData.logs ?? [],
    });
  }, data);
  await page.waitForTimeout(300);
}

/**
 * 注入 Feature Flags
 * 通过 useFeatureFlagStore 设置所有 Flag 的开关状态
 */
export async function injectFeatureFlags(
  page: Page,
  flags: Record<string, boolean>,
): Promise<void> {
  await page.evaluate(async (injectedFlags) => {
    const mod = await import('/src/store/featureFlagStore.ts');
    const store = (mod as any).useFeatureFlagStore;
    const currentFlags = store.getState().flags;
    store.setState({
      flags: { ...currentFlags, ...injectedFlags },
    });
  }, flags);
  await page.waitForTimeout(300);
}

/**
 * 注入 Activity Store 数据
 * 批量添加 Activity 条目
 */
export async function injectActivityData(
  page: Page,
  activities: unknown[],
): Promise<void> {
  await page.evaluate(async (injectedActivities) => {
    const mod = await import('/src/store/activityStore.ts');
    const store = (mod as any).useActivityStore;
    const activitiesMap = new Map();
    for (const activity of injectedActivities as Array<{ id: string; [k: string]: unknown }>) {
      activitiesMap.set(activity.id, activity);
    }
    store.setState({ activities: activitiesMap });
  }, activities);
  await page.waitForTimeout(300);
}

// ══════════════════════════════════════════════════════════════
// Store Cleanup
// ══════════════════════════════════════════════════════════════

/**
 * 清理所有 Store 数据，恢复到初始状态
 */
export async function clearAllStoreData(page: Page): Promise<void> {
  await page.evaluate(async () => {
    // Activity Store
    const actMod = await import('/src/store/activityStore.ts');
    (actMod as any).useActivityStore.getState().clearAll();

    // ChangeImpact Agg Store
    const ciMod = await import('/src/store/changeImpactStore.ts');
    (ciMod as any).useChangeImpactAggStore.getState().clearAll();
    (ciMod as any).useChangeImpactStore.getState().reset();

    // Anomaly Store
    const anomalyStore = (window as any).__anomalyStore__;
    if (anomalyStore) {
      anomalyStore.setState({ activeAnomalies: [], resolvedHistory: [], cooldownMap: new Map() });
    }

    // Swarm Store
    const swMod = await import('/src/store/swarmStore.ts');
    (swMod as any).useSwarmStore.getState().clearAll();

    // Feature Flags → 重置为默认值
    const ffMod = await import('/src/store/featureFlagStore.ts');
    (ffMod as any).useFeatureFlagStore.getState().resetToDefaults();
  });
  await page.waitForTimeout(300);
}

// ══════════════════════════════════════════════════════════════
// UI Interaction Helpers
// ══════════════════════════════════════════════════════════════

/**
 * 展开/收起 details 折叠面板
 * @param panelTitle 面板标题文本（如 "变更影响全景"、"Agent Pipeline"）
 */
export async function toggleDetailsPanel(page: Page, panelTitle: string): Promise<void> {
  const panel = page.locator(`text=${panelTitle}`).first();
  await expect(panel).toBeVisible({ timeout: 5000 });
  await panel.click();
  await page.waitForTimeout(400);
}

/**
 * 等待 Store 数据更新
 * @param storeName Store 名称（activity | changeImpact | anomaly | swarm | featureFlag）
 * @param timeout 超时时间（毫秒）
 */
export async function waitForStoreUpdate(
  page: Page,
  storeName: string,
  timeout = 5000,
): Promise<void> {
  const storeImportMap: Record<string, { path: string; hook: string; check: string }> = {
    activity: {
      path: '/src/store/activityStore.ts',
      hook: 'useActivityStore',
      check: 'store.getState().activities.size > 0',
    },
    changeImpact: {
      path: '/src/store/changeImpactStore.ts',
      hook: 'useChangeImpactAggStore',
      check: 'store.getState().aggregatedChanges.length > 0',
    },
    anomaly: {
      path: '__window__',
      hook: '__anomalyStore__',
      check: 'store.getState().activeAnomalies.length > 0',
    },
    swarm: {
      path: '/src/store/swarmStore.ts',
      hook: 'useSwarmStore',
      check: 'store.getState().swarms.size > 0',
    },
    featureFlag: {
      path: '/src/store/featureFlagStore.ts',
      hook: 'useFeatureFlagStore',
      check: 'Object.keys(store.getState().flags).length > 0',
    },
  };

  const config = storeImportMap[storeName];
  if (!config) throw new Error(`Unknown store: ${storeName}`);

  await page.waitForFunction(
    async ({ storePath, hookName, checkExpr }) => {
      try {
        let store;
        if (storePath === '__window__') {
          store = (window as any)[hookName];
        } else {
          const mod = await import(storePath);
          store = (mod as any)[hookName];
        }
        if (!store) return false;
        return eval(checkExpr);
      } catch {
        return false;
      }
    },
    { storePath: config.path, hookName: config.hook, checkExpr: config.check },
    { timeout },
  );
}

// ══════════════════════════════════════════════════════════════
// Screenshot Helpers
// ══════════════════════════════════════════════════════════════

/**
 * 截图辅助函数，用于测试证据收集
 * @param testId 测试用例编号（如 "TC-APOS2-001"）
 * @param step 步骤描述（如 "01-empty-state"）
 * @returns 截图文件路径
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
