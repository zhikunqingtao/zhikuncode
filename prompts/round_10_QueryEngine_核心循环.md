# Round 10: QueryEngine 核心循环

> 阶段: P0 核心后端 | 依赖: R6, R8, R9 | SPEC: §3.1

---

## 指令

请使用 `/implement-module` Skill，参照以下 SPEC 片段实现 **QueryEngine 核心循环** 模块。

### SPEC 片段文件

请读取以下文件作为实现参考：

- `spec_sections/ch03/s3.1_查询引擎_header.md`
- `spec_sections/ch03/s3.1.0_用户输入两阶段处理.md`
- `spec_sections/ch03/s3.1.1_核心流程.md`
- `spec_sections/ch03/s3.1.1a_查询主循环实现细节.md`
- `spec_sections/ch03/s3.1.5_CompactService_压缩算法.md`

### 核心要求

1. 8 步: 输入→系统提示→LLM调用→解析→工具调用→权限→执行→循环
2. Virtual Thread 执行
3. CompactService 压缩算法
4. 上下文窗口管理

### 已完成依赖

- R6: LLM Provider 抽象层
- R8: 10 个 P0 核心工具
- R9: 权限管线

### 技术约束

- Java 21+ / Virtual Threads
- Spring Boot 3.3+
- 包结构按 §2.8.1（参考 spec_sections/ch02/s2.4_Java_后端包结构.md）
- 前端: React 18 + TypeScript + Vite + Zustand
- Python: FastAPI + asyncio

### 验收标准

- [ ] QueryEngine 完整 8 步循环可运行
- [ ] 流式输出到 WebSocket
- [ ] 工具调用循环正常
- [ ] auto-compact 在 80% 上下文窗口触发

---

## 完成后

执行 `/verify-module` 校验本轮实现与 SPEC 的一致性：

```
/verify-module
模块: QueryEngine 核心循环
SPEC 片段: ch03/s3.1_查询引擎_header.md
章节: §3.1
```
