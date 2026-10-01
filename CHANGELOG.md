# Changelog

本文件记录 ZhikunCode 项目的所有重要变更。

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### Added
- 新增适配 PC、平板和手机的 Skill 管理页，支持搜索、详情及独立开关；状态由后端持久化并跨设备共享，现有和新增技能默认开启。
- Web 新增独立快捷键帮助对话框；会话列表支持按文件夹分组。
- 新增浏览器截图粘贴的固定 OSS 快速通道：无需 Skill 或额外 LLM 调用，上传后将可信 HTTPS 图片地址直接交给视觉模型；未配置 OSS 时给出明确提示。
- OSS 凭证支持 ECS RAM Role/IMDSv2 与本地阿里云默认凭证链双模式，覆盖本地一键启动和 Docker Compose 透传。
- 新增可浏览、持久且可撤销的 Project 文件夹授权；Project 作为信任范围和默认相对路径根，普通操作在其内免打扰，范围外操作进入常规授权，敏感路径和高风险操作仍需逐次确认。
- 新增百炼 Token Plan 渠道 `qwen3.8-flash`（官方规格：1M 上下文、131072 最大输出、多模态输入、支持思考模式），接入方式与 `qwen3.8-max` 一致。
- 新增百炼 Token Plan 渠道 `deepseek-v4-pro-0813` 与 `deepseek-v4-flash-0731`，并与 `qwen3.8-max` 统一标注“百炼”。
- 新增 `deepseek-v4-flash-vision-exp` 图片理解模型，作为 DeepSeek 系列的专属视觉兜底。
- 同一 LLM Provider 支持逗号分隔多 API Key（ZenMux 订阅 Key `sk-ss-v1-` 优先、按量 Key `sk-ai-v1-` 兜底），402 quote_exceeded / 404 model_not_available / 429 时自动冷却切换。
- 新增 OpenRouter Provider：`stealth/union-alpha`（Union Alpha，当前免费预览）及强推理模型 `openrouter/openai/gpt-6-astra`、`openrouter/anthropic/claude-fable-5.1`（默认 `reasoning.effort=max`）；内部 `openrouter/` 前缀用于渠道隔离，复用 OpenAI 兼容链路与 Key 轮换。
- 新增可选的删除会话二次确认验证码：配置 `ZHIKUN_DELETE_CONFIRM_CODE` 后，Web 删除会话须在确认气泡中输入该验证码（随 `X-Delete-Confirm-Code` 请求头由后端校验）；未配置时维持原确认流程，配置查询失败不缓存失败结果，下次挂载重试。
- Run API 新增 `usageStatus`（`known/partial/unknown`）与 `verificationScope`（`artifact_manifest/unknown`）字段：用量口径限定为「本 Run 的已观测消费」，任务概览按同一 Run 联展示用量与状态，unknown 显示未报告、partial 仅统计已报告部分，不估算、不归集子 Run；产物验证字段范围限定为 `artifact_manifest`，未知状态安全标为范围未知。
- 附件存储新增 `.metadata/<uuid>.json` 侧车文件保存原始显示名（版本、UUID、存储文件名、原名，不新增数据库表）；上传改为同文件系统临时文件 + 先发布元数据、再原子发布 payload，失败只清理本次文件。
- 新增第四主题「星舰 HUD」：深空 HUD 视觉（切角面板/机械角标/罗盘雷达/网格地板/扫描线）、三个独立特效开关（电影级视觉/事件特效/动效三档）、TOKEN 警告琥珀脉冲、开机自检过渡、DRIFT 同步徽章；支持移动端面板开关。
- `tools/office-regression/`：新增 XLSX/DOCX/PPTX/HTML 四类离线回归基建——固定 Playwright / LibreOffice / CJK 字体 / poppler 与基础镜像 digest，容器内 `--network none` 运行、证据输出到仓库外；四组均含正例与命中指定检测原因的受控反例（不允许以任意失败充当检出）。另新增后端 `BashTool → ManagedProcessRunner` 执行确定性生成/结构检查脚本的回归测试。Office 三格式当前无产品级生成入口，脚本属测试资产，本回归不外推为模型办公能力或生产保证；研究来源组不在本批，未接入 CI 门禁（按手动入口运行）。

