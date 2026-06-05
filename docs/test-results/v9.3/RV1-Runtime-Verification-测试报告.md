# RV-1 运行时验证 测试报告

> **报告版本**: v1.0 | **测试日期**: 2026-06-05 | **测试范围**: RV-1 Runtime Verification 全栈验证（Java 5 模块 / Python 4 模块 / E2E 8 用例 = 55 用例 + 7 能力域 + 1 Feature Flag 验证）
> **口径**：**真实三端启动 / 真调运行中服务（无 mock）/ Maven Surefire + pytest 实证 / 每条用例与日志/接口响应可追溯**
> **执行模式**: 单元测试（Java + Python）+ 集成测试（直连 Backend 与 Python 微服务）双线并行
>
> **总体结果**: **PASS** — 55/55 核心用例 100% 通过，0 FAIL / 0 ERROR / 0 SKIP

---

## 0 执行摘要

| 维度 | 结果 |
|---|---|
| **总测试用例** | **55**（Java 单测 29 + Python 单测 18 + E2E 集成 8）|
| Java 单元测试 | 29 PASS / 0 FAILURE / 0 ERROR / 0 SKIP（Surefire 累计 3.420s）|
| Python 单元测试 | 18 PASS / 0 FAILURE（pytest 0.15s）|
| E2E 集成测试 | 8 PASS / 0 FAIL（直连真实服务，无 mock / 无打桩）|
| Feature Flag | `RUNTIME_VERIFICATION=true`，VerifyJourney Tool 已注册（启动日志 "46 tools"）|
| 能力域可用性 | 7/7 available（HTTP_API / BROWSER_AUTOMATION / CODE_INTEL / GIT_ENHANCED / FILE_PROCESSING / CODE_QUALITY / ANALYSIS）|
| 三端启动 | Backend (8080) UP / Python (8000) ok / Frontend (5173) HTTP 200 |
| 缺陷数 | 0 阻塞缺陷；3 项实现-计划字段差异（已按真实契约对齐） |
| **总体判定** | **PASS** |

**关键结论**

1. RV-1 双域验证架构（VerifierFactory 多态分发 → HttpApiVerifier / BrowserVerifier）单元覆盖全绿，证据持久化（EvidenceStore）写入 + SHA-256 blob + 级联 items + 反查全链路通过。
2. Python 端 HTTP API 步骤执行器 18 个测试覆盖动词（GET/POST/PUT/DELETE）、断言（status/json/header）、变量系统（提取与注入）、边界防护（MAX_STEPS / 首步失败即停 / 未知 action），全部通过。
3. E2E 直接调用运行中 Python 服务（:8000）与 Backend 健康端点（:8080），跨服务调用、JSONPath 命中、变量跨步骤传递、断言失败、首步即停、501 步上限拒绝六类场景实证通过。
4. VerifyJourney Tool 已经过 Feature Flag 控制接入运行时（启动日志 "46 tools"），注册路径与权限管线一致。

---

## 1 测试环境

| 项目 | 详情 |
|------|------|
| **操作系统** | Darwin 25.5.0 arm64 (Apple Silicon) |
| **JDK** | Amazon Corretto 21.0.10 |
| **Node.js** | v22.14.0 |
| **Python** | 3.11.15（venv 隔离环境）|
| **Git** | 2.50.1 |
| **Spring Boot** | 3.4.13 |
| **数据库** | SQLite 嵌入式 |
| **测试日期** | 2026-06-05 |
| **测试框架** | Java：JUnit 5 + Mockito + Maven Surefire；Python：pytest + pytest-asyncio；E2E：Python httpx + asyncio |

**服务配置：**

| 服务 | 端口 | PID | 健康检查 |
|------|------|-----|---------|
| Backend (Java Spring Boot) | 8080 | 46336 | `UP`，java=21.0.10，db=`UP`，jvm=Heap 91MB/4096MB |
| Python (FastAPI v1.15.0) | 8000 | 46337 | `ok` |
| Frontend (Vite Dev Server) | 5173 | 46338 | HTTP 200 |

三端均处于运行态，E2E 测试直接打到真实端口，无任何 mock / fixture / 打桩。

---

## 2 通过率矩阵

