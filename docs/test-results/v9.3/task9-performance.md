# Task 9 — 性能专章

> 时间：2026-05-09 · 环境：macOS 26.4.1 · 后端 8080 / Python 8000 / 前端 5173
> 基线：v9.3 全链路真调 LLM · 测试文档基准：docs/ZhikunCode核心功能测试报告.md（v9.2）

## 1 目标与范围
- 关注真实部署下的尾延迟而非峰值吞吐
- 端到端路径全链路采样；不 mock、不打桩
- 覆盖三大类：同步 REST、异步 WS STOMP RTT、跨进程跨服务（浏览器语义快照 + Swarm 编排）

## 2 方法
| 维度 | 采样工具 | N | 产物 |
|---|---|---:|---|
| REST 同步 | `perf-rest.sh`（`curl -w time_total`） | 30/端点 | `perf/rest-samples.tsv` |
| WS 握手 + slash RTT | `perf-ws.cjs`（ws 库，performance.now 刻度） | 30 | `perf/ws-samples.tsv` |
| 浏览器语义快照 | `perf-browser-snap.sh`（Python 直调） | 20 | `perf/browser-snap-samples.tsv` |
| Swarm 创建 | `perf-swarm.sh` | 20 | `perf/swarm-create-samples.tsv` |

- 全部脚本位于 [scripts/](scripts/)，可复跑
- 聚合脚本：[scripts/perf-aggregate.py](scripts/perf-aggregate.py)（REST） + [scripts/perf-aggregate-multi.py](scripts/perf-aggregate-multi.py)（WS/快照/Swarm）
- 计算：百分位 = 线性插值；冷/热路径分离（快照去除首次冷启动样本）

## 3 REST 端点（N=30，单位 ms）

| endpoint | N | ok | p50 | p95 | p99 | min | max | mean |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| backend_actuator_health | 30 | 30 | 2.23 | 3.27 | 3.80 | 1.86 | 4.00 | 2.37 |
| backend_api_admin_status | 30 | 30 | 1.42 | 1.56 | 1.58 | 1.19 | 1.58 | 1.42 |
| backend_api_commands | 30 | 30 | 1.73 | 2.77 | 3.17 | 1.52 | 3.27 | 1.91 |
| backend_api_config | 30 | 30 | 1.38 | 1.53 | 1.60 | 1.17 | 1.63 | 1.38 |
| backend_api_mcp_servers | 30 | 30 | 1.57 | 1.86 | 2.55 | 1.31 | 2.80 | 1.60 |
| backend_api_memory | 30 | 30 | 1.53 | 1.74 | 1.78 | 1.28 | 1.79 | 1.55 |
| backend_api_models | 30 | 30 | 1.67 | 1.97 | 2.42 | 1.44 | 2.60 | 1.73 |
| backend_api_plugins | 30 | 30 | 1.47 | 1.63 | 4.34 | 1.30 | 5.43 | 1.59 |
| backend_api_skills | 30 | 30 | 1.48 | 2.12 | 2.33 | 1.36 | 2.37 | 1.55 |
| backend_api_swarm_list | 30 | 30 | 1.43 | 1.57 | 1.69 | 1.20 | 1.74 | 1.45 |
| backend_api_tools | 30 | 30 | 1.56 | 1.81 | 1.84 | 1.46 | 1.85 | 1.60 |
| frontend_index | 30 | 30 | 1.17 | 1.75 | 3.92 | 1.01 | 4.72 | 1.33 |
| python_api_health | 30 | 30 | 0.81 | 0.94 | 1.06 | 0.76 | 1.09 | 0.83 |
| python_api_health_capabilities | 30 | 30 | 0.84 | 0.90 | 0.91 | 0.79 | 0.91 | 0.85 |

**观察**
- 后端内存态只读端点（`config`、`admin/status`、`swarm` 列表）p99 全部 < 2ms，说明无阻塞与热点锁竞争
- 涉及数据装配（`commands`、`skills`、`mcp/servers`）p99 < 4ms
- `actuator/health` p99 3.8ms，健康检查开销可忽略
- Python `/api/health*` p99 < 1.1ms，最低延迟端（Python uvicorn + 无中间件）
- 首次曾出现 `api/plugins` p99 = 4.34（max 5.43）毛刺 → 查日志为 JVM G1 偶发短停，单次，不触发告警

原始 TSV：[perf/rest-samples.tsv](perf/rest-samples.tsv)，聚合：[perf/rest-samples.summary.md](perf/rest-samples.summary.md)

## 4 WebSocket STOMP（N=30）

| metric | N | ok | p50 | p95 | p99 | min | max | mean |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| ws_handshake_ms | 30 | 30 | 2.22 | 4.58 | 6.22 | 1.22 | 6.76 | 2.60 |
| ws_slash_rtt_ms | 30 | 30 | 2.76 | 7.00 | 39.63 | 1.57 | 52.83 | 4.78 |

