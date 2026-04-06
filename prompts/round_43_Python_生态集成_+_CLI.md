# Round 43: Python 生态集成 + CLI

> 阶段: P1 增强 | 依赖: R34 | SPEC: §4.14, §4.21

---

## 指令

请使用 `/implement-module` Skill，参照以下 SPEC 片段实现 **Python 生态集成 + CLI** 模块。

### SPEC 片段文件

请读取以下文件作为实现参考：

- `spec_sections/ch04/s4.14_Python_生态集成模块.md`
- `spec_sections/ch04/s4.21_CLI_接口层.md`

### 核心要求

1. CODE_INTEL + FILE_PROCESSING 两域
2. Typer + httpx + Rich CLI

### 已完成依赖

- R34: LSPTool

### 技术约束

- Java 21+ / Virtual Threads
- Spring Boot 3.3+
- 包结构按 §2.8.1（参考 spec_sections/ch02/s2.4_Java_后端包结构.md）
- 前端: React 18 + TypeScript + Vite + Zustand
- Python: FastAPI + asyncio

### 验收标准

- [ ] Python 端点可用
- [ ] aica CLI 可执行

---

## 完成后

执行 `/verify-module` 校验本轮实现与 SPEC 的一致性：

```
/verify-module
模块: Python 生态集成 + CLI
SPEC 片段: ch04/s4.14_Python_生态集成模块.md
章节: §4.14, §4.21
```
