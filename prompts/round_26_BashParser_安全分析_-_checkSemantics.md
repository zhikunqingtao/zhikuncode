# Round 26: BashParser 安全分析 - checkSemantics

> 阶段: BashParser 专项 | 依赖: R25 | SPEC: §3.2.3c (checkSemantics)

---

## 指令

请使用 `/implement-module` Skill，参照以下 SPEC 片段实现 **BashParser 安全分析 - checkSemantics** 模块。

### SPEC 片段文件

请读取以下文件作为实现参考：

- `spec_sections/ch03/s3.2.3c_Bash_解析器与命令安全分析子系统.md`

### 核心要求

1. walkArgument() 10 种节点
2. walkVariableAssignment()
3. DANGEROUS_TYPES
4. EVAL_LIKE_BUILTINS

### 已完成依赖

- R25: BashParser 安全分析 - parseForSecurity

### 技术约束

- Java 21+ / Virtual Threads
- Spring Boot 3.3+
- 包结构按 §2.8.1（参考 spec_sections/ch02/s2.4_Java_后端包结构.md）
- 前端: React 18 + TypeScript + Vite + Zustand
- Python: FastAPI + asyncio

### 验收标准

- [ ] sudo/env/nice 包装剥离正确
- [ ] EVAL_LIKE_BUILTINS 检测
- [ ] SPECIAL_VARS 检测

---

## 完成后

执行 `/verify-module` 校验本轮实现与 SPEC 的一致性：

```
/verify-module
模块: BashParser 安全分析 - checkSemantics
SPEC 片段: ch03/s3.2.3c_Bash_解析器与命令安全分析子系统.md
章节: §3.2.3c (checkSemantics)
```
