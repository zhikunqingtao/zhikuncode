# Round 17: STOMP 客户端 + dispatch

> 阶段: P0 前端 | 依赖: R16 | SPEC: §8.5.3

---

## 指令

请使用 `/implement-module` Skill，参照以下 SPEC 片段实现 **STOMP 客户端 + dispatch** 模块。

### SPEC 片段文件

请读取以下文件作为实现参考：

- `spec_sections/ch08/s8.5_WebSocket_消息协议.md`

### 核心要求

1. @stomp/stompjs 客户端
2. dispatch 函数: 按 type 字段分发到对应 Store
3. SockJS fallback
4. 心跳 + 重连策略

### 已完成依赖

- R16: TypeScript 类型 + Zustand Store

### 技术约束

- Java 21+ / Virtual Threads
- Spring Boot 3.3+
- 包结构按 §2.8.1（参考 spec_sections/ch02/s2.4_Java_后端包结构.md）
- 前端: React 18 + TypeScript + Vite + Zustand
- Python: FastAPI + asyncio

### 验收标准

- [ ] STOMP 客户端连接后端成功
- [ ] 25 种消息类型正确 dispatch
- [ ] 断线自动重连
- [ ] 消息序列号校验

---

## 完成后

执行 `/verify-module` 校验本轮实现与 SPEC 的一致性：

```
/verify-module
模块: STOMP 客户端 + dispatch
SPEC 片段: ch08/s8.5_WebSocket_消息协议.md
章节: §8.5.3
```
