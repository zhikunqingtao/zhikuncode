import { test, expect, type BrowserContext, type Page } from '@playwright/test';

test.use({ reducedMotion: 'reduce' });

type Theme = 'light' | 'dark' | 'glass';
const viewports = [
  { name: 'phone', width: 393, height: 852 },
  { name: 'tablet', width: 800, height: 1024 },
  { name: 'desktop', width: 1440, height: 900 },
];

/** Isolated HTTP fixtures: these tests never call a model or change local user settings. */
async function mockBackend(context: BrowserContext, theme: Theme = 'dark') {
  const skills = [
    { id: 'commit', name: 'commit', description: '根据当前变更生成清晰的提交说明。', source: 'BUNDLED', enabled: true },
    { id: 'review', name: 'review', description: '检查代码变更，查找影响正确性和可维护性的问题。', source: 'BUNDLED', enabled: false },
    { id: 'project-quality', name: `项目质量检查-${'VeryLongSkillName'.repeat(7)}`, description: '支持中文名称、很长的目录路径以及多段技能说明。', source: 'PROJECT', enabled: true },
    ...['debug', 'verify', 'remember', 'test', 'software-architecture', 'csv-data-summarizer'].map(name => ({
      id: name, name, description: `${name} 的工作流说明`, source: 'USER', enabled: true,
    })),
  ];
  let failNextSave = false;
  let stateError: string | null = null;
  await context.addInitScript(mode => {
    localStorage.setItem('zhikun.workbench.default-view', 'development');
    localStorage.setItem('ai-coder-config', JSON.stringify({ state: {
      theme: { mode, accentColor: '#0D9488', fontSize: 'medium', fontFamily: 'monospace', borderRadius: 'md' },
      themePreferenceSet: true, locale: 'zh-CN',
    }, version: 2 }));
  }, theme);
  await context.route(url => url.pathname.startsWith('/api/'), async route => {
    const url = new URL(route.request().url());
    const path = url.pathname;
    if (path === '/api/skills/manage') {
      return route.fulfill({ json: { skills, total: skills.length, enabledCount: skills.filter(s => s.enabled).length, stateError } });
    }
    if (path.startsWith('/api/skills/')) {
      const pieces = path.split('/');
      const id = decodeURIComponent(pieces[3] === 'manage' || pieces[3] === 'detail' ? pieces[4] : pieces[3]);
      const skill = skills.find(s => s.id === id || s.name === id);
      if (!skill) return route.fulfill({ status: 404, json: {} });
      if (route.request().method() === 'PATCH') {
        if (stateError) return route.fulfill({ status: 503, json: { error: { code: 'SKILL_STATE_UNAVAILABLE', message: stateError } } });
        if (failNextSave) { failNextSave = false; return route.fulfill({ status: 500, json: {} }); }
        skill.enabled = url.searchParams.get('enabled') === 'true';
        return route.fulfill({ json: skill });
      }
      if (pieces[3] !== 'manage' && !skill.enabled) return route.fulfill({ status: 404, json: {} });
      return route.fulfill({ json: { ...skill, filePath: `/workspace/${'long-directory/'.repeat(15)}SKILL.md`, content: `# ${skill.name}\n\n技能正文：只在调用时加载。\n${'very-long-word-'.repeat(50)}\n\n完成后验证结果。` } });
    }
    if (path === '/api/commands') return route.fulfill({ json: [] });
    if (path === '/api/config') return route.fulfill({ json: { theme, locale: 'zh-CN' } });
    if (path === '/api/models') return route.fulfill({ json: { models: [{ id: 'test-model', displayName: '测试模型', supportsImages: false, maxImages: 0 }], defaultModel: 'test-model' } });
    if (path.startsWith('/api/sessions')) return route.fulfill({ json: { sessions: [], hasMore: false, nextCursor: null } });
    if (path === '/api/mcp/services') return route.fulfill({ json: { services: [], total: 0, enabledCount: 0 } });
    if (path === '/api/memory/file') return route.fulfill({ json: { content: '', entries: [], size: 0, maxSize: 10000, updatedAt: null } });
    if (path === '/api/files/reference-capability') return route.fulfill({ json: { mode: 'unavailable' } });
    return route.fulfill({ json: {} });
  });
  return {
    skills,
    failSave: () => { failNextSave = true; },
    corruptState: () => {
      stateError = '无法读取 Skill 状态文件。所有技能已暂时关闭，请修复原文件后重启后端。';
      skills.forEach(skill => { skill.enabled = false; });
    },
  };
}

