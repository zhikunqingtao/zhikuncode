# Round 14: 安全认证

> 阶段: API 与通信层 | 依赖: R12 | SPEC: §9.1-§9.7

---

## 指令

请使用 `/implement-module` Skill，参照以下 SPEC 片段实现 **安全认证** 模块。

### SPEC 片段文件

请读取以下文件作为实现参考：

- `spec_sections/ch09_安全设计.md`

### 核心要求

1. 三层递进: localhost→Token→JWT
2. Spring Security FilterChain
3. P0: localhost 免认证即可
4. 统一错误码目录 §9.7

### 已完成依赖

- R12: REST API

### 技术约束

- Java 21+ / Virtual Threads
- Spring Boot 3.3+
- 包结构按 §2.8.1（参考 spec_sections/ch02/s2.4_Java_后端包结构.md）
- 前端: React 18 + TypeScript + Vite + Zustand
- Python: FastAPI + asyncio

### 验收标准

- [ ] localhost 免认证通过
- [ ] API Key Token 认证可用
- [ ] 无效 Key 返回 401
- [ ] CORS 配置正确

---

## 完成后

执行 `/verify-module` 校验本轮实现与 SPEC 的一致性：

```
/verify-module
模块: 安全认证
SPEC 片段: ch09_安全设计.md
章节: §9.1-§9.7
```
