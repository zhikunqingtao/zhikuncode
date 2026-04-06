# Round 33: MCP 集成

> 阶段: P1 增强 | 依赖: R11 | SPEC: §4.3, §4.1.18-§4.1.19

---

## 指令

请使用 `/implement-module` Skill，参照以下 SPEC 片段实现 **MCP 集成** 模块。

### SPEC 片段文件

请读取以下文件作为实现参考：

- `spec_sections/ch04/s4.3_MCP_集成.md`
- `spec_sections/ch04/s4.1.18_ListMcpResourcesTool.md`
- `spec_sections/ch04/s4.1.19_ReadMcpResourceTool.md`

### 核心要求

1. SmartLifecycle 启动
2. stdio 进程: SIGTERM→5s→SIGKILL
3. 重连机制

### 已完成依赖

- R11: CommandRegistry 命令系统

### 技术约束

- Java 21+ / Virtual Threads
- Spring Boot 3.3+
- 包结构按 §2.8.1（参考 spec_sections/ch02/s2.4_Java_后端包结构.md）
- 前端: React 18 + TypeScript + Vite + Zustand
- Python: FastAPI + asyncio

### 验收标准

- [ ] MCP 服务器连接成功
- [ ] 工具发现+注册
- [ ] 资源读取可用

---

## 完成后

执行 `/verify-module` 校验本轮实现与 SPEC 的一致性：

```
/verify-module
模块: MCP 集成
SPEC 片段: ch04/s4.3_MCP_集成.md
章节: §4.3, §4.1.18-§4.1.19
```
