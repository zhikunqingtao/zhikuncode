# Round 2: 数据模型

> 阶段: P0 核心后端 | 依赖: R1 | SPEC: §5.0-§5.6

---

## 指令

请使用 `/implement-module` Skill，参照以下 SPEC 片段实现 **数据模型** 模块。

### SPEC 片段文件

请读取以下文件作为实现参考：

- `spec_sections/ch05_数据模型.md`

### 核心要求

1. sealed interface Message 含 UserMessage/AssistantMessage/SystemMessage
2. 品牌化 ID: SessionId/MessageId/TaskId 不可互换
3. StoredCostState 成本追踪状态
4. ContentBlock 含 text/thinking/tool_use/tool_result/image/redacted_thinking

### 已完成依赖

- R1: 三项目初始化

### 技术约束

- Java 21+ / Virtual Threads
- Spring Boot 3.3+
- 包结构按 §2.8.1（参考 spec_sections/ch02/s2.4_Java_后端包结构.md）
- 前端: React 18 + TypeScript + Vite + Zustand
- Python: FastAPI + asyncio

### 验收标准

- [ ] 编译通过
- [ ] 品牌化 ID (SessionId/MessageId) 类型安全
- [ ] sealed interface Message 及所有子类型定义完整

---

## 完成后

执行 `/verify-module` 校验本轮实现与 SPEC 的一致性：

```
/verify-module
模块: 数据模型
SPEC 片段: ch05_数据模型.md
章节: §5.0-§5.6
```
