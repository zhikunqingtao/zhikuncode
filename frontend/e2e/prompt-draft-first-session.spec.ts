import { test, expect, type Page } from '@playwright/test';

/**
 * 首会话草稿归属回归（P1 stale draft-key writes）
 *
 * 背景：无任何会话时，首条消息的提交/粘贴图片都会在中途创建会话
 * （ensureSessionReady：sessionId null → 真实 id），兜底草稿键 '__none__'
 * 上的草稿随迁移效应整体搬到新会话键。修复前，跨 await 的续体仍用
 * 渲染期捕获的兜底键写草稿：
 *   a) 提交成功后的 setInput('') 写进 '__none__'，已发送文本「复活」在
 *      新会话输入框里；
 *   b) 粘贴图片的 OSS 发布续体把附件写进 '__none__'，首条消息丢失附件。
 *
 * 本 spec 以全 mock 后端（HTTP page.route + SockJS/STOMP page.routeWebSocket，
 * 沿用 frontend-e2e-full.spec.ts TC-FE-02 的自包含模式）锁定两条验收：
 *   1) 无会话 → 输入文本 → 提交（途中授权建会话）→ 消息发出后输入框必须为空；
 *   2) 无会话 → 粘贴图片（OSS 路径，途中授权建会话）→ 附件落在新会话草稿上，
 *      chip 可见，且随 /app/chat 帧发出（attachments 携带 OSS url）。
 */

// ── STOMP 帧工具（与 frontend-e2e-full.spec.ts 相同的文件内自包含约定）──
interface StompFrame {
  command: string;
  headers: Record<string, string>;
  body: string;
}

function parseStompFrame(raw: string): StompFrame {
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
): string {
  const headerBlock = Object.entries(headers)
    .map(([name, value]) => `${name}:${value}`)
    .join('\n');
  return `${command}\n${headerBlock}\n\n${body}\0`;
}

const SESSION_ID = 'session-e2e-draft';
const OSS_IMAGE_URL = 'https://oss.example.com/clipboard/pasted-e2e.png';

const project = {
  id: 'project-e2e-draft',
  name: 'E2E Draft Project',
  workspaceRoot: '/workspace/e2e-draft',
  createdAt: '2026-01-01T00:00:00Z',
};

interface MockBackend {
  clientFrames: StompFrame[];
  ossUploadSessionIds: string[];
}

/** 全 mock 后端：HTTP API + SockJS/STOMP（bind → session_restored 提交会话） */
async function mockBackend(page: Page, opts: { ossConfigured: boolean }): Promise<MockBackend> {
  const clientFrames: StompFrame[] = [];
  const ossUploadSessionIds: string[] = [];
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
      } });
    } else if (url.pathname === '/api/projects' && request.method() === 'GET') {
      await route.fulfill({ json: [project] });
    } else if (url.pathname === '/api/sessions' && request.method() === 'POST') {
      await route.fulfill({ status: 201, json: {
        sessionId: SESSION_ID,
        projectId: project.id,
      } });
    } else if (url.pathname === '/api/sessions') {
      await route.fulfill({ json: { sessions: [], hasMore: false, nextCursor: null } });
    } else if (url.pathname === '/api/models') {
      await route.fulfill({ json: {
        models: [{ id: 'test-model', displayName: 'Test Model' }],
        defaultModel: 'test-model',
      } });
    } else if (url.pathname === '/api/skills') {
      await route.fulfill({ json: [] });
    } else if (url.pathname === '/api/config') {
      await route.fulfill({ json: { defaultModel: 'test-model' } });
    } else if (url.pathname === '/api/oss/status') {
      await route.fulfill({ json: { configured: opts.ossConfigured } });
    } else if (url.pathname === '/api/oss/clipboard-images'
        && request.method() === 'POST') {
      ossUploadSessionIds.push(request.headers()['x-session-id'] ?? '');
      await route.fulfill({ json: {
        fileName: 'pasted-e2e.png',
        size: 8,
        mediaType: 'image/png',
        url: OSS_IMAGE_URL,
      } });
    } else {
      await route.fulfill({ status: 404, json: { error: 'not mocked' } });
    }
  });

  await page.route('**/ws/info**', route => route.fulfill({ json: {
    websocket: true,
    cookie_needed: false,
    origins: ['*:*'],
    entropy: 123456,
  } }));

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

  return { clientFrames, ossUploadSessionIds };
}

