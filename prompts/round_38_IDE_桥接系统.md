# Round 38: IDE 桥接系统

> 阶段: P1 增强 | 依赖: R13 | SPEC: §4.5

---

## 指令

请使用 `/implement-module` Skill，参照以下 SPEC 片段实现 **IDE 桥接系统** 模块。

### SPEC 片段文件

请读取以下文件作为实现参考：

- `spec_sections/ch04/s4.5_IDE_桥接系统.md`

### 核心要求

1. BridgeServer 独立端口
2. IDE↔Web 双向通信

### 已完成依赖

- R13: WebSocket STOMP

### 技术约束

- Java 21+ / Virtual Threads
- Spring Boot 3.3+
- 包结构按 §2.8.1（参考 spec_sections/ch02/s2.4_Java_后端包结构.md）
- 前端: React 18 + TypeScript + Vite + Zustand
- Python: FastAPI + asyncio

### 验收标准

- [ ] 桥接 WebSocket 可连接
- [ ] IDE 消息转发

---

## 完成后

执行 `/verify-module` 校验本轮实现与 SPEC 的一致性：

```
/verify-module
模块: IDE 桥接系统
SPEC 片段: ch04/s4.5_IDE_桥接系统.md
章节: §4.5
```
