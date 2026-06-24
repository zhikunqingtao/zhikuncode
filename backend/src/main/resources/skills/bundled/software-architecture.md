---
name: software-architecture
description: 提供Clean Architecture、SOLID原则与15种核心设计模式的中文化决策指引，重点覆盖Java/Spring Boot服务端场景
allowed-tools: [Read, Write]
arguments: [problem_description, tech_stack]
argument-hint: "问题描述与技术栈，如 '订单服务耦合过深 java-spring-boot'"
when_to_use: 当用户面临架构选型、模块解耦、设计模式选择或代码可维护性问题时
effort: medium
context: inline
user-invocable: true
version: "1.0"
---

# /software-architecture — 软件架构与设计模式中文指引

为服务端开发场景提供 Clean Architecture、SOLID 原则与核心设计模式的结构化决策支持。聚焦 Java / Spring Boot 实践，参考国内企业（阿里、字节跳动）的架构规范。

## 触发词
- "架构怎么设计"
- "用哪个设计模式"
- "代码耦合"
- "怎么解耦"
- "SOLID"
- "architecture"

## 执行流程

### 第一步：问题分类（工具：无，纯推理）

阅读用户提供的 `problem_description`，从以下维度归类：

| 类别 | 典型描述 | 推荐工具集 |
|------|---------|-----------|
| 分层混乱 | "Controller直接操作DB"、"Service里写HTTP调用" | Clean Architecture 四层模型 |
| 类职责过多 | "一个类几千行"、"修改一处影响全局" | SRP、提取模式 |
| 扩展困难 | "加一个支付渠道要改20处"、"if-else过长" | OCP、策略/工厂模式 |
| 接口僵化 | "一改父类全炸"、"子类被迫实现空方法" | LSP、ISP |
| 依赖紧耦合 | "无法单测"、"换中间件难" | DIP、依赖注入 |
| 对象创建复杂 | "构造参数20个"、"创建步骤多" | 工厂/建造者模式 |
| 行为切换 | "运行时算法切换"、"流程编排" | 策略/状态/责任链 |
| 跨切关注点 | "日志/事务/缓存散落各处" | 装饰器/AOP |

> **失败处理**：用户描述模糊时，主动追问"具体哪个模块/哪段代码不舒服"。

### 第二步：Clean Architecture 四层匹配（工具：无，纯推理）

按依赖方向 **由外向内**：

```
┌─────────────────────────────────────────┐
│  Frameworks & Drivers（最外层）         │
│  Spring Boot / MyBatis / Redis Client   │
│  ┌───────────────────────────────────┐  │
│  │  Interface Adapters               │  │
│  │  Controller / Gateway / DTO映射    │  │
│  │  ┌─────────────────────────────┐  │  │
│  │  │  Use Cases（应用业务规则）   │  │  │
│  │  │  XxxUseCase / Application    │  │  │
│  │  │  ┌───────────────────────┐  │  │  │
│  │  │  │  Entities（企业级规则）│  │  │  │
│  │  │  │  Domain Model         │  │  │  │
│  │  │  └───────────────────────┘  │  │  │
│  │  └─────────────────────────────┘  │  │
│  └───────────────────────────────────┘  │
└─────────────────────────────────────────┘
依赖方向：外层 → 内层（内层不知道外层存在）
```

**Java/Spring Boot 落地建议**：
- **Entities**：纯 POJO（无 `@Entity`、无 Spring 注解），位于 `domain/model`
- **Use Cases**：`application/usecase` 下的 `XxxUseCase` 接口与实现，无 `@RestController`
- **Interface Adapters**：`adapter/web`（Controller）、`adapter/persistence`（MyBatis/JPA Repository 实现）
- **Frameworks**：`infrastructure` 下的配置、Spring Boot 启动类、第三方 SDK 包装

> 阿里《Java开发手册》对应"应用分层"章节：开放接口层 / 终端显示层 / Web层 / Service层 / Manager层 / DAO层；可视为 Clean Architecture 的本地化变体。

### 第三步：SOLID 原则匹配（工具：无，纯推理）

| 原则 | 中文名 | 核心要点 | Java 示例 |
|------|--------|---------|-----------|
| **S**RP | 单一职责 | 一个类只有一个变化原因 | 把 `UserService` 拆为 `UserAuthService` + `UserProfileService` |
| **O**CP | 开闭原则 | 对扩展开放，对修改关闭 | 新增支付渠道不改 `PaymentService`，而是新增 `PaymentStrategy` 实现 |
| **L**SP | 里氏替换 | 子类可替换父类不破坏行为 | 不让 `Square extends Rectangle`（违反长宽独立约束） |
| **I**SP | 接口隔离 | 客户端不应依赖不需要的方法 | 拆分 `Readable` / `Writable` 而非一个 `Stream` |
| **D**IP | 依赖倒置 | 依赖抽象，不依赖具体实现 | Service 依赖 `OrderRepository` 接口，Spring 注入实现 |

**SRP 反例与修正**：
```java
// ❌ 反例：UserService 同时管理认证和邮件
class UserService {
    User login(String name, String pwd) { /* ... */ }
    void sendWelcomeEmail(User u) { /* ... */ }
}

// ✅ 修正：拆分职责
class UserAuthService { User login(...) { } }
class UserNotificationService { void sendWelcome(...) { } }
```

