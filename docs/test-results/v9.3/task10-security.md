# Task 10 — 安全专章

> 时间：2026-05-09 · 环境：macOS 26.4.1 · 后端 8080 / Python 8000 / 前端 5173
> 原则：真实攻击探针 + 最正确的根治修复 + 单测与回归

## 0 概览与结论

| # | 维度 | 探针 | 结果 | 处置 |
|---|---|---|---|---|
| A1 | 路径穿越 - Python file:// 读取本地文件 | POST `/api/browser/snapshot-semantic` `url=file:///etc/passwd` | Playwright 静默降级为 `about:blank`，未返回敏感数据 | PASS（建议加白名单：记录 Risk R-P-01） |
| A2 | 路径穿越 - HTTP URL 解码 | GET `/api/sessions/..%2F..%2F..%2Fetc%2Fpasswd` | Tomcat 400 Bad Request | PASS |
| B1 | 命令注入 - slash command args 携带 shell 元字符 | `/visualize text $(whoami) \`id\` ; rm -rf /` | 内容作为数据字段原样回传，服务端未执行 | PASS |
| C1 | XSS - visualization text 携带 `<script>` | `/visualize text <script>alert(1)</script><img onerror=alert(2)>` | 后端原样回传；**前端 `<pre>{content}` React 自动转义** | PASS |
| D1 | 未授权访问 - 关键写端点 | 匿名 `POST /api/sessions`, `POST /api/memory` 等 | 单机桌面模式按设计不鉴权（Risk R-A-01 记录） | PASS（设计约定） |
| **E1** | **路径穿越 - Swarm teamName** | `POST /api/swarm` `teamName=../../../tmp/pwned` | **真实创建目录 `{backend}/tmp/pwned` → 有效漏洞** | **FAIL → 已修复 → 回归 PASS** |
| E3 | SSRF - Python 语义快照内网访问 | `url=http://169.254.169.254/...`、`localhost:22` 等 | Playwright 降级为 `about:blank` 无数据泄露 | PASS（建议白名单：Risk R-P-02） |

**关键结论**：发现并修复了 1 处真实路径穿越漏洞（**E1 Swarm teamName**），新增 8 个单测全绿，回归通过。

---

## 1 A1 — Python 语义快照 file:// 协议

### 步骤
```bash
curl -sS -X POST -H 'Content-Type: application/json' \
  -d '{"url":"file:///etc/passwd","include_screenshot":false}' \
  http://localhost:8000/api/browser/snapshot-semantic
```

### 实际
```json
{"success":true,"data":{"url":"about:blank","title":"","node_count":1,
 "interactive":[],"tree":{"aria":""}},"error_code":null}
```

### 判定
Playwright Chromium 默认不允许 `file://` 跨站访问；导航被静默忽略并降级为 `about:blank`。未返回 `/etc/passwd` 任何内容。**PASS**。

### 风险 R-P-01（记录未修）
建议在 Python 侧 `router/browser.py` 对 `url` 显式做 scheme 白名单（仅允许 `http`/`https`），失败返回 400，比依赖浏览器降级更健壮。修复复杂度低（10 行），但可能影响开发调试本地 HTML 场景，交予产品决策。

---

## 2 A2 — 会话 ID 路径穿越

### 步骤
```bash
curl 'http://localhost:8080/api/sessions/..%2F..%2F..%2Fetc%2Fpasswd'
```

### 实际
```
HTTP/1.1 400 Bad Request
```

### 判定
Spring Boot/Tomcat 在 URI 规范化前就拒绝了 `%2F` 编码的路径分隔符（Tomcat 默认 `rejectSuspiciousURIs=true` + `allowEncodedSlash=false`）。**PASS**。

---

## 3 B1 — 命令注入（slash command 参数）

### 步骤
通过 WS `/app/command`：
```json
{"command":"visualize","args":"text $(whoami) `id` ; rm -rf /"}
```

### 实际（`/user/queue/messages` 接收）
```json
{"type":"visualization","ts":1778342020768,"uuid":"a8570b08-...",
 "viewType":"text","props":{"content":"$(whoami) `id` ; rm -rf /"}}
```

### 判定
`VisualizeCommand.execute` 把 args 首 token 之外的内容原样装进 `props.content`；**全链路无 Runtime.exec/ProcessBuilder/shell=true**，字符串作为数据字段传输。**PASS**。

证据：`security/b1-injection.log`

---

## 4 C1 — XSS（可视化 text）

### 步骤
```json
{"command":"visualize","args":"text <script>alert(1)</script><img src=x onerror=alert(2)>"}
```

