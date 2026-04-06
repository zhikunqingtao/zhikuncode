# Round 15: 集成测试

> 阶段: API 与通信层 | 依赖: R12, R13, R14 | SPEC: 无特定章节

---

## 指令

请使用 `/implement-module` Skill，参照以下 SPEC 片段实现 **集成测试** 模块。

### SPEC 片段文件

请读取以下文件作为实现参考：

- (无特定片段，参照已完成模块代码)

### 核心要求

1. 前端→WebSocket→QueryEngine→LLM→流式返回
2. 工具调用→权限→执行→结果返回
3. 错误场景: LLM 超时/工具失败

### 已完成依赖

- R12: REST API
- R13: WebSocket STOMP
- R14: 安全认证

### 技术约束

- Java 21+ / Virtual Threads
- Spring Boot 3.3+
- 包结构按 §2.8.1（参考 spec_sections/ch02/s2.4_Java_后端包结构.md）
- 前端: React 18 + TypeScript + Vite + Zustand
- Python: FastAPI + asyncio

### 验收标准

- [ ] 端到端消息流通
- [ ] 工具调用循环完整
- [ ] 权限确认流程正常
- [ ] 流式输出无丢失

---

## 完成后

执行 `/verify-module` 校验本轮实现与 SPEC 的一致性：

```
/verify-module
模块: 集成测试
SPEC 片段: N/A
章节: 无特定章节
```
