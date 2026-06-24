---
name: test-driven-development
description: TDD 红→绿→重构循环方法论指导，强制"先有失败测试再写实现"以保证代码可测性与质量
allowed-tools: [Bash, FileRead, FileEdit, Search]
arguments: [feature_description]
argument-hint: "待实现的功能或修复描述，如 '实现订单总价计算' 或 '修复用户名为空的校验bug'"
when_to_use: 当用户准备实现新功能、修复 bug，希望以 TDD 流程驱动开发，或代码缺乏测试覆盖时
effort: high
context: inline
user-invocable: true
version: "1.0"
---

# /test-driven-development — TDD 红绿重构循环

以测试驱动开发（TDD）方法论指导功能实现。强制遵循 **RED → GREEN → REFACTOR** 三段循环：先写一个会失败的测试，再写最少代码让它通过，最后重构改进设计。任何在失败测试存在之前写下的实现代码都应被视为"过早编码"，必须废弃。

## 触发词
- "用 TDD 实现"
- "先写测试再写代码"
- "测试驱动"
- "TDD"
- "红绿重构"

## 执行流程

### 第一步：需求拆分（工具：无，纯推理）

将 `feature_description` 拆解为可独立验证的最小行为单元：

1. **识别核心契约**：函数/方法的输入、输出、副作用
2. **列出行为切片**：把功能拆为 3-7 个独立行为
   - 正常路径：典型输入下的预期输出
   - 边界路径：空值、零值、最大值、单元素集合
   - 异常路径：非法输入、依赖故障、并发竞争
3. **排序**：按"最简单 → 最复杂"排序，每个切片对应一个测试用例

输出拆分结果：

```
🧩 行为切片清单
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
切片 1（最简）：{核心 happy path}
切片 2：{下一个增量行为}
切片 3：{边界场景 1}
切片 4：{异常处理}
...
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

> **失败处理**：需求过于笼统无法拆分时（如"做一个用户系统"），向用户确认本轮 TDD 的最小可交付范围，避免一次性吞掉整个模块。

### 第二步：测试框架定位（工具：Search / FileRead）

定位项目已有的测试框架与组织约定，**严格复用而非引入新框架**：

```bash
# Java（Spring Boot 后端）
ls backend/src/test/java
cat backend/pom.xml | grep -A 2 "junit\|mockito"

# 前端（React + Vitest/Jest）
cat frontend/package.json | grep -E "vitest|jest|@testing-library"

