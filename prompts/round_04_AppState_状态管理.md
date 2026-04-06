# Round 4: AppState 状态管理

> 阶段: P0 核心后端 | 依赖: R2 | SPEC: §3.5

---

## 指令

请使用 `/implement-module` Skill，参照以下 SPEC 片段实现 **AppState 状态管理** 模块。

### SPEC 片段文件

请读取以下文件作为实现参考：

- `spec_sections/ch03/s3.5_状态管理.md`

### 核心要求

1. Java record 不可变设计
2. 6 子组: session/config/permission/ui/cost/feature
3. 状态变更通过 withXxx() 方法返回新实例
4. 线程安全

### 已完成依赖

- R2: 数据模型

### 技术约束

- Java 21+ / Virtual Threads
- Spring Boot 3.3+
- 包结构按 §2.8.1（参考 spec_sections/ch02/s2.4_Java_后端包结构.md）
- 前端: React 18 + TypeScript + Vite + Zustand
- Python: FastAPI + asyncio

### 验收标准

- [ ] 编译通过
- [ ] AppState record 包含所有 6 子组
- [ ] 状态不可变性验证

---

## 完成后

执行 `/verify-module` 校验本轮实现与 SPEC 的一致性：

```
/verify-module
模块: AppState 状态管理
SPEC 片段: ch03/s3.5_状态管理.md
章节: §3.5
```