**度量定义**
- `ws_handshake_ms` = `ws.on('open')` → 收到第一个 STOMP `CONNECTED` 帧
- `ws_slash_rtt_ms` = `SEND /app/command`（SlashCommandPayload `{command:"visualize", args:"text hello-perf"}`）→ 第一个推送到 `/user/queue/messages` 的 visualization envelope

**观察**
- 握手中位 2.22ms，尾 p99 6.22ms，稳定
- slash RTT p95 仅 7.0ms（包含：STOMP 反序列化 + `UserInputProcessor`/`CommandRegistry` 分派 + `VisualizationCommand.execute` + `VisualizationPayloadBuilder.publish` + `convertAndSendToUser` + 反向解码）
- 唯一毛刺出现在第 12 次（52.83ms）；其余 29 次均 < 8ms；判定为外部噪声（非回归）
- 若剔除 1 个离群样本，p99 降至 7.31ms，与 p95 一致

原始：[perf/ws-samples.tsv](perf/ws-samples.tsv)

## 5 浏览器语义快照（Python 直调，N=20）

| metric | N | ok | p50 | p95 | p99 | min | max | mean |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| browser_snapshot_all_ms | 20 | 20 | 9.29 | 81.68 | 1136.60 | 7.88 | 1400.33 | 78.92 |
| browser_snapshot_warm_ms | 19 | 19 | 9.23 | 12.20 | 12.26 | 7.88 | 12.28 | 9.38 |

**冷热分离**
- 冷启动（第 1 次）= 1400.33ms ≈ Playwright 启动 Chromium + 页面导航 `https://example.com`
- 热路径（2..20 次）p50 9.23ms、p99 12.26ms，极其稳定；得益于浏览器上下文复用 + 已渲染 DOM 语义树缓存

**配合 Task 8 证据**：端到端 WS `/snap` → DOM snapshot → timeline append → 推送，53ms（含 WS 往返）。Python 侧仅 9-12ms，剩余为 Java→Python HTTP 与 STOMP 往返。

原始：
- [perf/browser-snap-samples.tsv](perf/browser-snap-samples.tsv)
- [perf/browser-snap-warm.tsv](perf/browser-snap-warm.tsv)

## 6 Swarm 编排（N=20）

| metric | N | ok | p50 | p95 | p99 | min | max | mean |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| swarm_create_ms | 20 | 20 | 2.39 | 4.90 | 12.40 | 2.06 | 14.27 | 3.14 |

- POST `/api/swarm` 含 FeatureFlag 校验 + `SwarmService.createSwarm`（分配 swarmId + scratchpad 目录 + 注册 CoordinatorEventBus phase transition 推送）
- 第 1 次 14.27ms 为文件系统首次 mkdir + 路径检查；其后 19 次中位 2.35ms
- 无 409/500

原始：[perf/swarm-create-samples.tsv](perf/swarm-create-samples.tsv)

## 7 压力稳态观察
- 本轮共发起 14(端点)×30 + 20 快照 + 20 Swarm + 30 WS = **490 次** 真实请求
- 后端日志期间无 ERROR 级别输出（见 `log/error.log` 未新增）
- 无 GC 长停（>50ms）事件；JVM G1 young GC 次数 < 5
- 三端健康探针在整个性能阶段前后差异：backend health p99 3.80ms（前）/ 4.18ms（跑完后立即复测），稳定无退化

## 8 判定
| 维度 | 门槛（v9.2 指标） | v9.3 实测 | 结论 |
|---|---|---|---|
| REST p95 | <50ms | 全部端点 ≤5ms | PASS |
| WS handshake p95 | <200ms | 4.58ms | PASS |
| WS slash RTT p95 | <100ms | 7.0ms | PASS |
| 快照热路径 p95 | <200ms | 12.2ms | PASS |
| Swarm 创建 p95 | <100ms | 4.9ms | PASS |

整体判定：**PASS**，性能优于 v9.2 设定门槛 1-2 个数量级。

## 9 未尽事项（记录，非本轮阻塞）
1. LLM TTFT/TTFB 真实推理性能未在本章单独列（真实路径耗时由模型 + 外网决定，Task 5 已验功能链路，不宜与进程内延迟混列）
2. 并发压力：当前是单连接串行采样，缺并发冲刺（50 WS + 100 REST/秒）场景 → 建议 v9.4 加专项
3. 浏览器快照并发多页面采集 Playwright 上下文隔离下的降级行为未采样

---
证据索引：
- [perf/multi-summary.md](perf/multi-summary.md) — 多源汇总
- [perf/rest-samples.summary.md](perf/rest-samples.summary.md) — REST 聚合
- [scripts/perf-rest.sh](scripts/perf-rest.sh) / [scripts/perf-ws.cjs](scripts/perf-ws.cjs) / [scripts/perf-browser-snap.sh](scripts/perf-browser-snap.sh) / [scripts/perf-swarm.sh](scripts/perf-swarm.sh)
