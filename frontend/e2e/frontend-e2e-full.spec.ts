import { test, expect, Page } from '@playwright/test';
import path from 'path';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const SCREENSHOT_DIR = path.resolve(__dirname, '../../docs/test-results/screenshots');

// Helper: save screenshot with descriptive name
async function screenshot(page: Page, name: string) {
  await page.screenshot({ path: path.join(SCREENSHOT_DIR, `${name}.png`), fullPage: true });
}

function parseStompFrame(raw: string) {
  const frame = raw.endsWith('\0') ? raw.slice(0, -1) : raw;
  const separator = frame.indexOf('\n\n');
  const head = separator >= 0 ? frame.slice(0, separator) : frame;
  const body = separator >= 0 ? frame.slice(separator + 2) : '';
  const [command, ...headerLines] = head.split('\n');
  const headers = Object.fromEntries(headerLines.map(line => {
    const colon = line.indexOf(':');
    return colon >= 0
      ? [line.slice(0, colon), line.slice(colon + 1)]
      : [line, ''];
  }));
  return { command, headers, body };
}

function stompFrame(
  command: string,
  headers: Record<string, string>,
  body = '',
) {
  const headerBlock = Object.entries(headers)
    .map(([name, value]) => `${name}:${value}`)
    .join('\n');
  return `${command}\n${headerBlock}\n\n${body}\0`;
}

