# 黄金监控公开审计案例：生成与复核

本目录发布 ZhikunCode 与 Codex 黄金监控任务的静态审计报告、结构化证据、脱敏日志摘录、公开脱敏截图和 SHA-256 清单。报告生成不依赖网络、远程字体或第三方 npm 包。

## 公开文件

- `zhikuncode-codex-gold-monitor-audit.html`：静态、自包含的公开审计报告。
- `zhikuncode-codex-gold-monitor-evidence.json`：38 条证据、14 维评分及可视化所需结构化数据。
- `zhikuncode-gold-monitor-log-excerpts.txt`：保留原始行号的脱敏关键日志摘录。
- `assets/gold-audit/`：由冻结报告解码的公开运行截图；ZhikunCode 开发过程图对一处本机绝对路径做了确定性遮蔽。
- `zhikuncode-codex-gold-monitor-SHA256SUMS.txt`：上述公开文件及本说明的 SHA-256。

完整运行日志、两份冻结源 HTML 和冻结产物 ZIP 含本机路径、会话标识或未公开材料，不属于公开发布物。完整日志受仓库 `*.log` 忽略规则保护，不应使用 `git add -f` 强制加入版本控制。

## 公开构建

要求：Node.js 22（本次验证环境；脚本无第三方依赖）。

从仓库根目录执行：

```bash
node scripts/build-gold-audit-report.mjs
```

公开构建使用已提交的证据 JSON、脱敏摘录和截图。完整日志不存在时，生成器不会尝试补造缺失事件；它会验证公开摘录的来源声明、必需行号、敏感信息和证据引用，然后生成 HTML 与 SHA-256 清单。

## 私有源核验

拥有两份登记哈希对应的冻结源 HTML 时，可额外运行：

```bash
node scripts/build-gold-audit-report.mjs \
  --verify-private-sources \
  --zhikun-report /absolute/path/to/zhikuncode.html \
  --comparison-report /absolute/path/to/zhikuncode对比codex.html
```

该模式会核对源 HTML 的 SHA-256、46 张源 SVG 的原始顺序与逐图哈希、60 次工具调用指纹、评分与澄清问题等冻结事实。若本地存在被 Git 忽略的 `docs/case-studies/zhikuncode黄金监控运行日志.log`，生成器还会先核验其字节数、行数和 SHA-256，再从指定原始行段重新生成脱敏摘录；完整日志不会写入 HTML 或哈希清单。

只有在登记哈希对应的源 HTML 确实发生受控更新时，维护者才需要重新捕获 SVG 快照。此步骤依赖仓库前端开发依赖中的 `jsdom` 与 `postcss`，并把两张运行时评分图物化为静态 SVG：

```bash
node scripts/capture-gold-audit-source-visuals.mjs \
  --zhikun-report /absolute/path/to/zhikuncode.html \
  --comparison-report /absolute/path/to/zhikuncode对比codex.html
```

捕获器登记 46 张源图，公开 32 张完整主面板和 12 张完整次级面板；另外 2 张只保留标题、哈希和排除理由，不把未脱敏 SVG 本体写入仓库。常规公开构建直接读取已提交的压缩快照，不需要源 HTML、完整日志或前端依赖。

私有路径仅作为命令行参数使用，不会进入公开文件。

## 校验公开产物

```bash
cd docs/case-studies
shasum -a 256 -c zhikuncode-codex-gold-monitor-SHA256SUMS.txt
```

生成器还会检查：

- 证据编号、引用闭包、评分权重和 68.3 / 68.4 复算结果；
- 60 次工具调用的完整顺序及六个错误位置；
- 46 张源图登记、44 张公开面板、逐图 viewBox/节点/几何指纹、命名空间 SVG ID、标题和证据引用；
- 本机绝对路径、UUID、令牌、Cookie、认证请求头等敏感信息；
- 禁止重新出现的已纠正表述和旧版简化 SVG。

修改报告数据、生成器或公开资产后，应连续执行两次公开构建并比较输出哈希，确认生成结果确定，再提交全部相互匹配的 HTML、JSON、摘录、截图和 SHA-256 清单。

