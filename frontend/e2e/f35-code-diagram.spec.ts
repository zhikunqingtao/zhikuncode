import { test, expect, Page } from '@playwright/test';
import path from 'path';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const SCREENSHOT_DIR = path.resolve(__dirname, '../../docs/test-results/screenshots/visualization');
const PROJECT_ROOT = '/Users/guoqingtao/Desktop/dev/code/zhikuncode/python-service';

// ── Helper 函数 ──

async function screenshot(page: Page, name: string) {
  await page.screenshot({ path: path.join(SCREENSHOT_DIR, `${name}.png`), fullPage: true });
}

async function setupDesktopPage(page: Page) {
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto('/', { waitUntil: 'networkidle' });
  await page.waitForTimeout(2000);
}

async function clickSidebarTab(page: Page, label: string) {
  // 侧边栏 tab 按钮只有图标，文本在 title 属性中
  const tabButton = page.locator(`aside button[title="${label}"]`).first();
  await tabButton.click();
  await page.waitForTimeout(1000);
}

async function navigateToDiagramTab(page: Page) {
  await clickSidebarTab(page, '图表生成');
  // 等待 CodeDiagramGenerator 组件加载
  await page.waitForFunction(() => {
    const aside = document.querySelector('aside');
    if (!aside) return false;
    const text = aside.textContent || '';
    return text.includes('时序图') || text.includes('流程图');
  }, { timeout: 10000 }).catch(() => {});
  await page.waitForTimeout(500);
}

async function generateDiagram(page: Page, targetValue: string, projectRootValue: string, depthValue?: number) {
  // 输入 target
  const targetInput = page.locator('aside input').first();
  await targetInput.fill(targetValue);

  // 输入 projectRoot
  const projectRootInput = page.locator('aside input').nth(1);
  await projectRootInput.fill(projectRootValue);

  // 选择深度
  if (depthValue) {
    const depthBtn = page.locator('aside button').filter({ hasText: String(depthValue) }).first();
    await depthBtn.click();
    await page.waitForTimeout(200);
  }

  // 点击"生成图表"
  const genBtn = page.locator('aside button').filter({ hasText: '生成图表' }).first();
  await genBtn.click();

  // 等待 Loading 消失（animate-spin 消失 且 "生成中..." 消失）
  await page.waitForFunction(() => {
    const aside = document.querySelector('aside');
    if (!aside) return false;
    const spinner = aside.querySelector('.animate-spin');
    const text = aside.textContent || '';
    return !spinner && !text.includes('生成中...');
  }, { timeout: 55000 }).catch(() => {});

  // 等待结果完全渲染（置信度出现 或 错误出现）
  await page.waitForFunction(() => {
    const aside = document.querySelector('aside');
    if (!aside) return false;
    const text = aside.textContent || '';
    return text.includes('置信度') || text.includes('Error') || text.includes('HTTP') || text.includes('错误');
  }, { timeout: 15000 }).catch(() => {});
  await page.waitForTimeout(1000);
}

