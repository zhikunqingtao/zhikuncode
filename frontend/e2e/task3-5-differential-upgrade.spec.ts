/**
 * Task 3-5 差异化升级 E2E 测试
 * 引用：docs/Task3-5差异化升级功能测试方案.md
 *
 * 本文件先落地 4 条 P0 红线 + 跨模块 TC-X-01/02 骨架，其余用例由后续迭代补齐。
 * 执行前提：./stop.sh && ./start.sh 三端已就绪，http://localhost:5173 可访问。
 */
import { test, expect, type APIRequestContext } from '@playwright/test';

const BACKEND = process.env.BACKEND_URL || 'http://localhost:8080';

/* ------------------------------------------------------------------
 * 辅助：三端健康检查（不健康时整组 skip，避免误报 FAIL）
 * ------------------------------------------------------------------ */
async function ensureBackendHealthy(request: APIRequestContext): Promise<boolean> {
  try {
    const res = await request.get(`${BACKEND}/actuator/health`, { timeout: 5000 });
    return res.ok();
  } catch {
    return false;
  }
}

function rid(prefix: string): string {
  return `${prefix}-${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
}

/* ------------------------------------------------------------------
 * 说明（P2-C 修复）：
 *   以下 6 个用例仍处于「骨架占位」阶段，断言只是 `expect(true).toBe(true)`
 *   之类的自证 pass，若直接随 CI 运行会造成「绿色错觉」。
 *   因此暂用 `test.describe.skip` 整体跳过，每个用例保留 TODO(P0 实施) 标记，
 *   待实际断言逻辑补全后再改回 `test.describe` 启用。
 *   参考：docs/Task3-5差异化升级功能测试方案.md
 * ------------------------------------------------------------------ */

/* ==================================================================
 * Task 3：CoordinatorEventBus 跨用户隔离
 * ================================================================== */
test.describe.skip('TC-T3-SEC-01: CoordinatorEventBus 跨用户订阅隔离（P0 红线）[pending P0 实施]', () => {
  test('User B 不应收到 User A 的 /user/queue/coordinator/{sid} 消息', async ({ request, page }) => {
    const healthy = await ensureBackendHealthy(request);
    test.skip(!healthy, '后端未就绪，跳过（需 ./start.sh）');

    // TODO(P0 实施): 双 principal STOMP 客户端 + 观测 User B 订阅队列无 A 的消息
    // 断言红线：B 的 frame 计数 === 0；A 的 frame 计数 ≥ 1
    expect(true).toBe(true); // 骨架占位，实施时替换
  });
});

/* ==================================================================
 * Task 4：自动路由默认关闭，零副作用
 * ================================================================== */
test.describe.skip('TC-T4-E2E-01: VISUALIZATION_AUTO_ROUTING_ENABLED=false 默认零副作用（P0 红线）[pending P0 实施]', () => {
  test('默认未开启 auto-routing 时，消息回复链路不调用 SideQueryService', async ({ request }) => {
    const healthy = await ensureBackendHealthy(request);
    test.skip(!healthy, '后端未就绪，跳过');

    const sid = rid('sess');
    // TODO(P0 实施): 发起一条含 tool_use(bash) 的消息，观察 /metrics 中
    // `visualization_classifier_skipped_total{reason="disabled"}` 计数 +1，
    // 且 `side_query_invoked_total` 不变。
    expect(sid).toMatch(/^sess-/);
  });
});

/* ==================================================================
 * Task 5：浏览器快照安全红线
 * ================================================================== */
test.describe.skip('TC-T5-SEC-03: /api/browser/replay/{sid} 不落库不写文件（P0 红线）[pending P0 实施]', () => {
  test('GET 结果仅来自内存缓存，disk 无 snapshotId 命中', async ({ request }) => {
    const healthy = await ensureBackendHealthy(request);
    test.skip(!healthy, '后端未就绪，跳过');

    const sid = rid('replay');
    const before = await request.get(`${BACKEND}/api/browser/replay/${sid}`);
    expect(before.ok()).toBeTruthy();
    const body = await before.json();
    expect(Array.isArray(body)).toBeTruthy();
    // TODO(P0 实施): 文件系统 grep 断言（CI 中 `grep -r snapshotId workspace/ log/` 应无命中）
  });
});

test.describe.skip('TC-T5-SEC-04: /browser-snapshot 不触发 Bash / shell 执行器（P0 红线）[pending P0 实施]', () => {
  test('REMOTE_SAFE_COMMANDS 路径不经过 Bash 执行链', async ({ request }) => {
    const healthy = await ensureBackendHealthy(request);
    test.skip(!healthy, '后端未就绪，跳过');

    // TODO(P0 实施): 触发 /browser-snapshot 命令，观察调用链日志无 BashToolExecutor / ProcessBuilder
    expect(true).toBe(true);
  });
});

/* ==================================================================
 * 跨模块冒烟
 * ================================================================== */
test.describe.skip('TC-X-01: /api/health/capabilities 显示 BROWSER_AUTOMATION=available [pending P0 实施]', () => {
  test('三端健康检查返回 4/4 能力', async ({ request }) => {
    const healthy = await ensureBackendHealthy(request);
    test.skip(!healthy, '后端未就绪，跳过');

    const res = await request.get(`${BACKEND}/api/health/capabilities`);
    if (res.status() === 404) {
      test.skip(true, '/api/health/capabilities 尚未实现，跳过');
      return;
    }
    expect(res.ok()).toBeTruthy();
    const caps = await res.json();
    expect(caps).toBeDefined();
  });
});

test.describe.skip('TC-X-02: Coordinator 事件 + 浏览器快照并行互不干扰 [pending P0 实施]', () => {
  test('同一会话双通道隔离', async ({ request }) => {
    const healthy = await ensureBackendHealthy(request);
    test.skip(!healthy, '后端未就绪，跳过');

    // TODO(实施): 并行启动 multi-agent + /browser-snapshot，验证双队列独立
    expect(true).toBe(true);
  });
});
