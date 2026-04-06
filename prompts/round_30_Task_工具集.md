# Round 30: Task 工具集

> 阶段: P1 增强 | 依赖: R29 | SPEC: §4.1.3, §4.1.7, §4.1.15

---

## 指令

请使用 `/implement-module` Skill，参照以下 SPEC 片段实现 **Task 工具集** 模块。

### SPEC 片段文件

请读取以下文件作为实现参考：

- `spec_sections/ch04/s4.1.3_TaskCreateTool_TaskUpdateTool_TaskListTool_TaskGetTool.md`
- `spec_sections/ch04/s4.1.7_TaskStopTool.md`
- `spec_sections/ch04/s4.1.15_TaskOutputTool.md`

### 核心要求

1. TaskCoordinator 服务类
2. 三层中断传播
3. 超时看门狗

### 已完成依赖

- R29: AgentTool 子代理系统

### 技术约束

- Java 21+ / Virtual Threads
- Spring Boot 3.3+
- 包结构按 §2.8.1（参考 spec_sections/ch02/s2.4_Java_后端包结构.md）
- 前端: React 18 + TypeScript + Vite + Zustand
- Python: FastAPI + asyncio

### 验收标准

- [ ] TaskCoordinator 提交/取消/查询
- [ ] 6 个 Task 工具可用

---

## 完成后

执行 `/verify-module` 校验本轮实现与 SPEC 的一致性：

```
/verify-module
模块: Task 工具集
SPEC 片段: ch04/s4.1.3_TaskCreateTool_TaskUpdateTool_TaskListTool_TaskGetTool.md
章节: §4.1.3, §4.1.7, §4.1.15
```
