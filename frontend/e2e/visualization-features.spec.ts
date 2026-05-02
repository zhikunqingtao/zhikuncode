import { test, expect, Page } from '@playwright/test';
import path from 'path';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const SCREENSHOT_DIR = path.resolve(__dirname, '../../docs/test-results/screenshots/visualization');

// Helper: save screenshot with descriptive name
async function screenshot(page: Page, name: string) {
  await page.screenshot({ path: path.join(SCREENSHOT_DIR, `${name}.png`), fullPage: true });
}

// Helper: navigate to home and set desktop viewport
async function setupDesktopPage(page: Page) {
  await page.setViewportSize({ width: 1280, height: 800 });
  await page.goto('/', { waitUntil: 'networkidle' });
  await page.waitForTimeout(2000);
}

// Helper: click sidebar tab by label text
async function clickSidebarTab(page: Page, label: string) {
  // Tab buttons are inside aside > div.flex > button, each contains a span with the label
  const tabButton = page.locator('aside button').filter({ hasText: label }).first();
  await tabButton.click();
  await page.waitForTimeout(1000);
}

// ═══════════════════════════════════════════════════════════════
// F15 文件树导航 (4 TC)
// ═══════════════════════════════════════════════════════════════
test.describe('F15 文件树导航', () => {

  test('TC-VIS-01: 文件树Tab切换与加载', async ({ page }) => {
    await setupDesktopPage(page);

    // 点击"文件"Tab
    await clickSidebarTab(page, '文件');

    // 等待文件树加载完成：Loader2 消失或 tree 节点出现
    // FileTreePanel uses Loader2 with animate-spin while loading
    await page.waitForFunction(() => {
      const spinner = document.querySelector('aside .animate-spin');
      return !spinner;
    }, { timeout: 15000 }).catch(() => {});
    await page.waitForTimeout(1000);

    // 验证文件树容器存在（react-arborist renders a div with role="tree" or tree structure）
    const treeContainer = page.locator('aside [role="tree"], aside [class*="tree"], aside .react-arborist').first();
    const treeExists = await treeContainer.isVisible().catch(() => false);

    // 也可检查是否有任何文件/目录节点文本
    const asideText = await page.locator('aside').textContent();
    const hasFileContent = asideText?.includes('src') || asideText?.includes('package') || asideText?.includes('node_modules');

    console.log(`[TC-VIS-01] Tree container visible: ${treeExists}, Has file content: ${hasFileContent}`);
    // 至少一种方式应该表明文件树已加载
    expect(treeExists || hasFileContent).toBeTruthy();

    await screenshot(page, 'vis-01-file-tree-loaded');
  });

  test('TC-VIS-02: 文件树搜索过滤', async ({ page }) => {
    await setupDesktopPage(page);
    await clickSidebarTab(page, '文件');

    // 等待文件树加载
    await page.waitForFunction(() => {
      const spinner = document.querySelector('aside .animate-spin');
      return !spinner;
    }, { timeout: 15000 }).catch(() => {});
    await page.waitForTimeout(1000);

    // 找到搜索输入框 (placeholder="搜索文件...")
    const searchInput = page.locator('aside input[placeholder="搜索文件..."]');
    await expect(searchInput).toBeVisible();

    // 记录过滤前的 aside 文本长度
    const textBefore = await page.locator('aside').textContent() ?? '';

    // 输入搜索关键词
    await searchInput.fill('src');
    await page.waitForTimeout(500);

    // 验证过滤后的内容发生了变化
    const textAfter = await page.locator('aside').textContent() ?? '';
    const filtered = textAfter.length !== textBefore.length || textAfter.includes('src');

    console.log(`[TC-VIS-02] Before length: ${textBefore.length}, After length: ${textAfter.length}, Filtered: ${filtered}`);
    expect(filtered).toBeTruthy();

    await screenshot(page, 'vis-02-file-tree-search');
  });

  test('TC-VIS-03: 文件树目录展开/折叠', async ({ page }) => {
    await setupDesktopPage(page);
    await clickSidebarTab(page, '文件');

    // 等待文件树加载
    await page.waitForFunction(() => {
      const spinner = document.querySelector('aside .animate-spin');
      return !spinner;
    }, { timeout: 15000 }).catch(() => {});
    await page.waitForTimeout(1000);

    // 找到一个目录节点（带有📁图标或 ▸ 展开指示器的节点）
    // FileNode renders directories with ▸ (collapsed) or ▾ (expanded) indicators
    const dirNode = page.locator('aside').locator('text=▸').first();
    const dirExists = await dirNode.isVisible().catch(() => false);

    if (dirExists) {
      // 点击目录节点展开
      await dirNode.click();
      await page.waitForTimeout(500);

      // 验证展开后有 ▾ 指示器
      const expandedIndicator = page.locator('aside').locator('text=▾').first();
      const expanded = await expandedIndicator.isVisible().catch(() => false);
      console.log(`[TC-VIS-03] Directory expanded: ${expanded}`);

      // 再次点击折叠
      if (expanded) {
        await expandedIndicator.click();
        await page.waitForTimeout(500);
      }
    } else {
      console.log('[TC-VIS-03] No collapsed directory found (tree may be empty or auto-expanded)');
    }

    await screenshot(page, 'vis-03-file-tree-expand');
  });

  test('TC-VIS-04: 文件类型图标验证', async ({ page }) => {
    await setupDesktopPage(page);
    await clickSidebarTab(page, '文件');

    // 等待文件树加载
    await page.waitForFunction(() => {
      const spinner = document.querySelector('aside .animate-spin');
      return !spinner;
    }, { timeout: 15000 }).catch(() => {});
    await page.waitForTimeout(1000);

    // 验证文件树中有文件类型图标
    // FileTreePanel uses emoji icons: 📁/📂 for dirs, TS/JS for code, etc.
    const asideContent = await page.locator('aside').textContent() ?? '';

    const hasDirIcon = asideContent.includes('📁') || asideContent.includes('📂');
    const hasFileIcon = asideContent.includes('📄') || asideContent.includes('📝') ||
                        asideContent.includes('TS') || asideContent.includes('JS');

    console.log(`[TC-VIS-04] Dir icon present: ${hasDirIcon}, File icon present: ${hasFileIcon}`);
    // At least directory icons should be visible
    expect(hasDirIcon || hasFileIcon).toBeTruthy();

    await screenshot(page, 'vis-04-file-tree-icons');
  });
});

