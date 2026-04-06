# Round 5: SessionManager 会话管理

> 阶段: P0 核心后端 | 依赖: R3, R4 | SPEC: §3.6

---

## 指令

请使用 `/implement-module` Skill，参照以下 SPEC 片段实现 **SessionManager 会话管理** 模块。

### SPEC 片段文件

请读取以下文件作为实现参考：

- `spec_sections/ch03/s3.6_会话持久化.md`

### 核心要求

1. SessionManager 服务类
2. 会话持久化到 data.db
3. 消息序列化/反序列化
4. 并发安全

### 已完成依赖

- R3: 数据库层
- R4: AppState 状态管理

### 技术约束

- Java 21+ / Virtual Threads
- Spring Boot 3.3+
- 包结构按 §2.8.1（参考 spec_sections/ch02/s2.4_Java_后端包结构.md）
- 前端: React 18 + TypeScript + Vite + Zustand
- Python: FastAPI + asyncio

### 验收标准

- [ ] 会话创建/恢复/列表/删除 CRUD 完整
- [ ] SQLite WAL 模式下并发无锁死
- [ ] 单元测试通过

---

## 完成后

执行 `/verify-module` 校验本轮实现与 SPEC 的一致性：

```
/verify-module
模块: SessionManager 会话管理
SPEC 片段: ch03/s3.6_会话持久化.md
章节: §3.6
```
