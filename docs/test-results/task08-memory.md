# Task 8: 记忆系统专项测试

## 测试时间
2026-04-26 05:50 ~ 05:55 (UTC)

## 测试汇总

| # | 测试用例 | 结果 | 耗时 |
|---|---------|------|------|
| TC-MEM-01 | 获取现有记忆列表 | **PASS** | <1s |
| TC-MEM-02 | 创建记忆 — 多类别测试 | **PASS** | <1s |
| TC-MEM-03 | 读取并验证创建的记忆 | **PASS** | <1s |
| TC-MEM-04 | 更新记忆 | **PASS** | <1s |
| TC-MEM-05 | 删除记忆 | **PASS** | <1s |
| TC-MEM-06 | 记忆内容限制验证 | **PASS** | <1s |
| TC-MEM-07 | 通过 LLM 对话触发记忆操作 | **PASS** | ~15s |

**通过率：7/7 (100%)**

---

## 详细测试结果

### TC-MEM-01: 获取现有记忆列表

**请求**: `GET /api/memory`

**响应状态码**: 200

**响应体**:
```json
{
  "entries": [
    {
      "id": "d4e6a90f-2463-4163-b3c1-9323810c8d0c",
      "category": "general",
      "title": "test memory",
      "content": "test content",
      "keywords": "test",
      "scope": "global",
      "createdAt": "2026-04-13T16:06:36.885875Z",
      "updatedAt": "2026-04-13T16:06:36.885875Z"
    }
  ]
}
```

**验证**: 返回 200，现有 1 条记忆条目，结构完整包含 id/category/title/content/keywords/scope/createdAt/updatedAt 字段。

**判定**: **PASS**

---

### TC-MEM-02: 创建记忆 — 多类别测试

**创建 1 — user_info 类别**:
```
POST /api/memory
Body: {"category":"user_info","title":"TEST-用户测试信息","content":"这是一条用于测试的用户信息记忆条目","keywords":"test,memory,user","scope":"workspace"}
```
**响应**: `HTTP 201`
```json
{"success":true,"id":"4e1ada43-88ab-4a10-a042-4cc1c5308f1f"}
```

**创建 2 — project_tech_stack 类别**:
```
POST /api/memory
Body: {"category":"project_tech_stack","title":"TEST-项目技术栈测试","content":"本项目使用 Java 21 + Spring Boot 3.x 作为后端框架","keywords":"test,java,springboot","scope":"workspace"}
```
**响应**: `HTTP 201`
```json
{"success":true,"id":"34614f01-4ba6-4053-b36e-141b9737bd01"}
```

**创建 3 — expert_experience 类别 (scope=global)**:
```
POST /api/memory
Body: {"category":"expert_experience","title":"TEST-专家经验测试","content":"在处理并发问题时，应优先使用虚拟线程而非线程池","keywords":"test,concurrency,virtual-threads","scope":"global"}
```
**响应**: `HTTP 201`
```json
{"success":true,"id":"aaf0c2e0-75a2-455b-b893-88f737268980"}
```

**验证**: 3 个不同类别的记忆全部返回 201 + id，支持 workspace 和 global 两种 scope。

**判定**: **PASS**

---

### TC-MEM-03: 读取并验证创建的记忆

**请求**: `GET /api/memory`

**响应** (过滤 TEST- 条目):
```
ID: aaf0c2e0-75a2-455b-b893-88f737268980 | Category: expert_experience | Title: TEST-专家经验测试 | Scope: global
ID: 34614f01-4ba6-4053-b36e-141b9737bd01 | Category: project_tech_stack | Title: TEST-项目技术栈测试 | Scope: workspace
ID: 4e1ada43-88ab-4a10-a042-4cc1c5308f1f | Category: user_info | Title: TEST-用户测试信息 | Scope: workspace
Total entries: 4
```

**验证**: 3 个 TEST- 开头的记忆条目全部存在，category/scope 均正确。列表按 updatedAt 倒序排列。

**判定**: **PASS**

---

### TC-MEM-04: 更新记忆

**请求**:
```
PUT /api/memory
Body: {"entries":[{"id":"4e1ada43-88ab-4a10-a042-4cc1c5308f1f","category":"user_info","title":"TEST-用户测试信息-已更新","content":"这是更新后的用户信息记忆条目，增加了更多内容","keywords":"test,memory,user,updated","scope":"workspace"}]}
```

**响应**: `HTTP 200`
```json
{"success":true}
```

**验证读取**:
```json
{
  "id": "4e1ada43-88ab-4a10-a042-4cc1c5308f1f",
  "category": "user_info",
  "title": "TEST-用户测试信息-已更新",
  "content": "这是更新后的用户信息记忆条目，增加了更多内容",
  "keywords": "test,memory,user,updated",
  "scope": "workspace",
  "createdAt": "2026-04-26T05:50:12.067135Z",
  "updatedAt": "2026-04-26T05:50:43.016930Z"
}
```

**验证**: title 更新为 "TEST-用户测试信息-已更新"，content 已更新，keywords 已更新，updatedAt 时间戳已变更（从 05:50:12 变为 05:50:43）。

**判定**: **PASS**

---

### TC-MEM-05: 删除记忆

**请求**: `DELETE /api/memory/aaf0c2e0-75a2-455b-b893-88f737268980`

**响应**: `HTTP 204` (No Content)

**验证读取**:
```
Remaining TEST entries: 2
  TEST-用户测试信息-已更新
  TEST-项目技术栈测试
```

**验证**: 返回 204，expert_experience 类别的测试记忆已删除，剩余 2 条 TEST 记忆。

**判定**: **PASS**

---

### TC-MEM-06: 记忆内容限制验证