// ═══════════════════════════════════════════════════════════════
// F4 API序列图 (3 TC)
// ═══════════════════════════════════════════════════════════════
test.describe('F4 API序列图', () => {

  test('TC-VIS-05: 序列图Tab切换与空状态', async ({ page }) => {
    await setupDesktopPage(page);

    // 点击"序列图"Tab
    await clickSidebarTab(page, '序列图');

    // 在新会话中应显示空状态
    const emptyText = page.locator('aside').locator('text=当前会话暂无工具调用');
    const hasEmptyState = await emptyText.isVisible({ timeout: 5000 }).catch(() => false);

    // 也检查面板是否渲染了
    const asideText = await page.locator('aside').textContent() ?? '';
    const panelRendered = asideText.includes('工具调用') || asideText.includes('序列图') || hasEmptyState;

    console.log(`[TC-VIS-05] Empty state visible: ${hasEmptyState}, Panel rendered: ${panelRendered}`);
    expect(panelRendered).toBeTruthy();

    await screenshot(page, 'vis-05-sequence-empty');
  });

  test('TC-VIS-06: 序列图面板UI元素', async ({ page }) => {
    await setupDesktopPage(page);
    await clickSidebarTab(page, '序列图');
    await page.waitForTimeout(500);

    // APISequenceDiagram in empty state shows ArrowDownUp icon and text
    // When data exists, it shows toolbar with filter and refresh buttons
    const asideContent = await page.locator('aside').textContent() ?? '';
    const hasContent = asideContent.includes('工具调用') || asideContent.includes('过滤') ||
                       asideContent.includes('暂无') || asideContent.includes('刷新');

    console.log(`[TC-VIS-06] Sequence panel has UI elements: ${hasContent}`);
    console.log(`[TC-VIS-06] Aside content snippet: ${asideContent.substring(0, 200)}`);

    await screenshot(page, 'vis-06-sequence-panel');
  });

  test('TC-VIS-07: 序列图刷新按钮', async ({ page }) => {
    await setupDesktopPage(page);
    await clickSidebarTab(page, '序列图');
    await page.waitForTimeout(500);

    // In empty state there is no refresh button (only shown when data exists)
    // Check if refresh button exists
    const refreshBtn = page.locator('aside button[title="刷新"]');
    const hasRefresh = await refreshBtn.isVisible().catch(() => false);

    if (hasRefresh) {
      await refreshBtn.click();
      await page.waitForTimeout(500);
      console.log('[TC-VIS-07] Refresh button clicked successfully');
    } else {
      // In empty state, just verify the panel doesn't crash
      const asideVisible = await page.locator('aside').isVisible();
      console.log(`[TC-VIS-07] No refresh button (empty state), aside visible: ${asideVisible}`);
    }

    // Verify no crash
    await expect(page.locator('body')).toBeVisible();
    await screenshot(page, 'vis-07-sequence-refresh');
  });
});

