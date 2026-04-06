# Round 16: TypeScript 类型 + Zustand Store

> 阶段: P0 前端 | 依赖: R13 | SPEC: §8.3

---

## 指令

请使用 `/implement-module` Skill，参照以下 SPEC 片段实现 **TypeScript 类型 + Zustand Store** 模块。

### SPEC 片段文件

请读取以下文件作为实现参考：

- `spec_sections/ch08/s8.3_前端状态管理__—_权威定义.md`

### 核心要求

1. 11 个 Store: messageStore/sessionStore/configStore/permissionStore/costStore/appUiStore/commandStore/dialogStore/toolStore/taskStore/mcpStore
2. Zustand create() + persist middleware
3. ServerMessage 25 种类型的 TS interface

### 已完成依赖

- R13: WebSocket STOMP

### 技术约束

- Java 21+ / Virtual Threads
- Spring Boot 3.3+
- 包结构按 §2.8.1（参考 spec_sections/ch02/s2.4_Java_后端包结构.md）
- 前端: React 18 + TypeScript + Vite + Zustand
- Python: FastAPI + asyncio

### 验收标准

- [ ] TypeScript 编译通过
- [ ] 11 个 Store 创建函数定义完整
- [ ] Store 初始值和 persist 配置正确

---

## 完成后

执行 `/verify-module` 校验本轮实现与 SPEC 的一致性：

```
/verify-module
模块: TypeScript 类型 + Zustand Store
SPEC 片段: ch08/s8.3_前端状态管理__—_权威定义.md
章节: §8.3
```