// ═══════════════════════════════════════════════════════════════
// 模块 1: F35 图表生成入口与基础 UI (5 TC)
// ═══════════════════════════════════════════════════════════════
test.describe('F35 图表生成入口与基础 UI', () => {

  test('TC-F35-01: Tab 切换与初始状态', async ({ page }) => {
    await setupDesktopPage(page);
    await navigateToDiagramTab(page);

    // 验证"时序图"和"流程图"按钮可见
    const sequenceBtn = page.locator('aside button').filter({ hasText: '时序图' }).first();
    const flowchartBtn = page.locator('aside button').filter({ hasText: '流程图' }).first();
    await expect(sequenceBtn).toBeVisible();
    await expect(flowchartBtn).toBeVisible();

    // 验证默认选中"时序图"（bg-blue-500 样式）
    const seqClass = await sequenceBtn.getAttribute('class') ?? '';
    const isSeqSelected = seqClass.includes('bg-blue-500');
    console.log(`[TC-F35-01] 时序图选中: ${isSeqSelected}`);
    expect(isSeqSelected).toBe(true);

    // 验证输入框存在（placeholder 包含 /api/）
    const targetInput = page.locator('aside input').first();
    const placeholder = await targetInput.getAttribute('placeholder') ?? '';
    console.log(`[TC-F35-01] Target placeholder: ${placeholder}`);
    expect(placeholder).toContain('/api/');

    await screenshot(page, 'f35-01-initial-state');
  });

  test('TC-F35-02: 时序图/流程图 Tab 切换', async ({ page }) => {
    await setupDesktopPage(page);
    await navigateToDiagramTab(page);

    // 点击"流程图"
    const flowchartBtn = page.locator('aside button').filter({ hasText: '流程图' }).first();
    await flowchartBtn.click();
    await page.waitForTimeout(500);

    // 验证 placeholder 变化
    const targetInput = page.locator('aside input').first();
    const flowPlaceholder = await targetInput.getAttribute('placeholder') ?? '';
    console.log(`[TC-F35-02] 流程图 placeholder: ${flowPlaceholder}`);
    expect(flowPlaceholder).toContain('Session');

    // 切回"时序图"
    const sequenceBtn = page.locator('aside button').filter({ hasText: '时序图' }).first();
    await sequenceBtn.click();
    await page.waitForTimeout(500);

    const seqPlaceholder = await targetInput.getAttribute('placeholder') ?? '';
    console.log(`[TC-F35-02] 时序图 placeholder: ${seqPlaceholder}`);
    expect(seqPlaceholder).toContain('/api/');

    await screenshot(page, 'f35-02-tab-switch');
  });

  test('TC-F35-03: 深度选择器交互', async ({ page }) => {
    await setupDesktopPage(page);
    await navigateToDiagramTab(page);

    // 默认深度 3 应该有 bg-blue 样式
    const depthButtons = page.locator('aside button').filter({ hasText: /^[1-5]$/ });
    const btn3 = depthButtons.filter({ hasText: '3' }).first();
    const btn3Class = await btn3.getAttribute('class') ?? '';
    console.log(`[TC-F35-03] Depth 3 class includes bg-blue-500: ${btn3Class.includes('bg-blue-500')}`);
    expect(btn3Class).toContain('bg-blue-500');

    // 点击深度 1
    const btn1 = depthButtons.filter({ hasText: '1' }).first();
    await btn1.click();
    await page.waitForTimeout(300);
    const btn1Class = await btn1.getAttribute('class') ?? '';
    console.log(`[TC-F35-03] Depth 1 selected: ${btn1Class.includes('bg-blue-500')}`);
    expect(btn1Class).toContain('bg-blue-500');

    // 点击深度 5
    const btn5 = depthButtons.filter({ hasText: '5' }).first();
    await btn5.click();
    await page.waitForTimeout(300);
    const btn5Class = await btn5.getAttribute('class') ?? '';
    console.log(`[TC-F35-03] Depth 5 selected: ${btn5Class.includes('bg-blue-500')}`);
    expect(btn5Class).toContain('bg-blue-500');

    await screenshot(page, 'f35-03-depth-selector');
  });

  test('TC-F35-04: 空输入禁用生成按钮', async ({ page }) => {
    await setupDesktopPage(page);
    await navigateToDiagramTab(page);

    // target 输入框应为空
    const targetInput = page.locator('aside input').first();
    const value = await targetInput.inputValue();
    console.log(`[TC-F35-04] Target input value: "${value}"`);

    // "生成图表"按钮应 disabled
    const genBtn = page.locator('aside button').filter({ hasText: '生成图表' }).first();
    const isDisabled = await genBtn.isDisabled();
    console.log(`[TC-F35-04] 生成按钮 disabled: ${isDisabled}`);
    expect(isDisabled).toBe(true);

    await screenshot(page, 'f35-04-button-disabled');
  });

  test('TC-F35-05: 项目路径默认值', async ({ page }) => {
    await setupDesktopPage(page);
    await navigateToDiagramTab(page);

    const projectRootInput = page.locator('aside input').nth(1);
    const value = await projectRootInput.inputValue();
    const placeholder = await projectRootInput.getAttribute('placeholder') ?? '';
    console.log(`[TC-F35-05] Project root value: "${value}", placeholder: "${placeholder}"`);
    // 默认值或 placeholder 应为 "."
    expect(value === '.' || placeholder === '.').toBe(true);

    await screenshot(page, 'f35-05-project-root');
  });
});

