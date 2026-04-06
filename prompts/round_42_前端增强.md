# Round 42: 前端增强

> 阶段: P1 增强 | 依赖: R20 | SPEC: §4.8, §4.10, §4.15

---

## 指令

请使用 `/implement-module` Skill，参照以下 SPEC 片段实现 **前端增强** 模块。

### SPEC 片段文件

请读取以下文件作为实现参考：

- `spec_sections/ch04/s4.10_Web_前端增强.md`
- `spec_sections/ch04/s4.8_键盘绑定系统.md`
- `spec_sections/ch04/s4.15_Vim_编辑模式.md`

### 核心要求

1. SettingsPage 5 路由
2. 97 种动作绑定
3. Vim 输入模式

### 已完成依赖

- R20: 响应式布局 + 主题系统

### 技术约束

- Java 21+ / Virtual Threads
- Spring Boot 3.3+
- 包结构按 §2.8.1（参考 spec_sections/ch02/s2.4_Java_后端包结构.md）
- 前端: React 18 + TypeScript + Vite + Zustand
- Python: FastAPI + asyncio

### 验收标准

- [ ] 设置页面 5 个 Tab
- [ ] 键盘快捷键可用

---

## 完成后

执行 `/verify-module` 校验本轮实现与 SPEC 的一致性：

```
/verify-module
模块: 前端增强
SPEC 片段: ch04/s4.10_Web_前端增强.md
章节: §4.8, §4.10, §4.15
```
