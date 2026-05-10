# Task 8 — 浏览器语义快照 MVP（Task 5 差异化）

> 时间：2026-05-09 23:39~23:43
> 目标：验证 ZhikunCode v1.5 升级项 A（Browser Semantic Snapshot MVP）端到端链路，包括：
> 1) Python `POST /api/browser/snapshot-semantic` 直接可用；
> 2) `/browser-snapshot` slash command（别名 `/snap`）走 WS → Java BackendReplayService → Python；
> 3) `GET /api/browser/replay/{sessionId}` 时间线读取；
> 4) 富交互页面 aria role 清单正确；
> 5) Capability 动态刷新（BROWSER_AUTOMATION available=true）。

---

## 8.1 证据一：Python 能力端点与 capability 刷新

```bash
GET http://localhost:8000/api/health/capabilities → 200
{
  "BROWSER_AUTOMATION": {"name": "浏览器自动化", "available": true},
  "CODE_INTEL": {"available": true},
  "GIT_ENHANCED": {"available": true},
  "FILE_PROCESSING": {"available": true},
  "CODE_QUALITY": {"available": true},
  "ANALYSIS": {"available": true}
}
```

后端日志实时刷新记录：

```
2026-05-09 23:41:23.857 INFO  PythonCapabilityAwareClient — Python 能力清单已刷新: 6 个域
2026-05-09 23:41:23.858 DEBUG POST /api/browser/snapshot-semantic body: {session_id, selector, include_screenshot,...}
```

> 结论：能力刷新机制工作正常（SUCCESS_CACHE_TTL=5min / FAILURE_CACHE_TTL=30s），BROWSER_AUTOMATION 常驻可用。

---

## 8.2 证据二：Python 直接语义快照（example.com）

```bash
SID=v93-snap-nav-1778341234
POST http://localhost:8000/api/browser/navigate
     { "session_id": SID, "url": "https://example.com", "wait_until": "load", "timeout": 15000 }
→ { "success": true, "data": { "url":"https://example.com/", "title":"Example Domain", "status":200 } }

POST http://localhost:8000/api/browser/snapshot-semantic
     { "session_id": SID, "selector": null, "interesting_only": true, "include_screenshot": false, "strict_session": true }
→ { "success": true, "data": {
      "url":"https://example.com/",
      "title":"Example Domain",
      "node_count": 6,
      "interactive": [ … link … ],
      "tree": {
        "aria": "- heading \"Example Domain\" [level=1]\n- paragraph: This domain is for use in…\n- paragraph:\n  - link \"Learn more\": /url: https://iana.org/domains/example"
      }
    } }
```

产物文件：[logs/snapshot-semantic-example.json](./logs/snapshot-semantic-example.json)

> 结论：ARIA 树以 YAML 形式产出，体积极小；interactive 提取正确（1 个 link）。

---

## 8.3 证据三：WebSocket `/snap` Slash Command 端到端

脚本：[scripts/ws-slash-visualize.cjs](./scripts/ws-slash-visualize.cjs)  
日志：[logs/ws-slash-snap-real.log](./logs/ws-slash-snap-real.log)

时序：

```json
{"t":"531ms","ev":"slash_command_sent","command":"snap","args":"no-screenshot"}
{"t":"584ms","ev":"MESSAGE","body":"{\"type\":\"command_result\",\"output\":\"Browser snapshot captured: url=https://example.com/ | nodes=6 | interactive=1 | frames=1\",\"resultType\":\"text\",\"command\":\"snap\"}"}
```

**端到端延迟**：`slash_command_sent 531ms → MESSAGE 584ms` = **53ms**（含 WS→Backend→Python→Playwright capture→cache append→WS 回程）。

后端摘要文本：

```
Browser snapshot captured: url=https://example.com/ | nodes=6 | interactive=1 | frames=1
```

> 结论：LOCAL 类型 slash command 链路完整；summary 文本与 Python 返回一致。

---

## 8.4 证据四：REST Replay 时间线

```bash
GET http://localhost:8080/api/browser/replay/105b8298-58b7-4a2b-8664-b1bebb6339d4 → 200
[
  {
    "snapshotId": "105b8298-58b7-4a2b-8664-b1bebb6339d4-1778341390886",
    "url": "https://example.com/",
    "title": "Example Domain",
    "nodeCount": 6,
    "interactive": [{ "role": "link", ... }],
    "tree": { "aria": "- heading \"Example Domain\" [level=1]\n- paragraph: …" }
  }
]
```

