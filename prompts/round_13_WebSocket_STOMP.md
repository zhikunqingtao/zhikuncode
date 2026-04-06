# Round 13: WebSocket STOMP

> 阶段: API 与通信层 | 依赖: R12 | SPEC: §6.2, §8.5

---

## 指令

请使用 `/implement-module` Skill，参照以下 SPEC 片段实现 **WebSocket STOMP** 模块。

### SPEC 片段文件

请读取以下文件作为实现参考：

- `spec_sections/ch06/s6.2_WebSocket_API.md`
- `spec_sections/ch08/s8.5_WebSocket_消息协议.md`

### 核心要求

1. Spring WebSocket + STOMP MessageBroker
2. SockJS fallback
3. 25 种 ServerMessage: stream_delta/thinking_delta/tool_use_begin/tool_result/...
4. /topic/session/{sessionId} 订阅
5. /queue/messages 用户专属队列

### 已完成依赖

- R12: REST API

### 技术约束

- Java 21+ / Virtual Threads
- Spring Boot 3.3+
- 包结构按 §2.8.1（参考 spec_sections/ch02/s2.4_Java_后端包结构.md）
- 前端: React 18 + TypeScript + Vite + Zustand
- Python: FastAPI + asyncio

### 验收标准

- [ ] WebSocket 连接建立成功
- [ ] STOMP 订阅/发送工作
- [ ] 25 种消息类型正确序列化
- [ ] 断线重连可用

---

## 完成后

执行 `/verify-module` 校验本轮实现与 SPEC 的一致性：

```
/verify-module
模块: WebSocket STOMP
SPEC 片段: ch06/s6.2_WebSocket_API.md
章节: §6.2, §8.5
```