| 序号 | 模块 | 用例数 | PASS | FAIL | ERROR | SKIP | 通过率 | 备注 |
|------|------|--------|------|------|-------|------|--------|------|
| 1 | VerifierFactory（Java）| 8 | 8 | 0 | 0 | 0 | 100% | 多态分发 三向 + 边界 |
| 2 | HttpApiVerifier（Java）| 5 | 5 | 0 | 0 | 0 | 100% | 含 STOMP 推送验证 |
| 3 | BrowserVerifier（Java）| 5 | 5 | 0 | 0 | 0 | 100% | 含 120s 超时验证 |
| 4 | EvidenceStore（Java）| 6 | 6 | 0 | 0 | 0 | 100% | 含 SHA-256 blob |
| 5 | VerifyJourneyTool（Java）| 5 | 5 | 0 | 0 | 0 | 100% | Feature Flag + 能力域门控 |
| 6 | HTTP 动词 Action Handler（Python）| 6 | 6 | 0 | 0 | 0 | 100% | GET/POST/PUT/DELETE + 异常 |
| 7 | 断言 Action Handler（Python）| 6 | 6 | 0 | 0 | 0 | 100% | status / json / header |
| 8 | 变量系统（Python）| 3 | 3 | 0 | 0 | 0 | 100% | 提取 + 注入 + 跨步骤 |
| 9 | 边界防护（Python）| 3 | 3 | 0 | 0 | 0 | 100% | MAX_STEPS / 首步失败 / 未知 action |
| 10 | E2E 集成测试 | 8 | 8 | 0 | 0 | 0 | 100% | 真实服务，无 mock |
| **合计** | | **55** | **55** | **0** | **0** | **0** | **100%** | — |

> 注：所有用例均为真实 Surefire/pytest 报告与真实 HTTP 响应实证，未引入条件跳过或动态 skip。

---

## 3 服务启动验证

### 3.1 Backend（Java Spring Boot, :8080）— PASS

- **健康检查**: `GET http://localhost:8080/api/health`
- **响应**:
  ```json
  {"status":"UP","service":"ai-code-assistant-backend","version":"1.0.0","java":"21.0.10",
   "subsystems":{"database":{"status":"UP","message":"SQLite embedded database available"},
   "jvm":{"status":"UP","message":"Heap: 91MB/4096MB"}}}
  ```
- **PID**: 46336
- **启动日志**: 输出 `46 tools registered`，VerifyJourney Tool 在 `RUNTIME_VERIFICATION=true` 时挂载到工具池
- **判定**: PASS — JVM、数据库、版本、工具数均符合预期

### 3.2 Python（FastAPI v1.15.0, :8000）— PASS

- **健康检查**: `GET http://localhost:8000/api/health`
- **响应**: `{"status":"ok","service":"ai-code-assistant-python","version":"1.15.0"}`
- **PID**: 46337
- **判定**: PASS

### 3.3 Frontend（Vite Dev Server, :5173）— PASS

- **请求**: `GET http://localhost:5173`
- **响应**: HTTP 200
- **PID**: 46338
- **判定**: PASS — Vite HMR 启动正常，前端可达

### 3.4 能力域探测 — PASS（7/7 available）

- **请求**: `GET http://localhost:8000/api/health/capabilities`
- **响应**:

| 能力域 | 状态 |
|--------|------|
| HTTP_API | available |
| BROWSER_AUTOMATION | available |
| CODE_INTEL | available |
| GIT_ENHANCED | available |
| FILE_PROCESSING | available |
| CODE_QUALITY | available |
| ANALYSIS | available |

- **判定**: PASS — 7 个能力域全部 available，覆盖 RV-1 双域验证所需依赖（HTTP_API / BROWSER_AUTOMATION）。

---

## 4 Java 单元测试详细记录

> **执行命令**: `mvn -pl backend test -Dtest='VerifierFactoryTest,HttpApiVerifierTest,BrowserVerifierTest,EvidenceStoreTest,VerifyJourneyToolTest'`
> **结果汇总**: Tests run: 29, Failures: 0, Errors: 0, Skipped: 0，累计耗时 3.420s
> **JUnit 平台**: JUnit Jupiter 5 + Mockito（针对 PythonServiceClient / EvidenceStore 的依赖隔离）

### 4.1 VerifierFactory — 8/8 PASS（耗时 0.044s）

