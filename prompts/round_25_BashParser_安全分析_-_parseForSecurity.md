# Round 25: BashParser 安全分析 - parseForSecurity

> 阶段: BashParser 专项 | 依赖: R24 | SPEC: §3.2.3c (parseForSecurity)

---

## 指令

请使用 `/implement-module` Skill，参照以下 SPEC 片段实现 **BashParser 安全分析 - parseForSecurity** 模块。

### SPEC 片段文件

请读取以下文件作为实现参考：

- `spec_sections/ch03/s3.2.3c_Bash_解析器与命令安全分析子系统.md`

### 核心要求

1. ParseForSecurityResult 三态
2. walkProgram 遍历
3. 复杂度判定

### 已完成依赖

- R24: BashParser 复合结构

### 技术约束

- Java 21+ / Virtual Threads
- Spring Boot 3.3+
- 包结构按 §2.8.1（参考 spec_sections/ch02/s2.4_Java_后端包结构.md）
- 前端: React 18 + TypeScript + Vite + Zustand
- Python: FastAPI + asyncio

### 验收标准

- [ ] parseForSecurity 返回正确三态
- [ ] simple/too-complex 分类准确

---

## 完成后

执行 `/verify-module` 校验本轮实现与 SPEC 的一致性：

```
/verify-module
模块: BashParser 安全分析 - parseForSecurity
SPEC 片段: ch03/s3.2.3c_Bash_解析器与命令安全分析子系统.md
章节: §3.2.3c (parseForSecurity)
```
