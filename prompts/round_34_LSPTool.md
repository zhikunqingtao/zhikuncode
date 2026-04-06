# Round 34: LSPTool

> 阶段: P1 增强 | 依赖: R33 | SPEC: §4.1.4

---

## 指令

请使用 `/implement-module` Skill，参照以下 SPEC 片段实现 **LSPTool** 模块。

### SPEC 片段文件

请读取以下文件作为实现参考：

- `spec_sections/ch04/s4.1.4_LSPTool.md`

### 核心要求

1. 通过 Python 服务通信
2. goto-definition/references/completion

### 已完成依赖

- R33: MCP 集成

### 技术约束

- Java 21+ / Virtual Threads
- Spring Boot 3.3+
- 包结构按 §2.8.1（参考 spec_sections/ch02/s2.4_Java_后端包结构.md）
- 前端: React 18 + TypeScript + Vite + Zustand
- Python: FastAPI + asyncio

### 验收标准

- [ ] LSPTool 通过 Python pygls 提供补全/跳转

---

## 完成后

执行 `/verify-module` 校验本轮实现与 SPEC 的一致性：

```
/verify-module
模块: LSPTool
SPEC 片段: ch04/s4.1.4_LSPTool.md
章节: §4.1.4
```