> 多态分发的核心入口，按 `mode` 字段路由到 BrowserVerifier / HttpApiVerifier / Auto。

**TC-VF-01: mode=browser 路由到 BrowserVerifier — PASS**
- **操作**: 构造 `mode="browser"` 的 VerifyRequest，调用 `factory.create(req)`
- **验证**: 返回实例为 `BrowserVerifier`，未触发 HttpApiVerifier 构造
- **判定**: PASS — 显式 browser 路由命中

**TC-VF-02: mode=http_api 路由到 HttpApiVerifier — PASS**
- **操作**: 构造 `mode="http_api"`
- **验证**: 返回实例为 `HttpApiVerifier`
- **判定**: PASS

**TC-VF-03: mode=auto + 仅 BROWSER_AUTOMATION 可用 — PASS**
- **操作**: mock 能力域仅 BROWSER_AUTOMATION available
- **验证**: auto 降级为 BrowserVerifier
- **判定**: PASS — auto 模式按可用域优先级分发

**TC-VF-04: mode=auto + 仅 HTTP_API 可用 — PASS**
- **操作**: mock 能力域仅 HTTP_API available
- **验证**: auto 降级为 HttpApiVerifier
- **判定**: PASS

**TC-VF-05: mode=auto + 两域均可用 — PASS**
- **操作**: 两域同时 available
- **验证**: auto 按既定优先级返回 BrowserVerifier（用户语义优先视觉证据）
- **判定**: PASS

**TC-VF-06: mode=null 抛出参数异常 — PASS**
- **操作**: `req.mode = null`
- **验证**: 抛出 `IllegalArgumentException`，错误信息含 mode 字段
- **判定**: PASS — null 守卫生效

**TC-VF-07: mode 为空字符串抛出参数异常 — PASS**
- **操作**: `req.mode = ""`
- **验证**: 抛出 `IllegalArgumentException`
- **判定**: PASS — 空字符串视同非法

**TC-VF-08: mode 取值非法（mixed）抛出异常 — PASS**
- **操作**: `req.mode = "mixed"`（不在白名单 browser/http_api/auto）
- **验证**: 抛出 `IllegalArgumentException`，提示合法值集合
- **判定**: PASS — 白名单门控生效

### 4.2 HttpApiVerifier — 5/5 PASS（耗时 0.018s）

> 调用 Python `/api/runtime/journey/http` 执行 DSL 步骤，并将结果汇聚回 EvidenceStore。

**TC-HA-01: HTTP_API 能力不可用时直接拒绝 — PASS**
- **操作**: mock 能力探测返回 HTTP_API=unavailable，调用 `verify()`
- **验证**: 返回 `passed=false`，`error` 含 "HTTP_API capability unavailable"
- **判定**: PASS — 前置门控有效，未发出真实 HTTP 调用

**TC-HA-02: 正常 verify 路径 — PASS**
- **操作**: mock PythonServiceClient 返回 `passed=true`、step_results 长度=3
- **验证**: VerifyResult 字段完整（passed / steps / duration）
- **判定**: PASS

**TC-HA-03: 空响应处理 — PASS**
- **操作**: mock 返回空 body
- **验证**: 不抛 NPE，返回结构化失败结果，error 字段说明原因
- **判定**: PASS

**TC-HA-04: 入参校验（base_url 必填） — PASS**
- **操作**: 缺失 base_url 字段
- **验证**: 抛出参数校验异常，错误信息含字段名
- **判定**: PASS

**TC-HA-05: STOMP 推送验证消息 — PASS**
- **操作**: 调用 verify 后断言 SimpMessagingTemplate 至少调用一次
- **验证**: `/topic/runtime-verification/{sessionId}` 有事件推送，载荷含 verifyId
- **判定**: PASS — 实时进度回传链路正常

### 4.3 BrowserVerifier — 5/5 PASS（耗时 0.009s）

> 通过 Python BROWSER_AUTOMATION 能力域执行浏览器步骤，对 rv- 前缀变量做隔离处理。

**TC-BV-01: BROWSER_AUTOMATION 能力不可用时直接拒绝 — PASS**
- **操作**: mock 能力域 BROWSER_AUTOMATION=unavailable
- **验证**: passed=false，error 提示能力不可用，未触发 Playwright 启动
- **判定**: PASS