// ═══════════════════════════════════════════════════════════════
// 模块 2: F35 时序图生成 (5 TC)
// ═══════════════════════════════════════════════════════════════
test.describe('F35 时序图生成', () => {

  test('TC-F35-06: 时序图 API 直接调用', async ({ page }) => {
    test.setTimeout(60000);
    await setupDesktopPage(page);

    const apiResult = await page.evaluate(async (projectRoot) => {
      const resp = await fetch('/api/code-diagrams/generate', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          diagramType: 'sequence',
          target: 'SequenceDiagramGenerator.generate',
          projectRoot: projectRoot,
          depth: 3
        })
      });
      const data = await resp.json();
      return {
        status: resp.status,
        hasMermaid: data.mermaidSyntax?.includes('sequenceDiagram'),
        confidenceScore: data.confidenceScore,
        nodesCount: data.metadata?.nodesCount,
        edgesCount: data.metadata?.edgesCount,
        languages: data.metadata?.languagesAnalyzed,
        warningsCount: data.warnings?.length,
        mermaidLength: data.mermaidSyntax?.length
      };
    }, PROJECT_ROOT);

    console.log(`[TC-F35-06] API status: ${apiResult.status}, hasMermaid: ${apiResult.hasMermaid}`);
    console.log(`[TC-F35-06] Confidence: ${apiResult.confidenceScore}, Nodes: ${apiResult.nodesCount}, Edges: ${apiResult.edgesCount}`);
    console.log(`[TC-F35-06] Languages: ${apiResult.languages}, Warnings: ${apiResult.warningsCount}, MermaidLen: ${apiResult.mermaidLength}`);
    expect(apiResult.status).toBe(200);
    expect(apiResult.hasMermaid).toBe(true);
    expect(apiResult.confidenceScore).toBeGreaterThan(0);

    await screenshot(page, 'f35-06-api-sequence');
  });

  test('TC-F35-07: 前端时序图生成全流程', async ({ page }) => {
    test.setTimeout(60000);
    await setupDesktopPage(page);
    await navigateToDiagramTab(page);

    await generateDiagram(page, 'SequenceDiagramGenerator.generate', PROJECT_ROOT);

    // 验证 .diagram-preview-container 内有 SVG
    const svgEl = page.locator('.diagram-preview-container svg').first();
    const hasSvg = await svgEl.isVisible({ timeout: 10000 }).catch(() => false);
    console.log(`[TC-F35-07] SVG rendered: ${hasSvg}`);
    expect(hasSvg).toBe(true);

    await screenshot(page, 'f35-07-sequence-generated');
  });

  test('TC-F35-08: 时序图 Mermaid 语法正确性', async ({ page }) => {
    test.setTimeout(60000);
    await setupDesktopPage(page);
    await navigateToDiagramTab(page);

    await generateDiagram(page, 'SequenceDiagramGenerator.generate', PROJECT_ROOT);

    // 检查 aside 区域是否包含 sequenceDiagram 或 participant 关键字
    const asideText = await page.locator('aside').textContent() ?? '';
    const hasSequenceKeyword = asideText.includes('sequenceDiagram') || asideText.includes('participant');
    console.log(`[TC-F35-08] Contains sequenceDiagram: ${asideText.includes('sequenceDiagram')}, participant: ${asideText.includes('participant')}`);
    expect(hasSequenceKeyword).toBe(true);

    await screenshot(page, 'f35-08-mermaid-syntax');
  });

  test('TC-F35-09: 时序图置信度评分', async ({ page }) => {
    test.setTimeout(60000);
    await setupDesktopPage(page);
    await navigateToDiagramTab(page);

    await generateDiagram(page, 'SequenceDiagramGenerator.generate', PROJECT_ROOT);

    // 查找包含"置信度"文本
    const confidenceEl = page.locator('aside').locator('text=置信度').first();
    const hasConfidence = await confidenceEl.isVisible().catch(() => false);
    console.log(`[TC-F35-09] 置信度元素可见: ${hasConfidence}`);
    expect(hasConfidence).toBe(true);

    // 提取百分比值
    const confidenceText = await page.locator('aside').locator('text=/置信度.*%/').first().textContent().catch(() => '');
    const match = confidenceText?.match(/(\d+)%/);
    if (match) {
      const pct = parseInt(match[1]);
      console.log(`[TC-F35-09] 置信度: ${pct}%`);
      expect(pct).toBeGreaterThanOrEqual(0);
      expect(pct).toBeLessThanOrEqual(100);
    }

    // 验证置信度进度条
    const bar = page.locator('aside .rounded-full').first();
    const hasBar = await bar.isVisible().catch(() => false);
    console.log(`[TC-F35-09] 进度条存在: ${hasBar}`);

    await screenshot(page, 'f35-09-confidence');
  });

  test('TC-F35-10: 时序图元数据显示', async ({ page }) => {
    test.setTimeout(60000);
    await setupDesktopPage(page);
    await navigateToDiagramTab(page);

    await generateDiagram(page, 'SequenceDiagramGenerator.generate', PROJECT_ROOT);

    const asideText = await page.locator('aside').textContent() ?? '';
    const hasNodes = asideText.includes('节点:');
    const hasEdges = asideText.includes('边:');
    const hasLang = asideText.includes('语言:');
    const hasTime = asideText.includes('耗时:');

    console.log(`[TC-F35-10] 节点: ${hasNodes}, 边: ${hasEdges}, 语言: ${hasLang}, 耗时: ${hasTime}`);
    expect(hasNodes).toBe(true);
    expect(hasEdges).toBe(true);
    expect(hasLang).toBe(true);
    expect(hasTime).toBe(true);

    await screenshot(page, 'f35-10-metadata');
  });
});