---

# 案例二：12306 候补可视化双工具对比评估（2026-07-27）

本案例发布 ZhikunCode（KimiK3）与 Codex（GPT-5.6 Sol）完成同一"12306 候补成功后台全链路"动态可视化任务的对比评估报告、结构化证据、脱敏日志摘录、运行截图和 SHA-256 清单。报告生成不依赖网络、远程字体或第三方 npm 包。

▶ **在线体验 ZhikunCode 产物（单文件 HTML，浏览器直接运行）**：<https://zhikunqingtao.github.io/zhikuncode/case-studies/assets/12306-comparison/artifacts/zhikuncode/12306-houbu.html>

## 公开文件

- `zhikuncode-codex-12306-audit.html`：静态、自包含的 7 维评估报告。
- `zhikuncode-codex-12306-evidence.json`：21 条证据、7 维评分及可视化所需结构化数据。
- `zhikuncode-12306-log-excerpts.txt`：保留原始行号的脱敏关键日志摘录（65 行，≈ 完整日志 2.3%）；UUID/会话 ID 已掩码，本机绝对路径的用户段已掩码为 `<USER_HOME>`。
- `assets/12306-comparison/`：11 张产物实际运行截图（ZhikunCode 6 张 + Codex 5 张），未做修饰；另含 1 张 Codex 会话界面过程证据截图 `codex-06-duration.png`（EV-021，总耗时 27m52s 与改动规模的计分依据，非产物运行画面）。
- `assets/12306-comparison/artifacts/`：两侧交付产物源文件副本——ZhikunCode：[zhikuncode/12306-houbu.html](assets/12306-comparison/artifacts/zhikuncode/12306-houbu.html)（单文件自包含，可直接打开运行）；Codex：[page.tsx](assets/12306-comparison/artifacts/codex/page.tsx)、[globals.css](assets/12306-comparison/artifacts/codex/globals.css)、[layout.tsx](assets/12306-comparison/artifacts/codex/layout.tsx)、[package.json](assets/12306-comparison/artifacts/codex/package.json)（源文件，浏览器中以文本呈现或下载）。均与评测当晚原件逐字节一致，随仓库公开、内容未做任何修改。
- `zhikuncode-codex-12306-SHA256SUMS.txt`：上述公开文件及本说明的 SHA-256。

完整运行日志 12306补票.log（548KB / 2,808 行）与两份快进版过程录屏含本机路径或未公开材料，仅本地留存，不属于公开发布物；三者的 SHA-256 已锚定在 `zhikuncode-codex-12306-SHA256SUMS.txt` 注释区（`# … local artifact, not in repo`），持有原件者可据此核验。

## 校验公开产物

```bash
cd docs/case-studies
shasum -a 256 -c zhikuncode-codex-12306-SHA256SUMS.txt
```

## 利益声明与复核方式

本评估由 Qoder（AI 编程助手）执行完成、由 ZhikunCode 项目发布，被评一方（ZhikunCode）与发布方同源，利益冲突已知，且未经独立第三方复核，结论请以"多维参考"而非排名解读。两侧产物源文件已随仓库公开于 `assets/12306-comparison/artifacts/`，任何人可用 `shasum -a 256` 比对清单哈希、并按报告附录 G 的 grep/wc 命令独立复核全部统计数字。评分口径、降偏措施与已知限制详见报告页首"利益披露"块及页脚披露段。

---

# 案例三：类《王者荣耀》单机 Web 5v5 MOBA 原型证据实录（2026-08-09）

本案例记录 ZhikunCode（Kimi K3）从一句话需求到可运行单机 Web 5v5 MOBA 原型的过程。开发证据窗口固定为 `2026-08-09 01:30:00 ≤ 本地时间 < 07:01:00`；窗口外的最终运行和阿里云试玩单独分类，不能混入开发耗时或行为统计。

最终产物已经部署到阿里云 HTTPS 环境，可直接验证：