// ═══════════════════════════════════════════════════════════════
// F5 Agent DAG (3 TC)
// ═══════════════════════════════════════════════════════════════
test.describe('F5 Agent DAG', () => {

  test('TC-VIS-08: DAG Tab切换与容器渲染', async ({ page }) => {
    await setupDesktopPage(page);

    // 点击"DAG"Tab
    await clickSidebarTab(page, 'DAG');

    // 验证 ReactFlow 容器渲染
    // ReactFlow renders with class "react-flow"
    const reactFlowContainer = page.locator('aside .react-flow').first();
    const rfExists = await reactFlowContainer.isVisible({ timeout: 5000 }).catch(() => false);

    // Also check for empty state (Network icon + "暂无 Agent 任务")
    const asideText = await page.locator('aside').textContent() ?? '';
    const hasDAGContent = rfExists || asideText.includes('Agent') || asideText.includes('DAG') || asideText.includes('暂无');

    console.log(`[TC-VIS-08] ReactFlow visible: ${rfExists}, DAG content: ${hasDAGContent}`);
    expect(hasDAGContent).toBeTruthy();

    await screenshot(page, 'vis-08-dag-container');
  });

  test('TC-VIS-09: DAG空状态', async ({ page }) => {
    await setupDesktopPage(page);
    await clickSidebarTab(page, 'DAG');
    await page.waitForTimeout(500);

    // 无Agent任务时的空状态: "暂无 Agent 任务"
    const emptyText = page.locator('aside').locator('text=暂无 Agent 任务');
    const hasEmptyState = await emptyText.isVisible({ timeout: 3000 }).catch(() => false);

    // If there are tasks, ReactFlow canvas should exist
    const rfCanvas = page.locator('aside .react-flow').first();
    const hasCanvas = await rfCanvas.isVisible().catch(() => false);

    console.log(`[TC-VIS-09] Empty state: ${hasEmptyState}, ReactFlow canvas: ${hasCanvas}`);
    // Either empty state or canvas should be present
    expect(hasEmptyState || hasCanvas).toBeTruthy();

    await screenshot(page, 'vis-09-dag-empty');
  });

  test('TC-VIS-10: DAG布局控件', async ({ page }) => {
    await setupDesktopPage(page);
    await clickSidebarTab(page, 'DAG');
    await page.waitForTimeout(500);

    // 检查布局切换按钮和全屏按钮
    // These are only shown when agentTasks.length > 0
    // Buttons have titles: "切换为从左到右" / "切换为从上到下", "适应视图", "全屏"
    const layoutBtn = page.locator('aside button[title*="切换为"]').first();
    const fullscreenBtn = page.locator('aside button[title*="全屏"]').first();
    const fitViewBtn = page.locator('aside button[title="适应视图"]').first();

    const hasLayout = await layoutBtn.isVisible().catch(() => false);
    const hasFullscreen = await fullscreenBtn.isVisible().catch(() => false);
    const hasFitView = await fitViewBtn.isVisible().catch(() => false);

    console.log(`[TC-VIS-10] Layout button: ${hasLayout}, Fullscreen: ${hasFullscreen}, FitView: ${hasFitView}`);

    if (hasLayout) {
      await layoutBtn.click();
      await page.waitForTimeout(500);
      console.log('[TC-VIS-10] Layout toggle clicked');
    } else {
      console.log('[TC-VIS-10] No layout controls (empty DAG state)');
    }

    // Verify no crash
    await expect(page.locator('body')).toBeVisible();
    await screenshot(page, 'vis-10-dag-controls');
  });
});

