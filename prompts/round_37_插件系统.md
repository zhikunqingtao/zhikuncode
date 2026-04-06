# Round 37: 插件系统

> 阶段: P1 增强 | 依赖: R35 | SPEC: §4.6

---

## 指令

请使用 `/implement-module` Skill，参照以下 SPEC 片段实现 **插件系统** 模块。

### SPEC 片段文件

请读取以下文件作为实现参考：

- `spec_sections/ch04/s4.6_插件系统.md`

### 核心要求

1. 插件注册/发现/执行
2. 安全沙箱

### 已完成依赖

- R35: 增强命令 (59 个)

### 技术约束

- Java 21+ / Virtual Threads
- Spring Boot 3.3+
- 包结构按 §2.8.1（参考 spec_sections/ch02/s2.4_Java_后端包结构.md）
- 前端: React 18 + TypeScript + Vite + Zustand
- Python: FastAPI + asyncio

### 验收标准

- [ ] 插件生命周期管理
- [ ] 插件 API 可用

---

## 完成后

执行 `/verify-module` 校验本轮实现与 SPEC 的一致性：

```
/verify-module
模块: 插件系统
SPEC 片段: ch04/s4.6_插件系统.md
章节: §4.6
```