### Changed
- 斜杠命令描述与回执改为与真实行为一致：`/model` 仅列出可用模型、不再标记 `(current)` 或读取应用状态猜测当前模型，描述与前端内置命令列表同步为「切换请使用模型选择器」；`/fast`、`/vim`、`/effort`、`/output-style`、`/color` 的描述标注「尚未接入」、回执统一说明未改变任何设置；`/theme` 描述改为引导使用外观设置，不再宣称由命令切换主题。`/keybindings` 当前无 UI 呈现，见 Known Issues。
- 会话指导与工具契约按真实工具名对齐后**首次真正生效**（此前门控判断的是 `AgentTool`／`SkillTool` 注册名，与模型实际看到的 `Agent`／`Skill` 不一致，属模型输入行为变化）：修复 Agent／Skill 使用指导的启用门控，技能示例改为明确形式（如 `Skill({skill:"debug", args:"..."})`）并说明 `commit`／`review` 等名称同时存在命令与技能时的区别；删除 Fork／Spawn 假承诺与已无读取者的 `FORK_SUBAGENT` 死段（不再声称 fork 提供「完全文件隔离的 Git worktree」或工具输出不进入主上下文），改为按 schema 如实说明 `run_in_background: true`（结果默认以独立消息送达，可被环境配置关闭）与 `isolation: "worktree"`；子代理类型展示名改为 schema 枚举值 `explore`／`plan`／`verification`／`general-purpose`／`guide`，并删除 Explore 代理提示中的幽灵工具 `search_codebase`／`search_symbol`（改为 Glob／Grep／Read）；`Read` 示例改用真实参数 `offset`（0 基）与 `limit`（行数）；`boundary_conditions` 的 Grep 结果上限改为 250、Glob 改为 200，Grep 段改用真实的文件过滤参数（glob／include／exclude），Bash 段改为如实描述超时与后台行为（未指定时按命令类别给出建议超时、约 2 分钟为命令类别缺省值的兜底、上限受配置约束默认 10 分钟、超时后命令被终止并返回超时信息、长命令建议写入脚本文件；后台进程立即返回 PID、输出不被捕获、无完成通知）；`error_recovery` 与 `tool_examples` 同步更新（不再教未公开参数）。
- 工具自述与参数 schema 改为与真实实现一致（模型输入）：`TaskStop` 参数名改为 `taskId`；`REPL` 改为 `sessionId`，语言枚举收敛为 `python`（不再宣称 node／ruby）；`TodoWrite` 提示改用模型 schema 已有的 `PENDING`／`IN_PROGRESS`／`COMPLETE`／`CANCELLED` 状态、删除 `activeForm` 必填要求；运行时保留大小写不敏感的状态归一（包括 `completed` → `COMPLETE`），缺失或为 null 的 `status` 沿用原有行为、原样保留，其他未知状态仍拒绝且不更新列表；不修改模型 schema 或接入新的管线 schema 校验，不新增 `id`／`status` 必填要求。`merge` 只合并带 `id` 的条目、缺 `id` 条目原样保留且互不覆盖；`ConfigTool` 示例改用真实的 `action`／`key`／`value`，列表与示例均仅描述该工具运行时存储的值，不持久化、不宣称改变模型、主题或其他运行时设置；`BashTool` 的 schema 与提示改为如实描述超时（未指定时按命令类别推荐，兜底 120000ms，上限服务端可配），并删除后台命令的完成通知承诺（改为立即返回 PID、输出不被捕获、无通知）；`GrepTool` 把实现已读取但未声明的参数补进 schema 与说明（`head_limit`／`offset`／`multiline`／`type`／`-A`／`-B`／`-C`，`head_limit` 默认 250），并明确 `head_limit <= 0` 时不分页、忽略 `offset`、仍受既有总输出限制；多行搜索与按类型过滤需要 ripgrep。
- Coordinator 的 worker 能力描述改为如实：后台 worker 一次运行到完成，当前版本**不支持停止、续传或中途纠正**——`TaskStop` 只管理 TaskCoordinator 登记的任务、无法停止 worker，`SendMessage` 对 worker 没有可达的送达目标；需求变化后仅将旧结论标为过期，不能据此认定执行已停止或修改已撤销；旧执行未确认结束前不派发重叠写任务，确认结束后根据实际差异有界修正，独立只读工作仍可并行。`timeout`／`interrupted`／future 取消不等于停写，另起 worktree 也不能绕过该边界。通知中的 XML 仅为完整格式示例，实际送达可能有外层包装、普通错误文本或截断，不假定字段完整，Agent 示例补齐 `run_in_background: true` 并删除 `task_id`／`agent-x7q` 误导示例；正文区分同步工具结果与后台通知，后台回送受当前 Run、`BACKGROUND_AGENT_WAIT`、取消、轮次及等待结果约束，当前实现按本 Run 的跟踪状态收齐结果后批量交付，不保证逐个即时通知，状态终结不替代实际停写确认；协调相关工具及环境工具参考不再冒充实际请求或具体 worker 的完整工具清单，工具可见不代表获执行授权；协调者工具声明同步收窄为 `Agent` 与 `SyntheticOutput`（`WorkflowPhase` 各阶段白名单同步；属声明层，不等于模型侧隐藏，工具池接线另行推进）；任务通知格式化对 `agentId`／`status` 做 XML 转义。
- Git 提交面板不再自动为消息添加双引号；`/commit <消息>` 后端将消息原样作为独立的 Git 参数传递，不剥除用户输入的字面引号、不额外裁剪或反转义。
- **Breaking:** `/diff` 的参数解析由「包含 `staged` 即命中」改为精确取值：只接受 `unstaged`（默认，未暂存）、`staged` 与 `--staged`（已暂存），其余值（包括此前被容忍的 `--cached`）返回用法错误，不再静默按未暂存处理。迁移方式为把 `--cached` 改写为 `staged`／`--staged`；README 中英文档同步更新 `/diff` 用法与 `/commit` 说明。
- 内置 `review` 技能将用户指定的比较对象、路径与排除项送达提示正文；仅作用于最终解析为内置 `review` 的定义，保留用户／项目同名覆盖。模型工具入口对完整范围执行既有 2000 字符单参数限制；命令入口保留原有校验行为。
- 移除 Skill 加载正文的会话累计 5000／25000 token 硬拦截及其专用计数状态，允许正常重复加载技能；模型上下文容量、运行轮次、输出上限、参数校验、授权与超时规则不变。重复加载仍会增加实际输入 token 消耗。
- Skill 关闭后立即阻止新调用，下一条用户消息更新工具配置；运行时列表和解析仅提供启用技能，隐藏命令不再进入命令建议。全部技能关闭时移除 `Skill` 工具，历史与已开始的执行保留。设置文件异常时仅停用技能并禁止覆盖原文件，后端继续运行。
- Web 设置面板精简为外观设置，顶栏主题入口改为打开选择面板；移除“跟随系统”、语言和努力程度设置入口。旧“跟随系统”偏好按升级时的系统外观迁移为浅色或深色。
- Web 液态玻璃改用统一分层材质：连续导航、悬浮输入区、Chrome 背景边缘折射、局部高光与分段选择动效；正文使用清晰底色，支持浏览器与无障碍降级。
- 对话按“用户指令 / 任务过程 / 回复”展示，显示方式三档（精简 / 标准 / 完整过程）：精简档三层默认折叠，标准与完整过程档保留完整问题与回复，运行中同样生效；含工具调用的流式段归入过程区。移动输入采用常驻卡片，本轮移除旧的全部展开/折叠、复制本轮与轮次大纲入口。
- ZenMux 默认目录保留 `anthropic/claude-fable-5.1`，新增 GPT-6 Astra、Gemini 3.8 Flash 与 Grok 4.6，并清理已替换或下线的旧型号。
- Web 新会话必须先选择 Project；Session、Query 和文件搜索统一由 `projectId` / `sessionId` 解析服务端工作目录。
- 智谱主模型全量升级为 GLM-5.3，覆盖前后端默认配置与中英文文档。
- 模型列表、默认模型、会话创建和恢复统一以当前已注册 Provider 为权威；无效或已下线模型不再显示为 `Unknown Model`，历史会话仅在当前连接中回退到可用默认模型。
- Docker 运行时升级到 Python 3.12 并内置可选的受管 Python 服务；基础 Compose 保持默认不启动 Python，部署方须通过显式 override 启用。
- **Breaking:** Query 不再接受客户端提供的 `workingDirectory`。CLI 本地连接会登记当前目录，远程连接应使用 `--project-id` 或服务端默认工作区。
- 无 allowed roots 时，本机目录选择默认关闭；直连本机桌面服务须显式设置 `ZHIKUN_LOCAL_PICKER_ENABLED=true`，远程或反向代理部署须配置 `ZHIKUN_WORKSPACE_ALLOWED_ROOTS`。
- 顶栏的模型选择器、模型重试与成本指示改为桌面端常驻（≥768px）。
- 上下文压缩引擎重构为 ContextCompactor / CompactConfiguration / CompactionContext / CompactionHistory：摘要失败时按完整工具事务本地选择，用户原文永不省略且不再尾部截断，工具终止未确认不宣告成功。
- `BACKGROUND_AGENT_WAIT` 默认开启：主 Run 等待本轮后台代理完成再汇总结果，默认预算 31 分钟，可用 `FEATURE_BACKGROUND_AGENT_WAIT=false` 关闭或 `AGENT_TIMEOUT_MAX_WAIT_MINUTES` 调整。
- Web 侧边栏移除图标轨改为直接面板加收起展开条，会话搜索下推服务端（防抖 + 分页互斥），停止按钮三端统一，隐藏工作台切换；移除侧边栏“新窗口打开”导航入口（独立窗口渲染能力保留）。
- 多 Provider 重复模型 ID 由“首个匹配”改为明确拒绝歧义路由；OpenRouter 列表中的官方原始 ID 启动时自动规范化去重。
- 同一会话执行期间，REST 查询与会话管理操作返回 409 `SESSION_CONCURRENT_MODIFICATION`：WS / REST / SSE / Undo 及历史删除统一由每会话 `SessionExecutionGate` 非阻塞互斥，忙时明确拒绝而非排队。
- 消息持久化改为落盘优先：assistant 消息先同步落库成功才进入工作集并允许本轮工具执行；落盘失败先取消已启动工具，再以 INCOMPLETE（`PERSISTENCE_FAILED`）终结 Run，取代原“失败后跳过后写、结尾按长度补偿补写”策略。
- ZenMux 多 API Key 选择策略改为 `PRIORITY_FAILOVER`（配置顺序中首个健康 Key 优先，冷却时故障转移，恢复后重新优先）；其余 Provider 保持既有轮询。
- 移除内部压缩标记（`[final]` / `[skeleton]` / `[collapsed]` 等）的正文剥离规则：用户可见正文原样保留，避免误伤 INI 段名等合法方括号内容；流式与历史展示同步不再过滤。整段最终答复仅为系统折叠占位符时仍按无可见正文处理，触发一次补请求恢复。
- 压缩摘要默认模型改为 DeepSeek 官方直连 `deepseek-flash`（Provider `deepseek`）：摘要传输通道同时支持百炼 Token Plan 与 DeepSeek 官方端点；可用 `LLM_COMPACT_MODEL` / `LLM_COMPACT_PROVIDER` 覆盖回百炼 `deepseek-v4.1-flash`。
- 附件下载改为按完整规范 UUID 精确定位（拒绝短前缀、多候选返回冲突，排除元数据、临时文件与符号链接）；新上传仅保留 `[A-Za-z0-9]{1,16}` 的扩展名；配置的上传根目录可以是符号链接，目录内的符号链接文件仍不可下载。旧附件及其合法文件后缀不受影响，无有效元数据时回退安全 UUID 文件名。
- Evidence Viewer、Journey 面板、移动提示与 Workbench 的验证结果统一为有限范围口径（所列步骤、文件完整性、执行状态、访问检查或范围未知），区分 failed / unavailable / inconclusive / unknown，部分核验统一显示为「核验未完成」；空证据或未知类型只保留记录自身结论，不推断检查范围。文件交付正向结果改用「上次完整性检查通过」，不再暗示当前文件版本仍被验证，未知值不再导致页面崩溃。
- 新增非破坏性数据库迁移：`run_envelopes` 增加 `usage_status` 与 `usage_snapshot_seq` 列；旧数据保留 `unknown`，不回填历史零值为已知。
- 证据 bundle/items 入库改为同一事务普通 INSERT：重复 ID 明确失败、不覆盖既有记录、不自动换 ID 重试（同时消除旧 `INSERT OR REPLACE` 触发 `ON DELETE SET NULL` 静默解除 run 验收关联的路径）；默认 bundle ID 改为 `ev-` + 完整 UUID（显式指定 ID 与历史短 ID 保持原样、可读）；元数据序列化失败明确报错；入库复用 5 秒有界写锁，锁忙或等待中断不产生新记录。读取容错不变；**对象归属授权与 blob 原子发布/内容校验未包含在本批**，证据端点沿用既有开放访问模型（另行推进）。
- **Breaking:** `/api/query`、`/api/query/stream`、`/api/query/conversation` 对非空 `maxBudgetUsd`（含 0 与负数）明确返回 400（`QUERY_BUDGET_USD_UNSUPPORTED`）：该字段此前被静默忽略，现改为显式拒绝，显式使用 `--max-budget` 的 CLI 调用将收到失败响应——迁移方式为省略该字段（缺失或传 null 时行为不变）。CLI 帮助文案同步更新，参数与转发逻辑保留；错误响应为显式 JSON——纯 `Accept: text/event-stream` 请求同样收到 400 JSON（同类 not-found 路径一并覆盖）。本批**不包含** token 严格预算与计量主体（另行推进）。

