# Task 2: REST API 基础功能测试

## 测试时间
2026-04-26T05:14:55Z (UTC)

## 测试汇总
| # | API 端点 | HTTP 方法 | 状态码 | 耗时 | 结果 |
|---|---------|-----------|--------|------|------|
| 1 | /api/auth/status | GET | 200 | 0.023s | PASS |
| 2 | /api/models | GET | 200 | 0.010s | PASS |
| 3a | /api/sessions | POST | 201 | 0.022s | PASS |
| 3b | /api/sessions?limit=5 | GET | 200 | 0.015s | PASS |
| 3c | /api/sessions/{sessionId} | GET | 200 | 0.019s | PASS |
| 3d | /api/sessions/{sessionId}/messages?limit=10 | GET | 200 | 0.030s | PASS |
| 3e | /api/sessions/{sessionId} | DELETE | 200 | 0.008s | PASS |
| 4a | /api/config | GET | 200 | 0.031s | PASS |
| 4b | /api/config/project | GET | 200 | 0.009s | PASS |
| 5a | /api/permissions/rules?scope=all | GET | 200 | 0.007s | PASS |
| 5b | /api/permissions/rules | POST | 201 | 0.008s | PASS |
| 5c | /api/permissions/rules/{ruleId} | DELETE | 204 | 0.005s | PASS |
| 6a | /api/tools | GET | 200 | 0.044s | PASS |
| 6b | /api/tools/Read | GET | 200 | 0.006s | PASS |
| 6c | /api/tools/Bash | GET | 200 | 0.010s | PASS |
| 7a | /api/skills | GET | 200 | 0.006s | PASS |
| 7b | /api/skills/translate | GET | 200 | 0.004s | PASS |
| 8a | /api/memory | GET | 200 | 0.008s | PASS |
| 8b | /api/memory | POST | 201 | 0.009s | PASS |
| 8c | /api/memory (验证创建) | GET | 200 | 0.003s | PASS |
| 8d | /api/memory/{memoryId} | DELETE | 204 | 0.004s | PASS |
| 9a | /api/plugins | GET | 200 | 0.005s | PASS |
| 9b | /api/plugins/reload | POST | 200 | 0.008s | PASS |
| 10a | /api/mcp/capabilities | GET | 200 | 0.007s | PASS |
| 10b | /api/mcp/capabilities/{id} | GET | 200 | 0.006s | PASS |
| 11a | /api/attachments/upload | POST | 201 | 0.029s | PASS |
| 11b | /api/attachments/{fileUuid} | GET | 200 | 0.006s | PASS |
| 12a | /api/health | GET | 200 | 0.004s | PASS |
| 12b | /api/health/live | GET | 200 | 0.003s | PASS |
| 12c | /api/health/ready | GET | 200 | 0.002s | PASS |
| 12d | /api/doctor | GET | 200 | 0.032s | PASS |
| 13 | /api/remote/status | GET | 200 | 0.003s | PASS |

## 详细测试结果

### 2.1 认证 API
**请求**: `GET /api/auth/status`
**响应状态码**: 200
**响应体**:
```json
{"authenticated":true,"authMode":"localhost","username":"localhost-user"}
```
**判定**: PASS

### 2.2 模型 API
**请求**: `GET /api/models`
**响应状态码**: 200
**响应体**:
```json
{"models":[{"id":"qwen3.6-max-preview","displayName":"Qwen 3.6 Max Preview","maxOutputTokens":16384,"contextWindow":262144,"supportsStreaming":true,"supportsThinking":true,"supportsImages":false,"supportsToolUse":true,"costPer1kInput":0.009,"costPer1kOutput":0.054},{"id":"qwen3.6-plus","displayName":"Qwen 3.6 Plus","maxOutputTokens":8192,"contextWindow":1000000,"supportsStreaming":true,"supportsThinking":false,"supportsImages":true,"supportsToolUse":true,"costPer1kInput":8.0E-4,"costPer1kOutput":0.002},{"id":"deepseek-v4-pro","displayName":"DeepSeek V4 Pro","maxOutputTokens":384000,"contextWindow":1000000,"supportsStreaming":true,"supportsThinking":true,"supportsImages":false,"supportsToolUse":true,"costPer1kInput":0.001,"costPer1kOutput":0.004},{"id":"deepseek-v4-flash","displayName":"DeepSeek V4 Flash","maxOutputTokens":384000,"contextWindow":1000000,"supportsStreaming":true,"supportsThinking":true,"supportsImages":false,"supportsToolUse":true,"costPer1kInput":5.0E-4,"costPer1kOutput":0.002}],"defaultModel":"qwen3.6-max-preview"}
```
**判定**: PASS — 返回4个模型，默认模型为 qwen3.6-max-preview