// ═══════════════════════════════════════════════════════════════
// 模块 3: F35 流程图生成 (5 TC)
// ═══════════════════════════════════════════════════════════════
test.describe('F35 流程图生成', () => {

  test('TC-F35-11: 流程图 API 直接调用', async ({ page }) => {
    test.setTimeout(60000);
    await setupDesktopPage(page);

    const apiResult = await page.evaluate(async (projectRoot) => {
      const resp = await fetch('/api/code-diagrams/generate', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          diagramType: 'flowchart',
          target: 'FlowChartGenerator.generate',
          projectRoot: projectRoot,
          depth: 3
        })
      });
      const data = await resp.json();
      return {
        status: resp.status,
        hasMermaid: data.mermaidSyntax?.includes('flowchart') || data.mermaidSyntax?.includes('graph'),
        confidenceScore: data.confidenceScore,
        nodesCount: data.metadata?.nodesCount,
        edgesCount: data.metadata?.edgesCount,
        languages: data.metadata?.languagesAnalyzed,
        warningsCount: data.warnings?.length,
        mermaidLength: data.mermaidSyntax?.length
      };
    }, PROJECT_ROOT);

    console.log(`[TC-F35-11] API status: ${apiResult.status}, hasMermaid: ${apiResult.hasMermaid}`);
    console.log(`[TC-F35-11] Confidence: ${apiResult.confidenceScore}, Nodes: ${apiResult.nodesCount}, Edges: ${apiResult.edgesCount}`);
    expect(apiResult.status).toBe(200);
    expect(apiResult.hasMermaid).toBe(true);

    await screenshot(page, 'f35-11-api-flowchart');
  });

  test('TC-F35-12: 前端流程图生成全流程', async ({ page }) => {
    test.setTimeout(60000);
    await setupDesktopPage(page);
    await navigateToDiagramTab(page);

    // 切换到"流程图"
    const flowchartBtn = page.locator('aside button').filter({ hasText: '流程图' }).first();
    await flowchartBtn.click();
    await page.waitForTimeout(500);

    await generateDiagram(page, 'FlowChartGenerator.generate', PROJECT_ROOT);

    // 验证 SVG 渲染
    const svgEl = page.locator('.diagram-preview-container svg').first();
    const hasSvg = await svgEl.isVisible({ timeout: 10000 }).catch(() => false);
    console.log(`[TC-F35-12] 流程图 SVG rendered: ${hasSvg}`);
    expect(hasSvg).toBe(true);

    await screenshot(page, 'f35-12-flowchart-render');
  });

  test('TC-F35-13: 流程图分支结构验证', async ({ page }) => {
    test.setTimeout(60000);
    await setupDesktopPage(page);
    await navigateToDiagramTab(page);

    // 切换到"流程图"
    const flowchartBtn = page.locator('aside button').filter({ hasText: '流程图' }).first();
    await flowchartBtn.click();
    await page.waitForTimeout(500);

    await generateDiagram(page, 'FlowChartGenerator.generate', PROJECT_ROOT);

    // 检查 aside 内容包含分支结构标识
    const asideText = await page.locator('aside').textContent() ?? '';
    const hasBranch = asideText.includes('{') || asideText.includes('-->') || asideText.includes('条件');
    console.log(`[TC-F35-13] 包含分支结构: ${hasBranch}`);
    console.log(`[TC-F35-13] Aside snippet: ${asideText.substring(0, 300)}`);
    expect(hasBranch).toBe(true);

    await screenshot(page, 'f35-13-flowchart-branches');
  });

  test('TC-F35-14: 不同深度参数对比', async ({ page }) => {
    test.setTimeout(60000);
    await setupDesktopPage(page);

    // depth=1
    const result1 = await page.evaluate(async (projectRoot) => {
      const resp = await fetch('/api/code-diagrams/generate', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          diagramType: 'flowchart',
          target: 'FlowChartGenerator.generate',
          projectRoot: projectRoot,
          depth: 1
        })
      });
      const data = await resp.json();
      return { nodesCount: data.metadata?.nodesCount ?? 0, edgesCount: data.metadata?.edgesCount ?? 0 };
    }, PROJECT_ROOT);

    // depth=5
    const result5 = await page.evaluate(async (projectRoot) => {
      const resp = await fetch('/api/code-diagrams/generate', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          diagramType: 'flowchart',
          target: 'FlowChartGenerator.generate',
          projectRoot: projectRoot,
          depth: 5
        })
      });
      const data = await resp.json();
      return { nodesCount: data.metadata?.nodesCount ?? 0, edgesCount: data.metadata?.edgesCount ?? 0 };
    }, PROJECT_ROOT);

    console.log(`[TC-F35-14] Depth=1: nodes=${result1.nodesCount}, edges=${result1.edgesCount}`);
    console.log(`[TC-F35-14] Depth=5: nodes=${result5.nodesCount}, edges=${result5.edgesCount}`);
    expect(result5.nodesCount).toBeGreaterThanOrEqual(result1.nodesCount);

    await screenshot(page, 'f35-14-depth-comparison');
  });

  test('TC-F35-15: 流程图置信度与警告', async ({ page }) => {
    test.setTimeout(60000);
    await setupDesktopPage(page);
    await navigateToDiagramTab(page);

    // 切换到"流程图"
    const flowchartBtn = page.locator('aside button').filter({ hasText: '流程图' }).first();
    await flowchartBtn.click();
    await page.waitForTimeout(500);

    await generateDiagram(page, 'FlowChartGenerator.generate', PROJECT_ROOT);

    // 验证置信度
    const asideText = await page.locator('aside').textContent() ?? '';
    const hasConfidence = asideText.includes('置信度');
    console.log(`[TC-F35-15] 流程图置信度: ${hasConfidence}`);
    expect(hasConfidence).toBe(true);

    // 检查警告
    const hasWarnings = asideText.includes('个警告');
    console.log(`[TC-F35-15] 有警告: ${hasWarnings}`);
    if (hasWarnings) {
      console.log('[TC-F35-15] 警告文本存在');
    }

    await screenshot(page, 'f35-15-flowchart-confidence');
  });
});

