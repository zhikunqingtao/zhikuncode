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
    <a href="#-cli-tools">CLI Tools</a> ·
    <a href="#-skill-system">Skill System</a> ·
    <a href="#-comparison">Comparison</a> ·
    <a href="../README.md">中文</a>
  </p>

  <p>
    <a href="https://opensource.org/licenses/MIT"><img src="https://img.shields.io/badge/License-MIT-yellow.svg" alt="License: MIT" /></a>
    <a href="https://github.com/zhikunqingtao/zhikuncode"><img src="https://img.shields.io/badge/Docker-Ready-blue?logo=docker" alt="Docker" /></a>
    <a href="https://github.com/zhikunqingtao/zhikuncode/stargazers"><img src="https://img.shields.io/github/stars/zhikunqingtao/zhikuncode?style=social" alt="GitHub Stars" /></a>
    <a href="https://github.com/zhikunqingtao/zhikuncode"><img src="https://img.shields.io/github/last-commit/zhikunqingtao/zhikuncode" alt="Last Commit" /></a>
    <a href="https://github.com/zhikunqingtao/zhikuncode"><img src="https://img.shields.io/github/languages/code-size/zhikunqingtao/zhikuncode" alt="Code Size" /></a>
  </p>
</div>

---

> **deploy it to a server, open a browser, and start coding. Works on your phone too.**


---

## ✨ Key Features

| | Feature | Description |
|---|---|---|
| 🌐 | **Full Browser-Based Control** | Deploy once, then manage everything from any device's browser — permission approvals, plan discussions, task management. Works on mobile. No client installation needed |
| 🤖 | **Multi-Agent Collaboration** | Three collaboration modes: Team (fixed roles) / Swarm (dynamic negotiation) / SubAgent (parent-child delegation). Complex tasks are automatically distributed |
| 🔒 | **Defense-in-Depth Security** | 8-layer Bash sandbox + 14-step permission pipeline + 289 security tests. Every command must pass security checks before execution |
| 🇨🇳 | **Native Chinese LLM Support** | Qwen / DeepSeek / Moonshot work out of the box with direct connections from mainland China — no VPN required |
| 🐳 | **One-Command Docker Deployment** | `docker compose up -d` — one command to start. Data stays local, fully private |

---

## 🎬 Demo

### File Writing
![File Writing Demo](assets/demo-write-file.gif)

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

**Prerequisites:** JDK 21, Node.js 20+, Python 3.11+

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

### Supported LLM Providers

Configure `LLM_BASE_URL` and `LLM_API_KEY` in `.env` to switch providers:

| Provider | Base URL | Recommended Model | Notes |
|----------|----------|-------------------|-------|
| **Qwen / DashScope** | `https://dashscope.aliyuncs.com/compatible-mode/v1` | qwen3.6-plus | **Default**, direct connection in China |
| **DeepSeek** | `https://api.deepseek.com/v1` | deepseek-chat | Direct connection in China |
| **Moonshot (Kimi)** | `https://api.moonshot.cn/v1` | moonshot-v1-auto | Direct connection in China |
| **OpenAI** | `https://api.openai.com/v1` | gpt-4o | Requires international network access |
| **Local Ollama** | `http://localhost:11434/v1` | qwen2.5:latest | Fully offline |

> Any provider compatible with the OpenAI API format can be integrated — just configure the corresponding Base URL and API Key.

---

## 📊 Comparison

### Feature Comparison

| Feature | ZhikunCode | Aider | Cline | Cursor | Claude Code | Copilot |
|---------|:---:|:---:|:---:|:---:|:---:|:---:|
| Open Source & Free | ✅ | ✅ | ✅ | ❌ | ❌ | ❌ |
| Web UI | ✅ Full-featured | ⚠️ Streamlit | ❌ | ⚠️ Web ver. | ✅ | ❌ |
| Docker Self-hosted | ✅ Full web service | ⚠️ CLI container | ❌ | ⚠️ Enterprise | ❌ | ❌ |
| Chinese LLM Support | ✅ Native | ⚠️ Compatible API | ⚠️ Compatible API | ❌ | ❌ | ❌ |
| Multi-Agent | ✅ Team/Swarm/Sub | ❌ | ❌ | ✅ Multi-Agents | ✅ Sub-Agents | ✅ Agent Mode |
| Full Browser Control¹ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ |
| Security Sandbox | ✅ 8-layer | ❌ | ❌ | ⚠️ Enterprise | ✅ OS-level | N/A |
| MCP Tool Extension | ✅ | ⚠️ 3rd-party | ✅ | ❌ | ✅ | ✅ |
| CLI Terminal Tools | ✅ aica + 35+ slash cmds | ✅ CLI-first | ⚠️ VS Code only | ❌ | ✅ CLI-only | ❌ |
| Extensible Skill System | ✅ Markdown-driven + 6-level sources | ❌ | ❌ | ✅ Rules | ✅ Hooks | ❌ |
| No Client Install | ✅ | ❌ | ❌ | ⚠️ | ✅ | ❌ |