### 2.3 会话 CRUD

#### 2.3.1 创建会话
**请求**: `POST /api/sessions`
**请求体**: `{"workingDirectory":"/Users/guoqingtao/Desktop/dev/code/zhikuncode","model":"qwen3.6-max-preview"}`
**响应状态码**: 201
**响应体**:
```json
{"sessionId":"ec86ee59-dcaf-49ad-a42b-5b98851548ec","webSocketUrl":"/ws/session/ec86ee59-dcaf-49ad-a42b-5b98851548ec","model":"qwen3.6-max-preview","createdAt":"2026-04-26T05:14:55.413151Z"}
```
**判定**: PASS — 返回 sessionId 和 WebSocket URL

#### 2.3.2 列出会话
**请求**: `GET /api/sessions?limit=5`
**响应状态码**: 200
**响应体**:
```json
{"sessions":[{"id":"ec86ee59-dcaf-49ad-a42b-5b98851548ec","model":"qwen3.6-max-preview","workingDirectory":"/Users/guoqingtao/Desktop/dev/code/zhikuncode","messageCount":0,"costUsd":0.0,"createdAt":"2026-04-26T05:14:55.402642Z","updatedAt":"2026-04-26T05:14:55.402642Z"},...],"hasMore":true,"nextCursor":"..."}
```
**判定**: PASS — 新创建的会话在列表首位，支持分页

#### 2.3.3 获取会话详情
**请求**: `GET /api/sessions/ec86ee59-dcaf-49ad-a42b-5b98851548ec`
**响应状态码**: 200
**响应体**:
```json
{"sessionId":"ec86ee59-dcaf-49ad-a42b-5b98851548ec","model":"qwen3.6-max-preview","workingDir":"/Users/guoqingtao/Desktop/dev/code/zhikuncode","status":"active","messages":[],"config":{},"totalUsage":{"inputTokens":0,"outputTokens":0,"cacheReadInputTokens":0,"cacheCreationInputTokens":0},"totalCostUsd":0.0,"createdAt":"2026-04-26T05:14:55.402642Z","updatedAt":"2026-04-26T05:14:55.402642Z"}
```
**判定**: PASS

#### 2.3.4 获取会话消息
**请求**: `GET /api/sessions/ec86ee59-dcaf-49ad-a42b-5b98851548ec/messages?limit=10`
**响应状态码**: 200
**响应体**:
```json
{"messages":[],"hasMore":false}
```
**判定**: PASS — 新会话无消息，返回空列表

#### 2.3.5 删除会话
**请求**: `DELETE /api/sessions/ec86ee59-dcaf-49ad-a42b-5b98851548ec`
**响应状态码**: 200
**响应体**:
```json
{"success":true}
```
**判定**: PASS

### 2.4 配置 API

#### 2.4.1 全局配置
**请求**: `GET /api/config`
**响应状态码**: 200
**响应体**:
```json
{"authType":"localhost","defaultModel":"qwen3.6-max-preview","modelAliases":{},"theme":"dark","locale":"en","defaultPermissionMode":"DEFAULT","globalAlwaysAllowRules":[],"globalAlwaysDenyRules":[],"mcpServers":{},"analyticsEnabled":false,"autoCompactEnabled":true,"autoCompactThreshold":80}
```
**判定**: PASS

#### 2.4.2 项目配置
**请求**: `GET /api/config/project`
**响应状态码**: 200
**响应体**:
```json
{"lastModel":"qwen3.6-plus","lastCost":0.05,"projectAlwaysAllowRules":[],"projectMcpServers":{},"customSettings":{}}
```
**判定**: PASS

### 2.5 权限规则 API

#### 2.5.1 获取权限规则
**请求**: `GET /api/permissions/rules?scope=all`
**响应状态码**: 200
**响应体**:
```json
{"rules":[]}
```
**判定**: PASS — 当前无规则

#### 2.5.2 创建权限规则
**请求**: `POST /api/permissions/rules`
**请求体**: `{"toolName":"Bash","ruleContent":"allow ls commands","decision":"allow","scope":"session"}`
**响应状态码**: 201
**响应体**:
```json
{"rule":{"toolName":"Bash","ruleContent":"allow ls commands","decision":"allow","scope":"session"},"success":true,"id":"869a757a-c6a1-4f40-8af6-6fa488f5d833"}
```
**判定**: PASS