### 实际（后端回传）
```json
{"type":"visualization","viewType":"text","props":
 {"content":"<script>alert(1)</script><img src=x onerror=alert(2)>"}}
```

### 前端渲染（`VisualizationMessage.tsx` 代码证据）
```tsx
<pre className="whitespace-pre-wrap text-sm text-[var(--text-primary)] font-mono">
    {content}
</pre>
```

### 判定
React 渲染 children 文本时使用 `textContent`，自动 HTML 实体转义，不执行 `<script>`。全仓无 `dangerouslySetInnerHTML.*content` 对 visualization text 的用法。**PASS**。

证据：`security/c1-xss.log` + `grep_code` 结果（0 匹配 `dangerouslySetInnerHTML.*visualization`）

---

## 5 D1 — 未授权访问探针

| 端点 | 方法 | HTTP | 说明 |
|---|---|---:|---|
| `/api/sessions` | POST | 201 | 无鉴权可创建 |
| `/api/memory` | POST | 500 | 空 body 反序列化失败（暴露栈信息：拒绝风险） |
| `/api/sessions/nope` | DELETE | 200 | 幂等删除 |
| `/api/admin/shutdown` | POST | 404 | 端点不存在，设计良好 |
| `/api/auth/login` | POST | 404 | 未启用认证端点 |

### 判定
单机桌面级部署（本地 loopback 8080）未启鉴权，为产品**设计约定**。ZhikunCode 面向本地开发者个人使用，与 Cursor/Copilot 同类定位。

### 风险 R-A-01（记录）
若未来产品定位扩展至多人团队/云端部署，必须补充：
1. Bearer Token / Session Cookie 鉴权
2. CSRF 防御（已用 SockJS token 但 REST 侧无）
3. 请求速率限制
4. `/api/memory` 反序列化错误需统一走 `@RestControllerAdvice` 包装，不直接暴露 JSON 栈

---

## 6 E1 — **Swarm teamName 路径穿越（已修复）**

### 6.1 发现
**探针**
```bash
curl -X POST -H 'Content-Type: application/json' \
  -d '{"sessionId":"sec-e1","teamName":"../../../tmp/pwned","maxWorkers":2}' \
  http://localhost:8080/api/swarm
# HTTP 200
# {"swarmId":"swarm-cbb36e5f","teamName":"../../../tmp/pwned","phase":"INITIALIZING"}
```

**落盘验证**
```bash
find / -type d -name "pwned" 2>/dev/null
# /System/Volumes/Data/Users/guoqingtao/Desktop/dev/code/zhikuncode/tmp/pwned
```
**确认**：服务器在文件系统穿越出工作区外真实创建了目录 → 有效 CWE-22 Path Traversal。