**TC-BV-02: 正常 verify 路径 — PASS**
- **操作**: mock Python 返回 passed=true，含截图与 DOM 摘要
- **验证**: VerifyResult.steps 包含 screenshot blob 引用
- **判定**: PASS

**TC-BV-03: 空响应不崩溃 — PASS**
- **操作**: mock Python 返回空 step_results
- **验证**: 优雅返回 passed=false 而非抛出
- **判定**: PASS

**TC-BV-04: rv- 前缀变量隔离 — PASS**
- **操作**: 输入变量含 `rv-token` 名称
- **验证**: 转发至 Python 时按内部命名规范处理，不与业务变量冲突
- **判定**: PASS — 命名空间隔离生效

**TC-BV-05: 120s 超时配置 — PASS**
- **操作**: 检查 `BrowserVerifier` 内部 HTTP 客户端超时阈值
- **验证**: 超时上限为 120 秒，与 Playwright 浏览器步骤的最坏耗时匹配
- **判定**: PASS — 超时常量符合设计

### 4.4 EvidenceStore — 6/6 PASS（耗时 1.254s）

> 验证证据持久化：verify 主记录 + items 级联 + blob SHA-256 入库 + 反查链路。

**TC-ES-01: save 主记录写入 — PASS**
- **操作**: 构造 VerifyResult 调用 `evidenceStore.save()`
- **验证**: 主表行数 +1，主键非空
- **判定**: PASS

**TC-ES-02: 级联保存 items — PASS**
- **操作**: VerifyResult 含 3 个 step item，调用 save
- **验证**: items 子表 +3 行，外键回指主记录
- **判定**: PASS

**TC-ES-03: findById 命中 — PASS**
- **操作**: save 后用主键反查
- **验证**: 返回完整聚合（含 items 与 blobs）
- **判定**: PASS

**TC-ES-04: findById 不存在返回 null/Optional.empty — PASS**
- **操作**: 用伪造 ID 查询
- **验证**: 返回空，不抛异常
- **判定**: PASS

**TC-ES-05: saveBlob 计算 SHA-256 — PASS**
- **操作**: 写入 byte[] payload
- **验证**: 持久化记录中 hash 字段为 SHA-256，与 `MessageDigest.getInstance("SHA-256")` 期望值一致
- **判定**: PASS — 证据完整性可校验

**TC-ES-06: findBySession 列表反查 — PASS**
- **操作**: 同一 sessionId 写入多条 verify 记录后调用 `findBySession`
- **验证**: 返回列表按时间倒序，长度与写入次数一致
- **判定**: PASS

### 4.5 VerifyJourneyTool — 5/5 PASS（耗时 0.162s）

> 工具入口的 Feature Flag 与能力域联合门控。

**TC-VT-01: Feature Flag 关闭直接拒绝 — PASS**
- **操作**: mock `RUNTIME_VERIFICATION=false`
- **验证**: 工具返回结构化拒绝结果，error 含 "feature disabled"，未调用 VerifierFactory
- **判定**: PASS

**TC-VT-02: BROWSER_AUTOMATION 可用时正常分发 — PASS**
- **操作**: Flag=true + BROWSER 域 available
- **验证**: 命中 BrowserVerifier，passed=true
- **判定**: PASS

**TC-VT-03: 仅 HTTP_API 可用时降级 — PASS**
- **操作**: Flag=true + 仅 HTTP_API available
- **验证**: 自动降级到 HttpApiVerifier，结果链路完整
- **判定**: PASS — auto 模式与单域可用性联动

**TC-VT-04: 两域均不可用时拒绝 — PASS**
- **操作**: Flag=true 但 HTTP_API 与 BROWSER 均 unavailable
- **验证**: passed=false，error 提示无可用 verifier
- **判定**: PASS

**TC-VT-05: getName 返回稳定标识 — PASS**
- **操作**: 调用 `tool.getName()`
- **验证**: 返回 `VerifyJourney`（与工具池注册键一致）
- **判定**: PASS — 工具注册键稳定

---

## 5 Python 单元测试详细记录

> **执行命令**: `pytest python-service/tests/test_runtime_http_api.py -v`
> **结果汇总**: 18 passed / 0 failed，累计耗时 0.15s
> **测试目标**: HTTP API 步骤执行器（动词、断言、变量、边界）

### 5.1 HTTP 动词 Action Handler — 6/6 PASS

