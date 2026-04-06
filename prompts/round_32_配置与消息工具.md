# Round 32: 配置与消息工具

> 阶段: P1 增强 | 依赖: R11 | SPEC: §4.1.9, §4.1.11, §4.1.14

---

## 指令

请使用 `/implement-module` Skill，参照以下 SPEC 片段实现 **配置与消息工具** 模块。

### SPEC 片段文件

请读取以下文件作为实现参考：

- `spec_sections/ch04/s4.1.11_ConfigTool.md`
- `spec_sections/ch04/s4.1.14_SendMessageTool.md`
- `spec_sections/ch04/s4.1.9_SyntheticOutputTool.md`

### 核心要求

1. ConfigTool: 读写配置
2. SendMessageTool: 发送 STOMP 消息

### 已完成依赖

- R11: CommandRegistry 命令系统

### 技术约束

- Java 21+ / Virtual Threads
- Spring Boot 3.3+
- 包结构按 §2.8.1（参考 spec_sections/ch02/s2.4_Java_后端包结构.md）
- 前端: React 18 + TypeScript + Vite + Zustand
- Python: FastAPI + asyncio

### 验收标准

- [ ] 3 个工具编译通过 + 测试

---

## 完成后

执行 `/verify-module` 校验本轮实现与 SPEC 的一致性：

```
/verify-module
模块: 配置与消息工具
SPEC 片段: ch04/s4.1.11_ConfigTool.md
章节: §4.1.9, §4.1.11, §4.1.14
```
