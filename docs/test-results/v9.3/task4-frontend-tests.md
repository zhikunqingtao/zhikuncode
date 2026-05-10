# Task 4 — 前端 vitest 全跑 + 覆盖率（证据）

> 执行时间：2026-05-09 23:10 → 23:11 (≈3.5 s)
> 命令：`npx vitest run --coverage --coverage.reportsDirectory=../docs/test-results/v9.3/coverage/frontend`

---

## 4.1 总体指标

| 维度 | 数值 |
| ---- | ---- |
| Test Files | **13** / 13 passed |
| Tests | **78 passed** / 16 skipped / **94 total** |
| FAILED | **0** |
| Duration | 3.53 s（transform 457ms, setup 1.33s, collect 1.06s, tests 182ms, env 13.30s, prepare 1.27s）|
| Coverage (line) | **6.97%** |
| Coverage (branch) | **55.15%** |
| Coverage (function) | **24.10%** |

---

## 4.2 测试文件逐条（13 全绿）

| # | 文件 | Tests | Skipped | 状态 |
| - | ---- | ----- | ------- | ---- |
| 1 | `src/store/__tests__/broadcastSync.test.ts` | 4 | 0 | ✅ 3 ms |
| 2 | `src/hooks/__tests__/useStreamingText.test.ts` | 7 | 0 | ✅ 3 ms |
| 3 | `src/store/__tests__/messageStore.test.ts` | 7 | 0 | ✅ 5 ms |
| 4 | `src/__tests__/stores/coordinatorStore.test.ts` | 12 | **7** | ✅ 32 ms |
| 5 | `src/__tests__/stores/routeBoundary.test.ts` | 4 | 0 | ✅ 4 ms |
| 6 | `src/__tests__/stores/immerImmutability.test.ts` | 4 | 0 | ✅ 7 ms |
| 7 | `src/__tests__/stores/storeLifecycle.test.ts` | 16 | 0 | ✅ 7 ms |
| 8 | `src/store/__tests__/dispatch.test.ts` | 10 | 0 | ✅ 9 ms |
| 9 | `src/components/browser/BrowserReplayTimeline.test.tsx` | 8 | **5** | ✅ 99 ms |
| 10 | `src/components/visualization/shared/AgentDAGChart.test.tsx` | 6 | **4** | ✅ 4 ms |
| 11 | `src/store/__tests__/configStore.test.ts` | 7 | 0 | ✅ 4 ms |
| 12 | `src/store/__tests__/sessionStore.test.ts` | 6 | 0 | ✅ 3 ms |
| 13 | `src/store/__tests__/useWebSocket.test.ts` | 3 | 0 | ✅ 2 ms |

16 个 skipped 分布在 3 个骨架文件（coordinatorStore 7 + BrowserReplayTimeline 5 + AgentDAGChart 4），均为 `.skip` 占位 TODO，为 Task 3/4/5 差异化升级后续阶段预留。

---

## 4.3 Store 层覆盖率（核心业务态）

| Store | Statements | Branch | 备注 |
| ----- | ---------- | ------ | ---- |
| `costStore.ts` | **100%** | 100% | 成本追踪完整覆盖 |
| `notificationStore.ts` | **100%** | 100% | 通知中心完整覆盖 |
| `permissionStore.ts` | **100%** | 100% | 权限控制完整覆盖 |
| `appUiStore.ts` | **100%** | 100% | UI 态完整覆盖 |
| `dialogStore.ts` | **90%** | 100% | 对话框 |
| `coordinatorStore.ts` | **80.91%** | 83.78% | 多 Agent 协作主态（Task 3 核心）|
| `messageStore.ts` | **74.50%** | 92.30% | 消息中心 |
| `bridgeStore.ts` | **74.28%** | 88.88% | SSE/WS 桥接 |
| `broadcastMiddleware.ts` | **70.37%** | 57.14% | 跨标签同步中间件 |
| `sessionStore.ts` | **68.96%** | 100% | 会话态 |
| `taskStore.ts` | 52.50% | 100% | 任务态 |
| `planStore.ts` | 40.00% | 100% | 计划态 |
| `configStore.ts` | 39.18% | 100% | 配置态 |
| `inboxStore.ts` | 37.50% | 100% | 收件箱 |
| `mcpStore.ts` | 20.14% | 100% | MCP 状态 |
| `swarmStore.ts` | 14.28% | 100% | Swarm 集群 |
| `artifactStore.ts` | 0% | - | 未测 |
| `codeImpactStore.ts` | 0% | - | 未测 |
| `insightStore.ts` | 0% | - | 未测 |
| `codePathStore.ts` | 0% | - | 未测 |
| `commandStore.ts` | 0% | - | 未测 |
| `complexityStore.ts` | 0% | - | 未测 |
| `diagramStore.ts` | 0% | - | 未测 |
| `fileTreeStore.ts` | 0% | - | 未测 |
| `capabilityStore.ts` | 0% | - | 未测 |
| `toolStore.ts` | 0% | - | 未测 |

