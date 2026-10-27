[🌐 English](docs/README_EN.md)

<div align="center">
  <img src="docs/assets/logo.svg" alt="ZhikunCode" width="120" />
  <h1>ZhikunCode</h1>
  <p><strong>开源 AI 编程工作台 — 自托管，通过浏览器操作</strong></p>
  <p>Agent 分工与接续 · 上下文管理 · 工具授权 · 运行验证</p>

  <p>
    <a href="#-快速开始">快速开始</a> ·
    <a href="#-特性亮点">核心特性</a> ·
    <a href="https://zhikunqingtao.github.io/zhikuncode/#case-study">案例展示</a> ·
    <a href="#-公开工程案例">公开工程案例</a> ·
    <a href="#-swe-bench-lite-评测">SWE-bench 评测</a> ·
    <a href="#-cli-工具">CLI 工具</a> ·
    <a href="#-skill技能系统">skill技能系统</a> ·
    <a href="#-插件系统">插件系统</a> ·
    <a href="#-可视化">可视化</a> ·
    <a href="#-记忆系统">记忆系统</a> ·
    <a href="#-竞品对比">竞品对比</a> ·
    <a href="docs/README_EN.md">English</a>
  </p>

  <p>
    <a href="https://opensource.org/licenses/MIT"><img src="https://img.shields.io/badge/License-MIT-yellow.svg" alt="License: MIT" /></a>
    <a href="https://github.com/zhikunqingtao/zhikuncode"><img src="https://img.shields.io/badge/Docker-Ready-blue?logo=docker" alt="Docker" /></a>
    <a href="https://github.com/zhikunqingtao/zhikuncode/stargazers"><img src="https://img.shields.io/github/stars/zhikunqingtao/zhikuncode?style=social" alt="GitHub Stars" /></a>
    <a href="https://github.com/zhikunqingtao/zhikuncode"><img src="https://img.shields.io/github/last-commit/zhikunqingtao/zhikuncode" alt="Last Commit" /></a>
    <a href="https://github.com/zhikunqingtao/zhikuncode"><img src="https://img.shields.io/github/languages/code-size/zhikunqingtao/zhikuncode" alt="Code Size" /></a>
    <a href="https://github.com/zhikunqingtao/zhikuncode/actions/workflows/ci.yml"><img src="https://github.com/zhikunqingtao/zhikuncode/actions/workflows/ci.yml/badge.svg" alt="CI" /></a>
  </p>
</div>

---

ZhikunCode 将**多 Agent 分工、上下文管理、工具执行和运行验证**接入同一个浏览器工作台。模型读取项目、修改文件、执行命令，工具结果进入下一轮判断；你可以跟进子任务、检查修改，在运行中补充要求、处理审批或取消任务。