### Fixed
- 手动删除 WORKTREE 时，在既有后台占用检查之外，补查创建与调用 Run、相关会话当前 Run 的前台进程；实际删除前复查，占用、检查失败或观察到 Run 映射变化时保留目录和分支，不终止进程。不以调用 Run 仍活跃本身阻止删除，自动子代理交付的资源确认路径保持不变。
- 修复隔离子代理在明确尚未尝试 Run 注册、尚未执行模型或工具的启动失败后遗留会话租约的问题：使用本次执行的内部启动证据，确认没有冲突 Run 和后台资源后释放自身租约并收尾。失败结果与工作树保留；部分注册、身份未知、执行超时或停止未确认仍不强制回收。
- 修复 WORKTREE 成果遗漏、错仓库合回、失败吞没及过早删除：绑定可信项目、创建时分支与 HEAD，正常成果经完整状态检查后自动三方合回；异常、未交付或停止未确认时保留并返回恢复位置。交付遵守 Git 忽略规则，ignored 文件不强制提交、可随正常清理删除，子模块内部未提交成果保留。工作区与分支分别非强制清理，已交付但清理失败不诱导重跑；停止脚本不再强删 worktree。Git hook 自然结束后留下的后台进程保持运行，不为清理终止；内部 Git 使用独立操作归属、完整机器输出及有界等待（每次管理操作 240 秒、单次查询 30 秒、commit/merge 120 秒），超限或状态未知时保留。手动 remove 限于当前项目的安全受管对象；WORKTREE 使用独立缓存，可能改写父目录时要求重新读取。同步 Agent timeout（包括 NONE）不再无条件声明 terminationConfirmed=true，其他既有超时状态和重试语义不变。
- `/commit` 的预览文件列表和计数统一使用暂存区差异；普通空暂存区明确提示并停止提交，保留待完成 merge 的收尾路径。不自动暂存、不自动重试；机器格式输出使用无损读取，原文本 Git API 的换行与 trim 契约不变。
- Grep 在工具入口精确校验分页与上下文整数，拒绝负偏移、整数回绕和分页计算溢出，以及 content 模式的负上下文参数；`head_limit <= 0` 仍关闭分页并忽略 offset。未新增任意分页额度，也未改变搜索进程或输出读取框架。
- 修复斜杠命令虚假成功回执：删除 `/model` 对应用状态的无效写入与 `Model switched to` 成功日志（该写入不影响会话实际调用模型；有参时现明确说明不切换会话模型并引导使用输入栏模型选择器）；移除 `/fast`「已启用低延迟模型」、`/effort` 虚构「medium（默认）」、`/color` 虚构配色清单以及 `/vim`、`/output-style`、`/theme` 有参的「已设置」文案，统一回复尚未接入、未改变任何设置；`/theme` 无参此前返回前端没有渲染器的 JSX 选择器（用户只看到空白回执），现改为诚实文本并引导外观设置；`/model` 未知模型仍返回错误。
- 修复 `/diff` 的范围误判与失败混同：`/diff unstaged` 不再因参数包含 `staged` 子串而被当作已暂存；当前范围的 Git 差异读取失败（stat 或正文为 null）现在明确报错并区分失败来源，不再与「无差异」混同（两者均空仍返回「无差异」）；stat 展示同步截断到 10000 字符并复用既有截断标记，文件数改为先按完整 stat 计算再截断（stat 为空而正文非空时不再得到 -1）。
- 修复 `/commit` 的失败识别：Git 状态读取失败明确报错，不再与「没有可提交的变更」混同；无参预览在暂存差异读取失败时明确报出失败来源（stat／正文／两者）并放弃预览，不再返回貌似完整的面板；提交结果不可读时如实说明「提交可能未生效、也可能已成功」，要求先核对仓库状态（`git log`／`git status`）再决定是否重试（若被 pre-commit 钩子拒绝请先修复钩子），不做自动重试。
- 补全 OpenAI 兼容链路的截断流诊断（此前只有错误上报、没有诊断上下文）：尾行缺少结尾换行且尚未收到有效 `finish_reason` 时同样记录不完整流诊断；三处缺少 `finish_reason` 的诊断均检查取消标志，避免已取消调用因缓冲尾部进入 DONE／EOF 分支而产生该 WARN；已收到有效 `finish_reason` 的截断尾行也不再误报缺少结束原因。只修日志，原有异常、错误回调与重试语义不变，Provider 终态归类及无换行尾行的兼容限制见 Known Issues；截断帧改为显式标记（`:truncated`），不再按「长度恰好等于上限」推断，恰好等于上限的完整帧仍正常解析，无先行问题时 `firstIssue` 输出 `none`；诊断只保留两帧的有界结构摘要（不含帧内容），诊断自身失败不会替换原始错误。相关回归测试不再向共享 Log4j 配置遗留 per-class LoggerConfig（此前会导致该类日志在测试进程内被静默丢弃）。
- Edit 更新已有文件时展示本次实际写入的 diff，并在会话恢复后保留；增删统计使用实际差异。预览上限为 500 行／65,536 个 UTF-16 单元，超出明确标注部分展示；未提供预览时（如创建文件、旧记录或生成失败）保留原结果并给出中性提示。工具结果的全部 UI `structuredResult`（包括 Edit 预览及 external-resource 下载卡片）不进入压缩摘要输入，工具正文保留；新生成的会话合并文字投影仅排除 Edit 展示快照，其他 schema 保持原行为，完整快照仍保留在原始归档和会话记录中，已有封存包保持不变。文件写入、权限和模型结果正文不变。
- 修复 Git 大输出填满管道导致 diff／审查读取失败的问题，改为并发读取完整输出。进程退出与输出读完共用 5 秒期限，失败时对当前进程与已知后代增加至多 2 秒的尽力清理等待；成功路径保留后台 hook。Git 已执行成功但输出迟迟不结束时仍可能返回失败，不自动重试；完整输出仍需占用内存。
- 修正内置 Explore／Plan／Verification 的 `Edit`、`Write` 排除名单及 Guide 的 `Read` 可用名单，并统一代理提示、内置示例和工具说明中的文件与搜索工具名称；保留其他授权规则与通用代理的读写能力。
- 修复多批次后台代理等待共享运行级截止时间的问题：首个等待周期结束后，超过 `AGENT_TIMEOUT_MAX_WAIT_MINUTES` 的新批次会以 0 预算立即触发 `BACKGROUND_AGENT_WAIT_TIMEOUT` 并终结主 Run，已启动的后台代理结果无法交付。现每个等待周期独立获得完整预算（同一次等待内的唤醒不刷新预算），并新增两波次等待回归测试。
- 修复 Kimi 视觉模型拒绝粘贴图片公网 URL 的问题：发送前临时转为 base64，历史保留引用；图片上下文按尺寸估算并独立限制传输大小。
- 图片额度优先保留当前附件和新读取的工具图片；附件处理失败或历史图片省略的提示实时显示并随会话保存。
- 修复旧主题配置导致的首屏白屏，统一校验本地及服务端主题；主题 E2E 增加真实切换、刷新持久化与旧配置迁移断言。
- 完整过程档下新指令不再自动折叠前轮过程；自动压缩与命令执行提示在三档显示方式下均保持可见。
- 接通首页模板填词和聚焦，修复移动端可视化自动跳转及隐藏“回到最新”按钮截获点击。
- 修复历史 TodoWrite 任务状态/结果解析，统一切换调用的分节归属；实时工具按 assistant 段关联，任务边界以消息 UUID 合并实时与历史数据。
- 系统消息用独立 JSON `kind` 保存后端分类，避免快照往返丢失压缩摘要标记；图片引用复制保留消息元数据。
- 规范化远端 MCP Schema 中的非标准类型别名（如 `bool` → `boolean`），避免 Moonshot/Kimi 因任一工具 Schema 非法而拒绝包含智谱搜索在内的整批工具。
- Python 健康恢复改为异步且防重入，避免异常重启阻塞 WebSocket 心跳、授权重投及其他定时任务。
- 修复 Responses API 多轮对话中 assistant 历史文本被编码为 input_text 导致的跨模型 400 错误，现按规范编码为 output_text。
- 修复子代理 checkpoint 的 token 用量双计：`onAssistantMessage`/`onUsage` 双回调不再各自累计，checkpoint 直接读取运行累计的已观测用量。QueryEngine 在消息持久化与业务回调之前记录已收到的原始 usage（同一响应只收敛一次，异常及工具参数解析失败仍保留已收到用量；缺 usage 与显式零值严格区分，已确认未发起调用不标成缺失、不估算）；已观测用量在终止前按递增序号保存快照，补充写入有限等待写锁以保留后续本地清理路径，外部取消写入终态后仍可补齐最终观测值，且不改动终态、退出原因与终止时间或重复发送完成事件。
- 修复未知退出原因导致 Run 读取失败的问题：未知值安全读取为 `UNKNOWN`（数据库原文保留、不推导为成功）；预算与轮次耗尽分别记录为 `token_budget_exhausted`/`max_turns`，仍为非正常完成，取消、超时及未确认停止的权威结果优先。
- 修复中文、emoji 等文件名下载时丢失或乱码：新增无状态 Content-Disposition 编码（同时输出 ASCII `filename` 与 UTF-8 `filename*`，移除控制字符、≤200 UTF-8 字节且不切断字符，超长名保留完整的短安全扩展名），接入文件预览、附件下载、OSS 下载、会话导出与 Evidence 下载。
- 修复 Workbench 将证据 claim 与业务要求文字相同直接判为通过的问题：现在仅建立关联、最多表示部分覆盖，实际失败保留在对应检查项；运行时技术检查只聚合具有有效步骤数据的 Journey 证据，空证据、未知类型与模型自报不再产生通过结论，hash 通过仅表示对应记录的文件完整性检查。
- 图片注入确认改为绑定「最终实际保留的图片身份」：仅当本轮请求已发出且正常结束（未失败、未中断）且图片仍以图片形式存在于最终序列化 payload 时才确认对应源图片——BMP→PNG 转码与缩略降级按块跟踪身份；被省略、转文字、移除或请求失败的图片不确认（后续轮次仍可重试）；与必选附件字节相撞的图片不再被从待确认集合剔除（可确认、但受保护不被降级；相撞判定按注入载荷哈希求交，BMP 转码等跨格式形态同样受保护）。同一载荷对应多个源身份时按集合保留、全部确认（缩略降级合并身份），不会因哈希碰撞丢失确认。身份映射仅在内存中流转，不进入请求、持久化消息或用户历史；「请求正常结束且图片保留」不等于模型已理解图片。本批**不包含** adapter 级「接受后断流」语义与 `FinalProviderPayloadGuard` 扩展（另行推进）。
- 修复 `EvidenceStore.readBlob` 的目录逃逸原语（预存缺陷）：入参增加 64 位十六进制白名单与规范化路径包含校验；重写此前给出错误安全结论的边缘测试（原断言只验证「目标文件不存在」，未验证路径受限）。
- 修复证据落库失败的处理：`VerifyJourney` 在证据保存失败时返回独立结果码 `EVIDENCE_PERSIST_FAILED` 与脱敏文案，**正文保留真实 verdict**（verified / failed / unavailable）与「结果未持久化」事实及禁止自动重跑提示；不再把持久化失败记为验证 failed，也不再向模型回传底层异常文本。