Store 层整体 **31.68%**（明显高于 UI 组件侧），核心业务态（Task 3 coordinatorStore 80.9% / messageStore 74.5% / bridgeStore 74.3%）均达到 可靠水平。

---

## 4.4 Hook 层覆盖率

| Hook | Statements | Branch |
| ---- | ---------- | ------ |
| `useStreamingText` | **76.59%** | 100% |
| `useKeybinding` | 0% | - |
| `useMediaQuery` | 0% | - |
| `useGlobalKeyboard` | 0% | - |
| `useWebSocket` | 0% | - |
| `useMcpSubscription` | 0% | - |

Hook 整体 **9.27%**，仅 useStreamingText 深度覆盖。

---

## 4.5 UI 组件层覆盖率（风险点）

| 分类 | 平均覆盖率 | 状态 |
| ---- | ---------- | ---- |
| `src/components/browser/*` | 近 0% | R-FE-01 |
| `src/components/visualization/backend/*` (7 文件) | 0% | R-FE-01 |
| `src/components/visualization/shared/*` (7 文件) | 近 0% | R-FE-01 |
| `src/components/skills/*` | 0% | R-FE-01 |
| `src/components/settings/*` | 0% | R-FE-01 |
| `src/components/status/*` | 0% | R-FE-01 |
| `src/components/theme/*` | 0% | R-FE-01 |

大量 UI 组件未被单测覆盖，依赖 Playwright E2E 兜底（见 v9.2 报告第 §8 / 本次 Task 8）。

---

## 4.6 风险记录

| # | 风险 | 严重度 | 决策 |
| - | ---- | ------ | ---- |
| R-FE-01 | 前端整体 Statements Coverage 6.97%，UI 组件层多数 0% | **P1** | **重大变更，本轮跳过**。UI 组件层测试补齐需 E2E + 组件单测配合（React Testing Library），工作量 30+ 新测试文件，属专项迭代。核心 Store 层 31.68% + 关键业务 store 多数 70-100% 已达防退化阈值 |
| R-FE-02 | `playwright.config.ts` 被含在 coverage 统计里被记 0%（非业务代码），拉低总分 | P3 | **接受**。属配置文件，不影响业务判断 |
| R-FE-03 | 16 skipped 均为 `.skip` 占位 TODO，明确标记为"下阶段实现" | P3 | **接受**。`coordinatorStore (7) + BrowserReplayTimeline (5) + AgentDAGChart (4)` 后续阶段补齐 |

---

## 4.7 证据清单

- `docs/test-results/v9.3/coverage/frontend/index.html` — vitest v8 HTML 报告（交互式）
- `docs/test-results/v9.3/coverage/frontend/coverage-final.json` — JSON 精确数据
- `/tmp/zk-v9.3/frontend-test.log` — 完整执行日志（含 5000+ 文件级覆盖明细）

---

## 4.8 判定

✅ **Task 4 部分 PASS** — 78/78 活跃用例 100% 通过，0 failure；核心 Store（coordinatorStore / messageStore / bridgeStore / permissionStore）覆盖率 ≥70%。
⚠️ **Coverage Gap** — 整体 Statements 6.97% 过低，主因 UI 组件层未被单测覆盖。记入 R-FE-01，本轮跳过不修复，依赖 Task 7/8 E2E 兜底。
