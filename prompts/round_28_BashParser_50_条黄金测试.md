# Round 28: BashParser 50 条黄金测试

> 阶段: BashParser 专项 | 依赖: R27 | SPEC: §3.2.3c (50 条黄金测试)

---

## 指令

请使用 `/implement-module` Skill，参照以下 SPEC 片段实现 **BashParser 50 条黄金测试** 模块。

### SPEC 片段文件

请读取以下文件作为实现参考：

- `spec_sections/ch03/s3.2.3c_Bash_解析器与命令安全分析子系统.md`

### 核心要求

1. 简单命令(5)/管道(4)/重定向(5)/变量展开(5)/命令替换(4)/引号转义(5)/控制流(6)/复合(4)/函数(2)/Glob(3)/声明(3)/安全边界(4)

### 已完成依赖

- R27: BashParser 与权限系统集成

### 技术约束

- Java 21+ / Virtual Threads
- Spring Boot 3.3+
- 包结构按 §2.8.1（参考 spec_sections/ch02/s2.4_Java_后端包结构.md）
- 前端: React 18 + TypeScript + Vite + Zustand
- Python: FastAPI + asyncio

### 验收标准

- [ ] 50 条测试全部通过
- [ ] 12 个语法类别覆盖

---

## 完成后

执行 `/verify-module` 校验本轮实现与 SPEC 的一致性：

```
/verify-module
模块: BashParser 50 条黄金测试
SPEC 片段: ch03/s3.2.3c_Bash_解析器与命令安全分析子系统.md
章节: §3.2.3c (50 条黄金测试)
```
