# Round 7: Tool 基础框架

> 阶段: P0 核心后端 | 依赖: R2 | SPEC: §3.2.1-§3.2.2, §3.2.4

---

## 指令

请使用 `/implement-module` Skill，参照以下 SPEC 片段实现 **Tool 基础框架** 模块。

### SPEC 片段文件

请读取以下文件作为实现参考：

- `spec_sections/ch03/s3.2.1_工具接口定义.md`
- `spec_sections/ch03/s3.2.1a_工具构建工厂_buildTool.md`
- `spec_sections/ch03/s3.2.1b_工具执行管线.md`
- `spec_sections/ch03/s3.2.2_工具并发执行引擎.md`
- `spec_sections/ch03/s3.2.4_工具池装配流程.md`

### 核心要求

1. Tool<I extends ToolInput> 泛型接口
2. ToolInput: getString/getInt/getOptionalString 等 API
3. ToolResult: text/image/error 结果类型
4. ToolUseContext record: 传递上下文
5. ToolRegistry: Spring Bean 自动发现
6. StreamingToolExecutor + Virtual Threads

### 已完成依赖

- R2: 数据模型

### 技术约束

- Java 21+ / Virtual Threads
- Spring Boot 3.3+
- 包结构按 §2.8.1（参考 spec_sections/ch02/s2.4_Java_后端包结构.md）
- 前端: React 18 + TypeScript + Vite + Zustand
- Python: FastAPI + asyncio

### 验收标准

- [ ] Tool 接口及所有类型定义编译通过
- [ ] ToolRegistry Bean 注册/查找工作
- [ ] 并发执行引擎 Virtual Threads 可用
- [ ] 工具执行管线: 输入验证→权限→执行→结果

---

## 完成后

执行 `/verify-module` 校验本轮实现与 SPEC 的一致性：

```
/verify-module
模块: Tool 基础框架
SPEC 片段: ch03/s3.2.1_工具接口定义.md
章节: §3.2.1-§3.2.2, §3.2.4
```