// ═══════════════════════════════════════════════════════════════
// 模块 4: F35 导出与编辑 (5 TC)
// ═══════════════════════════════════════════════════════════════
test.describe('F35 导出与编辑', () => {

  test('TC-F35-16: Monaco 编辑器显示源码', async ({ page }) => {
    test.setTimeout(90000);
    await setupDesktopPage(page);
    await navigateToDiagramTab(page);

    await generateDiagram(page, 'SequenceDiagramGenerator.generate', PROJECT_ROOT);

    // 等待 Monaco Editor 从 CDN 加载并初始化
    await page.waitForSelector('aside .monaco-editor', { timeout: 20000 }).catch(() => {});
    const monacoEl = page.locator('aside .monaco-editor').first();
    const hasMonaco = await monacoEl.isVisible().catch(() => false);
    console.log(`[TC-F35-16] Monaco editor visible: ${hasMonaco}`);
    expect(hasMonaco).toBe(true);

    // 验证 "Mermaid 源码" 标签
    const asideText = await page.locator('aside').textContent() ?? '';
    const hasMermaidLabel = asideText.includes('Mermaid 源码');
    console.log(`[TC-F35-16] Mermaid 源码 label: ${hasMermaidLabel}`);
    expect(hasMermaidLabel).toBe(true);

    await screenshot(page, 'f35-16-monaco-editor');
  });

  test('TC-F35-17: SVG 复制按钮', async ({ page }) => {
    test.setTimeout(90000);
    await setupDesktopPage(page);
    await navigateToDiagramTab(page);

    await generateDiagram(page, 'SequenceDiagramGenerator.generate', PROJECT_ROOT);

    // 找到 "复制 SVG" 按钮（等待结果渲染，然后用角色选择器查找）
    const asideEl = page.locator('aside');
    // CodeDiagramGenerator 底部状态栏的 SVG 按钮（有 title 和文本 "SVG"）
    const svgBtn = asideEl.getByTitle('复制 SVG').first();
    await svgBtn.scrollIntoViewIfNeeded().catch(() => {});
    const hasSvgBtn = await svgBtn.isVisible().catch(() => false);
    console.log(`[TC-F35-17] SVG copy button visible: ${hasSvgBtn}`);
    // Debug: 检查所有匹配的按钮数量
    const svgBtnCount = await asideEl.getByTitle('复制 SVG').count();
    console.log(`[TC-F35-17] SVG buttons with title count: ${svgBtnCount}`);
    expect(hasSvgBtn).toBe(true);

    // 点击按钮
    await svgBtn.click();
    await page.waitForTimeout(500);
    console.log('[TC-F35-17] SVG copy button clicked');

    await screenshot(page, 'f35-17-svg-copy');
  });

  test('TC-F35-18: PNG 下载按钮', async ({ page }) => {
    test.setTimeout(90000);
    await setupDesktopPage(page);
    await navigateToDiagramTab(page);

    await generateDiagram(page, 'SequenceDiagramGenerator.generate', PROJECT_ROOT);

    // 找到 "下载 PNG" 按钮
    const asideEl = page.locator('aside');
    const pngBtn = asideEl.getByTitle('下载 PNG').first();
    await pngBtn.scrollIntoViewIfNeeded().catch(() => {});
    const hasPngBtn = await pngBtn.isVisible().catch(() => false);
    console.log(`[TC-F35-18] PNG download button visible: ${hasPngBtn}`);
    const pngBtnCount = await asideEl.getByTitle('下载 PNG').count();
    console.log(`[TC-F35-18] PNG buttons with title count: ${pngBtnCount}`);
    expect(hasPngBtn).toBe(true);

    await screenshot(page, 'f35-18-png-download');
  });

  test('TC-F35-19: 警告信息展开/折叠', async ({ page }) => {
    test.setTimeout(60000);
    await setupDesktopPage(page);
    await navigateToDiagramTab(page);

    await generateDiagram(page, 'SequenceDiagramGenerator.generate', PROJECT_ROOT);

    const asideText = await page.locator('aside').textContent() ?? '';
    const hasWarnings = asideText.includes('个警告');

    if (hasWarnings) {
      // 点击展开警告
      const warningBtn = page.locator('aside button').filter({ hasText: '个警告' }).first();
      await warningBtn.click();
      await page.waitForTimeout(500);

      // 验证 "•" 前缀条目可见
      const warningItems = page.locator('aside').locator('text=•');
      const itemCount = await warningItems.count();
      console.log(`[TC-F35-19] 展开后警告条目数: ${itemCount}`);
      expect(itemCount).toBeGreaterThan(0);

      // 再次点击折叠
      await warningBtn.click();
      await page.waitForTimeout(500);
      console.log('[TC-F35-19] 警告已折叠');
    } else {
      console.log('[TC-F35-19] 无警告信息，跳过展开/折叠测试');
    }

    await screenshot(page, 'f35-19-warnings');
  });

  test('TC-F35-20: 清除结果', async ({ page }) => {
    test.setTimeout(60000);
    await setupDesktopPage(page);
    await navigateToDiagramTab(page);

    await generateDiagram(page, 'SequenceDiagramGenerator.generate', PROJECT_ROOT);

    // 确认有 SVG 结果
    const svgBefore = page.locator('.diagram-preview-container svg').first();
    const hasSvgBefore = await svgBefore.isVisible({ timeout: 10000 }).catch(() => false);
    console.log(`[TC-F35-20] SVG before clear: ${hasSvgBefore}`);

    // 点击清除按钮
    const clearBtn = page.locator('aside button[title="清除结果"]');
    const hasClearBtn = await clearBtn.isVisible().catch(() => false);
    console.log(`[TC-F35-20] Clear button visible: ${hasClearBtn}`);
    expect(hasClearBtn).toBe(true);

    await clearBtn.click();
    await page.waitForTimeout(500);

    // 验证 SVG 消失
    const svgAfter = page.locator('.diagram-preview-container svg').first();
    const hasSvgAfter = await svgAfter.isVisible().catch(() => false);
    console.log(`[TC-F35-20] SVG after clear: ${hasSvgAfter}`);
    expect(hasSvgAfter).toBe(false);

    // 验证空状态文本
    const asideText = await page.locator('aside').textContent() ?? '';
    const hasEmptyState = asideText.includes('输入目标并点击生成图表');
    console.log(`[TC-F35-20] Empty state: ${hasEmptyState}`);
    expect(hasEmptyState).toBe(true);

    await screenshot(page, 'f35-20-clear-result');
  });
});

