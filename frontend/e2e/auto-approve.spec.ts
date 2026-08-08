import { expect, test } from '@playwright/test';

function parseStompFrame(raw: string) {
  const frame = raw.endsWith('\0') ? raw.slice(0, -1) : raw;
  const separator = frame.indexOf('\n\n');
  const head = separator >= 0 ? frame.slice(0, separator) : frame;
  const body = separator >= 0 ? frame.slice(separator + 2) : '';
  const [command, ...headerLines] = head.split('\n');
  const headers = Object.fromEntries(headerLines.map(line => {
    const colon = line.indexOf(':');
    return [line.slice(0, colon), line.slice(colon + 1)];
  }));
  return { command, headers, body };
}

function stompFrame(command: string, headers: Record<string, string>, body = '') {
  const headerBlock = Object.entries(headers)
    .map(([name, value]) => `${name}:${value}`)
    .join('\n');
  return `${command}\n${headerBlock}\n\n${body}\0`;
}

async function mockHttpApi(page: import('@playwright/test').Page) {
  const project = {
    id: 'project-auto-approve',
    name: 'AUTO_APPROVE Project',
    workspaceRoot: '/workspace/auto-approve',
    createdAt: '2026-01-01T00:00:00Z',
  };
  await page.route(url => url.pathname.startsWith('/api/'), async route => {
    const request = route.request();
    const pathname = new URL(request.url()).pathname;
    if (pathname === '/api/projects/directories') {
      await route.fulfill({ json: {
        roots: ['/workspace'], current: project.workspaceRoot,
        parent: '/workspace', directories: [],
      }});
    } else if (pathname === '/api/projects') {
      await route.fulfill({ json: [project] });
    } else if (pathname === '/api/sessions' && request.method() === 'POST') {
      await route.fulfill({ status: 201, json: {
        sessionId: 'session-auto-approve', projectId: project.id,
        permissionMode: 'DEFAULT',
      }});
    } else if (pathname === '/api/sessions') {
      await route.fulfill({ json: { sessions: [], hasMore: false, nextCursor: null } });
    } else if (pathname === '/api/skills') {
      await route.fulfill({ json: [] });
    } else if (pathname === '/api/config') {
      await route.fulfill({ json: { defaultModel: 'test-model' } });
    } else {
      await route.fulfill({ status: 404, json: { error: 'not mocked' } });
    }
  });
  return project;
}

test.describe('AUTO_APPROVE permission mode', () => {
  test('switches only after the bound server confirms AUTO_APPROVE', async ({ page }) => {
    const project = await mockHttpApi(page);
    let requestedMode: string | null = null;
    await page.route('**/ws/info**', route => route.fulfill({ json: {
      websocket: true, cookie_needed: false, origins: ['*:*'], entropy: 123456,
    }}));
    await page.routeWebSocket(/\/ws\/[^/]+\/[^/]+\/websocket$/, ws => {
      let subscriptionId = 'sub-0';
      const sendMessage = (payload: Record<string, unknown>) => {
        const body = JSON.stringify(payload);
        ws.send(`a${JSON.stringify([stompFrame('MESSAGE', {
          subscription: subscriptionId,
          'message-id': `message-${Date.now()}`,
          destination: '/user/queue/messages',
          'content-type': 'application/json',
          'content-length': String(Buffer.byteLength(body)),
        }, body)])}`);
      };
      ws.onMessage(message => {
        for (const raw of JSON.parse(String(message)) as string[]) {
          if (raw === '\n') continue;
          const frame = parseStompFrame(raw);
          if (frame.command === 'CONNECT') {
            ws.send(`a${JSON.stringify([stompFrame('CONNECTED', {
              version: '1.2', 'heart-beat': '0,0',
            })])}`);
          } else if (frame.command === 'SUBSCRIBE') {
            subscriptionId = frame.headers.id ?? subscriptionId;
          } else if (frame.command === 'SEND'
              && frame.headers.destination === '/app/bind-session') {
            const bind = JSON.parse(frame.body) as {
              sessionId: string; bindRequestId: string; bindingEpoch: number;
            };
            sendMessage({
              type: 'session_restored', ts: Date.now(), protocolVersion: 3,
              bindRequestId: bind.bindRequestId, bindingEpoch: bind.bindingEpoch,
              messages: [], activities: [], totalActivityCount: 0, hasMore: false,
              metadata: {
                sessionId: bind.sessionId, model: 'test-model',
                permissionMode: 'DEFAULT', status: 'idle',
              },
            });
          } else if (frame.command === 'SEND'
              && frame.headers.destination === '/app/permission-mode') {
            requestedMode = (JSON.parse(frame.body) as { mode: string }).mode;
            sendMessage({
              type: 'permission_mode_changed', ts: Date.now(),
              mode: 'AUTO_APPROVE', previous: 'DEFAULT',
            });
          }
        }
      });
      ws.send('o');
    });

    await page.goto('/');
    await page.locator('button[title="新建会话"]').click();
    await page.getByText(project.name, { exact: true }).click();
    await page.getByRole('button', { name: '使用所选授权' }).click();
    await page.locator('button[title="设置"]').click();

    const autoApprove = page.getByText('完全访问权限').first().locator('..');
    await expect(autoApprove).toBeEnabled();
    await autoApprove.click();

    await expect.poll(() => requestedMode).toBe('AUTO_APPROVE');
    await expect(page.locator('footer').getByText('完全访问权限')).toBeVisible();
  });

  test('does not allow a mode request before a session is bound', async ({ page }) => {
    await mockHttpApi(page);
    await page.goto('/');
    await page.locator('button[title="设置"]').click();

    const autoApprove = page.getByText('完全访问权限').first().locator('..');
    await expect(autoApprove).toBeDisabled();
    await expect(page.getByText('请先创建或选择会话后再设置权限模式。')).toBeVisible();
  });
});
