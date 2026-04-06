# Round 39: 权限系统增强

> 阶段: P1 增强 | 依赖: R9 | SPEC: §4.9

---

## 指令

请使用 `/implement-module` Skill，参照以下 SPEC 片段实现 **权限系统增强** 模块。

### SPEC 片段文件

请读取以下文件作为实现参考：

- `spec_sections/ch04/s4.9_权限系统增强.md`

### 核心要求

1. resolveClassifierModel 四级回退
2. 5 个 Few-Shot 示例

### 已完成依赖

- R9: 权限管线

### 技术约束

- Java 21+ / Virtual Threads
- Spring Boot 3.3+
- 包结构按 §2.8.1（参考 spec_sections/ch02/s2.4_Java_后端包结构.md）
- 前端: React 18 + TypeScript + Vite + Zustand
- Python: FastAPI + asyncio

### 验收标准

- [ ] 4 种权限模式切换
- [ ] YoloClassifier Few-Shot

---

## 完成后

执行 `/verify-module` 校验本轮实现与 SPEC 的一致性：

```
/verify-module
模块: 权限系统增强
SPEC 片段: ch04/s4.9_权限系统增强.md
章节: §4.9
```