# Python
ls python-service/tests
cat python-service/pyproject.toml | grep -E "pytest|unittest"
```

输出技术栈摘要：
- 测试框架：JUnit 5 / Vitest / pytest / ...
- 断言风格：assertEquals / expect().toBe() / assert ...
- Mock 工具：Mockito / vi.mock / unittest.mock
- 覆盖率工具：JaCoCo / c8 / coverage.py

> **失败处理**：项目尚无任何测试基础设施时，先停下并提醒用户："当前项目无测试框架，TDD 需先建立测试基础（例如执行 `npm i -D vitest` 或在 pom.xml 添加 spring-boot-starter-test），是否继续？"

### 第三步：🔴 RED — 写一个会失败的测试（工具：FileEdit / Bash）

针对当前最简切片，编写**一个**失败的测试：

1. **命名清晰**：测试名直接陈述预期行为
   - 推荐："`shouldReturnZero_whenCartIsEmpty`" / "`returns_empty_list_for_no_orders`"
   - 避免："test1"、"testFunc"
2. **遵循 Arrange-Act-Assert 三段式**：
   ```
   // Arrange — 准备测试输入
   Cart cart = new Cart();
   // Act — 执行被测代码
   BigDecimal total = cart.calculateTotal();
   // Assert — 验证预期
   assertEquals(BigDecimal.ZERO, total);
   ```
3. **只断言一个行为**：每个测试只验证一个事实，多事实拆为多个测试
4. **运行测试，确认失败**：
   ```bash
   cd backend && ./mvnw test -Dtest=CartTest#shouldReturnZero_whenCartIsEmpty
   cd frontend && npx vitest run cart.test.ts -t "empty cart"
   ```
5. **检查失败原因是预期的**：
   - ✅ 编译失败（被测类/方法不存在）→ 正常，符合 RED
   - ✅ 断言失败（实现返回错值）→ 正常，符合 RED
   - ❌ 测试本身写错导致失败 → 修正测试再继续

> **失败处理**：测试意外通过（GREEN BEFORE RED）时，说明测试没有真正约束行为，必须重写测试直到它能真实失败。

### 第四步：🟢 GREEN — 写最少的代码让测试通过（工具：FileEdit / Bash）

写**刚好够**让红色测试变绿的实现，**严禁过度设计**：

1. **允许的最小实现**：
   - 硬编码返回值（如 `return BigDecimal.ZERO`）
   - 单分支 if-else
   - 复制粘贴重复代码（重构留到第五步）
2. **禁止的越界行为**：
   - ❌ 处理当前测试未覆盖的场景
   - ❌ 添加"未来肯定会用到"的扩展点
   - ❌ 引入新的抽象层 / 设计模式
   - ❌ 修改不相关的代码
3. **运行该测试 + 全量回归**：
   ```bash
   # 单测验证
   ./mvnw test -Dtest=CartTest#shouldReturnZero_whenCartIsEmpty
   # 全量回归确认无破坏
   ./mvnw test
   ```

> **失败处理**：发现实现需要超过 30 行才能让测试通过时，说明切片粒度过大，回到第一步把当前切片再拆细。

### 第五步：🔵 REFACTOR — 在绿色保护下重构（工具：FileEdit / Bash）

测试通过后，**保持测试始终绿色**的前提下优化代码：

1. **重构清单**（按需选用）：
   - 消除重复（DRY）：抽取方法、提取常量
   - 改名：变量/方法/类名向意图对齐
   - 拆分长方法：单个方法 < 30 行
   - 简化分支：嵌套 if 改为提前返回
   - 类型与边界：补充 null 检查、参数校验
2. **每改一处，立刻跑测试**：
   ```bash
   ./mvnw test -Dtest=CartTest
   ```
3. **不允许在重构阶段添加新功能**：新功能 = 新切片 = 回到第三步
4. **编辑器/IDE 自动重构优先于手工改动**：减少引入新 bug 的风险

> **失败处理**：重构过程中测试变红，立刻 `git diff` 回看改动，回退到上一个绿色状态后再小步重构。

### 第六步：循环推进（工具：无，纯推理）

完成一个切片后，回到第三步处理下一切片，直至所有切片完成：

```
📈 TDD 进度
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
切片 1（empty cart）：🔴→🟢→🔵 ✅ 完成
切片 2（single item）：🔴 进行中
切片 3（multiple items）：⏳ 待处理
切片 4（applies discount）：⏳ 待处理
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

切片间允许休整：
- 每完成 2-3 个切片，做一次集中重构（结构性优化）
- 每完成全部切片，整体跑一次覆盖率检查
  ```bash
  ./mvnw test jacoco:report
  npx vitest run --coverage
  pytest --cov
  ```

### 第七步：终态验证（工具：Bash）

全部切片完成后做收尾检查：

1. **全量测试通过**：`./mvnw test` / `npm test` / `pytest`
2. **覆盖率达标**：核心逻辑分支覆盖率 ≥ 80%
3. **回归无破坏**：邻近模块的既有测试仍然全绿
4. **测试可读性自检**：测试名 + AAA 结构是否能让陌生读者一眼看懂行为契约

## 输出格式

```
## 🧪 TDD 实施报告

**功能描述**：{feature_description}
**循环次数**：{N} 个红绿重构循环
**新增测试**：{M} 个

### 行为切片完成情况
| # | 切片名称 | 测试方法 | 状态 |
|---|---------|---------|------|
| 1 | empty cart | shouldReturnZero_whenCartIsEmpty | ✅ |
| 2 | single item | shouldReturnPrice_whenSingleItem | ✅ |

### 关键设计决策
- {为什么选择某种实现，而非另一种}
- {重构阶段做的命名/结构优化}

### 覆盖率
- 行覆盖：{X}%
- 分支覆盖：{Y}%
- 关键路径：{已覆盖 / 待补}

### 后续建议
- 推荐补充的边界用例：{...}
- 建议的下一轮 TDD 切片：{...}
```

## TDD 原则
- **测试先行**：在失败测试存在之前写下的任何实现代码都视为废弃，必须删除重做
- **一次一红**：每个 RED 阶段只引入一个失败测试，避免多目标耦合
- **最小实现**：GREEN 阶段只解决眼前红色测试，不预测未来需求
- **重构受测试保护**：REFACTOR 阶段每一步都要保持全绿，不绿就回退
- **小步快走**：每个红绿重构循环控制在 5-15 分钟，超时说明切片过粗
- **拒绝伪 TDD**：先写完代码再补测试不是 TDD，是测试覆盖而已；本技能要求严格的"先红后绿"
- **复用项目栈**：使用项目已有的测试框架与约定，不引入第二套测试栈
