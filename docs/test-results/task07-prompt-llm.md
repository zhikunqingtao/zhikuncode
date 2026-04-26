# Task 7: System Prompt 与 LLM 集成测试

## 测试时间
2026-04-26 13:45 CST

## 测试环境
- Backend: http://localhost:8080
- 默认模型: qwen3.6-max-preview
- 工作目录: /Users/guoqingtao/Desktop/dev/code/zhikuncode

## 测试汇总
| # | 测试用例 | 结果 | 备注 |
|---|---------|------|------|
| TC-SP-01 | 模型列表与能力字段 | PASS | 4个模型，字段完整 |
| TC-SP-02 | 模型能力详细验证 | PASS | 默认模型各项能力正常 |
| TC-SP-03 | System Prompt 影响行为 | PASS | systemPrompt 成功改变行为 |
| TC-SP-04 | appendSystemPrompt 追加系统提示 | PASS | 追加指令生效，回复语言改变 |
| TC-SP-05 | LLM 流式响应完整性 | PASS | SSE 事件完整 |
| TC-SP-06 | LLM 错误处理 — 无效模型 | PASS | 错误信息清晰，未崩溃 |
| TC-SP-07 | Token 用量跟踪准确性 | PASS | 长短问题 token 差异合理 |

**总计: 7/7 PASS**

---

## 详细测试结果

### TC-SP-01: 模型列表与能力字段
**请求**: `GET /api/models`
**响应**:
```json
{
    "models": [
        {
            "id": "qwen3.6-max-preview",
            "displayName": "Qwen 3.6 Max Preview",
            "maxOutputTokens": 16384,
            "contextWindow": 262144,
            "supportsStreaming": true,
            "supportsThinking": true,
            "supportsImages": false,
            "supportsToolUse": true,
            "costPer1kInput": 0.009,
            "costPer1kOutput": 0.054
        },
        {
            "id": "qwen3.6-plus",
            "displayName": "Qwen 3.6 Plus",
            "maxOutputTokens": 8192,
            "contextWindow": 1000000,
            "supportsStreaming": true,
            "supportsThinking": false,
            "supportsImages": true,
            "supportsToolUse": true,
            "costPer1kInput": 0.0008,
            "costPer1kOutput": 0.002
        },
        {
            "id": "deepseek-v4-pro",
            "displayName": "DeepSeek V4 Pro",
            "maxOutputTokens": 384000,
            "contextWindow": 1000000,
            "supportsStreaming": true,
            "supportsThinking": true,
            "supportsImages": false,
            "supportsToolUse": true,
            "costPer1kInput": 0.001,
            "costPer1kOutput": 0.004
        },
        {
            "id": "deepseek-v4-flash",
            "displayName": "DeepSeek V4 Flash",
            "maxOutputTokens": 384000,
            "contextWindow": 1000000,
            "supportsStreaming": true,
            "supportsThinking": true,
            "supportsImages": false,
            "supportsToolUse": true,
            "costPer1kInput": 0.0005,
            "costPer1kOutput": 0.002
        }
    ],
    "defaultModel": "qwen3.6-max-preview"
}
```
**验证**:
- 模型数量: 4 个 ✓
- 默认模型: qwen3.6-max-preview ✓
- 能力字段完整性: 每个模型包含 id, displayName, maxOutputTokens, contextWindow, supportsStreaming, supportsThinking, supportsImages, supportsToolUse, costPer1kInput, costPer1kOutput ✓
**判定**: PASS

---

### TC-SP-02: 模型能力详细验证
**验证对象**: 默认模型 qwen3.6-max-preview
**验证**:
- maxOutputTokens = 16384 > 0 ✓
- contextWindow = 262144 > 0 ✓
- supportsStreaming = true ✓
- supportsToolUse = true ✓
- supportsThinking = true ✓
**判定**: PASS

---

### TC-SP-03: System Prompt 影响行为
**请求1（无 systemPrompt）**:
```json
{"prompt":"你是谁？","permissionMode":"BYPASS_PERMISSIONS","workingDirectory":"/Users/guoqingtao/Desktop/dev/code/zhikuncode"}
```
**响应1 result**:
> 我是 ZhikunCode 的 AI 编码助手。我可以帮助你编写、修改、调试代码，搜索代码库，运行测试，以及处理各种软件开发任务。有什么我可以帮你的吗？

**请求2（有 systemPrompt）**:
```json
{"prompt":"你是谁？","systemPrompt":"你是一个名叫小明的数学老师，所有回答都要以小明老师说：开头","permissionMode":"BYPASS_PERMISSIONS","workingDirectory":"/Users/guoqingtao/Desktop/dev/code/zhikuncode"}
```
**响应2 result**:
> 小明老师说：我是小明，一名数学老师。如果你有任何数学问题、需要讲解知识点，或者想一起探讨解题思路，随时告诉我，我很乐意为你答疑解惑！

**对比分析**:
- 无 systemPrompt: 回复以 ZhikunCode AI 编码助手身份应答
- 有 systemPrompt: 回复以"小明老师说："开头，完全遵循指定角色
- systemPrompt 确实改变了行为 ✓
**判定**: PASS

---

