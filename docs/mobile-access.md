# 本机启动与手机远程访问

本方案让手机浏览器通过 HTTPS 访问本机运行的 ZhikunCode，复用已有会话、聊天流式输出和工具审批。面向本人使用的独立实例；安装示例以 macOS 为准，Linux 的依赖安装和电源管理需自行适配，Windows 未验收。

工具位于 [`scripts/mobile-access/`](../scripts/mobile-access/)，只包含可公开的脚本、代理配置与本地验证程序。凭据、临时网址和日志在该目录的 `.runtime/` 中生成，已加入 Git 忽略规则。

## 访问链路与权限

```text
手机浏览器（4G / 5G / Wi-Fi）
  │ HTTPS
  ▼
Cloudflare Quick Tunnel（每次运行的临时地址）
  │ 加密隧道；本机主动建立出站连接
  ▼
Caddy 127.0.0.1:15173
  │ Basic Auth；改写前检查 Origin
  ├── /                         → frontend/dist
  ├── /api/* 与 /ws/*           → Java 127.0.0.1:8080
  └── Python 所属 API 前缀      → Python 127.0.0.1:8000
```

前端使用同源 `/api` 和 `/ws`，无需增加手机专用协议。主聊天使用 SockJS/STOMP；Caddy 负责 WebSocket 升级代理。手机入口托管生产构建，避免 Vite 开发模式的大量模块请求。一次旧版环境测试中，生产构建消除了开发模式出现的 429；首屏请求总数本身不等于并发数，也不是所有版本都固定为同一个数量。

**通过入口认证意味着获得这个单用户实例的操作能力。** 手机可以查看会话、选择项目，并驱动 Agent 操作运行账户及现有工具策略允许范围内的文件和命令。新会话沿用应用默认的 `AUTO_APPROVE`，脚本不改变权限模式。需要审批时在会话权限菜单切换到 `DEFAULT`；普通工作区编辑等操作仍可能自动获准。

入口在认证后删除转发头，将上游请求按本机调用处理，使手机可创建项目、浏览服务端目录。**已知限制：附件能力检测也可能把手机当成本机，点击“引用本地文件”或原生目录选择按钮时，可能在 Mac 上弹出选择窗口，手机无法独立完成选择。** 当前保留这一行为，使用电脑完成相关文件选择或使用已经关联的项目文件。

