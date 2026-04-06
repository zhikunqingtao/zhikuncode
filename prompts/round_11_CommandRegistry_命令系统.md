# Round 11: CommandRegistry 命令系统

> 阶段: P0 核心后端 | 依赖: R10 | SPEC: §3.3

---

## 指令

请使用 `/implement-module` Skill，参照以下 SPEC 片段实现 **CommandRegistry 命令系统** 模块。

### SPEC 片段文件

请读取以下文件作为实现参考：

- `spec_sections/ch03/s3.3_命令系统.md`

### 核心要求

1. Command 接口 + CommandRegistry
2. Spring @Component 自动发现
3. 12 个 P0 命令: help/compact/clear/config/model/cost/memory/login/logout/permissions/doctor/init

### 已完成依赖

- R10: QueryEngine 核心循环

### 技术约束

- Java 21+ / Virtual Threads
- Spring Boot 3.3+
- 包结构按 §2.8.1（参考 spec_sections/ch02/s2.4_Java_后端包结构.md）
- 前端: React 18 + TypeScript + Vite + Zustand
- Python: FastAPI + asyncio

### 验收标准

- [ ] CommandRegistry Bean 注册工作
- [ ] 12 个 P0 命令可执行
- [ ] /help /compact /clear /config 等基础命令可用

---

## 完成后

执行 `/verify-module` 校验本轮实现与 SPEC 的一致性：

```
/verify-module
模块: CommandRegistry 命令系统
SPEC 片段: ch03/s3.3_命令系统.md
章节: §3.3
```
