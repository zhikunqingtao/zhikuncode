# 秒悟发布

`/publish-meoo <精确路径>` 将静态产物或全栈源码发布到**新的秒悟站点**。每次新发布占用账号额度，旧站点保留。本功能默认关闭，仅显式请求时使用，不随生成或验证自动上线；完全访问（AUTO_APPROVE）模式下系统自动完成校验并直接发布，其他模式每次发布仍需要独立的高风险授权。

## 部署账号

后端使用官方 CLI **0.5.4** 和部署级 API Key。主机预先安装 `npm install -g @aliyun-meoo/cli@0.5.4`，通过官方 CLI 登录配置账号。不要将密钥放进聊天、代码、工具参数或项目目录。复用官方 `~/.meoo/credentials.json`（api_key、apiBaseUrl=https://meoo.com、有 userId、无 projectUrlId），文件权限只允许所有者读取，例如 `chmod 600 ~/.meoo/credentials.json`。

```dotenv
ZHIKUN_MEOO_ENABLED=true
ZHIKUN_MEOO_CREDENTIALS_FILE=/absolute/private/path/credentials.json
ZHIKUN_MEOO_EXECUTABLE=/absolute/path/to/meoo
ZHIKUN_MEOO_TIMEOUT_SECONDS=1200
```

`ZHIKUN_MEOO_CREDENTIALS_FILE` 省略时兼容当前服务用户的 `~/.meoo/credentials.json`。账号是整个部署实例共享的，并非每个会话独立。后端只将凭证放进 CLI 子进程的受控环境；授权卡只显示账号 ID。

Docker 镜像包含 Node.js 22.14.0、CLI 0.5.4 和 zip。通过只读 secret 挂载（宿主文件保持 0600 且容器运行用户可读），例如 Compose override：

```yaml
services:
  zhikuncode:
    environment:
      ZHIKUN_MEOO_ENABLED: "true"
      ZHIKUN_MEOO_CREDENTIALS_FILE: /run/secrets/meoo-credentials.json
      PYTHON_SERVICE_AUTO_START: "true"
      PYTHON_SERVICE_PATH: /app/python-service
      PYTHON_SERVICE_EXECUTABLE: /app/python-service/.venv/bin/python
      WORKSPACE_ROOT: /app/workspace
    volumes:
      - /absolute/private/path/credentials.json:/run/secrets/meoo-credentials.json:ro
```

服务名以当前 Compose 为准。容器重建或应用重启后生效；不要把 credentials.json 复制进镜像。不会在每次发布时安装或升级 CLI。

发布前验证依赖 Python 服务。镜像在构建时安装与 Python Playwright 匹配的 Chromium headless shell 和系统依赖，浏览器放在运行用户可读的 `/opt/playwright-browsers`，使用默认的无头模式。上面的 override 启用同容器 Python 服务，静态验证服务和浏览器通过容器内回环地址通信，不需要映射额外公网端口。若使用外置 Python 服务，必须另行确保它能够访问验证服务；其 localhost 不代表 Java 容器。

部署后在该容器内运行 `curl -fsS http://127.0.0.1:8000/api/health/capabilities`，确认 `BROWSER_AUTOMATION.available=true`。不可用时检查返回的 reason 和 Python 启动日志；补齐依赖后重启服务，能力与路由在启动时初始化。旧镜像需要重新构建，不能仅打开秒悟开关。

## 使用与验证

1. 静态模式指定独立 HTML、含 index.html 的完整站点目录，或已构建的 dist 目录。单文件有本地依赖时须改为完整目录。发布工具不执行本地构建。
2. 全栈模式指定源码目录；准备 scripts/setup.sh 和 scripts/start.sh，监听 `0.0.0.0:${PORT:-9000}`。可参考仓库内 `backend/src/test/resources/meoo/minimal-node`。平台构建脚本在远端执行。
3. InspectMeooDeployment 检查文件与配置，不写云端。VerifyJourney 验证主要交互或 HTTP 服务，传入 `publication_path` 和 `publication_runtime`，通过证据会绑定具体文件摘要。
4. PublishMeoo 携带同一路径、模式及 verification_id；权限卡展示应用、账号、文件数、大小、摘要和额度影响。点击仅本次允许才创建云项目；拒绝后不写云端。完全访问（AUTO_APPROVE）模式下不弹权限卡，验证绑定、快照一致性等安全检查仍强制执行。验证或授权后文件变化会被拒绝。
5. 网站卡显示版本、访问状态，可打开网站、复制链接、前往项目设置。静态页面必须匿名请求成功且入口内容一致（规范化 HTML 并剔除平台固定 favicon、水印和安全脚本；正文、样式、业务脚本仍须一致）；全栈必须匿名 HTTPS 请求成功。失败或跳转登录页不会显示“匿名访问已验证”。

