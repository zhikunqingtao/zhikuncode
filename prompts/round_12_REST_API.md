# Round 12: REST API

> 阶段: API 与通信层 | 依赖: R10, R11 | SPEC: §6.1

---

## 指令

请使用 `/implement-module` Skill，参照以下 SPEC 片段实现 **REST API** 模块。

### SPEC 片段文件

请读取以下文件作为实现参考：

- `spec_sections/ch06/s6.1_REST_API.md`

### 核心要求

1. Spring MVC @RestController
2. QueryController: POST /api/query + /stream + /conversation
3. SessionController: CRUD
4. ConfigController: 读写配置

### 已完成依赖

- R10: QueryEngine 核心循环
- R11: CommandRegistry 命令系统

### 技术约束

- Java 21+ / Virtual Threads
- Spring Boot 3.3+
- 包结构按 §2.8.1（参考 spec_sections/ch02/s2.4_Java_后端包结构.md）
- 前端: React 18 + TypeScript + Vite + Zustand
- Python: FastAPI + asyncio

### 验收标准

- [ ] 所有 REST 端点可通过 curl 测试
- [ ] QueryController 3 端点: /api/query, /api/query/stream, /api/query/conversation
- [ ] JSON 序列化/反序列化正确

---

## 完成后

执行 `/verify-module` 校验本轮实现与 SPEC 的一致性：

```
/verify-module
模块: REST API
SPEC 片段: ch06/s6.1_REST_API.md
章节: §6.1
```
