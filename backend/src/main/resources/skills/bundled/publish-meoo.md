---
name: publish-meoo
description: 仅在用户明确要求通过秒悟发布应用、创建公网分享链接或调用 /publish-meoo 时，将指定工作区路径发布到新的秒悟站点；不因生成或验证完成而自动发布
allowed-tools: InspectMeooDeployment,PublishMeoo,VerifyJourney,AskUserQuestion
arguments: path
argument-hint: "精确 HTML 文件、静态站点目录或全栈源码目录"
when_to_use: 用户明确要求秒悟发布时使用
effort: medium
context: inline
user-invocable: true
version: "1.0"
---

# 发布新的秒悟公网应用

用户指定路径：`{{path}}`。可发布当前授权工作区内已有的内容，不要求由当前对话生成。缺少精确路径时询问，不扫描工作区猜测目标。说明每次发布都会创建新站点、保留旧站点并占用平台额度。

1. 确认 runtime 为 static（HTML/静态目录/已构建前端）或 image（需要后端进程）。应用名称默认由路径生成。
2. 调用 InspectMeooDeployment 检查精确路径。静态单文件引用其他本地资源时，改为用户确认的完整目录。全栈必须有 scripts/setup.sh 和 scripts/start.sh，监听 0.0.0.0:${PORT:-9000}，不依赖本地持久化。未配置运行密钥、SQLite、多服务等问题必须先适配。
3. 发布工具不修改代码、不本地构建、不部署数据库。缺失部署脚本或构建产物时，明确返回普通开发流程补齐，再重新发起发布；禁止用 Bash/curl/其他发布方式绕过检查。
4. 使用对应发布内容已有的 VerifyJourney 通过证据（可来自其他会话），或调用 VerifyJourney 验证浏览器主要功能/HTTP 服务。验证时必须传 publication_path（精确发布路径）与 publication_runtime（static/image），使证据绑定该目录的内容摘要；仅有同工作区的通用证据不能用于发布，验证后文件不能改变。工具表示跳过或不可用不等于验证通过。将实际 evidence bundle ID 作为 verification_id 再次检查。
5. 告知权限卡将显示目标账号、应用、模式、文件数、大小和摘要；全栈会上传源码并远程构建。仅调用一次 PublishMeoo，由现有授权机制完成逐次确认。
6. 拒绝授权后停止；失败、取消、超时或额度不足时不重试、不创建替代项目、不购买额度。远端状态不明不代表已撤销。用户新的明确发布请求才可创建另一个站点。
7. 按网站结果卡报告：匿名访问已通过、已部署但待验证、或失败/结果待确认。不要把平台登录页视为公网成功。公开链接仅由结果卡展示，禁止猜测或拼接链接；访问权限需调整时引导打开卡片中的秒悟项目设置。

静态页面使用 browser 模式验证渲染与主要交互；HTTP 200 不能证明 3D 模型已加载。传 publication_path 和 publication_runtime=static，省略 start_command、base_url：工具按上传清单复制到临时目录并自动启动静态服务，独立 HTML 映射为 index.html。navigate 使用 `/` 等相对地址。

浏览器步骤示例：`{"action":"navigate","url":"/"}`、`{"action":"wait_for","selector":"#loading.done","state":"attached","timeout":60000}`、`{"action":"assert_text","selector":"body","expected":"应用标题"}`。按真实页面选择元素；wait_for 不支持 js 表达式。浏览器 timeout 单位为毫秒。

全栈 browser 模式在发布目录启动服务；无法自动探测时提供 start_command 和对应的本地 HTTP base_url（显式端口）。HTTP 模式只访问已有服务，不接受 start_command；使用 `{"action":"http_get","url":"/api/health"}`、`{"action":"assert_status","expected_code":200}` 等 HTTP 步骤，不支持 assert_text。HTTP timeout 单位为秒。

若返回 MEOO_VERIFICATION_UNAVAILABLE，说明部署实例的 Python 验证能力需要管理员修复，不是秒悟平台拒绝。停止发布，不把独立 Playwright 截图当作 verification_id。

禁止读取或复述 API Key、credentials.json、.env、容器环境变量；不上传凭证、数据库、Git、符号链接和截图证据。静态发布只上传选定产物，不同步云端沙箱。全栈发布不开通云服务，不修改外部数据库，不复制 .env；依赖这些资源的应用须预先适配配置。
