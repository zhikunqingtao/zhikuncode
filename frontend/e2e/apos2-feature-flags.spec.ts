import { test, expect } from '@playwright/test';
import {
  waitForAppReady,
  navigateToAPOSTab,
  injectFeatureFlags,
  clearAllStoreData,
  takeTestScreenshot,
} from './helpers/apos2-helpers';
import { createPhase2Flags } from './helpers/apos2-data-factory';

/**
 * APOS Phase 2 — Feature Flags (TC-APOS2-038 ~ 042)
 *
 * 覆盖 FeatureFlagPanel 的面板展示、开关切换、依赖级联、重置、
 * 以及 Flag 控制组件可见性等核心功能。
 *
 * 源码依赖：
 *   - frontend/src/components/apos/FeatureFlagPanel.tsx
 *   - frontend/src/store/featureFlagStore.ts
 *   - frontend/src/types/apos.ts (APOS_FLAG_DEFAULTS, APOS_FLAG_DEPENDENCIES)
 *   - frontend/src/components/apos/ActivityStream.tsx (面板显隐)
 */

// ──────────────────────────────────────────────────────────────
// Constants aligned with source code
// ──────────────────────────────────────────────────────────────

/** 8 个 Flag 的显示名称，顺序与 APOS_FLAG_DEFAULTS key 顺序一致 */
const ALL_FLAG_LABELS = [
  'Activity Stream',
  'AI Insight',
  'Batch Review',
  'Risk Heatmap',
  'Change Impact',
  'Agent Pipeline',
  'Anomaly Alert',
  'Mobile Status',
] as const;

/** Flag key → 描述（来自 FeatureFlagPanel.tsx FLAG_DESCRIPTIONS） */
const FLAG_DESCRIPTIONS: Record<string, string> = {
  APOS_CHANGE_IMPACT: '变更影响全景面板（需先启用活动流）',
  APOS_AGENT_PIPELINE: 'Agent Pipeline 多 Worker 可视化（需先启用活动流）',
  APOS_ANOMALY_ALERT: '异常告警面板（需先启用 Agent Pipeline）',
  APOS_MOBILE_STATUS: '移动端底部状态栏（需先启用活动流）',
};

/** 默认值映射（与 APOS_FLAG_DEFAULTS 一致） */
const FLAG_DEFAULTS: Record<string, boolean> = {
  APOS_ACTIVITY_STREAM: true,
  APOS_AI_INSIGHT: true,
  APOS_BATCH_REVIEW: true,
  APOS_RISK_HEATMAP: false,
  APOS_CHANGE_IMPACT: true,
  APOS_AGENT_PIPELINE: true,
  APOS_ANOMALY_ALERT: true,
  APOS_MOBILE_STATUS: true,
};

// ──────────────────────────────────────────────────────────────
// Helpers
// ──────────────────────────────────────────────────────────────

/**
 * 定位 FeatureFlagPanel 容器（通过 Header "Feature Flags" 文本所在的外层 div）
 */
function getFeatureFlagPanel(page: import('@playwright/test').Page) {
  return page.locator('span:text("Feature Flags")').locator('xpath=ancestor::div[contains(@class,"rounded-lg")]').first();
}

/**
 * 定位特定 Flag 行：匹配 FLAG_LABELS 中的文本
 */
function getFlagRow(page: import('@playwright/test').Page, label: string) {
  // 每一行是 divide-y 下的 flex 容器，内含 label 文本
  return page
    .locator(`div.text-xs.font-medium:text-is("${label}")`)
    .locator('xpath=ancestor::div[contains(@class,"flex") and contains(@class,"items-center") and contains(@class,"justify-between")]')
    .first();
}

/**
 * 获取某 Flag 行内的 toggle 按钮
 */
function getFlagToggle(page: import('@playwright/test').Page, label: string) {
  return getFlagRow(page, label).locator('button').last();
}

// ──────────────────────────────────────────────────────────────
// Test Suite
// ──────────────────────────────────────────────────────────────