### Security
- 内置文件搜索、写入、Glob、Grep、LSP 与 Snip 以单一 Session 根解析相对路径；范围外绝对路径进入常规授权，并在执行前复检路径、符号链接和 Project 状态。
- Python 文件、Git 与分析端点统一执行 canonical workspace allowlist 与请求 project-root 子路径边界校验，递归扫描跳过符号链接；文件树深度限制为 0–20，越界或不存在的分析路径统一拒绝。

### Known Issues
- OpenAI 兼容流在有效 `finish_reason` 后收到缺少结尾换行的尾行，仍按既有 I/O 错误处理；本批只修诊断，不将任意 EOF 改判成功。取消时若响应尾部已缓冲，Provider 的终态回调仍可能按该尾部归类；主调用链另行检查取消信号，本批不声称统一了 Provider 取消终态。
- Grep 的最终字符截断不等于读取阶段的内存上限，极大合法分页／上下文的中间聚合开销仍存在；本批未扩展为有界流式读取改造。
- `/keybindings` 当前没有前端 UI 呈现：命令回执不会渲染出快捷键编辑器（描述中的「查看／编辑键盘绑定配置」与实际不符），用户只看到空白回执；Web 端可使用独立的快捷键帮助对话框。该命令的 UI 接线不在本批（另行推进）。

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