手机上的文件与服务器路径是两回事。显式配置路径策略导致原生选择器不可用时，附件入口可能改走应用已有的 OSS 上传机制；该机制须另行配置，并会公开发布相应对象，使用前阅读 [README 的文件引用说明](../README.md#可选oss-产物发布截图粘贴与远程文件引用)。

本机启动脚本在未显式设置路径策略时会启用本地 picker，Python 文件接口此时可能访问工作区之外的绝对路径。`ZHIKUN_WORKSPACE_ALLOWED_ROOTS` 可收紧使用该校验的接口，但不能代替 Bash 工具的操作系统隔离。入口不会覆盖你已设置的路径策略。

## 首次运行

先按 [README 本地安装](../README.md#local-setup) 安装 Java、Node、Python 依赖并配置自己的模型 API Key。以下命令均在仓库根执行：

```bash
# macOS：先安装 Homebrew，再安装入口依赖
brew install caddy cloudflared

# 终端一：服务已正常运行时跳过
bash start.sh
```

`start.sh` 会清理固定端口和旧进程，不能作为无副作用的状态查询。共享开发机优先使用 README 中的手动三服务启动步骤。先在电脑打开 `http://localhost:5173`，确认可以正常使用。

```bash
# 终端二：首次明确构建前端，然后开放手机入口
bash scripts/mobile-access/start-mobile.sh --build
```

脚本先检查端口与本地服务，生成独立强密码，再启动仅监听 loopback 的 Caddy。通过匿名拒绝、错误密码拒绝、Origin 与 API 初始化检查后，才启动临时隧道。用手机打开输出的 HTTPS 网址，在浏览器登录框中输入显示的用户名和密码。

网址不要拼接用户名或密码，也不要把入口终端截图外发。Cloudflare 是该链路的受信任代理方；此方案未提供只有手机与本机能够解密的应用层端到端加密。模型 API 和其他外部工具仍按各自配置处理请求中的数据。

## 日常操作

```bash
# 复用已有生产包；不存在生产包时自动构建
bash scripts/mobile-access/start-mobile.sh

# 前端代码变化后重新构建（先关闭已运行的入口）
bash scripts/mobile-access/start-mobile.sh --build

# 只读检查：缺少生产包或服务时报告失败，不自动补建
bash scripts/mobile-access/start-mobile.sh --check
```

`--check` 不生成凭据、不修改构建、不启动 Caddy 或隧道，也不检查真实公网连通性。公开工具仅提供生产构建模式；不提供旧版 `--dev` 公网入口。

保持入口终端运行，Mac 联网且不合盖。脚本在 macOS 上使用 `caffeinate` 防空闲睡眠，但不能阻止合盖、主动睡眠、关机或断网。入口终端按 `Ctrl+C` 会停止本次 Caddy、隧道与防休眠进程，本地 Java、Python、Vite 保持运行。进程意外退出后需要重新启动；当前没有自动守护服务。

运行数据都位于 `scripts/mobile-access/.runtime/`：

- `auth.env`：自动生成的用户名、密码与 bcrypt 哈希，文件权限为 600。只由本人保管，不要提交。
- 每次运行的目录：包含 `url.txt`、Caddy／隧道诊断日志和空白隧道配置。诊断日志不等于完整的访问或操作审计。

更换密码：

1. 在旧入口终端按 `Ctrl+C`，确认旧入口已关闭。
2. 从仓库根执行 `rm scripts/mobile-access/.runtime/auth.env`。
3. 重新运行启动脚本，使用新网址与新凭据。

删除文件不会撤销运行中 Caddy 的密码。新版在创建凭据或构建前检查端口占用，避免生成新凭据后发现旧入口仍运行。切换自旧版私有目录时，也应先停止旧入口；新工具不会复制原有密码、日志或 SSH 文件。

## 验收与验证范围

原型在真实公网浏览器中完成过入口登录、页面渲染和 STOMP `CONNECTED` 检查，使用者已确认真机 4G 能打开页面。这些结果不等于每台手机、每条网络和全部交互流程都已验收。

使用者提供的[真机 4G 原始截图](assets/mobile-access/phone-4g.png)保留了状态栏的 4G 标识、浏览器地址栏、已加载的历史会话与消息输入界面。原图未经编辑；它展示页面访问结果，不作为实时流式回复、审批点击或断线恢复已验收的证明。

先运行不连接公网的本地验证：

```bash
python3 scripts/mobile-access/verify.py
```

验证程序使用临时凭据、临时端口和模拟后端，不读取真实入口密码、不访问现有会话、不启动 Cloudflare 隧道。检查身份认证、Origin 的协议／主机／端口、Java/Python 路由及认证后按本机处理的转发头策略。它不替代真实后端与手机验收。

手机验收建议逐项记录：

1. 关闭 Wi-Fi，使用蜂窝网络打开当次网址，确认正确密码可登录，错误密码被拒绝。
2. 打开已有会话，确认历史消息恢复；手机与电脑当前选中的会话不会自动同步。
3. 发送简单消息，确认回复逐步显示，能够中断任务。
4. 在 `DEFAULT` 模式下，用测试项目执行一个确定需要授权且可回滚的操作，分别验证 Allow／Deny。普通写文件可能自动获准，不能用来保证审批弹窗一定出现。
5. 在任务运行中切换 Wi-Fi／蜂窝网络，以及短暂切后台再返回，确认状态与消息恢复。
6. 关闭入口后确认旧网址不可继续访问，本地页面仍能使用。

锁屏或长期后台后的恢复仍需真机验证。当前没有 Web Push／APNs／FCM 唤醒通知；首次等待可用连接最多 600 秒，首次投递后的接收确认总窗口为 30 秒，确认后的决策窗口为 300 秒。超过窗口的审批可能过期，需要回到会话检查任务状态。

## 边界与公司部署

- [Quick Tunnel](https://developers.cloudflare.com/tunnel/get-started/quick-tunnels/) 面向测试和开发，没有可用性保证，限制为 200 个同时在途请求，不支持 SSE；主聊天走 WebSocket，`/api/query/stream` 不适用于这条入口。
- Caddy 必须先完成身份和来源检查，再改写上游 Host／Origin。后端信任 loopback，后端 token 不能替代入口认证。非浏览器请求可以没有 Origin，但仍须通过 Basic Auth。
- 不要另外将 Java 8080、Python 8000 或 Vite 5173 暴露到公网。Java 可能监听所有网卡，局域网访问需按自己的网络边界控制。Docker 的 `ALLOW_PRIVATE_NETWORK=true` 也不是“只放行私网”。
- 手机没有独立交互式 shell 界面，但可以通过 Agent 执行命令；没有 shell 输入框不意味着只读。部分桌面 Diff 等页面仍需移动端适配。
- 这是单用户实例。Basic Auth 不提供企业 SSO、MFA、按设备撤销或多人工作区隔离；网址保密只是辅助措施，不能作为身份认证。
- 公司设备应先采用公司允许的远程接入与数据处理方式：手机 → 企业 VPN／零信任及身份认证 → 受控入口 → 隔离开发环境。可替换 Quick Tunnel，复用生产前端与 HTTP/WebSocket 服务；网关要支持长连接，后端端口不得绕过入口。`127.0.0.1` 入口需要同环境连接器或受控私网代理才能被 VPN 手机访问。
- 一人多设备可共用一个隔离实例；多人使用宜先按人分实例。增加 SSO 并不会自动让当前应用获得租户隔离。模型服务、手机文件缓存和 OSS 发布也应符合公司要求。

长期使用优先考虑固定入口、进程恢复、真机断线验收，再按需求增加 PWA 或推送。

## 排错

| 现象 | 处理 |
|---|---|
| 15173 已占用 | 使用正在运行的入口，或先在其终端按 Ctrl+C；不要直接删除凭据重跑 |
| `--check` 报缺少生产包 | 执行 `npm --prefix frontend run build` 后再检查，或直接用 `--build` 启动 |
| 手机打不开 | 确认是当次网址、入口进程还在、Mac 未睡眠，并检查隧道诊断日志 |
| 一直 401 | 使用新路径 `.runtime/auth.env` 中的凭据，按上面的停止／轮换流程处理 |
| 403 | 检查是否从其他页面嵌入或发起跨站请求；反代时需保持正确的外部 Host 和 Origin |
| 页面正常但聊天不通 | `/ws/info` 成功不代表 STOMP 和业务交互完成，检查 `/ws` 代理与实际发送／回复 |
| 无法创建项目或浏览目录 | 确认入口使用新版配置，并检查显式的 allowed roots／picker 策略 |
| 手机点附件后没有选择窗口 | 当前可能在 Mac 上打开原生选择器，需回电脑完成；这是已知限制，未做手机专项适配 |
| 审批未弹出或过期 | 检查权限模式、操作是否真的需要审批、页面是否保持前台及上述时限 |
| 前端改动未显示 | 先关闭旧入口，再使用 `--build` 启动并刷新手机页面 |

## 维护索引

- [启动与自检](../scripts/mobile-access/start-mobile.sh)、[代理配置](../scripts/mobile-access/mobile-access.Caddyfile)、[隔离验证](../scripts/mobile-access/verify.py)。
- [后端网络认证](../backend/src/main/java/com/aicodeassistant/config/RemoteAccessSecurityFilter.java)、[SockJS 白名单](../backend/src/main/java/com/aicodeassistant/config/WebSocketConfig.java)。
- [项目来源判断](../backend/src/main/java/com/aicodeassistant/controller/ProjectController.java)、[附件能力与原生选择器](../backend/src/main/java/com/aicodeassistant/controller/FileController.java)。
- [Python 路径范围](../python-service/src/workspace_paths.py)、[审批时间窗口](../backend/src/main/java/com/aicodeassistant/interaction/DurableInteractionService.java)。