/** 首个授权会话：对话框选择已有 Project 授权并确认（建会话 UI 链路） */
async function authorizeFirstSession(page: Page) {
  await expect(page.getByText('选择文件夹授权')).toBeVisible({ timeout: 15000 });
  await page.getByText(project.name, { exact: true }).click();
  await page.getByRole('button', { name: '使用所选授权' }).click();
}

function chatFrames(frames: StompFrame[]): StompFrame[] {
  return frames.filter(frame =>
    frame.command === 'SEND' && frame.headers.destination === '/app/chat');
}

test.describe('首会话草稿归属（create-session × draft writes）', () => {

  test('无会话首条文本消息：发出后新会话输入框必须为空', async ({ page }) => {
    const backend = await mockBackend(page, { ossConfigured: false });
    await page.goto('/', { waitUntil: 'domcontentloaded' });

    const textarea = page.locator('textarea[aria-label="输入消息"]');
    await expect(textarea).toBeVisible({ timeout: 15000 });

    // 无会话时输入草稿（写入兜底键 '__none__'）
    await textarea.fill('first message of a brand-new session');
    await textarea.press('Enter');

    // 提交中途创建首个会话（ensureSessionReady → 授权对话框 → 建会话 → 绑定）
    await authorizeFirstSession(page);

    // 消息经 STOMP 发出，且文本未被中途的清空/迁移吞掉
    await expect.poll(() => chatFrames(backend.clientFrames).length, {
      timeout: 15000,
    }).toBe(1);
    expect(JSON.parse(chatFrames(backend.clientFrames)[0].body)).toMatchObject({
      text: 'first message of a brand-new session',
    });

    // P1 验收 1：提交成功后的清空写在新会话键上 —— 输入框为空、文本不复活
    await expect(textarea).toHaveValue('');
  });

  test('无会话粘贴图片（OSS）：附件归属新会话并随首条消息发出', async ({ page }) => {
    const backend = await mockBackend(page, { ossConfigured: true });
    await page.goto('/', { waitUntil: 'domcontentloaded' });

    const textarea = page.locator('textarea[aria-label="输入消息"]');
    await expect(textarea).toBeVisible({ timeout: 15000 });

    // 无会话时粘贴图片：OSS 已配置 → 发布会先创建首个会话
    await page.evaluate(() => {
      const dataTransfer = new DataTransfer();
      dataTransfer.items.add(new File(
        [new Uint8Array([137, 80, 78, 71, 13, 10, 26, 10])],
        'pasted-e2e.png',
        { type: 'image/png' },
      ));
      const event = new ClipboardEvent('paste', {
        clipboardData: dataTransfer,
        bubbles: true,
        cancelable: true,
      });
      document.querySelector('textarea[aria-label="输入消息"]')!.dispatchEvent(event);
    });

    // 发布链路的 ensureSessionReady 打开授权对话框：完成建会话与绑定
    await authorizeFirstSession(page);

    // 粘贴图片经 OSS 发布，且 X-Session-Id 是新建会话
    await expect.poll(() => backend.ossUploadSessionIds.length, {
      timeout: 15000,
    }).toBe(1);
    expect(backend.ossUploadSessionIds[0]).toBe(SESSION_ID);

    // P1 验收 2：附件写在新会话草稿上 —— chip 在新会话输入区可见
    // （修复前附件被写进已迁空的 '__none__'，chip 不出现，消息将丢失附件）
    await expect(page.locator('img[alt="pasted-e2e.png"]')).toBeVisible({ timeout: 15000 });

    // 携带附件发出首条消息：/app/chat 帧必须包含该 OSS 附件
    await textarea.fill('message with a pasted image');
    await textarea.press('Enter');
    await expect.poll(() => chatFrames(backend.clientFrames).length, {
      timeout: 15000,
    }).toBe(1);
    const chatBody = JSON.parse(chatFrames(backend.clientFrames)[0].body) as {
      text: string;
      attachments: Array<{ type: string; name: string; url?: string }>;
    };
    expect(chatBody.text).toBe('message with a pasted image');
    expect(chatBody.attachments).toHaveLength(1);
    expect(chatBody.attachments[0]).toMatchObject({
      type: 'image',
      name: 'pasted-e2e.png',
      url: OSS_IMAGE_URL,
    });

    // 发送成功后输入框与附件条一并清空
    await expect(textarea).toHaveValue('');
    await expect(page.locator('img[alt="pasted-e2e.png"]')).toHaveCount(0);
  });
});
