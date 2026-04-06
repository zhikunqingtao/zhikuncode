# Round 36: Skill 系统

> 阶段: P1 增强 | 依赖: R35 | SPEC: §4.7, §4.1.20

---

## 指令

请使用 `/implement-module` Skill，参照以下 SPEC 片段实现 **Skill 系统** 模块。

### SPEC 片段文件

请读取以下文件作为实现参考：

- `spec_sections/ch04/s4.7_Skill_系统.md`
- `spec_sections/ch04/s4.1.20_SkillTool.md`

### 核心要求

1. Markdown YAML frontmatter 解析
2. 技能作为命令可调用

### 已完成依赖

- R35: 增强命令 (59 个)

### 技术约束

- Java 21+ / Virtual Threads
- Spring Boot 3.3+
- 包结构按 §2.8.1（参考 spec_sections/ch02/s2.4_Java_后端包结构.md）
- 前端: React 18 + TypeScript + Vite + Zustand
- Python: FastAPI + asyncio

### 验收标准

- [ ] .qoder/skills/ 目录扫描
- [ ] 技能注册+调用

---

## 完成后

执行 `/verify-module` 校验本轮实现与 SPEC 的一致性：

```
/verify-module
模块: Skill 系统
SPEC 片段: ch04/s4.7_Skill_系统.md
章节: §4.7, §4.1.20
```