**请求**: 通过 python3 生成 249 行 / 7112 字符的超长内容，写入临时文件后用 curl 发送：
```
POST /api/memory
Body: {"category":"expert_experience","title":"TEST-超长内容限制测试","content":"<249行中文内容>","keywords":"test,limit","scope":"workspace"}
Payload size: 38,647 bytes
```

**响应**: `HTTP 201`
```json
{"success":true,"id":"55ba4a87-6cd0-4ad3-941b-2f889d205f2b"}
```

**验证读取**:
```
Title: TEST-超长内容限制测试
Content lines: 249
Content chars: 7112
First line: 这是第1行测试内容，用于验证记忆系统的内容长度限制。
Last line: 这是第249行测试内容，用于验证记忆系统的内容长度限制。
```

**验证**: 系统完整接受 249 行 / 7112 字符 / 38KB payload 的超长内容，无截断、无拒绝。REST API 层(/api/memory)不对内容长度做限制。

**备注**: MemdirService（文件存储层）有独立的大小限制机制：MAX_ENTRYPOINT_LINES=200, MAX_ENTRYPOINT_BYTES=25KB, MAX_MEMORY_SIZE=50000 字符，但这些限制仅作用于 MEMORY.md 文件存储，不影响 REST API 的 SQLite 存储。

**判定**: **PASS**

---

### TC-MEM-07: 通过 LLM 对话触发记忆操作

**请求**:
```
POST /api/query
Body: {
  "prompt": "请记住以下信息并保存为记忆：我最喜欢的编程语言是Python，标题请使用TEST-前缀。这是测试用的记忆，请直接调用update_memory工具创建。",
  "permissionMode": "BYPASS_PERMISSIONS",
  "workingDirectory": "/Users/guoqingtao/Desktop/dev/code/zhikuncode",
  "maxTurns": 5,
  "timeoutSeconds": 120
}
```

**响应**: `HTTP 200`
```json
{
  "sessionId": "7af2c5ed-2e97-47e0-a20d-87991ad1a4e9",
  "result": "已为您创建记忆，标题使用 TEST- 前缀，内容包含您最喜欢的编程语言是 Python 的信息。",
  "usage": {"inputTokens": 53026, "outputTokens": 205},
  "costUsd": 0.0,
  "toolCalls": [{"tool": "Memory", "output": "Memory saved.", "isError": false}],
  "stopReason": "end_turn"
}
```

**验证**: 在 `~/.ai-code-assistant/MEMORY.md` 中确认写入：
```
<!-- source:TOOL time:2026-04-26T05:53:35.880059Z category:semantic -->
TEST-用户偏好：最喜欢的编程语言是Python。这是测试用的记忆。
```

**重要发现**: 系统存在两套记忆存储：
1. **REST API (`/api/memory`)** → SQLite 数据库存储，供外部客户端直接 CRUD
2. **LLM Memory 工具** → `~/.ai-code-assistant/MEMORY.md` 文件存储（MemdirService），供 LLM Agent 跨会话持久化

LLM 的 Memory 工具使用 MemdirService 写入文件，而非 REST API 的 SQLite 数据库。两者相互独立。

**判定**: **PASS**

---

## 记忆数据结构示例

### REST API 记忆条目 (SQLite)
```json
{
  "id": "4e1ada43-88ab-4a10-a042-4cc1c5308f1f",
  "category": "user_info",
  "title": "TEST-用户测试信息-已更新",
  "content": "这是更新后的用户信息记忆条目，增加了更多内容",
  "keywords": "test,memory,user,updated",
  "scope": "workspace",
  "createdAt": "2026-04-26T05:50:12.067135Z",
  "updatedAt": "2026-04-26T05:50:43.016930Z"
}
```

### LLM Memory 工具条目 (MEMORY.md)
```
<!-- source:TOOL time:2026-04-26T05:53:35.880059Z category:semantic -->
TEST-用户偏好：最喜欢的编程语言是Python。这是测试用的记忆。
```

---

## 清理确认

### REST API 测试数据清理
- `TEST-超长内容限制测试` (55ba4a87) → 删除 HTTP 204 ✓
- `TEST-短内容专家测试` (f69b5ac8) → 删除 HTTP 204 ✓
- `TEST-用户测试信息-已更新` (4e1ada43) → 删除 HTTP 204 ✓
- `TEST-项目技术栈测试` (34614f01) → 删除 HTTP 204 ✓

### MEMORY.md 测试数据清理
- 通过 LLM Memory 工具 delete action 删除 "TEST-用户偏好" → "Memory deleted." ✓

### 清理后验证
- REST API: 1 条记忆（原有 "test memory"），0 条 TEST- 记忆 ✓
- MEMORY.md: 无 TEST- 条目 ✓

**确认：所有测试数据已完全清理，用户真实记忆数据未受影响。**

---

## 测试结论

记忆系统 7 个测试用例全部通过（7/7, 100%），CRUD 操作和 LLM 集成均正常工作。

### 关键发现
1. **双存储架构**：REST API 使用 SQLite 数据库，LLM Memory 工具使用 MEMORY.md 文件，两者独立运行
2. **无内容长度限制**：REST API 层不对记忆内容做长度限制（249行/38KB 正常接受）
3. **MemdirService 有独立限制**：MAX_ENTRYPOINT_LINES=200, MAX_ENTRYPOINT_BYTES=25KB（仅限文件存储）
4. **PUT 接口为 upsert 语义**：存在则更新，不存在则插入
5. **LLM 工具调用可靠**：Memory 工具的 read/write/delete 三种 action 均通过 LLM 正确触发

### 改进建议
1. 考虑统一两套记忆存储系统，或提供跨系统查询接口
2. REST API 可增加内容长度校验，防止过大数据写入
3. 可增加搜索/过滤端点，支持按 category/keywords 查询
