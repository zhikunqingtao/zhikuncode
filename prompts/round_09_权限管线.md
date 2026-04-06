# Round 9: 权限管线

> 阶段: P0 核心后端 | 依赖: R7, R8 | SPEC: §3.4

---

## 指令

请使用 `/implement-module` Skill，参照以下 SPEC 片段实现 **权限管线** 模块。

### SPEC 片段文件

请读取以下文件作为实现参考：

- `spec_sections/ch03/s3.4_权限系统.md`

### 核心要求

1. 7 阶段管线: 缓存→规则→分类→风险评估→用户确认→记录→执行
2. PermissionMode 枚举
3. BashTool 特殊处理: AST 安全分析
4. 权限规则持久化到 global.db

### 已完成依赖

- R7: Tool 基础框架
- R8: 10 个 P0 核心工具

### 技术约束

- Java 21+ / Virtual Threads
- Spring Boot 3.3+
- 包结构按 §2.8.1（参考 spec_sections/ch02/s2.4_Java_后端包结构.md）
- 前端: React 18 + TypeScript + Vite + Zustand
- Python: FastAPI + asyncio

### 验收标准

- [ ] PermissionPipeline 7 阶段完整
- [ ] PermissionMode: Auto/AllowEdits/Bypass/DontAsk 枚举
- [ ] 工具调用经过权限检查
- [ ] always_allow/always_deny 规则持久化

---

## 完成后

执行 `/verify-module` 校验本轮实现与 SPEC 的一致性：

```
/verify-module
模块: 权限管线
SPEC 片段: ch03/s3.4_权限系统.md
章节: §3.4
```