**运行环境由你部署，模型 API 由你选择。** 任务覆盖代码开发与审查、网页和数据分析，以及 Word、Excel、PPT 等材料制作。先看下方公开成果，再按[快速开始](#quickstart)搭建，完成[首次任务](#first-task)。模型 API 与外部服务仍会接收相应请求中的数据。

> 🏗️ **[查看完整系统架构图 →](https://zhikunqingtao.github.io/zhikuncode/ZhikunCode-Architecture.html)**  
> Java Agent Runtime · React 浏览器操作面 · Python Capability Service · 全景可视化

> 🧭 **[查看完整能力总览 →](https://zhikunqingtao.github.io/zhikuncode/ZhikunCode-Capability-Overview.html)**
> 交互入口 · Agent / Run · 权限安全 · 工具与 Skill · 验证产物 · 观测恢复

> **历史代码规模快照（非当前版本统计）：** `ea0170c` · 2026-08-09 · Git 跟踪的产品源代码 134,826 行 / 863 文件（Java 93,118 / 622、React 33,642 / 209、Python 服务与 CLI 8,066 / 32；不统计本地忽略文件）

> 🔎 **[实时黄金监控：ZhikunCode vs Codex 同题 PK →](https://zhikunqingtao.github.io/zhikuncode/case-studies/zhikuncode-codex-gold-monitor-audit.html)**
> ZhikunCode × Codex · 同一需求两套实现，14 维评分、过程截图与公开日志摘录

> 🚄 **[12306 候补全链路可视化：单文件零依赖交付 →](https://zhikunqingtao.github.io/zhikuncode/case-studies/zhikuncode-codex-12306-audit.html)**
> ZhikunCode/KimiK3 × Codex/GPT-5.6 Sol · 7 维对比评估，附公开源码与 ZhikunCode 单文件演示

> 🎮 **[5小时 AI 从零写出可玩的《王者荣耀》网页版 →](https://zhikunqingtao.github.io/zhikuncode/case-studies/zhikuncode%E5%BC%80%E5%8F%91%E7%8E%8B%E8%80%85%E8%8D%A3%E8%80%80.html)**
> Kimi K3 · 一次连续开发 5 小时 29 分 · 19 个文件 / 7,979 行纯静态 Three.js · 打开浏览器即可试玩

> 📊 **[AI 办公工具对比测评（宇树科技 · 截至 2026-08-30）→](https://zhikunqingtao.github.io/aireport/case-studies/AI%E5%8A%9E%E5%85%AC%E5%B7%A5%E5%85%B7%E5%AF%B9%E6%AF%94%E6%B5%8B%E8%AF%84_%E5%AE%87%E6%A0%91%E7%A7%91%E6%8A%80_%E6%88%AA%E8%87%B32026-08-30.html)**
> 五款工具 × 六任务连续链路 · 30 件固定终稿证据复核 · 同一报告阿里云 OSS 镜像：[oss.zhikun.xin](https://oss.zhikun.xin/zhikuncode/aireport/case-studies/AI%E5%8A%9E%E5%85%AC%E5%B7%A5%E5%85%B7%E5%AF%B9%E6%AF%94%E6%B5%8B%E8%AF%84_%E5%AE%87%E6%A0%91%E7%A7%91%E6%8A%80_%E6%88%AA%E8%87%B32026-08-30.html)

> 🔍 **AI Coding 代码审查对比：17 份报告**
> [Claude Opus5.5：排名分析、方法与证据 →](https://github.com/zhikunqingtao/zhikuncode/blob/aicoding-eval-0923/docs/case-studies/assets/aicoding%E8%AF%84%E6%B5%8B/Claude%20Opus5.5-17%E4%BB%BD%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A%E6%8E%92%E5%90%8D%E5%88%86%E6%9E%90%E6%96%B9%E6%B3%95%E8%BF%87%E7%A8%8B%E8%AF%81%E6%8D%AE%E4%B8%8E%E7%BB%93%E8%AE%BA.md) · [GPT-6 Astra Ultra：公平排名与可复核证据 →](https://github.com/zhikunqingtao/zhikuncode/blob/aicoding-eval-0923/docs/case-studies/assets/aicoding%E8%AF%84%E6%B5%8B/GPT-6%20Astra%20Ultra-17%E4%BB%BD%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A%E5%85%AC%E5%B9%B3%E6%8E%92%E5%90%8D%E4%B8%8E%E5%8F%AF%E5%A4%8D%E6%A0%B8%E8%AF%81%E6%8D%AE.md)

> 🔬 **[八款 AI Coding 同题代码审查（2026-09-25） →](docs/case-studies/assets/AICoding%E8%AF%84%E6%B5%8B0925/README.md)**
> 八份原报告 · 六维评分 · 可复核证据与约1小时57分钟过程录屏

> 🏆 **[SWE-bench Lite 技术报告 →](https://zhikunqingtao.github.io/zhikuncode/swe-bench-report.html)**  
> 提交命名空间 `20260525_zhikuncode` · 历史 harness 结果 **168 / 300 (56.0%)**；公开轨迹存在参考修复暴露，评测边界见[正文](#swe-bench)。

---

<a id="cases"></a>
## 🔎 公开工程案例

### 实时黄金监控：ZhikunCode vs Codex 同题 PK

同一个实时黄金监控需求，分别交给 ZhikunCode 与 Codex 从零完成，再按需求理解、开发交互、数据准确性、架构、代码质量、用户体验等 14 个维度打分。报告同时保留双方的优点与缺陷。

- ZhikunCode：Kimi K3 遇到 HTTP 429 后，由用户切换至 GLM-5.2 并在同一逻辑会话中继续。
- Codex：GPT-5.6 Sol，推理强度 High。
- 最终评分 ZhikunCode 68.3、Codex 68.4，两者接近；截图为历史行情，银行价格含估算，全球数据覆盖仍有缺口。

[![黄金监控应用的历史成品截图](docs/case-studies/assets/gold-audit/zhikuncode-product.jpg)](https://zhikunqingtao.github.io/zhikuncode/case-studies/zhikuncode-codex-gold-monitor-audit.html)

**[查看对比报告 →](https://zhikunqingtao.github.io/zhikuncode/case-studies/zhikuncode-codex-gold-monitor-audit.html)** ·
**[下载原始数据 →](docs/case-studies/zhikuncode-codex-gold-monitor-evidence.json)** ·
**[了解评测方法 →](docs/case-studies/README.md)**

### 12306 候补全链路可视化：单文件零依赖交付

同一个"12306 候补成功后台全链路"动态可视化需求，分别交给 ZhikunCode 与 Codex 完成，从任务理解、代码质量、动态可视化、架构与数学、用户体验、开发效率、技术实现 7 个维度评估。报告同时保留双方的优点与缺陷。

- ZhikunCode：kimi-k3 交付单文件 HTML，1,061 行、零外部网络依赖，打开就能跑。
- Codex：GPT-5.6 Sol（高推理模式）交付 Next.js 工程，会话截图显示总耗时 27m52s。
- 最终加权评分 ZhikunCode 8.68、Codex 8.45，差距小于报告的 0.5 分区分阈值，不据此判断总体能力高低。
- 作品含 8 幕自动播放、13 节点关系图与 6 张数学原理卡；这是候补流程概念演示，图中架构与数值不是铁路系统内部资料或实测。

[![12306 候补概念可视化：系统关系动画](docs/case-studies/assets/12306-comparison/zhikuncode-02-arch.png)](https://zhikunqingtao.github.io/zhikuncode/case-studies/assets/12306-comparison/artifacts/zhikuncode/12306-houbu.html)

**[查看对比报告 →](https://zhikunqingtao.github.io/zhikuncode/case-studies/zhikuncode-codex-12306-audit.html)** ·
**[产物在线 Demo →](https://zhikunqingtao.github.io/zhikuncode/case-studies/assets/12306-comparison/artifacts/zhikuncode/12306-houbu.html)** ·
**[下载原始数据 →](docs/case-studies/zhikuncode-codex-12306-evidence.json)** ·
**[了解评测方法 →](docs/case-studies/README.md)**

### 5小时，AI 从零写出可玩的《王者荣耀》网页版

ZhikunCode 使用 Kimi K3 在 2026-08-09 的连续开发记录中完成一个纯静态 Three.js 5v5 MOBA 单机原型：5 名可选英雄、三路兵线、技能和装备，从选将、对线，到结算与重开。

- 首次请求至末次计费请求约 5 小时 29 分，产出 19 个第一方文件、7,979 行代码，另含 Three.js。
- 10 个子 Agent 分工，覆盖玩法、渲染、AI 行为与关卡资源；其中 9 个因预算或时限结束，未完成部分由主 Agent 接手。
- 纯静态交付，无后端、无外部网络依赖，浏览器直接运行。
- 公开材料包括代码、日志摘录、43 个 PNG 文件（42 个不同内容）及 5 段派生视频预览；预览不等于原始录屏。

[![MOBA 原型录屏中的河道战斗](docs/case-studies/assets/king/videos/storyboard-frames/05-02.jpg)](https://king.zhikun.xin/?demo=1)

**具体如何调试：** 浏览器运行状态显示约 100 个小兵聚集在地图拐角 `(-80,60)`；主 Agent 据此委派路径与分离逻辑修复，再运行长局检查。后续又定位并修复终局僵持、基地贴墙等问题。报告保留“运行取证→代码修改→复验”的记录，可用来核查分工和验证实际发生了什么。

**[查看详细案例 →](https://zhikunqingtao.github.io/zhikuncode/case-studies/zhikuncode%E5%BC%80%E5%8F%91%E7%8E%8B%E8%80%85%E8%8D%A3%E8%80%80.html)** ·
**[在线试玩（5英雄选将）→](https://king.zhikun.xin/)** ·
**[自动演示（直接进入对局）→](https://king.zhikun.xin/?demo=1)** ·
**[了解评测方法 →](docs/case-studies/README.md)**

### AI 办公工具对比测评（宇树科技 · 截至 2026-08-30）

五款 AI 办公工具——千问办公、Qoder、豆包办公、WorkBuddy 与 ZhikunCode——依次执行同一组六条连续指令，围绕宇树科技完成从 Excel 分析底稿、Word 研究报告、PPT 到信息图与交互 HTML 的完整办公链路，共产出 30 件固定终稿并逐件复核证据。

六任务等权评分 ZhikunCode 93.77、千问 93.57；投研实务权重评分 ZhikunCode 92.65、千问 93.32，两榜均处于近分组。ZhikunCode 六件终稿均为 G1，需修正后采用。这是单次固定样本、不同模型条件下的产物评测，不外推为产品总体排名。

**[查看完整报告（GitHub Pages）→](https://zhikunqingtao.github.io/aireport/case-studies/AI%E5%8A%9E%E5%85%AC%E5%B7%A5%E5%85%B7%E5%AF%B9%E6%AF%94%E6%B5%8B%E8%AF%84_%E5%AE%87%E6%A0%91%E7%A7%91%E6%8A%80_%E6%88%AA%E8%87%B32026-08-30.html)** ·
**[阿里云 OSS 镜像 →](https://oss.zhikun.xin/zhikuncode/aireport/case-studies/AI%E5%8A%9E%E5%85%AC%E5%B7%A5%E5%85%B7%E5%AF%B9%E6%AF%94%E6%B5%8B%E8%AF%84_%E5%AE%87%E6%A0%91%E7%A7%91%E6%8A%80_%E6%88%AA%E8%87%B32026-08-30.html)**

### 教学 Word 与 PPT

与 WorkBuddy 完成同题教学材料，检查文档结构、25 页课件、配图和要求落实情况；这是固定产物比较，不代表教学效果评估。[查看产物对比](https://zhikunqingtao.github.io/zhikuncode/case-studies/zhikuncode对比workbuddy.html)。

### AI Coding 代码审查对比：17 份报告

两位评估者分别分析同一组报告，保留方法与证据；不是两组独立产品试验。

**[Claude Opus5.5：排名分析方法、过程、证据与结论 →](https://github.com/zhikunqingtao/zhikuncode/blob/aicoding-eval-0923/docs/case-studies/assets/aicoding%E8%AF%84%E6%B5%8B/Claude%20Opus5.5-17%E4%BB%BD%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A%E6%8E%92%E5%90%8D%E5%88%86%E6%9E%90%E6%96%B9%E6%B3%95%E8%BF%87%E7%A8%8B%E8%AF%81%E6%8D%AE%E4%B8%8E%E7%BB%93%E8%AE%BA.md)** ·
**[GPT-6 Astra Ultra：公平排名与可复核证据 →](https://github.com/zhikunqingtao/zhikuncode/blob/aicoding-eval-0923/docs/case-studies/assets/aicoding%E8%AF%84%E6%B5%8B/GPT-6%20Astra%20Ultra-17%E4%BB%BD%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A%E5%85%AC%E5%B9%B3%E6%8E%92%E5%90%8D%E4%B8%8E%E5%8F%AF%E5%A4%8D%E6%A0%B8%E8%AF%81%E6%8D%AE.md)**

### 八款 AI Coding 同题代码审查（2026-09-25）

八款 AI Coding 工具审查同一代码提交，从有效发现、准确性、归因、证据、工程覆盖及建议交付六个维度比较最终报告。公开八份原报告、逐项评分、独立复核证据、重算脚本与约1小时57分钟的过程录屏。本次为单任务产物评测，不代表产品总体能力排名。

**[评测首页 →](docs/case-studies/assets/AICoding%E8%AF%84%E6%B5%8B0925/README.md)** ·
**[证据与复算 →](docs/case-studies/assets/AICoding%E8%AF%84%E6%B5%8B0925/%E8%AF%81%E6%8D%AE%E9%99%84%E4%BB%B6/README.md)** ·
**[过程录屏与时间索引 →](docs/case-studies/assets/AICoding%E8%AF%84%E6%B5%8B0925/%E8%AF%81%E6%8D%AE%E9%99%84%E4%BB%B6/%E5%AE%8C%E6%95%B4%E6%B5%81%E7%A8%8B%E5%BD%95%E5%B1%8F/%E5%BD%95%E5%B1%8F%E6%97%B6%E9%97%B4%E7%B4%A2%E5%BC%95%E4%B8%8E%E8%BF%87%E7%A8%8B%E6%A0%B8%E9%AA%8C.md)**

ZhikunCode 在八方审查中找到工具输出 `outputTruncated` 与上层读取 `truncated` 的字段错配，并排除一项工作目录丢失的误报；复核也保留其自身漏检和错误建议。[查看具体发现](docs/case-studies/assets/AICoding评测0925/AICoding八方评测_综合排名与证据审计.md)。

> 以上案例的模型、输入和验收条件不同。详细方法与数据见各报告；公开材料范围及文件校验见[证据说明](#evidence-integrity)。

---

<a id="capabilities"></a>
## ✨ 特性亮点

| | 特性 | 说明 |
|---|---|---|
| 🌐 | **浏览器全流程操控** | 部署一次，任何设备的浏览器即可完成全流程操作 —— 权限审批、方案协商、任务管控，手机上也能用，无需安装客户端 |
| 🧭 | **开发工作台与过程显示** | 开发工作台展示对话、工具、文件、Git、终端、浏览器与 Agent 信息；精简、标准、完整过程三档控制过程展示。“本轮修改”可检查已记录的成功原生 Edit／Write；简洁视图切换入口目前隐藏 |
| 📦 | **当前交付投影** | 后端按 Root Run 及递归子 Run 关联本轮要求、回复、产物、待处理交互与证据；旧会话无法精确关联时采用兼容回退。这是运行数据组织能力，不代表简洁视图切换已开放 |
| 🔀 | **会话资料合并** | 将 2～5 个空闲会话的摘要、已持久化过程文本和可确认归属的临时产物整理到一个新会话，保留来源会话；新会话等待下一条指令，不自动合并工程代码 |
| 📁 | **Project 与 Run 控制** | 直连本机可选用原生目录选择器，远程部署只浏览配置的 allowed roots；Project 经真实路径规范化并持久授权，Session 拒绝客户端任意 `workingDirectory`。运行中输入按 Session 投递并返回 queued/applied/rejected，支持取消、包含 INTERRUPTED 的 CAS 单一终态与 WebSocket 恢复 |
| 🔗 | **引用本地文件** | 直连本机通过系统选择器把 canonical 路径加入提示词（不上传内容）；ECS、远程或代理访问通过浏览器选择文件，立即上传为 OSS 永久公开对象，再把地址加入提示词。远程上传需要先配置 OSS |
| 🤖 | **Agent 委派与后台任务** | 五种子 Agent 角色、独立上下文与并行执行；同步结果回传、当前 Run 后台结果汇总，以及可查询、可请求取消的后台任务 |
| 🔒 | **统一授权安全架构** | 所有核心工具统一经过 Tool Gateway：规范化输入冻结 → Operation Analyzer 风险与资源分析 → 系统不变量检查 → RUN/SESSION/WORKSPACE Grant 匹配或持久权限交互 → 执行前动态复检 → 结构化结果审计。高风险操作只允许单次授权；专用 Network/MCP Analyzer 对 SAFE/GUARDED 操作支持按工具记住 RUN/SESSION 授权，未知 MCP/动态工具默认只能单次审批 |
| 🇨🇳 | **国产大模型直连** | 可同时配置千问 / DeepSeek / Moonshot / 智谱GLM / MiniMax 等渠道，使用各自的 Key、端点和可用模型 |
| 🐳 | **Docker 一键部署** | `docker compose up -d` 默认启动 Java 后端和内置静态前端；镜像同时包含可选的受管 Python 服务，数据存本地 |
| 📤 | **OSS 发布与截图粘贴（可选）** | 按明确指令发布工作区文件；有匹配 Manifest 时复检验证状态和哈希。粘贴截图在 OSS 可用时立即上传，否则用 Base64；远程文件引用会上传永久公开对象，详见配置说明 |
| 🌍 | **秒悟应用发布（可选）** | 通过 `/publish-meoo` 将已验证的静态网站或全栈应用发布为独立新站点，获取可分享的网站链接；默认关闭，发布按当前权限模式授权或拒绝 |
| 🎙️ | **语音交互（ASR / TTS）** | 对话输入支持麦克风语音识别（qwen3-asr-flash），AI 回复支持一键朗读（qwen3-tts-flash）；接入阿里云百炼 DashScope，配置 API Key 即用，未配置时自动隐藏 |
| ⚡ | **上下文管理与恢复** | 裁剪旧工具输出、渐进折叠、摘要压缩与上游上下文拒绝后的分级恢复；图片外置引用、按需注入和预算守卫减少历史图片负担。自纠错循环与精确 tokenizer 为默认关闭的可选增强 |
| 📷 | **多模态图片对话** | 支持上传或粘贴图片。当前模型不支持图片时，依次尝试配置的兜底模型、模型家族、同 Provider 和其他已配置视觉模型；没有可用目标时不能保证兜底。外置图片引用重新注入时，单次最多 8 张，Base64 单张上限 1,500,000 字节、总量上限 2,000,000 字节；实际还受模型支持图片数和剩余预算约束。图片引用、完整性检查与预算限制见 ImageRefInjector / TokenBudgetGuard |
| 🖼️ | **浏览器语义快照** | `/snap` 命令智能捕获网页完整状态（DOM 结构 + 交互元素），支持富交互页面语义提取，生成结构化 JSON 供 Agent 解析和回放验证 |
| 💬 | **轮次与任务过程视图** | 显示方式三档：精简档默认折叠问题、过程与回复，可分别展开；标准、完整过程档保留完整问题与回复，按档展示过程，运行中同样生效。移动端常驻输入卡片提供附件、语音和显示方式切换，首页模板可直接填入草稿。 |
| 📊 | **实时活动追踪与审批** | Activity Panel 实时记录 AI 工具执行全流程，L1/L2/L3 三层展示体系，Signal 智能标记（auto_approve/review_recommended/needs_review），一键批量审批决策，SQLite 后端持久化，支持会话恢复 |
| 🧪 | **运行时验证框架（Runtime Verification）** | browser／http_api 两类验证器及 auto 自动选择 + 8 种 HTTP action handler + JSONPath 断言 + 证据链 SQLite 存储 + Feature Flag 双重门控 + 前端实时进度面板 |
| 🔍 | **证据包查看** | 查看验证步骤及实际留存的截图、命令、控制台、测试、视频、HAR、diff 等证据类型；类型定义不保证每次都产出。移动证据审批仍有未接通链路 |
| 🏆 | **SWE-bench Lite 历史记录** | 2026-05、qwen3.7-max、六工具、无子 Agent；历史 harness 结果 168/300。公开轨迹存在参考修复暴露，不能无条件视为独立完成率，详见评测正文 |
| 🚀 | **工程测试与观测** | 仓库提供后端、前端、Python 测试和 CI；历史接口延迟属于特定环境采样，不是模型完成任务耗时，也不代表当前版本全部测试通过 |
| 🏭 | **运行时可靠性** | Run 状态 CAS 原子管理 · 完整 Session 快照、Run event sequence 与待处理持久交互重放 · 进程硬超时+梯度终止 · 作用域 Grant 与子 Agent 受控继承 · Artifact declare→seal→hash 验证及显式发布 · 结构化工具结果生成工具卡片与权威下载链接 · `sid/rid/prid/agent/turn/tool/llm` 日志关联 · BestEffortObservabilityRecorder 补充事件失败隔离 · Provider 本地预算守卫 |

---

### 外观主题

支持浅色、深色、液态玻璃、星舰、花果晨、灵霄夜、果冻、蜘蛛爬虫和璀璨银河九套主题。桌面端通过顶栏「外观设置」切换，手机布局通过输入区「更多操作」中的「外观」切换；设置即时生效并保存在本地。

蜘蛛爬虫（`spider`）采用深黑底与青色／洋红／紫色霓虹蛛网，八足蜘蛛在空闲时探索、抓取文字副本，并随真实任务状态变化调整动作。选中即启用完整动效，不提供独立动效档位；原始文字仍可选择和复制。

该主题优先适配 PC，平板和手机按可用空间减少同时抓取数量。三维动效需要 WebGL 2，不可用时保留主题配色与聊天功能，页面隐藏时暂停三维绘制。相比普通主题会增加 GPU 开销；动画本身不额外调用模型。

璀璨银河（`galaxy`）以持续旋转的蓝／紫／玫红银河铺满页面，真实任务通过文件星点、工具光轨和回复星尘联动；工具详情折叠时，光效可落在执行摘要上。选中即启用完整效果，不提供独立效果开关，也不增加星图侧栏；原始文字和操作保持原位。动效需要 WebGL 2，不可用时保留静态配色与聊天功能，页面隐藏时暂停绘制。相比普通主题会增加 GPU 与电量消耗；动画本身不额外调用模型，历史消息不会重播任务动画。

果冻提供浓郁／克制样式及完整、精简、关闭三档动效，并遵循系统「减少动态效果」偏好。

花果晨与灵霄夜采用大闹天宫重彩戏曲风，分别提供暖色绢纸与深色夜景配色；使用专属强调色，不开放强调色预设切换。两套主题均提供：

- **浓郁／克制**：开启「浓郁模式」展示描金纹样、牌匾和实色消息气泡，关闭后使用克制样式；动效可独立选择完整、精简或关闭。
- **闭关**：收起装饰层，专注书写。
- **装裱分享**：将会话题名与可编辑的题跋制作成立轴、手卷或册页样式的单张 PNG 图片并导出。

<a id="workbench"></a>
### 对话工作台与修改验收

新建会话默认使用**开发工作台、标准显示方式**，显示方式可选精简、标准、完整过程；简洁工作台的切换入口目前隐藏。助手回复末尾的“本轮修改”可展开文件清单，查看本轮原生 `Edit`／`Write` 的逐次修改（仅含成功且路径可核实的操作）。

### 会话资料合并（2～5 个来源）

在会话列表选择 2～5 个已停止执行的空闲会话（可来自不同文件夹）合并为新会话，需确认主会话、新标题和模型；新会话接收交接摘要、过程文本与可确认归属的临时产物副本，工程代码不自动合并。合并期间来源会话可查看但不能执行或删除；新会话使用**“完全访问权限”（`AUTO_APPROVE`）**模式，创建后等待你的下一条指令。

### 语音识别热词配置

语音识别需配置 `LLM_PROVIDER_DASHSCOPE_API_KEY`（不能用 Token Plan Key），热词纠正在根目录 `.env` 设置 `ASR_CORRECTIONS` 后重启后端生效。格式为 `标准写法:变体1,变体2;标准写法2:变体3`，例如 `zhikuncode:zhi kun code,zkun code,智坤code;PostgreSQL:post gre sql`。配置项见 [`.env.example`](.env.example)。

<a id="quickstart"></a>
## ⚡ 快速开始

### 前置准备：获取 LLM API Key

准备支持工具调用的模型 API Key，可从[阿里云百炼](https://bailian.console.aliyun.com/cn-beijing/?tab=model#/api-key)、[DeepSeek](https://platform.deepseek.com/)、[Moonshot/Kimi](https://platform.moonshot.cn/)或其他支持的渠道获取。普通 DashScope 与 Token Plan Key 不能混用；费用、额度和账号权限以所选服务为准。

首次使用时，登录所选服务商的平台，在 API Key 管理页创建密钥并复制保存；在下方克隆仓库并执行 `cp .env.example .env` 后，将其填入 `.env` 中对应渠道的变量（例如 `LLM_PROVIDER_DASHSCOPE_API_KEY`）。不要提交含密钥的 `.env`。

### 方式一：Docker 部署（推荐）

准备一个支持工具调用的模型 API Key。以下命令面向 macOS／Linux 的 Bash 或 Zsh；Docker 还需 Docker Engine、Compose V2 和能下载构建依赖的网络。

| 方式 | 本次能体验什么 | 入口 |
|---|---|---|
| Docker 基础部署 | 对话、文件修改、命令和测试；镜像含前端与 Node，无需宿主安装 Java、Node | 下方命令 → [首次任务](#first-task) |
| 本地三服务 | 开发调试、完整前端代理，以及依赖就绪的 Python 分析／浏览器能力 | [本地安装](#local-setup) → [首次任务](#first-task) |

Docker 入口检查 DashScope、Token Plan、DeepSeek、Moonshot、Zhipu、ZenMux 或旧式 `LLM_API_KEY`；仅有 Kimi Code、MiniMax、OpenRouter 专用 Key 时会在入口退出，请使用本地三服务路径。基础部署在本机构建镜像，不是拉取预构建应用镜像。

```bash
git clone https://github.com/zhikunqingtao/zhikuncode.git
cd zhikuncode
cp .env.example .env
# 编辑 .env，填写自己的 Key，例如 LLM_PROVIDER_DASHSCOPE_API_KEY
# 设置 WORKSPACE_PATH=/你的项目绝对路径；目录需存在且允许容器用户读写

docker compose up -d --build
docker compose ps
curl -fsS http://localhost:8080/actuator/health
```

已有 `.env` 时保留自己的配置。健康检查应包含 `"status":"UP"`；打开 [localhost:8080](http://localhost:8080)，选择模型并授权容器内 `/app/workspace` 下的项目。端口改动需按 `.env` 中 `ZHIKUN_PORT` 同步访问；启动失败看 `docker compose logs --tail=100 zhikuncode`。停止用 `docker compose stop`，保留数据时不要使用 `down -v`。

新会话默认完全访问（`AUTO_APPROVE`）；需要审批时先切换权限模式。基础 Compose 不启动 Python，相关验证能力见[可选配置](#docker-python)。默认容器入口会放行到达后端的来源，首次试用应在受控网络，远程部署先处理[访问边界](#security)。

<a id="local-setup"></a>
### 方式二：本地开发

<details>
<summary>本地三服务：依赖、三终端启动与健康检查</summary>

准备 JDK 21、Node.js 22+、Python 3.11 或 3.12、Git、curl、ripgrep；Java 构建使用仓库自带 Maven Wrapper。先按上方步骤获取源码并配置 `.env`，确认 8080／8000／5173 端口未被其他服务占用。从仓库根安装依赖：

```bash
python3.11 -m venv python-service/.venv
python-service/.venv/bin/python -m pip install -r python-service/requirements.lock
python-service/.venv/bin/python -m playwright install chromium
npm --prefix frontend ci
```

使用 Python 3.12 时替换首行解释器。Linux 按 Playwright 输出补齐 Chromium 系统库；文件类型检测可能需要 `libmagic`。下面显式使用 `.venv`，避免把依赖装进与运行时不同的环境。

**打开三个终端，每个终端都从仓库根目录开始，先执行公共配置：**

```bash
set -a
source .env
set +a
export ZHIKUN_DEFAULT_WORKSPACE="$PWD"
export WORKSPACE_ROOT="$PWD"
export ZHIKUN_LOCAL_PICKER_ENABLED=true
export PATH="$PWD/scripts/mcp:$PATH"
```

只加载自己维护的可信 `.env`。本机 picker 适用于直连本机；远程部署保持关闭并配置 allowed roots。然后分别执行：

```bash
# 终端一：Java 后端，Python 在另一个终端独立运行
export PYTHON_SERVICE_AUTO_START=false
cd backend
./mvnw spring-boot:run -DskipTests
```

```bash
# 终端二：Python 能力服务
export PYTHONPATH="$PWD/python-service/src"
python-service/.venv/bin/python -m uvicorn src.main:app --app-dir python-service --host 127.0.0.1 --port 8000
```

```bash
# 终端三：前端开发代理
cd frontend
npm run dev
```

在另一个终端检查：

```bash
curl -fsS http://127.0.0.1:8080/actuator/health
curl -fsS http://127.0.0.1:8000/api/health
curl -fsS http://127.0.0.1:8000/api/health/capabilities
curl -fsS -o /dev/null -w '%{http_code}\n' http://127.0.0.1:5173/
```

Java 应为 `UP`，Python 为 `ok`，前端 HTTP 为 `200`；能力列表还需检查各域的 `available` 与 `reason`。打开 [localhost:5173](http://localhost:5173) 后继续[首次任务](#first-task)。各终端按 `Ctrl+C` 停止自己的服务。

依赖准备后也可使用 `./start.sh`，但它会清理固定端口、终止匹配的旧 Java 进程并删除 `backend/target`；脚本优先使用 `python-service/venv`，其次 `.venv`，最后系统 Python。共享开发机优先采用上面的手动方式。Vite 开发代理只供本机使用，不应直接暴露到远程网络。

</details>

#### 本地工作台使用说明

新建会话并选择、授权服务端项目目录，然后选择模型并描述任务。开发工作台提供文件、Git、终端、浏览器及 Agent 视图；助手回复末尾的“本轮修改”展示已记录的成功原生 Edit／Write。目录在 Docker 中使用 `/app/workspace` 下的路径；远程后端不能直接读取客户端电脑上的本地路径。“在文件夹中显示”仅适用于支持该操作的直连本机环境。

<a id="docker-python"></a>
<details>
<summary>Docker 可选：启用容器内 Python 能力服务</summary>

基础镜像包含 Python 环境，默认未启动。以下从仓库根通过标准输入传入 override；以后重新创建容器时也需要保留这组覆盖设置：

```bash
docker compose -f docker-compose.yml -f - up -d --build <<'YAML'
services:
  zhikuncode:
    environment:
      PYTHON_SERVICE_AUTO_START: "true"
      PYTHON_SERVICE_PATH: /app/python-service
      PYTHON_SERVICE_EXECUTABLE: /app/python-service/.venv/bin/python
      WORKSPACE_ROOT: /app/workspace
YAML
docker compose exec zhikuncode curl -fsS http://127.0.0.1:8000/api/health/capabilities
```

这条路径使 Java 能调用容器内 Python，仍需检查 Feature Flag 和实际能力状态。前端直连 Python 的文件／Git／分析界面还需同源代理，不能只开进程就视为全部界面就绪。代理范围以 [Vite 配置](frontend/vite.config.ts)与 [Python 路由](python-service/src/main.py)为准；保留 Java 的文件选择、引用和搜索入口，并让 Java、Python 与 WebSocket 共用访问控制。不要直接公开 Python 端口。

</details>

<a id="first-task"></a>
<details>
<summary>首次任务：准备两个文件，让模型改代码，再独立验收</summary>

本练习不安装第三方包。先保留一个专用终端；**从 zhikuncode 仓库根目录执行**下面的准备命令，创建独立临时项目（两种部署方式共用）：

```bash
ZHIKUN_DEMO=$(mktemp -d "${TMPDIR:-/tmp}/zhikuncode-first-task.XXXXXX")
mkdir -p "$ZHIKUN_DEMO/src" "$ZHIKUN_DEMO/test"
cat > "$ZHIKUN_DEMO/src/shipping.mjs" <<'JS'
/** Amounts are in yuan. Existing rule: free shipping at 100, otherwise 6. */
export function shippingFee(subtotal) {
  if (!Number.isFinite(subtotal) || subtotal < 0) {
    throw new RangeError('subtotal must be a finite, non-negative number');
  }
  return subtotal >= 100 ? 0 : 6;
}
JS
cat > "$ZHIKUN_DEMO/test/shipping.test.mjs" <<'JS'
import test from 'node:test';
import assert from 'node:assert/strict';
import { shippingFee } from '../src/shipping.mjs';

test('regular: zero subtotal costs 6', () => assert.equal(shippingFee(0), 6));
test('regular: below 100 costs 6', () => assert.equal(shippingFee(99.99), 6));
test('regular: exactly 100 is free', () => assert.equal(shippingFee(100), 0));
test('regular: above 100 is free', () => assert.equal(shippingFee(150), 0));
test('member: below 80 costs 6', () => assert.equal(shippingFee(79.99, true), 6));
// These two acceptance tests intentionally fail before the first task is done.
test('member: exactly 80 is free', () => assert.equal(shippingFee(80, true), 0));
test('member: below 100 is free', () => assert.equal(shippingFee(99.99, true), 0));
test('member: at 100 remains free', () => assert.equal(shippingFee(100, true), 0));
test('negative subtotal is rejected', () => assert.throws(() => shippingFee(-1), RangeError));
test('NaN subtotal is rejected', () => assert.throws(() => shippingFee(NaN), RangeError));
test('Infinity subtotal is rejected', () => assert.throws(() => shippingFee(Infinity), RangeError));
test('-Infinity subtotal is rejected', () => assert.throws(() => shippingFee(-Infinity), RangeError));
JS
```

**本地三服务用户**在同一终端继续；需要 Node.js 22+：

```bash
git -C "$ZHIKUN_DEMO" init -q
git -C "$ZHIKUN_DEMO" add .
ZHIKUN_BASE=$(git -C "$ZHIKUN_DEMO" write-tree)
(cd "$ZHIKUN_DEMO" && pwd -P && node --test test/shipping.test.mjs)
```

**Docker 用户**在同一终端继续；使用容器自带 Node，不要求宿主安装 Node。第一行需成功确认挂载可写：

```bash
docker compose exec -T zhikuncode sh -c 'test -w /app/workspace'
ZHIKUN_CONTAINER_DEMO=$(docker compose exec -T zhikuncode mktemp -d /app/workspace/first-task.XXXXXX)
tar -C "$ZHIKUN_DEMO" -cf - src test | docker compose exec -T zhikuncode tar -C "$ZHIKUN_CONTAINER_DEMO" -xf -
docker compose exec -T -w "$ZHIKUN_CONTAINER_DEMO" zhikuncode git init -q
docker compose exec -T -w "$ZHIKUN_CONTAINER_DEMO" zhikuncode git add .
ZHIKUN_BASE=$(docker compose exec -T -w "$ZHIKUN_CONTAINER_DEMO" zhikuncode git write-tree)
printf '%s\n' "$ZHIKUN_CONTAINER_DEMO"
docker compose exec -T -w "$ZHIKUN_CONTAINER_DEMO" zhikuncode node --test test/shipping.test.mjs
```

`git write-tree` 将初始文件快照的 tree ID 保存到 `ZHIKUN_BASE`，不创建提交；后续即使索引改变，仍与这份固定快照比较。保留当前终端及变量供验收使用。初始应是 **10 通过、2 失败，退出码 1**；失败项为 `member: exactly 80 is free` 与 `member: below 100 is free`。若出现其他错误，先处理环境。

打开工作台，新建会话并授权打印出的项目路径。本地使用 `pwd -P` 的路径，Docker 使用 `/app/workspace/first-task.…`；选择支持工具调用的模型，发送：

```text
这是独立的运费规则练习项目。阅读 src/shipping.mjs 和 test/shipping.test.mjs，
先运行 node --test test/shipping.test.mjs 确认当前失败。
只修改 src/shipping.mjs，实现 shippingFee(subtotal, isMember = false)：
普通用户满100元免运费，否则6元；会员满80元免运费，否则6元。
不传第二个参数时保持普通用户行为；负数或非有限金额仍抛出 RangeError。
不要改测试，不安装依赖，不联网，不提交或推送。
修改后重新运行测试并检查 git diff，报告实际结果和改动文件；未执行的检查明确说明。
```

在工具卡片中查看实际读写和命令输出。完成后，**本地用户**回到原终端独立验收：

```bash
(cd "$ZHIKUN_DEMO" && node --test test/shipping.test.mjs)
git -C "$ZHIKUN_DEMO" diff "$ZHIKUN_BASE" -- src/shipping.mjs
git -C "$ZHIKUN_DEMO" diff --name-only "$ZHIKUN_BASE"
git -C "$ZHIKUN_DEMO" diff --exit-code "$ZHIKUN_BASE" -- test/shipping.test.mjs
git -C "$ZHIKUN_DEMO" ls-files --others --exclude-standard
```

**Docker 用户**在原仓库终端验收容器中的同一个项目：

```bash
docker compose exec -T -w "$ZHIKUN_CONTAINER_DEMO" zhikuncode node --test test/shipping.test.mjs
docker compose exec -T -w "$ZHIKUN_CONTAINER_DEMO" zhikuncode git diff "$ZHIKUN_BASE" -- src/shipping.mjs
docker compose exec -T -w "$ZHIKUN_CONTAINER_DEMO" zhikuncode git diff --name-only "$ZHIKUN_BASE"
docker compose exec -T -w "$ZHIKUN_CONTAINER_DEMO" zhikuncode git diff --exit-code "$ZHIKUN_BASE" -- test/shipping.test.mjs
docker compose exec -T -w "$ZHIKUN_CONTAINER_DEMO" zhikuncode git ls-files --others --exclude-standard
```

验收终点：**12 通过、0 失败**；相对固定基线的 diff 只有 `src/shipping.mjs`，测试文件无变化；检查未跟踪文件，区分应用元数据与额外业务文件。这验证基本改码与测试流程，不代表已经验证并行 Agent、浏览器或发布能力，也不是模型成绩保证。

</details>

### 可选：OSS 产物发布、截图粘贴与远程文件引用

ZhikunCode 提供 `/publish-oss` 命令，将工作区内单个普通文件发布为 OSS 永久公开链接。底层发布能力默认关闭，需单独配置启用；部署未启用时，命令不出现在命令列表和补全中。匹配到同一持久化根 Session 及后代 Run 的 Manifest 时，要求状态为 `verified`／`partial`、目标条目已验证且哈希仍匹配；未匹配 Manifest 时允许按工作区文件路径发布，仍检查路径、文件类型、大小及敏感内容。生成、预览文件不会自动发布，但下述截图粘贴和远程引用有独立的即时上传路径。发布必须由用户明确请求；`AUTO_APPROVE` 可自动授权，`PLAN` 和 `DONT_ASK` 拒绝这类高风险发布，其余权限模式要求单次确认。配置、文件和内容检查仍须通过。

启用同一套 OSS 配置后，浏览器还能感知从其他应用复制并粘贴到对话框的 PNG/JPEG/WebP 截图（单张不超过 5 MiB），通过固定后端通道立即上传，并将服务端验证过的 OSS 图片地址直接交给视觉模型。这个路径不调用 `/publish-oss` Skill，也不额外调用 LLM；OSS 未配置时自动降级为 Base64 直传，无需额外配置即可使用图片分析能力。

“引用本地文件”会按实际访问能力选择路径：只有直接 loopback、无代理转发且原生选择器安全门禁全部满足时，才只发送 canonical 路径；ECS、远程和代理访问则使用浏览器文件选择器，选中后立即上传到 `${ZHIKUN_OSS_PREFIX}/local-files/`，并把文件名与 OSS 地址加入提示词。远程文件默认沿用 `ZHIKUN_OSS_MAX_FILE_BYTES`（100 MiB）上限，OSS 未配置时该入口不可用且不会降级为 Base64。该通道接受任意文件类型，不执行敏感文件名或内容扫描；对象发布为永久 `public-read`，移除标签、切换 Session 或放弃消息都不会删除它，使用前请确认文件可以公开。

凭证模式默认为 `auto`：本机存在完整的标准 `ALIBABA_CLOUD_ACCESS_KEY_ID`/`ALIBABA_CLOUD_ACCESS_KEY_SECRET` 时优先使用阿里云默认凭证链；否则，配置了 `ZHIKUN_OSS_ECS_ROLE_NAME` 时使用 **ECS RAM Role + IMDSv2**；两者都没有时仍尝试默认凭证链（例如阿里云 CLI 默认 Profile）。因此同一套非密钥配置可同时支持本地一键启动和 ECS。每个部署者都必须使用自己的 Bucket 与最小权限身份；仓库不包含维护者的 OSS 凭证。

在 `.env` 中配置：

```bash
ZHIKUN_OSS_ENABLED=true
ZHIKUN_OSS_ENDPOINT=https://oss-cn-your-region.aliyuncs.com
ZHIKUN_OSS_REGION=cn-your-region
ZHIKUN_OSS_BUCKET=your-bucket
ZHIKUN_OSS_PUBLIC_BASE_URL=
ZHIKUN_OSS_PREVIEW_ENABLED=false
ZHIKUN_OSS_PREFIX=zhikuncode-artifacts
ZHIKUN_OSS_ECS_ROLE_NAME=your-ecs-ram-role
ZHIKUN_OSS_CREDENTIAL_MODE=auto
ZHIKUN_OSS_MAX_FILE_BYTES=104857600
ZHIKUN_OSS_CONNECT_TIMEOUT_MS=10000
ZHIKUN_OSS_REQUEST_TIMEOUT_MS=120000
```

可选自定义域名与预览：先自行完成目标 Bucket 的域名绑定、DNS 和 HTTPS 配置，再将 `ZHIKUN_OSS_PUBLIC_BASE_URL` 设为自己的 HTTPS 域名根地址（例如 `https://files.example.com`，不带端口、路径前缀、查询参数或片段）。产物发布、截图粘贴和远程文件引用会统一使用该域名，上传 Endpoint 不变。设置 `ZHIKUN_OSS_PREVIEW_ENABLED=true` 后，仅新上传的 HTML、纯文本、PDF、PNG/JPEG/GIF/WebP 使用 `inline`，其他类型仍作为附件；开启预览必须配置自定义域名，不能使用 OSS 默认域名。默认留空且关闭时保持原下载行为。

OSS 关闭时，无效的自定义域／预览配置会产生警告并从 CSP 省略，不阻断启动；开启时仍会阻止启动。合法自定义域在 OSS 关闭时仍保留在 CSP 中，以支持历史图片。发布入口始终保留严格配置校验。剪贴板 API 会规范化 HEAD 返回的具体图片 MIME（统一大小写、去除参数），保留有效 CDN 转码类型；其他类型回退到文件字节检测结果。客户端声明的图片类型仍须匹配上传内容。

本地部署可先配置阿里云 CLI 默认 Profile；也可将受限 RAM 用户或临时 STS 的 `ALIBABA_CLOUD_ACCESS_KEY_ID`、`ALIBABA_CLOUD_ACCESS_KEY_SECRET` 与可选 `ALIBABA_CLOUD_SECURITY_TOKEN` 写入本机 `.env`。不要提交真实值。旧式 `OSS_ACCESS_KEY_ID`、`OSS_ACCESS_KEY_SECRET`、`OSS_SESSION_TOKEN` 会被拒绝。

> 身份只应具有目标 Bucket/前缀所需的最小 `PutObject`、`GetObject/HeadObject`、`PutObjectAcl` 和失败清理 `DeleteObject` 权限；Bucket 必须允许目标对象设置 `public-read`，否则上传会失败并尝试清理私有对象。

使用流程：

1. 在已授权项目中准备要发布的单个文件并检查内容。有匹配 Manifest 时，目标条目必须已验证且哈希一致；没有 Manifest 时按工作区文件策略检查。
2. 明确输入 `/publish-oss <文件路径>`，或说明“上传刚生成的产物到 OSS”。
3. 先确认文件和公开范围；如当前权限模式要求审批，核对卡片后决定是否批准。`AUTO_APPROVE` 可自动通过权限裁决。
4. 上传成功后使用发布成功卡片打开预览或下载；以浏览器实际响应为准，不让模型重新拼接链接。

安全边界与当前限制：

- 只发布 workspace 内单个普通文件，默认不超过 100 MiB；有匹配 Manifest 时复核其归属、条目状态和哈希。无 Manifest 的工作区文件回退不等于已经做过运行验证。
- 拒绝目录、批量上传、符号链接、workspace 外路径、`.env`、私钥、数据库、凭证配置及检测到敏感内容的文件。
- 对象先私有上传，远端校验成功后才切换为 `public-read`；失败时清理本次新建的私有对象。
- 返回地址为**永久公开链接**。卡片依据公开地址的响应判断预览或下载；检查失败时保守显示“下载”，不会撤销已成功的发布。OSS 默认域名通常会下载 HTML。
- 旧对象的内容、元数据及历史消息链接不会迁移；重复发布旧对象可返回当前配置域名，但原附件仍可能下载。关闭预览也不会恢复已上传对象的元数据。
- HTML 预览会执行页面脚本。分享域不得共享应用登录 Cookie 或加入应用认证接口的跨域白名单；同站子域名不等于完整安全隔离。注意 HTTPS 证书续签，不承诺所有浏览器均可预览所有格式。
- 当前精简版不提供批量发布、发布历史或撤销入口；删除公开对象需要运维人员在 OSS 侧显式执行。
- 本地与 ECS 都支持真实上传；本地使用默认凭证链，ECS 建议使用自动轮换的 RAM Role 临时凭证。
- 远程文件引用使用原始请求体流式上传，不扩大 Spring 的全局 multipart 限制；若 ECS 前还有反向代理，代理的请求体上限也必须不低于 `ZHIKUN_OSS_MAX_FILE_BYTES`。

秒悟 `/publish-meoo` 的策略不同：要求绑定实际通过的 VerifyJourney 证据、项目目录、运行方式和发布快照，发布前复检；每次创建新站点并消耗平台额度。配置与步骤见[秒悟发布指南](docs/deployment/meoo.md)。

### 支持的 LLM 服务商

ZhikunCode 支持**多 Provider 同时配置**（推荐）和单 Provider 两种模式。多 Provider 模式下可在前端自由切换模型：

**方式一：多 Provider 配置（推荐）**

在 `.env` 中为每个服务商配置独立的 API Key，前端可自由切换：

```bash
# DashScope（千问系列）
LLM_PROVIDER_DASHSCOPE_API_KEY=your-dashscope-key

# DashScope Token Plan（百炼订阅专属 sk-sp- Key，与按量计费 Key 分离）
LLM_PROVIDER_DASHSCOPE_TOKEN_PLAN_API_KEY=your-token-plan-key

# DeepSeek
LLM_PROVIDER_DEEPSEEK_API_KEY=your-deepseek-key

# Moonshot (Kimi)
LLM_PROVIDER_MOONSHOT_API_KEY=your-moonshot-key

# Kimi Code 订阅：K3.1 Pro Highspeed + K2.8 Preview（与 Moonshot 按量渠道独立）
LLM_PROVIDER_KIMI_CODE_API_KEY=your-kimi-code-key
# LLM_PROVIDER_KIMI_CODE_MODELS=k3.1-pro-highspeed,kimi-for-coding

# Zhipu (智谱 GLM)
LLM_PROVIDER_ZHIPU_API_KEY=your-zhipu-api-key-here

# MiniMax
LLM_PROVIDER_MINIMAX_API_KEY=your-minimax-api-key-here

# ZenMux（默认目录：Fable 5.1 / Opus 5.5 / Sonnet 5.5 / Haiku 5.5 / GPT-5.6 Sol / GPT-6 Astra / Gemini 4 Argon / Grok 4.7 / Muse Wonder 1.0）
# 支持逗号分隔多 Key：订阅 Key（sk-ss-v1- 前缀）放前优先、按量 Key（sk-ai-v1- 前缀）兜底；
# 402 quote_exceeded / 404 model_not_available 时自动冷却 15 分钟切换到下一把 Key。
LLM_PROVIDER_ZENMUX_API_KEY=your-zenmux-api-key-here
```

**Kimi Code 订阅：** 配置独立的 `LLM_PROVIDER_KIMI_CODE_API_KEY` 后，仓库默认注册 `k3.1-pro-highspeed` 与 `kimi-for-coding`，请求端点为 `https://api.kimi.com/coding/v1`；Moonshot 按量渠道默认 `kimi-k3.1-pro-highspeed`，密钥、模型 ID 与路由分开，不自动跨渠道切换。订阅模型的流式与非流式请求均显式发送 `thinking.type=enabled`、`reasoning_effort=max`。注册表声明 1,048,576 token 上下文和 131,072 token 输出上限，主循环推荐输出预算另按 65,536 token 上限裁剪；这是应用配置，不代表账号必然有相应权限。订阅用量不按 Moonshot 按量价格估算，界面估算为 0 不代表免费。仅配置 Kimi Code Key 仍会被当前 Docker 入口检查拒绝，先采用本地开发路径。历史案例的 Kimi K3 保持原记录。模型资料参见 [Kimi Code 官方模型文档](https://www.kimi.com/code/docs/kimi-code/models.html)。

**GLM-5.3（百炼）：** 复用 `LLM_PROVIDER_DASHSCOPE_TOKEN_PLAN_API_KEY`，默认模型列表已包含 `bailian/glm-5.3`；若 `.env` 自定义了 `LLM_PROVIDER_DASHSCOPE_TOKEN_PLAN_MODELS`，请将该 ID 加入列表并重启。内部模型 ID 为 `bailian/glm-5.3`，仅发往百炼 Token Plan 的请求使用上游 ID `glm-5.3`，现有 `glm-5.3` 仍走智谱直连。百炼配置中填写裸 `glm-5.3` 也会自动规范化并去重，避免注册顺序影响路由。流式与非流式均固定 `enable_thinking=true`、`reasoning_effort=max`、`clear_thinking=false`，配置最大 **1,000,000 tokens** 上下文和 65,536 tokens 单次输出预算（已实测接受的应用预算，非官方最大输出声明）；支持工具调用与历史思考回传，文本模型不开放图片输入。订阅 Credits 以百炼账单为准，界面 0 单价不代表免费。2026-09-21 核对：[Token Plan 支持模型](https://help.aliyun.com/zh/model-studio/token-plan-personal-overview)、[GLM 参数](https://help.aliyun.com/zh/model-studio/glm)、[官方上下文配置](https://help.aliyun.com/zh/model-studio/openclaw)。

**方式二：单 Provider 配置（向后兼容）**

如未配置多 Provider，系统自动回退到单 Provider 模式。在 `.env` 中配置 `LLM_BASE_URL` 和 `LLM_API_KEY` 即可切换：

| 服务商 | Base URL | 推荐模型 | 备注 |
|--------|----------|----------|------|
| **千问/DashScope** | `https://dashscope.aliyuncs.com/compatible-mode/v1` | qwen3.8-max-0902 | 按量计费 Provider，国内直连 |
| **阿里云百炼 Token Plan** | `https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1` | qwen3.8-max / qwen3.8-flash / deepseek-v4.1-flash | 使用独立 `sk-sp-` Key；模型均标注“百炼”。V4.1 Flash 为服务端 `app.model.default`、快速查询及图片兜底的默认值；前端初始模型偏好仍为 `qwen3.8-max-0902`，用户配置／会话选择优先 |
| **DeepSeek** | `https://api.deepseek.com/v1` | deepseek-flash | 国内直连；DeepSeek V4.1 Flash，支持思考、工具调用与原生视觉 |
| **Moonshot（Kimi）** | `https://api.moonshot.cn/v1` | kimi-k3.1-pro-highspeed | 独立 Moonshot 按量 Key；当前注册表配置视觉和 1M 上下文 |
| **Zhipu（智谱 GLM）** | `https://open.bigmodel.cn/api/paas/v4/chat/completions` | glm-5.3, glm-5.3-flash | 国内直连 |
| **MiniMax** | `https://api.minimax.chat/v1` | MiniMax-M3 | 百万上下文 |
| **ZenMux（多模型中转）** | `https://zenmux.ai/api/v1` | anthropic/claude-fable-5.1 / anthropic/claude-opus-5.5 / anthropic/claude-sonnet-5.5 / anthropic/claude-haiku-5.5 / openai/gpt-5.6-sol / openai/gpt-6-astra / google/gemini-4-argon / x-ai/grok-4.7 / meta/muse-wonder-1.0 | OpenAI/Google/xAI/Meta 模型使用 Responses 推理与工具续轮；支持图片 |
| **OpenAI** | `https://api.openai.com/v1` | gpt-5.6-sol / gpt-5.4-mini | 需要外网访问 |
| **本地 Ollama** | `http://localhost:11434/v1` | 支持所有 Ollama 模型（ollama/*） | 完全离线 |

> 任何兼容 OpenAI API 格式的服务商都可以接入，只需配置对应的 Base URL 和 API Key。

### 可选：启用 DashScope 托管 MCP 服务

ZhikunCode 当前预置了 17 个阿里云百炼 MCP 服务、共 83 个白名单工具。运行时采用服务级最小化默认策略：智谱联网搜索、高德地图、千问图像和万相视频默认开启；新闻、法律、企业信息、文本审核、金融、学术、采购、物流、行情和行业洞察等 13 个低频或高风险服务默认关闭。没有配置百炼 Key 时会跳过远端连接，不影响核心对话、代码编辑和本地工具使用。

默认关闭或被用户关闭的 MCP 不建立连接、不注册工具，也不会向模型上下文注入工具描述；直接调用会被拒绝。点击页面顶部的 **MCP** 徽章可打开 **MCP 管理**，按服务即时启用或关闭，选择保存在本机且无需重启。

| MCP 服务 | 传输 | 能力 | 工具数 |
|---------|------|------|-------|
| 高德地图 | SSE | 地理编码、IP 定位、天气、搜索、距离与四类路线规划等 | 13 |
| 千问-图像生成（QwenImage） | HTTP | 文生图、单图编辑、多图融合 | 3 |
| 万相-视频生成（WanVideo） | HTTP | 文生视频、图生视频、数字人、首尾帧视频及结果查询 | 8 |
| OneKey 新闻查询 | HTTP | 频道、热榜、按频道或标题检索新闻 | 3 |
| OneKey 得理法律数据 | HTTP | 法规与类案检索 | 2 |
| OneKey 上奇企业工商 | HTTP | 企业匹配、工商、股东、人员、投资、分支、联系方式 | 7 |
| OneKey 文本内容审核 | HTTP | 违规、推广、辱骂、低质和广告法风险审核 | 1 |
| `zhipu-websearch` | SSE | 智谱联网搜索 Pro | 1 |
| 东方财富妙想 | HTTP | 股票行情、公告、研报、财务、资金与市场数据查询 | 11 |
| 企业知识产权 | HTTP | 企业知识产权信息查询 | 1 |
| 万方文献 | HTTP | 学术文献检索与详情查询 | 2 |
| arXiv 论文 | SSE | 论文搜索、详情、作者与分类查询 | 4 |
| 1688 选品 | HTTP | 商品选品与货源查询 | 1 |
| 物流快递 | SSE | 快递物流查询与轨迹跟踪 | 2 |
| 贵金属行情 | HTTP | 黄金、白银等贵金属行情与市场数据 | 6 |
| 连锁商业 / 茶饮洞察 | HTTP | 连锁品牌、门店、商圈与茶饮市场洞察 | 8 |
| 旅游洞察 | HTTP | 景区、客流、酒店与旅游市场数据分析 | 10 |

**如需使用**（需要阿里云百炼 API Key，并在控制台开通相应 MCP 能力；部分服务调用可能产生费用，具体以百炼控制台为准）：

1. 在 `.env` 中配置 DashScope Key：
   ```bash
   LLM_PROVIDER_DASHSCOPE_API_KEY=sk-xxxxxxxx
   ```
2. 修改 `.env` 后按原部署方式重启：Docker 用原 Compose 文件与 override 执行 `up -d` 应用配置；手动启动者重新加载 `.env` 后重启后端；使用启动脚本的用户才执行 stop／start（脚本会清理固定端口）。
3. 在页面顶部打开 **MCP 管理**，仅启用当前任务需要的服务；后续开关即时生效，无需重启。

> 注册表中的 `"enabled": true` 表示该工具进入管理员白名单，不等于服务在运行时默认开启。工具必须同时通过注册表白名单、服务开关和凭证可用性三层检查。

---

<a id="swe-bench"></a>
## 🏆 SWE-bench Lite 评测

2026-05 历史运行使用 `qwen3.7-max` 和六个工具，无子 Agent；官方 harness 结果为 **168 / 300（56.0%）Resolved**，284 / 300 生成 Patch。公开产物见 [历史归档](docs/swe-bench/20260525/)。

**至少一例公开轨迹包含对应的已合并修复，不能将成绩无条件视为未接触参考修复的独立完成率。** [`sympy__sympy-11897`](docs/swe-bench/20260525/trajs/sympy__sympy-11897.md) 第 37、39 次 Bash 输出包含参考修复，源码修改块与最终预测补丁相同；实际工具输入记录为 `{}`，不能据此判断来自实时联网还是已有材料，也不能推断其他实例无效。原说明指向的 `swe-bench/swe_bench.py` 未纳入 Git；仓库另有[评测脚本](docs/swe-bench/scripts/swe_bench.py)，但它与历史材料的预算和版本条件不同，公开材料不足以严格重跑原环境。

### 关键指标

| 指标 | 数值 | 出处 |
|---|---|---|
| Resolved Instances | **168 / 300 (56.0%)** | `docs/swe-bench/20260525/results.json` `resolved_instances=168` |
| Patch 生成率 | **284 / 300 (94.7%)** | `all_preds.jsonl`（其中 16 条空 patch） |
| 历史主干模型（保留原记录） | `qwen3.7-max` | `docs/swe-bench/20260525/metadata.yaml` |
| 工具闭集 | Read / Edit / Write / Bash / Grep / Glob | 历史提交说明与公开轨迹 |
| 单实例预算 | 历史材料口径不同 | 原主 README、归档说明和当前脚本不一致，不能用当前默认值反推历史条件 |
| 说明中的并发 worker | 1 | 历史说明，不作为当前部署默认值 |
| 网络 / Sub-agent | 无子 Agent；联网状态不能仅凭提示声明认定 | 已发现参考修复内容，详见上文 |
| 提交命名空间 | `20260525_zhikuncode` | `metadata.yaml` |

### 仓库级表现（`results/resolved_by_repo.json`）

| Repository | Resolved / Total | Resolve Rate |
|---|---|---|
| mwaskom/seaborn | 3 / 4 | **75.0%** |
| django/django | 82 / 114 | **71.9%** |
| astropy/astropy | 4 / 6 | **66.7%** |
| sympy/sympy | 42 / 77 | 54.5% |
| scikit-learn/scikit-learn | 12 / 23 | 52.2% |
| pytest-dev/pytest | 8 / 17 | 47.1% |
| pydata/xarray | 2 / 5 | 40.0% |
| matplotlib/matplotlib | 8 / 23 | 34.8% |
| psf/requests | 2 / 6 | 33.3% |
| sphinx-doc/sphinx | 4 / 16 | 25.0% |
| pylint-dev/pylint | 1 / 6 | 16.7% |
| pallets/flask | 0 / 3 | 0.0% |
| **Total** | **168 / 300** | **56.0%** |

### 工程亮点（每一项均可在源码定位）

当前主干可查阅 [ContextCascade](backend/src/main/java/com/aicodeassistant/engine/ContextCascade.java) 和 [QueryEngine](backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java) 的上下文管理、工具循环与恢复策略；这是当前实现说明，不能据此推断历史运行器启用了全部机制。[自纠错（SelfCorrectionLoop）](backend/src/main/java/com/aicodeassistant/engine/correction/SelfCorrectionLoop.java) 为可选开关，默认关闭。

[技术报告](https://zhikunqingtao.github.io/zhikuncode/swe-bench-report.html) · [结果数据](docs/swe-bench/20260525/results.json) · [预测补丁](docs/swe-bench/20260525/all_preds.jsonl) · [原始轨迹](docs/swe-bench/20260525/trajs/)

<a id="evidence-integrity"></a>
<details>
<summary>公开证据的范围与文件校验</summary>

黄金监控与 12306 案例保留评分 JSON、公开日志摘录和图片；黄金完整原始日志和产物 ZIP 未公开，12306 的 HTML 可直接打开，另一实现的公开源码不等于已部署演示。MOBA 有第一方代码、截图与派生视频预览，不把预览称为原始录屏。

两份原 SHA-256 清单对应 `cd9261c7`（2026-07-28）；[该历史版本](https://github.com/zhikunqingtao/zhikuncode/tree/cd9261c755ecda8fc9528cca6383a7f375b532b4/docs/case-studies)的黄金 10/10、12306 21/21 文件匹配。当前 HTML 和共用案例 README 有后续导航／说明变更，不再逐字匹配原清单；已核对的评分 JSON、日志摘录、图片和 12306 产物保持匹配。不要把当前整包写成全部通过，也不要将哈希一致当成业务内容或评分正确的证明。

原始材料：[黄金证据](docs/case-studies/zhikuncode-codex-gold-monitor-evidence.json) · [12306 证据](docs/case-studies/zhikuncode-codex-12306-evidence.json) · [黄金清单](docs/case-studies/zhikuncode-codex-gold-monitor-SHA256SUMS.txt) · [12306 清单](docs/case-studies/zhikuncode-codex-12306-SHA256SUMS.txt)。

从仓库根检查当前文件：

```bash
cd docs/case-studies
shasum -a 256 -c zhikuncode-codex-gold-monitor-SHA256SUMS.txt
shasum -a 256 -c zhikuncode-codex-12306-SHA256SUMS.txt
```

报告和共用 README 的已知差异会返回校验失败；不要重生成清单来掩盖这些差异。

</details>

---

## 📊 竞品对比

### 功能对比

产品能力和版本变化较快，本节以公开同题任务和明确条件作比较；单次作品与评分不等于产品总体排名。

| 同题任务 | 已公开的比较 | 解读范围 |
|---|---|---|
| 黄金监控 | ZhikunCode 68.3、Codex 68.4；14 个维度 | 接近；模型不同，报告保留各自缺陷 |
| 12306 概念可视化 | ZhikunCode 8.68、Codex 8.45；单 HTML 与 Next.js 工程 | 差距小于报告区分阈值；公开源码不等于都已部署 |
| 宇树办公任务链 | 五款工具、六任务、30 件终稿 | ZhikunCode 与千问两榜均处近分组；ZhikunCode 六件需修正后采用 |
| 八方代码审查 | 同一提交的八份报告，附复核与复算材料 | 对比具体有效发现、误报和漏检，不外推总体能力 |

报告入口见[公开工程案例](#cases)。

<a id="adoption"></a>
### 安全特性对比

采用时应逐项核对部署条件，而不把授权检查、容器部署和操作系统沙箱视为同一能力：

| 评估项 | ZhikunCode 当前行为 |
|---|---|
| 自托管与数据 | Java／Web 工作台自托管，运行数据保存于 SQLite 和本地文件；模型及外部服务接收相应输入 |
| 执行控制 | 项目授权、工具网关、持久审批、运行取消；新会话默认完全访问 |
| 隔离 | worktree 隔离代码目录；可选 Docker 命令沙箱默认关闭且不覆盖全部路径 |
| 账号边界 | 需配置入口认证与网络控制；实例访问控制不等于多租户隔离 |

#### ZhikunCode 差异化能力展示

| 能力维度 | 可核查的实现与使用入口 |
|---|---|
| 长任务推进 | 上下文级联、分级恢复、多 Agent 分工和结果接续；见[架构](#how-it-works)及[Agent](#agents) |
| 行为与交付检查 | 浏览器／HTTP 断言、实际工具输出、逐次修改、证据与产物记录；验证依赖须就绪并实际调用 |
| 浏览器操作 | 桌面与手机查看任务、补充要求、处理审批、检查结果；重连恢复记录，重启不续执行栈 |
| 扩展 | Markdown Skill、Java SPI 插件、MCP 服务和可配置模型；各入口有独立启用条件 |

---

<a id="️-架构概览"></a>
<a id="how-it-works"></a>
## 🏗️ 架构概览

ZhikunCode 采用三端分离架构，Java 后端负责核心编排，React 前端提供交互界面，Python 服务处理代码分析：

```
┌──────────────────┐      WebSocket / HTTP      ┌──────────────────────┐
│    Frontend       │ ◄────────────────────────► │      Backend          │
│  React 18 + TS    │                            │  Java 21 + Spring    │
│  Vite + Tailwind  │                            │  Boot 3.4            │
│  :5173 (dev)      │                            │  :8080               │
└──────────────────┘                            └──────────┬───────────┘
                                                           │ HTTP
                                                           ▼
                                                ┌──────────────────────┐
                                                │   Python Service      │
                                                │   FastAPI + Uvicorn   │
                                                │   :8000               │
                                                └──────────────────────┘
```

### 各层职责

| 层 | 技术栈 | 职责 |
|----|--------|------|
| **后端** | Java 21, Spring Boot 3.4.x, WebSocket, SQLite | 核心编排引擎、LLM API 路由、Agent 管理、内置工具与 MCP 动态扩展、统一授权网关、会话持久化 |
| **前端** | React 18, TypeScript 5.6, Vite 5, TailwindCSS, Monaco Editor, xterm.js, Zustand | 对话式交互 UI、代码编辑器、内置终端、文件浏览器、设置面板、实时流式输出、委派结果查看 |
| **Python 服务** | FastAPI, Uvicorn, Python 3.11/3.12 | 代码分析、AST 解析、MCP 工具桥接 |

### Docker 部署架构

生产环境通过 Docker 单容器部署。镜像包含 Java 后端、内置静态前端和 Python 运行时；基础 Compose 默认只启动 Java 进程，受管 Python 服务需通过显式的环境变量/override 启用：

```
┌─────────────────────────────────────────────────┐
│                Docker Container                  │
│  ┌───────────┐  ┌───────────┐  ┌──────────────┐ │
│  │  Backend   │  │  Python*  │  │   Frontend   │ │
│  │  :8080     │  │  :8000    │  │  (静态文件)   │ │
│  └───────────┘  └───────────┘  └──────────────┘ │
│                                                  │
│  Volume: zhikun-data (SQLite + 会话数据)          │
│  Volume: workspace (用户项目代码)                  │
├──────────────────────────────────────────────────┤
│  Port: 8080 → 宿主机                              │
└──────────────────────────────────────────────────┘
```

`*` Python 服务为可选的容器内受管进程，不对外暴露端口，也不由基础 Compose 默认启动。

### 核心引擎（Core Engines）

ZhikunCode 的智能决策由以下核心引擎协同驱动：

| 引擎 | 职责 | 关键特性 |
|------|------|----------|
| **查询引擎（QueryEngine）** | Agent 决策与工具执行编排 | 多步迭代循环，驱动完整 Agent Loop |
| **授权服务（AuthorizationService）** | 统一策略与授权裁决 | 系统不变量、权限模式、Grant 匹配、持久交互 |
| **操作分析器（OperationAnalyzerRegistry）** | 工具语义与风险分析 | 文件、Bash、网络、MCP/动态工具分类 |
| **工具授权网关（ToolExecutionGateway）** | 工具执行唯一入口 | 执行槽、最终授权复检、结构化执行结果 |
| **权限 Grant 仓库（PermissionGrantRepository）** | 授权保存、匹配和撤销 | RUN/SESSION/WORKSPACE 作用域与父子 Agent 继承 |
| **上下文级联（ContextCascade）** | 上下文压缩与恢复 | 6层级联 + 413两级上下文压缩 |
| **图片注入器（ImageRefInjector）** | 图片引用安全注入与预算管理 | 候选选取、动态预算、完整性校验和去重 |
| **Token预算守卫（TokenBudgetGuard）** | 两阶段 Token 预算控制 | 历史 Base64 预清理 + 最终 payload 梯度降级 |
| **工具执行管线（ToolExecutionPipeline）** | 工具执行全生命周期 | 校验、输入冻结、授权、执行和结果归一化 |
| **自纠错循环（SelfCorrectionLoop）** | 可选错误诊断与修复提示 | 默认关闭；配置启用后仍有尝试次数和运行预算限制 |
| **RunControlService** | Run 生命周期 CAS | 状态原子更新，COMPLETED/FAILED/CANCELLED/INTERRUPTED 单一终态权威 |
| **RunExecutionRegistry** | 活跃 Run 执行注册 | 以 Session 映射内存活跃 Run、输入队列与取消上下文；不承担 checkpoint 持久化 |
| **DurableInteractionService** | 持久交互 | Inbox、ACK、服务端 deadline、CAS 终态与重连恢复 |
| **Project 工作空间服务** | Project 选择与信任边界 | 持久授权、真实路径规范化、Session 工作目录固定、符号链接/别名/重绑定及越界复检 |
| **Artifact Manifest 与发布策略** | 产物归属、完整性与发布检查 | Manifest 登记文件并校验哈希；OSS 有普通工作区文件回退，秒悟强制绑定通过的运行验证证据与发布快照 |
| **BestEffortObservabilityRecorder** | 补充观测故障隔离 | 有界异步事件队列、丢弃/写失败指标；记录失败不反向破坏主执行链 |
| **日志关联与恢复投影** | 诊断关联和断线恢复 | MDC 提供 `sid/rid/prid/agent/turn/tool/llm`；完整 Session 快照、Run event sequence、活跃工具状态及持久交互重放恢复前端 |

<a id="-agent-主循环"></a>
### Agent Loop 查询循环

ZhikunCode 的 QueryEngine 通过多步迭代循环驱动 Agent 决策与工具执行：

```
压缩级联（ContextCascade）→ 流式会话创建 → API 调用（含熔断、自适应重试和降级保护）→ 响应收集 → 工具结果消费（ToolExecutionPipeline）→ 终止评估 → 工具摘要注入 → 状态更新
```

**关键子系统：**

| 组件 | 职责 | 配置项 |
|------|------|--------|
| IncrementalCollapseManager | 当前按代码常量每 10 轮尝试增量折叠 | 同名 YAML 数值未绑定，不能假定修改配置即可调整 |
| ContextCascade | 六层压缩级联（Snip→MicroCompact→ContextCollapse→AutoCompact→CollapseDrain→ReactiveCompact） | `context.cascade.*` |
| MicroCompactService | 清除旧工具结果内容，保护近期内容 | 主循环调用；CACHED_MICROCOMPACT 仅控制提示段 |
| ModelTierService | 模型降级与冷却；无 retryAfter 时默认 30 分钟，恢复还取决于后续成功调用 | `app.model.tier-chain` |

**413 恢复**：由 [QueryEngine.java](backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java) 编排，先尝试两级上下文压缩：
1. **Level 3** — CollapseDrain 激进压缩，按当前恢复预算收缩历史
2. **Level 4** — ReactiveCompact 反应式压缩，进一步缩减历史并保留必要上下文

若仍失败且错误与媒体相关，再尝试移除媒体块；恢复耗尽后释放错误并终止，不继续落入普通模型降级。两级压缩不等同于完整错误恢复链路。

收到上游明确的 token 上下文超限响应后，系统会按被拒请求的历史估算量，为本次执行中的实际模型收紧恢复预算；限制不带入下一次执行。恢复保留用户原文与完整工具事务；必要内容仍无法满足恢复目标时会停止恢复并提示新开会话。[恢复实现](backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java) · [回归用例](backend/src/test/java/com/aicodeassistant/engine/QueryEngineUnitTest.java)。

<details>
<summary><b>六层压缩级联（ContextCascade）详解</b></summary>

| 层级 | 名称 | 语义 | 触发条件 |
|------|------|------|----------|
| L0 | Snip（首选裁剪） | 按模型窗口预算截断过长工具结果 | 每轮检查工具结果预算 |
| L1 | MicroCompact（微压缩） | 清除旧工具结果并保护近期内容 | 按保留尾部规则处理 |
| L1.5 | ContextCollapse（渐进折叠） | 渐进折叠历史；增量折叠另以代码固定的 10 轮 Agent 循环阈值判断是否触发 | 启用对应机制时 |
| L2 | AutoCompact（自动压缩） | 系统自动压缩全局上下文 | 常规阈值突破 |
| L3 | CollapseDrain（激进压缩） | 按恢复预算激进压缩 | 上游上下文拒绝后的第一阶段 |
| L4 | ReactiveCompact（反应式压缩） | 进一步缩减历史并保留必要上下文 | 第一阶段仍不足时 |

源码：`ContextCascade.java`

</details>

---

<a id="security"></a>
## 🔒 安全架构

工具执行由授权、风险分析和具体执行路径共同约束。新会话默认完全访问（`AUTO_APPROVE`）；需人工确认时应先切换权限模式。授权控制、Git worktree 与 Docker 沙箱分别解决不同问题。

<a id="8-层-bash-安全沙箱"></a>
### Bash 检查与可选命令沙箱

| 环节 | 当前行为与边界 |
|---|---|
| 命令与资源分析 | 解析命令、识别风险及资源，拒绝明确禁止的操作；不等于能识别所有 Shell 行为 |
| 工具授权 | 按权限模式、Grant 与交互裁决；完全访问可自动批准到达授权阶段的高风险操作 |
| 执行前检查 | 复核对应执行路径的目录、授权状态及运行准入；各工具分析器的复检范围不同 |
| 进程与输出 | 受管执行有超时、终止和输出限制；取消回执不等于所有进程已经退出 |
| Docker 命令沙箱 | 默认关闭；开启后还需 Docker 可用及执行路由支持，不覆盖所有后台／工具路径 |
| 记录与诊断 | 工具结果、运行事件与审计记录帮助追踪；记录存在不证明业务结果正确 |

源码：[BashTool](backend/src/main/java/com/aicodeassistant/tool/impl/BashTool.java) · [授权服务](backend/src/main/java/com/aicodeassistant/authorization/AuthorizationService.java)。

### 统一工具授权流程

核心工具通过统一管线与网关进行授权，具体检查按工具类型实现：

```text
Schema/Tool 校验
→ 规范化输入冻结
→ Operation Analyzer
→ 系统安全不变量
→ Grant / 权限模式 / 用户交互裁决
→ 获取执行并发槽
→ 路径、符号链接、环境和授权状态最终复检
→ Tool Gateway 执行
→ 结构化 Effect / Result / 审计事件
```

- 工具使用已冻结的规范输入执行，授权后修改原始参数不会改变实际执行内容。
- 高风险操作只能选择 ONCE，不生成可复用 Grant。
- Bash 的 SAFE／GUARDED 操作可记住 RUN／SESSION 工具范围授权，后续仍核对工具语义、风险和约束；不是只匹配原命令字符串，不支持 WORKSPACE。
- 受约束的文件读取和编辑可以选择 SESSION/WORKSPACE。
- 未知 MCP 和动态工具默认只能 ONCE；只有专用 Analyzer 才能产生可复用授权。
- `network-v1` 和 `mcp-v1` 的 SAFE/GUARDED 操作可选择 RUN/SESSION：保存的 Grant 仅匹配同一工具，输入值可变化；不支持 WORKSPACE，HIGH 风险仍只能 ONCE。
- Grant 不能覆盖保护路径、符号链接逃逸、敏感环境变量和执行前动态变化。
- 用户决策以 SQLite 中的 Interaction CAS 终态为唯一权威。

### 文件夹选择与持久授权

Web 新会话必须先选择一个已授权目录。远程和 Docker 部署的目录浏览只展示服务端配置允许的根目录及其子目录；启用本地 picker 的直连本机部署中，内置目录浏览器从默认 workspace 起始，可浏览本机服务进程可读的目录。macOS 和 Windows 还会在系统能力可用时显示原生文件夹选择按钮，系统对话框通常从桌面目录开始；Linux 继续使用内置目录浏览器。Docker 中宿主机的 `WORKSPACE_PATH` 对应容器内的 `/app/workspace`。浏览器不能把客户端本地路径直接当作远程服务端路径。

- Project 是一个全局、持久、可撤销的信任范围和默认相对路径根，并不是通用的操作系统文件沙箱；专用文件工具以 Session 的规范化工作目录解析相对路径，范围外路径另行授权并复检。
- DEFAULT 模式下，Project 内的普通文件操作免于重复确认；Project 外的普通请求进入常规授权流程，并可在策略允许时记住授权；敏感文件和高风险操作仍需逐次批准。
- 撤销 Project 会停止后续普通写入的持久自动授权。已有 Session 仍以原目录作为默认相对路径根；安全读取仍按 Session 策略执行，其他受控操作重新进入正常授权流程。
- Session、Query 和文件搜索使用 `projectId` 或 `sessionId`；客户端提交任意 `workingDirectory` 会被拒绝。
- 普通目录和 Git 子目录都可授权；只有所选目录本身就是 Git worktree 根目录时，才提供内置仓库上下文和 Git slash 命令。Bash 不是目录沙箱，但始终走独立的命令授权，不会因 Project 选择而自动获准。
- 后端的 `ZHIKUN_LOCAL_PICKER_ENABLED` 安全默认值为 `false`。使用 `./start.sh` 本地快速启动时，如果 `ZHIKUN_WORKSPACE_ALLOWED_ROOTS` 和该变量均无非空配置，脚本会为直连本机桌面场景启用 picker；显式设置 `false` 或配置 allowed roots 时不会被覆盖或隐式启用。远程、反向代理和生产部署应显式保持关闭并配置 allowed roots。
- Python 的 `WORKSPACE_ROOT` 是相对路径的默认锚点。配置 `ZHIKUN_WORKSPACE_ALLOWED_ROOTS` 后，Python 路由同样严格限制在这些根目录内；只有未配置 allowed roots 且显式启用本机 picker 的 loopback 开发模式，才允许分析用户选择的其他绝对路径。

### 授权范围与子 Agent 继承

| 范围 | 生命周期 | 子 Agent 行为 |
|------|----------|---------------|
| ONCE | 当前一次操作 | 不持久化 |
| RUN | 当前直接执行 Run | DIRECT_ONLY，不被兄弟或后代继承 |
| SESSION | 当前根会话 | 根 Agent 及其后代可在相同约束下复用 |
| WORKSPACE | 当前规范化工作区 | 根会话及后代可复用受约束的文件能力 |

`ONCE` 是不写入 Grant 仓库的一次性授权决策；`RUN`、`SESSION`、`WORKSPACE` 才是三种持久 Grant 范围。

子 Agent 使用 root session、root run 和 actor ancestry 构成的授权主体匹配父会话 Grant。继承只复用相同工具语义和约束，不能扩大路径、命令、风险等级或资源范围。

### 权限模式

| 模式 | 实际行为 |
|------|----------|
| DEFAULT | 已授权 Project 内的普通文件编辑自动允许；其他无可复用 Grant 的受控操作请求用户确认 |
| PLAN | 允许安全工作区读取及列入 SAFE_INTERNAL 的内部操作；拒绝其他受控 Effect |
| ACCEPT_EDITS | 自动允许工作区内非高风险文件编辑，其他受控操作仍需确认 |
| DONT_ASK | 不创建交互；安全读取、已有 Grant 和已授权 Project 内普通文件操作可执行，其他需要交互的操作直接拒绝 |
| AUTO_APPROVE | 自动批准到达人工授权阶段的工具操作，包括工作区外文件、公共互联网请求和秒悟发布；秒悟发布仍强制验证证据绑定、快照一致性与内容安全检查；硬拒绝、安全 Hook、SSRF 防护和部署沙箱仍然生效 |

新建 Web、REST 和合并会话默认使用完全访问（`AUTO_APPROVE`），创建接口显式传入其他合法权限模式时使用该模式。已有会话的 REST 查询沿用保存的权限；若显式传入的 `permissionMode` 与会话不同，三个查询接口（包括 SSE）在开始执行前返回 HTTP 409，错误码 `PERMISSION_MODE_MISMATCH`，请先在页面修改会话权限。页面收到保存确认后更新显示；修改影响后续工具授权，已经获准或执行中的操作不会被终止。

`AUTO_APPROVE` 会取消工具权限确认（包括秒悟发布）；远程部署使用时应确认运行账户、文件系统和网络边界符合预期。它不会赋予操作系统之外的新权限，也不会绕过系统安全与部署限制。

### 受保护路径

以下路径由保护规则重点处理；不同工具及读写操作的结果不同。敏感文件读取可能进入高风险授权，不能把名单理解为任何权限模式下都绝对不可读：

- `.git` — Git 仓库数据
- `.env` — 环境变量和密钥
- `.ssh` — SSH 密钥
- `.gnupg` — GPG 密钥
- `.aws` — AWS 凭证

### 远程部署与访问边界

镜像默认 `ALLOW_PRIVATE_NETWORK=true` 会放行所有到达后端的来源，并非只允许私网免认证。对外部署需在入口配置认证、TLS、防火墙和端口绑定；实例访问控制不等于多用户／租户隔离。改变该值须通过 Compose environment／override 显式传入，仅写根 `.env` 不会覆盖未透传变量。设为 false 后仍需核对代理来源与认证行为，不能当成完整公网身份体系。

模型 API、MCP 和发布服务会接收请求中的相关数据；自托管运行环境不代表外部调用全部留在本机。源码：[访问过滤器](backend/src/main/java/com/aicodeassistant/config/RemoteAccessSecurityFilter.java)。

### 安全测试

- 权限、Bash、路径、符号链接、WebSocket、并发和数据库 CAS 均有专项测试覆盖
- 包含命令注入、路径穿越、权限绕过等攻击场景
- **v9.3 深度防御新增**：
  - **CWE-22 路径穿越**：`CoordinatorService.getScratchpadDir` sessionId 白名单（11 单测），即使上游 URI 拦截被绕过，白名单仍为最终落盘防线
  - **跨用户访问隔离（P2-A）**：`BrowserReplayController` 双层闸门 —— sessionId 格式校验返回 400 + principal 归属校验返回 403，MVP 匿名会话兼容

  **v9.3 安全防御摘录：**

  | 防御层级 | 位置 | 防护机制 | 单测数 |
  |---------|------|---------|-------|
  | P1-2 | `CoordinatorService.getScratchpadDir` | sessionId 白名单 `^[A-Za-z0-9_-]{1,128}$` | 11 |
  | P2-A | `BrowserReplayController` | sessionId 格式校验 (400) + principal 归属校验 (403) | — |

- 上述场景均有专项测试覆盖；完整安全套件通过本地回归或专项工作流执行。

<a id="-质量保障"></a>
### 🧪 质量验证

历史测试报告见 [全链路测试报告](docs/test-results/v9.3/ZhikunCode全链路测试报告.md)（目录名保留 v9.3，报告正文为 v9.5、2026-06-05）。下列数量均为注明日期的快照；历史采样总数不是当前独立测试用例数，也不代表当前提交已经通过全部检查。

**持续集成：**
- **GitHub Actions 自动化流水线**：主 CI 执行后端与 Python 测试、前端测试和构建；Docker 镜像验证仅在 `main` push 时执行。[查看流水线](.github/workflows/ci.yml)。
- **公开案例校验**：CI 通过 `node scripts/verify-king-case.mjs` 复算 MOBA 公开材料，依赖 Node.js 22 和 FFmpeg；校验文件与记录一致性，不替代对业务效果的判断。[校验脚本](scripts/verify-king-case.mjs)。

**前端专项验证快照（2026-10-04，历史记录）：**

- **Vitest 全量单元测试**：127 个测试文件通过，1220 PASS / 16 skipped（1236 total）；包含通知替换、旧计时回调竞态、卸载重挂和语法色板对比度回归。
- **主题浏览器回归**：1440px／390px 视口下共 16 个主题与浓度组合场景、8360 项颜色与隔离断言通过；覆盖正文、链接悬停、行内代码、表格、99／100 行代码与手动高亮、消息操作、Mermaid、图片和图文混合消息。
- **历史跳过项**：当时星舰主题在两个视口下的图片浮层交互因 `clip-path` 裁剪问题跳过；2026-10-06 已修复并恢复回归，当前不再跳过。
- **静态与构建验证**：TypeScript、改动源码 ESLint、Vite 生产构建和差异检查通过；构建仍提示部分分包超过 1000 kB。

此快照仅记录前端验证，不代表重新执行了后端、Python 或全栈会话链路测试。主题脚本使用真实消息组件和完整样式，在独立 Chromium 上下文中阻断网络请求；不启动业务服务、不读取现有会话，也不使用系统剪贴板。

**历史本地验证快照（2026-09-02）：**

- **后端单元/集成测试**：1258 tests / 0 failure / 0 error / 62 skipped
- **Python pytest**：107 PASS
- **前端 vitest**：207 PASS / 16 skipped（223 total）
- **简洁工作台 Playwright E2E**：7 / 7 PASS
- **静态与构建验证**：TypeScript 与 Vite 生产构建通过

**历史专项与 E2E 基线：**
- **36 模块 REST/WS/LLM/Session 冒烟**：45/45 PASS（42 REST + 1 WS STOMP + 1 LLM 真推理 + 1 Session 持久化）
- **E2E 差异化链路**：Task 7 可视化自动路由（`/visualize` mermaid/json/text）· Task 8 浏览器语义快照 MVP（`/snap`）全链路 PASS
- **APOS Phase 1 E2E**：62 用例（9 模块，含 28 原始功能 + 34 支撑链路）100% PASS，覆盖 Activity 基础 UI / 数据流转 / 三层展示 / Signal 标记 / Feature Flag / 后端 API / 响应式 / 持久化，含 4 Bug 修复回归
- **APOS Phase 2 E2E**：5 模块 50 用例（变更影响全景 / Pipeline 视图与 DAG / 异常检测与告警 / 移动端响应式 / Phase 2 集成功能）48 PASS / 2 SKIP，通过率 96%
- **APOS 风险修复专项**：11 用例 100% PASS（工具调用 / 批量操作 / 并发竞态 / API 降级）
- **AI Coding 功能增强专项**：6 大模块（SelfCorrectionLoop / 精确Tokenizer / Skill预算安全 / BashTool动态超时 / GitDiffTracker / SearchStrategyRouter）33 用例 + 238 单元测试 + 7 集成测试 + Feature Flag 双向验证，100% PASS
- **历史功能验证**：报告按当时用例范围记录结果，不代表当前版本全部功能完整或无缺陷

**测试框架详情（2026-09-02 历史记录）：**

| 框架 | 层级 | 覆盖范围 | 数量 |
|------|------|---------|------|
| JUnit 5 + Mockito | 后端单元/集成测试 | 上下文/授权网关/技能/插件/LLM/MCP/记忆/并发/SSE/持久化/工具/工作台投影等（覆盖范围摘录） | 1258 tests / 0 failure / 0 error / 62 skipped |
| Vitest | 前端单元测试 | Store 生命周期/跨 Tab 同步/流式渲染/权限交互/重连恢复/工作台与路由边界 | 207 PASS / 16 skipped |
| Playwright + 节点脚本 | 端到端 E2E | 简洁工作台 / 可视化 3 种 viewType / 浏览器快照 MVP / APOS Phase 1 全栈 / APOS Phase 2 全栈 | 简洁工作台 7/7 PASS；其他历史结果与跳过项见报告 |
| Pytest | Python 服务测试 | Token 估算/文件处理/浏览器自动化/语义快照/代码分析器/CLI | 107 PASS |

**历史性能采样（2026-05-09，本地 macOS，490 次请求）：**

单连接串行采样，14 个混合端点包括前端首页与 Python 健康接口；不含 LLM 推理耗时，也不是并发压测。

| 指标 | p50 | p95 | p99 |
|-----|-----|-----|-----|
| REST API（14 端点混合） | 1.5ms | 2.3ms | 4.3ms |
| WS STOMP 握手 | 2.22ms | 4.58ms | 6.22ms |
| 浏览器语义快照（热路径） | 9.23ms | 12.20ms | 12.26ms |

**详细测试数据与证据：**
- v9.3 完整报告：[docs/test-results/v9.3/](docs/test-results/v9.3/)
- 分模块测试结果：[docs/test-results/](docs/test-results/)
- 前端 E2E 脚本：[frontend/e2e/](frontend/e2e/)
- 隔离主题回归脚本：[frontend/tests/browser/theme-regression.mjs](frontend/tests/browser/theme-regression.mjs)
- E2E 截图证据：[docs/test-results/screenshots/](docs/test-results/screenshots/)

从仓库根目录运行主题回归（首次运行先安装前端依赖与 Playwright Chromium；已安装时可直接执行测试命令）：

```bash
cd frontend
npm ci
npx playwright install chromium
npm run test:theme-regression
```

该测试无需启动后端或前端开发服务器，失败截图写入系统临时目录。

<details>
<summary>📋 历史测试模块摘录（原报告共 36 个模块，保留原编号，点击展开）</summary>

| # | 模块 | 用例数 | 通过率 | 备注 |
|---|------|--------|--------|------|
| 1 | 环境准备与三端启动 | 7 | 100% | — |
| 2 | REST API 基础功能 | 33 | 100% | 逐端点验证 |
| 3 | WebSocket STOMP 通信 | 8 | 100% | — |
| 4 | Agent Loop 核心循环 | 9 | 100% | — |
| 5 | 工具系统与安全 | 10 | 100% | — |
| 6 | 权限治理与安全 | 6 | 100% | — |
| 7 | System Prompt 与 LLM | 7 | 100% | — |
| 8 | 记忆系统 | 7 | 86% | ★ 首次覆盖 |
| 9 | 技能系统 | 7 | 100% | ★ 首次覆盖 |
| 10 | 插件系统与 MCP | 11 | 100% | ★ 首次覆盖 |
| 12 | Python 服务 | 15 | 100% | 1 BUG 已修复 |
| 13 | 前端 E2E 与 UI | 7 | 86% | 1 PARTIAL |
| 14 | 文件历史与补充 API | 11 | 100% | ★ 首次覆盖 |
| 15 | CLI 命令行工具 aica | 11 | 91% | 2 BUG 已修复 |
| 16 | 可视化功能 E2E | 19 | 100% | ★ 首次覆盖 |
| 17 | F3 代码复杂度分析 | 6 | 100% | ★ v1.0 新增 |
| 18 | F33 变更影响链路分析 | 6 | 100% | ★ v1.0 新增 |
| 19 | F25 API 契约可视化 | 6 | 100% | ★ v1.0 新增 |
| 20 | F35 代码→图表自动生成 | 25 | 100% | ★ v1.0 新增 |
| 21 | F40 代码路径追踪可视化 | 25 | 100% | ★ v1.0 新增 |
| 22 | 单元测试体系（v9.3 扩展） | 84 | 100% | 原报告模块级用例数，与独立 JUnit 数量不是同一口径 |
| 23 | APOS 基础 UI | 4 | 100% | ★ 首次覆盖 |
| 24 | APOS 数据流转 | 4 | 100% | ★ 首次覆盖 |
| 25 | APOS 三层展示 | 4 | 100% | ★ 首次覆盖 |
| 26 | APOS Signal与筛选 | 2 | 100% | ★ 首次覆盖 |
| 27 | APOS Feature Flag | 2 | 100% | ★ 首次覆盖 |
| 28 | APOS 后端API验证 | 1 | 100% | ★ 首次覆盖 |
| 29 | APOS 响应式+健康 | 3 | 100% | ★ 首次覆盖 |
| 30 | APOS Activity持久化 | 8 | 100% | ★ 首次覆盖 + 4 Bug 修复 |
| 31 | APOS 变更影响全景 | 6 | 100% | ★ Phase 2 新增 |
| 32 | APOS Pipeline视图与DAG | 12 | 92% | ★ Phase 2 新增 |
| 33 | APOS 异常检测与告警 | 10 | 100% | ★ Phase 2 新增，2 SKIP |
| 34 | APOS 移动端响应式 | 9 | 89% | ★ Phase 2 新增 |
| 35 | APOS Phase 2集成功能 | 13 | 100% | ★ Phase 2 新增 |
| 36 | AI Coding 功能增强 | 33 | 100% | ★ v9.4 新增（6子模块 + 238单元 + Feature Flag验证） |

</details>

---

<a id="extensions"></a>
## 🎯 skill技能系统

ZhikunCode 的skill技能系统（Skill System）是一个 **Markdown 驱动的可扩展工作流引擎**。每个技能就是一个 `.md` 文件，用 YAML frontmatter 定义元数据，用 Markdown 正文定义执行指令。

### 管理与启停

PC 和平板通过顶部 **Skill** 入口、手机通过 **更多 → Skill 管理** 打开管理页，可搜索技能、查看来源与正文，并分别开启或关闭。现有与新增技能默认开启。设置由同一后端的所有设备和会话共享，默认保存到 `~/.zhikun/skill-states.json`；可通过 `SKILL_STATE_PATH` 指定位置，Docker 使用持久化目录中的 `/app/data/skill-states.json`。

开关按技能内部名称保存，同名覆盖、文件热加载或删除后重建同名技能都会保留原有选择。

保存后立即拦截新的技能调用，下一条用户消息使用更新后的配置，无需重启。关闭的技能不出现在可用列表或技能建议中，不再自动加入其描述、正文和对应 token 开销；全部关闭时移除 `Skill` 工具。页面可见时每 5 秒同步一次其他设备的设置，也可手动刷新。

如果技能设置文件损坏或无法读取，后端仍会启动，但所有 Skill 暂时关闭。管理页会显示错误并禁止保存，原文件不会被覆盖；修复提示中的配置文件后，重启后端恢复正常管理。

当前回答与已经开始的技能执行继续运行；旧聊天历史中的技能内容仍可能影响模型，新聊天可获得干净上下文。开关管理的是技能加载与调用，不会隔离磁盘文件。技能对应的独立命令、底层工具及部署配置仍有各自的启用规则，例如关闭 `review` Skill 不会关闭独立的 `/review` 命令。

### 13 个内置技能

在 Web 输入框输入 `/skill <技能名>` 打开技能弹窗，填写参数或任务说明后执行。内置 `commit`、`pr`、`test`、`verify`、`debug`、`remember`、`software-architecture`、`prompt-engineering`、`csv-data-summarizer`、`test-driven-development` 会在模板后保留完整补充说明；内置 `review` 将完整说明作为审查范围。Web `/skill` 与模型调用 `Skill` 都使用这套渲染规则。其他技能（包括项目或用户提供的同名覆盖）仍按声明参数替换模板，不自动追加原文。

发布使用表中的专用命令；同名直接命令（如 `/review`）有独立实现。内置 13 个工作流默认启用，实际调用仍受开关、工具权限与服务配置约束。部署未启用 OSS 或秒悟时，对应 `/publish-oss` 或 `/publish-meoo` 不出现在命令列表和补全中；启用部署功能后仍需对应 Skill 可用。

| 技能 | 命令 | 功能 |
|------|------|------|
| **智能提交** | `/skill commit` | 分析暂存区变更，按 Conventional Commits 格式生成 commit message |
| **代码审查** | `/skill review` | 审查未提交变更，按 P0/P1/P2/P3 严重程度分类问题 |
| **智能测试** | `/skill test` | 为指定代码或近期变更生成/运行测试，覆盖边界情况 |
| **PR 助手** | `/skill pr` | 分析分支差异，生成结构化 PR 描述和审查说明 |
| **调试诊断** | `/skill debug` | 按需求进行根因诊断、最小修复或脱困分析 |
| **代码验证** | `/skill verify` | 按项目技术栈和改动风险选择构建、测试及静态检查 |
| **项目笔记** | `/skill remember` | 显式保存、检索和更新项目共享笔记；不保证新会话自动读取 |
| **架构分析** | `/skill software-architecture` | Clean Architecture + SOLID + 设计模式评估 |
| **数据摘要** | `/skill csv-data-summarizer` | CSV统计分析+可视化图表+Markdown报告 |
| **Prompt工程** | `/skill prompt-engineering` | 优化prompt结构、清晰度和有效性 |
| **测试驱动开发** | `/skill test-driven-development` | TDD红→绿→重构循环方法论指导 |
| **OSS 产物发布** | `/publish-oss` | 显式发布工作区单文件；匹配 Manifest 时校验条目与哈希，无匹配时按文件策略检查。部署默认关闭，审批依当前权限模式 |
| **秒悟应用发布** | `/publish-meoo` | 每次创建新站点、保留旧站点并占用平台额度；结果卡显示匿名访问验证状态。[配置与使用说明](docs/deployment/meoo.md) |

### 技能加载来源

当前启动时依次加载内置、项目和用户技能，同名定义由后加载者覆盖；这不是支持删除后自动回退的分层注册表：

```
bundled → project → user
```

| 来源 | 目录 | 说明 | 状态 |
|------|------|------|------|
| **managed** | 策略管理目录 | 企业统一下发的技能 | 预留 |
| **user** | `~/.zhikun/skills/` | 用户全局自定义技能 | ✅ 已实现 |
| **project** | `.zhikun/skills/` | 项目级技能；是否纳入版本控制取决于 Git 忽略规则 | ✅ 已实现 |
| **plugin** | 插件提供 | JAR 插件内嵌的技能 | 预留 |
| **bundled** | 内置 | 13 个工作流定义，执行依赖各自条件 | ✅ 已实现 |
| **mcp** | MCP 构建 | 通过 MCP 协议注册的技能 | 预留 |

### 自定义技能

在后端运行用户的 `~/.zhikun/skills/translate.md` 中保存以下示例，重启后端加载。`project` 来源的 `.zhikun/skills/` 按后端启动目录解析，不随界面选择的 Project 切换：

```markdown
---
description: "将代码翻译为指定语言"
arguments:
  - language
---

# 翻译任务

将原对话中明确指定的代码翻译为 {{language}}，保持原有逻辑和必要注释。
```

Web 调用方式：输入 `/skill translate`，在弹窗参数栏填写 `language=python` 或 `python`，再执行。

**可解析的 frontmatter 字段：**

模型调用 `Skill` 工具时，渲染正文通过工具结果返回；Web `/skill` 通过命令路径将渲染提示送入当前对话。

| 字段 | 类型 | 说明 |
|------|------|------|
| `description` | string | 技能描述 |
| `name` | string | 显示名称（覆盖文件名） |
| `arguments` | list | 参数定义列表 |
| `argument_hint` | string | 参数提示文本 |
| `when_to_use` | string | 使用条件描述元数据 |
| `allowed_tools` | list | 技能声明的工具列表；不能替代实际工具权限控制 |
| `context` | string | `inline`（默认）将渲染正文作为工具结果返回；`fork` 返回正文和执行元数据，当前入口不自动创建子代理 |
| `model` | string | 模型声明（`inherit` 表示不指定覆盖）；当前执行器返回此元数据，不自动切换模型 |
| `version` | string | 技能版本号 |
| `disable_model_invocation` | boolean | 模型调用偏好声明（默认 false）；当前工具入口未据此禁止模型调用 |
| `user_invocable` | boolean | 手动调用偏好声明（默认 true）；当前入口未据此限制调用 |
| `hooks` | object | 钩子配置（预留） |
| `effort` | string | 推理强度声明；fork 结果携带此元数据，当前不自动调整模型请求 |
| `agent` | string | 关联代理名称声明；fork 结果携带此元数据，当前不自动创建对应代理 |
| `paths` | list | 文件路径 glob 模式声明（元数据） |
| `shell` | string | Shell 类型声明（默认 bash）；当前不据此启动或切换 Shell |

> 已监听的项目技能目录支持热更新（500ms 防抖）；用户目录新增或修改后建议重启。删除同名覆盖文件不会自动恢复内置定义；修改内置资源或清理覆盖文件后，应重新构建并重启后端，再刷新前端菜单。

**安全与运行控制：**
- **技能加载与上下文**：不设置单技能或会话累计的 token 加载额度；模型上下文容量、运行轮次和输出限制仍然适用，重复加载仍会增加实际输入 token 消耗。
- **工具声明**：`allowed_tools` 描述预期工具；当前不保证运行时强制执行这份列表，实际工具权限仍然适用
- **文本技能检查**：模型调用 `Skill` 时，上述 10 个内置补充说明技能及内置 `review` 按原始说明全文检查 2000 长度上限（Java `String.length()`，包含参数名、空白和换行），不将 Markdown 或代码示例按 shell 危险模式过滤。对这 10 个技能，全文限长比此前逐参数限长更严格：单个参数未超限也可能因全文超限而被拒绝。本次保留此工具入口限制。
- **其他技能检查**：通过 `Skill` 工具调用其他技能（包括自定义技能、同名覆盖和发布技能）时，仍对每个解析参数检查 2000 长度上限及六类危险正则模式：`$(...)`、反引号、`; command`、管道命令、`&& rm`、`${...}`。
- **Web 命令入口**：`/skill` 不经过 `SkillToolValidator`，本次未给该入口新增上述长度或正则检查；同一段说明可能在 Web 命令入口可用、在 `Skill` 工具入口因全文超限被拒绝。后续实际工具调用仍经过授权和执行检查。
- **Fork 深度检查**：fork 模式检查调用时的当前上下文深度，深度 ≥3 时拒绝；该入口仍只返回渲染正文与元数据。

---

## 🧩 插件系统

ZhikunCode 的插件系统通过标准 **Java SPI（ServiceLoader）** 机制发现和加载第三方 JAR 插件，提供四大桥接能力：命令注册、工具注册、钩子拦截、MCP 服务器集成。`plugin.enabled`（默认 true）控制启动时自动加载，不是所有入口的运行时禁用开关；显式 reload／install 入口仍可初始化插件。

### 四大桥接能力

| 桥接类型 | 说明 | 示例 |
|---------|------|------|
| **命令注册** | 插件可注册自定义斜杠命令，自动添加插件前缀 | `/myplugin:hello` |
| **工具注册** | 插件可提供自定义工具供 AI Agent 调用 | 自定义代码分析工具 |
| **钩子拦截** | 插件可在关键事件前后执行自定义逻辑 | 工具执行前安全审计 |
| **MCP 服务器** | 插件可注册 MCP 服务器扩展 AI 能力 | 连接外部数据源 |

### 安全特性

- **PluginClassLoader 类加载控制** — 插件通过包白名单访问宿主 API：核心 API 包（`com.aicodeassistant.plugin.*`、`tool.*`、`command.*`、`mcp.*`）、标准库（`java.*`、`javax.*`、`jdk.*`、`sun.*`、`org.slf4j.*`）、基础框架（`org.springframework.*`、`com.fasterxml.jackson.*`、`jakarta.*`）。未在白名单中的宿主类访问会抛出 `ClassNotFoundException`。插件仍在后端进程执行，这不是针对恶意代码的操作系统沙箱
- **钩子执行超时保护** — 设置 5 秒等待超时；前置安全钩子失败或超时会拒绝执行，其他展示类钩子失败记录后继续。等待超时不等于插件线程已停止
- **JAR 文件验证** — 加载前校验文件存在性、JAR 格式合法性、大小限制（默认 50MB）、SPI 配置文件（`META-INF/services/`）完整性
- **API 版本兼容检查** — 插件声明 `minApiVersion` / `maxApiVersion`，宿主自动校验兼容性

### 热重载机制

支持运行时重新加载所有插件，无需重启服务：

- 使用 `ReentrantReadWriteLock` 串行化重载与相关查询；不代表所有执行中的插件任务都已停止
- 重载流程：卸载所有插件（注销命令/工具/钩子/MCP + 关闭 ClassLoader）→ 重新扫描加载
- 触发方式：`/reload-plugins` 斜杠命令 或 REST API

### 8 种钩子事件类型

插件接口声明以下事件；实际触发以接入链路为准，`TaskCompleted` 当前没有自动触发调用方：

| 事件类型 | 触发时机 |
|---------|----------|
| `PreToolExecution` | 工具执行前 |
| `PostToolExecution` | 工具执行后 |
| `UserPromptSubmit` | 提示词提交时 |
| `SessionStart` | 会话开始 |
| `SessionEnd` | 会话结束 |
| `TaskCompleted` | 声明已存在，尚未自动触发 |
| `Notification` | 通知事件 |
| `Stop` | 停止事件 |

### 插件开发指南

开发一个 ZhikunCode 插件只需四步：

**1. 实现 `PluginExtension` SPI 接口**

```java
public class MyPlugin implements PluginExtension {
    @Override public String name() { return "my-plugin"; }
    @Override public String version() { return "1.0.0"; }

    @Override
    public List<Command> getCommands() {
        return List.of(/* 自定义命令 */);
    }

    @Override
    public void onLoad(PluginContext ctx) {
        ctx.getLogger().info("Plugin loaded!");
    }
}
```

**2. 在 `META-INF/services/` 中注册 SPI**

```
# META-INF/services/com.aicodeassistant.plugin.PluginExtension
com.example.MyPlugin
```

**3. 将 JAR 放入 `~/.zhikun/plugins/` 目录**

**4. 重启服务或执行 `/reload-plugins` 热重载**

> `PluginExtension` 接口采用默认方法设计 — 最小实现仅需 `name()` 和 `version()`，其余能力（命令/工具/钩子/MCP）按需覆写。

---

<a id="memory"></a>
## 🧠 记忆系统

ZhikunCode 提供个人记忆与项目约定管理，也有团队 Markdown 组织方式；不同来源的加载入口不同。可把常用构建、测试和工程约定保存下来，在后续任务中复用。

### 三层记忆架构

| 层级 | 文件 | 作用域 | 说明 |
|------|------|--------|------|
| **个人记忆** | `~/.ai-code-assistant/MEMORY.md` | 全局跨项目 | 通过 Memory 工具或页面读写，按需使用 |
| **项目记忆** | `zhikun.md` / `zhikun.local.md` | 当前项目 | 项目级编码约定、架构决策、构建流程 |
| **团队记忆** | `.zhikun/team-memories/*.md` | 团队共享 | 随代码库分发，经普通文件读取按需使用；未自动注入普通会话 |

### 记忆分类

实现支持四类标签；普通 Memory 工具写入默认使用 semantic，不能理解为自动语义分类：

| 分类 | 说明 | 示例 |
|------|------|------|
| **语义记忆**（semantic） | 项目知识、用户偏好、技术约定 | 「这个项目使用 JUnit 5 + AssertJ」 |
| **事件记忆**（episodic） | 具体操作历史、调试经过 | 「上次部署时端口冲突的解决方法」 |
| **程序记忆**（procedural） | 常用工作流、部署流程 | 「每次提交前先跑 `mvn test`」 |
| **团队记忆**（team） | 团队级共享知识和规范 | 「API 返回统一使用 Result 包装」 |

### 自动记忆与搜索

- **项目自动加载** — 所选 Project 根的 `zhikun.md` 与 `zhikun.local.md` 加入系统提示，不向父目录搜索。
- **个人记忆工具** — Memory 提供 read／write／delete；实际是否写入取决于调用，不保证每轮自动记忆。
- **团队资料** — 可用普通文件读取工具按需查看团队 Markdown；没有全量自动注入保证。
- **来源记录** — 条目支持 source 等元数据。
- **检索实现的状态** — 代码包含 BM25 与可选 LLM 重排，但尚未接入普通 Memory 工具或会话自动加载。不能把内部方法当作已经开放的搜索入口。

实现：[MemdirService](backend/src/main/java/com/aicodeassistant/memdir/MemdirService.java) · [项目约定加载](backend/src/main/java/com/aicodeassistant/service/ProjectMemoryService.java)。

### 页面管理记忆

点击页面顶部的 **记忆** 徽章，即可在页面中查看和编辑个人记忆（`MEMORY.md`）：

- **卡片模式** — 逐条查看、编辑、删除记忆条目，支持手动新增
- **整篇模式** — 以 Markdown 编辑器整体修改，支持实时预览
- **安全保护** — 保存时自动规范化条目格式、生成 `.bak` 备份，并在内容被并发修改时进行冲突检测；PC / 平板 / 手机响应式布局

### 项目记忆文件

在所选 Project 根目录创建以下文件，其内容会进入系统提示；是否准确执行仍需检查实际输出，不要在其中写凭证：

```markdown
# zhikun.md — 项目约定（随代码库提交）

## 编码规范
- Java 方法命名使用 camelCase
- 测试类以 Test 结尾

## 构建流程
- 提交前必须跑 `./mvnw test`
- Commit 采用 Conventional Commits 格式
```

```markdown
# zhikun.local.md — 本地配置（不提交，加入 .gitignore）

## 本地环境
- 我的 API Key 在 .env 文件中
- 本地数据库端口: 5432
```

> 项目记忆仅从所选 Project 根目录加载 `zhikun.md` 和 `zhikun.local.md`，不向父目录搜索；单文件上限 100KB。`zhikun.md` 随代码库提交用于团队共享，`zhikun.local.md` 用于个人本地配置。

### 安全保护

- 页面保存有 50,000 字符限制，并提供备份、并发冲突检测和原子替换。
- 项目约定有单文件读取上限与路径／符号链接检查。
- 代码中的提示读取截断和过期清理是辅助路径；普通会话未接入个人／团队记忆全量加载，也没有周期清理调用。

---

## 💻 CLI 工具

除了 Web UI，ZhikunCode 提供复用后端引擎的命令行客户端，支持脚本输入、结构化输出和会话续接。

### Python CLI（aica）— 终端 AI 编程

`aica` 是 ZhikunCode 的命令行客户端，设计为 UNIX 管道一等公民：

```bash
# 安装：先完成本地 Python 环境准备，从仓库根执行
python-service/.venv/bin/python -m pip install -e './python-service[cli]'
export PATH="$PWD/python-service/.venv/bin:$PATH"

# 基础使用
aica "帮我重构这个函数"

# 显式授权当前目录（仅 localhost 后端）
aica --working-dir "$PWD" "帮我重构这个函数"

# 远程后端使用已有 Project
aica --server https://example.com --project-id PROJECT_ID "检查项目"

# 管道输入 — 像 grep/sed 一样组合
cat src/main.py | aica "review this code"

# 结构化输出 + jq 处理
aica -f json "list all API endpoints" | jq '.result'

# 流式输出
aica -f stream-json "refactor this module"

# 继续上次对话
aica --continue "fix the bug we just discussed"
```

未提供 `--working-dir`、`--project-id`，且没有通过 `--session-id`、`--continue` 或 `--resume` 选择 Session 时，CLI 使用后端默认 workspace。`--working-dir` 只适用于 localhost，远程后端应使用 `--project-id`；两者不能同时使用。

**核心特性：**

| 特性 | 说明 |
|------|------|
| 三种输出格式 | `text`（终端 Markdown 渲染）/ `json`（结构化）/ `stream-json`（SSE 流式） |
| 管道支持 | 自动读取 stdin，与 shell 管道无缝组合 |
| 权限模式 | `--permission-mode default/plan/accept_edits/dont_ask/auto_approve` 控制授权策略（CLI 未指定时新建默认完全访问、续接沿用会话权限；`auto_approve` 取消人工确认，含秒悟发布；不能绕过硬拒绝、安全 Hook、SSRF 或部署沙箱） |
| 会话管理 | `--continue` 继续上次会话，`--resume <id>` 恢复指定会话 |
| 模型选择 | `--model` 指定已配置模型；`--effort` 虽在 CLI 中解析，当前查询接口没有对应字段，不作为已生效功能 |
| 工具控制 | `--allowed-tools` / `--disallowed-tools` 白名单/黑名单 |
| 退出码 | 0=成功，1=通用错误，2=参数错误，3=连接错误，4=认证错误，130=Ctrl+C 中断 |

> `aica` 通过 HTTP/SSE 连接后端，共享模型与权限控制。`--effort`、`--fallback-model`、`--fork-session`、`--json-schema` 虽能解析并发送，后端查询接口没有对应字段，不能依赖它们改变执行行为；以 [CLI](python-service/cli/main.py) 与 [查询接口](backend/src/main/java/com/aicodeassistant/controller/QueryController.java) 为准。自动化中应检查退出码和实际工具结果。

### 35+ 斜杠命令 — Web UI 快捷操作

> 以下斜杠命令在 Web UI 中使用，`aica` CLI 通过自然语言 prompt 访问后端 Agent 引擎实现相同能力。

在 Web UI 中输入 `/` 或按 `Ctrl+K` 打开命令面板，支持模糊搜索和键盘导航：

| 类别 | 命令 | 说明 |
|------|------|------|
| **核心** | `/help` `/clear` `/exit` | 帮助、清除对话、退出 |
| **模型** | `/model` | 查看可用模型；切换请使用输入栏的模型选择器 |
| **诊断** | `/doctor` | 9 项系统诊断（Java/LLM/Git/JVM/Python/磁盘） |
| **压缩** | `/compact` | 手动触发上下文压缩，可附带指令（如 `/compact focus on API`） |
| **Git** | `/diff` `/commit` `/review` | 代码差异（`/diff [unstaged\|staged\|--staged]`；`--cached` 不再被接受，请用 `staged`）、暂存预览/直接提交、代码审查 |
| **配置** | `/config` `/permissions` | 查看配置、权限模式管理 |
| **会话** | `/session` `/resume` | 会话信息、恢复历史会话 |
| **成本** | `/cost` `/usage` | Token 用量、费用统计 |
| **MCP** | `/mcp-servers` `/mcp-tools` | MCP 服务管理 |
| **深度分析** | `/ultrareview` | AI 深度审查（架构+安全+性能+并发） |
| **浏览器** | `/snap` | 语义快照 —— 捕获当前页面 DOM 结构与交互元素，生成 JSON 格式快照 |
| **可视化** | `/visualize mermaid\|json\|text` | 自动路由推送 —— 流式渲染 Mermaid 图表、JSON 数据或纯文本可视化结果 |

> 命令输入错误时，系统会基于 Levenshtein 距离自动建议相似命令。

---

## 📱 浏览器全流程操控

ZhikunCode 是独立的 Web 应用。完成部署与访问控制后，可通过电脑、平板或手机浏览器跟进服务器上的任务。

### 为什么这很重要？

| 场景 | 使用方式与条件 |
|---|---|
| 离开电脑后跟进任务 | 手机浏览器查看运行状态、补充要求及处理待办审批 |
| 在另一台设备继续查看 | 连接同一后端恢复会话与运行事件；后端重启不自动续跑执行栈 |
| 在服务器处理项目 | 授权服务端目录；本地文件引用和远程上传是不同路径 |
| 团队评估部署 | 可共用受控实例，但需要自行明确访问身份、项目权限与数据边界 |

### 完整的浏览器操控能力

浏览器提供以下任务操作入口：

- **对话式编程** — 输入自然语言需求，Agent 自动生成代码，实时流式输出
- **权限审批** — 需要交互裁决时处理服务端提供的选项；默认完全访问可自动批准，并非每个敏感操作都弹窗
- **方案协商** — Agent 提出方案后可以在浏览器中讨论、修改、确认
- **任务管控** — 查看任务状态与结果，按任务 ID 请求取消并查询停止确认
- **文件浏览** — 在浏览器中直接查看和导航项目文件树
- **委派结果查看** — 通过对话中的工具结果查看子任务结果

### 实时通信

前后端通过 **STOMP over SockJS** 保持实时连接（自动协商 WebSocket → xhr-streaming → xhr-polling 降级）：

- **流式输出** — LLM 响应逐字输出，无需等待完成
- **持久权限交互** — 权限请求先写入 SQLite，再投递到浏览器；刷新或断线重连后可恢复未完成请求
- **服务端终态裁决** — 浏览器只提交服务端下发的选项，允许、拒绝、过期和取消通过数据库 CAS 互斥裁决
- **服务端倒计时** — 权限决策窗口为 300 秒，前端只展示服务端 deadline，不在本地自动伪造拒绝
- **子 Agent 授权复用** — 后代 Agent 在严格约束下复用根会话 SESSION/WORKSPACE Grant，避免重复弹窗
- **状态同步** — Agent 工作状态变化即时反映在 UI 上
- **心跳保活** — 双向 10s 心跳检测，断线自动重连（指数退避 1s→10s）
- **消息保障** — 128KB 消息大小限制，1MB 发送缓冲，30s 发送超时

### 🌉 Bridge 跨端桥接

源码中保留以下 Bridge 组件，但连接与移动证据审批仍有未接通链路，不能作为完整跨端桥接功能宣传。普通浏览器远程操作不依赖它们：

| 组件 | 职责 |
|------|------|
| **BridgeServer** | 桥接服务端，管理设备连接和消息路由 |
| **BridgeApiClient** | 客户端 API 封装，支持远程调用 |
| **TrustedDeviceManager** | 可信设备管理，设备注册与认证 |

---

<a id="agents"></a>
## 🤖 Agent 委派与后台任务

把独立调查、实现或验证交给子 Agent，让主 Agent 保持对整体目标的掌握；把需要持续查询的执行交给后台 Task，用任务 ID 跟踪状态与结果。两者都能让耗时工作与主对话配合推进，各自承担明确的职责。

### 五种内置 Agent 角色

`Agent` 工具通过 `subagent_type` 选择角色，`SubAgentExecutor` 为每次调用创建独立子会话与上下文，由角色提示和当前可用工具共同约束执行：

| 角色 | 适合的任务 | 工具范围 |
|------|-----------|---------|
| **探索（explore）** | 搜索代码、梳理调用链、定位实现与风险 | 当前启用工具中排除直接编辑工具与角色禁用项 |
| **验证（verification）** | 复现问题、执行测试、寻找反例并给出证据 | 当前启用工具中排除直接编辑工具与角色禁用项；遵守验证角色的文件保护约束 |
| **规划（plan）** | 分析需求、比较方案、拆解实施步骤 | 当前启用工具中排除直接编辑工具与角色禁用项 |
| **通用（general-purpose）** | 实现功能、修复问题、完成完整子任务 | 当前启用工具，受公共禁用项与授权约束 |
| **引导（guide）** | 查阅文档、解释配置与用法 | 仅 Glob、Grep、Read、WebFetch、WebSearch |

工具池从当前启用的内置工具、插件和 MCP 工具组装，再应用公共禁用项及角色 allow/deny 规则。子 Agent 不提供 `Agent`、`TaskCreate`、`VerifyPlanExecution` 等嵌套委派或验证编排入口。探索、验证和规划角色排除 `Edit`、`Write`、`NotebookEdit` 等直接编辑工具；仍可用的 Shell、浏览器或 MCP 操作继续受授权约束，工具过滤本身不是完整只读沙箱。

**验证以证据为准。** `verification` 的角色提示要求保护源码、现有测试、配置、依赖与锁文件，在授权范围内执行测试并允许生成可复现的验证产物、缓存和临时脚本；不得为验证而安装依赖或进行 Git 写入。动态验证结论须引用实际命令和工具输出，静态分析须明确标注。报告需针对主要风险进行反例检查，并区分 **PASS**（验收范围已验证且无重大缺口）、**FAIL**（已确认失败）与 **PARTIAL**（尚未确认失败，但关键验证未完成）。这些是角色执行与报告规范，最终应结合实际证据审阅结论。

### 独立上下文、并行执行与结果回收

每次委派都需要在 `prompt` 中交代目标、已知结论、文件路径、授权边界与预期交付；子 Agent 从独立上下文开始。多个相互独立的 `Agent` 调用可在同一轮通过 Virtual Thread 并行执行，适合分别调查不同模块，或并行完成互不冲突的子任务。共享目录中的文件修改仍需避免重叠；上下文独立不等于文件隔离。

| 入口 | 调用与结果链路 | 适合场景 |
|---------|---------------|---------|
| **Agent（同步）** | 子会话执行 → 返回状态与结果 → 主 Agent 继续 | 下一步依赖该子任务结论 |
| **Agent（后台）** | `run_in_background=true` → 返回代理 ID 与输出文件路径 → 后台执行 → 当前 Run 收集结果 → 主 Agent 汇总 | 主 Agent 还有其他独立工作可推进 |
| **TaskCreate** | 提交后台执行 → 返回任务 ID → TaskList／TaskGet 查询状态与输出 | 需要单独查询、更新输出或请求取消的执行 |
| **TodoWrite** | 更新计划与进度清单 | 记录步骤与完成情况，不启动执行 |

后台 Agent 由 `BackgroundAgentTracker` 按父会话和父 Run 登记，WebSocket 推送启动、完成和失败事件。默认启用 `BACKGROUND_AGENT_WAIT`：主 Run 准备结束时，`QueryEngine` 等待**当前 Run** 尚未交付的后台 Agent 结果，将其作为任务数据回填上下文，再由主 Agent 生成汇总。结果不会成为新的授权或执行指令。

常规执行会对超过 **100,000 字符**的答案正文截断并附标记；Worktree 路径对包含交付、恢复信息的合成内容作同样处理。异常恢复及附加信息另有处理，因此这不是所有最终响应的统一硬上限。后台输出文件保存处理后的结果，父 Run 自动回填每个文件的前 **4,000 字符**，超长时附截断标记，并保留文件路径；可按路径读取更多已保存内容，文件本身也可能已被截断。

子 Agent 执行等待的基础预算默认 **30 分钟**，默认配置上限也为 **30 分钟**；执行等待超时后默认再等待 **30 秒**供退出。子会话初始化与 Worktree 准备、交付不计入这段执行等待计时，因此它不是调用总耗时上限。

父 Run 每批后台结果等待另设默认 **31 分钟**预算，与子执行等待分别计时；新一批等待独立获得预算，同一批唤醒不会刷新截止时间。可通过 `FEATURE_BACKGROUND_AGENT_WAIT=false` 关闭等待，或用 `AGENT_TIMEOUT_MAX_WAIT_MINUTES` 调整预算；提高子代理超时上限时应同步调整。等待超时会明确报告 `BACKGROUND_AGENT_WAIT_TIMEOUT`；取消、等待中断或主 Run 轮次耗尽也可能使结果收集未完成。此结果回收链路属于后台 `Agent`；`TaskCreate` 的执行结果通过任务工具查询。

### 模型选择、授权与真实执行状态

子 Agent 的模型解析顺序为 **显式 `model` 参数 → 父查询模型 → `premium` 别名**，五种角色遵循同一规则。`model` 可选择可用的实际模型名或 `light`、`standard`、`premium` 别名；别名在 `application.yml` 的 `agent.model-aliases` 中配置，当前三者默认均为 `qwen3.8-max-0902`。

子 Agent 使用关联的根会话、根 Run 与代理祖先链进行权限裁决。符合工具语义和约束的 SESSION／WORKSPACE Grant 可按规则复用；继承不会扩大路径、命令、风险或资源范围。需要人工授权的操作通过根会话的持久交互流程处理。

执行结果区分 `completed`、`failed`、`timeout`、`interrupted` 和 `max_turns`，后台结果保留实际结束原因。执行等待超时会发出中止信号并等待退出窗口，能够恢复时附带部分进展；执行等待超时或取消请求本身不等于所有受管执行已经停止，应结合停止确认与恢复信息判断后续处理。

`AgentConcurrencyController` 提供全局 **30** 个、每会话 **10** 个并发准入名额，并设 **3 层**执行深度上限。超限请求会被拒绝；名额按执行路径释放，名额释放不构成底层执行已停止的确认。

### 工作目录与安全交付

`Agent` 的 `isolation` 提供两种选择：

| 隔离方式 | 行为 | 适合场景 |
|---------|------|---------|
| **none**（默认） | 使用父工作目录，子会话上下文独立 | 代码调查、验证、文件范围清晰的子任务 |
| **worktree** | 从项目已提交 HEAD 创建独立 Git Worktree，通过安全检查后自动合回 | 需要隔离文件修改的实现或实验 |

WORKTREE 不包含父工作区未提交的修改，也不会自动 stash 或提交父目录；实际执行目录与提示一致，原 Project 授权边界不扩大。授权根必须是有效仓库根；其内部子目录可映射到新树执行，无初始提交或 detached HEAD 不支持自动交付。

只有执行正常完成、受管工作已停止且目标分支、历史与工作区状态通过核验时才自动合回。已交付但清理失败仍为完成并附警告；未交付、冲突、超时或停止未确认会保留工作区与分支，并返回实际恢复位置。Git 自然退出后不会为清理终止 hook 后台进程。Linux 和 macOS 对仍在受管进程组内的普通后台后代保留目录；主动脱离该组的守护进程不在此保证内。其他平台沿用原有尽力跟踪能力，不承诺同等级的后代停止证明。停止服务也不删除 worktree 或代理分支。 自动交付遵守 Git 忽略规则：ignored 文件不会强制加入提交，正常清理时可能随目录删除；需要保留的交付文件应放在非忽略路径。子模块内部未提交内容会阻止交付，不递归提交。

`Worktree remove` 仅安全清理本进程登记、属于当前项目且已无占用的空树或已交付工作区，不提供强制丢弃。保留目录不是永久归档，也不承诺跨重启自动恢复管理。手动清理还会保守检查创建会话与当前会话的受管后台任务；同会话中无关的后台任务也可能使清理暂缓。内部串行化不覆盖外部 Git 或其他工具的并发写入。


### 后台 Task 与计划清单

`TaskCreate` 立即提交实际后台执行，返回任务 ID 和创建回执；任务是否成功由执行结果决定。支持 `agent`、`shell`，以及单次通用子代理兼容类型 `local_workflow`、`monitor_mcp`、`dream`；后三者不提供周期监控或特殊优先级。

任务通常经历 `PENDING → RUNNING → COMPLETED / FAILED / CANCELLED`；开始前取消可直接进入 `CANCELLED`。运行中的取消先登记请求，待执行与所属受管资源退出后发布终态。单任务默认 **30 分钟**超时，超时退出记为 `FAILED` 并保留超时原因。Task 在服务进程内共享 **10 个**并发名额，满额立即返回 `TASK_CAPACITY_REACHED`，不会排队等待空位；这一限额独立于 Agent 并发限制。

| 工具 | 职责 | 结果与边界 |
|------|------|-----------|
| **TaskCreate** | 创建并启动后台任务 | 用返回的 `taskId` 跟踪；创建成功不代表执行成功 |
| **TaskList** | 列出当前会话的任务 | 查看状态与输出摘要，可按状态筛选 |
| **TaskGet** | 查询当前会话内指定任务 | 获取状态、已保存的输出和可用错误信息，以及取消请求和停止确认字段；返回内容受长度限制 |
| **TaskUpdate** | 替换任务的存储输出 | 不改变执行状态；运行中的临时说明可被最终执行输出替换 |
| **TaskStop** | 按 `taskId` 请求取消当前会话的任务 | 回执表示请求已发出，继续用 TaskGet 确认退出 |
| **TodoWrite** | 维护计划与进度清单 | 记录待办与完成情况，不启动后台执行 |

Task 的执行状态由 `TaskCoordinator` 管理，查询区分“已请求取消”（`cancellationRequested`）与“停止已确认”（`terminationConfirmed`）。任务记录保存在服务进程内存中；输出与错误的存储、查询返回各有长度限制，超过时可能截断。`TaskUpdate` 可在任务结束后修订输出，但不会改写状态或错误；`TaskStop` 使用 Task 记录的任务 ID，后台 Agent 回执中的代理 ID 属于另一条执行链路。

典型用法：并行探索后，由主 Agent 汇总并决定自行实现或委派，再通过验证证据完成交付。

```text
并行探索：多个 explore Agent 分别调查
  -> 主 Agent 汇总结论与实施方案
  -> 实现（主 Agent 完成或委派）
  -> verification Agent 执行验证
  -> 提交命令、输出与验证结论
  -> 主 Agent 审阅证据并交付
```

需要单独跟踪长命令或后台代理任务时，用 `TaskCreate` 启动、`TaskList`／`TaskGet` 查询，必要时用 `TaskStop` 请求取消。计划拆解与进度展示使用 `TodoWrite`。

---

## 🧩 MCP 工具扩展

ZhikunCode 的 [MCP（Model Context Protocol）](https://modelcontextprotocol.io/) 客户端支持 STDIO、SSE、HTTP 和 WebSocket，用于接入外部服务；另有可选 HTTP JSON-RPC 服务端入口，两者覆盖范围不同。

### 预置 MCP 服务

当前注册表收录 17 个预置服务、83 个白名单工具，不代表全部已连接或逐项实测。运行时还需通过服务开关、凭证、端点、账号开通／额度及远端工具列表检查，以管理页状态和实际调用为准。

- **默认开启（4 个）**：智谱联网搜索、高德地图、千问图像、万相视频。
- **默认关闭（13 个）**：新闻、法律、企业信息、文本内容审核、A 股金融数据、企业知识产权、万方文献、arXiv、1688 选品、物流、贵金属、零售洞察、旅游消费。
- **上下文隔离**：关闭的服务不连接、不注册、不注入模型工具上下文，直接调用返回拒绝结果。
- **用户控制**：点击页面顶部的 **MCP** 徽章进入 **MCP 管理**。开关即时生效，状态默认保存在 `~/.zhikun/mcp-service-states.json`；Docker 使用持久化路径 `/app/data/mcp-service-states.json`。

| 工具 | 说明 | 来源 |
|------|------|------|
| **高德地图（13 项）** | IP 定位、天气、地理编码、地点搜索、距离与路线规划 | DashScope MCP |
| **千问图像生成（3 项）** | 文生图、单图编辑和多图融合 | DashScope Streamable HTTP MCP |
| **万相视频生成（8 项）** | 文生视频、图生视频、数字人和首尾帧视频的异步提交与查询 | DashScope Streamable HTTP MCP |
| **网络搜索 Pro** | 联网搜索，返回网页摘要 | DashScope MCP |
| **OneKey 新闻 / 法律 / 企业 / 内容审核（13 项）** | 外部数据查询与文本安全 | DashScope Streamable HTTP MCP |
| **东方财富妙想（11 项）** | 股票行情、公告、研报、财务、资金与市场数据查询 | DashScope Streamable HTTP MCP |
| **企业知识产权（1 项）** | 企业知识产权信息查询 | DashScope Streamable HTTP MCP |
| **万方文献（2 项）** | 学术文献检索与详情查询 | DashScope Streamable HTTP MCP |
| **arXiv 论文（4 项）** | 论文搜索、详情、作者与分类查询 | DashScope SSE MCP |
| **1688 选品（1 项）** | 商品选品与货源查询 | DashScope Streamable HTTP MCP |
| **物流快递（2 项）** | 快递物流查询与轨迹跟踪 | DashScope SSE MCP |
| **贵金属行情（6 项）** | 黄金、白银等贵金属行情与市场数据 | DashScope Streamable HTTP MCP |
| **连锁商业 / 茶饮洞察（8 项）** | 连锁品牌、门店、商圈与茶饮市场洞察 | DashScope Streamable HTTP MCP |
| **旅游洞察（10 项）** | 景区、客流、酒店与旅游市场数据分析 | DashScope Streamable HTTP MCP |

### 可选外部 MCP 服务

GitHub、Context7 和 Alibaba Cloud Ops 通过 `.env` 中的 `ZHIKUN_MCP_SERVERS` 配置。配置只会让服务进入可选列表，三个服务均默认关闭，必须由用户在 **MCP 管理** 页面手动启用。不要使用 `MCP_SERVERS`：该名称会与 Spring Boot 的 `mcp.servers` 配置绑定冲突。完整 JSON 模板见 [`.env.example`](.env.example)，真实密钥不得提交到 Git。

| 服务 | 默认状态（配置后） | 工具白名单 |
|------|------------------|------------|
| **GitHub MCP Server** | 默认关闭，需在管理页手动启用 | `get_me`、`get_file_contents`、`search_code`、`list_branches`、`list_commits`、`list_pull_requests`、`pull_request_read`；强制只读和 Lockdown 模式 |
| **Context7** | 默认关闭，需在管理页手动启用 | `resolve-library-id`、`query-docs` |
| **Alibaba Cloud Ops** | 默认关闭，需在管理页手动启用 | `ECS_DescribeInstances`、`ECS_DescribeRegions`、`ECS_DescribeSecurityGroups`、`OSS_ListBuckets`、`OSS_ListObjects` |

```bash
# 仅示例占位符；请将真实值放入已被 Git 忽略的 .env
CONTEXT7_API_KEY=your-context7-api-key
ALIBABA_CLOUD_ACCESS_KEY_ID=your-access-key-id
ALIBABA_CLOUD_ACCESS_KEY_SECRET=your-access-key-secret
GITHUB_TOOLS=get_me,get_file_contents,search_code,list_branches,list_commits,list_pull_requests,pull_request_read
GITHUB_READ_ONLY=1
GITHUB_LOCKDOWN_MODE=1
ALIBABA_CLOUD_OPS_VISIBLE_TOOLS=ECS_DescribeInstances,ECS_DescribeRegions,ECS_DescribeSecurityGroups,OSS_ListBuckets,OSS_ListObjects
# ZHIKUN_MCP_SERVERS=<从 .env.example 复制 JSON 模板>
```

修改 `.env` 或外部 MCP 连接配置后需要重启；重启后还需在 **MCP 管理** 页面手动启用所需服务。之后在管理页启用或关闭服务无需再次重启。

本地外部服务还需要对应运行程序：GitHub wrapper 需要官方二进制或可用 Docker；Alibaba Cloud Ops wrapper 需要专用运行环境或 `uvx`。镜像已包含对应运行依赖；本地仅把 `scripts/mcp` 加到 PATH 或填写 Key 并不会安装它们。参见 [GitHub wrapper](scripts/mcp/zhikun-github-mcp) 和 [Alibaba wrapper](scripts/mcp/zhikun-alibaba-cloud-ops-mcp)。

### 自定义 MCP 工具

管理员可在 `configuration/mcp/mcp_capability_registry.json` 中注册新的 MCP 工具。这里的 `enabled` 是工具白名单，不是用户服务开关：

```json
{
  "id": "mcp_your_tool",
  "name": "你的工具名称",
  "toolName": "mcp_server_tool_name",
  "url": "https://your-mcp-server/mcp",
  "transportType": "HTTP",
  "domain": "your_domain",
  "category": "MCP_TOOL",
  "enabled": true
}
```

### MCP 协议能力增强

- **MCP Roots 范围声明**：向服务声明工作区根；不构成对远端进程的操作系统沙箱
- **MCP 长操作进度追踪**：实时显示 MCP 工具执行进度，支持用户取消
- **MCP Schema 智能压缩**：自动压缩大型工具参数描述，减少 LLM 上下文占用
- **MCP 服务级上下文隔离**：关闭的服务不会建立连接、注册工具或向模型上下文注入 Schema
- **MCP 失败处理**：远端工具报告错误时保留失败状态及诊断；连接不可用、超时或调用异常时返回对应错误，不以历史成功结果替代。

### MCP 协议传输层

| 客户端传输 | 典型连接方式 |
|---|---|
| STDIO | 启动本地子进程并通过标准输入输出通信 |
| SSE | 连接远端 SSE 服务 |
| HTTP | 连接远端 HTTP MCP 端点 |
| WebSocket | 连接 WebSocket MCP 服务 |

服务端在 `mcp.server.enabled=true` 时提供 `/mcp` HTTP JSON-RPC 入口；不能据此宣称四类服务端均已接通。STDIO 服务端类尚无启动调用方，WebSocket `/app/mcp` 属于界面管理通道。实现见 [MCP 源码](backend/src/main/java/com/aicodeassistant/mcp/)。

---

<a id="️-内置工具集"></a>
## 🛠️ 内置工具集

ZhikunCode 提供 **40+ 内置工具**，并支持 MCP、插件与平台条件工具动态扩展；最终可用集合以当前部署、Feature Flag 和已连接的 MCP Server 为准：

| 分类 | 工具 | 说明 |
|------|------|------|
| **文件操作** | Read、Write、Edit、NotebookEdit | 读取、写入、编辑文件（原子写入+SHA-256冲突检测），支持 Jupyter Notebook；Read 支持大图片自动外置化（>50KB 转 JSON 引用，由 ImageRefInjector 按需注入） |
| **代码搜索** | Grep、Glob、ToolSearch、LSP、Snip | 正则搜索、文件匹配、工具搜索、LSP 语言服务（含调用层级分析）、代码片段、智能分层搜索（作用域感知 4 层优先级路由） |
| **命令执行** | Bash、PowerShell、REPL | Shell 执行（授权、动态超时分类与受控恢复提示；可选沙箱不覆盖全部路径，不自动重试）、Windows PowerShell、交互式 REPL 会话 |
| **Git 操作** | Git、Worktree | Git 命令执行、Worktree 管理 |
| **Web 工具** | WebSearch、WebFetch、WebBrowser | 网络搜索、网页抓取、浏览器自动化 |
| **Agent 委派** | Agent | 五种角色、独立上下文、并行执行、同步或后台结果回收 |
| **任务管理** | TaskCreate、TaskGet、TaskList、TaskUpdate、TaskStop | 当前会话的后台执行、状态与结果查询、输出更新和取消请求 |
| **交互** | AskUserQuestion、Brief、Sleep、TodoWrite | 用户提问、基础上下文展示、等待、任务清单；多选问题须显式设置 multiSelect |
| **定时任务** | CronCreate、CronList、CronDelete | 定时任务管理 |
| **计划工具** | EnterPlanMode、ExitPlanMode、VerifyPlanExecution | EnterPlanMode／ExitPlanMode 尚未接入权限切换，返回 PLAN_MODE_UNAVAILABLE；VerifyPlanExecution 提供文件、内容与命令结果检查 |
| **配置** | Config、SyntheticOutput | 配置管理、合成输出 |
| **监控** | Monitor、CtxInspect、TerminalCapture | 系统监控、上下文检查、终端输出捕获 |
| **验证** | VerifyJourney | 浏览器交互或 HTTP API 断言；auto 按步骤为整次旅程选择验证器，不是任意混合步骤切换。需运行验证开关与对应 Python 能力就绪 |
| **MCP 扩展** | MCP 工具适配器 | 连接外部 MCP 服务（动态注册） |

`PLAN` 权限模式仍然可用，与计划面板相互独立。`/plan` 和 `/plan on [名称]` 显示前端计划面板，`/plan off` 隐藏面板；这些命令不会改变会话权限。`EnterPlanMode`／`ExitPlanMode` 工具也不会切换权限、提交审批或展示计划文件。

后台任务的创建、查询、输出更新与取消流程，以及它们和 `TodoWrite` 的用途区别，见上文“Agent 委派与后台任务”。

`Edit` 更新已有文件时要求非空匹配；已有空文件请用 `Write` 填入内容。不存在的文件仍可通过 `Edit` 的空 `old_string` 创建。

---

## 📈 可视化

ZhikunCode 提供多种可视化能力，展示 AI 编程过程中的数据和状态：

| 功能 | 说明 |
|------|------|
| **Mermaid 图表渲染** | AI 回复中的 mermaid 代码块自动渲染为交互式矢量图，支持复制 SVG / 下载 PNG |
| **API 序列图** | 自动从会话中提取工具调用记录，生成 Mermaid 序列图，支持过滤和详情查看 |
| **Git 时间线** | 可视化 Git 提交历史，支持 Diff 查看和 Blame 视图，commit 类型自动着色 |
| **工具进度可视化** | 工具执行过程显示进度条、ETA 预估和迷你日志查看器 |
| **文件树导航** | 侧边栏项目文件树，支持搜索过滤、虚拟滚动、文件类型图标 |
| **代码复杂度 Treemap** | 基于 recharts 的交互式矩形面积图，面积映射代码行数(LOC)，颜色映射风险等级(A-E)，支持钻取导航、语言/风险过滤、统计卡片，Python radon + tree-sitter 多语言分析 |
| **变更影响链路** | 基于 @xyflow/react 的 DAG 可视化，展示代码变更的影响传播路径，LibCST 精准解析 Python 调用图，BFS 逐层传播分析，节点类型/置信度/影响层级一目了然 |
| **API 契约可视化** | 合并 ZhikunCode 自身运行中的 Java + Python 双端 OpenAPI 规范（不自动推断任意用户项目的接口），端点列表按 Tag 分组、HTTP 方法颜色编码、Schema 递归展示，支持 All/Java/Python 数据源切换 |
| **代码→图表自动生成** | 输入代码文件路径自动生成 Mermaid 时序图/流程图，Python LibCST + tree-sitter 多语言解析，调用链 BFS 遍历自动识别 Controller/Service/Repository 参与者，五维置信度评分(0-1)，Monaco Editor 实时编辑源码，SVG 复制 / PNG 下载导出，支持 1-5 级遍历深度控制 |
| **代码路径追踪可视化** | 基于 @xyflow/react 的静态代码调用路径可视化，Python CodePathTracer 正向 BFS 遍历 + 六层分层标注（Controller/Service/Repository/Database/External/Utility），dagre TB 布局算法自动排列节点，自定义 LayerNode 按层级着色，MiniMap 全局导览 + LayerStatsBar 层级统计，支持 API 端点扫描、参数追踪、节点点击详情、maxDepth 深度控制，通过侧边栏"代码路径" Tab 进入 |
| **运行时验证进度面板** | JourneyVerifyPanel — 实时显示验证步骤执行状态（waiting/executing/passed/failed）、STOMP 推送进度、证据链关联 |
| **RV-4 证据包查看器** | 查看已保存的 screenshot / command / console / test / video / har / diff 等证据类型，实际产出依步骤与环境而定；移动证据审批尚未完整接通。REST 入口提供证据查询与 Blob 下载：`GET /api/evidence/{bundleId}`、`GET /api/evidence/session/{sessionId}`、`GET /api/evidence/blob/{sha256}` |
| **Activity Panel 活动面板** | 三层卡片展示（L1 紧凑 → L2 展开 → L3 Portal），实时显示工具执行状态、Signal 风险标记、审批决策状态 |

> `/visualize` 通过 VisualizationAutoRouter 推送 mermaid／json／text。分析页面可从侧边栏进入；部分页面依赖 Python 服务及前端同源代理，基础 Docker 部署不能保证全部可用。调用图包含语言相关解析与启发式分析，置信度不等于准确率，也不是完整运行时追踪。

验证时可要求模型打开实际页面完成指定操作，或调用 VerifyJourney 对接口状态／JSONPath 作断言，再检查工具结果和留存证据。自动模式为整次旅程选择浏览器或 HTTP 验证器；证据存储成功与测试行为通过是不同结果。

---

<a id="️-配置说明"></a>
<a id="configuration"></a>
## ⚙️ 配置说明

### 模型选择与路由

主对话使用用户选择的模型；子 Agent 按显式 `model` → 父查询模型 → `premium` 别名解析模型。快速查询与压缩摘要使用各自配置：快速查询默认百炼 `deepseek-v4.1-flash`，压缩摘要默认 DeepSeek 官方直连 `deepseek-flash`；只有当前模型不支持图片时才触发视觉兜底。百炼与 DeepSeek 直连的模型 ID、端点和 Key 独立。

百炼 V4.1 Flash 的流式请求、快速查询和压缩摘要统一发送 `thinking.type=enabled`、字符串 `reasoning_effort="max"`；DeepSeek 官方直连的流式请求与压缩摘要同样发送该参数组合（`chatSync` 非流式快速查询不注入 thinking）。2026-09-15 Token Plan 实测接受 `"max"`、拒绝整数；未验证内部数值映射。

### 环境变量

配置模板在 [`.env.example`](.env.example)，完整默认值和开关以 [application.yml](backend/src/main/resources/application.yml) 与 [ModelRegistry](backend/src/main/java/com/aicodeassistant/llm/ModelRegistry.java) 为准。手动启动时需显式加载 `.env`，Docker 只传入 Compose 列出的变量；写入 `.env` 不等于已进入进程。修改启动配置后重启对应服务。

**多 Provider 配置（推荐）：**

| 变量 | 必填 | 默认值 | 说明 |
|------|:---:|--------|------|
| `LLM_PROVIDER_DASHSCOPE_API_KEY` | — | — | 千问/DashScope API Key |
| `LLM_PROVIDER_DASHSCOPE_TOKEN_PLAN_API_KEY` | — | — | 阿里云百炼 Token Plan 专属 API Key |
| `LLM_PROVIDER_DEEPSEEK_API_KEY` | — | — | DeepSeek API Key |
| `LLM_PROVIDER_MOONSHOT_API_KEY` | — | — | Moonshot/Kimi API Key |
| `LLM_PROVIDER_KIMI_CODE_API_KEY` | — | — | Kimi Code 订阅 API Key；留空不注册订阅渠道 |
| `LLM_PROVIDER_KIMI_CODE_MODELS` | — | k3.1-pro-highspeed,kimi-for-coding | Kimi Code 订阅模型列表；权限与额度取决于账号 |
| `LLM_PROVIDER_ZHIPU_API_KEY` | — | — | 智谱 GLM API Key |
| `LLM_PROVIDER_OPENROUTER_API_KEY` | — | — | OpenRouter API Key；留空不注册此 Provider |
| `LLM_PROVIDER_OPENROUTER_MODELS` | — | openrouter/anthropic/claude-fable-5.1 | OpenRouter 可用模型列表（逗号分隔） |
| `LLM_DEFAULT_MODEL` | — | deepseek-v4.1-flash | 默认模型（百炼 Token Plan）；不可用时回退到当前 Provider 的有效默认模型 |
| `LLM_FAST_MODEL` | — | deepseek-v4.1-flash | 快速辅助查询与分类器回退优先使用的模型 |
| `LLM_COMPACT_MODEL` | — | deepseek-flash | 对话压缩摘要模型（DeepSeek 官方直连），独立于快速模型选择 |
| `LLM_COMPACT_PROVIDER` | — | deepseek | 压缩摘要 Provider；需已注册该 Provider，配合 `LLM_COMPACT_MODEL` 使用 |
| `LLM_VISION_FALLBACK_MODEL` | — | deepseek-v4.1-flash | 当前模型不支持图片时优先使用的视觉模型 |

> 前端可切换已配置且可用的 Provider。当前 Docker 入口只检查 DashScope、Token Plan、DeepSeek、Moonshot、Zhipu、ZenMux 或旧式 Key；仅 Kimi Code、MiniMax、OpenRouter Key 时会退出，可先按本地三服务启动。

**WebSearch 配置（本地启动与 Docker Compose 均支持）：**

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `WEB_SEARCH_BACKEND` | auto | `auto`：已连接的 MCP zhipu-websearch → disabled；`deepseek`：显式启用 DeepSeek；也支持 `mcp`、`disabled` |
| `WEB_SEARCH_DEEPSEEK_API_KEY` | 空 | 专用 Key；未设置、空串或仅空白时复用 `LLM_PROVIDER_DEEPSEEK_API_KEY` |
| `WEB_SEARCH_DEEPSEEK_BASE_URL` | https://api.deepseek.com | API 根地址，不附加 `/v1` 或 `/v1/tools/search`；未设置或空白时使用默认值 |

设置 `WEB_SEARCH_BACKEND=deepseek` 并重启后启用收费搜索；`auto` 不会因存在 DeepSeek Key 自动启用它，调用失败也不自动切换后端。选定的 Key 来源按逗号拆分、trim、去空后固定使用首项，不参与 LLM Key 轮换；非空白专用值若没有可用项（如 `,,`）则禁用搜索。

DeepSeek 仅接入 Search，`include_content` 默认关闭。收到 HTTP 408、429、500–599 时最多 3 次尝试（含首次），单次请求 45 秒超时；可解析的 `Retry-After` 与本地退避取较大值，单次等待超过 30 秒或无法解析时停止重试，不提前重发。重试产生的等待会计入整个工具调用的实际耗时，但不占用单次请求的超时。

**OpenRouter / Claude Fable 5.1：** 在 `.env` 中填写 `LLM_PROVIDER_OPENROUTER_API_KEY`，重启服务后从模型列表选择 `Claude Fable 5.1（OpenRouter）`（`openrouter/anthropic/claude-fable-5.1`）。如需设为默认模型，增加 `LLM_DEFAULT_MODEL=openrouter/anthropic/claude-fable-5.1`。本地启动与 Docker Compose 均支持上述变量。

本次模型目录精简由项目主动进行，不表示供应商下架。OpenRouter **Provider 默认模型**从免费预览的 Union Alpha 改为收费的 Claude Fable 5.1，仅影响使用该 Provider 默认回退的请求；全局 `app.model.default` 与 `LLM_DEFAULT_MODEL` 的默认值仍为百炼 `deepseek-v4.1-flash`。实际计费以服务商账单为准。

接入复用现有 `llm.providers.*` → `MultiProviderConfiguration` → `OpenAiCompatibleProvider` 链路，端点为 `https://openrouter.ai/api/v1`，沿用流式输出、图片输入、工具调用和 Key 轮换。能力由 `ModelRegistry` 统一提供给 `/api/models`，无需新增前端分支。

**OpenRouter 强推理模型与渠道隔离：**

| 界面名称 | ZhikunCode 模型 ID | 上游模型 ID | 上下文 / 最大输出 |
|---|---|---|---|
| Claude Fable 5.1（OpenRouter） | `openrouter/anthropic/claude-fable-5.1` | `anthropic/claude-fable-5.1` | 1,000,000 / 128,000 |

Claude Fable 5.1 使用已有 OpenRouter Key，默认请求 `reasoning.effort=max`、`exclude=false`，不受通用 thinking 关闭开关降档；这是仓库注册与请求配置，账号可用性和上游规格需向服务商核实。上下文按注册表窗口配置，仍保留现有输出预留、压缩阈值和安全余量。单次输出预算由现有运行配置决定，上表是仓库登记上限，并非对服务商规格的独立保证，也非每次都生成该长度。

内部 ID 的 `openrouter/` 前缀只用于路由、能力、会话与重试隔离，发送请求时去掉；现有 ZenMux 的 `openai/gpt-6-astra`、`anthropic/claude-fable-5.1` 路由保持不变。OpenRouter 的模型列表若填写官方原始 ID `anthropic/claude-fable-5.1`，启动时也会自动规范化并去重，避免注册顺序决定渠道。其他重复 ID 明确拒绝歧义路由。

流式 thinking 使用现有展示事件；`reasoning_details`（含签名/加密数据）按原序保存，并仅对同 Provider、同模型的后续请求回传，切换渠道或模型不复用。费用展示采用仓库登记的参考价格，实际计费以账号和服务商账单为准。

原配置记录日期：2026-09-17；模型与服务商页面可能变化。参考：[Fable](https://openrouter.ai/anthropic/claude-fable-5.1)、[Reasoning 文档](https://openrouter.ai/docs/guides/best-practices/reasoning-tokens)。

**单 Provider 配置（向后兼容）：**

| 变量 | 必填 | 默认值 | 说明 |
|------|:---:|--------|------|
| `LLM_API_KEY` | ✅ | — | LLM 服务商的 API Key |
| `LLM_BASE_URL` | — | DashScope | LLM API 地址 |
| `LLM_DEFAULT_MODEL` | — | qwen3.8-max-0902 | 默认模型 |
| `LLM_MODELS` | — | 千问系列 | 可用模型列表（逗号分隔） |

> 如 `LLM_PROVIDER_*` 均为空，系统自动回退到单 Provider 模式。

**通用配置：**

| 变量 | 必填 | 默认值 | 说明 |
|------|:---:|--------|------|
| `ZHIKUN_PORT` | — | 8080 | Docker 映射的宿主机端口 |
| `SPRING_PROFILES_ACTIVE` | — | production | Spring 配置文件 |
| `JAVA_OPTS` | — | -Xms256m -Xmx1024m | JVM 参数 |
| `WORKSPACE_PATH` | — | ./workspace | 挂载到容器的工作目录 |
| `ZHIKUN_DEFAULT_WORKSPACE` | — | 服务进程目录；`./start.sh` 为仓库根 | 非 Web 客户端未指定 Project 时的服务端兜底目录，不自动授予普通编辑权限 |
| `ZHIKUN_WORKSPACE_ALLOWED_ROOTS` | — | 空 | 允许登记和浏览的服务端根目录，多个目录用逗号分隔；远程登记时必须配置 |
| `ZHIKUN_LOCAL_PICKER_ENABLED` | — | false（后端）；true（`./start.sh` 且两个相关变量均无非空配置） | 仅供直连本机桌面服务浏览本机文件系统；显式 `false` 和 allowed roots 配置均会保留，反向代理和生产部署应保持关闭并配置 allowed roots |
| `ZHIKUN_GLOBAL_DB_PATH` | — | `~/.config/ai-code-assistant/global.db` | Project 授权和全局配置数据库文件路径 |
| `ZHIKUNCODE_DATABASE_PROJECT_ROOT` | — | Project 工作目录 | Session 数据库根目录；Docker Compose 固定为 `/app/data` |
| `ALLOW_PRIVATE_NETWORK` | — | true（Docker） | 放行所有到达后端的来源，并非仅私网；远程部署见安全章节 |
| `LOG_DIR` | — | /app/log | 容器内日志目录 |
| `MCP_REGISTRY_PATH` | — | 自动配置 | MCP 能力注册表文件路径 |

**高级配置：**

| 变量 | 必填 | 默认值 | 说明 |
|------|:---:|--------|------|
| `FEATURE_BACKGROUND_AGENT_WAIT` | — | true | 等待并汇总当前父 Run 的后台 Agent 结果；设为 false 关闭 |
| `AGENT_TIMEOUT_MAX_WAIT_MINUTES` | — | 31 | 每批后台 Agent 结果的等待预算，单位分钟；不修改子 Agent 执行超时 |
| `ZHIKUN_COORDINATOR_MODE` | — | 0 | Feature flag，启用协调器模式（0=关闭，1=开启） |
| `ZHIKUN_DELETE_CONFIRM_CODE` | — | 空 | 删除会话二次确认验证码；配置后 Web 删除会话须输入该验证码，未配置保持原确认流程 |
| `LLM_PROVIDER_DASHSCOPE_MODELS` | — | qwen3.8-max-0902 | 按量计费 DashScope 可用模型列表（逗号分隔；实际目录可动态扩展） |
| `LLM_PROVIDER_DASHSCOPE_TOKEN_PLAN_MODELS` | — | qwen3.8-max,qwen3.8-flash,deepseek-v4.1-flash,bailian/glm-5.3 | 百炼 Token Plan Provider 可用模型列表；与普通 DashScope、DeepSeek 直连配置相互独立 |
| `LLM_PROVIDER_DEEPSEEK_MODELS` | — | deepseek-flash | DeepSeek 可用模型列表；默认使用 V4.1 Flash（逗号分隔） |
| `LLM_PROVIDER_MOONSHOT_MODELS` | — | kimi-k3.1-pro-highspeed | Moonshot 可用模型列表（逗号分隔） |
| `LLM_PROVIDER_ZHIPU_MODELS` | — | glm-5.3,glm-5.3-flash | 智谱 GLM 可用模型列表（逗号分隔） |

上述两个后台等待变量需进入后端进程环境；Docker 部署须通过 Compose override 的 `environment` 显式传入，仅写入项目 `.env` 不会自动透传。

**上下文管理配置（application.yml）：**

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `context.cascade.incremental-collapse.enabled` | true | 启动时决定是否注册 IncrementalCollapseManager；设为 false 时禁用该组件触发的增量折叠，修改后需重启 |
| `context.cascade.incremental-collapse.segment-turns` | 10 | YAML 声明值；实际 10 轮来自代码常量 |
| `context.cascade.incremental-collapse.session-timeout-minutes` | 30 | YAML 声明值；当前跟踪状态的 30 分钟清理条件来自代码常量，不是任务超时 |
| `features.flags.CACHED_MICROCOMPACT` | true | 控制系统提示中的微压缩说明段，不关闭实际 ContextCascade 微压缩 |
| `features.flags.TOKEN_BUDGET` | false | 控制系统提示中的预算说明段，不代表 TokenBudgetGuard 默认关闭 |
| `features.flags.SELF_CORRECTION_LOOP` | false | 执行失败自动诊断修复循环（编译错误/测试失败，最多重试3次） |
| `features.flags.PRECISE_TOKENIZER` | false | 精确 Token 计数（调用 Python tiktoken，替代字符估算） |
| `features.flags.GIT_DIFF_TRACKER` | false | Git 变更追踪与编辑历史聚合 |
| `features.flags.SEARCH_STRATEGY_ROUTER` | false | 作用域感知分层搜索策略路由 |
| `RUNTIME_VERIFICATION` | true | 启用运行时验证框架（开启后前端显示验证进度面板，需同时配合 BROWSER_AUTOMATION 或 HTTP_API 能力域） |

通过 `FeatureFlagService` 读取的功能开关，可用后端进程环境变量 `FEATURE_<大写键名>` 覆盖 `features.flags` 中的对应值，例如 `FEATURE_PRECISE_TOKENIZER=true`；此规则不适用于任意配置项。手动启动需先加载环境变量，Docker 则需在 Compose 的 `environment` 中显式传入；仅写入根 `.env` 不会自动透传。修改后重启后端生效。

### Docker 资源限制

默认资源配置（可在 `docker-compose.yml` 中调整）：

| 配置项 | 默认值 |
|--------|--------|
| 内存上限 | 4GB |
| 内存预留 | 1GB |
| 健康检查间隔 | 30s |
| 启动等待时间 | 60s |

> 首次构建同时涉及 Maven 与前端构建；若出现内存不足，按构建日志增加 Docker 可用内存。表中为 Compose 声明值，实际限制取决于运行环境是否应用该配置。

---

<a id="troubleshooting"></a>
## ❓ FAQ

| 现象 | 先检查什么 |
|---|---|
| 构建／启动失败 | JDK 21、Node 22、依赖下载；Docker 看 `docker compose logs --tail=100 zhikuncode`，手动启动看各终端 |
| Key 无效／没有工具调用 | 选择的 Provider 与模型、实际服务商错误、Docker 入口支持范围和模型工具调用能力 |
| 端口占用 | 先识别 8080／8000／5173 的监听进程；不要终止不属于本次启动的服务 |
| 找不到项目／挂载不可写 | 本地使用真实路径；Docker 选择容器路径，检查宿主卷权限和 Docker Desktop 文件共享 |
| Python 活着但能力不可用 | `/api/health/capabilities` 中的 available／reason、实际解释器、依赖和 Chromium |
| 页面断线／审批未出现 | WebSocket 代理与服务日志；重连恢复过程，默认完全访问可能不弹审批，后端重启则旧运行中断 |

使用 `start.sh` 时日志位于 `log/backend-console.log`、`log/python-console.log`、`log/frontend-console.log`。Compose 的 `zhikun-data` 卷保存 `/app/data` 下的会话和全局配置，项目来自独立挂载；备份应同时覆盖两者，保留数据时不要执行 `docker compose down -v`。错误信息中先移除凭证再分享。


<details>
<summary><b>Q1：支持哪些大模型？</b></summary>

提供 OpenAI 兼容接口接入路径和多 Provider 配置，例如：

- **千问 / DashScope**（国内直连，默认推荐）
- **DeepSeek**（国内直连）
- **Moonshot / Kimi**（国内直连）
- **智谱GLM**（国内直连）
- **MiniMax**（国内直连）
- **OpenAI gpt-5.6-sol / gpt-5.4-mini**（需外网访问）
- **Anthropic Claude**（通过 OpenAI 兼容 API）
- **本地模型**（通过 Ollama、vLLM 等）

配置 `LLM_BASE_URL`、`LLM_API_KEY` 和模型 ID 后仍需验证兼容性，尤其工具调用、流式输出、图片和推理字段；兼容协议名称不保证每个模型能力都可用。

</details>

<details>
<summary><b>Q2：Docker 部署需要什么配置？</b></summary>

**最低要求：**
- Docker 20.10+
- Docker Compose V2
- 4GB+ 可用内存
- 网络能访问 LLM API 端点（用千问的话国内网络就行）

**部署只需 3 步：**
```bash
git clone https://github.com/zhikunqingtao/zhikuncode.git && cd zhikuncode
cp .env.example .env  # 编辑填入 API Key
docker compose up -d --build  # 构建并启动
```

打开 `http://localhost:8080` 即可使用。

</details>

<details>
<summary><b>Q3：数据存在哪里？安全吗？</b></summary>

**应用状态和数据库保存在本地**；提示词、相关代码上下文和其他模型输入会发送给你配置的 LLM 服务商：

- **会话数据** — SQLite 数据库，存储在 Docker Volume `zhikun-data` 中
- **项目代码** — 通过 Volume 挂载你本地的项目目录
- **API Key** — 由部署环境和配置管理；排查日志或分享资料前应移除凭证

> **旧授权数据库升级边界：** 当前 V015 持久交互与 V019 授权表不保证兼容旧版授权 schema，旧权限不会自动转成当前 Grant。旧表可能缺少必需列或与新表同名，迁移校验失败会阻止启动。升级前应停止应用并一致备份项目库、全局库及相关数据，先在隔离副本验证目标版本迁移与启动；不兼容时停止升级、保留原库，采用经过验证的迁移方案后重新授权。项目库也保存会话和消息，不能直接删库或无条件重建；只有明确可丢弃的开发库才适合重建。
>
> **已验证兼容后的存储路径迁移：** Compose 现在把 Session 数据库和全局配置数据库分别持久化到 `zhikun-data` 中的 `/app/data/.ai-code-assistant` 和 `/app/data/global.db`。以下步骤仅处理旧容器数据的复制与恢复，不转换授权 schema。确认上述副本验证通过后，停止并备份 `/app/.ai-code-assistant` 和 `/app/.config/ai-code-assistant/global.db`，再恢复到新位置；全程不要使用 `docker compose down -v`。
>
> ```bash
> docker compose stop zhikuncode
> docker cp zhikuncode:/app/.ai-code-assistant ./ai-code-assistant.backup
> docker cp zhikuncode:/app/.config/ai-code-assistant/global.db ./global.db.backup
> docker compose down
> docker compose create --build zhikuncode
> docker cp ./ai-code-assistant.backup \
>   zhikuncode:/app/data/.ai-code-assistant
> docker cp ./global.db.backup \
>   zhikuncode:/app/data/global.db
> docker compose run --rm --user root --entrypoint chown \
>   zhikuncode -R zhikun:zhikun \
>   /app/data/.ai-code-assistant /app/data/global.db
> docker compose start zhikuncode
> ```
>
> `docker compose create` 只创建、不启动新容器；必须在首次 `start` 前完成恢复和 `chown`。旧路径不存在时可跳过对应的 `docker cp` 和恢复目标。确认 Session、配置和已授权 Project 正常后再删除本地备份。

实际请求发往你配置的模型／MCP／云服务端点；如果选择聚合 Provider 或代理，请求也会经过该渠道。自托管不代表所有输入只留在本地。

</details>

<details>
<summary><b>Q4：支持内网 / 离线部署吗？</b></summary>

可以部署在内网；使用云模型、联网搜索、MCP 或发布时仍需访问相应端点。完全离线还需提前准备构建依赖、模型和运行资源，并验证所选模型的工具调用兼容性。

- **使用国产模型（千问/DeepSeek）：** 国内网络直连，无需科学上网
- **本地模型：** 可配置 Ollama 的兼容端点；例如 Docker Desktop 下宿主服务为 `http://host.docker.internal:11434/v1`，Linux 需另行配置宿主地址。是否适合当前任务需实际验证
- **企业内网：** 只需确保服务器能访问 LLM API 端点即可

</details>

<details>
<summary><b>Q5：Agent 委派与后台任务怎么用？</b></summary>

在对话中说明任务、分工范围和预期结果，例如：

> “让两个 explore Agent 并行调查认证接口和前端登录流程，分别给出文件位置与风险；你汇总结论后提出修改方案。实现完成后，再让 verification Agent 执行已授权的测试并报告证据。”

主 Agent 可同步等待子任务，或通过 `run_in_background=true` 在推进其他工作后收集当前 Run 的结果。需要隔离文件修改时可指定 `isolation=worktree`，执行基于已提交 HEAD 的独立工作区，并在安全检查通过后合回。

需要持续查询的后台执行，可要求“用 TaskCreate 运行测试，随后用 TaskGet 查询结果”；取消时按该任务 ID 使用 TaskStop，并查询停止是否确认。TodoWrite 用于计划清单。具体角色、模型选择与交付边界见上文“Agent 委派与后台任务”。

</details>

<details>
<summary><b>Q6：和 VS Code 插件（Copilot/Cline）冲突吗？</b></summary>

**不冲突。** ZhikunCode 是独立的 Web 应用，不依赖任何 IDE，不需要安装插件。

你可以同时使用：
- **VS Code + Copilot** —— 做行级代码补全
- **ZhikunCode** —— 做对话式 Agent 编程、复杂任务编排

两者可并用；同时修改同一项目时应协调文件范围，避免覆盖彼此改动。

</details>

<details>
<summary><b>Q7：怎么贡献代码？</b></summary>

欢迎贡献！详细流程见 [CONTRIBUTING.md](CONTRIBUTING.md)。

简单步骤：
1. Fork 仓库
2. 创建功能分支 (`git checkout -b feature/your-feature`)
3. 提交代码
4. 创建 Pull Request

推荐从标记为 `good first issue` 的 Issue 开始。

**开发环境需要：** JDK 21、Node.js 22+、Python 3.11~3.12、Maven 3.9+

</details>

<details>
<summary><b>Q8：为什么选择 Java + React + Python 三端架构？</b></summary>

每种技术选型都有明确的理由：

- **Java 21 + Spring Boot（后端）：**
  - 强类型 + 成熟的企业级生态，代码可维护性强
  - Spring WebSocket 原生支持实时通信
  - Virtual Thread（虚拟线程）天然适合多 Agent 并发执行
  - 企业 IT 团队容易接受和部署

- **React 18 + TypeScript（前端）：**
  - 组件化开发，状态管理成熟（Zustand）
  - TypeScript 提供类型安全
  - Vite 构建速度快，开发体验好
  - TailwindCSS 实现高效的 UI 开发

- **Python FastAPI（分析服务）：**
  - Python 生态在代码分析、AST 解析方面成熟
  - FastAPI 异步性能好
  - 作为独立服务，不影响主后端的稳定性

</details>

<details>
<summary><b>Q9：Docker 部署遇到问题怎么排查？</b></summary>

**容器启动后显示 unhealthy：**

```bash
# 查看容器状态
docker ps -a

# 查看启动日志（Java 启动通常需要 30-60 秒）
docker logs zhikuncode

# 查看健康检查详情
docker inspect --format='{{json .State.Health}}' zhikuncode | python3 -m json.tool
```

**常见启动失败原因：**
- `LLM_API_KEY is not configured` — 未配置 API Key，请检查 .env 文件
- `Unable to access jarfile` — 镜像构建不完整，尝试 `docker compose up --build`
- 内存不足 — 默认需要 4GB，可在 docker-compose.yml 中调整 `deploy.resources.limits.memory`

**查看运行时日志：**
```bash
# 实时跟踪日志
docker logs -f zhikuncode

# 进入容器查看日志文件
docker exec -it zhikuncode ls -la /app/log/
docker exec -it zhikuncode tail -100 /app/log/app.log
```

**关于 `ALLOW_PRIVATE_NETWORK`：**

当前镜像默认 `true` 会放行所有到达后端的来源；容器网络不能替代入口认证。请按[安全章节](#security)配置认证、TLS、端口绑定和代理边界。设为 false 需通过 Compose 显式传入，之后仍要核对认证行为。

**调整 JVM 内存：**

在 `.env` 中设置：
```bash
JAVA_OPTS="-Xms512m -Xmx2048m --enable-preview"
```

</details>

<details>
<summary><b>Q10：8080 端口被占用怎么办？</b></summary>

修改 `.env` 文件中的端口配置：

```bash
ZHIKUN_PORT=9090  # 改为任意未被占用的端口
```

然后重新启动：
```bash
docker compose down
docker compose up -d
```

访问 `http://localhost:9090` 即可。

</details>

---

<a id="resources"></a>
继续阅读：[案例资料](docs/case-studies/) · [系统架构图](https://zhikunqingtao.github.io/zhikuncode/ZhikunCode-Architecture.html) · [能力总览](https://zhikunqingtao.github.io/zhikuncode/ZhikunCode-Capability-Overview.html) · [会话合并设计](docs/session-merge-architecture-v2.md) · [安全问题报告](SECURITY.md)。

## 🤝 贡献

我们欢迎任何形式的贡献 —— Bug 修复、新功能、文档改进都可以。

详见 [CONTRIBUTING.md](CONTRIBUTING.md)。

---

## 📄 开源协议

本项目使用 [MIT License](LICENSE) 开源协议。

---

## 📬 联系

- **邮箱：** alizhikun@gmail.com
- **GitHub Issues：** [提交问题](https://github.com/zhikunqingtao/zhikuncode/issues)

---

## ⭐ Star History

如果这个项目对你有帮助，欢迎点个 Star ⭐

<div align="center">
  <a href="https://star-history.com/#zhikunqingtao/zhikuncode&Date">
    <img src="https://api.star-history.com/svg?repos=zhikunqingtao/zhikuncode&type=Date" alt="Star History Chart" width="600" />
  </a>
</div>

---

<div align="center">
  <p>用 ❤️ 和 AI 构建</p>
</div>