async function openManagement(page: Page) {
  if ((page.viewportSize()?.width ?? 1440) < 768) {
    await page.getByRole('button', { name: '更多', exact: true }).click();
  }
  await page.getByRole('button', { name: 'Skill 管理', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: 'Skill 管理', exact: true });
  await expect(dialog).toBeVisible();
  await expect(dialog.getByRole('switch', { name: /^(关闭|启用) commit$/ })).toBeVisible();
  await dialog.evaluate(async element => {
    await Promise.all(element.getAnimations().filter(animation => animation.effect?.getComputedTiming().iterations !== Infinity)
      .map(animation => animation.finished.catch(() => {})));
  });
  return dialog;
}

for (const viewport of viewports) {
  test.describe(viewport.name, () => {
    test.use({ viewport, isMobile: viewport.width < 768, hasTouch: viewport.width < 1024, deviceScaleFactor: 1 });
    for (const theme of ['light', 'dark', 'glass'] as const) {
      test(`${theme}: layout, details, search and toggles`, async ({ page, context }, testInfo) => {
        await mockBackend(context, theme);
        await page.goto('/');
        const dialog = await openManagement(page);
        const bounds = await dialog.boundingBox();
        expect(bounds!.x).toBeGreaterThanOrEqual(0);
        expect(bounds!.width).toBeLessThanOrEqual(viewport.width);
        if (viewport.width < 768) expect(Math.abs(bounds!.height - viewport.height)).toBeLessThanOrEqual(1);
        const cards = dialog.locator('article');
        const first = await cards.nth(0).boundingBox();
        const second = await cards.nth(1).boundingBox();
        if (viewport.width >= 1024) expect(Math.abs(first!.y - second!.y)).toBeLessThanOrEqual(1);
        else expect(second!.y).toBeGreaterThan(first!.y);

        const toggle = dialog.getByRole('switch', { name: '关闭 commit', exact: true });
        const toggleBounds = await toggle.boundingBox();
        expect(toggleBounds!.width).toBeGreaterThanOrEqual(44);
        expect(toggleBounds!.height).toBeGreaterThanOrEqual(44);
        await toggle.click();
        await expect(dialog.getByRole('switch', { name: '启用 commit', exact: true })).toHaveAttribute('aria-checked', 'false');
        await dialog.getByRole('switch', { name: '启用 commit', exact: true }).click();
        await expect(dialog.getByRole('switch', { name: '关闭 commit', exact: true })).toHaveAttribute('aria-checked', 'true');
        await dialog.getByRole('textbox', { name: '搜索 Skill' }).fill('项目质量');
        await expect(cards).toHaveCount(1);
        await dialog.getByRole('button', { name: /^展开 项目质量/ }).click();
        await expect(dialog.locator('pre')).toContainText('技能正文');
        expect(await dialog.evaluate(element => element.scrollWidth <= element.clientWidth)).toBe(true);
        expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
        await page.screenshot({ path: testInfo.outputPath(`${viewport.name}-${theme}.png`), fullPage: true });
        await dialog.getByRole('button', { name: '关闭 Skill 管理', exact: true }).click();
        await expect(dialog).toBeHidden();
      });
    }
  });
}

