# Round 18: MessageList + 渲染器

> 阶段: P0 前端 | 依赖: R17 | SPEC: §8.2.1-§8.2.4

---

## 指令

请使用 `/implement-module` Skill，参照以下 SPEC 片段实现 **MessageList + 渲染器** 模块。

### SPEC 片段文件

请读取以下文件作为实现参考：

- `spec_sections/ch08/s8.2.1_组件层级总览.md`
- `spec_sections/ch08/s8.2.2_原版_Ink_组件_→_Web_组件映射表.md`
- `spec_sections/ch08/s8.2.4_UI_组件系统完整实现规范.md`

### 核心要求

1. MessageList 虚拟滚动
2. TextBlock: Markdown + syntax highlighting
3. ThinkingBlock: 可折叠
4. ToolUseBlock: 工具名+参数+状态
5. ToolResultBlock: 工具输出
6. ImageBlock: base64/URL 图片

### 已完成依赖

- R17: STOMP 客户端 + dispatch

### 技术约束

- Java 21+ / Virtual Threads
- Spring Boot 3.3+
- 包结构按 §2.8.1（参考 spec_sections/ch02/s2.4_Java_后端包结构.md）
- 前端: React 18 + TypeScript + Vite + Zustand
- Python: FastAPI + asyncio

### 验收标准

- [ ] 消息列表正确渲染 user/assistant/system 消息
- [ ] Markdown + 代码高亮渲染
- [ ] 工具调用/结果 Block 渲染
- [ ] 流式更新不闪烁

---

## 完成后

执行 `/verify-module` 校验本轮实现与 SPEC 的一致性：

```
/verify-module
模块: MessageList + 渲染器
SPEC 片段: ch08/s8.2.1_组件层级总览.md
章节: §8.2.1-§8.2.4
```