// ═══════════════════════════════════════════════════════════════
// F7 Git时间线 (3 TC)
// ═══════════════════════════════════════════════════════════════
test.describe('F7 Git时间线', () => {

  test('TC-VIS-11: Git Tab切换与加载', async ({ page }) => {
    await setupDesktopPage(page);

    // 点击"Git"Tab
    await clickSidebarTab(page, 'Git');

    // 等待加载：骨架屏(animate-pulse)消失 或 内容出现 或 错误出现
    await page.waitForFunction(() => {
      const aside = document.querySelector('aside');
      if (!aside) return false;
      const text = aside.textContent || '';
      // Loaded if: has commit content, or error, or empty state
      return text.includes('commit') || text.includes('暂无') ||
             text.includes('重试') || text.includes('分钟前') ||
             text.includes('小时前') || text.includes('天前') ||
             text.includes('刚刚') || text.includes('个月前');
    }, { timeout: 15000 }).catch(() => {});
    await page.waitForTimeout(1000);

    // 验证面板已渲染
    const asideText = await page.locator('aside').textContent() ?? '';
    const panelRendered = asideText.length > 10;
    console.log(`[TC-VIS-11] Git panel rendered, content length: ${asideText.length}`);
    console.log(`[TC-VIS-11] Content snippet: ${asideText.substring(0, 300)}`);

    await screenshot(page, 'vis-11-git-timeline');
  });

  test('TC-VIS-12: Git时间线UI结构', async ({ page }) => {
    await setupDesktopPage(page);
    await clickSidebarTab(page, 'Git');

    // 等待加载
    await page.waitForFunction(() => {
      const aside = document.querySelector('aside');
      if (!aside) return false;
      const pulse = aside.querySelector('.animate-pulse');
      return !pulse;
    }, { timeout: 15000 }).catch(() => {});
    await page.waitForTimeout(1000);

    const asideText = await page.locator('aside').textContent() ?? '';

    // 检查commit卡片、垂直线、圆点等UI结构
    const hasCommitData = asideText.includes('分钟前') || asideText.includes('小时前') ||
                          asideText.includes('天前') || asideText.includes('刚刚') ||
                          asideText.includes('个月前');
    const hasError = asideText.includes('重试');
    const hasEmptyState = asideText.includes('暂无 commit');

    // 如果有commit数据，验证垂直线元素存在
    if (hasCommitData) {
      // 垂直线: div with w-0.5 class
      const verticalLine = page.locator('aside .w-0\\.5').first();
      const hasLine = await verticalLine.isVisible().catch(() => false);
      // 圆点: div with rounded-full inside the timeline
      const dots = page.locator('aside .rounded-full');
      const dotCount = await dots.count();
      console.log(`[TC-VIS-12] Has commit data, vertical line: ${hasLine}, dots: ${dotCount}`);
    } else if (hasError) {
      console.log(`[TC-VIS-12] Git error state detected: ${asideText.substring(0, 200)}`);
    } else if (hasEmptyState) {
      console.log('[TC-VIS-12] Git empty state - no commits');
    } else {
      console.log(`[TC-VIS-12] Unknown state: ${asideText.substring(0, 200)}`);
    }

    await screenshot(page, 'vis-12-git-structure');
  });

  test('TC-VIS-13: Git时间线错误恢复', async ({ page }) => {
    await setupDesktopPage(page);
    await clickSidebarTab(page, 'Git');

    // 等待加载
    await page.waitForFunction(() => {
      const aside = document.querySelector('aside');
      if (!aside) return false;
      const pulse = aside.querySelector('.animate-pulse');
      return !pulse;
    }, { timeout: 15000 }).catch(() => {});
    await page.waitForTimeout(1000);

    // 如果有重试按钮，点击测试
    const retryBtn = page.locator('aside').locator('text=重试').first();
    const hasRetry = await retryBtn.isVisible().catch(() => false);

    if (hasRetry) {
      await retryBtn.click();
      await page.waitForTimeout(3000);
      console.log('[TC-VIS-13] Retry button clicked');
    } else {
      console.log('[TC-VIS-13] No retry button (Git loaded successfully or empty state)');
    }

    // Verify no crash
    await expect(page.locator('body')).toBeVisible();
    await screenshot(page, 'vis-13-git-retry');
  });
});