**OCP 落地（策略模式）**：
```java
public interface PaymentStrategy { PayResult pay(Order o); }

@Service public class AlipayStrategy implements PaymentStrategy { }
@Service public class WechatStrategy implements PaymentStrategy { }

@Service
public class PaymentService {
    private final Map<String, PaymentStrategy> strategies; // Spring 自动注入
    public PayResult pay(String channel, Order o) {
        return strategies.get(channel).pay(o);
    }
}
```

### 第四步：15种核心设计模式决策树（工具：无，纯推理）

```
你的痛点是什么？
├─ 创建对象复杂
│   ├─ 同类多变种 → ① 工厂方法
│   ├─ 多产品族   → ② 抽象工厂
│   ├─ 参数过多   → ③ 建造者（Builder）
│   ├─ 全局唯一   → ④ 单例（Spring Bean 默认即是）
│   └─ 复制成本高 → ⑤ 原型
├─ 结构组合
│   ├─ 接口不兼容   → ⑥ 适配器
│   ├─ 动态扩展行为 → ⑦ 装饰器
│   ├─ 简化复杂子系统 → ⑧ 外观（Facade）
│   └─ 树形结构     → ⑨ 组合（Composite）
└─ 行为协作
    ├─ 算法运行时切换 → ⑩ 策略
    ├─ 状态驱动行为   → ⑪ 状态机
    ├─ 多级处理流程   → ⑫ 责任链
    ├─ 一对多通知     → ⑬ 观察者 / 发布订阅
    ├─ 模板化流程     → ⑭ 模板方法
    └─ 操作可撤销/排队 → ⑮ 命令
```

**服务端 Java 高频组合示例**：

| 业务场景 | 推荐模式 | 落地说明 |
|---------|---------|---------|
| 订单状态流转 | 状态模式 + 策略模式 | `OrderState` 接口 + Spring 注入 |
| 多渠道支付 | 策略模式 + 工厂方法 | 按 `channel` 路由到具体 `PaymentStrategy` |
| 风控规则引擎 | 责任链模式 | 每个 `RiskHandler` 决定 pass/block/next |
| 通用CRUD封装 | 模板方法 | `AbstractService<T,ID>` 抽象骨架 |
| 操作审计/日志 | 装饰器 + AOP | `@Aspect` 切面包装 Service |
| 事件解耦 | 观察者 | Spring `ApplicationEventPublisher` |
| 复杂DTO构建 | 建造者 | Lombok `@Builder` |

### 第五步：技术栈适配（工具：无，纯推理）

根据 `tech_stack` 参数（如 `java-spring-boot` / `python-fastapi` / `node-express`）输出适配建议：

**Java / Spring Boot 重点**（默认）：
- 优先用 Spring 原生能力承载模式：`@Component` 实现策略、`ApplicationEvent` 实现观察者、`@Aspect` 实现装饰器
- DI 容器天然契合 DIP：所有依赖通过构造器注入
- 事务边界放在 Use Case 层（`@Transactional`），不下沉到 Adapter
- 字节跳动 Java 服务实践：DDD + Clean Architecture 折中，按"领域包 + 应用包 + 适配包"分包，禁止跨包反向依赖
- 阿里 COLA 架构：`adapter / app / domain / infrastructure / client`，可作为本地化参考

**Python / Node 等**：仅给出原则映射，不强行套用 Java 特性。

### 第六步：输出决策建议（工具：Write 可选）

按结构化模板输出，必要时写入 `docs/architecture/<topic>.md`：

```markdown
## 🏛️ 架构建议

**问题摘要**：{problem_description}
**技术栈**：{tech_stack}

### 一、问题归类
- 主要矛盾：{矛盾点}
- 次生影响：{影响范围}

### 二、推荐方案
1. **分层调整**：{Clean Architecture 落点}
2. **关键原则**：{命中的 SOLID 原则}
3. **设计模式**：{推荐的 1-3 个模式 + 理由}

### 三、改造步骤
- 第1步：{最小改动}
- 第2步：{扩展抽象}
- 第3步：{切换实现}

### 四、代码骨架（伪代码）
```java
// 关键接口与实现示例
```

### 五、风险提示
- ⚠️ {过度设计风险}
- ⚠️ {迁移成本}

### 六、参考资料
- 阿里巴巴《Java开发手册》分层规约
- 字节跳动 Java 微服务架构实践
- 《Clean Architecture》—— Robert C. Martin
```

## 决策原则

1. **奥卡姆剃刀**：能用最简方案解决的，不引入额外抽象
2. **YAGNI**：不为"以后可能需要"提前抽象
3. **可测试优先**：每个建议都要保证业务逻辑可单元测试（即必须遵守 DIP）
4. **本地化对标**：优先参考阿里 COLA、字节技术规范，避免照搬纯西方教科书示例
5. **渐进式改造**：大型重构必须给出最小可落地的第一步

## 安全规则
- 不建议未经测试覆盖的大范围重构
- 不在生产代码中引入未经评审的 SPI 机制
- 涉及安全/事务/并发关键路径时，必须额外提示风险

## 错误处理策略
- 用户描述不足以归类 → 主动列出 8 大问题类别请用户对号入座
- 多种模式都适用 → 列出 Top2 并给出取舍标准（团队熟悉度 / 后续扩展可能性）
- 模式与现有代码风格冲突 → 提示渐进式过渡，不强推大爆炸式改造