#### 2.5.3 删除权限规则
**请求**: `DELETE /api/permissions/rules/869a757a-c6a1-4f40-8af6-6fa488f5d833`
**响应状态码**: 204
**响应体**: (空)
**判定**: PASS

### 2.6 工具 API

#### 2.6.1 列出所有工具
**请求**: `GET /api/tools`
**响应状态码**: 200
**响应体**: 返回包含 48 个工具的完整列表，包括 Bash、Read、Write、Edit、Agent、TaskCreate 等核心工具以及 MCP 工具
**判定**: PASS

#### 2.6.2 获取工具详情 - Read
**请求**: `GET /api/tools/Read`
**响应状态码**: 200
**响应体**:
```json
{"name":"Read","description":"Read the contents of a file. Supports text files with optional line range (offset/limit) and image files (returns base64).","category":"read","permissionLevel":"NONE","inputSchema":{"properties":{"limit":{"type":"integer","description":"Number of lines to read"},"offset":{"type":"integer","description":"Starting line number (0-based)"},"file_path":{"type":"string","description":"Absolute path to the file"}},"type":"object","required":["file_path"]}}
```
**判定**: PASS

#### 2.6.3 获取工具详情 - Bash
**请求**: `GET /api/tools/Bash`
**响应状态码**: 200
**响应体**:
```json
{"name":"Bash","description":"Execute a shell command. Use for running scripts, installing packages, compiling code, managing files, and performing system operations.","category":"bash","permissionLevel":"CONDITIONAL","inputSchema":{"properties":{"description":{"type":"string","description":"Description of what the command does"},"is_background":{"type":"boolean","description":"Run command in background, returning immediately with process ID"},"timeout":{"type":"integer","description":"Timeout in milliseconds (default 120000)"},"command":{"type":"string","description":"The shell command to execute"}},"type":"object","required":["command"]}}
```
**判定**: PASS

### 2.7 技能 API

#### 2.7.1 列出所有技能
**请求**: `GET /api/skills`
**响应状态码**: 200
**响应体**:
```json
[{"name":"pr","source":"BUNDLED","description":"准备一个结构良好的 Pull Request，包含描述、变更日志和审查说明。"},{"name":"fix","source":"BUNDLED","description":"根据错误信息或失败的测试，诊断并修复当前项目中的错误。"},{"name":"test","source":"BUNDLED","description":"为指定代码或近期变更生成或运行测试。"},{"name":"review","source":"BUNDLED","description":"审查当前未提交的变更，提供可操作的反馈。"},{"name":"commit","source":"BUNDLED","description":"分析暂存区的变更，创建结构良好的 git commit。"},{"name":"translate","source":"PROJECT","description":"将代码从一种编程语言翻译为另一种，保持原有逻辑、注释风格和代码结构。"}]
```
**判定**: PASS — 5个内置技能 + 1个项目技能

#### 2.7.2 获取技能详情
**请求**: `GET /api/skills/translate`
**响应状态码**: 200
**响应体**:
```json
{"name":"translate","description":"将代码从一种编程语言翻译为另一种，保持原有逻辑、注释风格和代码结构。","content":"# /translate — 代码翻译器\n\n将代码从一种编程语言翻译为另一种...","filePath":"/Users/guoqingtao/Desktop/dev/code/zhikuncode/.zhikun/skills/translate.md","source":"PROJECT"}
```
**判定**: PASS — 返回完整的技能定义内容和文件路径

### 2.8 记忆 API

#### 2.8.1 获取记忆列表
**请求**: `GET /api/memory`
**响应状态码**: 200
**响应体**:
```json
{"entries":[{"id":"d4e6a90f-2463-4163-b3c1-9323810c8d0c","category":"general","title":"test memory","content":"test content","keywords":"test","scope":"global","createdAt":"2026-04-13T16:06:36.885875Z","updatedAt":"2026-04-13T16:06:36.885875Z"}]}
```
**判定**: PASS

#### 2.8.2 创建记忆
**请求**: `POST /api/memory`
**请求体**: `{"category":"user_info","title":"测试记忆条目","content":"这是一个REST API测试创建的记忆条目","keywords":"test,api,memory","scope":"workspace"}`
**响应状态码**: 201
**响应体**:
```json
{"success":true,"id":"466643a2-ea4c-410d-aec1-a153cc0572ba"}
```
**判定**: PASS

#### 2.8.3 验证创建成功
**请求**: `GET /api/memory`
**响应状态码**: 200
**响应体**: 返回 2 条记忆，包含新创建的 "测试记忆条目"
**判定**: PASS — 新记忆条目已出现在列表中