test.describe('APOS Phase 2 - Feature Flags (TC-APOS2-038~042)', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/');
    await waitForAppReady(page);
    await navigateToAPOSTab(page);
    // 确保 Flag 为默认状态
    await clearAllStoreData(page);
    // 重新注入默认 Flags（clearAll 已 resetToDefaults，这里显式保障）
    await injectFeatureFlags(page, createPhase2Flags());
  });

  // ════════════════════════════════════════════════════════════
  // TC-APOS2-038: Phase 2 Feature Flag 面板展示（8 个 Flag 全部显示）
  // ════════════════════════════════════════════════════════════
  test('TC-APOS2-038: Feature Flag 面板展示所有 Phase 2 Flag', async ({ page }) => {
    // Step 1 — 面板可见，Header 包含 "Feature Flags" 标题 + "重置" 按钮
    const panel = getFeatureFlagPanel(page);
    await expect(panel).toBeVisible({ timeout: 5000 });

    const headerTitle = panel.locator('span:text("Feature Flags")');
    await expect(headerTitle).toBeVisible({ timeout: 3000 });

    const resetBtn = panel.locator('button:text("重置")');
    await expect(resetBtn).toBeVisible({ timeout: 3000 });

    await takeTestScreenshot(page, 'TC-APOS2-038', '01-panel');

    // Step 2 — 完整列出 8 个 Flag
    for (const label of ALL_FLAG_LABELS) {
      const flagLabel = panel.locator(`text="${label}"`).first();
      await expect(flagLabel).toBeVisible({ timeout: 3000 });
    }

    await takeTestScreenshot(page, 'TC-APOS2-038', '02-list');

    // Step 3 — 检查默认状态（enabled Flag toggle 应有 bg-blue-600 class）
    // Risk Heatmap 默认关闭 → toggle 不带 bg-blue-600
    const riskHeatmapToggle = getFlagToggle(page, 'Risk Heatmap');
    await expect(riskHeatmapToggle).not.toHaveClass(/bg-blue-600/, { timeout: 3000 });

    // Change Impact 默认开启 → toggle 带 bg-blue-600
    const changeImpactToggle = getFlagToggle(page, 'Change Impact');
    await expect(changeImpactToggle).toHaveClass(/bg-blue-600/, { timeout: 3000 });

    // Agent Pipeline 默认开启
    const agentPipelineToggle = getFlagToggle(page, 'Agent Pipeline');
    await expect(agentPipelineToggle).toHaveClass(/bg-blue-600/, { timeout: 3000 });

    // Anomaly Alert 默认开启
    const anomalyAlertToggle = getFlagToggle(page, 'Anomaly Alert');
    await expect(anomalyAlertToggle).toHaveClass(/bg-blue-600/, { timeout: 3000 });

    // Mobile Status 默认开启
    const mobileStatusToggle = getFlagToggle(page, 'Mobile Status');
    await expect(mobileStatusToggle).toHaveClass(/bg-blue-600/, { timeout: 3000 });

    // Step 4 — 检查 Phase 2 新增 Flag 的描述文本
    for (const [, desc] of Object.entries(FLAG_DESCRIPTIONS)) {
      const descEl = panel.locator(`text="${desc}"`).first();
      await expect(descEl).toBeVisible({ timeout: 3000 });
    }
  });

  // ════════════════════════════════════════════════════════════
  // TC-APOS2-039: Phase 2 新增 Flag 开关可交互切换 + 依赖级联
  // （合并原 TC-APOS2-039 开关切换 与测试文档中的依赖级联验证）
  // ════════════════════════════════════════════════════════════
  test('TC-APOS2-039: Phase 2 新增 Flag 开关可交互切换', async ({ page }) => {
    // 验证 Change Impact toggle 切换
    // 初始: enabled (bg-blue-600)
    const ciToggle = getFlagToggle(page, 'Change Impact');
    await expect(ciToggle).toHaveClass(/bg-blue-600/, { timeout: 3000 });

    // 关闭 Change Impact
    await ciToggle.click();
    await page.waitForTimeout(300);
    await expect(ciToggle).not.toHaveClass(/bg-blue-600/, { timeout: 3000 });

    // 重新开启
    await ciToggle.click();
    await page.waitForTimeout(300);
    await expect(ciToggle).toHaveClass(/bg-blue-600/, { timeout: 3000 });

    // 验证 Anomaly Alert toggle 切换（在 Agent Pipeline 之前，因为关闭 AP 会级联禁用 AA）
    const aaToggle = getFlagToggle(page, 'Anomaly Alert');
    await expect(aaToggle).toHaveClass(/bg-blue-600/, { timeout: 3000 });

    await aaToggle.click();
    await page.waitForTimeout(300);
    await expect(aaToggle).not.toHaveClass(/bg-blue-600/, { timeout: 3000 });

    await aaToggle.click();
    await page.waitForTimeout(300);
    await expect(aaToggle).toHaveClass(/bg-blue-600/, { timeout: 3000 });

    // 验证 Agent Pipeline toggle 切换
    // 注意：关闭 AP 会级联禁用 AA，重新开启 AP 后需手动重启 AA
    const apToggle = getFlagToggle(page, 'Agent Pipeline');
    await expect(apToggle).toHaveClass(/bg-blue-600/, { timeout: 3000 });

    // 关闭 Agent Pipeline
    await apToggle.click();
    await page.waitForTimeout(300);
    await expect(apToggle).not.toHaveClass(/bg-blue-600/, { timeout: 3000 });

    // 重新开启
    await apToggle.click();
    await page.waitForTimeout(300);
    await expect(apToggle).toHaveClass(/bg-blue-600/, { timeout: 3000 });

    // 验证 Mobile Status toggle 切换
    const msToggle = getFlagToggle(page, 'Mobile Status');
    await expect(msToggle).toHaveClass(/bg-blue-600/, { timeout: 3000 });

    await msToggle.click();
    await page.waitForTimeout(300);
    await expect(msToggle).not.toHaveClass(/bg-blue-600/, { timeout: 3000 });

    await msToggle.click();
    await page.waitForTimeout(300);
    await expect(msToggle).toHaveClass(/bg-blue-600/, { timeout: 3000 });

    await takeTestScreenshot(page, 'TC-APOS2-039', '01-toggle-verified');
  });

  // ════════════════════════════════════════════════════════════
  // TC-APOS2-040: Flag 依赖关系级联禁用
  // ════════════════════════════════════════════════════════════
  test('TC-APOS2-040: Flag 依赖关系级联禁用', async ({ page }) => {
    const panel = getFeatureFlagPanel(page);

    // Step 1 — 关闭 Agent Pipeline → Anomaly Alert 应被级联禁用
    const apToggle = getFlagToggle(page, 'Agent Pipeline');
    await apToggle.click();
    await page.waitForTimeout(500);

    await takeTestScreenshot(page, 'TC-APOS2-040', '01-disable-pipeline');

    // Step 2 — 验证 Anomaly Alert 被禁用
    const aaRow = getFlagRow(page, 'Anomaly Alert');
    // 行应有 opacity-50（依赖缺失造成的视觉禁用）
    await expect(aaRow).toHaveClass(/opacity-50/, { timeout: 3000 });

    // toggle 按钮应 disabled
    const aaToggle = getFlagToggle(page, 'Anomaly Alert');
    await expect(aaToggle).toBeDisabled({ timeout: 3000 });

    // 应显示依赖提示："需要先启用: APOS_AGENT_PIPELINE"
    const depHint = aaRow.locator('text=需要先启用');
    await expect(depHint).toBeVisible({ timeout: 3000 });
    await expect(aaRow.locator('text=APOS_AGENT_PIPELINE')).toBeVisible({ timeout: 3000 });

    await takeTestScreenshot(page, 'TC-APOS2-040', '02-cascade');

    // Step 3 — 关闭 Activity Stream → 所有依赖 Flag 全部级联禁用
    const asToggle = getFlagToggle(page, 'Activity Stream');
    await asToggle.click();
    await page.waitForTimeout(500);

    // Step 4 — 验证 AI Insight, Batch Review, Change Impact, Agent Pipeline, Mobile Status 全部禁用
    const dependentLabels = ['AI Insight', 'Batch Review', 'Change Impact', 'Agent Pipeline', 'Mobile Status'];
    for (const label of dependentLabels) {
      const row = getFlagRow(page, label);
      await expect(row).toHaveClass(/opacity-50/, { timeout: 3000 });

      const toggle = getFlagToggle(page, label);
      await expect(toggle).toBeDisabled({ timeout: 3000 });

      // 应显示 "需要先启用: APOS_ACTIVITY_STREAM"
      await expect(row.locator('text=APOS_ACTIVITY_STREAM')).toBeVisible({ timeout: 3000 });
    }

    // Step 5 — Anomaly Alert 同时因 APOS_AGENT_PIPELINE 被禁用而显示其依赖
    const aaRowAfter = getFlagRow(page, 'Anomaly Alert');
    await expect(aaRowAfter).toHaveClass(/opacity-50/, { timeout: 3000 });
    await expect(aaRowAfter.locator('text=APOS_AGENT_PIPELINE')).toBeVisible({ timeout: 3000 });

    await takeTestScreenshot(page, 'TC-APOS2-040', '03-all-disabled');
  });

  // ════════════════════════════════════════════════════════════
  // TC-APOS2-041: 重置按钮恢复默认值
  // ════════════════════════════════════════════════════════════
  test('TC-APOS2-041: 重置按钮恢复默认值', async ({ page }) => {
    // Step 1 — 修改多个 Flag 状态
    // 关闭 Change Impact
    const ciToggle = getFlagToggle(page, 'Change Impact');
    await ciToggle.click();
    await page.waitForTimeout(300);
    await expect(ciToggle).not.toHaveClass(/bg-blue-600/, { timeout: 3000 });

    // 关闭 Agent Pipeline（同时级联关闭 Anomaly Alert）
    const apToggle = getFlagToggle(page, 'Agent Pipeline');
    await apToggle.click();
    await page.waitForTimeout(300);
    await expect(apToggle).not.toHaveClass(/bg-blue-600/, { timeout: 3000 });

    // 确认 Anomaly Alert 已被级联禁用
    const aaRow = getFlagRow(page, 'Anomaly Alert');
    await expect(aaRow).toHaveClass(/opacity-50/, { timeout: 3000 });

    // Step 2 — 点击 "重置" 按钮
    const panel = getFeatureFlagPanel(page);
    const resetBtn = panel.locator('button:text("重置")');
    await resetBtn.click();
    await page.waitForTimeout(500);

    // Step 3 — 验证所有 Flag 恢复到 APOS_FLAG_DEFAULTS
    // 默认开启的 Flag（bg-blue-600）
    const enabledByDefault = [
      'Activity Stream', 'AI Insight', 'Batch Review',
      'Change Impact', 'Agent Pipeline', 'Anomaly Alert', 'Mobile Status',
    ];
    for (const label of enabledByDefault) {
      const toggle = getFlagToggle(page, label);
      await expect(toggle).toHaveClass(/bg-blue-600/, { timeout: 3000 });
    }

    // 默认关闭的 Flag
    const riskToggle = getFlagToggle(page, 'Risk Heatmap');
    await expect(riskToggle).not.toHaveClass(/bg-blue-600/, { timeout: 3000 });

    // 所有行不应有 opacity-50（无禁用态）
    for (const label of enabledByDefault) {
      const row = getFlagRow(page, label);
      await expect(row).not.toHaveClass(/opacity-50/, { timeout: 3000 });
    }

    await takeTestScreenshot(page, 'TC-APOS2-041', '01-reset');
  });

  // ════════════════════════════════════════════════════════════
  // TC-APOS2-042: Flag 控制组件可见性
  // ════════════════════════════════════════════════════════════
  test('TC-APOS2-042: Flag 控制组件可见性', async ({ page }) => {
    // ── Part A: APOS_CHANGE_IMPACT 控制 "变更影响全景" 面板 ──

    // Step 1 — 初始状态: "变更影响全景" details 区域可见
    const changeImpactSection = page.locator('summary:has-text("变更影响全景")');
    await expect(changeImpactSection).toBeVisible({ timeout: 5000 });

    await takeTestScreenshot(page, 'TC-APOS2-042', '01-change-impact-visible');

    // Step 2 — 关闭 Change Impact Flag → "变更影响全景" 区域消失
    const ciToggle = getFlagToggle(page, 'Change Impact');
    await ciToggle.click();
    await page.waitForTimeout(500);

    await expect(changeImpactSection).not.toBeVisible({ timeout: 5000 });

    await takeTestScreenshot(page, 'TC-APOS2-042', '02-change-impact-hidden');

    // Step 3 — 重新开启 → "变更影响全景" 区域再次显示
    await ciToggle.click();
    await page.waitForTimeout(500);
    await expect(changeImpactSection).toBeVisible({ timeout: 5000 });

    // ── Part B: APOS_AGENT_PIPELINE + APOS_ANOMALY_ALERT 联动 ──

    // Step 4 — 初始: "Agent Pipeline" 折叠区域可见
    const pipelineSection = page.locator('summary:has-text("Agent Pipeline")');
    await expect(pipelineSection).toBeVisible({ timeout: 5000 });

    await takeTestScreenshot(page, 'TC-APOS2-042', '03-pipeline-visible');

    // Step 5 — 关闭 Anomaly Alert（保持 Agent Pipeline 开启）
    // Pipeline 区域仍在，但 AnomalyAlertPanel 内容不可见
    const aaToggle = getFlagToggle(page, 'Anomaly Alert');
    await aaToggle.click();
    await page.waitForTimeout(500);

    // Pipeline summary 仍然可见
    await expect(pipelineSection).toBeVisible({ timeout: 3000 });

    await takeTestScreenshot(page, 'TC-APOS2-042', '04-pipeline-only');

    // Step 6 — 关闭 Agent Pipeline → 整个 "Agent Pipeline" 折叠区域消失
    const apToggle = getFlagToggle(page, 'Agent Pipeline');
    await apToggle.click();
    await page.waitForTimeout(500);

    await expect(pipelineSection).not.toBeVisible({ timeout: 5000 });

    await takeTestScreenshot(page, 'TC-APOS2-042', '05-pipeline-hidden');
  });
});