### TC-SP-04: appendSystemPrompt 追加系统提示
**请求**:
```json
{"prompt":"请告诉我今天星期几","appendSystemPrompt":"所有回答必须用英文回复，不要使用中文","permissionMode":"BYPASS_PERMISSIONS","workingDirectory":"/Users/guoqingtao/Desktop/dev/code/zhikuncode"}
```
**响应 result**:
> Today is **Sunday**.

**验证**:
- 回复为纯英文 ✓
- 模型调用了 Bash 工具获取日期信息，回答正确（Sunday）✓
- appendSystemPrompt 追加生效 ✓
**判定**: PASS

---

### TC-SP-05: LLM 流式响应完整性
**请求**: `POST /api/query/stream`
```json
{"prompt":"请列出5种常见的编程语言，每种一行","permissionMode":"BYPASS_PERMISSIONS","workingDirectory":"/Users/guoqingtao/Desktop/dev/code/zhikuncode"}
```
**SSE 事件序列**:
```
event:turn_start        → {"turn":1}
event:thinking_delta    → 多个 thinking 片段
event:text_delta        → "1. Python\n2. Java\n3. JavaScript\n4. C++\n5. Go"
event:assistant_message → {"uuid":"...","stop_reason":"end_turn"}
event:usage             → {"input_tokens":26397,"output_tokens":47}
event:turn_end          → {"stop_reason":"end_turn","turn":1}
event:message_complete  → {"sessionId":"...","usage":{...},"stopReason":"end_turn"}
```
**验证**:
- 收到多个 SSE event ✓
- 包含 text_delta 事件 ✓
- 包含 thinking_delta 事件 ✓
- 最终有 message_complete 事件 ✓
- 流式内容可组合为完整回复（5种编程语言）✓
**判定**: PASS

---

### TC-SP-06: LLM 错误处理 — 无效模型
**请求**:
```json
{"prompt":"test","model":"completely-invalid-model-name-xyz","permissionMode":"BYPASS_PERMISSIONS","workingDirectory":"/Users/guoqingtao/Desktop/dev/code/zhikuncode"}
```
**响应**:
```json
{
    "sessionId": "b8acd667-8cca-4939-911f-75de97667111",
    "result": "",
    "usage": {"inputTokens":0,"outputTokens":0,"cacheReadInputTokens":0,"cacheCreationInputTokens":0},
    "costUsd": 0.0,
    "toolCalls": [],
    "stopReason": "error",
    "error": "No provider found for model: completely-invalid-model-name-xyz"
}
```
**HTTP 状态码**: 200
**验证**:
- 返回有意义的错误信息: "No provider found for model: completely-invalid-model-name-xyz" ✓
- stopReason 正确标记为 "error" ✓
- 未发生 500 内部错误崩溃 ✓
- **观察项**: HTTP 状态码为 200 而非 400/404，建议改为语义化的 HTTP 错误状态码
**判定**: PASS（附观察项）

---

### TC-SP-07: Token 用量跟踪准确性
**短问题请求**:
```json
{"prompt":"说一个字：好","permissionMode":"BYPASS_PERMISSIONS","workingDirectory":"/Users/guoqingtao/Desktop/dev/code/zhikuncode"}
```
**短问题响应**:
- result: "好"
- usage: inputTokens=26392, outputTokens=23

**长问题请求**:
```json
{"prompt":"请详细解释什么是面向对象编程的四大特性（封装、继承、多态、抽象），每个特性用100字左右描述，并给出一个Java代码示例","permissionMode":"BYPASS_PERMISSIONS","workingDirectory":"/Users/guoqingtao/Desktop/dev/code/zhikuncode"}
```
**长问题响应**:
- result: 包含四大特性详细说明及完整 Java 代码示例（约 700+ tokens 输出）
- usage: inputTokens=26423, outputTokens=704

**验证**:
- 短问题 usage 字段存在且有效 ✓
- 长问题 usage 字段存在且有效 ✓
- inputTokens > 0（两者均 > 26000）✓
- outputTokens > 0（两者均 > 0）✓
- 长问题 outputTokens(704) > 短问题 outputTokens(23) ✓

## Token 用量对比数据
| 查询 | inputTokens | outputTokens | 总计 |
|------|------------|-------------|------|
| 短问题（"说一个字：好"） | 26,392 | 23 | 26,415 |
| 长问题（OOP四大特性） | 26,423 | 704 | 27,127 |

**判定**: PASS

---

## 测试结论

全部 7 个测试用例通过（7/7 PASS），System Prompt 与 LLM 集成功能运行正常：

1. **模型管理**: 4 个模型配置正确，能力字段完整，默认模型 qwen3.6-max-preview 标识正确
2. **System Prompt**: systemPrompt 参数有效改变 LLM 行为，角色扮演功能正常
3. **appendSystemPrompt**: 追加系统提示生效，可控制回复语言等行为
4. **流式响应**: SSE 事件序列完整（turn_start → thinking_delta → text_delta → message_complete），流式传输可靠
5. **错误处理**: 无效模型返回清晰错误信息，未导致系统崩溃（建议优化 HTTP 状态码）
6. **Token 跟踪**: 用量统计准确，长短问题 token 差异符合预期