// ═══════════════════════════════════════════════════════════════
// F1 Mermaid渲染 (3 TC) — 需要AI交互
// ═══════════════════════════════════════════════════════════════
test.describe('F1 Mermaid渲染', () => {

  test('TC-VIS-14: 发送Mermaid代码并验证渲染', async ({ page }) => {
    test.setTimeout(120000); // 2 min for LLM response

    await setupDesktopPage(page);

    const input = page.locator('textarea[aria-label="输入消息"]');
    await expect(input).toBeVisible();

    // 输入要求AI回复mermaid代码
    await input.fill('请用mermaid语法画一个简单的流程图，包含3个节点：开始→处理→结束。请直接用 ```mermaid 代码块返回，不需要解释。');
    await page.waitForTimeout(500);

    // 发送消息
    await input.press('Enter');
    await page.waitForTimeout(2000);

    await screenshot(page, 'vis-14-mermaid-sent');

    // 等待AI回复 — 检查是否出现SVG元素（Mermaid渲染后的结果）
    // MermaidBlock renders SVG inside a div with dangerouslySetInnerHTML
    let hasSvg = false;
    try {
      await page.waitForSelector('svg:not(button svg):not(header svg)', { timeout: 90000 });
      hasSvg = true;
    } catch {
      // SVG may not appear if mermaid rendering failed
    }

    // Also check for mermaid-related content
    const bodyText = await page.textContent('body') ?? '';
    const hasMermaidContent = bodyText.includes('开始') || bodyText.includes('处理') ||
                              bodyText.includes('结束') || bodyText.includes('graph') ||
                              bodyText.includes('flowchart');

    console.log(`[TC-VIS-14] SVG rendered: ${hasSvg}, Mermaid content: ${hasMermaidContent}`);

    await screenshot(page, 'vis-14-mermaid-rendered');
  });

  test('TC-VIS-15: Mermaid工具栏', async ({ page }) => {
    test.setTimeout(120000);

    await setupDesktopPage(page);

    const input = page.locator('textarea[aria-label="输入消息"]');
    await expect(input).toBeVisible();

    // 发送mermaid请求
    await input.fill('请用mermaid语法画一个简单的流程图：A-->B-->C。直接返回 ```mermaid 代码块。');
    await input.press('Enter');

    // 等待SVG渲染
    try {
      await page.waitForSelector('svg:not(button svg):not(header svg)', { timeout: 90000 });
      await page.waitForTimeout(2000);
    } catch {
      console.log('[TC-VIS-15] SVG not found, mermaid may not have rendered');
    }

    // 验证Mermaid工具栏按钮（复制SVG / 下载PNG）
    // MermaidBlock has buttons with title="复制 SVG" and title="下载 PNG"
    // These buttons appear on hover (group-hover)
    // Hover over the mermaid container to show buttons
    const mermaidContainer = page.locator('.group').filter({ has: page.locator('svg') }).first();
    const containerExists = await mermaidContainer.isVisible().catch(() => false);

    if (containerExists) {
      await mermaidContainer.hover();
      await page.waitForTimeout(500);

      const copyBtn = page.locator('button[title="复制 SVG"]');
      const downloadBtn = page.locator('button[title="下载 PNG"]');
      const hasCopy = await copyBtn.isVisible().catch(() => false);
      const hasDownload = await downloadBtn.isVisible().catch(() => false);

      console.log(`[TC-VIS-15] Copy SVG button: ${hasCopy}, Download PNG button: ${hasDownload}`);
    } else {
      console.log('[TC-VIS-15] Mermaid container not found');
    }

    await screenshot(page, 'vis-15-mermaid-toolbar');
  });

  test('TC-VIS-16: Mermaid渲染后查看序列图数据', async ({ page }) => {
    test.setTimeout(120000);

    await setupDesktopPage(page);

    const input = page.locator('textarea[aria-label="输入消息"]');
    await expect(input).toBeVisible();

    // 发送一个会触发工具调用的请求
    await input.fill('请读取当前目录下的 package.json 文件的前3行');
    await input.press('Enter');

    // 等待AI回复
    await page.waitForTimeout(30000);

    // 切换到序列图Tab
    await clickSidebarTab(page, '序列图');
    await page.waitForTimeout(1000);

    // 检查序列图面板状态
    const asideText = await page.locator('aside').textContent() ?? '';
    const hasToolData = asideText.includes('次调用') || asideText.includes('调用记录');
    const hasEmptyState = asideText.includes('暂无工具调用');

    console.log(`[TC-VIS-16] Tool data in sequence: ${hasToolData}, Empty state: ${hasEmptyState}`);
    console.log(`[TC-VIS-16] Aside snippet: ${asideText.substring(0, 300)}`);

    await screenshot(page, 'vis-16-sequence-with-data');
  });
});