**TC-HA-01: GET 200 正常返回 — PASS**
- **操作**: 步骤 `{action:"http_get", url:"/health"}`，mock httpx 返回 200
- **验证**: step_result.ok=true，response 字段含 status=200 与 body
- **判定**: PASS

**TC-HA-02: GET 抛 ConnectError 优雅降级 — PASS**
- **操作**: mock httpx 抛 `httpx.ConnectError`
- **验证**: step_result.ok=false，error 含 "ConnectError"，不抛栈到上层
- **判定**: PASS

**TC-HA-03: GET 抛 TimeoutException — PASS**
- **操作**: mock 抛 `httpx.TimeoutException`
- **验证**: step_result.ok=false，error 标记超时
- **判定**: PASS

**TC-HA-04: POST JSON body — PASS**
- **操作**: `{action:"http_post", url:"/api/x", json:{"a":1}}`
- **验证**: 透传 JSON body，Content-Type 自动 `application/json`
- **判定**: PASS

**TC-HA-05: PUT 动词 — PASS**
- **操作**: `{action:"http_put", url:"/api/x"}`
- **验证**: httpx 调用 method=PUT，响应回填 step_result
- **判定**: PASS

**TC-HA-06: DELETE 动词 — PASS**
- **操作**: `{action:"http_delete", url:"/api/x"}`
- **验证**: method=DELETE，幂等语义保持
- **判定**: PASS

### 5.2 断言 Action Handler — 6/6 PASS

**TC-HA-07: assert_status 命中（真）— PASS**
- **操作**: 上一步 status=200，断言 `expected_code=200`
- **验证**: ok=true
- **判定**: PASS

**TC-HA-08: assert_status 失败（假）— PASS**
- **操作**: status=200，断言 `expected_code=404`
- **验证**: ok=false，error 显式给出 actual vs expected
- **判定**: PASS

**TC-HA-09: assert_json JSONPath 命中 — PASS**
- **操作**: response.body=`{"status":"ok"}`，断言 `path=$.status, expected=ok`
- **验证**: ok=true，jsonpath-ng 解析正确
- **判定**: PASS

**TC-HA-10: assert_json JSONPath 不存在 — PASS**
- **操作**: 断言 `$.missing` 字段
- **验证**: ok=false，error 提示路径未命中
- **判定**: PASS

**TC-HA-11: assert_header 命中 — PASS**
- **操作**: response.headers 含 `Content-Type: application/json`，断言相同键值
- **验证**: ok=true（大小写不敏感匹配）
- **判定**: PASS

**TC-HA-12: assert_header 不命中 — PASS**
- **操作**: 断言不存在的 header
- **验证**: ok=false，error 标识缺失键
- **判定**: PASS

### 5.3 变量系统 — 3/3 PASS

**TC-HA-13: set_variable 从响应提取 — PASS**
- **操作**: response.body=`{"token":"abc"}`，`set_variable from_response_path=$.token name=tk`
- **验证**: 上下文变量表 `tk=abc`，ok=true
- **判定**: PASS — 变量提取契约符合实现

**TC-HA-14: _resolve_variables 替换 URL 占位符 — PASS**
- **操作**: 上下文 `tk=abc`，下一步 url=`/api/{tk}`
- **验证**: 实际请求 URL 为 `/api/abc`
- **判定**: PASS

**TC-HA-15: 跨步骤变量传递 — PASS**
- **操作**: 步骤 1 set_variable → 步骤 2 在 url / json body / headers 中引用
- **验证**: 三处占位符均被正确替换，链路连贯
- **判定**: PASS

### 5.4 边界防护 — 3/3 PASS

**TC-HA-16: MAX_STEPS=500 上限保护 — PASS**
- **操作**: 提交 501 步任务
- **验证**: 引擎拒绝执行，返回结构化错误（业务态 passed=false），error 显式给出阈值
- **判定**: PASS — 静态上限生效，避免任务无限放大

**TC-HA-17: 首步失败即停 — PASS**
- **操作**: 第 1 步 http_get 抛 ConnectError，后续步骤不应执行
- **验证**: step_results 长度=1，余下步骤被跳过
- **判定**: PASS — fail-fast 策略一致

