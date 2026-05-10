# Task 3 — Python pytest + fixture 发现验证 + 覆盖率（证据）

> 执行时间：2026-05-09 23:05 → 23:06 (≈8 s) + fixture 发现验证 23:07
> 命令：`pytest --cov=src --cov-report=html --cov-report=xml --cov-report=term-missing`

---

## 3.1 总体指标

| 维度 | 数值 |
| ---- | ---- |
| 收集用例 | **47** |
| PASSED | **47** |
| FAILED | **0** |
| ERROR | **0** |
| 用时 | **8.07 s** |
| Coverage（line + branch） | **25.66%**（3362 stmts，948 branches，2366 missed，56 br-part） |
| Coverage fail-under 阈值（`pyproject.toml`） | 70% → **未达标（R-PY-01 风险）** |

---

## 3.2 按模块分布（47 PASS）

| 测试模块 | 用例数 |
| -------- | ------ |
| `test_analyzer_bfs.py` | 1 |
| `test_analyzer_callgraph.py` | 1 |
| `test_analyzer_empty.py` | 1 |
| `test_browser_automation.py` | 16 |
| `test_capabilities.py` | 12 |
| `test_file_processing.py` | 6 |
| `test_main.py` | 6 |
| `test_token_estimation.py` | 4 |
| **合计** | **47** |

全部绿灯，覆盖：
- 代码分析器 BFS / Call Graph / 空仓边界（Python 端 diagram 能力）
- Browser Automation（自动化浏览 + 16 path 场景）
- 能力清单（12 capability registry 变体）
- 文件处理（text 解析 / 截断 / 二进制识别）
- API 主入口（/ping, /health, /capabilities, /browser/capture, 等）
- Token 估算

---

## 3.3 Fixture 发现性验证（v1.2.4 新增 `pytest_plugins` 机制）

执行：`pytest --fixtures` 输出节选：

```
--------- fixtures defined from tests.fixtures.coordinator_multi_agent ---------
coordinator_multi_agent_scenario -- tests/fixtures/coordinator_multi_agent.py:43
    返回 4 阶段 + 2 并行 agent_spawn + 1 mailbox_write 的完整事件序列。

multi_agent_mailbox_messages -- tests/fixtures/coordinator_multi_agent.py:99
    仅收件箱类事件子集，用于 mailbox 隔离与截断断言。
```

