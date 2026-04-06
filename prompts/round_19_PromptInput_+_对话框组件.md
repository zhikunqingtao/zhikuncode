# Round 19: PromptInput + 对话框组件

> 阶段: P0 前端 | 依赖: R18 | SPEC: §8.2.5-§8.2.6a

---

## 指令

请使用 `/implement-module` Skill，参照以下 SPEC 片段实现 **PromptInput + 对话框组件** 模块。

### SPEC 片段文件

请读取以下文件作为实现参考：

- `spec_sections/ch08/s8.2.5_React_Context_提供者层与对话启动器.md`
- `spec_sections/ch08/s8.2.6a_前端核心页面与组件完整实现.md`

### 核心要求

1. PromptInput: 多行输入 + 文件附件 + 命令触发
2. PermissionDialog: 三级风险展示 + Allow/Deny/AlwaysAllow
3. CommandPalette: 搜索+执行命令

### 已完成依赖

- R18: MessageList + 渲染器

### 技术约束

- Java 21+ / Virtual Threads
- Spring Boot 3.3+
- 包结构按 §2.8.1（参考 spec_sections/ch02/s2.4_Java_后端包结构.md）
- 前端: React 18 + TypeScript + Vite + Zustand
- Python: FastAPI + asyncio

### 验收标准

- [ ] 输入框可发送消息
- [ ] / 触发命令自动完成
- [ ] 权限对话框弹出并可响应
- [ ] Ctrl+K 打开命令面板

---

## 完成后

执行 `/verify-module` 校验本轮实现与 SPEC 的一致性：

```
/verify-module
模块: PromptInput + 对话框组件
SPEC 片段: ch08/s8.2.5_React_Context_提供者层与对话启动器.md
章节: §8.2.5-§8.2.6a
```