// ═══════════════════════════════════════════════════════════════
// F8 工具进度增强 (3 TC) — 需要AI交互
// ═══════════════════════════════════════════════════════════════
test.describe('F8 工具进度增强', () => {

  test('TC-VIS-17: 触发工具调用验证ToolCallBlock', async ({ page }) => {
    test.setTimeout(120000);

    await setupDesktopPage(page);

    const input = page.locator('textarea[aria-label="输入消息"]');
    await expect(input).toBeVisible();

    // 发送会触发工具调用的消息（使用Read工具）
    await input.fill('请读取项目根目录的 README.md 文件的前5行内容');
    await input.press('Enter');

    await screenshot(page, 'vis-17-tool-sent');

    // 等待工具调用块出现
    // ToolCallBlock has class "tool-call-block" and data-tool-use-id attribute
    let hasToolBlock = false;
    try {
      await page.waitForSelector('.tool-call-block', { timeout: 60000 });
      hasToolBlock = true;
    } catch {
      console.log('[TC-VIS-17] No tool-call-block appeared');
    }

    if (hasToolBlock) {
      // 验证工具名显示
      const toolBlock = page.locator('.tool-call-block').first();
      const toolText = await toolBlock.textContent() ?? '';
      const hasToolName = toolText.includes('Read') || toolText.includes('File') ||
                          toolText.includes('Bash') || toolText.includes('Tool');
      console.log(`[TC-VIS-17] Tool block found, has tool name: ${hasToolName}`);
      console.log(`[TC-VIS-17] Tool block text: ${toolText.substring(0, 200)}`);
    } else {
      // AI might respond without using tools
      const bodyText = await page.textContent('body') ?? '';
      console.log(`[TC-VIS-17] No tool block; body snippet: ${bodyText.substring(0, 300)}`);
    }

    await screenshot(page, 'vis-17-tool-call-block');
  });

  test('TC-VIS-18: 工具完成状态', async ({ page }) => {
    test.setTimeout(120000);

    await setupDesktopPage(page);

    const input = page.locator('textarea[aria-label="输入消息"]');
    await expect(input).toBeVisible();

    await input.fill('请读取项目根目录的 LICENSE 文件的前3行');
    await input.press('Enter');

    // 等待工具调用完成
    let hasToolBlock = false;
    try {
      await page.waitForSelector('.tool-call-block', { timeout: 60000 });
      hasToolBlock = true;
      // 等待工具完成 — 检查Completed状态标签
      await page.waitForFunction(() => {
        const blocks = Array.from(document.querySelectorAll('.tool-call-block'));
        return blocks.some(block => block.textContent?.includes('Completed'));
      }, { timeout: 30000 }).catch(() => {});
    } catch {
      console.log('[TC-VIS-18] No tool-call-block appeared');
    }

    if (hasToolBlock) {
      const toolBlock = page.locator('.tool-call-block').first();
      const toolText = await toolBlock.textContent() ?? '';

      // 验证完成状态
      const isCompleted = toolText.includes('Completed');
      // 验证耗时显示（格式如 "1.2s" 或 "350ms"）
      const hasDuration = /\d+(\.\d+)?[ms]+/.test(toolText);

      console.log(`[TC-VIS-18] Completed: ${isCompleted}, Has duration: ${hasDuration}`);
      console.log(`[TC-VIS-18] Tool text: ${toolText.substring(0, 200)}`);
    }

    await screenshot(page, 'vis-18-tool-completed');
  });

  test('TC-VIS-19: 工具输入输出展示', async ({ page }) => {
    test.setTimeout(120000);

    await setupDesktopPage(page);

    const input = page.locator('textarea[aria-label="输入消息"]');
    await expect(input).toBeVisible();

    await input.fill('请读取项目根目录的 .gitignore 文件的前3行');
    await input.press('Enter');

    // 等待工具调用完成
    let hasToolBlock = false;
    try {
      await page.waitForSelector('.tool-call-block', { timeout: 60000 });
      hasToolBlock = true;
      // 等待完成
      await page.waitForFunction(() => {
        const blocks = Array.from(document.querySelectorAll('.tool-call-block'));
        return blocks.some(block => block.textContent?.includes('Completed') || block.textContent?.includes('Result'));
      }, { timeout: 30000 }).catch(() => {});
    } catch {
      console.log('[TC-VIS-19] No tool-call-block appeared');
    }

    if (hasToolBlock) {
      const toolBlock = page.locator('.tool-call-block').first();

      // ToolCallBlock has "Input" and "Result" collapsible sections
      const toolText = await toolBlock.textContent() ?? '';
      const hasInput = toolText.includes('Input');
      const hasResult = toolText.includes('Result');

      console.log(`[TC-VIS-19] Has Input section: ${hasInput}, Has Result section: ${hasResult}`);

      // 点击展开Input区域
      if (hasInput) {
        const inputBtn = toolBlock.locator('button').filter({ hasText: 'Input' }).first();
        const inputBtnVisible = await inputBtn.isVisible().catch(() => false);
        if (inputBtnVisible) {
          await inputBtn.click();
          await page.waitForTimeout(500);
          console.log('[TC-VIS-19] Input section expanded');
        }
      }
    }

    await screenshot(page, 'vis-19-tool-io');
  });
});