✅ **验证 PASS**：子目录 `tests/fixtures/coordinator_multi_agent.py` 的两个 `@pytest.fixture` 已通过 [conftest.py](file:///Users/guoqingtao/Desktop/dev/code/zhikuncode/python-service/tests/conftest.py#L1-L12) 顶部的 `pytest_plugins = ["tests.fixtures.coordinator_multi_agent"]` 成功挂载到全局，可被任意 `test_*.py` 直接参数注入。

v1.2.4 前（本次迭代前）：fixture 仅存在于 `tests/fixtures/`，pytest 默认不会发现子目录的 fixture 模块，导致后续 Task 6 多 Agent 协作 E2E 测试无法参数注入。本次修复根治了该问题。

---

## 3.4 覆盖率分层明细（高-中-低三档）

### 高覆盖（≥80%）
| 模块 | 行 | Miss | Cover |
| ---- | -- | ---- | ----- |
| `src/analyzers/__init__.py` | 7 | 0 | **100%** |
| `src/analyzers/diagram_models.py` | 15 | 0 | **100%** |
| `src/services/__init__.py` | 0 | 0 | **100%** |
| `src/routers/__init__.py` | 0 | 0 | **100%** |
| `src/services/browser_models.py` | 51 | 0 | **100%** |
| `src/capabilities.py` | 64 | 4 | **93%** |
| `src/routers/token_estimator.py` | 40 | 7 | **81%** |

### 中覆盖（30–80%）
| 模块 | 行 | Miss | Cover |
| ---- | -- | ---- | ----- |
| `src/services/file_detector.py` | 70 | 16 | **74%** |
| `src/routers/browser.py` | 152 | 56 | **53%** |
| `src/analyzers/change_impact_analyzer.py` | 170 | 68 | **51%** |
| `src/routers/file_processing.py` | 154 | 66 | **51%** |
| `src/analyzers/call_graph_builder.py` | 354 | 161 | **48%** |
| `src/main.py` | 44 | 22 | **42%** |

### 低覆盖（<30%）— 风险点
| 模块 | 行 | Miss | Cover | 风险等级 |
| ---- | -- | ---- | ----- | -------- |
| `src/services/tree_sitter_service.py` | 139 | 91 | 29% | P3 |
| `src/services/browser_service.py` | 376 | 283 | 22% | P2 |
| `src/analyzers/code_path_tracer.py` | 248 | 173 | 22% | P3 |
| `src/analyzers/sequence_diagram_generator.py` | 236 | 209 | 8% | P3 |
| `src/analyzers/flow_chart_generator.py` | 421 | 389 | **6%** | P2 |
| `src/routers/analysis.py` | 215 | 215 | **0%** | **P1** |
| `src/routers/code_intel.py` | 140 | 140 | **0%** | **P1** |
| `src/routers/code_quality.py` | 55 | 55 | **0%** | P2 |
| `src/routers/git_enhanced.py` | 85 | 85 | **0%** | P2 |
| `src/services/complexity_analyzer.py` | 279 | 279 | **0%** | P2 |
| `src/services/git_enhanced_service.py` | 47 | 47 | **0%** | P2 |

**0% 覆盖合计行数：821 行 / 3362 行 = 24.4%** — 意味着 Python 侧 24.4% 代码路径尚未被任何测试覆盖。

---

## 3.5 风险记录

| # | 风险 | 严重度 | 决策 |
| - | ---- | ------ | ---- |
| R-PY-01 | Python 覆盖率 25.66%，远低于 `pyproject.toml` 配置阈值 70%。`analysis.py` / `code_intel.py` 两个核心 router 的 431 行业务代码 0% 覆盖 | **P1** | **重大变更，本轮跳过**。补完需约 40–60 个新测试类（覆盖 3362 stmts 中的 2366 misses），属全新测试矩阵搭建，不在本次全链路测试范围。v9.3 报告风险专章明确列出，建议下一迭代专项补齐（目标 ≥60%）。本轮在 Task 6 多 Agent 协作 E2E 中补充 coordinator_multi_agent fixture 的实战验证 |
| R-PY-02 | 3 个 test_analyzer_*.py 文件使用了未注册的 `@pytest.mark.timeout`，产生 `PytestUnknownMarkWarning` | P3 | **接受**。警告不影响用例执行；修复需安装 `pytest-timeout`（可选） |
| R-PY-03 | `asyncio_default_fixture_loop_scope` 未在 pyproject 显式设置，触发 DeprecationWarning | P3 | **接受**。未来 pytest-asyncio 默认值变更前处理 |

R-PY-01 为生产风险，R-PY-02/03 为测试工具链噪声。

---

## 3.6 证据清单

- `docs/test-results/v9.3/coverage/python/index.html` — Python HTML 覆盖率报告（可交互）
- `/tmp/zk-v9.3/python-coverage.xml` — Cobertura 格式 XML（CI 友好）
- `/tmp/zk-v9.3/python-test.log` — 完整 pytest 执行日志
- [python-service/tests/conftest.py](file:///Users/guoqingtao/Desktop/dev/code/zhikuncode/python-service/tests/conftest.py) — v1.2.4 `pytest_plugins` 注册点
- [python-service/tests/fixtures/coordinator_multi_agent.py](file:///Users/guoqingtao/Desktop/dev/code/zhikuncode/python-service/tests/fixtures/coordinator_multi_agent.py) — 2 个 fixture 源码

---

## 3.7 判定

✅ **Task 3 部分 PASS** — 47/47 功能用例 100% 通过，0 failure；fixture 发现性验证通过（v1.2.4 修复确认生效）。
⚠️ **Coverage Gap** — 25.66% 远低于 70% 阈值，记入 R-PY-01 重大风险，本轮跳过不修复（需专项迭代）。