**TC-HA-18: 未知 action 拒绝 — PASS**
- **操作**: `action="http_patch_unknown"`
- **验证**: step_result.ok=false，error 提示未知 action
- **判定**: PASS — action 白名单门控

---

## 6 E2E 集成测试详细记录

> **测试方式**: Python 脚本经 httpx 直连运行中的真实 Python 服务（:8000）与 Backend 健康端点（:8080），无 mock / 无打桩。
> **DSL 字段以 Python 端实现为准**：`url`（非 `path`）、`expected_code`（非 `expected`）、`from_response_path`（非 `json_path`）。
> **结果汇总**: 8/8 PASS

**TC-E2E-01: http_get 基本请求 — PASS**
- **请求 DSL**:
  ```json
  {"base_url":"http://localhost:8000",
   "steps":[{"action":"http_get","url":"/api/health"}]}
  ```
- **响应**: `passed=true`，step_results[0].ok=true，duration=5ms
- **判定**: PASS — 单步基线链路畅通

**TC-E2E-02: assert_status 200 断言 — PASS**
- **请求 DSL**:
  ```json
  {"steps":[
    {"action":"http_get","url":"/api/health"},
    {"action":"assert_status","expected_code":200}
  ]}
  ```
- **响应**: 2 步均 ok=true，passed=true
- **判定**: PASS — status 断言契约符合实现

**TC-E2E-03: assert_json JSONPath 命中 — PASS**
- **请求 DSL**:
  ```json
  {"steps":[
    {"action":"http_get","url":"/api/health"},
    {"action":"assert_json","path":"$.status","expected":"ok"}
  ]}
  ```
- **响应**: JSONPath `$.status` 命中 `ok`，passed=true
- **判定**: PASS

**TC-E2E-04: set_variable 跨步骤传递 — PASS**
- **请求 DSL**:
  ```json
  {"steps":[
    {"action":"http_get","url":"/api/health"},
    {"action":"set_variable","name":"svc","from_response_path":"$.service"},
    {"action":"assert_json","path":"$.service","expected":"{svc}"}
  ]}
  ```
- **响应**: 3 步均 ok=true，变量 `svc` 提取并被占位符替换
- **判定**: PASS — 变量系统端到端可用

**TC-E2E-05: 断言失败场景 — PASS**
- **请求 DSL**: 实际 status=200，断言 `expected_code=404`
- **响应**: passed=false，error 字段说明 `actual=200, expected=404`
- **判定**: PASS — 失败态结构化输出符合预期

**TC-E2E-06: 首步失败即停 — PASS**
- **请求 DSL**: `base_url=http://localhost:9999`（不可达）+ 后续 2 步
- **响应**: step_results 长度=1，passed=false，第 1 步 error 含 ConnectError；后两步未执行
- **判定**: PASS — fail-fast 行为一致

**TC-E2E-07: 跨服务调用（Backend 健康端点）— PASS**
- **请求 DSL**: `base_url=http://localhost:8080`，步骤 `http_get /api/health`
- **响应**: passed=true，duration=17ms，body 包含 `status=UP`
- **判定**: PASS — 同一执行器可跨进程访问 Java 后端，证明 RV-1 可在多服务拓扑下复用

**TC-E2E-08: MAX_STEPS 边界（501 步）— PASS**
- **请求 DSL**: 提交 501 个 http_get 步骤
- **响应**: HTTP 200 + `passed=false` + error 显式说明上限被触发
- **判定**: PASS — 业务态错误（非 4xx），契合"工具结果即业务结果"的设计

---

## 7 Feature Flag 与能力域验证

### 7.1 Feature Flag — RUNTIME_VERIFICATION=true

| 配置项 | 值 | 来源 | 验证方式 |
|--------|----|------|---------|
| RUNTIME_VERIFICATION | true | 启动配置 / 环境变量 | 启动日志 `46 tools` 含 VerifyJourney |

- **验证**: Backend 启动日志输出 `46 tools registered`，工具池中可见 `VerifyJourney` 注册项；TC-VT-01 在关闭态下走拒绝分支，开启态下进入正常分发链路。
- **判定**: PASS — Feature Flag 与工具注册联动正确

### 7.2 能力域可用性矩阵

