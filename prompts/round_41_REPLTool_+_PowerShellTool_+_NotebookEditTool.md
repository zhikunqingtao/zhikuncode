# Round 41: REPLTool + PowerShellTool + NotebookEditTool

> 阶段: P1 增强 | 依赖: R11 | SPEC: §4.1.5, §4.1.10, §4.1.16

---

## 指令

请使用 `/implement-module` Skill，参照以下 SPEC 片段实现 **REPLTool + PowerShellTool + NotebookEditTool** 模块。

### SPEC 片段文件

请读取以下文件作为实现参考：

- `spec_sections/ch04/s4.1.16_REPLTool.md`
- `spec_sections/ch04/s4.1.10_PowerShellTool.md`
- `spec_sections/ch04/s4.1.5_NotebookEditTool.md`

### 核心要求

1. pty4j 伪终端
2. ProcessBuilder 降级

### 已完成依赖

- R11: CommandRegistry 命令系统

### 技术约束

- Java 21+ / Virtual Threads
- Spring Boot 3.3+
- 包结构按 §2.8.1（参考 spec_sections/ch02/s2.4_Java_后端包结构.md）
- 前端: React 18 + TypeScript + Vite + Zustand
- Python: FastAPI + asyncio

### 验收标准

- [ ] REPLTool pty4j 交互
- [ ] PowerShellTool Windows 支持

---

## 完成后

执行 `/verify-module` 校验本轮实现与 SPEC 的一致性：

```
/verify-module
模块: REPLTool + PowerShellTool + NotebookEditTool
SPEC 片段: ch04/s4.1.16_REPLTool.md
章节: §4.1.5, §4.1.10, §4.1.16
```
