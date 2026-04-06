# Round 8: 10 个 P0 核心工具

> 阶段: P0 核心后端 | 依赖: R7 | SPEC: §3.2.3-§3.2.3b

---

## 指令

请使用 `/implement-module` Skill，参照以下 SPEC 片段实现 **10 个 P0 核心工具** 模块。

### SPEC 片段文件

请读取以下文件作为实现参考：

- `spec_sections/ch03/s3.2.3_P0_核心工具规范.md`
- `spec_sections/ch03/s3.2.3a_P0_核心工具实现算法.md`
- `spec_sections/ch03/s3.2.3b_全工具完整实现规范.md`

### 核心要求

1. BashTool: ProcessBuilder + 超时 + 输出截断 30000 chars
2. FileReadTool: Files.readString, 200MB/60K tokens 限制
3. FileWriteTool: Files.writeString + mtime 竞态检测
4. FileEditTool: java-diff-utils, 3 策略 fuzzy matching
5. GlobTool: FileSystem.getPathMatcher + FileVisitor
6. GrepTool: ProcessBuilder 调用 ripgrep
7. WebFetchTool: OkHttp + Jsoup HTML→Markdown
8. WebSearchTool: 策略模式 4 后端
9. EnterPlanModeTool / ExitPlanModeV2Tool: 纯模式切换

### 已完成依赖

- R7: Tool 基础框架

### 技术约束

- Java 21+ / Virtual Threads
- Spring Boot 3.3+
- 包结构按 §2.8.1（参考 spec_sections/ch02/s2.4_Java_后端包结构.md）
- 前端: React 18 + TypeScript + Vite + Zustand
- Python: FastAPI + asyncio

### 验收标准

- [ ] 10 个工具编译通过
- [ ] 每个执行工具有基础单元测试
- [ ] BashTool: ProcessBuilder + 120s 超时 + SIGTERM→SIGKILL
- [ ] FileEditTool: java-diff-utils fuzzy matching

---

## 完成后

执行 `/verify-module` 校验本轮实现与 SPEC 的一致性：

```
/verify-module
模块: 10 个 P0 核心工具
SPEC 片段: ch03/s3.2.3_P0_核心工具规范.md
章节: §3.2.3-§3.2.3b
```
