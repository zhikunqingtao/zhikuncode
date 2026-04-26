# Task 1: 环境准备与三端服务启动验证

## 测试时间

2026-04-26T05:12:00Z (UTC) / 2026-04-26 13:12 (CST)

## 后端健康检查

### /api/health — 完整健康检查

```json
{
  "status": "UP",
  "service": "ai-code-assistant-backend",
  "version": "1.0.0",
  "uptime": 38,
  "java": "21.0.10",
  "subsystems": {
    "database": {
      "status": "UP",
      "message": "SQLite embedded database available"
    },
    "jvm": {
      "status": "UP",
      "message": "Heap: 62MB/4096MB"
    }
  },
  "timestamp": "2026-04-26T05:12:12.004280Z"
}
```

**结果**: PASS

### /api/health/live — 存活探针

```
OK
```

**结果**: PASS

### /api/health/ready — 就绪探针

```
READY
```

**结果**: PASS

### /api/doctor — 环境诊断

```json
{
  "checks": [
    {
      "name": "java",
      "status": "ok",
      "version": "21.0.10",
      "message": "Java runtime available"
    },
    {
      "name": "git",
      "status": "ok",
      "version": "git version 2.50.1 (Apple Git-155)",
      "message": "git available",
      "latencyMs": 19
    },
    {
      "name": "ripgrep",
      "status": "warning",
      "message": "ripgrep not found: Cannot run program \"rg\": Exec failed, error: 2 (No such file or directory)",
      "latencyMs": 6
    },
    {
      "name": "jvm_memory",
      "status": "ok",
      "message": "Used 63MB / Max 4096MB"
    }
  ]
}
```

**结果**: PASS (ripgrep 缺失为 warning 级别，不影响核心功能)

## Python 健康检查

### /api/health — 健康检查

```json
{
  "status": "ok",
  "service": "ai-code-assistant-python",
  "version": "1.15.0"
}
```

**结果**: PASS

### /api/health/capabilities — 能力探测

```json
{
  "CODE_INTEL": {
    "name": "代码智能",
    "available": true,
    "reason": null
  },
  "GIT_ENHANCED": {
    "name": "Git 增强",
    "available": true,
    "reason": null
  },
  "FILE_PROCESSING": {
    "name": "文件处理",
    "available": true,
    "reason": null
  },
  "BROWSER_AUTOMATION": {
    "name": "浏览器自动化",
    "available": true,
    "reason": null
  }
}
```

**结果**: PASS (4/4 能力全部可用)

## 前端可达性

```html
<!DOCTYPE html>
<html lang="zh-CN">
  <head>
    <script type="module">import { injectIntoGlobalHook } from "/@react-refresh";
    injectIntoGlobalHook(window);
    window.$RefreshReg$ = () => {};
    window.$RefreshSig$ = () => (type) => type;</script>
    <script type="module" src="/@vite/client"></script>
    <meta charset="UTF-8" />
    <link rel="icon" type="image/svg+xml" href="/vite.svg" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>AI Code Assistant</title>
  </head>
  <body>
    <div id="root"></div>
    <script type="module" src="/src/main.tsx"></script>
  </body>
</html>
```

**结果**: PASS (Vite 开发服务器正常响应，React 热重载已注入)

## 环境基线

### 服务 PID

| 服务 | PID |
|------|-----|
| Backend (Java Spring Boot) | 96022 |
| Python (FastAPI) | 96023 |
| Frontend (Vite) | 96024 |

### 运行时版本

| 组件 | 版本 |
|------|------|
| Java | OpenJDK 21.0.10 (Corretto-21.0.10.7.1) |
| Node.js | v22.14.0 |
| Python | 3.11.15 |

### 服务端口

| 服务 | 端口 |
|------|------|
| Backend | 8080 |
| Python | 8000 |
| Frontend | 5173 |

### 环境配置 (.env)

| 配置项 | 值 |
|--------|-----|
| LLM_PROVIDER_DASHSCOPE_API_KEY | sk-936...4ed5 |
| LLM_PROVIDER_DEEPSEEK_API_KEY | sk-409...9969 |
| LLM_DEFAULT_MODEL | qwen3.6-max-preview |
| ALLOW_PRIVATE_NETWORK | true |
| ZHIKUN_COORDINATOR_MODE | (未启用，已注释) |

### 操作系统

- macOS Darwin 26.4.1
- Shell: /bin/zsh

## 测试结论

**PASS** — 三端服务全部健康运行，环境基线已建立。

- 后端: 4/4 端点全部正常 (health, live, ready, doctor)
- Python: 健康检查通过，4/4 能力模块全部可用
- 前端: Vite 开发服务器正常响应
- 注意事项: ripgrep (rg) 未安装，doctor 报 warning 级别，不影响核心功能运行
