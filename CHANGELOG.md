# Changelog

本文件记录 ZhikunCode 项目的所有重要变更。

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [1.2.0] - 2026-05-07

### Added
- 新增 GitHub Actions CI 工作流与安全扫描
- 新增 Dependabot 自动依赖更新配置
- 新增 Moonshot/Kimi 作为第三方 LLM Provider
- 新增代码路径追踪可视化（F40）三端实现
- 新增代码转图表自动生成功能（F35）
- 新增 6 项前端可视化功能（F3/F33/F25 等）及 E2E 验证
- 新增侧边栏可拖拽调整宽度与独立窗口支持
- 新增级联压缩 Phase1+Phase2 实现

### Changed
- 升级 Node.js 20 → 22
- 建立综合单元测试体系（84 用例 / 277 方法）
- 新增架构图 HTML 页面用于 GitHub Pages 部署

### Fixed
- 修复 Blame 视图列宽导致内容截断的问题

### Removed
- 移除无用代码 TokenAlertEvaluator 及所有引用

## [1.1.0] - 2026-04-29

### Added
- 新增插件系统：支持动态加载、生命周期钩子和热重载
- 新增 DeepSeek V4 Pro/Flash 模型支持（含完整思考模式）
- 新增 WebSocket 推送替代会话列表轮询 + TTL 自动清理
- 新增 Skill 系统端到端执行链路修复与完善
- 新增局域网访问支持与移动端适配
- 新增 P0P1 六项架构优化：安全黑名单 / 记忆统一存储 / API 语义化 / 依赖锁定 / 前端组件化 / 压缩增强

### Fixed
- 修复 WebSocket 断连后权限弹窗失效与工具执行可靠性问题
- 修复 PROMPT 命令注入 LLM 路由 + WebSocket 会话映射问题
- 修复 `crypto.randomUUID()` 在非安全上下文下的兼容性问题
- 修复 aica CLI `--version` 和 `--continue` 会话 bug
- 修复 stop.sh 自动清理 Agent 残留 git worktree 和临时分支
- 禁用 DashScope MCP 服务器默认启动以避免日志刷屏

## [1.0.1] - 2026-04-23

### Added
- 新增品牌目录统一：`.qoder` → `.zhikun`
- 新增 CLI 工具文档章节（中英文 README）
- 新增记忆系统、技能系统、多 Agent 协作文档章节
- 新增质量保障章节与测试报告

### Changed
- 优化 Docker 部署体验，改善首次使用引导
- 修正竞品对比表格数据（基于源码验证）

### Fixed
- 修复 Docker 构建缺少 MCP 注册表配置的问题
- 修复 logo.png 缺失、统一工具数量描述

## [1.0.0] - 2026-04-22

### Added
- 多模型 LLM 支持（通义千问、DeepSeek、OpenAI 兼容 API 等）
- 多 Agent 协作模式（Team / Swarm）
- 47 个内置工具（Bash、文件编辑、搜索、Git 等）
- MCP（Model Context Protocol）集成，可扩展工具生态
- 8 层 Bash 安全流水线与权限控制
- WebSocket 实时通信架构
- React + TailwindCSS 前端
- Python FastAPI 分析服务
- Docker 单容器部署方案
- 完整的权限与路径安全体系

### Security
- 路径穿越防护
- 敏感文件访问控制
- 命令注入防护
- 基于权限的工具执行机制

[1.2.0]: https://github.com/zhikuncode/zhikuncode/compare/v1.1.0...HEAD
[1.1.0]: https://github.com/zhikuncode/zhikuncode/compare/v1.0.1...v1.1.0
[1.0.1]: https://github.com/zhikuncode/zhikuncode/compare/v1.0.0...v1.0.1
[1.0.0]: https://github.com/zhikuncode/zhikuncode/releases/tag/v1.0.0
