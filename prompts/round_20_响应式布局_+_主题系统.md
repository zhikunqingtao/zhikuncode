# Round 20: 响应式布局 + 主题系统

> 阶段: P0 前端 | 依赖: R19 | SPEC: §8.6-§8.9

---

## 指令

请使用 `/implement-module` Skill，参照以下 SPEC 片段实现 **响应式布局 + 主题系统** 模块。

### SPEC 片段文件

请读取以下文件作为实现参考：

- `spec_sections/ch08/s8.6_主题系统.md`
- `spec_sections/ch08/s8.6a_无障碍访问规范.md`
- `spec_sections/ch08/s8.8_移动端访问与响应式设计.md`
- `spec_sections/ch08/s8.9_输出样式系统.md`

### 核心要求

1. 26 个 CSS 变量 × light/dark 两套
2. prefers-color-scheme 跟随系统
3. Tailwind 响应式断点
4. ≥375px 移动端适配

### 已完成依赖

- R19: PromptInput + 对话框组件

### 技术约束

- Java 21+ / Virtual Threads
- Spring Boot 3.3+
- 包结构按 §2.8.1（参考 spec_sections/ch02/s2.4_Java_后端包结构.md）
- 前端: React 18 + TypeScript + Vite + Zustand
- Python: FastAPI + asyncio

### 验收标准

- [ ] Light/Dark 主题切换正常
- [ ] 移动端 ≥375px 可用
- [ ] 26 个 CSS 变量正确
- [ ] ARIA 标签完整

---

## 完成后

执行 `/verify-module` 校验本轮实现与 SPEC 的一致性：

```
/verify-module
模块: 响应式布局 + 主题系统
SPEC 片段: ch08/s8.6_主题系统.md
章节: §8.6-§8.9
```
