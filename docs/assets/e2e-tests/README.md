# ZhikunCode E2E 测试套件

> 版本: v9.0 | 更新: 2026-05-06 | 覆盖: 22 模块 / 326 用例

## 概述

本目录包含 ZhikunCode 项目完整的端到端测试套件，覆盖 REST API、WebSocket、Agent 核心循环、工具系统、LLM 集成、前端 UI、CLI 工具、可视化功能等全栈功能。

## 目录结构

```
docs/assets/
├── e2e-tests/               # 测试脚本和配置
│   ├── run-all-tests.sh     # 一键执行全部测试
│   ├── test-config.json     # 测试配置（URL/超时/模块定义）
│   ├── module02-*.mjs       # REST API 测试 (33端点)
│   ├── module03-*.mjs       # WebSocket STOMP 测试 (8场景)
│   ├── module04-06-*.mjs    # Agent/工具/权限 (25用例)
│   ├── module07-10-*.mjs    # LLM/记忆/技能/MCP (32用例)
│   ├── module11-*.mjs       # 多Agent协作 (6用例)
│   ├── module12-*.mjs       # Python服务 (15用例)
│   ├── module14-*.mjs       # 文件历史API (11用例)
│   ├── module15-*.mjs       # CLI aica (11用例)
│   ├── module16-*.mjs       # 可视化功能 (19用例)
│   ├── module17-21-*.mjs    # 高级可视化 (68用例)
│   ├── module22-*.sh        # 单元测试执行
│   └── README.md            # 本文档
├── e2e-evidence/            # 测试证据
│   ├── logs/                # 各模块执行日志
│   └── screenshots/         # 前端E2E截图
```

## 执行前提

### 环境要求

| 组件 | 最低版本 |
|------|----------|
| Node.js | v22.0.0+ |
| Java (Corretto) | 21 |
| Python | 3.11+ |
| npm packages | ws, @stomp/stompjs |

### 服务要求

执行测试前，必须确保三端服务已启动：

```bash
# 从项目根目录启动
cd /path/to/zhikuncode
./start.sh
```

验证服务状态：
- Backend: `curl http://localhost:8080/api/health`
- Python: `curl http://localhost:8000/health`
- Frontend: `curl http://localhost:5173`

## 执行方法

### 方式一：一键执行全部测试

```bash
cd docs/assets/e2e-tests
chmod +x run-all-tests.sh
./run-all-tests.sh
```

脚本会自动：
1. 检查三端服务是否运行
2. 按序执行所有模块（串行）
3. 收集日志到 `e2e-evidence/logs/`
4. 生成 JSON 结果到 `test-results.json`
5. 返回退出码（0=全通过，1=有失败）

### 方式二：单模块执行

```bash
# 执行单个模块
node module02-rest-api-test.mjs
node module03-websocket-test.mjs
node module04-06-agent-tool-permission-test.mjs

# 单元测试
bash module22-unit-tests.sh
```

### 方式三：Playwright 前端 E2E

```bash
cd frontend
npx playwright test
npx playwright test e2e/visualization-features.spec.ts  # 单个文件
```

## 结果解读

### 退出码

| 退出码 | 含义 |
|--------|------|
| 0 | 全部模块通过 |
| 1 | 有模块失败 |

### 测试状态

| 状态 | 含义 |
|------|------|
| PASS | 用例完全通过 |
| PARTIAL | 核心功能通过，但有非阻塞性降级 |
| OBSERVE | 功能正常但未触发特定子场景 |
| FAIL | 用例失败 |

### JSON 结果格式

执行完成后在 `test-results.json` 中生成：

```json
{
  "timestamp": "2026-05-06 10:30:00",
  "version": "v9.0",
  "summary": { "total_modules": 11, "passed": 11, "failed": 0 },
  "modules": [
    { "module": 2, "name": "REST API", "status": "PASS", "duration_ms": 5000 }
  ]
}
```

## CI/CD 集成

### GitHub Actions 示例

```yaml
jobs:
  e2e-test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with: { node-version: '22' }
      - uses: actions/setup-java@v4
        with: { distribution: 'corretto', java-version: '21' }
      - uses: actions/setup-python@v5
        with: { python-version: '3.11' }
      - name: Start services
        run: ./start.sh
      - name: Run E2E tests
        run: |
          cd docs/assets/e2e-tests
          chmod +x run-all-tests.sh
          ./run-all-tests.sh
      - name: Upload evidence
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: e2e-evidence
          path: docs/assets/e2e-evidence/
```

### Docker 环境执行

```bash
docker compose up -d
docker exec zhikuncode bash -c "cd docs/assets/e2e-tests && ./run-all-tests.sh"
```

## 依赖安装

测试脚本使用 Node.js 原生 fetch（Node 22+）和 ws 包：

```bash
cd docs/assets/e2e-tests
npm install  # 安装 ws, @stomp/stompjs 等
```

## 注意事项

1. **LLM 相关测试** (Module 7-10) 需要有效的 API Key 配置在 `.env` 中
2. **Playwright 测试** 需要先安装浏览器: `npx playwright install chromium`
3. **串行执行**：所有模块按序执行以避免端口/资源冲突
4. **超时配置**：LLM 调用超时为 180s，可在 `test-config.json` 中调整