test.describe('live availability', () => {
  test.use({ viewport: { width: 1440, height: 900 } });
  test('supports constructor switches and manage runtime details', async ({ page, context }) => {
    const backend = await mockBackend(context);
    backend.skills.push(
      { id: 'constructor', name: 'constructor', description: '合法技能名称', source: 'PROJECT', enabled: true },
      { id: 'manage', name: 'manage', description: '合法管理同名技能', source: 'PROJECT', enabled: true },
    );
    await page.goto('/');
    const dialog = await openManagement(page);
    await dialog.getByRole('textbox', { name: '搜索 Skill' }).fill('constructor');
    await dialog.getByRole('switch', { name: '关闭 constructor', exact: true }).click();
    await expect(dialog.getByRole('switch', { name: '启用 constructor', exact: true })).toHaveAttribute('aria-checked', 'false');
    expect(backend.skills.find(skill => skill.id === 'constructor')?.enabled).toBe(false);
    await page.keyboard.press('Escape');
    const input = page.getByRole('textbox', { name: '输入消息' });
    await input.fill('/skill manage');
    const request = page.waitForRequest(request => new URL(request.url()).pathname === '/api/skills/detail/manage');
    await page.getByRole('option', { name: /\/skill manage/ }).click();
    await request;
    const detail = page.getByRole('dialog', { name: '技能详情：manage', exact: true });
    await expect(detail.locator('pre')).toContainText('# manage');
    await expect(detail.getByRole('button', { name: '执行技能', exact: true })).toBeEnabled();
  });

  test('shows damaged state without blocking the app or permitting settings overwrite', async ({ page, context }) => {
    const backend = await mockBackend(context);
    backend.corruptState();
    let saveRequests = 0;
    page.on('request', request => { if (request.method() === 'PATCH') saveRequests++; });
    await page.goto('/');
    const dialog = await openManagement(page);
    await expect(dialog.getByRole('alert')).toContainText('修复原文件后重启后端');
    await expect(dialog.getByText('0 个已启用', { exact: true })).toBeVisible();
    for (const toggle of await dialog.getByRole('switch').all()) await expect(toggle).toBeDisabled();
    await dialog.getByRole('button', { name: '展开 commit 详情', exact: true }).click();
    await expect(dialog.locator('pre')).toContainText('技能正文');
    await dialog.getByRole('button', { name: '刷新 Skill 列表', exact: true }).click();
    await expect(dialog.getByRole('alert')).toContainText('所有技能已暂时关闭');
    expect(saveRequests).toBe(0);
    await page.keyboard.press('Escape');
    await page.getByRole('button', { name: 'MCP 管理', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'MCP 管理', exact: true })).toBeVisible();
  });

  test('updates command candidates, preserves failed saves and synchronizes another device', async ({ page, context }) => {
    const backend = await mockBackend(context);
    await page.goto('/');
    let dialog = await openManagement(page);
    backend.failSave();
    await dialog.getByRole('switch', { name: '关闭 commit', exact: true }).click();
    await expect(dialog.getByRole('alert')).toContainText('保存技能设置失败');
    await expect(dialog.getByRole('switch', { name: '关闭 commit', exact: true })).toHaveAttribute('aria-checked', 'true');
    await dialog.getByRole('switch', { name: '关闭 commit', exact: true }).click();
    await expect(dialog.getByRole('switch', { name: '启用 commit', exact: true })).toBeVisible();
    await dialog.getByRole('button', { name: '关闭 Skill 管理', exact: true }).click();
    const input = page.getByRole('textbox', { name: '输入消息' });
    await input.fill('/skill commit');
    await expect(page.getByRole('option', { name: /\/skill commit/ })).toHaveCount(0);
    await input.press('Escape');
    await input.fill('');
    dialog = await openManagement(page);
    // Emulate a second device enabling the skill through the same backend.
    backend.skills[0].enabled = true;
    await expect(dialog.getByRole('switch', { name: '关闭 commit', exact: true })).toBeVisible({ timeout: 8000 });
    await page.keyboard.press('Escape');
    await expect(dialog).toBeHidden();
    await expect(page.getByRole('button', { name: 'Skill 管理', exact: true })).toBeFocused();
    await input.fill('/skill commit');
    await expect(page.getByRole('option', { name: /\/skill commit/ })).toBeVisible();
  });

  test('retains MCP and memory entry points and traps keyboard focus', async ({ page, context }) => {
    await mockBackend(context);
    await page.goto('/');
    const dialog = await openManagement(page);
    await dialog.getByRole('button', { name: '关闭 Skill 管理', exact: true }).focus();
    await page.keyboard.press('Shift+Tab');
    expect(await dialog.evaluate(element => element.contains(document.activeElement))).toBe(true);
    await page.keyboard.press('Escape');
    await page.getByRole('button', { name: 'MCP 管理', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'MCP 管理', exact: true })).toBeVisible();
    await page.getByRole('button', { name: '关闭 MCP 管理' }).click();
    await page.getByRole('button', { name: '记忆', exact: true }).click();
    await expect(page.getByRole('heading', { name: /记忆/ }).last()).toBeVisible();
  });
});