- **[在线试玩](https://king.zhikun.xin/)**：从5名英雄选将页开始，锁定英雄后手动操作。
- **[自动演示](https://king.zhikun.xin/?demo=1)**：同一份构建通过 `demo=1` 运行参数跳过选将，自动进入5v5对局。

## 公开文件

- `zhikuncode开发王者荣耀.html`：静态案例报告；产品定位为“类《王者荣耀》的单机 Web 5v5 MOBA 原型”，不是腾讯官方游戏或授权复刻。
- `assets/king/code/`：与最终项目代码逐字节一致的 21 个代码文件，并附 vendored Three.js MIT 许可证；原项目不做任何修改。
- `assets/king/logs/app-session-20260809-0130-0701.public.log`：两份源日志按完整时间戳块过滤、依次合并并最小脱敏后的公开版本；共 38,626 个时间块、38,641 行，严格位于半开窗口 `[01:30, 07:01)`，保留 Session/Run/LLM/Tool/下游请求 ID。
- `assets/king/logs/observability-events-20260809-0130-0701.jsonl` 与 `security-audit-20260809-0130-0701.log`：2,003 条观测事件和 116 行安全日志。
- `assets/king/db/`：4 份冻结导出：1 条会话记录、229 条窗口内消息、113 条窗口内活动和 4 条窗口内需求确认。窗口外报告编写交互不进入公开开发证据。
- `assets/king/bill/`：877 条账单数据记录加 1 条表头；873 条运行时 completed 的 input/output token 元组与其中 873 行逐条一致。
- `assets/king/screenshots/`：43 个 PNG 文件、42 份唯一图像内容；重复存档在报告中明示。
- `assets/king/videos/previews/`：5 份 960×540 H.264 派生预览。五份 HEVC 原件不进入普通 Git 历史，由 `release-assets.json` 登记 SHA-256，待人工确认后再发布到 GitHub Release。
- `assets/king/videos/storyboard-frames/`：从 5 份预览按 15%/38%/62%/85% 固定位置提取的 20 张派生帧；来源视频、时间码、倍速、命令和哈希登记在 `video-storyboards.json`，不冒充独立原始截图。
- `assets/king/provenance.json`、`verification.json`、`browser-verification.json`、`visualization-manifest.json`、`redaction-report.json`、`release-assets.json`：来源/窗口/SQL、机器复算结果、与当前HTML SHA绑定的浏览器回归、91图数据绑定、脱敏统计、原始视频与派生预览映射。
- `assets/king/SHA256SUMS.txt`：仓库内证据清单；大视频原件由 `release-assets.json` 单独登记。

## 本地准备与校验

需要 Node.js 22、SQLite CLI 和 FFmpeg/ffprobe。数据库、源日志、原始项目及原视频都存在于案例作者的本地工作环境时，从仓库根目录运行：

```bash
node scripts/prepare-king-case-evidence.mjs
./scripts/build-king-video-previews.sh
node scripts/freeze-king-public-log.mjs
node scripts/build-king-video-storyboards.mjs
node scripts/build-king-report-v15.mjs
node scripts/verify-king-case.mjs --write
node scripts/verify-king-case.mjs
```

常规审查者在公开文件齐全时只需运行最后一条；它会复算代码行数、账单、运行事件、数据库公开导出计数、工具调用分布、日志窗口与脱敏结果、91 张本案例 SVG、39 张表/25 段原文的内容哈希、43 张截图、20 张视频派生帧、视频编码/体积、HTML 相对引用及高置信度密钥特征，并验证 `SHA256SUMS.txt`。

## 证据和发布边界

- SHA-256 证明文件相对于清单的完整性，不证明捕获时间、作者身份或内容真实性；文件名 epoch 和文件系统时间不是可信时间戳。
- 观测日志、应用日志、SQLite 与截图均来自同一套本地系统，不能冒充相互独立的第三方证据。账单 CSV 是另一导出面，但本包中没有提供方数字签名。
- 报告中的缓存统计只陈述 token 数量和比例；CSV 无单价与折扣规则，不估算金额或“节省倍数”。
- 原始视频发布、Git 标签、Release、提交和推送必须在人工检查后执行；本地准备脚本不会调用 GitHub API。