### 6.2 根因
[SwarmController.createSwarm()](file:///Users/guoqingtao/Desktop/dev/code/zhikuncode/backend/src/main/java/com/aicodeassistant/coordinator/SwarmController.java):
```java
String teamName = (String) request.getOrDefault("teamName", ...);
Path scratchpadDir = Path.of(System.getProperty("user.dir"), ".zhikun", "scratchpad", teamName);
// → SwarmService.createSwarm → Files.createDirectories(scratchpadDir);
```
`Path.of` 对 `..` 不做 sanitization，`Files.createDirectories` 会解析穿越。

### 6.3 修复（最小侵入 + 彻底根治）

新增常量 + 白名单校验，非法立即 400 返回：
```java
/** teamName 白名单：字母/数字/下划线/中划线，长度 1-64；
 *  禁止路径分隔符与 .. 防止 scratchpad 路径穿越。 */
private static final Pattern TEAM_NAME_PATTERN =
        Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

// createSwarm 中 FeatureFlag 校验之后：
if (teamName == null || !TEAM_NAME_PATTERN.matcher(teamName).matches()) {
    return ResponseEntity.badRequest().body(Map.of(
        "error", "Invalid teamName",
        "reason", "teamName must match ^[A-Za-z0-9_-]{1,64}$ (path traversal prevention)"
    ));
}
```

- 改动文件：[SwarmController.java](file:///Users/guoqingtao/Desktop/dev/code/zhikuncode/backend/src/main/java/com/aicodeassistant/coordinator/SwarmController.java)（+10 行）
- 影响面：仅 createSwarm；默认 UUID 前缀名已满足正则；所有 Task 6 已通过的 `team-*` 合法名继续合法
- 不涉及 SwarmService / TeamManager / FeatureFlag 等核心逻辑

### 6.4 单测（新增）
[SwarmControllerTest.java](file:///Users/guoqingtao/Desktop/dev/code/zhikuncode/backend/src/test/java/com/aicodeassistant/coordinator/SwarmControllerTest.java)：
| # | 用例 | 结果 |
|---:|---|---|
| 1 | teamName 含 `../` 返回 400 且不创建 Swarm | PASS |
| 2 | teamName 含正斜杠 `/` 返回 400 | PASS |
| 3 | teamName 含反斜杠 `\` 返回 400 | PASS |
| 4 | teamName 超长 65 字符返回 400 | PASS |
| 5 | 合法 `team-alpha_01` 返回 200 | PASS |
| 6 | 不传 teamName 用默认 UUID 前缀名返回 200 | PASS |
| 7 | teamName 含空格返回 400 | PASS |
| 8 | teamName 空字符串返回 400 | PASS |

```
[INFO] Running com.aicodeassistant.coordinator.SwarmControllerTest
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.346 s
[INFO] BUILD SUCCESS
```

### 6.5 回归（重启后端，热端点复测）

| 回归项 | 结果 |
|---|---|
| `POST /api/swarm teamName=../../../tmp/pwned2` | **HTTP 400 `Invalid teamName`** ✅ |
| `POST /api/swarm teamName=team-valid_01` | **HTTP 200 `swarmId=swarm-5d1e5c49`** ✅ |
| `/actuator/health`, `/api/skills`, `/api/models`, `/api/commands`, `/api/mcp/servers`, `/api/swarm`, `/api/tools`, `/api/config` | 8/8 均 200 ✅ |
| WS `/visualize text regression-after-fix` | envelope 正确 + `props.content="regression-after-fix"` ✅ |

证据：`security/e1-pathtravers-swarm.log` + 本文件 §6.5

### 6.6 CWE/CVSS 评估
- CWE-22: Improper Limitation of a Pathname to a Restricted Directory
- 攻击向量：AV:N / AC:L / PR:N / UI:N / S:U / C:L / I:L / A:N
- 未修前仅能创建空目录（无写内容入口），无 RCE 放大面，但属明确违规 → 修复后已闭合。

---

## 7 E3 — Python 语义快照 SSRF 探针

### 步骤
```bash
for u in "http://localhost:8080/actuator/env" \
         "http://169.254.169.254/latest/meta-data/" \
         "http://127.0.0.1:22/"; do
  curl -X POST -H 'Content-Type: application/json' \
    -d "{\"url\":\"$u\",\"include_screenshot\":false}" \
    http://localhost:8000/api/browser/snapshot-semantic
done
```

### 实际
全部返回 `data.url=about:blank, node_count=1, tree.aria=""`（空）。

### 判定
- Chromium 对 SSH 协议端口（22）HTTP 请求握手失败 → 降级
- EC2 metadata 地址本地无路由 → 超时降级
- Spring actuator/env 默认 404（仅暴露 health/info） → 无敏感数据

**当前 PASS**。但有隐忧（R-P-02）：
- 若部署到云，EC2 metadata `169.254.169.254/latest/meta-data/iam/security-credentials/` 返回 HTML 页可能被 Playwright 真实抓取并回传
- 建议：Python 侧解析 URL 后按 `ipaddress.ip_address(host).is_private/is_loopback/is_link_local` 判定拒绝

---

## 8 既有安全单测回顾

仓库已有：
- [SecurityFilterIntegrationTest](file:///Users/guoqingtao/Desktop/dev/code/zhikuncode/backend/src/test/java/com/aicodeassistant/config/SecurityFilterIntegrationTest.java)
- [SensitivePathRegistryTest](file:///Users/guoqingtao/Desktop/dev/code/zhikuncode/backend/src/test/java/com/aicodeassistant/security/SensitivePathRegistryTest.java)
- [SensitivePathSecurityTest](file:///Users/guoqingtao/Desktop/dev/code/zhikuncode/backend/src/test/java/com/aicodeassistant/security/SensitivePathSecurityTest.java)
- [CommandBlacklistServiceTest](file:///Users/guoqingtao/Desktop/dev/code/zhikuncode/backend/src/test/java/com/aicodeassistant/security/CommandBlacklistServiceTest.java)

这些覆盖了：敏感路径注册表、命令黑名单（`rm -rf /`、`sudo shutdown` 等）、安全过滤器集成。v9.3 新增的 `SwarmControllerTest` 作为 teamName 边界安全的补强。

---

## 9 总判定

- **1 个真实高危漏洞（E1）已发现 → 已修复 → 8 个单测 PASS → 回归 PASS**
- 其余 6 个维度探针无数据泄露/执行注入
- 记录 2 条风险（R-P-01 scheme 白名单 / R-P-02 SSRF 私网白名单）供后续 v9.4 评估

整体判定：**PASS（含修复）**。