> ¹ **Full Browser Control**: After deployment, any device's browser (including mobile) can fully control the entire coding workflow — permission approval, plan negotiation, task management. This is different from Cline/Cursor's "AI controlling a browser for automated testing".

### Security Comparison

| Security Feature | ZhikunCode | Aider | Cline | Claude Code |
|-----------------|:---:|:---:|:---:|:---:|
| Command Sandbox | 8-layer checks | ❌ User approval | ❌ User approval | ✅ gVisor/Firecracker |
| Permission Pipeline | 14-step pipeline | ❌ | Simple confirm | Permission system |
| Security Tests | 289 items | Not disclosed | Not disclosed | Not disclosed |
| Sensitive Path Block | ✅ | ❌ | ❌ | ❌ |
| Dangerous Cmd Block | ✅ | ❌ | ❌ | ✅ Partial |
| Env Var Whitelist | ✅ | ❌ | ❌ | ❌ |

> **Note:** Comparison based on official documentation (2025 Q2). Please [open an issue](https://github.com/zhikunqingtao/zhikuncode/issues) if any inaccuracy is found. Cursor 2.0+ and GitHub Copilot Agent Mode are relatively new features still evolving rapidly.

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
| **Backend** | Java 21, Spring Boot 3.4, WebSocket, SQLite | Core orchestration engine, LLM API routing, Agent management, tool execution (47 built-in tools), permission pipeline, session persistence |
| **Frontend** | React 18, TypeScript, Vite, TailwindCSS | Conversational UI, file browser, settings panel, real-time streaming output, Agent collaboration visualization |
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

---

## 🔒 Security Architecture

Security is a core design principle of ZhikunCode. Every command must pass through multiple security layers before execution.

### 8-Layer Bash Security Sandbox

All shell commands must pass through these 8 layers before execution:

| Layer | Check | Description |
|-------|-------|-------------|
| **Layer 1** | Command parsing | Parses command structure; identifies pipes, redirects, and subcommands |
| **Layer 2** | Blocklist filtering | Blocks known dangerous commands (`rm -rf /`, `mkfs`, `dd`, `format`, etc.) |
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

- **289 security tests** covering all security paths
- Includes command injection, path traversal, permission bypass, and other attack scenarios
- The full security test suite runs on every code change

### 🧪 Quality Assurance

Full test report: [ZhikunCode Core Functionality Test Report](ZhikunCode核心功能测试报告.md)

- **110 test cases** — 100% pass rate
- **280+ automated tests** — all passing (Vitest + Pytest + Playwright + JUnit 5)
- **Feature completeness** — 100% coverage of benchmark features

---

## 🎯 Skill System

ZhikunCode's Skill System is a **Markdown-driven extensible workflow engine**. Each skill is a `.md` file — YAML frontmatter defines metadata, Markdown body defines execution instructions.

### 5 Built-in Skills

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
| **bundled** | Built-in | 5 out-of-the-box skills |
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
| Permission modes | `--permission-mode dont_ask/bypass/default` to control security policy |
| Session management | `--continue` resumes last session, `--resume <id>` restores a specific session |
| Model selection | `--model` to specify model, `--effort` to control reasoning depth |
| Tool control | `--allowed-tools` / `--disallowed-tools` whitelist/blocklist |
| Exit codes | 0=success, 2=argument error, 3=connection error, 4=auth error, 130=Ctrl+C |

> `aica` connects to the ZhikunCode backend via HTTP/SSE, sharing the same Agent engine, toolset, and security architecture. Ideal for CI/CD integration and scripting automation.

### 35+ Slash Commands — Web UI Quick Actions

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

The frontend and backend maintain a persistent WebSocket connection:

- **Streaming output** — LLM responses stream token by token, no waiting for completion
- **Permission bubbling** — Sub-Agent permission requests are pushed to the browser in real time
- **State synchronization** — Agent state changes are reflected in the UI instantly

---

## 🤖 Multi-Agent Collaboration

ZhikunCode offers three Agent collaboration modes for tasks of varying complexity:

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
- Agents communicate via TeamMailbox
- Tasks shared through SharedTaskList

### Swarm Mode — Dynamic Negotiation

Dynamic multi-Worker collaboration built on Java 21 virtual threads, with a four-phase workflow:

```
Research → Synthesis → Implementation → Verification
```

- Use case: complex refactoring, large-scale code migrations
- Worker count adjusts dynamically
- Permissions bubble up to the UI for unified user approval
- Real-time status updates via WebSocket

### SubAgent Mode — Parent-Child Delegation

The main Agent delegates subtasks to independent child Agents:

- Use case: tasks requiring isolated execution environments
- Supports Git Worktree isolation (independent working directories)
- Supports Fork mode (inherits parent Agent context)
- Supports background async execution

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

---

## 🛠️ Built-in Tools

ZhikunCode ships with 47 built-in tools covering the full development lifecycle:

| Category | Tools | Description |
|----------|-------|-------------|
| **File Operations** | FileRead, FileWrite, FileEdit | Read, write, and edit files |
| **Code Search** | GrepTool, GlobTool, ToolSearch | Regex search, file glob matching, tool search |
| **Command Execution** | BashTool | Execute shell commands inside the security sandbox |
| **Git Operations** | GitTool, Worktree | Git command execution, Worktree management |
| **Web Tools** | WebSearch, WebFetch, WebBrowser | Web search, page fetching, browser automation |
| **Agent Collaboration** | AgentTool | Create and manage sub-Agents |
| **Task Management** | Task create/list/assign | SharedTaskList-based task collaboration |
| **Scheduled Tasks** | CronCreate, CronList, CronDelete | Cron job management |
| **Plan Mode** | EnterPlanMode, ExitPlanMode, VerifyPlan | Plan-then-execute workflow |
| **MCP Extensions** | MCP tool adapters | Connect to external MCP services |
| **Monitoring** | MonitorTool, CtxInspect | System monitoring, context inspection |

---

## ⚙️ Configuration

### Environment Variables

Environment variables are managed via the `.env` file. Copy `.env.example` and modify as needed:

| Variable | Required | Default | Description |
|----------|:---:|---------|-------------|
| `LLM_API_KEY` | ✅ | — | API Key for your LLM provider |
| `LLM_BASE_URL` | — | DashScope | LLM API endpoint |
| `LLM_DEFAULT_MODEL` | — | qwen3.6-plus | Default model |
| `LLM_MODELS` | — | Qwen series | Available models (comma-separated) |
| `ZHIKUN_PORT` | — | 8080 | Host port for Docker mapping |
| `SPRING_PROFILES_ACTIVE` | — | production | Spring profile |
| `JAVA_OPTS` | — | -Xms256m -Xmx1024m | JVM options |
| `WORKSPACE_PATH` | — | ./workspace | Working directory mounted into the container |
| `ALLOW_PRIVATE_NETWORK` | — | true (Docker) | Allow private network IPs to bypass auth in Docker |
| `LOG_DIR` | — | /app/log | Container log directory |
| `MCP_REGISTRY_PATH` | — | Auto-configured | MCP capability registry file path |

### Docker Resource Limits

Default resource configuration (adjustable in `docker-compose.yml`):

| Setting | Default |
|---------|---------|
| Memory limit | 2GB |
| Memory reservation | 512MB |
| Health check interval | 30s |
| Startup grace period | 60s |

> **Note:** The initial image build requires more memory (~3-4GB). If the build fails, increase Docker Desktop's memory allocation to 4GB or more in its settings.

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

**Development environment:** JDK 21, Node.js 20+, Python 3.11+, Maven 3.9+

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
- Out of memory — Default requires 2GB, adjust `deploy.resources.limits.memory` in docker-compose.yml

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
