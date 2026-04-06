# Round 1: 三项目初始化

> 阶段: 项目脚手架 | 依赖: 无 | SPEC: §1.3-§1.4, §2.4(包结构), §2.5, §2.8

---

## 指令

请使用 `/implement-module` Skill，参照以下 SPEC 片段实现 **三项目初始化** 模块。

### SPEC 片段文件

请读取以下文件作为实现参考：

- `spec_sections/ch01_项目概述.md`
- `spec_sections/ch02/s2.4_Java_后端包结构.md`
- `spec_sections/ch02/s2.5_前端目录结构.md`
- `spec_sections/ch02/s2.8_项目构建配置.md`

### 核心要求

1. Java: Spring Boot 3.3+, Java 21+, OkHttp, SQLite JDBC, Jackson, Lombok, MapStruct
2. Python: FastAPI + uvicorn + tree-sitter + rope + jedi + pygls + bashlex
3. React: Vite + React 18 + TypeScript + Zustand + Tailwind + shadcn/ui
4. React vite.config.ts 含 proxy 到 localhost:8080

### 已完成依赖

- 无前序依赖

### 技术约束

- Java 21+ / Virtual Threads
- Spring Boot 3.3+
- 包结构按 §2.8.1（参考 spec_sections/ch02/s2.4_Java_后端包结构.md）
- 前端: React 18 + TypeScript + Vite + Zustand
- Python: FastAPI + asyncio

### 验收标准

- [ ] Java 后端 ./gradlew bootRun 或 mvn spring-boot:run 启动成功
- [ ] Python FastAPI uvicorn 启动 + /api/health 返回 200
- [ ] React Vite dev 启动 + 页面可访问
- [ ] 三个项目各自独立，无业务逻辑

---

## 完成后

执行 `/verify-module` 校验本轮实现与 SPEC 的一致性：

```
/verify-module
模块: 三项目初始化
SPEC 片段: ch01_项目概述.md
章节: §1.3-§1.4, §2.4(包结构), §2.5, §2.8
```