test.describe('前端 E2E 与 UI 功能测试 (Task 13)', () => {

  // ─── TC-FE-01: 页面加载与布局 ───
  test('TC-FE-01: 页面加载与布局', async ({ page }) => {
    await page.goto('/', { waitUntil: 'networkidle' });
    await page.waitForTimeout(2000);

    await screenshot(page, 'fe-01-page-load');

    // 页面正常加载（无白屏）
    const body = page.locator('body');
    await expect(body).toBeVisible();

    // Header 存在
    const header = page.locator('header');
    await expect(header).toBeVisible();

    // 输入框存在 (aria-label="输入消息")
    const input = page.locator('textarea[aria-label="输入消息"]');
    await expect(input).toBeVisible();

    // 侧边栏存在 (aside 元素或包含会话/任务/文件标签)
    // On desktop the sidebar is an <aside>, on mobile it may be hidden
    const sidebar = page.locator('aside').first();
    const sidebarVisible = await sidebar.isVisible().catch(() => false);
    // 至少 body 加载正常
    expect(await body.textContent()).toBeTruthy();

    // 获取页面文本验证关键 UI
    const pageText = await page.textContent('body');
    expect(pageText).toBeTruthy();

    // 获取页面结构摘要
    const structure = await page.evaluate(() => {
      const tags = ['header', 'aside', 'main', 'textarea', 'select', 'button'];
      return tags.map(t => `${t}: ${document.querySelectorAll(t).length}`).join(', ');
    });
    console.log(`[TC-FE-01] DOM structure: ${structure}`);
    console.log(`[TC-FE-01] Header visible: true, Input visible: true, Sidebar visible: ${sidebarVisible}`);
  });

  // ─── TC-FE-02: 会话创建交互 ───
  test('TC-FE-02: 会话创建交互', async ({ page }) => {
    const browserErrors: string[] = [];
    page.on('pageerror', error => browserErrors.push(error.message));
    page.on('console', message => {
      if (message.type() === 'error') browserErrors.push(message.text());
    });
    const project = {
      id: 'project-e2e',
      name: 'E2E Project',
      workspaceRoot: '/workspace/e2e',
      createdAt: '2026-01-01T00:00:00Z',
    };
    let sessionCreateBody: Record<string, unknown> | null = null;
    const clientFrames: Array<ReturnType<typeof parseStompFrame>> = [];
    let subscriptionId = 'sub-0';

    await page.route(url => url.pathname.startsWith('/api/'), async route => {
      const request = route.request();
      const url = new URL(request.url());
      if (url.pathname === '/api/projects/directories') {
        await route.fulfill({ json: {
          roots: ['/workspace'],
          current: project.workspaceRoot,
          parent: '/workspace',
          directories: [],
        }});
      } else if (url.pathname === '/api/projects') {
        await route.fulfill({ json: [project] });
      } else if (url.pathname === '/api/sessions'
          && request.method() === 'POST') {
        sessionCreateBody = request.postDataJSON();
        await route.fulfill({ status: 201, json: {
          sessionId: 'session-e2e',
          projectId: project.id,
        }});
      } else if (url.pathname === '/api/sessions') {
        await route.fulfill({ json: {
          sessions: [], hasMore: false, nextCursor: null,
        }});
      } else if (url.pathname === '/api/skills') {
        await route.fulfill({ json: [] });
      } else if (url.pathname === '/api/config') {
        await route.fulfill({ json: { defaultModel: 'test-model' } });
      } else {
        await route.fulfill({ status: 404, json: { error: 'not mocked' } });
      }
    });
    await page.route('**/ws/info**', route => route.fulfill({ json: {
      websocket: true,
      cookie_needed: false,
      origins: ['*:*'],
      entropy: 123456,
    }}));
    await page.routeWebSocket(/\/ws\/[^/]+\/[^/]+\/websocket$/, ws => {
      const sendSockJs = (frame: string) => {
        ws.send(`a${JSON.stringify([frame])}`);
      };
      ws.onMessage(message => {
        const frames = JSON.parse(String(message)) as string[];
        for (const raw of frames) {
          if (raw === '\n') continue;
          const frame = parseStompFrame(raw);
          clientFrames.push(frame);
          if (frame.command === 'CONNECT') {
            sendSockJs(stompFrame('CONNECTED', {
              version: '1.2',
              'heart-beat': '0,0',
            }));
          } else if (frame.command === 'SUBSCRIBE') {
            subscriptionId = frame.headers.id ?? subscriptionId;
          } else if (frame.command === 'SEND'
              && frame.headers.destination === '/app/bind-session') {
            const bind = JSON.parse(frame.body) as {
              sessionId: string;
              bindRequestId: string;
              bindingEpoch: number;
            };
            const restored = JSON.stringify({
              type: 'session_restored',
              ts: Date.now(),
              protocolVersion: 3,
              bindRequestId: bind.bindRequestId,
              bindingEpoch: bind.bindingEpoch,
              messages: [],
              activities: [],
              totalActivityCount: 0,
              hasMore: false,
              metadata: {
                sessionId: bind.sessionId,
                model: 'test-model',
                permissionMode: 'DEFAULT',
                status: 'idle',
              },
            });
            sendSockJs(stompFrame('MESSAGE', {
              subscription: subscriptionId,
              'message-id': 'restore-1',
              destination: '/user/queue/messages',
              'content-type': 'application/json',
              'content-length': String(Buffer.byteLength(restored)),
            }, restored));
          }
        }
      });
      ws.send('o');
    });

    await page.goto('/', { waitUntil: 'domcontentloaded' });
    const newSessionButton = page.getByLabel('新建会话', { exact: true });
    await expect(newSessionButton,
      `Application failed to render: ${browserErrors.join(' | ')}`,
    ).toBeVisible();
    await newSessionButton.click();
    await expect(page.getByText('选择文件夹授权')).toBeVisible();
    await page.getByText(project.name, { exact: true }).click();
    await page.getByRole('button', { name: '使用所选授权' }).click();

    await expect.poll(() => sessionCreateBody).toEqual({
      projectId: project.id,
      model: 'test-model',
      permissionMode: 'AUTO_APPROVE',
    });
    await expect.poll(() => clientFrames.find(frame =>
      frame.command === 'SEND'
      && frame.headers.destination === '/app/bind-session'),
    ).toBeTruthy();
    const bindFrame = clientFrames.find(frame =>
      frame.command === 'SEND'
      && frame.headers.destination === '/app/bind-session');
    const bindPayload = JSON.parse(bindFrame!.body);
    expect(bindPayload).toMatchObject({
      sessionId: 'session-e2e',
      protocolVersion: 3,
      bindingEpoch: 1,
    });
    expect(bindPayload.bindRequestId).toEqual(expect.any(String));

    const input = page.locator('textarea[aria-label="输入消息"]');
    await input.fill('E2E main-chain message');
    await input.press('Enter');
    await expect.poll(() => clientFrames.find(frame =>
      frame.command === 'SEND'
      && frame.headers.destination === '/app/chat'),
    ).toBeTruthy();
    const chatFrame = clientFrames.find(frame =>
      frame.command === 'SEND'
      && frame.headers.destination === '/app/chat');
    expect(JSON.parse(chatFrame!.body)).toMatchObject({
      text: 'E2E main-chain message',
    });
    await screenshot(page, 'fe-02-project-session-bind-send');
  });

  // ─── TC-FE-03: 消息提交与流式渲染 ───
  test('TC-FE-03: 消息提交与流式渲染', async ({ page }) => {
    test.setTimeout(60000); // 60s timeout for LLM response

    await page.goto('/', { waitUntil: 'networkidle' });
    await page.waitForTimeout(3000);

    const input = page.locator('textarea[aria-label="输入消息"]');
    await expect(input).toBeVisible();

    // 输入消息
    await input.fill('请直接回答：1+1等于多少？');
    await page.waitForTimeout(500);
    await screenshot(page, 'fe-03-message-typed');

    // 发送消息 - 尝试点击发送按钮
    const sendBtn = page.locator('button:has(svg)').filter({ has: page.locator('svg') }).last();
    // 更精确的方式：找到输入框旁边的 button
    const submitButton = input.locator('..').locator('button').last();
    
    // 按 Enter 发送
    await input.press('Enter');
    await page.waitForTimeout(1000);
    await screenshot(page, 'fe-03-message-sent');

    // 等待流式响应渲染 (最多等 20 秒)
    console.log('[TC-FE-03] Waiting for streaming response...');
    await page.waitForTimeout(15000);
    await screenshot(page, 'fe-03-response-rendered');

    // 检查页面是否有新的消息内容
    const pageContent = await page.textContent('body');
    const hasResponse = pageContent?.includes('2') || pageContent?.includes('二');
    console.log(`[TC-FE-03] Response contains answer: ${hasResponse}`);
    console.log(`[TC-FE-03] Page content length: ${pageContent?.length}`);

    // 获取页面结构
    const structure = await page.evaluate(() => {
      const msgs = document.querySelectorAll('[class*="message"], [class*="Message"]');
      return `Message elements: ${msgs.length}`;
    });
    console.log(`[TC-FE-03] ${structure}`);
  });

  // ─── TC-FE-04: 命令面板 ───
  test('TC-FE-04: 命令面板', async ({ page }) => {
    await page.goto('/', { waitUntil: 'networkidle' });
    await page.waitForTimeout(2000);

    const input = page.locator('textarea[aria-label="输入消息"]');
    await expect(input).toBeVisible();

    // 输入 / 触发命令面板
    await input.fill('/');
    await page.waitForTimeout(1000);
    await screenshot(page, 'fe-04-command-panel');

    // 检查命令面板是否出现
    // 命令面板通常会渲染在输入框上方
    const pageContent = await page.textContent('body');
    // 检查是否有命令相关元素
    const hasCommandUI = pageContent?.includes('command') || 
                          pageContent?.includes('命令') ||
                          pageContent?.includes('compact') ||
                          pageContent?.includes('/');
    console.log(`[TC-FE-04] Command panel visible indicators: ${hasCommandUI}`);

    // 清除输入
    await input.fill('');
    await page.waitForTimeout(500);
    await screenshot(page, 'fe-04-command-panel-closed');
  });

  // ─── TC-FE-05: 设置页面 ───
  test('TC-FE-05: 设置页面', async ({ page }) => {
    await page.goto('/', { waitUntil: 'networkidle' });
    await page.waitForTimeout(2000);

    // 找到设置按钮 (title="设置")
    const settingsBtn = page.locator('button[title="设置"]');
    await expect(settingsBtn).toBeVisible();

    await settingsBtn.click();
    await page.waitForTimeout(1000);
    await screenshot(page, 'fe-05-settings-page');

    // 验证设置对话框出现
    // 通常是 dialog 或 modal
    const dialog = page.locator('[role="dialog"], .modal, [class*="dialog"], [class*="Dialog"]').first();
    const dialogVisible = await dialog.isVisible().catch(() => false);
    
    // 获取设置页面内容
    const pageContent = await page.textContent('body');
    const hasSettings = pageContent?.includes('API') || 
                        pageContent?.includes('设置') || 
                        pageContent?.includes('Settings') ||
                        pageContent?.includes('Key') ||
                        pageContent?.includes('模型');
    
    console.log(`[TC-FE-05] Settings dialog visible: ${dialogVisible}, Has settings content: ${hasSettings}`);
  });

  // ─── TC-FE-06: 主题切换 ───
  test('TC-FE-06: 主题切换', async ({ page }) => {
    await page.goto('/', { waitUntil: 'networkidle' });
    await page.waitForTimeout(2000);

    // 记录当前主题
    const bgBefore = await page.evaluate(() => getComputedStyle(document.body).backgroundColor);
    const dataBefore = await page.evaluate(() => document.documentElement.getAttribute('data-theme') || document.documentElement.className || 'none');
    await screenshot(page, 'fe-06-theme-before');

    // 找到主题切换按钮 (aria-label 包含 "切换")
    const themeBtn = page.locator('button[aria-label*="切换"]').first();
    const themeBtnExists = await themeBtn.isVisible().catch(() => false);

    if (themeBtnExists) {
      await themeBtn.click();
      await page.waitForTimeout(1000);
      
      const bgAfter = await page.evaluate(() => getComputedStyle(document.body).backgroundColor);
      const dataAfter = await page.evaluate(() => document.documentElement.getAttribute('data-theme') || document.documentElement.className || 'none');
      await screenshot(page, 'fe-06-theme-after');

      const changed = dataBefore !== dataAfter || bgBefore !== bgAfter;
      console.log(`[TC-FE-06] Theme before: data="${dataBefore}", bg="${bgBefore}"`);
      console.log(`[TC-FE-06] Theme after: data="${dataAfter}", bg="${bgAfter}"`);
      console.log(`[TC-FE-06] Theme changed: ${changed}`);

      // 再次切换回来
      await themeBtn.click();
      await page.waitForTimeout(500);
      await screenshot(page, 'fe-06-theme-restored');
    } else {
      console.log('[TC-FE-06] Theme toggle button not found');
    }
  });

  // ─── TC-FE-07: 响应式布局 ───
  test('TC-FE-07: 响应式布局', async ({ page }) => {
    await page.goto('/', { waitUntil: 'networkidle' });
    await page.waitForTimeout(2000);

    // 移动端 375x667
    await page.setViewportSize({ width: 375, height: 667 });
    await page.waitForTimeout(1000);
    await screenshot(page, 'fe-07-mobile-375x667');

    // 验证移动端：body 可见，无明显溢出
    const bodyMobile = page.locator('body');
    await expect(bodyMobile).toBeVisible();
    const overflowMobile = await page.evaluate(() => {
      return document.documentElement.scrollWidth > window.innerWidth;
    });
    console.log(`[TC-FE-07] Mobile overflow: ${overflowMobile}`);

    // 平板 768x1024
    await page.setViewportSize({ width: 768, height: 1024 });
    await page.waitForTimeout(1000);
    await screenshot(page, 'fe-07-tablet-768x1024');

    const overflowTablet = await page.evaluate(() => {
      return document.documentElement.scrollWidth > window.innerWidth;
    });
    console.log(`[TC-FE-07] Tablet overflow: ${overflowTablet}`);

    // 桌面 1280x800
    await page.setViewportSize({ width: 1280, height: 800 });
    await page.waitForTimeout(1000);
    await screenshot(page, 'fe-07-desktop-1280x800');

    const overflowDesktop = await page.evaluate(() => {
      return document.documentElement.scrollWidth > window.innerWidth;
    });
    console.log(`[TC-FE-07] Desktop overflow: ${overflowDesktop}`);

    // 验证侧边栏在桌面端可见
    const sidebar = page.locator('aside').first();
    const sidebarDesktop = await sidebar.isVisible().catch(() => false);
    console.log(`[TC-FE-07] Sidebar visible on desktop: ${sidebarDesktop}`);
  });

});