| 能力域 | 状态 | RV-1 关联用例 |
|--------|------|--------------|
| HTTP_API | available | TC-HA-01~05、TC-VT-03、所有 E2E |
| BROWSER_AUTOMATION | available | TC-BV-01~05、TC-VT-02 |
| CODE_INTEL | available | 间接（工具池） |
| GIT_ENHANCED | available | 间接 |
| FILE_PROCESSING | available | 间接 |
| CODE_QUALITY | available | 间接 |
| ANALYSIS | available | 间接 |

- **请求**: `GET http://localhost:8000/api/health/capabilities`
- **响应**: 7/7 available，与 RV-1 双域验证（HTTP_API + BROWSER_AUTOMATION）所需依赖完全匹配
- **判定**: PASS

---

## 8 发现与建议

> 测试期间未出现阻塞缺陷；以下为实现-计划之间的 3 项契约差异，已按真实实现对齐用例，并建议同步到设计文档以避免后续误读。

### 8.1 DSL 字段名差异（实现 vs 初始测试计划）

| 维度 | 初始计划 | 实际实现 | 处置 |
|------|---------|---------|------|
| HTTP 路径字段 | `path` | `url` | 用例已统一为 `url` |
| 状态码断言 | `expected` | `expected_code` | 用例已统一 |
| 变量提取来源 | `json_path` | `from_response_path` | 用例已统一 |

- **建议**: 在 RV-1 设计文档与对外 schema 中明确 `url` / `expected_code` / `from_response_path` 三字段为正式契约，避免外部调用方按旧名提交导致 400。

### 8.2 能力域端点路径差异

- **现状**: 实际能力探测端点为 `/api/health/capabilities`，与早期文档中的 `/api/capabilities` 不一致。
- **建议**: 在 README 与 OpenAPI 描述中统一为 `/api/health/capabilities`，并保留旧路径 410 / 308 提示（如需兼容）。

### 8.3 MAX_STEPS 错误返回方式

- **现状**: 提交 501 步时返回 HTTP 200 + `passed=false` + 错误说明，而非 HTTP 4xx。
- **评估**: 该返回方式与 RV-1 "工具结果即业务结果" 的统一契约一致，下游解析无需区分传输层 vs 业务层错误，**判定为合理设计**。
- **建议**: 在公开文档中显式注明此约定，避免调用方误把"业务态失败"当作传输异常处理。

---

## 9 总结与判定

### 9.1 量化结论

- **总用例**: 55（Java 29 + Python 18 + E2E 8）
- **PASS**: 55
- **FAIL / ERROR / SKIP**: 0 / 0 / 0
- **通过率**: 100%
- **总执行时间**: Java 3.420s + Python 0.15s + E2E（实测 < 1s）≈ 5s 内完成全量验证

### 9.2 质量评价

1. **架构闭环**: VerifierFactory（多态分发）→ HttpApiVerifier / BrowserVerifier（双域执行）→ EvidenceStore（持久化 + SHA-256）→ STOMP 推送（实时回传）四个关键环节单元测试全部通过，证明 RV-1 双域验证框架核心链路稳健。
2. **边界防护**: MAX_STEPS 上限、首步失败即停、未知 action 拒绝、Feature Flag 关闭、能力域不可用拒绝、null/空字符串/非法 mode 守卫六类失败路径均显式覆盖，未现 NPE 或栈穿透。
3. **真实可信**: E2E 全部直连运行中真实服务（Backend :8080 + Python :8000），无 mock / 无 fixture，包括跨服务调用、JSONPath 提取、变量跨步骤传递、断言失败、首步失败、501 上限六类典型业务场景。
4. **门控正确**: Feature Flag `RUNTIME_VERIFICATION=true` 时 VerifyJourney Tool 注册（46 tools），关闭时走拒绝分支；7/7 能力域 available，验证器按可用性自动降级。

### 9.3 总体判定

**PASS** — RV-1 Runtime Verification 模块以 100% 通过率完成 55 项核心用例验证（Java 单元 29 + Python 单元 18 + E2E 集成 8），实现-契约差异已识别并对齐，无阻塞缺陷，具备进入下一阶段（前端联调与运行时灰度）的质量基线。

---

> **附录**：
> - Java Surefire 输出：`backend/target/surefire-reports/`
> - Python pytest 输出：`python-service/.pytest_cache/`
> - 报告生成时间：2026-06-05
> - 报告范围：本次提交对应 RV-1 模块代码与运行时实例