// ═══════════════════════════════════════════════════════════════
// 模块 5: F35 错误处理与边界 (5 TC)
// ═══════════════════════════════════════════════════════════════
test.describe('F35 错误处理与边界', () => {

  test('TC-F35-21: 无效项目路径', async ({ page }) => {
    test.setTimeout(120000);
    await setupDesktopPage(page);
    await navigateToDiagramTab(page);

    // 输入无效路径
    const targetInput = page.locator('aside input').first();
    await targetInput.fill('SomeClass.method');

    const projectRootInput = page.locator('aside input').nth(1);
    await projectRootInput.fill('/nonexistent/path/12345');

    // 点击生成
    const genBtn = page.locator('aside button').filter({ hasText: '生成图表' }).first();
    await genBtn.click();

    // 等待 Loading 消失（只等待 spinner 和 loading 文本消失）
    await page.waitForFunction(() => {
      const aside = document.querySelector('aside');
      if (!aside) return false;
      const spinner = aside.querySelector('.animate-spin');
      const text = aside.textContent || '';
      return !spinner && !text.includes('生成中...');
    }, { timeout: 90000 }).catch(() => {});
    await page.waitForTimeout(1000);
    
    // 验证错误提示区域
    const errorEl = page.locator('aside .border-red-500\/30, aside [class*="border-red"]').first();
    const hasError = await errorEl.isVisible().catch(() => false);
    
    const asideText = await page.locator('aside').textContent() ?? '';
    const hasErrorText = asideText.includes('Error') || asideText.includes('错误') || 
      asideText.includes('HTTP') || asideText.includes('not available') || asideText.includes('unavailable');
    console.log(`[TC-F35-21] Error element visible: ${hasError}, Error text: ${hasErrorText}`);
    console.log(`[TC-F35-21] Aside text snippet: ${asideText.substring(0, 200)}`);
    expect(hasError || hasErrorText).toBe(true);

    await screenshot(page, 'f35-21-invalid-path');
  });

  test('TC-F35-22: 目标未找到', async ({ page }) => {
    test.setTimeout(120000);
    await setupDesktopPage(page);
    await navigateToDiagramTab(page);

    // 输入有效路径但不存在的 target
    const targetInput = page.locator('aside input').first();
    await targetInput.fill('NonExistentClass.nonExistentMethod');

    const projectRootInput = page.locator('aside input').nth(1);
    await projectRootInput.fill(PROJECT_ROOT);

    // 点击生成
    const genBtn = page.locator('aside button').filter({ hasText: '生成图表' }).first();
    await genBtn.click();

    // 等待 Loading 消失
    await page.waitForFunction(() => {
      const aside = document.querySelector('aside');
      if (!aside) return false;
      const spinner = aside.querySelector('.animate-spin');
      const text = aside.textContent || '';
      return !spinner && !text.includes('生成中...');
    }, { timeout: 90000 }).catch(() => {});
    await page.waitForTimeout(1000);

    const asideText = await page.locator('aside').textContent() ?? '';
    const hasError = asideText.includes('Error') || asideText.includes('错误') || 
      asideText.includes('HTTP') || asideText.includes('not available') || asideText.includes('unavailable');
    const hasWarning = asideText.includes('个警告');
    const hasLowConfidence = asideText.includes('置信度');
    console.log(`[TC-F35-22] Error: ${hasError}, Warning: ${hasWarning}, Confidence: ${hasLowConfidence}`);
    console.log(`[TC-F35-22] Aside text snippet: ${asideText.substring(0, 200)}`);
    // 应该有错误提示或低置信度结果
    expect(hasError || hasWarning || hasLowConfidence).toBe(true);

    await screenshot(page, 'f35-22-target-not-found');
  });

  test('TC-F35-23: Loading 状态验证', async ({ page }) => {
    test.setTimeout(60000);
    await setupDesktopPage(page);
    await navigateToDiagramTab(page);

    // 输入有效参数
    const targetInput = page.locator('aside input').first();
    await targetInput.fill('SequenceDiagramGenerator.generate');

    const projectRootInput = page.locator('aside input').nth(1);
    await projectRootInput.fill(PROJECT_ROOT);

    // 点击生成
    const genBtn = page.locator('aside button').filter({ hasText: '生成图表' }).first();
    await genBtn.click();

    // 立即检查 Loading 状态（100-500ms 内）
    await page.waitForTimeout(100);

    const loadingText = page.locator('aside').locator('text=生成中...');
    const spinnerEl = page.locator('aside .animate-spin').first();
    const analyzingText = page.locator('aside').locator('text=正在分析代码结构...');

    const hasLoadingText = await loadingText.isVisible().catch(() => false);
    const hasSpinner = await spinnerEl.isVisible().catch(() => false);
    const hasAnalyzing = await analyzingText.isVisible().catch(() => false);

    console.log(`[TC-F35-23] Loading text: ${hasLoadingText}, Spinner: ${hasSpinner}, Analyzing: ${hasAnalyzing}`);
    expect(hasLoadingText || hasSpinner || hasAnalyzing).toBe(true);

    await screenshot(page, 'f35-23-loading-state');

    // 等待加载完成避免影响后续测试
    await page.waitForFunction(() => {
      const aside = document.querySelector('aside');
      if (!aside) return false;
      const spinner = aside.querySelector('.animate-spin');
      return !spinner;
    }, { timeout: 55000 }).catch(() => {});
  });

  test('TC-F35-24: 使用 Python 项目分析', async ({ page }) => {
    test.setTimeout(60000);
    await setupDesktopPage(page);

    const apiResult = await page.evaluate(async (projectRoot) => {
      const resp = await fetch('/api/code-diagrams/generate', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          diagramType: 'sequence',
          target: 'code_analysis_service.analyze',
          projectRoot: projectRoot,
          depth: 3
        })
      });
      const data = await resp.json();
      return {
        status: resp.status,
        languages: data.metadata?.languagesAnalyzed,
        hasMermaid: !!data.mermaidSyntax,
        confidenceScore: data.confidenceScore
      };
    }, PROJECT_ROOT);

    console.log(`[TC-F35-24] Status: ${apiResult.status}, Languages: ${JSON.stringify(apiResult.languages)}`);
    console.log(`[TC-F35-24] HasMermaid: ${apiResult.hasMermaid}, Confidence: ${apiResult.confidenceScore}`);
    expect(apiResult.status).toBe(200);
    // 验证 languagesAnalyzed 包含 python
    const hasPython = apiResult.languages?.some((l: string) => l.toLowerCase().includes('python'));
    console.log(`[TC-F35-24] Contains python: ${hasPython}`);
    expect(hasPython).toBe(true);

    await screenshot(page, 'f35-24-python-project');
  });

  test('TC-F35-25: Keyboard shortcut (Ctrl+Enter)', async ({ page }) => {
    test.setTimeout(60000);
    await setupDesktopPage(page);
    await navigateToDiagramTab(page);

    // 输入 target
    const targetInput = page.locator('aside input').first();
    await targetInput.fill('SequenceDiagramGenerator.generate');

    const projectRootInput = page.locator('aside input').nth(1);
    await projectRootInput.fill(PROJECT_ROOT);
    await page.waitForTimeout(300);

    // 按 Meta+Enter (Mac) 或 Ctrl+Enter
    const isMac = process.platform === 'darwin';
    await page.keyboard.press(isMac ? 'Meta+Enter' : 'Control+Enter');

    // 等待出现 Loading 或结果
    await page.waitForTimeout(500);
    const asideText = await page.locator('aside').textContent() ?? '';
    const hasLoading = asideText.includes('生成中...') || asideText.includes('正在分析');
    const spinnerEl = page.locator('aside .animate-spin').first();
    const hasSpinner = await spinnerEl.isVisible().catch(() => false);

    // 也可能已经出结果了（如果 API 很快）
    const hasResult = asideText.includes('置信度') || asideText.includes('Mermaid 源码');

    console.log(`[TC-F35-25] Loading: ${hasLoading}, Spinner: ${hasSpinner}, Result: ${hasResult}`);
    expect(hasLoading || hasSpinner || hasResult).toBe(true);

    await screenshot(page, 'f35-25-keyboard-shortcut');

    // 等待完成
    await page.waitForFunction(() => {
      const aside = document.querySelector('aside');
      if (!aside) return false;
      const spinner = aside.querySelector('.animate-spin');
      return !spinner;
    }, { timeout: 55000 }).catch(() => {});
  });
});