#### 2.8.4 删除记忆
**请求**: `DELETE /api/memory/466643a2-ea4c-410d-aec1-a153cc0572ba`
**响应状态码**: 204
**响应体**: (空)
**判定**: PASS

### 2.9 插件 API

#### 2.9.1 列出插件
**请求**: `GET /api/plugins`
**响应状态码**: 200
**响应体**:
```json
{"plugins":[{"name":"hello","version":"1.0.0","description":"示例插件 — 验证插件系统端到端链路","enabled":true,"isBuiltin":true,"sourceType":"BUILTIN","commandCount":1,"toolCount":1,"hookCount":1}]}
```
**判定**: PASS — 1个内置示例插件

#### 2.9.2 重载插件
**请求**: `POST /api/plugins/reload`
**响应状态码**: 200
**响应体**:
```json
{"enabled":1,"loaded":1,"disabled":0}
```
**判定**: PASS

### 2.10 MCP API

#### 2.10.1 列出 MCP 能力
**请求**: `GET /api/mcp/capabilities`
**响应状态码**: 200
**响应体**: 返回 3 个 MCP 能力：万相2.5图像编辑、网络搜索Pro、万相2.5图像生成
```json
{"capabilities":[...],"enabledCount":3,"total":3}
```
**判定**: PASS

#### 2.10.2 获取 MCP 能力详情
**请求**: `GET /api/mcp/capabilities/mcp_web_search_pro`
**响应状态码**: 200
**响应体**: 返回网络搜索Pro的完整配置，包括 SSE URL、输入输出 schema、超时配置等 [截断，原文约 1200 字符]
**判定**: PASS

### 2.11 附件 API

#### 2.11.1 上传附件
**请求**: `POST /api/attachments/upload` (multipart/form-data, file=test-attachment.txt)
**响应状态码**: 201
**响应体**:
```json
{"fileUuid":"5238685f-b57c-466a-a8d7-9f29df3a80ba","fileName":"test-attachment.txt","size":24}
```
**判定**: PASS

#### 2.11.2 下载附件
**请求**: `GET /api/attachments/5238685f-b57c-466a-a8d7-9f29df3a80ba`
**响应状态码**: 200
**下载内容**: `test attachment content`
**判定**: PASS — 下载内容与上传内容一致

### 2.12 健康检查

#### 2.12.1 健康状态
**请求**: `GET /api/health`
**响应状态码**: 200
**响应体**:
```json
{"status":"UP","service":"ai-code-assistant-backend","version":"1.0.0","uptime":328,"java":"21.0.10","subsystems":{"database":{"status":"UP","message":"SQLite embedded database available"},"jvm":{"status":"UP","message":"Heap: 84MB/4096MB"}},"timestamp":"2026-04-26T05:17:01.913897Z"}
```
**判定**: PASS

#### 2.12.2 存活探针
**请求**: `GET /api/health/live`
**响应状态码**: 200
**响应体**: `OK`
**判定**: PASS

#### 2.12.3 就绪探针
**请求**: `GET /api/health/ready`
**响应状态码**: 200
**响应体**: `READY`
**判定**: PASS

#### 2.12.4 系统诊断
**请求**: `GET /api/doctor`
**响应状态码**: 200
**响应体**:
```json
{"checks":[{"name":"java","status":"ok","version":"21.0.10","message":"Java runtime available"},{"name":"git","status":"ok","version":"git version 2.50.1 (Apple Git-155)","message":"git available","latencyMs":21},{"name":"ripgrep","status":"warning","message":"ripgrep not found: Cannot run program \"rg\": Exec failed, error: 2 (No such file or directory) ","latencyMs":8},{"name":"jvm_memory","status":"ok","message":"Used 85MB / Max 4096MB"}]}
```
**判定**: PASS — 注意 ripgrep 状态为 warning（未安装），其余均 ok

### 2.13 远程控制 API
**请求**: `GET /api/remote/status`
**响应状态码**: 200
**响应体**:
```json
{"activeSessions":0,"sessions":[],"serverUptime":"5m"}
```
**判定**: PASS

## 测试结论
- 总计测试 **33 个端点**
- **PASS: 33 个**
- **FAIL: 0 个**
- 所有 REST API 端点均可达且返回预期响应

### 注意事项
1. **ripgrep 未安装**: `/api/doctor` 诊断显示 ripgrep (rg) 未找到，状态为 warning。这不影响 API 功能但可能影响搜索性能。
2. **所有 CRUD 操作验证完整**: 会话、权限规则、记忆均完成了 创建→查询→删除 的完整生命周期测试。
3. **附件上传下载一致性验证通过**: 上传内容与下载内容完全匹配。
