# SPEC 执行清单

总轮次: 43

## 项目脚手架

- [ ] **R01**: 三项目初始化 (依赖: 无)

## P0 核心后端

- [ ] **R02**: 数据模型 (依赖: R1)
- [ ] **R03**: 数据库层 (依赖: R2)
- [ ] **R04**: AppState 状态管理 (依赖: R2)
- [ ] **R05**: SessionManager 会话管理 (依赖: R3, R4)
- [ ] **R06**: LLM Provider 抽象层 (依赖: R1)
- [ ] **R07**: Tool 基础框架 (依赖: R2)
- [ ] **R08**: 10 个 P0 核心工具 (依赖: R7)
- [ ] **R09**: 权限管线 (依赖: R7, R8)
- [ ] **R10**: QueryEngine 核心循环 (依赖: R6, R8, R9)
- [ ] **R11**: CommandRegistry 命令系统 (依赖: R10)

## API 与通信层

- [ ] **R12**: REST API (依赖: R10, R11)
- [ ] **R13**: WebSocket STOMP (依赖: R12)
- [ ] **R14**: 安全认证 (依赖: R12)
- [ ] **R15**: 集成测试 (依赖: R12, R13, R14)

## P0 前端

- [ ] **R16**: TypeScript 类型 + Zustand Store (依赖: R13)
- [ ] **R17**: STOMP 客户端 + dispatch (依赖: R16)
- [ ] **R18**: MessageList + 渲染器 (依赖: R17)
- [ ] **R19**: PromptInput + 对话框组件 (依赖: R18)
- [ ] **R20**: 响应式布局 + 主题系统 (依赖: R19)

## BashParser 专项

- [ ] **R21**: BashParser EBNF 语法 + AST 定义 (依赖: R8)
- [ ] **R22**: BashParser Lexer (依赖: R21)
- [ ] **R23**: BashParser Parser 核心 (依赖: R22)
- [ ] **R24**: BashParser 复合结构 (依赖: R23)
- [ ] **R25**: BashParser 安全分析 - parseForSecurity (依赖: R24)
- [ ] **R26**: BashParser 安全分析 - checkSemantics (依赖: R25)
- [ ] **R27**: BashParser 与权限系统集成 (依赖: R26, R9)
- [ ] **R28**: BashParser 50 条黄金测试 (依赖: R27)

## P1 增强

- [ ] **R29**: AgentTool 子代理系统 (依赖: R11)
- [ ] **R30**: Task 工具集 (依赖: R29)
- [ ] **R31**: 用户交互工具 (依赖: R11)
- [ ] **R32**: 配置与消息工具 (依赖: R11)
- [ ] **R33**: MCP 集成 (依赖: R11)
- [ ] **R34**: LSPTool (依赖: R33)
- [ ] **R35**: 增强命令 (59 个) (依赖: R11)
- [ ] **R36**: Skill 系统 (依赖: R35)
- [ ] **R37**: 插件系统 (依赖: R35)
- [ ] **R38**: IDE 桥接系统 (依赖: R13)
- [ ] **R39**: 权限系统增强 (依赖: R9)
- [ ] **R40**: Memdir 记忆系统 (依赖: R11)
- [ ] **R41**: REPLTool + PowerShellTool + NotebookEditTool (依赖: R11)
- [ ] **R42**: 前端增强 (依赖: R20)
- [ ] **R43**: Python 生态集成 + CLI (依赖: R34)