静态浏览器验证自动从精确上传清单建立临时站点（单 HTML 对应 index.html），分配本地端口，不执行 npm install 或构建。省略 start_command/base_url，使用相对 navigate URL。HTTP 模式要求已有服务，拒绝 start_command，状态断言使用 expected_code；它不能替代 3D 页面的渲染和交互验收。与 OSS 一样，可发布当前授权工作区内已有的内容，不要求由当前对话生成。验证凭据可来自其他会话，但必须匹配同一发布路径、模式及未变化的内容；验证不可用或跳过均不能发布。

静态上传保持目录结构，使用 `--runtime static --skip-build --skip-push`，不执行沙箱源码同步。全栈遵循 .dockerignore（支持常用 `*`、`**`、`?`、否定和目录规则；字符类或转义规则明确拒绝，需先简化）。两种模式强制排除凭证、.env、数据库文件、Git、符号链接、缓存等；项目规则不能重新包含它们。发现文件内明显的令牌或连接密码时拒绝整个包。

CLI 创建项目产生的配置与上传目录隔离。复制后的文件再次核对大小与摘要；模型不能传入旧云项目 ID。发布记录持久化在项目库的 meoo_publications，按 Run 与工具调用去重，关联 Session、快照、项目、版本和访问地址。相同调用不会创建第二个项目。

全栈 `.dockerignore` 必须保留自身；若被规则排除，在末尾添加 `!/.dockerignore` 后重新验证。否则 CLI 会恢复默认排除规则，改变上传范围，因此检查阶段会拒绝发布。匹配大小写行为与 CLI 0.5.4 一致。网站地址只采用平台返回值；项目设置入口采用官方固定路由及平台创建响应中的项目 ID，在部署开始前保存，因此全栈、构建失败和超时也可进入设置。

## 范围与故障

全栈运行环境是平台提供的 Node/Python/Java/Go 环境，不支持任意 Docker 镜像或 Compose 多服务。应用不能依赖容器本地磁盘保存业务数据。发现 SQLite、额外运行变量、缺失启动脚本或明显不支持的运行环境时，先回到普通开发流程适配。静态检查不是完整程序分析，仍需验证应用主要功能与持久化需求。

首版不自动开通数据库、不迁移数据库、不管理应用运行密钥、不购买额度；不支持覆盖、回滚、自定义域名或删除历史站点。

| 错误/状态 | 处理 |
| --- | --- |
| MEOO_DISABLED | 部署管理员启用开关并重启 |
| MEOO_CREDENTIALS_UNAVAILABLE / MEOO_CREDENTIAL_PERMISSIONS | 检查绝对路径、只读挂载、服务用户和 0600 权限 |
| MEOO_AUTH_FAILED | 通过官方 CLI 修复账号登录，再由用户明确发起新发布 |
| MEOO_CLI_VERSION_MISMATCH | 安装固定版本 0.5.4 |
| MEOO_VERIFICATION_REQUIRED / MEOO_VERIFICATION_WORKSPACE_MISMATCH / MEOO_VERIFICATION_STALE | 对精确目录重新 VerifyJourney，传 publication_path、publication_runtime |
| MEOO_VERIFICATION_UNAVAILABLE | 检查 Python capabilities 的 reason，修复浏览器/HTTP 验证环境并重启；不要跳过验证 |
| MEOO_VERIFICATION_SETUP_REQUIRED / VERIFY_JOURNEY_INVALID_BASE_URL | 全栈浏览器验证需提供启动命令和同一端口的本地 HTTP 地址 |
| VERIFY_JOURNEY_INVALID_STEP / VERIFY_JOURNEY_HTTP_START_UNSUPPORTED | 按工具字段说明修正步骤；HTTP 模式先启动服务，再指定实际地址 |
| MEOO_SNAPSHOT_CHANGED | 文件在授权后变化，重新检查、验证、授权 |
| MEOO_STATIC_RESOURCE_MISSING | 补齐目录内资源或改选完整站点 |
| MEOO_QUOTA_EXCEEDED | 用户在平台检查额度；不自动购买或重试 |
| MEOO_BUILD_FAILED | 在项目设置查看构建结果，普通开发流程修复 |
| MEOO_APP_ENV_REQUIRED / MEOO_PERSISTENT_STORAGE_ADAPTATION_REQUIRED | 先适配运行配置或外部持久化；发布工具不会补齐 |
| 已部署，公网待验证 | 在项目设置检查访问权限和平台就绪状态，不承诺所有人已可访问 |
| 远端结果待确认 | 超时、取消或无法确认响应；保留已知项目 ID，不自动重发或声称撤销 |

取消本地进程不会保证远端取消。创建项目后失败也保留记录与项目，不自动删除。重复投递返回已有结果；用户新的发布调用才会创建新站点。
