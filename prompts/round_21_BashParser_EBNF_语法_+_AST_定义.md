# Round 21: BashParser EBNF 语法 + AST 定义

> 阶段: BashParser 专项 | 依赖: R8 | SPEC: §3.2.3c (EBNF + AST)

---

## 指令

请使用 `/implement-module` Skill，参照以下 SPEC 片段实现 **BashParser EBNF 语法 + AST 定义** 模块。

### SPEC 片段文件

请读取以下文件作为实现参考：

- `spec_sections/ch03/s3.2.3c_Bash_解析器与命令安全分析子系统.md`

### 核心要求

1. sealed interface BashAstNode
2. SimpleCommandNode/PipelineNode/ListNode/...

### 已完成依赖

- R8: 10 个 P0 核心工具

### 技术约束

- Java 21+ / Virtual Threads
- Spring Boot 3.3+
- 包结构按 §2.8.1（参考 spec_sections/ch02/s2.4_Java_后端包结构.md）
- 前端: React 18 + TypeScript + Vite + Zustand
- Python: FastAPI + asyncio

### 验收标准

- [ ] AST 节点类型编译通过
- [ ] 覆盖所有 EBNF 产生式

---

## 完成后

执行 `/verify-module` 校验本轮实现与 SPEC 的一致性：

```
/verify-module
模块: BashParser EBNF 语法 + AST 定义
SPEC 片段: ch03/s3.2.3c_Bash_解析器与命令安全分析子系统.md
章节: §3.2.3c (EBNF + AST)
```
