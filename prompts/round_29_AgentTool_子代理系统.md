# Round 29: AgentTool 子代理系统

> 阶段: P1 增强 | 依赖: R11 | SPEC: §4.1.1

---

## 指令

请使用 `/implement-module` Skill，参照以下 SPEC 片段实现 **AgentTool 子代理系统** 模块。

### SPEC 片段文件

请读取以下文件作为实现参考：

- `spec_sections/ch04/s4.1.1_AgentTool.md`

### 核心要求

1. 复用 QueryEngine 核心循环
2. Semaphore 并发控制

### 已完成依赖

- R11: CommandRegistry 命令系统

### 技术约束

- Java 21+ / Virtual Threads
- Spring Boot 3.3+
- 包结构按 §2.8.1（参考 spec_sections/ch02/s2.4_Java_后端包结构.md）
- 前端: React 18 + TypeScript + Vite + Zustand
- Python: FastAPI + asyncio

### 验收标准

- [ ] 子代理创建/执行/取消
- [ ] 嵌套≤3层/全局≤30/会话≤10

---

## 完成后

执行 `/verify-module` 校验本轮实现与 SPEC 的一致性：

```
/verify-module
模块: AgentTool 子代理系统
SPEC 片段: ch04/s4.1.1_AgentTool.md
章节: §4.1.1
```
