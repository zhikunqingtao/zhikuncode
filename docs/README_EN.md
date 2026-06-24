[🇨🇳 中文版](../README.md)

<div align="center">
  <img src="assets/logo.svg" alt="ZhikunCode" width="120" />
  <h1>ZhikunCode</h1>
  <p><strong>Open-Source AI Coding Assistant — Deploy Once, Control Everything from Your Browser</strong></p>
  <p>Multi-Agent Collaboration · Docker Self-Hosted · Direct Integration with Chinese LLMs · Defense-in-Depth Security</p>

  <p>
    <a href="#-quick-start">Quick Start</a> ·
    <a href="#-key-features">Key Features</a> ·
    <a href="#-demo">Demo</a> ·
    <a href="#-swe-bench-lite-evaluation">SWE-bench</a> ·
    <a href="#-cli-tools">CLI Tools</a> ·
    <a href="#-skill-system">Skill System</a> ·
    <a href="#-plugin-system">Plugin System</a> ·
    <a href="#-visualization">Visualization</a> ·
    <a href="#-memory-system">Memory</a> ·
    <a href="#-comparison">Comparison</a> ·
    <a href="../README.md">中文</a>
  </p>

  <p>
    <a href="https://opensource.org/licenses/MIT"><img src="https://img.shields.io/badge/License-MIT-yellow.svg" alt="License: MIT" /></a>
    <a href="https://github.com/zhikunqingtao/zhikuncode"><img src="https://img.shields.io/badge/Docker-Ready-blue?logo=docker" alt="Docker" /></a>
    <a href="https://github.com/zhikunqingtao/zhikuncode/stargazers"><img src="https://img.shields.io/github/stars/zhikunqingtao/zhikuncode?style=social" alt="GitHub Stars" /></a>
    <a href="https://github.com/zhikunqingtao/zhikuncode"><img src="https://img.shields.io/github/last-commit/zhikunqingtao/zhikuncode" alt="Last Commit" /></a>
    <a href="https://github.com/zhikunqingtao/zhikuncode"><img src="https://img.shields.io/github/languages/code-size/zhikunqingtao/zhikuncode" alt="Code Size" /></a>
    <a href="https://github.com/zhikunqingtao/zhikuncode/actions/workflows/ci.yml"><img src="https://github.com/zhikunqingtao/zhikuncode/actions/workflows/ci.yml/badge.svg" alt="CI" /></a>
    <a href="https://zhikunqingtao.github.io/zhikuncode/swe-bench-report.html"><img src="https://img.shields.io/badge/SWE--bench%20Lite-46.3%25%20(139%2F300)-7a2410?logo=python&logoColor=white" alt="SWE-bench Lite 46.3% (139/300)" /></a>
  </p>
</div>

---

> **deploy it to a server, open a browser, and start coding. Works on your phone too.**

