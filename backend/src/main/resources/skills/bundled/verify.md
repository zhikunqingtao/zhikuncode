---
name: verify
description: 全面代码验证流程，包含编译检查、测试执行、静态分析和质量报告
allowed-tools: [Bash, FileRead, Search]
arguments: [scope]
argument-hint: "验证范围，如 'all'、'backend'、'frontend'、'src/main/java/com/example/Service.java'"
when_to_use: 代码修改后需要验证正确性和质量时
effort: medium
context: inline
user-invocable: true
version: "1.0"
---

# /verify — 代码验证与质量检查

全面验证代码的正确性，包含编译检查、测试执行、静态分析。确保每次修改后代码处于可工作状态。

## 触发词
- "验证一下"
- "检查代码"
- "跑一下测试"
- "编译通过吗"
- "verify"

## 执行流程

### 第一步：确定验证范围（工具：Bash / Search）

根据用户输入确定验证级别：

| 范围参数 | 含义 | 验证内容 |
|----------|------|----------|
| `all` | 全量验证 | 后端 + 前端 + Python 全部验证 |
| `backend` | 后端验证 | Java 编译 + 后端测试 |
| `frontend` | 前端验证 | TypeScript 编译 + 前端测试 |
| `python` | Python验证 | Python 语法检查 + 测试 |
| `具体文件路径` | 文件级验证 | 仅验证指定文件及其依赖 |

1. 解析 scope 参数确定验证目标
2. 检查项目结构确认可用的验证工具：
   ```bash
   # 检查可用的构建工具
   ls backend/mvnw backend/pom.xml 2>/dev/null
   ls frontend/package.json frontend/tsconfig.json 2>/dev/null
   ls python-service/requirements.txt 2>/dev/null
   ```

> **失败处理**：如果指定的范围不存在对应目录，报告并建议可用选项。

### 第二步：编译检查（工具：Bash）

按技术栈分别执行编译：

**Java 后端**：
```bash
cd backend && ./mvnw compile -q 2>&1
# 检查编译结果
echo "Exit code: $?"
```

**TypeScript 前端**：
```bash
cd frontend && npx tsc --noEmit 2>&1
echo "Exit code: $?"
```

**Python 服务**：
```bash
cd python-service && python3 -m py_compile app.py 2>&1
# 批量检查
find python-service -name "*.py" -exec python3 -m py_compile {} \; 2>&1
```

记录每个模块的编译状态：
```
📦 编译检查
- Java 后端：   ✅ 通过 / ❌ 失败（N个错误）
- TS 前端：     ✅ 通过 / ❌ 失败（N个错误）
- Python 服务： ✅ 通过 / ❌ 失败（N个错误）
```

> **失败处理**：编译失败时，提取错误信息并列出需要修复的文件和行号。不继续后续步骤直到编译通过（除非用户明确要求）。

### 第三步：单元测试执行（工具：Bash）

**Java 后端测试**：
```bash
cd backend && ./mvnw test 2>&1 | tail -30
# 提取测试摘要
grep -E "Tests run:|BUILD" target/surefire-reports/*.txt 2>/dev/null || echo "无测试报告"
```

**前端测试**（如有配置）：
```bash
cd frontend && npm test -- --run 2>&1 | tail -30
```

**Python 测试**（如有配置）：
```bash
cd python-service && python3 -m pytest --tb=short -q 2>&1 | tail -20
```

记录测试结果：
```
🧪 单元测试
- Java：  通过 N / 失败 N / 跳过 N（总计 N）
- 前端：  通过 N / 失败 N / 跳过 N（总计 N）
- Python：通过 N / 失败 N / 跳过 N（总计 N）
```

> **失败处理**：
> - 测试失败 → 列出失败用例名称和失败原因
> - 测试超时 → 标记超时用例，建议单独排查
> - 无测试配置 → 标记"未配置"，不视为失败

### 第四步：静态分析 / Lint（工具：Bash）

检查项目是否配置了 lint 工具，如有则执行：

**前端 Lint**：
```bash
# 检查是否有ESLint配置
if [ -f frontend/.eslintrc* ] || grep -q "eslint" frontend/package.json; then
  cd frontend && npx eslint src/ --max-warnings 0 2>&1 | tail -20
fi
```

**Java 代码检查**（如有 checkstyle/spotbugs）：
```bash
# 检查是否配置了代码检查插件
grep -q "checkstyle\|spotbugs" backend/pom.xml && cd backend && ./mvnw verify -DskipTests 2>&1 | tail -20
```

**Python Lint**（如有 flake8/ruff）：
```bash
if command -v ruff &>/dev/null; then
  ruff check python-service/ 2>&1 | tail -20
elif command -v flake8 &>/dev/null; then
  flake8 python-service/ 2>&1 | tail -20
fi
```

> **失败处理**：无 lint 配置时跳过此步骤，在报告中标注"未配置"。

### 第五步：生成验证报告（工具：无，纯输出）

```
## ✅ 代码验证报告

**验证范围**：{scope}
**验证时间**：{timestamp}

### 编译状态
| 模块 | 状态 | 详情 |
|------|------|------|
| Java 后端 | ✅ 通过 | — |
| TS 前端 | ❌ 失败 | 3个类型错误 |
| Python 服务 | ⏭️ 跳过 | 不在验证范围内 |

### 测试结果
| 模块 | 通过 | 失败 | 跳过 | 总计 |
|------|------|------|------|------|
| Java 后端 | 42 | 0 | 2 | 44 |
| 前端 | 15 | 1 | 0 | 16 |

### 失败测试详情（如有）
1. `frontend/src/__tests__/App.test.tsx`
   - 失败原因：Expected "Hello" but got "Hi"

### 静态分析
| 工具 | 状态 | 告警数 |
|------|------|--------|
| ESLint | ✅ 通过 | 0 |
| Checkstyle | ⏭️ 未配置 | — |

### 总体结论
{PASS / FAIL + 需要修复的问题清单}
```

## 快速模式

当 scope 指定为具体文件路径时，执行精简验证：
1. 仅编译包含该文件的模块
2. 仅运行与该文件相关的测试（按类名/模块名匹配）
3. 跳过静态分析

## 验证规则
- 编译失败是阻断性问题，必须优先解决
- 测试失败需区分"本次引入"和"已有失败"
- 所有验证命令使用非交互模式运行
- 超时上限：编译 5 分钟，测试 10 分钟
