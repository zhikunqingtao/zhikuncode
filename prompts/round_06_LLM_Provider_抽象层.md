# Round 6: LLM Provider 抽象层

> 阶段: P0 核心后端 | 依赖: R1 | SPEC: §3.1.1-§3.1.3.1

---

## 指令

请使用 `/implement-module` Skill，参照以下 SPEC 片段实现 **LLM Provider 抽象层** 模块。

### SPEC 片段文件

请读取以下文件作为实现参考：

- `spec_sections/ch03/s3.1.1_核心流程.md`
- `spec_sections/ch03/s3.1.1a_查询主循环实现细节.md`
- `spec_sections/ch03/s3.1.1b_withRetry_重试机制.md`
- `spec_sections/ch03/s3.1.2_Java_接口定义.md`
- `spec_sections/ch03/s3.1.3_流式处理.md`
- `spec_sections/ch03/s3.1.3.1_OpenAI_兼容供应商实现.md`

### 核心要求

1. LlmProvider 接口: sendMessage() 返回 Flux<SseEvent>
2. OkHttp EventSource SSE 流式
3. OpenAI chat/completions 协议
4. 速率限制: 429 退避 + x-ratelimit-* 头解析
5. 重试: 指数退避 + jitter

### 已完成依赖

- R1: 三项目初始化

### 技术约束

- Java 21+ / Virtual Threads
- Spring Boot 3.3+
- 包结构按 §2.8.1（参考 spec_sections/ch02/s2.4_Java_后端包结构.md）
- 前端: React 18 + TypeScript + Vite + Zustand
- Python: FastAPI + asyncio

### 验收标准

- [ ] LlmProvider 接口定义完整
- [ ] OpenAI 兼容 Provider 可连接并流式返回
- [ ] SSE 解析正确处理 data/event/id 字段
- [ ] 速率限制头 (x-ratelimit-*) 解析
- [ ] withRetry 重试机制可用

---

## 完成后

执行 `/verify-module` 校验本轮实现与 SPEC 的一致性：

```
/verify-module
模块: LLM Provider 抽象层
SPEC 片段: ch03/s3.1.1_核心流程.md
章节: §3.1.1-§3.1.3.1
```