> 🏗️ **[View Full System Architecture →](https://zhikunqingtao.github.io/zhikuncode/ZhikunCode-Architecture.html)**  
> Three-tier Separation · 759 Files · 115,783 Lines of Code (147,395 lines / 893 files including tests) · Full Visualization

> 🏆 **[SWE-bench Lite Technical Report →](https://zhikunqingtao.github.io/zhikuncode/swe-bench-report.html)**  
> Submission namespace `20260520_zhikuncode` · Official harness Resolve **139 / 300 (46.3%)** · Patch generation 280 / 300 (93.3%)

---

## ✨ Key Features

| | Feature | Description |
|---|---|---|
| 🌐 | **Full Browser-Based Control** | Deploy once, then manage everything from any device's browser — permission approvals, plan discussions, task management. Works on mobile. No client installation needed |
| 🤖 | **Multi-Agent Collaboration** | Three collaboration modes: Team (fixed roles) / Swarm (dynamic negotiation) / SubAgent (parent-child delegation). Complex tasks are automatically distributed |
| 🔒 | **Defense-in-Depth Security** | 8-layer Bash sandbox (error classification + output truncation + process tree mgmt) + 14-step permission pipeline + 308 security test coverage (including 19 new CWE-22 depth-defense unit tests in v9.3). Every command must pass security checks before execution |
| 🇨🇳 | **Native Chinese LLM Support** | Qwen / DeepSeek / Moonshot work out of the box with direct connections from mainland China — no VPN required |
| 🐳 | **One-Command Docker Deployment** | `docker compose up -d` — one command to start. Data stays local, fully private |
| ⚡ | **Intelligent Context Management** | Six-layer compression cascade (Snip / MicroCompact / ContextCollapse / AutoCompact / CollapseDrain / ReactiveCompact) + incremental collapse (auto-compress every 10 turns) + 413 two-phase recovery (CollapseDrain aggressive compression → ReactiveCompact) + Precise Token Counting (tiktoken multi-model support) + Self-Correction Loop (auto-diagnose compile/test failures, max 3 retries) + three-level token alerts for seamless ultra-long conversations |
| 📷 | **Multimodal Image Chat** | Upload images for AI analysis. Supported models: qwen3.7-plus / kimi-k2.6 / kimi-k2.7-code / glm-5v-turbo / MiniMax-M3 / openai/gpt-5.5-pro / google/gemini-3.5-flash (max 5MB per image, image count limit varies by model). **Intelligent Vision Routing**: when the selected model lacks image input support, the system auto-routes to a vision-capable model from the same provider (with global fallback) and reverts to the original model after image processing |
| 🖼️ | **Browser Semantic Snapshot** | `/snap` command captures full web page state (DOM structure + interactive elements), extracts structured JSON for Agent parsing and replay verification |
| 📊 | **Real-Time Activity Tracking & Approval** | Activity Panel records full AI tool execution lifecycle, L1/L2/L3 three-layer display, Signal smart tagging (auto_approve/review_recommended/needs_review), one-click batch approval, SQLite backend persistence, session restoration support |
| 🧪 | **Runtime Verification Framework** | VerifierFactory tri-modal dispatch (browser/http_api/auto) + 8 HTTP action handlers + JSONPath assertions + evidence chain SQLite storage + Feature Flag dual-gating + frontend real-time progress panel |
| 📦 | **Evidence Bundle Visualization (RV-4)** | Tabbed viewer for 7 evidence types (screenshots / commands / console / tests / network HAR / videos / diffs); on verification failure, a STOMP `verify_attention` notification triggers a mobile bottom-sheet for one-tap approve/reject. Backed by `/api/evidence/*` REST endpoints (bundle by id, list by session, binary blob by SHA-256) |
| 🏆 | **SWE-bench Lite Submission** | Single backbone `qwen-3.6-max-preview` + closed six-tool set (Read/Edit/Write/Bash/Grep/Glob); no internet, no sub-agent. Official harness reports **Resolve 46.3% (139/300)** and Patch generation **93.3% (280/300)**. [Technical Report →](https://zhikunqingtao.github.io/zhikuncode/swe-bench-report.html) |

---

## 🎬 Demo

### 📱 Mobile Full-Stack TODO App Development (End-to-End)

https://github.com/user-attachments/assets/bf1f1d3a-4a9b-4d91-af48-97a7d3dd7b8a

### Auto-Code to Download Xiaohongshu Videos

https://github.com/user-attachments/assets/4b66261b-3258-44bd-82d3-6b2b3bbd4995

![Auto-Code to Download Xiaohongshu Videos](assets/demo-auto-code-xiaohongshu.gif)

### 📱 Project Analysis and Command Execution Demo

https://github.com/user-attachments/assets/7b45c5d4-e540-4ffd-80d4-e11502477dba

### File Operations
![File Operations Demo](assets/demo-file-operation.gif)

### Game Generation
![Game Generation Demo](assets/demo-game-generation.gif)

### Code Optimization
![Code Optimization Demo](assets/demo-code-optimization.gif)

### Multi-Agent Collaborative Full-Stack Development
![Multi-Agent Collaboration Demo](assets/demo-multi-agent-todo.gif)

### Full Browser-Based Control on iPad
![iPad Browser Control Demo](assets/demo-ipad-browser.gif)

---

## ⚡ Quick Start

### Prerequisites: Get an LLM API Key

This project requires an LLM (Large Language Model) API Key to run. It defaults to **Alibaba Cloud Qwen (DashScope)**, which works directly in China without VPN.

**Get a Qwen API Key:**
1. Visit [Alibaba Cloud Bailian API Key Management](https://bailian.console.aliyun.com/cn-beijing/?tab=model#/api-key)
2. Sign up or log in to your Alibaba Cloud account
3. Create an API Key and copy the full key (starts with `sk-`)

> Qwen offers a free quota sufficient for personal development. You can also use [DeepSeek](https://platform.deepseek.com/), [Moonshot/Kimi](https://platform.moonshot.cn/), or other providers. See "Supported LLM Providers" below.

### Option 1: Docker Deployment (Recommended)

Three steps from zero to running:

```bash
# 1. Clone the repository
git clone https://github.com/zhikunqingtao/zhikuncode.git
cd zhikuncode

# 2. Configure your API Key
cp .env.example .env
# Edit .env and add your LLM API Key (defaults to Qwen/DashScope, direct connection in China)

# 3. Start
docker compose up -d
```

> **First build note:** The first run will automatically build the Docker image, downloading dependencies and compiling the project. This takes approximately **15-30 minutes** depending on network speed. Subsequent starts take only a few seconds. Use `docker compose logs -f` to monitor build progress.

Once started, open **http://localhost:8080** in your browser.

> **System Requirements:** Docker 20.10+, Docker Compose V2, 4GB+ RAM recommended.

### Option 2: Local Development

**Prerequisites:** JDK 21, Node.js 22+, Python 3.11~3.12 (does not support 3.13+)

```bash
git clone https://github.com/zhikunqingtao/zhikuncode.git
cd zhikuncode

# Configure environment variables
cp .env.example .env
# Edit .env and add your LLM API Key

# Start all three services at once
./start.sh
```

All three services start simultaneously:

| Service | Address | Description |
|---------|---------|-------------|
| **Backend** | `http://localhost:8080` | Java Spring Boot backend, core API |
| **Python Service** | `http://localhost:8000` | FastAPI service, code analysis |
| **Frontend** | `http://localhost:5173` | React dev server |

<details>
<summary><b>Start each service manually</b></summary>

```bash
# Backend
cd backend && ./mvnw spring-boot:run -DskipTests

# Python Service
cd python-service
python -m venv venv && source venv/bin/activate
pip install -r requirements.txt
uvicorn src.main:app --host 0.0.0.0 --port 8000

# Frontend
cd frontend && npm install && npm run dev
```

</details>

> **RV-1 Runtime Verification Dependencies**: `jsonpath-ng` (JSONPath assertion engine), `httpx` (async HTTP client), already included in `python-service/requirements.txt`.

### Supported LLM Providers

ZhikunCode supports **multi-Provider simultaneous configuration** (recommended) and single-Provider mode. In multi-Provider mode, you can freely switch models from the frontend:

**Option A: Multi-Provider Configuration (Recommended)**

Configure independent API Keys for each provider in `.env`, and switch freely from the frontend:

```bash
# DashScope (Qwen series)
LLM_PROVIDER_DASHSCOPE_API_KEY=your-dashscope-key

# DeepSeek
LLM_PROVIDER_DEEPSEEK_API_KEY=your-deepseek-key

# Moonshot (Kimi)
LLM_PROVIDER_MOONSHOT_API_KEY=your-moonshot-key

# Zhipu (GLM)
LLM_PROVIDER_ZHIPU_API_KEY=your-zhipu-api-key-here

# MiniMax
LLM_PROVIDER_MINIMAX_API_KEY=your-minimax-api-key-here

# ZenMux (claude-opus-4.8 / claude-fable-5 / openai/gpt-5.5-pro / google/gemini-3.5-flash, 1M context)
LLM_PROVIDER_ZENMUX_API_KEY=your-zenmux-api-key-here
```

**Option B: Single-Provider Configuration (Backward Compatible)**

If no multi-Provider keys are configured, the system automatically falls back to single-Provider mode. Configure `LLM_BASE_URL` and `LLM_API_KEY` in `.env`:

| Provider | Base URL | Recommended Model | Notes |
|----------|----------|-------------------|-------|
| **Qwen / DashScope** | `https://dashscope.aliyuncs.com/compatible-mode/v1` | qwen3.7-max | **Default**, direct connection in China |
| **DeepSeek** | `https://api.deepseek.com/v1` | deepseek-v4-pro | Direct connection in China |
| **Moonshot (Kimi)** | `https://api.moonshot.cn/v1` | kimi-k2.6 / kimi-k2.7-code | Direct connection; image support |
| **Zhipu (GLM)** | `https://open.bigmodel.cn/api/paas/v4/chat/completions` | glm-5.2, glm-5v-turbo | China direct access |
| **MiniMax** | `https://api.minimax.chat/v1` | MiniMax-M3 | 1M context window |
| **ZenMux (Multi-Model Gateway)** | `https://zenmux.ai/api/v1` | anthropic/claude-opus-4.8 / claude-fable-5 / openai/gpt-5.5-pro / google/gemini-3.5-flash | 1M context · Image support |
| **OpenAI** | `https://api.openai.com/v1` | gpt-4o | Requires international network access |
| **Local Ollama** | `http://localhost:11434/v1` | qwen2.5:latest | Fully offline |

> Any provider compatible with the OpenAI API format can be integrated — just configure the corresponding Base URL and API Key.

### Optional: Enable DashScope-hosted MCP Services

Starting from the latest version, the following MCP services hosted on `dashscope.aliyuncs.com` are **disabled by default**, to avoid startup log flooding for users who have not configured an Alibaba Cloud Bailian API Key:

| MCP Service | Capability | Tool IDs |
|-------------|------------|----------|
| `Wan25Media` | Wanx 2.5 image generation / image-to-image editing | `mcp_wan25_image_gen`, `mcp_wan25_image_edit` |
| `zhipu-websearch` | Zhipu Web Search Pro | `mcp_web_search_pro` |

> ℹ️ Disabling these MCPs does **not** affect core chat, code editing, or local tools.

**To enable them** (requires an Alibaba Cloud Bailian API Key with the corresponding MCP capabilities activated in the console):

1. Configure your DashScope key in `.env`:
   ```bash
   LLM_PROVIDER_DASHSCOPE_API_KEY=sk-xxxxxxxx
   ```
2. Uncomment the `zhipu-websearch` block in [`backend/src/main/resources/application.yml`](../backend/src/main/resources/application.yml).
3. Flip `enabled` to `true` for the entries you need in [`configuration/mcp/mcp_capability_registry.json`](../configuration/mcp/mcp_capability_registry.json).
4. Run `./stop.sh && ./start.sh` to fully restart all three tiers so the changes take effect.

---

## 🏆 SWE-bench Lite Evaluation

ZhikunCode has completed an end-to-end SWE-bench Lite evaluation (300 instances, pass@1) under the official harness, achieving an **official Resolve Rate of 46.3% (139/300)**. All artifacts (`all_preds.jsonl`, `results.json`, `metadata.yaml`, trajectories) are open-source under [`docs/swe-bench/20260520/`](swe-bench/20260520/) for third-party reproduction.

### Key Metrics

| Metric | Value | Source |
|---|---|---|
| Resolved Instances | **139 / 300 (46.3%)** | `docs/swe-bench/20260520/results/results.json` `resolved=139` |
| Patch Generation Rate | **280 / 300 (93.3%)** | `all_preds.jsonl` (20 empty patches) |
| Backbone Model | `qwen-3.6-max-preview` | `docs/swe-bench/20260520/metadata.yaml` |
| Closed Tool Set | Read / Edit / Write / Bash / Grep / Glob | [`swe-bench/swe_bench.py`](../swe-bench/swe_bench.py) `ALLOWED_TOOLS` |
| Per-instance Budget | 60 turns / 900 seconds | [`swe_bench.py`](../swe-bench/swe_bench.py) `solve_instance(max_turns=60, timeout=900)` |
| Parallel Workers | 1 | `--workers` default |
| Network / Sub-agent | Both disabled | Explicit in system prompt |
| Submission Namespace | `20260520_zhikuncode` | `metadata.yaml` |

### Per-Repository Breakdown (from `results/resolved_by_repo.json`)

| Repository | Resolved / Total | Resolve Rate |
|---|---|---|
| mwaskom/seaborn | 3 / 4 | **75.0%** |
| django/django | 69 / 114 | **60.5%** |
| psf/requests | 3 / 6 | 50.0% |
| sympy/sympy | 38 / 77 | 49.4% |
| pytest-dev/pytest | 7 / 17 | 41.2% |
| pydata/xarray | 2 / 5 | 40.0% |
| astropy/astropy | 2 / 6 | 33.3% |
| pallets/flask | 1 / 3 | 33.3% |
| scikit-learn/scikit-learn | 6 / 23 | 26.1% |
| sphinx-doc/sphinx | 3 / 16 | 18.8% |
| matplotlib/matplotlib | 4 / 23 | 17.4% |
| pylint-dev/pylint | 1 / 6 | 16.7% |
| **Total** | **139 / 300** | **46.3%** |

### Engineering Highlights (every claim is source-traceable)

- **Agent-Loop with explicit four phases** ANALYZE→LOCATE→FIX→VERIFY, hard-enforced by the system prompt ([swe_bench.py](../swe-bench/swe_bench.py))
- **Six-layer context compression cascade** Snip / MicroCompact / ContextCollapse / AutoCompact / CollapseDrain / ReactiveCompact ([ContextCascade.java](../backend/src/main/java/com/aicodeassistant/engine/ContextCascade.java); Level 1.5 ContextCollapse is the progressive-collapse intermediate layer per source comments)
- **Two-phase 413 recovery** CollapseDrain → ReactiveCompact, keeping 60-turn sessions inside the context window
- **Self-correction loop** turning compile/test failures into structured re-prompting, hard-capped at 3 attempts ([SelfCorrectionLoop.java](../backend/src/main/java/com/aicodeassistant/engine/correction/SelfCorrectionLoop.java) `MAX_ATTEMPTS = 3`)

📄 Full methodology and reproduction command in the technical report: <https://zhikunqingtao.github.io/zhikuncode/swe-bench-report.html>

---

## 📊 Comparison

### Feature Comparison

| Feature | ZhikunCode | Aider | Cline | Cursor | Claude Code | Copilot |
|---------|:---:|:---:|:---:|:---:|:---:|:---:|
| Open Source & Free | ✅ | ✅ | ✅ | ❌ | ❌ | ❌ |
| Web UI | ✅ Full-featured | ⚠️ Experimental browser UI | ❌ | ⚠️ Web ver. | ✅ | ⚠️ GitHub.com |
| Docker Self-hosted | ✅ Full web service | ⚠️ CLI container | ❌ | ⚠️ Enterprise | ❌ | ❌ |
| Chinese LLM Support | ✅ Native | ⚠️ Compatible API | ⚠️ Compatible API | ❌ | ❌ | ❌ |
| Multi-Agent | ✅ Team/Swarm/Sub | ❌ | ✅ Kanban + CLI parallel | ✅ Multi-Agents | ✅ Sub-Agents | ✅ /fleet + Agent Mode |
| Full Browser Control¹ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ |
| Security Sandbox | ✅ 8-layer | ❌ | ❌ | ⚠️ Enterprise | ✅ OS-level | ⚠️ GitHub permission policies |
| MCP Tool Extension | ✅ | ⚠️ 3rd-party | ✅ | ✅ | ✅ | ✅ |
| CLI Terminal Tools | ✅ aica + 35+ slash cmds | ✅ CLI-first | ✅ CLI 2.0 | ✅ Cursor CLI | ✅ CLI-only | ✅ Copilot CLI |
| Extensible Skill System | ✅ Markdown-driven + 6-level sources | ❌ | ❌ | ✅ Rules | ✅ Hooks | ❌ |
| Plugin System | ✅ Java SPI plugins + sandbox isolation + hot reload | ❌ | ❌ | ✅ Plugins | ✅ Skills/Hooks | ✅ Plugins |
| Cross-Session Memory | ✅ 3-layer memory + BM25 search | ❌ | ❌ | ✅ Rules | ✅ Memory | ❌ |
| Activity Tracking & Approval | ✅ L1/L2/L3 Three-layer | ❌ | ❌ | ❌ | ✅ Permission Mgmt | ❌ |
| Activity Persistence | ✅ SQLite + STOMP | ❌ | ❌ | ❌ | ⚠️ Memory-level | ❌ |
| No Client Install | ✅ | ❌ | ❌ | ⚠️ | ✅ | ❌ |

> ¹ **Full Browser Control**: After deployment, any device's browser (including mobile) can fully control the entire coding workflow — permission approval, plan negotiation, task management. This is different from Cline/Cursor's "AI controlling a browser for automated testing".

### Security Comparison

| Security Feature | ZhikunCode | Aider | Cline | Claude Code |
|-----------------|:---:|:---:|:---:|:---:|
| Command Sandbox | 8-layer checks | ❌ User approval | ❌ User approval | ✅ gVisor/Firecracker |
| Permission Pipeline | 14-step pipeline | ❌ | Simple confirm | Permission system |
| Security Tests | 308 items | Not disclosed | Not disclosed | Not disclosed |
| Sensitive Path Block | ✅ | ❌ | ❌ | ❌ |
| Dangerous Cmd Block | ✅ | ❌ | ❌ | ✅ Partial |
| Env Var Whitelist | ✅ | ❌ | ❌ | ❌ |

> **Note:** Comparison based on official documentation (as of April 2026). AI coding tools iterate rapidly — please [open an issue](https://github.com/zhikunqingtao/zhikuncode/issues) if any inaccuracy is found. Cline CLI 2.0, Cursor 2.0+, Claude Code Desktop, and GitHub Copilot /fleet are all evolving rapidly.
>
> **Latest Updates (April 2026):** Claude Code Desktop App released (supports local + cloud hybrid execution); Cursor 3.1 introduced Canvas feature (interactive dashboard + custom UI components); latest versions: Aider v0.86+, Cline v1.0.35+, Cursor 3.1+, Claude Code 2.1.119+, GitHub Copilot CLI 1.0.35+.

---

## 🏗️ Architecture Overview

ZhikunCode uses a three-tier architecture: the Java backend handles core orchestration, the React frontend provides the UI, and the Python service handles code analysis.

```
┌──────────────────┐      WebSocket / HTTP      ┌──────────────────────┐
│    Frontend       │ ◄────────────────────────► │      Backend          │
│  React 18 + TS    │                            │  Java 21 + Spring    │
│  Vite + Tailwind  │                            │  Boot 3.4            │
│  :5173 (dev)      │                            │  :8080               │
└──────────────────┘                            └──────────┬───────────┘
                                                           │ HTTP
                                                           ▼
                                                ┌──────────────────────┐
                                                │   Python Service      │
                                                │   FastAPI + Uvicorn   │
                                                │   :8000               │
                                                └──────────────────────┘
```

### Layer Responsibilities

| Layer | Tech Stack | Responsibilities |
|-------|-----------|-----------------|
| **Backend** | Java 21, Spring Boot 3.4.x, WebSocket, SQLite | Core orchestration engine, LLM API routing, Agent management, tool execution (27 built-in tools + MCP dynamic extensions), permission pipeline, session persistence |
| **Frontend** | React 18, TypeScript 5.6, Vite 5, TailwindCSS, Monaco Editor, xterm.js, Zustand | Conversational UI, code editor, built-in terminal, file browser, settings panel, real-time streaming output, Agent collaboration visualization |
| **Python Service** | FastAPI, Uvicorn, Python 3.11+ | Code analysis, AST parsing, MCP tool bridging |

### Docker Deployment Architecture

In production, all three services are packaged in a single Docker container:

```
┌─────────────────────────────────────────────────┐
│                Docker Container                  │
│  ┌───────────┐  ┌───────────┐  ┌──────────────┐ │
│  │  Backend   │  │  Python   │  │   Frontend   │ │
│  │  :8080     │  │  :8000    │  │ (static files)│ │
│  └───────────┘  └───────────┘  └──────────────┘ │
│                                                  │
│  Volume: zhikun-data (SQLite + session data)     │
│  Volume: workspace (user project files)          │
├──────────────────────────────────────────────────┤
│  Port: 8080 → host                               │
└──────────────────────────────────────────────────┘
```

### Agent Loop Query Cycle

ZhikunCode's core execution engine QueryEngine drives Agent decision-making and tool execution through an 8-step loop:

```
Compression Cascade → Streaming Session Creation → API Call (with circuit breaker + adaptive retry + downgrade protection) → Response Collection → Tool Result Consumption (4-layer priority scheduling) → 6-dimension termination evaluation → Tool Summary Injection → State Update
```

**Key Subsystems:**

| Component | Responsibility | Configuration |
|-----------|---------------|---------------|
| IncrementalCollapseManager | Triggers incremental context collapse every 10 turns | `context.cascade.incremental-collapse.enabled` |
| ContextCascade | Six-layer compression cascade (Snip→MicroCompact→ContextCollapse→AutoCompact→CollapseDrain→ReactiveCompact) | `context.cascade.*` |
| MicroCompactService | Clears old tool result content to reduce context size | `features.flags.CACHED_MICROCOMPACT` |
| ModelTierService | Model downgrade chain management with 30-min cooldown auto-recovery | `app.model.tier-chain` |

**413 Two-Phase Recovery**: When the API returns 413 (Payload Too Large), automatic two-phase recovery is triggered (source: [ContextCascade.java](../backend/src/main/java/com/aicodeassistant/engine/ContextCascade.java) `recoverFromPayloadTooLarge`):
1. **Level 3** — CollapseDrain aggressive compression (contextWindow × 0.5 target)
2. **Level 4** — ReactiveCompact (keep only 1 turn + extreme compression)

---

## 🔒 Security Architecture

Security is a core design principle of ZhikunCode. Every command must pass through multiple security layers before execution.

### 8-Layer Bash Security Sandbox

All shell commands must pass through these 8 layers before execution:

| Layer | Check | Description |
|-------|-------|-------------|
| **Layer 1** | Command parsing | Parses command structure; identifies pipes, redirects, and subcommands |
| **Layer 2** | Blocklist filtering | Three-tier interception system (ABSOLUTE_DENY/HIGH_RISK_ASK/AUDIT_LOG), blocks known dangerous commands (`rm -rf /`, `mkfs`, `dd`, `format`, etc.), with ReDoS regex protection |
| **Layer 3** | Path traversal detection | Prevents `../` path traversal attacks; blocks device paths and UNC paths |
| **Layer 4** | Permission verification | 14-step permission pipeline decision; sensitive operations require user approval |
| **Layer 5** | Sandboxed execution | Destructive commands run in a Docker sandbox (read-only filesystem + memory limits + network isolation) |
| **Layer 6** | Argument sanitization | Environment variable allowlist, command injection protection |
| **Layer 7** | Output validation | Detects anomalous output, redacts sensitive information |
| **Layer 8** | Audit logging | Complete record of every command execution for traceability |

### 14-Step Permission Pipeline

The permission pipeline uses a **short-circuit** design — any matching interception rule returns immediately without further processing:

```
Request enters
  │
  ├─ 1.  Deny rule check ──────────── Match → Deny
  ├─ 2.  Ask rule check ───────────── Match → Prompt user
  ├─ 3.  Tool-level permission ─────── Tool denies → Block
  ├─ 4.  User interaction check ────── Needs interaction → Prompt
  ├─ 5.  Content-level danger ──────── rm -rf, chmod 777, eval, sudo → Force prompt
  ├─ 6.  Write path safety ─────────── Dangerous directories, symlinks → Block
  ├─ 7.  Dangerous delete detection ── rm with risky targets → Block
  ├─ 8.  Environment variable check ── Non-allowlisted vars → Block
  ├─ 9.  Hook injection check ──────── PreToolUse hooks can block
  ├─ 10. Classifier evaluation ─────── AI risk assessment (AUTO mode)
  ├─ 11. Sandbox rule evaluation ───── In-sandbox operations → Auto-allow
  ├─ 12. Emergency kill switch ─────── Admin can temporarily disable AUTO
  ├─ 13. AlwaysAllow rules ─────────── Allowlist match → Allow
  └─ 14. Mode branch decision ──────── DEFAULT/PLAN/AUTO/BYPASS final decision
```

### Protected Paths

The following paths require user confirmation even in bypass mode:

- `.git` — Git repository data
- `.env` — Environment variables and secrets
- `.ssh` — SSH keys
- `.gnupg` — GPG keys
- `.aws` — AWS credentials

### Security Testing

- **308 security tests** covering all security paths
- Includes command injection, path traversal, permission bypass, and other attack scenarios
- **New defense-in-depth in v9.3**:
  - **CWE-22 Path Traversal**: `CoordinatorService.getScratchpadDir` sessionId allowlist (11 unit tests) + `SwarmController.createSwarm` teamName allowlist (8 unit tests). Even if upstream URI interception is bypassed, the allowlist remains the final on-disk line of defense
  - **Cross-User Access Isolation (P2-A)**: `BrowserReplayController` two-layer gate — sessionId format validation returns 400 + principal ownership validation returns 403, with MVP anonymous-session compatibility

  **v9.3 Security Defense Summary:**

  | Defense Layer | Location | Protection Mechanism | Unit Tests |
  |--------------|----------|---------------------|------------|
  | P1-2 | `CoordinatorService.getScratchpadDir` | sessionId allowlist `^[A-Za-z0-9_-]{1,128}$` | 11 |
  | E1 | `SwarmController.createSwarm` | teamName allowlist `^[A-Za-z0-9_-]{1,64}$` | 8 |
  | P2-A | `BrowserReplayController` | sessionId format validation (400) + principal ownership validation (403) | — |

- The full security test suite runs on every code change

### 🧪 Quality Assurance

Full test report: [ZhikunCode v9.4 End-to-End Test Report](test-results/v9.3/ZhikunCode全链路测试报告.md) (2026-05-16)

**Continuous Integration:**
- **GitHub Actions Pipeline**: Automatically runs backend compilation, frontend build, Python tests, and Docker image verification on every push

**Test Coverage (v9.4):**
- **Total**: 1948 cases + 490 performance probes + 7 security probes = **2445** (including APOS E2E comprehensive 123 cases: 62 Phase 1 + 50 Phase 2 + 11 risk fixes)
- **Backend Unit/Integration Tests**: 1500 PASS / 0 failure / 0 error / 48 skipped (including AI Coding Enhancement 238 unit tests), coverage Inst 42.17% / Branch 30.44%
- **Python pytest**: 47 PASS, coverage 25.66%
- **Frontend vitest**: 78 PASS / 16 skipped (94 total)
- **36-Module REST/WS/LLM/Session Smoke**: 45/45 PASS (42 REST + 1 WS STOMP + 1 LLM live inference + 1 Session persistence)
- **E2E Differentiated Pipelines**: Task 6 Multi-Agent Collaboration (CoordinatorEventBus) · Task 7 Visualization Auto-Routing (`/visualize` mermaid/json/text) · Task 8 Browser Semantic Snapshot MVP (`/snap`) — all end-to-end PASS
- **APOS Phase 1 E2E**: 62 cases (9 modules, including 28 core features + 34 supporting paths) 100% PASS, covering Activity UI / Data Flow / Three-layer Display / Signal Marking / Feature Flag / Backend API / Responsive / Persistence, with 4 bug fix regressions
- **APOS Phase 2 E2E**: 5 modules, 50 cases (Change Impact Panorama / Pipeline View & DAG / Anomaly Detection & Alert / Mobile Responsive / Phase 2 Integration) 48 PASS / 2 SKIP, pass rate 96%
- **APOS Risk Fix Verification**: 11 cases 100% PASS (tool invocation / batch operations / concurrency race conditions / API fallback)
- **AI Coding Enhancement**: 6 modules (SelfCorrectionLoop / Precise Tokenizer / Skill Budget & Security / BashTool Dynamic Timeout / GitDiffTracker / SearchStrategyRouter) 33 cases + 238 unit tests + 7 integration tests + Feature Flag bi-directional verification, 100% PASS
- **Feature Completeness**: 100% of planned v1.0 features verified

**Test Framework Details:**

| Framework | Layer | Coverage | Count |
|-----------|-------|----------|-------|
| JUnit 5 + Mockito | Backend Unit/Integration | Context/Permission/Skill/Plugin/LLM/MCP/Memory/Concurrency/SSE/Persistence/Tool/Coordinator/Swarm/AI Coding Enhancement etc. | 1500 PASS |
| Vitest | Frontend Unit | Store Lifecycle/Cross-Tab Sync/Streaming Render/Immer Immutability/Route Boundary | 78 PASS |
| Playwright + Node scripts | E2E | Coordinator WS subscription / Three visualization viewTypes / Browser snapshot MVP / APOS Phase 1 full-stack / APOS Phase 2 full-stack | Task 6/7/8/APOS all green |
| Pytest | Python Service | Token Estimation/File Processing/Browser Automation/Semantic Snapshot/Code Analyzers | 47 PASS |

**Performance Baseline (v9.3, 490 real request samples):**

| Metric | p50 | p95 | p99 |
|--------|-----|-----|-----|
| REST API (14 endpoints mixed) | 1.5ms | 2.3ms | 4.3ms |
| WS STOMP Handshake | 2.22ms | 4.58ms | 6.22ms |
| Browser Semantic Snapshot (warm) | 9.23ms | 12.20ms | 12.26ms |
| Swarm Creation | 2.39ms | 4.90ms | 12.40ms |

**Detailed Test Data & Evidence:**
- Full v9.3 report: [docs/test-results/v9.3/](test-results/v9.3/)
- Per-module results: [docs/test-results/](test-results/)
- Frontend E2E scripts: [frontend/e2e/](../frontend/e2e/)
- E2E screenshots: [docs/test-results/screenshots/](test-results/screenshots/) (42 items)

<details>
<summary>📋 36 Test Modules Breakdown (click to expand)</summary>

| # | Module | Cases | Pass Rate | Notes |
|---|--------|-------|-----------|-------|
| 1 | Environment Setup & Service Startup | 7 | 100% | — |
| 2 | REST API Core Functions | 33 | 100% | Per-endpoint verification |
| 3 | WebSocket STOMP Communication | 8 | 100% | — |
| 4 | Agent Loop Core Cycle | 9 | 100% | — |
| 5 | Tool System & Security | 10 | 100% | — |
| 6 | Permission Governance | 6 | 100% | — |
| 7 | System Prompt & LLM Integration | 7 | 100% | — |
| 8 | Memory System | 7 | 86% | ★ First coverage |
| 9 | Skill System | 7 | 100% | ★ First coverage |
| 10 | Plugin System & MCP | 11 | 100% | ★ First coverage |
| 11 | Multi-Agent Collaboration | 6 | 100% | — |
| 12 | Python Service | 15 | 100% | 1 BUG fixed |
| 13 | Frontend E2E & UI | 7 | 86% | 1 PARTIAL |
| 14 | File History & API | 11 | 100% | ★ First coverage |
| 15 | CLI Tool (aica) | 11 | 91% | 2 BUGs fixed |
| 16 | Visualization E2E | 19 | 100% | ★ First coverage |
| 17 | F3 Code Complexity Analysis | 6 | 100% | ★ New in v1.0 |
| 18 | F33 Change Impact Analysis | 6 | 100% | ★ New in v1.0 |
| 19 | F25 API Contract Visualization | 6 | 100% | ★ New in v1.0 |
| 20 | F35 Code→Diagram Generation | 25 | 100% | ★ New in v1.0 |
| 21 | F40 Code Path Tracing | 25 | 100% | ★ New in v1.0 |
| 22 | Unit Test Suite (v9.3 expanded) | 84 | 100% | E2E module-level test cases (backend JUnit 1500+ counted separately) |
| 23 | APOS Basic UI | 4 | 100% | ★ First coverage |
| 24 | APOS Data Flow | 4 | 100% | ★ First coverage |
| 25 | APOS Three-layer Display | 4 | 100% | ★ First coverage |
| 26 | APOS Signal & Filter | 2 | 100% | ★ First coverage |
| 27 | APOS Feature Flag | 2 | 100% | ★ First coverage |
| 28 | APOS Backend API | 1 | 100% | ★ First coverage |
| 29 | APOS Responsive + Health | 3 | 100% | ★ First coverage |
| 30 | APOS Activity Persistence | 8 | 100% | ★ First coverage + 4 Bug fixes |
| 31 | APOS Change Impact Panorama | 6 | 100% | ★ Phase 2 new |
| 32 | APOS Pipeline View & DAG | 12 | 92% | ★ Phase 2 new |
| 33 | APOS Anomaly Detection & Alert | 10 | 100% | ★ Phase 2 new, 2 SKIP |
| 34 | APOS Mobile Responsive | 9 | 89% | ★ Phase 2 new |
| 35 | APOS Phase 2 Integration | 13 | 100% | ★ Phase 2 new |
| 36 | AI Coding Enhancements | 33 | 100% | ★ v9.4 new (6 sub-modules + 238 units + Feature Flag verification) |

</details>

---

## 🎯 Skill System

ZhikunCode's Skill System is a **Markdown-driven extensible workflow engine**. Each skill is a `.md` file — YAML frontmatter defines metadata, Markdown body defines execution instructions.

### 6 Built-in Skills

Ready to use out of the box — type `/skill-name` to invoke:

| Skill | Command | Description |
|-------|---------|-------------|
| **Smart Commit** | `/commit` | Analyzes staged changes, generates commit messages in Conventional Commits format |
| **Code Review** | `/review` | Reviews uncommitted changes, categorizes issues by P0/P1/P2 severity |
| **Smart Fix** | `/fix` | Diagnoses root cause from error messages, applies minimal fix and verifies |
| **Smart Test** | `/test` | Generates/runs tests for specified code or recent changes, covers edge cases |
| **PR Assistant** | `/pr` | Analyzes branch diff, generates structured PR description and review notes |

### 6-Level Loading Priority

Skills with the same name are overridden by priority chain — higher priority automatically shadows lower:

```
managed > user > project > plugin > bundled > mcp
```

| Source | Directory | Description |
|--------|-----------|-------------|
| **managed** | Policy-managed directory | Enterprise-distributed skills |
| **user** | `~/.zhikun/skills/` | User global custom skills |
| **project** | `.zhikun/skills/` | Project-level skills, distributed with the codebase |
| **plugin** | Plugin-provided | Skills embedded in JAR plugins |
| **bundled** | Built-in | 6 out-of-the-box skills |
| **mcp** | MCP-built | Skills registered via MCP protocol |

### Custom Skills

Create `.md` files in `~/.zhikun/skills/` or `.zhikun/skills/` in your project root:

```markdown
---
description: "Translate code to a specified language"
arguments:
  - language
---

# Translation Task

Translate the selected code to {{language}}, preserving original logic and comment style.
```

Invoke with: `/translate language=python` or `/translate python`

**Supported frontmatter fields:**

| Field | Type | Description |
|-------|------|-------------|
| `description` | string | Skill description |
| `name` | string | Display name (overrides filename) |
| `arguments` | list | Parameter definition list |
| `argument_hint` | string | Parameter hint text |
| `when_to_use` | string | Conditions for automatic model invocation |
| `allowed_tools` | list | Tool allowlist for this skill |
| `context` | string | `inline` (default, inject into current conversation) or `fork` (create independent sub-agent) |
| `model` | string | Specify model (`inherit` uses parent model) |

> Skills support hot reload — changes take effect immediately after saving, no service restart needed. Powered by Java NIO WatchService with 500ms debounce.

**Security & Budget Controls:**
- **Token Budget**: Single Skill ≤5000 tokens / Session total ≤25000 tokens, preventing resource abuse
- **Tool Whitelist**: Skills can only invoke tools declared in frontmatter `allowed_tools`
- **Injection Protection**: Shell injection triple-vector interception (`$()` / backticks / pipes), parameter length limit 2000 chars
- **Fork Depth Control**: Fork-mode Skill nesting depth ≤3 levels, preventing infinite recursion

---

## 🧩 Plugin System

ZhikunCode's Plugin System uses standard **Java SPI (ServiceLoader)** to discover and load third-party JAR plugins, providing four bridging capabilities: command registration, tool registration, hook interception, and MCP server integration. Controlled by the `plugin.enabled` feature flag.

### Four Bridging Capabilities

| Bridge Type | Description | Example |
|-------------|-------------|--------|
| **Command Registration** | Plugins can register custom slash commands, auto-prefixed with plugin name | `/myplugin:hello` |
| **Tool Registration** | Plugins can provide custom tools for AI Agents to invoke | Custom code analysis tool |
| **Hook Interception** | Plugins can execute custom logic before/after key events | Security audit before tool execution |
| **MCP Server** | Plugins can register MCP servers to extend AI capabilities | Connect to external data sources |

### Security Features

- **PluginClassLoader Sandbox Isolation** — Plugins access host APIs via package allowlists: Core API packages (`com.aicodeassistant.plugin.*`, `tool.*`, `command.*`, `mcp.*`), standard libraries (`java.*`, `javax.*`, `jdk.*`, `sun.*`, `org.slf4j.*`), and core frameworks (`org.springframework.*`, `com.fasterxml.jackson.*`, `jakarta.*`). Access to non-allowlisted host classes throws `ClassNotFoundException`
- **Hook Execution Timeout Protection** — Virtual Thread + `CompletableFuture.orTimeout(5s)`, auto-allows on timeout to prevent plugins from blocking the main flow
- **JAR File Validation** — Pre-load validation of file existence, JAR format, size limit (default 50MB), and SPI config file (`META-INF/services/`) completeness
- **API Version Compatibility Check** — Plugins declare `minApiVersion` / `maxApiVersion`, the host automatically validates compatibility

### Hot Reload

Supports runtime reloading of all plugins without service restart:

- Uses `ReentrantReadWriteLock` to ensure concurrency safety during reload
- Reload flow: unload all plugins (unregister commands/tools/hooks/MCP + close ClassLoaders) → re-scan and load
- Trigger via: `/reload-plugins` slash command or REST API

### 8 Hook Event Types

Plugins can register hooks for the following events to execute custom logic at key points:

| Event Type | Trigger |
|------------|--------|
| `PreToolExecution` | Before tool execution |
| `PostToolExecution` | After tool execution |
| `UserPromptSubmit` | When a prompt is submitted |
| `SessionStart` | Session begins |
| `SessionEnd` | Session ends |
| `TaskCompleted` | Task completion |
| `Notification` | Notification event |
| `Stop` | Stop event |

### Plugin Development Guide

Developing a ZhikunCode plugin takes just four steps:

**1. Implement the `PluginExtension` SPI interface**

```java
public class MyPlugin implements PluginExtension {
    @Override public String name() { return "my-plugin"; }
    @Override public String version() { return "1.0.0"; }

    @Override
    public List<Command> getCommands() {
        return List.of(/* custom commands */);
    }

    @Override
    public void onLoad(PluginContext ctx) {
        ctx.getLogger().info("Plugin loaded!");
    }
}
```

**2. Register via `META-INF/services/`**

```
# META-INF/services/com.aicodeassistant.plugin.PluginExtension
com.example.MyPlugin
```

**3. Place the JAR in `~/.zhikun/plugins/`**

**4. Restart the service or run `/reload-plugins` to hot reload**

> The `PluginExtension` interface uses default methods — a minimal implementation only requires `name()` and `version()`. Additional capabilities (commands/tools/hooks/MCP) can be overridden as needed.

---

## 🧠 Memory System

ZhikunCode features a three-layer memory architecture that lets the AI assistant **remember your preferences, project conventions, and workflows across sessions**.

### Three-Layer Memory Architecture

| Layer | File | Scope | Description |
|-------|------|-------|-------------|
| **Personal Memory** | `~/.ai-code-assistant/MEMORY.md` | Global, cross-project | AI auto-records user preferences, common patterns, error solutions |
| **Project Memory** | `zhikun.md` / `zhikun.local.md` | Current project | Project-level coding conventions, architecture decisions, build processes |
| **Team Memory** | `.zhikun/team-memories/*.md` | Shared with team | Team standards and shared knowledge distributed with the codebase |

### Memory Categories

Based on cognitive psychology models (a design-level conceptual taxonomy; the implementation distinguishes memories via file paths and metadata tags), memories are automatically classified into four types:

| Category | Description | Example |
|----------|-------------|----------|
| **Semantic** | Project knowledge, user preferences, technical conventions | "This project uses JUnit 5 + AssertJ" |
| **Episodic** | Specific operation history, debugging sessions | "Port conflict fix from last deployment" |
| **Procedural** | Common workflows, deployment processes | "Always run `mvn test` before committing" |
| **Team** | Team-level shared knowledge and standards | "All APIs return unified Result wrapper" |

### Automatic Memory & Search

The AI automatically records and retrieves memories via the built-in MemoryTool:

- **Auto-write** — AI proactively records important information (user preferences, project norms, etc.)
- **Auto-load** — Memories are automatically injected into the system prompt at session start
- **BM25 Search** — Pure Java BM25 search engine with Chinese+English support (Unigram + Bigram CJK tokenization)
- **Source Tracking** — Memory source tracking (source field) to distinguish REST API-created vs LLM tool-created memories
- **LLM Reranking** — Optional LLM reranking service for precision after BM25 initial retrieval

### Project Memory Files

Create memory files in your project root — the AI will automatically read and follow them:

```markdown
# zhikun.md — Project conventions (committed to repo)

## Coding Standards
- Java methods use camelCase naming
- Test classes end with Test suffix

## Build Process
- Run `./mvnw test` before every commit
- Use Conventional Commits format
```

```markdown
# zhikun.local.md — Local config (not committed, add to .gitignore)

## Local Environment
- My API Key is in the .env file
- Local database port: 5432
```

> Project memory files are loaded by traversing up to 5 parent directories, with a 100KB per-file limit. `zhikun.md` is committed for team sharing; `zhikun.local.md` is for personal local configuration.

### Safety Protections

- Truncation protection: Personal memory capped at 200 lines / 25KB to prevent system prompt token explosion
- Auto-compaction: When exceeding 50,000 characters, automatically compacts keeping the newest 70%
- Auto-expiration: Memories untouched for 90 days are automatically purged
- Path traversal protection: Absolute path and symlink validation on project memory writes

---

## 💻 CLI Tools

Beyond the Web UI, ZhikunCode provides full command-line capabilities for three scenarios:

### Python CLI (aica) — Terminal AI Coding

`aica` is ZhikunCode's command-line client, designed as a first-class UNIX pipe citizen:

```bash
# Install
cd python-service
pip install -e ".[cli]"

# Basic usage
aica "refactor this function"

# Pipe input — compose like grep/sed
cat src/main.py | aica "review this code"

# Structured output + jq processing
aica -f json "list all API endpoints" | jq '.result'

# Streaming output
aica -f stream-json "refactor this module"

# Continue last conversation
aica --continue "fix the bug we just discussed"
```

**Key features:**

| Feature | Description |
|---------|-------------|
| Three output formats | `text` (terminal Markdown rendering) / `json` (structured) / `stream-json` (SSE streaming) |
| Pipe support | Auto-reads stdin, seamlessly composable with shell pipes |
| Permission modes | `--permission-mode dont_ask/bypass/default` to control security policy (CLI defaults to `dont_ask`) |
| Session management | `--continue` resumes last session, `--resume <id>` restores a specific session |
| Model selection | `--model` to specify model, `--effort` to control reasoning depth |
| Tool control | `--allowed-tools` / `--disallowed-tools` whitelist/blocklist |
| Exit codes | 0=success, 1=generic error, 2=argument error, 3=connection error, 4=auth error, 130=Ctrl+C |

> `aica` connects to the ZhikunCode backend via HTTP/SSE, sharing the same Agent engine, toolset, and security architecture. Ideal for CI/CD integration and scripting automation.

### 35+ Slash Commands — Web UI Quick Actions

> The following slash commands are available in the **Web UI**. The `aica` CLI accesses the same backend Agent engine through natural language prompts.

Type `/` or press `Ctrl+K` in the Web UI to open the command palette with fuzzy search and keyboard navigation:

| Category | Commands | Description |
|----------|----------|-------------|
| **Core** | `/help` `/clear` `/exit` | Help, clear conversation, exit |
| **Model** | `/model` | List/switch LLM models |
| **Diagnostics** | `/doctor` | 9-item system diagnostic (Java/LLM/Git/JVM/Python/Disk) |
| **Compression** | `/compact` | Manual context compression, accepts instructions (e.g., `/compact focus on API`) |
| **Git** | `/diff` `/commit` `/review` | Code diff, generate commit messages, code review |
| **Config** | `/config` `/permissions` | View config, permission mode management |
| **Session** | `/session` `/resume` | Session info, restore history sessions |
| **Cost** | `/cost` `/usage` | Token usage, cost statistics |
| **MCP** | `/mcp-servers` `/mcp-tools` | MCP service management |
| **Deep Analysis** | `/ultrareview` | AI deep review (architecture + security + performance + concurrency) |
| **Browser** | `/snap` | Semantic snapshot — capture current page DOM structure and interactive elements, generate JSON-format snapshot |
| **Visualization** | `/visualize mermaid\|json\|text` | Auto-routing push — stream-rendered Mermaid diagrams, JSON data, or plain-text visualization results |

> On mistyped commands, the system automatically suggests similar commands using Levenshtein distance matching.

---

## 📱 Full Browser-Based Control

This is ZhikunCode's core differentiator. Unlike Cursor, Cline, and other tools that require desktop clients or IDE plugins, ZhikunCode is a standalone web application — deploy once, use from any device's browser.

### Why This Matters

| Scenario | Traditional AI Coding Tools | ZhikunCode |
|----------|---------------------------|------------|
| Approve a permission request on your commute | ❌ Need to open your laptop | ✅ Use your phone browser |
| A colleague wants to try your AI coding assistant | ❌ Install VS Code + plugins | ✅ Just share a link |
| Deploy to a team server for shared use | ❌ Everyone installs a client | ✅ Open a browser and go |
| Edit code on an iPad | ❌ No native client | ✅ Works in Safari/Chrome |

### Complete Browser Control

From your browser, you can manage the entire AI coding workflow:

- **Conversational coding** — Describe requirements in natural language; the Agent generates code with real-time streaming output
- **Permission approvals** — Every sensitive operation triggers an approval prompt: allow / deny / modify
- **Plan discussion** — Review, discuss, and confirm Agent-proposed plans in the browser
- **Task management** — Monitor progress, interrupt execution, reassign tasks
- **File browsing** — Navigate and view the project file tree directly in the browser
- **Agent collaboration visualization** — See real-time status of each Agent in multi-Agent mode

### Real-Time Communication

The frontend and backend maintain a real-time connection via **STOMP over SockJS** (auto-negotiates WebSocket → xhr-streaming → xhr-polling fallback):

- **Streaming output** — LLM responses stream token by token, no waiting for completion
- **Permission bubbling** — Sub-Agent permission requests are pushed to the browser in real time
- **State synchronization** — Agent state changes are reflected in the UI instantly
- **Heartbeat keep-alive** — Bidirectional 10s heartbeat detection, auto-reconnect on disconnect (exponential backoff 1s→10s)
- **Message guarantees** — 128KB message size limit, 1MB send buffer, 30s send timeout

---

## 🤖 Multi-Agent Collaboration

ZhikunCode offers three Agent collaboration modes and five typed Agent definitions for tasks of varying complexity.

### Five Built-in Agent Types

Built on Java 21 sealed interfaces with compile-time exhaustiveness checking. Each Agent type has its own toolset, model preference, and system prompt:

| Agent Type | Purpose | Toolset | Model Preference |
|-----------|---------|---------|------------------|
| **General-Purpose** | Full implementation capability | All tools, unrestricted | Inherits parent |
| **Explore** | Read-only code search | FileEdit/FileWrite denied | Lightweight (light) |
| **Verification** | Adversarial test validation | FileEdit/FileWrite denied | Inherits parent |
| **Plan** | Analysis & solution design | FileEdit/FileWrite denied | Inherits parent |
| **Guide** | Documentation & usage guidance | Only Glob/Grep/FileRead/WebFetch/WebSearch | Lightweight (light) |

> All sub-agents are blocked from calling Agent/TeamCreate/TeamDelete tools, architecturally preventing infinite recursion.

### Team Mode — Fixed Roles

Team collaboration with predefined roles. Each Agent has a clear set of responsibilities and tools.

```
┌─────────────┐
│   Leader     │  Task assignment & result aggregation
└──────┬──────┘
       │
  ┌────┴────┐
  ▼         ▼
┌──────┐ ┌──────┐
│Agent A│ │Agent B│  Parallel execution, independent toolsets
│Backend│ │Frontend│
└──────┘ └──────┘
```

- Use case: frontend/backend split development, test + dev collaboration
- Agents communicate via `TeamMailbox` (async, ConcurrentLinkedQueue)
- Tasks shared through `SharedTaskList` FIFO queue with claim & status tracking
- `InProcessBackend` runs multiple Workers concurrently via Virtual Threads

### Swarm Mode — Dynamic Negotiation

Dynamic multi-Worker collaboration built on Java 21 virtual threads, orchestrated by the Coordinator through a four-phase workflow:

```
Research → Synthesis → Implementation → Verification
```

Phases follow strict sequential order (no skipping). Each phase records timestamps and result summaries. `CoordinatorWorkflow` manages the full phase lifecycle.

- Use case: complex refactoring, large-scale code migrations
- Worker count adjusts dynamically, no pre-declaration needed
- One Virtual Thread per Worker, 30-minute timeout protection
- Worker toolsets precisely controlled via allowList/denyList
- Permissions bubble up to UI (`LeaderPermissionBridge`) with stacked display for concurrent permission requests, each with an independent 60-second countdown timer, supporting individual or batch approve/deny — auto-denies on timeout to prevent deadlocks
- Real-time status pushed via STOMP WebSocket
- Active Swarms managed by Caffeine cache, 4-hour TTL auto-evicts stale instances

### SubAgent Mode — Parent-Child Delegation

The main Agent delegates subtasks to independent child Agents, with three isolation levels:

| Isolation Mode | Behavior | Use Case |
|---------------|----------|----------|
| **NONE** | Shares parent Agent working directory | Lightweight subtasks |
| **WORKTREE** | Creates independent Git Worktree, auto-merges or discards on completion | Experimental changes needing isolation |
| **Fork** | Inherits parent session’s full message history, reuses LLM KV cache | Continuation tasks needing full context |

- Supports background async execution (`BackgroundAgentTracker`), real-time pushing start/complete/fail events via WebSocket for live monitoring of agent execution progress
- Per-agent 5-minute timeout, results capped at 100,000 characters

### Three-Layer Concurrency Safety

`AgentConcurrencyController` enforces three-layer limits via Semaphore + session-level counters:

| Dimension | Limit | Protection Target |
|-----------|-------|-------------------|
| Global concurrency | ≤ 30 agents | Memory & API pressure |
| Session concurrency | ≤ 10 agents/session | Interactive resource isolation |
| Nesting depth | ≤ 3 levels | Prevents infinite recursion |

Slots are auto-released via RAII pattern (`try-with-resources`), ensuring no resource leaks on exception paths.

### Model Alias Routing

Agents use a three-level fallback strategy for model resolution: user parameter → Agent type default → global default. Aliases are configured in `application.yml` under `agent.model-aliases` (e.g., `light → qwen-plus`), avoiding hardcoded model names — configure once, apply everywhere.

---

## 🧩 MCP Tool Extensions

ZhikunCode implements the standard [MCP (Model Context Protocol)](https://modelcontextprotocol.io/) and supports connecting to external MCP services via SSE transport:

### Built-in MCP Tools

| Tool | Description | Source |
|------|-------------|--------|
| **Wanx 2.5 Image Generation** | AI painting — generate images from text | DashScope MCP |
| **Wanx 2.5 Image Editing** | AI image editing (image-to-image) | DashScope MCP |
| **Web Search Pro** | Online search, returns web page summaries | DashScope MCP |

### Custom MCP Tools

Register new MCP tools in `configuration/mcp/mcp_capability_registry.json`:

```json
{
  "id": "mcp_your_tool",
  "name": "Your Tool Name",
  "toolName": "mcp_server_tool_name",
  "sseUrl": "https://your-mcp-server/sse",
  "domain": "your_domain",
  "category": "MCP_TOOL",
  "enabled": true
}
```

### MCP Protocol Enhancements

- **MCP Roots Security Boundary**: Declares workspace filesystem roots to prevent MCP servers from accessing unauthorized paths
- **MCP Progress Tracking**: Real-time progress display for long-running MCP tool operations with cancellation support
- **MCP Schema Compression**: Automatically compresses large tool parameter schemas to reduce LLM context usage

---

## 🛠️ Built-in Tools

ZhikunCode ships with 27 built-in tools + MCP dynamic extensions, covering the full development lifecycle:

| Category | Tools | Description |
|----------|-------|-------------|
| **File Operations** | FileRead, FileWrite, FileEdit, NotebookEdit | Read, write, and edit files (atomic writes + SHA-256 conflict detection), including Jupyter Notebook support |
| **Code Search** | GrepTool, GlobTool, ToolSearch, LspTool, SnipTool | Regex search, file glob matching, tool search, LSP language service (call hierarchy analysis), code snippets, intelligent layered search (scope-aware 4-layer priority routing) |
| **Command Execution** | BashTool, PowerShellTool, REPLTool | Shell sandbox execution (dynamic timeout classification + exponential backoff recovery), Windows PowerShell, interactive REPL sessions |
| **Git Operations** | GitTool, Worktree | Git command execution, Worktree management |
| **Web Tools** | WebSearch, WebFetch, WebBrowser | Web search, page fetching, browser automation |
| **Agent Collaboration** | AgentTool | Create and manage sub-Agents |
| **Task Management** | Task create/get/list/update/stop/output | SharedTaskList task collaboration (6 tools) |
| **Interaction** | AskUserQuestion, Brief, Sleep, TodoWrite | User questions, briefings, wait, todo lists |
| **Scheduled Tasks** | CronCreate, CronList, CronDelete | Cron job management |
| **Plan Mode** | EnterPlanMode, ExitPlanMode, VerifyPlan | Plan-then-execute workflow |
| **Configuration** | ConfigTool, SendMessage, SyntheticOutput | Config management, message sending, synthetic output |
| **Monitoring** | MonitorTool, CtxInspect, TerminalCapture | System monitoring, context inspection, terminal output capture |
| **Verification** | VerifyJourneyTool, BrowserVerifier, HttpApiVerifier | Runtime verification toolset — end-to-end browser testing, HTTP API assertion chains, hybrid-mode auto-switching |
| **MCP Extensions** | MCP tool adapters | Connect to external MCP services (dynamically registered) |

---

## 📈 Visualization

ZhikunCode includes 11 built-in visualization features that make data and status transparent throughout the AI coding process:

| Feature | Description |
|---------|-------------|
| **Mermaid Diagram Rendering** | Mermaid code blocks in AI responses are automatically rendered as interactive vector diagrams, with copy SVG / download PNG support |
| **API Sequence Diagram** | Automatically extracts tool call records from conversations and generates Mermaid sequence diagrams, with filtering and detail viewing |
| **Agent DAG** | Real-time display of multi-Agent task dependency graphs, built on React Flow, with TB/LR layout switching |
| **Git Timeline** | Visualizes Git commit history with Diff viewing and Blame view, auto-colored by commit type |
| **Tool Progress Visualization** | Displays progress bars, ETA estimates, and a mini log viewer during tool execution |
| **File Tree Navigation** | Sidebar project file tree with search filtering, virtual scrolling, and file type icons |
| **Code Complexity Treemap** | Interactive treemap built on recharts — area maps to LOC, color maps to risk level (A-E). Supports drill-down navigation, language/risk filtering, and stats cards. Multi-language analysis via Python radon + tree-sitter |
| **Change Impact Analysis** | DAG visualization built on @xyflow/react showing change propagation paths. LibCST-powered precise Python call graph analysis with BFS propagation. Displays node type, confidence, and impact depth at a glance |
| **API Contract Viewer** | Auto-merges Java + Python dual-service OpenAPI specs. Endpoints grouped by tag, HTTP methods color-coded, recursive Schema display. Supports All/Java/Python data source switching |
| **Code-to-Diagram Auto-Generation** | Input a code file path to auto-generate Mermaid sequence diagrams / flowcharts. Python LibCST + tree-sitter multi-language parsing, BFS call-chain traversal with auto-identification of Controller/Service/Repository participants, five-dimensional confidence scoring (0-1), Monaco Editor for real-time source editing, SVG copy / PNG download export, supports 1-5 level traversal depth control |
| **Code Path Tracing Visualization** | Interactive code call-path tracing visualization built on @xyflow/react. Python CodePathTracer performs forward BFS traversal with six-layer classification (Controller/Service/Repository/Database/External/Utility), dagre TB layout algorithm for automatic node arrangement, custom LayerNode components with layer-based coloring, MiniMap for global overview + LayerStatsBar for layer statistics, supports API endpoint scanning, parameter tracking, node click details, and maxDepth depth control via the sidebar "Code Path" tab |
| **Runtime Verification Progress Panel** | JourneyVerifyPanel — real-time display of verification step execution status (waiting/executing/passed/failed), STOMP push progress, evidence chain association |
| **Evidence Bundle Viewer (RV-4)** | `EvidenceBundleView` tabbed panel rendering 7 verification evidence types — screenshots, command outputs, console errors, test results, network requests (HAR), video recordings, and code diffs — sourced from `/api/evidence/{bundleId}` and `/api/evidence/session/{sessionId}`, with binary assets streamed via `/api/evidence/blob/{sha256}`. On verification failure, the backend pushes a STOMP `verify_attention` message to `/user/queue/messages`, which triggers `MobileApprovalSheet` — a mobile bottom-sheet supporting one-tap approve/reject. Frontend state managed by `evidenceStore` |
| **Activity Panel** | Three-layer card display (L1 Compact → L2 Expanded → L3 Portal), real-time tool execution status, Signal risk markers, approval decision tracking |

> **New in v9.3**: The `/visualize` command auto-pushes three formats (mermaid / json / text) via VisualizationAutoRouter, with WS STOMP `/app/command` end-to-end latency p50 < 3ms.

---

## ⚙️ Configuration

### Environment Variables

Environment variables are managed via the `.env` file. Copy `.env.example` and modify as needed:

**Multi-Provider Configuration (Recommended):**

| Variable | Required | Default | Description |
|----------|:---:|---------|-------------|
| `LLM_PROVIDER_DASHSCOPE_API_KEY` | — | — | Qwen/DashScope API Key |
| `LLM_PROVIDER_DEEPSEEK_API_KEY` | — | — | DeepSeek API Key |
| `LLM_PROVIDER_MOONSHOT_API_KEY` | — | — | Moonshot/Kimi API Key |
| `LLM_DEFAULT_MODEL` | — | qwen3.7-max | Default model (used when no explicit selection) |

> In multi-Provider mode, configure at least one Provider's API Key. The frontend supports free switching between configured Providers.

**Single-Provider Configuration (Backward Compatible):**

| Variable | Required | Default | Description |
|----------|:---:|---------|-------------|
| `LLM_API_KEY` | ✅ | — | API Key for your LLM provider |
| `LLM_BASE_URL` | — | DashScope | LLM API endpoint |
| `LLM_DEFAULT_MODEL` | — | qwen3.7-max | Default model |
| `LLM_MODELS` | — | Qwen series | Available models (comma-separated) |

> If all `LLM_PROVIDER_*` keys are empty, the system automatically falls back to single-Provider mode.

**General Configuration:**

| Variable | Required | Default | Description |
|----------|:---:|---------|-------------|
| `ZHIKUN_PORT` | — | 8080 | Host port for Docker mapping |
| `SPRING_PROFILES_ACTIVE` | — | production | Spring profile |
| `JAVA_OPTS` | — | -Xms256m -Xmx1024m | JVM options |
| `WORKSPACE_PATH` | — | ./workspace | Working directory mounted into the container |
| `ALLOW_PRIVATE_NETWORK` | — | true (Docker) | Allow private network IPs to bypass auth in Docker |
| `LOG_DIR` | — | /app/log | Container log directory |
| `MCP_REGISTRY_PATH` | — | Auto-configured | MCP capability registry file path |

**Advanced Configuration:**

| Variable | Required | Default | Description |
|----------|:---:|---------|-------------|
| `ZHIKUN_COORDINATOR_MODE` | — | 0 | Feature flag, enable coordinator mode (0=off, 1=on) |
| `LLM_PROVIDER_DASHSCOPE_MODELS` | — | qwen3.7-max,qwen3.6-plus | DashScope available models (comma-separated) |
| `LLM_PROVIDER_DEEPSEEK_MODELS` | — | deepseek-v4-pro,deepseek-v4-flash | DeepSeek available models (comma-separated) |
| `LLM_PROVIDER_MOONSHOT_MODELS` | — | kimi-k2.6,moonshot-v1-128k | Moonshot available models (comma-separated) |

**Context Management Configuration (application.yml):**

| Configuration | Default | Description |
|--------------|---------|-------------|
| `context.cascade.incremental-collapse.enabled` | true | Enable incremental collapse |
| `context.cascade.incremental-collapse.segment-turns` | 10 | Collapse trigger interval (turns) |
| `context.cascade.incremental-collapse.session-timeout-minutes` | 30 | Session timeout |
| `features.flags.CACHED_MICROCOMPACT` | true | Enable micro-compact service |
| `features.flags.TOKEN_BUDGET` | false | Token budget control (disabled by default; enable when needed) |
| `features.flags.SELF_CORRECTION_LOOP` | false | Auto-diagnose and fix execution failures (compile errors/test failures, max 3 retries) |
| `features.flags.PRECISE_TOKENIZER` | false | Precise token counting (Python tiktoken, replaces character estimation) |
| `features.flags.GIT_DIFF_TRACKER` | false | Git change tracking and edit history aggregation |
| `features.flags.SEARCH_STRATEGY_ROUTER` | false | Scope-aware layered search strategy routing |
| `RUNTIME_VERIFICATION` | true | Enable runtime verification framework (displays verification progress panel on frontend, requires BROWSER_AUTOMATION or HTTP_API capability domain) |

### Docker Resource Limits

Default resource configuration (adjustable in `docker-compose.yml`):

| Setting | Default |
|---------|---------|
| Memory limit | 4GB |
| Memory reservation | 1GB |
| Health check interval | 30s |
| Startup grace period | 60s |

> **Note:** The initial image build requires more memory (Maven compilation + npm build). If the build fails, increase Docker Desktop's memory allocation to 6GB or more in its settings. The runtime container memory limit is 4GB (adjustable in docker-compose.yml).

---

## ❓ FAQ

<details>
<summary><b>Q1: Which LLMs are supported?</b></summary>

Any model compatible with the OpenAI API format, including:

- **Qwen / DashScope** (direct connection in China, recommended default)
- **DeepSeek** (direct connection in China)
- **Moonshot / Kimi** (direct connection in China)
- **OpenAI GPT-4o / GPT-4** (requires international network access)
- **Anthropic Claude** (via OpenAI-compatible API)
- **Local models** (via Ollama, vLLM, etc.)

As long as the provider is compatible with the OpenAI API format, just configure `LLM_BASE_URL` and `LLM_API_KEY`.

</details>

<details>
<summary><b>Q2: What are the Docker deployment requirements?</b></summary>

**Minimum requirements:**
- Docker 20.10+
- Docker Compose V2
- 4GB+ available RAM
- Network access to the LLM API endpoint (domestic network is fine for Qwen)

**Deploy in 3 steps:**
```bash
git clone https://github.com/zhikunqingtao/zhikuncode.git && cd zhikuncode
cp .env.example .env  # Edit and add your API Key
docker compose up -d  # Start
```

Open `http://localhost:8080` and you're ready to go.

</details>

<details>
<summary><b>Q3: Where is data stored? Is it secure?</b></summary>

**All data stays local** — nothing is sent to any third-party server:

- **Session data** — SQLite database stored in Docker Volume `zhikun-data`
- **Project code** — Your local project directory is mounted via Docker Volume
- **API Key** — Stored only in your `.env` file and the running container's environment variables

ZhikunCode does not run any telemetry. Your API Key connects directly to your configured LLM provider with no proxies or intermediary servers.

</details>

<details>
<summary><b>Q4: Can it run on an internal network / offline?</b></summary>

**Yes.** Once deployed via Docker, it runs entirely on your local network.

- **Using Chinese LLMs (Qwen/DeepSeek):** Direct connection from mainland China, no VPN needed
- **Fully offline:** Pair with Ollama for local models — `LLM_BASE_URL=http://host.docker.internal:11434/v1`
- **Enterprise intranet:** Just ensure the server can reach the LLM API endpoint

</details>

<details>
<summary><b>Q5: How do I use multi-Agent collaboration?</b></summary>

ZhikunCode offers three collaboration modes:

- **Team** — Fixed roles: create a team, each Agent works in parallel according to its role
- **Swarm** — Dynamic negotiation: tasks are automatically decomposed, Workers are dynamically assigned, four-phase workflow
- **SubAgent** — Parent-child delegation: the main Agent delegates subtasks to child Agents with isolated execution

Just describe your requirement in the conversation, for example:
> "Refactor the user authentication module — one Agent handles the backend API, another handles the frontend pages"

The Agent will automatically select the appropriate collaboration mode.

</details>

<details>
<summary><b>Q6: Does it conflict with VS Code plugins (Copilot/Cline)?</b></summary>

**No.** ZhikunCode is a standalone web application that doesn't depend on any IDE and requires no plugins.

You can use both simultaneously:
- **VS Code + Copilot** — for line-level code completion
- **ZhikunCode** — for conversational Agent programming and complex task orchestration

They complement each other.

</details>

<details>
<summary><b>Q7: How do I contribute?</b></summary>

Contributions are welcome! See [CONTRIBUTING.md](../CONTRIBUTING.md) for the full guide.

Quick steps:
1. Fork the repository
2. Create a feature branch (`git checkout -b feature/your-feature`)
3. Commit your changes
4. Open a Pull Request

We recommend starting with Issues labeled `good first issue`.

**Development environment:** JDK 21, Node.js 22+, Python 3.11~3.12, Maven 3.9+

</details>

<details>
<summary><b>Q8: Why the Java + React + Python three-tier architecture?</b></summary>

Each technology choice has a clear rationale:

- **Java 21 + Spring Boot (Backend):**
  - Strong typing + mature enterprise ecosystem for maintainable code
  - Spring WebSocket provides native real-time communication support
  - Virtual Threads are a natural fit for concurrent multi-Agent execution
  - Easy for enterprise IT teams to adopt and deploy

- **React 18 + TypeScript (Frontend):**
  - Component-based development with mature state management (Zustand)
  - TypeScript provides type safety
  - Vite offers fast builds and a great developer experience
  - TailwindCSS enables efficient UI development

- **Python FastAPI (Analysis Service):**
  - Python's ecosystem excels at code analysis and AST parsing
  - FastAPI delivers strong async performance
  - Running as an independent service keeps the main backend stable

</details>

<details>
<summary><b>Q9: How to troubleshoot Docker deployment issues?</b></summary>

**Container shows unhealthy after startup:**

```bash
# Check container status
docker ps -a

# View startup logs (Java typically needs 30-60s to start)
docker logs zhikuncode

# Inspect health check details
docker inspect --format='json .State.Health' zhikuncode | python3 -m json.tool
```

**Common startup failure causes:**
- `LLM_API_KEY is not configured` — API Key not set, check your .env file
- `Unable to access jarfile` — Incomplete image build, try `docker compose up --build`
- Out of memory — Default requires 4GB, adjust `deploy.resources.limits.memory` in docker-compose.yml

**View runtime logs:**
```bash
# Follow logs in real-time
docker logs -f zhikuncode

# Enter container to check log files
docker exec -it zhikuncode ls -la /app/log/
docker exec -it zhikuncode tail -100 /app/log/app.log
```

**About `ALLOW_PRIVATE_NETWORK`:**

This variable controls whether requests from Docker bridge network IPs can bypass authentication. It defaults to `true` in Docker environments since container networking already provides isolation. For stricter security (e.g., multi-tenant environments), set to `false` — all non-localhost requests will require Bearer Token authentication.

**Adjust JVM memory:**

Set in `.env`:
```bash
JAVA_OPTS=-Xms512m -Xmx2048m --enable-preview
```

</details>

<details>
<summary><b>Q10: What if port 8080 is already in use?</b></summary>

Edit the port in your `.env` file:

```bash
ZHIKUN_PORT=9090  # Change to any available port
```

Then restart:
```bash
docker compose down
docker compose up -d
```

Access `http://localhost:9090` instead.

</details>

---

## 🤝 Contributing

We welcome all forms of contribution — bug fixes, new features, documentation improvements.

See [CONTRIBUTING.md](../CONTRIBUTING.md) for details.

---

## 📄 License

This project is licensed under the [MIT License](../LICENSE).

---

## 📬 Contact

- **Email:** alizhikun@gmail.com
- **GitHub Issues:** [Open an Issue](https://github.com/zhikunqingtao/zhikuncode/issues)

---

## ⭐ Star History

If this project is useful to you, a Star ⭐ would be appreciated.

<div align="center">
  <a href="https://star-history.com/#zhikunqingtao/zhikuncode&Date">
    <img src="https://api.star-history.com/svg?repos=zhikunqingtao/zhikuncode&type=Date" alt="Star History Chart" width="600" />
  </a>
</div>

---

<div align="center">
  <p>Built with ❤️ and AI</p>
</div>
