# Round 31: 用户交互工具

> 阶段: P1 增强 | 依赖: R11 | SPEC: §4.1.2, §4.1.6, §4.1.12, §4.1.13

---

## 指令

请使用 `/implement-module` Skill，参照以下 SPEC 片段实现 **用户交互工具** 模块。

### SPEC 片段文件

请读取以下文件作为实现参考：

- `spec_sections/ch04/s4.1.6_AskUserQuestionTool.md`
- `spec_sections/ch04/s4.1.2_TodoWriteTool.md`
- `spec_sections/ch04/s4.1.12_SleepTool.md`
- `spec_sections/ch04/s4.1.13_BriefTool.md`

### 核心要求

1. AskUserQuestionTool: WebSocket 推送等待用户响应
2. TodoWriteTool: CRUD todo

### 已完成依赖

- R11: CommandRegistry 命令系统

### 技术约束

- Java 21+ / Virtual Threads
- Spring Boot 3.3+
- 包结构按 §2.8.1（参考 spec_sections/ch02/s2.4_Java_后端包结构.md）
- 前端: React 18 + TypeScript + Vite + Zustand
- Python: FastAPI + asyncio

### 验收标准

- [ ] 4 个工具编译通过 + 基础测试

---

## 完成后

执行 `/verify-module` 校验本轮实现与 SPEC 的一致性：

```
/verify-module
模块: 用户交互工具
SPEC 片段: ch04/s4.1.6_AskUserQuestionTool.md
章节: §4.1.2, §4.1.6, §4.1.12, §4.1.13
```
