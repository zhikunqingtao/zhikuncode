# Round 3: 数据库层

> 阶段: P0 核心后端 | 依赖: R2 | SPEC: §7.1-§7.3

---

## 指令

请使用 `/implement-module` Skill，参照以下 SPEC 片段实现 **数据库层** 模块。

### SPEC 片段文件

请读取以下文件作为实现参考：

- `spec_sections/ch07_数据库设计.md`

### 核心要求

1. global.db: 全局配置/API Key/权限规则
2. data.db: 会话/消息/记忆/成本
3. WAL 模式 + 连接池配置
4. JdbcTemplate 访问

### 已完成依赖

- R2: 数据模型

### 技术约束

- Java 21+ / Virtual Threads
- Spring Boot 3.3+
- 包结构按 §2.8.1（参考 spec_sections/ch02/s2.4_Java_后端包结构.md）
- 前端: React 18 + TypeScript + Vite + Zustand
- Python: FastAPI + asyncio

### 验收标准

- [ ] SQLite 双库创建成功
- [ ] WAL 模式启用
- [ ] Migration 脚本可执行
- [ ] CRUD 操作通过单元测试

---

## 完成后

执行 `/verify-module` 校验本轮实现与 SPEC 的一致性：

```
/verify-module
模块: 数据库层
SPEC 片段: ch07_数据库设计.md
章节: §7.1-§7.3
```