产物文件：[logs/replay-timeline.json](./logs/replay-timeline.json)

**核对**：

| 字段 | 来源 | 实际 | 判定 |
|---|---|---|---|
| `snapshotId` | `sessionId + "-" + System.currentTimeMillis()` | `105b8298-...-1778341390886` | ✅ 时间线可单调排序 |
| `url` | Python 返回的当前 URL | `https://example.com/` | ✅ |
| `nodeCount` | aria 节点数 | `6` | ✅ 与 8.2 一致 |
| `interactive` | Python role 提取 | 1 × link | ✅ |
| `tree.aria` | Playwright aria_snapshot | YAML 格式 | ✅ |

> 结论：Caffeine 内存 cache（`expireAfterWrite=10min`, `maximumSize=200`）正常累积快照；REST 读取时间线顺序正确。

---

## 8.5 证据五：富交互页面（httpbin /forms/post）— 13 个交互元素

```bash
SID=01fef962-4ae2-4202-8217-938290692bcd
POST /api/browser/navigate  url=https://httpbin.org/forms/post
POST /api/browser/snapshot-semantic  → nodes=44, interactive=13
```

后端命令结果：

```
Browser snapshot captured: url=https://httpbin.org/forms/post | nodes=44 | interactive=13 | frames=1
```

`interactive` role 分布：

| role | count | 示例 |
|---|---|---|
| textbox | 5 | `{name:"custname"}`, `{name:"custtel"}`, `{name:"custemail"}`, ... |
| radio | 3 | `{name:"size", value:"small"}`, medium, large |
| checkbox | 4 | 披萨加料 |
| button | 1 | 提交 |

aria 树前 400 字（已行内化）：

```
- paragraph: | - text: "Customer name:" | - textbox "Customer name:"
- paragraph: | - text: "Telephone:" | - textbox "Telephone:"
- paragraph: | - text: "E-mail address:" | - textbox "E-mail address:"
- group "Pizza Size": | - text: Pizza Size | - paragraph: | - radio "Small" | …
```

产物文件：[logs/replay-timeline-form.json](./logs/replay-timeline-form.json)

> 结论：复杂表单场景下 interactive 提取完整（13/13），role 分类准确，结构保留 group 层级。

---

## 8.6 证据六：后端单测链路（BrowserSnapshotCommand / BrowserReplayService / DomSnapshotClient）

Task 2 后端全量 1500 PASS 已覆盖，其中浏览器相关关键类（从 surefire XML 聚合）：

- `BrowserSnapshotCommandTest`
- `BrowserReplayServiceTest`
- `DomSnapshotClientTest`

> 结论：覆盖错误路径（capability 不可用 / 空 sessionId / 解析失败）与正常路径，均 PASS。

---

## 8.7 总体判定

| 维度 | 证据 | 判定 |
|---|---|---|
| Python 语义快照 API | 8.2 example.com 成功 | ✅ PASS |
| 后端 DomSnapshotClient → Python | 8.3 日志 `POST /api/browser/snapshot-semantic body(...)` | ✅ PASS |
| `/browser-snapshot` slash command | 8.3 WS command_result `output=Browser snapshot captured...` | ✅ PASS |
| Replay 时间线读 REST | 8.4 snapshotId + url + nodeCount 一致 | ✅ PASS |
| aria 树结构 | 8.2 + 8.5 两个场景 | ✅ PASS |
| 富交互提取 | 8.5 13 个 role 分类正确 | ✅ PASS |
| Capability 动态刷新 | 8.1 日志 `Python 能力清单已刷新: 6 个域` | ✅ PASS |
| 单测覆盖 | 8.6 Task 2 全绿 | ✅ PASS |

**Task 8 结论：PASS（端到端真实采集 + Replay 时间线读取全链路验证）。**

**过程小故障记录**（非生产 bug）：
- 首轮误用 `visualize` 的 args `'mermaid graph TD...'` 调 `/snap`，被解析为 `selector="mermaid"` 导致 Python 报 `ValueError: Element not found: mermaid`。
- 修正后以空 selector + `no-screenshot` 参数重跑 PASS。
- 此行为符合 `BrowserSnapshotCommand.execute` L64-72 的 args token 解析约定。
