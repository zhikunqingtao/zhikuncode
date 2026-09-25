# -*- coding: utf-8 -*-
import json, re, hashlib
from pathlib import Path

BASE=Path('/Users/guoqingtao/Desktop/AICoding评测0925/评审结果_20260925_v1')
REPO=Path('/Users/guoqingtao/Desktop/dev/code/zhikuncode')
reports={}
for n in range(1,8):
    eid=f'E{n:02d}'; p=BASE/'inputs'/f'{eid}.md'
    reports[eid]={'report_id':eid,'path':str(p),'sha256':hashlib.sha256(p.read_bytes()).hexdigest(),'lines':p.read_text().splitlines(),'claims':[]}

def add(eid, cid, start, end, title, sev='未分级', kind='finding', audit=None):
    r=reports[eid]; ls=r['lines'][start-1:end]; excerpt='\n'.join(ls)
    nonempty=[x.strip() for x in ls if x.strip() and not x.startswith('```')]
    quote=next((x for x in nonempty if not x.startswith('#')), nonempty[0])
    positions=list(dict.fromkeys(re.findall(r'`([^`\n]*(?:\.java|\.py|\.yml|\.md|\.html)[^`\n]*)`',excerpt)+re.findall(r'\]\((file://[^)]+)\)',excerpt)))
    r['claims'].append({'claim_id':f'{eid}:{cid}','original_id':cid,'kind':kind,'title':title,'original_severity':sev,
        'report_citation':f'{eid}.md:L{start}-L{end}','report_line_start':start,'report_line_end':end,
        'short_original_quote':quote[:240],'full_original_excerpt':excerpt,'source_locations_as_reported':positions,
        'test_claim_in_excerpt':[x.strip() for x in ls if re.search(r'实测|探针|复现|测试|passed|failed|skipped|Tests run|通过|Test\b',x)],
        'evidence_visibility_ref':f'{eid}.artifacts','cross_report_disclosure_ref':f'{eid}.cross_report_disclosure',
        'audit_notes':audit or [],'audit_status':'未逐项判真伪；见 audit_notes 与报告级证据审计'} )

# Each row is a report-local claim. Repeated summary appearances are not counted as new findings.
data={
'E01':[
('R1',132,151,'启动占位窗口取消导致 Run 终态误判','中高'),
('R2',155,174,'前台清理硬失败、行为文档与 shell 快照分支','中'),
('R3',178,184,'保留许可且无后台回收导致容量耗尽','中'),
('R4',188,203,'浏览器容量硬拒绝与未完成条目占额','中低'),
('R5',207,217,'枚举异常阻断确认并跳过 Linux 组扫描','中低'),
('R6',221,223,'2 秒清理预算使 exit 0 变失败','中低'),
('R7',227,229,'每 10ms 全 /proc 扫描开销','低'),
('R8',233,237,'CHANGELOG/rv 文档/提交语言不同步','低'),
('R9',241,245,'真实浏览器测试未接 CI 及测试缺口','低'),
('R10',249,251,'废弃 verifier 旧 ID 且不关闭','低（信息）'),
('R11',255,259,'固定 sleep/极短阈值测试脆弱','低'),
('S1',342,342,'共享清理预算可跳 hook，但父提交已有','低（既有）'),
('S2',343,343,'非 Linux 确认假阳性为既有设计局限','信息（非回归）'),
('S3',344,344,'沙箱未确认清理时丢命令输出','中低（新暴露）'),
('S4',345,345,'PythonProcessManager FAILED 无自动恢复','低'),
('S5',346,348,'官方镜像满足 setsid/bash，部署风险排除','已排除','positive_assurance'),
('C1',122,122,'Java/Python 新字段双向兼容','未分级','positive_assurance'),
('C2',123,123,'FastAPI Request 注入实测正常','未分级','positive_assurance'),
('C3',124,124,'生产镜像满足新增依赖','未分级','positive_assurance'),
('C4',125,125,'journey 504/499 不触发客户端自动重试','未分级','positive_assurance'),
('C5',126,126,'API 模式跳失败快照修复幽灵 context','未分级','positive_assurance'),
('A1',267,267,'输出/退出码/截断/元数据兼容且 awaitDrain 纯优化','未分级','positive_assurance'),
('A2',268,268,'后台进程未受影响','未分级','positive_assurance'),
('A3',269,269,'沙箱失败清理可重试并共享一次执行','未分级','positive_assurance'),
('A4',270,271,'browser 并发资源隔离与 http_api 无回归','未分级','positive_assurance'),
('A5',272,273,'Python/DevServer 保留句柄安全、pid 文件无误杀消费方','未分级','positive_assurance'),
('A6',274,275,'前端无影响与版本双向兼容','未分级','positive_assurance'),
('Q1',282,282,'身份快照、握手解决 PID 复用与失控窗口','未分级','positive_assurance'),
('Q2',283,284,'取消安全清理与 deadline 预算设计可靠','未分级','positive_assurance'),
('Q3',285,286,'测试真并发/状态机与注释可维护性','未分级','positive_assurance'),
('Q4',291,291,'pending/reservation 可读性与 bound-method 隐式 key','未分级'),
('Q5',292,292,'cleanup CAS 语义正确但职责复杂','未分级'),
],
'E02':[
('P1-1',79,83,'exit 0 硬失败、shell 状态丢失与元数据不齐','P1'),
('P1-2',85,91,'完成期一次重试后仍未确认会耗尽许可','P1'),
('P2-1',93,96,'terminate/hook 共用预算可跳过 hook','P2（本次引入）'),
('P2-2',98,102,'沙箱分支丢输出，既有缺陷被放大','P2（非本次引入）'),
('P2-3',104,107,'Linux 新增 setsid/bash 依赖','P2'),
('P2-4',109,112,'持续 /proc 失败导致硬失败与许可保留','P2'),
('P2-5',114,117,'前台完成杀残留子进程需文档化','P2'),
('P2-6',119,122,'Python FAILED 无健康检查自动恢复','P2'),
('P3-1',127,127,'工具和 Run 失败码同名','P3'),
('P3-2',128,128,'废弃 verifier 保留旧命名','P3'),
('P3-3',129,129,'VerifyJourneyTool 未使用 import','P3'),
('P3-4',130,130,'DevServer pendingCleanup 仅停机重试','P3'),
('P3-5',131,131,'4 参构造器隐式生成随机 ID','P3'),
('P3-6',132,132,'非 Linux 20ms descendants 采样开销','P3'),
('P3-7',133,133,'10ms /proc 扫描无日志','P3'),
('P3-8',134,134,'绝对 deadline 依赖双端时钟','P3'),
('P3-9',135,135,'Python 总清理时限不足以保证 130 秒预算','P3'),
('P3-10',136,136,'runSync finally throw 维护性','P3'),
('P3-11',137,137,'WARN 无 Run/tool 标识','P3'),
('T1',152,152,'未覆盖无取消场景最终回收','未分级'),
('T2',153,153,'未覆盖慢 terminate 加慢 hook','未分级'),
('T3',154,154,'沙箱硬失败无断言','未分级'),
('T4',155,155,'非 Linux 真实路径测试薄','未分级'),
('T5',156,156,'真实 Chromium 容器 opt-in 非 CI 门禁','未分级'),
('T6',157,157,'反射/static mock 测试耦合强','未分级'),
('T7',158,158,'setsid/bash 缺失与 stdin 前提无测试','未分级'),
('Q1',169,169,'cleanup 语义正确但 CAS 可读性低','未分级'),
('A1',17,18,'跨端字段兼容与生产调用点无遗漏','未分级','positive_assurance'),
('A2',62,62,'后台命令无破坏','未分级','positive_assurance'),
('A3',66,68,'browser/http_api/journey 契约一致，close 日志加 TTL 兜底','未分级','positive_assurance'),
('A4',69,71,'ProcessTreeManager 签名语义与所有调用点同步','未分级','positive_assurance'),
('A5',164,164,'fail-safe 设计一致，新增 process 观测事件','未分级','positive_assurance'),
('A6',191,197,'可安全保留 main，但上线需 Linux 冒烟','未分级','positive_assurance'),
('A7',213,213,'明确未发现问题模块与测试核对范围','未分级','positive_assurance'),
],
'E03':[
('F1',93,105,'清理未确认锁死会话的探针与普遍化结论','P1'),
('F1-chain',144,157,'保留 lease→注册不移除→终态取消无恢复','P1'),
('F2',123,129,'受限枚举失去降级导致每条失败','P1（条件性）'),
('F3',206,207,'浏览器容量拒绝、owner 未释放永久占位','P2'),
('F4',166,174,'exit 0 硬失败、cwd/env 丢失、沙箱新增与失败码','P2'),
('F5',217,217,'时钟漂移提前超时或保护失效','P2'),
('F6',216,216,'断连 watcher 异常当成断连','P2'),
('F7',289,289,'废弃 verifier 及未用 import','P3'),
('F8-parse',131,131,'readIdentity 空截断解析脆弱','P3'),
('F8-perf',133,133,'每次 /proc 双读全扫性能','P3'),
('F8-deps',138,138,'精简镜像 setsid/bash 依赖','P3'),
('F9',136,136,'包装器注入 PWD/SHLVL 破坏严格清空环境','P3'),
('F10',137,137,'新 session 改变 tty/Ctrl+C/docker stop 信号传播','P3'),
('F11',161,164,'ProcessTreeManager/DevServer 清理预算缩短','P3'),
('D1',285,287,'CHANGELOG/部署配置/commit body 缺行为说明','P2'),
('S1',181,181,'PythonProcessManager FAILED 粘性失败','P3'),
('S2',182,183,'catch Error 注释与 synchronized 阻塞 12s','P3'),
('S3',189,189,'npm install 成功仍清理失败，浪费安装成本','未分级'),
('S4',208,208,'lambda close key 可读性','未分级'),
('S5',218,218,'499/504 被 Java 泛化成服务不可达','未分级'),
('S6',219,219,'journey mode 字段未使用是遗留','未分级'),
('S7',229,237,'Python 未确认自动 TTL 自愈，Java 不自愈','P2','positive_assurance'),
('T1',252,252,'Linux 路径本地跳过，容器探针补覆盖','P2'),
('T2',253,253,'真实浏览器两测试未接 CI','P2'),
('T3',254,254,'缺清理未确认后同会话新 Run 测试','P2'),
('T4',255,255,'本地 pytest-cov 缺失，覆盖率门未验证','P2'),
('T5',256,256,'缺 Java 504/499 归因测试','P2'),
('T6',258,274,'全量单一失败归因为既有预算 flake','P2（非本次引入）'),
('Q1',290,290,'单行 catch 风格与魔法数字','未分级'),
('Q2',291,291,'WARN 与保留容量缺可观测性','未分级'),
('A1',73,91,'生产容器命令/退出码/输入/子进程回收保真','未分级','positive_assurance'),
('A2',115,119,'身份校验、root first 与中断保存正确','未分级','positive_assurance'),
('A3',142,142,'预占防重复、CAS 单执行者、输出完成后仍保留','未分级','positive_assurance'),
('A4',178,178,'Python stop 所有权与 root 优雅退出有效','未分级','positive_assurance'),
('A5',186,188,'130 秒预算/不重试/资源 ID 隔离/strict 快照','未分级','positive_assurance'),
('A6',198,202,'迟到 context/取消安全/latch/TTL/strict 语义正确','未分级','positive_assurance'),
('A7',212,212,'journey 有界 join 与 FastAPI 注入实测正确','未分级','positive_assurance'),
('A8',223,223,'Optional 字段与 import 启动安全','未分级','positive_assurance'),
('A9',245,248,'新增测试设计质量与覆盖范围','未分级','positive_assurance'),
('A10',299,312,'常规链路全部无确定性破坏','未分级','positive_assurance'),
],
'E04':[
('S1',68,68,'exit 0 硬失败、shell 状态丢失、受限主机全失败','P1'),
('S2',69,69,'许可/lease 永久保留，Python 同理','P1'),
('S3',70,70,'浏览器容量由淘汰变拒绝','P1'),
('S4',71,71,'API 模式删除失败快照修复幽灵会话','P1','positive_assurance'),
('D1',75,75,'Linux setsid/bash 与 /proc 部署前提','P1/P2'),
('D2',76,76,'Java/Python 跨主机时钟同步','P2'),
('D3',77,77,'PowerShell 未同步 terminationConfirmed','P3'),
('Q1',96,96,'保留资源无界增长无指标','P1'),
('Q2',97,97,'new_context 挂起使同 ID waiter 无界等待','P2'),
('Q3',98,98,'Playwright 私有 API 升级风险','P2'),
('Q4',99,99,'stat 字段解析需短字段防御','P2'),
('Q5',100,100,'npm install 成功清理未确认仍整体失败','P2'),
('Q6',101,101,'gather set 解包无序但无实际影响','P3'),
('Q7',102,102,'close_session closed 字段未消费','P3'),
('Q8',103,103,'descendants 元数据额外开销','P3'),
('Q9',104,104,'测试反射耦合','P3'),
('Q10',105,105,'取消 shield 复杂度','P3'),
('T1',129,132,'50ms creation close 测试 flake','P2'),
('T2',124,125,'真实 Chromium 未跑与 token 性能失败','未分级'),
('A1',55,55,'前台命令/输出/超时测试全通过','未分级','positive_assurance'),
('A2',56,56,'后台命令未受影响','未分级','positive_assurance'),
('A3',57,57,'Python 托管启停健康检查语义兼容','未分级','positive_assurance'),
('A4',58,58,'DevServer 日志/pid/就绪语义不变','未分级','positive_assurance'),
('A5',59,59,'VerifyJourney 快照先于 close','未分级','positive_assurance'),
('A6',60,60,'浏览器 ad-hoc touch 与 JS 错误采集无回归','未分级','positive_assurance'),
('A7',61,62,'close envelope 与生产依赖满足','未分级','positive_assurance'),
('A8',85,90,'所有权对称、中断/错误/并发/PID/日志安全','未分级','positive_assurance'),
('A9',155,158,'可合并、无需回滚、两侧测试全绿','未分级','positive_assurance'),
],
'E05':[
('R1',46,95,'启动占位取消错误返回/Run 错判','高'),
('R2',97,116,'浏览器容量删除 LRU 的可用性回归','高'),
('R3',118,137,'仅 Linux 前台完成杀残留子进程','中高'),
('R4',139,161,'非 Linux 受限枚举与新非空测试断言','中'),
('R5',163,179,'失败 close 永久缓存不重试且占容量','中'),
('R6',181,194,'close_session 未确认由 bool 变异常契约','中'),
('R7',196,207,'资源未释放拒 startup','正文低；汇总中'),
('R8',309,309,'新增 Linux 依赖、安全检查未发现注入等','低'),
('R9',233,233,'OwnedProcess.destroy 仅根进程易误用','低'),
('R10',294,299,'真实浏览器 CI 缺口、Linux 跳过、测试脆弱','低'),
('R11',305,305,'CHANGELOG 未更新','低'),
('Q1',234,234,'cleanup CAS 循环可读性','未分级'),
('Q2',235,236,'browser 多状态容器复杂与双 close 标签逻辑','未分级'),
('Q3',237,237,'时间单位边界需常量/断言','未分级'),
('Q4',238,238,'known 只增不删','未分级'),
('Q5',306,306,'提交粒度过大难回滚二分','未分级'),
('Q6',307,307,'容量/清理/异常行为未登记','未分级'),
('T1',278,282,'Java 各失败 A/B 归因','未分级'),
('T2',294,294,'两真实浏览器测试未接 CI','未分级'),
('T3',295,295,'Linux 本地跳过无法发现启动窗口问题','未分级'),
('T4',296,296,'缺工具级残留进程与超限路由端到端','未分级'),
('T5',297,297,'Playwright 私有属性测试升级风险','未分级'),
('T6',298,299,'非空枚举断言与 100ms 取消时序','未分级'),
('A1',213,214,'资源 ID 隔离与 strict 快照不重建','未分级','positive_assurance'),
('A2',215,215,'deadline/disconnect 有界返回并清理','未分级','positive_assurance'),
('A3',216,216,'Python 保留旧 processRef 防双启动','未分级','positive_assurance'),
('A4',224,228,'抽象/注释/取消安全/错误保真良好','未分级','positive_assurance'),
('A5',242,244,'主源码编译通过、类型规范、清理普遍有界','未分级','positive_assurance'),
('A6',288,290,'新增测试生命周期/身份/capacity 覆盖','未分级','positive_assurance'),
],
'E06':[
('H1',125,184,'任一未确认→无自动重试→永久会话不可用','P1'),
('H2',188,216,'受限枚举条件未确认加延迟，正文补死根可确认限制','P1（条件）'),
('H3',220,243,'前台正常返回杀残留子进程仍报告成功','P1'),
('H4',249,270,'DevServer 未确认必残留端口使后续失败','P2'),
('H5',274,285,'exit 0 硬失败、枚举元数据/截断不齐、shell 空实现','P2'),
('H6',289,300,'close 异常缓存永久占额、TTL 无法重试 RPC','P2'),
('H7',304,314,'journey 首次限容，异常未映射导致裸 500','P2'),
('H8',318,329,'Python stop void/12s root grace/重试必失败','P2'),
('H9',333,339,'测试 skip/CI opt-in/私有字段/flaky','P2'),
('H10',345,347,'499/504 缺日志和部分结果','P3'),
('H11',349,351,'创建中 close 取消 owner/waiter 逃逸成 500','P3'),
('H12',353,355,'废弃 verifier 旧 ID 与 120s','P3'),
('H13a',359,360,'CHANGELOG 与 docs 不同步','P3'),
('H13b',361,362,'中英注释与死代码/import','P3'),
('H13c',363,363,'unclosed_contexts label 双语义','P3'),
('H13d',364,364,'fallback rv ID 仅 32bit','P3'),
('H13e',365,365,'整体 600s 预算余量收窄','P3'),
('H13f',366,366,'/proc 全扫描与 known 集合开销','P3'),
('H13g',367,367,'动态 callable key 可读性','P3'),
('H13h',368,368,'Request=None mypy 注解建议','P3'),
('H14',370,372,'driver stop latch 后 browser 能力不恢复且 health 不暴露','P3'),
('T1',508,508,'缺启动前提各失败分支','未分级'),
('T2',509,509,'缺 terminate=false cleanup=true 保留断言','未分级'),
('T3',510,510,'PID reuse 仅 mock','未分级'),
('T4',511,511,'sandbox 未确认无测试','未分级'),
('T5',512,512,'DevServer 残留端口后续失败无测试','未分级'),
('T6',513,513,'真实 disconnect 轮询无测试','未分级'),
('T7',514,514,'容量拒绝路由响应无测试','未分级'),
('T8',515,515,'关闭失败长期影响测试固化而未讨论','未分级'),
('T9',516,516,'Python catch Error/drain/grace 空等未覆盖','未分级'),
('T10',517,517,'资源收敛容器测试仅手动','未分级'),
('A19',426,426,'HTTP API 跳失败快照行为与父提交一致','未分级','positive_assurance'),
('A20',531,531,'生产不会触发 PROCESS_GROUP_UNAVAILABLE','未分级','positive_assurance'),
('A21',546,549,'提交四项承诺部分达成','未分级','positive_assurance'),
],
'E07':[
('P1-1',45,69,'容量 LRU 删除且 close 占额不可恢复','P1'),
('P1-2',73,101,'非 Linux 终止确认假阳性与泄漏未闭合','P1'),
('P2-1',105,114,'清理未确认长期许可/lease 保留无自动重试','P2'),
('P2-2',118,131,'Linux setsid/bash/proc 新部署前提','P2'),
('P2-3',135,148,'非 Linux CPU 量化与 Linux /proc 开销','P2'),
('P2-4',152,165,'前台正常完成清理残留行为变化','P2'),
('P2-5',169,175,'跳 shell 快照导致旧 cwd/env 与失败分类歧义','P2'),
('P2-6a',181,189,'真实 Chromium 两测试 CI 未执行','P2'),
('P2-6b',191,200,'Python 50ms 测试负载 flake','P2'),
('P2-6c',202,204,'Java 取消等失败归为负载 flake','P2'),
('P2-7',208,212,'CHANGELOG 缺失与覆盖率 workflow 需确认','P2'),
('S1',237,237,'未改的 ConcurrencyControlTest 为既有无因果','未分级'),
('S2',230,230,'close_session 异常契约变严，路由处理但其他调用注意','未分级'),
('A1',19,19,'编译和公共调用签名未破坏','未分级','positive_assurance'),
('A2',26,26,'PID/握手/取消/ID 隔离方向正确；沙箱分支父提交已有','未分级','positive_assurance'),
('A3',222,227,'后台、沙箱、Python/DevServer 与 HTTP 模式影响断言','未分级','positive_assurance'),
('A4',248,253,'macOS 普通命令/杀运行根/优雅 root 可确认','未分级','positive_assurance'),
]}
for eid, rows in data.items():
    for row in rows:add(eid,*row)
add('E04','A10',45,46,'Browser shutdown 全量有界清理，journey 取消后 join 有界清理','未分级','positive_assurance')
for i,line in enumerate(range(380,398),1):
    add('E06',f'A{i}',line,line,'已确认无问题清单 '+str(i),'未分级','positive_assurance')

def note(eid,cid,text):
    next(c for c in reports[eid]['claims'] if c['original_id']==cid)['audit_notes'].append(text)

# Cross-report disagreements resolved only by static source/probe inspection; no tests run in this audit.
shell='静态反证：ShellStateManager.java:94-100 的包装脚本已原子写入 CWD；104-106 的 updateStateFromSnapshot 仅 debug，并注明环境不跨调用保存。跳过该方法为真，推断 cwd/env 丢失不成立。'
for eid,cid in [('E01','R2'),('E02','P1-1'),('E03','F4'),('E04','S1'),('E07','P2-5')]:note(eid,cid,shell)
retry='遗漏完成期重试：RunTracker.java:74-80 在 awaitQuiescence 失败后会调用 termination.terminate；RunTerminationCoordinator.java:60 会再次 cancelRunDetailed。需限定为该重试仍持续失败。RunExecutionRegistry.java:620-629 的晚到 lease release 在 unregisterRequested 后会移除 execution，故单次 unregister 超时不等于永久不可逆。'
for eid,cid in [('E01','R3'),('E03','F1-chain'),('E04','S2'),('E06','H1'),('E07','P2-1')]:note(eid,cid,retry)
note('E02','P1-2','报告已在 L88-90 与 L210 自我修正完成期重试；有重试仍持续失败时保留许可的风险与断言可区分。')
note('E03','F1','探针源码 /tmp/zc_repro/Repro.java 实际可读：L15 hook 恒 false，L19 直接 unregister，L31 直接 runner.cancelRunDetailed；未实例化 RunTracker/RunTerminationCoordinator，不是完整 query/终态取消路径。可证明持续 hook 失败时底层注册被阻塞，不能证明任何单次清理未确认都永久锁死。')
note('E03','F1-chain','L149 把探针 [4]/[5] 归因于 RunControlService 的 ALREADY_TERMINAL 提前返回，与可见源码直接 runner.cancelRunDetailed 的路径不符。')
note('E01','R5','/tmp/probe/Probe.java:33-36 的 destroy/destroyForcibly 不改变 alive；84-86 的 live root 永远 alive。该模型同时包含不可杀根与枚举异常，不能隔离证明“枚举异常本身使已杀死根永不确认”。OwnedProcess.java:206 对死亡 parent 早退；E06 L213 明确限定了这一点。')
for eid,cid in [('E02','P2-4'),('E03','F2'),('E04','S1'),('E05','R4')]:note(eid,cid,'需分开“枚举抛异常”“静默返回空”“根/已知成员已退出”“持续 /proc 扫描异常”。OwnedProcess.java:206 的死亡 parent 早退意味着单纯 descendants 异常未必持续到清理结束；所有命令/永远未确认需要额外持续失败条件。')
for eid,cid in [('E01','R7'),('E03','F8-perf')]:note(eid,cid,'读数夸大：OwnedProcess.java:164 首读每 PID；165-166 过滤非本组后，168 仅匹配组成员二次读。不是每 PID 均两次 stat。10ms 是 sleep 下界，实际每轮还含扫描耗时，不能直接当每秒 100 次固定频率。')
note('E02','P2-1','需细分新旧：父提交 ManagedProcessRunner.java:145-147 已共享2秒deadline，但旧cleanup首次CAS成功会直接调用hook（360-364），只在等待已有attempt时检查remaining；HEAD:380新增首次hook调用前deadline门控。所以“共享预算本次引入”不准，但“首次hook被预算直接跳过”确为新变化。实际DockerRuntimeService.ensureRemoved:25-28在父提交已自行因remaining<=0返回false，Docker实际清理饥饿仍是既有；正常完成新增terminate可扩大触发条件。应部分支持，不能整体驳倒。')
note('E03','F4','L172 称沙箱分支“同样新增”与父提交 BashTool 既有分支不符；E02 L100/L209、E07 L26/L223 已说明既有。')
note('E03','S7','静态反证：browser_service.py:335-340 仅成功移除 close task；失败 task 被缓存，343-356 后续复用同一异常结果，不发新 RPC。TTL 会循环访问不等于关闭失败可自愈。E05 R5/E06 H6 的异常型与超时型区分更精确。')
note('E02','A3','“TTL 兜底”应限定超时 task 后续成功/可过期正常资源；已完成异常 close task 在缓存中不会发新 RPC，不能笼统保证关闭失败自动恢复。')
note('E04','Q1','Java active 受 16 许可约束，Python session/creating/context 受 max_sessions 约束；“无界/无限增长”需分别证明 pendingCleanup/resource_close_tasks 的累积路径，不能与有容量上限的计数直接等同。')
note('E05','R3','把行为限定“仅 Linux 生效/macOS 不会触发”过强：非 Linux waitFor 仍采样记住后代，对已捕获的存活子进程也会 terminate；单机静默空枚举的一个示例不能代表全部 macOS。')
note('E05','R4','报告摘要 L23-25 将 ProcessTreeManagerTest 归到“父提交同样失败”，正文 L155 与表 L282 却说父提交通过、为新断言；内部矛盾。')
note('E05','R7','正文 L196 标低风险，汇总 L323 标中风险，严重度不一致。')
note('E05','T3','L295 称 2.1 的启动窗口问题恰为 Linux 专属路径暴露，但 L80-81 已明确在 macOS 实测；平台覆盖论证自相矛盾。')
note('E06','H1','H3 的正常清理成功仅是行为变化，本身不造成 retained lease；“任一未确认一次即永久不可用”的链条缺 retry 持续失败前提。')
note('E06','H4','未确认不等于端口必仍被占：可因 inspection 不可用、非监听子进程等导致未确认。保留句柄与缺定期 retry 可静态证，后续端口持续失败须附端口实际仍被占的条件。')
note('E06','H6','失败 close 未确认与真实 context 必残留不能等同；RPC 异常也可能发生在服务端已关闭之后。每次“泄漏一个真实 Chromium context”因果过强，容量账目保留则静态成立。正常已登记 session 失败也可留在 _sessions，与 _unclosed_contexts 的回滚场景需区分。')
note('E06','H8','PythonProcessManager.java:131-136 每次 start 会重新 stopProcess，197-199 的 restart 先 stop 再检查。进程后来退出即可成功，不支持“每次必然同样失败”。只有持续失败条件下重复无新手段；root-first 空等是另一可独立核查项。')
note('E06','H10','路由并非没有任何 logger：journey.py:20 创建 logger、82 记录清理异常。准确表述应是 499/504 分支无日志（37/48/49）。')
note('E06','A19','静态反证：父提交 VerifyJourneyTool.java:415-416 仍把业务 sessionId 传到 handleVerificationResult，失败快照没有新 browserResourceId=null 跳过条件。此项与 E01/E02/E04/E07 对 API 模式修复的正确说明相冲突。')
note('E06','A20','Docker 仅证明 setsid/bash 存在，不能排除 PROCESS_GROUP_UNAVAILABLE 的其它分支（stdin、身份等待/proc）；“生产不会触发”超出实测范围。')
note('E07','P1-2','现版本 OwnedProcess.java 共257行，报告位置锚 L603-614/L720-770 不存在；机制描述可定位到35-44、150-208。非 Linux 漏捕获局限可成立，但报告未用父提交 A/B 证明新引入，宜区分未完全修复和回归。')
note('E07','P2-3','同样引用不存在的 L720-770/L806-820。L146 将 cleanupMs=0-4ms 接在“Linux侧…实测”之后，但附录 L248 标明 os=Mac OS X，且 L276 声明未运行 Linux；该耗时不能作为 Linux 实证。')
note('E07','S1','文件未在 diff 中并不足以证明失败与提交无因果；本项没有报告在父提交执行同用例，只是 HEAD 单独复跑。应为既有问题线索，而非已完成 A/B 因果排除。')
note('E07','P1-1','一次 close 失败不总等于需重启整个 Python 进程：browser/driver 祖先确认关闭会解除子资源归属；必须持续祖先 shutdown 也失败才达到整个进程不可恢复。')
note('E04','A9','L155“两侧测试全绿”与 L121 明确排除 token_estimation、L125 失败一次及 L122 首轮 flake 的统计口径不同；应说明是筛选/重跑后而非无条件全绿。')
note('E03','A1','L91 “环境保真”需要与同段 L88/F9 的 PWD/SHLVL 新增并列限定；少数常规命令 37-294ms 不证明所有常规命令不会误报。')

meta={
'E01':{'identity':'标题可见 DeepSeekHarness','method_citation':'E01.md:L1-L5,L47-L51,L66-L127','cross':('承认先独立后交叉阅读','E01.md:L336-L352','明确对同目录其他工具报告线索逐条复核后追加 S1-S5，不能称整份报告彼此隔离或双盲。'),'tests':[(70,118),(315,334)],'artifacts':[('/tmp/probe/Probe.java','probe_source',331),('/tmp/probe3/LaunchWindowCancelProbe.java','probe_source',332),('/tmp/probe3/CancelTimelineProbe.java','probe_source',117),('/tmp/zc-verify/target/surefire-reports/com.aicodeassistant.tool.process.ManagedProcessRunnerTest.txt','derived_test_report',51),('/tmp/zc-parent/target/surefire-reports/com.aicodeassistant.tool.process.ManagedProcessRunnerTest.txt','derived_test_report',51)],'issues':['R1窗口Linux典型10-30ms（L144）无报告内Linux实测；macOS单例58ms不是通用概率估计。','L257把父提交同样失败当作负载敏感证明，但L94说明的是ps权限问题，两者不能互换。','L352称所有已证实均附可复现命令或原始输出；探针源现可见，但附录cp含<slf4j>占位符，重跑还需解析环境。']},
'E02':{'identity':'标题无审查器姓名','method_citation':'E02.md:L6-L8,L203-L205','cross':('未声明读过其他报告','E02.md:L208-L210','披露自我复核修正，不等同于披露交叉读取。仅文本不能证明独立执行过程。'),'tests':[(203,205),(143,158),(191,197)],'artifacts':[],'issues':['明确只读静态、未执行构建测试（L205）；L191“已用…用例核验”应读为核对断言，不是运行通过证据。','L16/191“成功路径行为不变”需限定常规无残留清理；L24/114-117已承认正常exit后杀子进程。']},
'E03':{'identity':'L5 可见 ZCode','method_citation':'E03.md:L11-L28','cross':('明确声明未阅读指定其他工具报告','E03.md:L13','声明未阅读QoderCN/QoderIDE/pi报告；不自动覆盖所有7份、也不是可验证双盲。'),'tests':[(19,28),(63,103),(258,279),(342,364)],'artifacts':[('/tmp/zc_linux/Probe.java','probe_source',353),('/tmp/zc_linux/Probe2.java','probe_source',353),('/tmp/zc_linux/Probe3.java','probe_source',353),('/tmp/zc_repro/Repro.java','probe_source',358),('/tmp/zc_java_full.log','test_log',364),('/tmp/zc_java_tests.log','test_log',364),('/tmp/zc_py_tests.log','test_log',364),('/tmp/zc_cls_results.txt','repeat_log',364)],'issues':['L21定向总87（含6跳过），L278又称88 passed/6 skipped；内部数量矛盾。','L15声称未对仓库任何写操作，但L344/347在backend运行Maven默认写target，L351 pytest默认也可写缓存；“未改源码”与“未写任何文件”应区分。未证明报告运行真的改了受保护源码。','可见zc_java_full.log摘要支持3039/1/76；zc_py_tests.log支持229 passed/1 skip；zc_cls_results.txt支持6次exit=0，但文件存在不建立全部因果链或身份归属。']},
'E04':{'identity':'L5 可见 QoderCN','method_citation':'E04.md:L4,L117-L132','cross':('无交叉阅读披露','E04.md:L4','只声称独立重新审查；未说明读没读其他报告。'),'tests':[(117,132)],'artifacts':[],'issues':['未给测试日志或探针源码路径；只有报告内摘要和复跑次数，不能仅凭缺路径称捏造。','L16/155“两侧全通过”省略L121的筛除范围、L122/125失败与重跑条件。','“无命令内容泄露”（L90）是广泛正向保证，报告未附逐日志字段审计记录。']},
'E05':{'identity':'标题无审查器姓名','method_citation':'E05.md:L7-L10,L78-L89,L250-L284','cross':('无交叉阅读披露','E05.md:L7,L366','声称独立；未说明是否看其他报告。'),'tests':[(20,25),(78,89),(252,284),(345,364)],'artifacts':[('/tmp/zk-parent','parent_checkout',7)],'issues':['仅命名父提交副本/tmp/zk-parent，当前该路径不存在；启动窗口探针没有源文件具体路径、没有原始测试日志路径。此为留存不足，不是捏造结论。','L366称所有构建产物/tmp，L265/L356-357却在仓库backend默认mvn；未说明target重定向。','ProcessTreeManagerTest父提交结果在L23-25与L155/L282冲突。','Linux“命中概率不低/更长”（L89）未有Linux动态测量；单机窗口不能量化生产概率。']},
'E06':{'identity':'L8 可见三路并行工作方式','method_citation':'E06.md:L8-L9,L104-L118,L580-L603','cross':('披露内部子审查交叉复核；未披露读其他工具报告','E06.md:L112,L132,L300','协调者复核worker结论是团队内部方法，不能推断读取其它评审报告。'),'tests':[(483,517),(525,538),(582,587),(593,603)],'artifacts':[('backend/.zhikun/scratchpad/824e9ba4-7e97-4ead-9c30-7db8e486c098/java_review_findings.md','supporting_review',594),('backend/.zhikun/scratchpad/824e9ba4-7e97-4ead-9c30-7db8e486c098/python_review_findings.md','supporting_review',595),('backend/.zhikun/scratchpad/824e9ba4-7e97-4ead-9c30-7db8e486c098/test_execution_evidence.md','test_evidence',596),('/tmp/zhikun_parent','parent_checkout',602)],'issues':['L35称3 P1+6 P2+5 P3，而主表/正文H4-H9共有6 P2，H10-H14为5 P3，与主编号一致；H13含大量不同小项，本提取拆分保留。','L68称12个测试类/文件又括号Java8+Python3=11；L101/L479列第4个Python修改文件可解释为表述漏列。','动态环境/软件版本与8m58s仅是报告宣称；有证据文件可读，不等于全部时间、并行成本、模型参数可审计。','声明未改源码/配置/测试且允许gitignored构建产物（L9/L603）较“未写任何文件”精确。']},
'E07':{'identity':'标题无审查器姓名','method_citation':'E07.md:L7-L8,L271-L279','cross':('无交叉阅读披露','E07.md:L7,L273','声明独立复审，未说明是否阅读其他报告；不据此推断抄袭或双盲。'),'tests':[(57,62),(86,95),(139,146),(193,204),(234,253)],'artifacts':[('/tmp/oprobe/Probe.java','probe_source',245),('/tmp/oprobe/Probe2.java','probe_source',245),('/tmp/oprobe/Probe3.java','probe_source',245),('/tmp/oprobe/OwnedProcess.java','copied_source',245)],'issues':['L15宣称6项P2，正文P2-1到P2-7实际7项。','L9统计9 Java源+4 Python源+11测试；其它报告及提交地图为9 Java源+3 Python源+12测试（Python publication测试应计测试）。','L137/L75多处OwnedProcess源文件锚超过257行（可能错用拼接输出行号），影响可定位性。','L193/243的一次新用例失败与L240摘要只1个token失败可能来自不同轮次，但缺完整轮次日志映射，不能合并成一次运行。','L279“未修改任何文件”与仓库中mvn/pytest命令默认写构建缓存的语义需区分；git clean不证明无ignored写入。']},
}

for eid, info in meta.items():
    r=reports[eid]; r['visible_identity']=info['identity']; r['method_citation']=info['method_citation']
    r['cross_report_disclosure']={'status':info['cross'][0],'citation':info['cross'][1],'note':info['cross'][2]}
    r['testing_claims']=[{'citation':f'{eid}.md:L{a}-L{b}','original_excerpt':'\n'.join(r['lines'][a-1:b])} for a,b in info['tests']]
    r['artifacts']=[]
    for name,kind,line in info['artifacts']:
        p=Path(name) if name.startswith('/') else REPO/name
        r['artifacts'].append({'path_as_reported':name,'resolved_path':str(p),'kind':kind,'report_citation':f'{eid}.md:L{line}',
           'currently_exists':p.exists(),'readable_file':p.is_file(),'size_bytes':p.stat().st_size if p.is_file() else None,
           'visibility_interpretation':'当前可见；未通过运行重现认证来源' if p.exists() else '当前未找到；不推断当时未执行或捏造'})
    r['report_level_audit']=info['issues']
    r['runtime_cost_config_audit']={'status':'不可据这7份报告统一核算',
        'detail':'可见的测试耗时/软件版本/并行方法仅能按原文记录；无统一端到端开始结束时间、token/费用账单、模型精确版本/采样/推理强度/上下文与重试配置。测试运行耗时不等于审查总耗时。未补猜各工具配置。'}
    del r['lines']

out={'schema_version':'1.0','scope':'只读审计 E01-E07；静态核查报告直接提及的源码与证据文件可见性；未运行任何测试、未改被审仓库。',
 'independence_notice':'报告标题与自报名称可见，未做双盲。相同结论不证明非独立；明确披露交叉阅读的E01单独记录。',
 'non_fabrication_notice':'证据路径缺失/日志未留存只能降低可复核性，不等于捏造。现存文件亦不能单独认证全部运行背景、因果结论或审查成本。',
 'claim_count':sum(len(x['claims']) for x in reports.values()),'reports':list(reports.values())}
(BASE/'evidence/claims/claims.json').write_text(json.dumps(out,ensure_ascii=False,indent=2)+'\n')

md=['# 七份报告逐项主张与证据审计','',
'本审计不做最终排名。已逐份读完 E01–E07 全文；结构化提取见 `claims.json`，包含原始严重度、报告自身行号、简短原话、完整对应段落、声明的源码位置、测试宣称、证据可见性和审计注记。摘要中的重复条目不重复计数；包含有实质含义的正向保证与补充项。',
'','报告标题/自报名称可见，不能称双盲。没有运行测试，没有修改被审仓库。只对争议点做静态核查，并检查报告直接提及的日志和探针路径是否可见。缺日志不是捏造证据；文件存在也不等于所有测试或因果结论已认证。',
'','## 最关键的可核查争议','',
'1. **shell 状态丢失的错误因果。** E01 L169、E02 L81、E03 L171、E04 L68、E07 L173 都把跳过 `updateStateFromSnapshot` 推成 CWD/env 未更新。源码 `ShellStateManager.java:94-106` 显示 CWD 已由 shell 脚本原子写入，该 Java 方法仅打 debug；环境按产品约定不跨调用保存。E06 L283 明确指出这个反证。',
'2. **“没有完成期重试”遗漏真实调用链。** E01 L182、E03 L144-157、E06 L170-178、E07 L109 的强断言遗漏 `RunTracker.java:74-80 → RunTerminationCoordinator.java:60` 的一次完成期取消重试。E02 L88-90、L210 已自行修正。持续失败仍可能占许可/会话，但一次未确认并不等于必然永久锁死；`RunExecutionRegistry.java:620-629` 允许晚到 lease release 移除已请求注销的 execution。',
'3. **E03 F1 探针没有跑报告所称的上层路径。** E03 L95-105 的探针可见；`/tmp/zc_repro/Repro.java:15` 把 hook 固定为 false，L19 直接 unregister，L31 直接 runner.cancelRunDetailed。源码没有 RunTracker/RunTerminationCoordinator/终态数据库。因此 E03 L149 将 [4]/[5] 归因为 ALREADY_TERMINAL 提前返回不受该探针支持。该探针支持的是持续 hook 失败下底层注册阻塞。',
'4. **Python 异常型 close 不是 TTL 自动重发。** E03 L233-237 正向保证“周期性重试、有界可自愈”过强。`browser_service.py:335-356` 缓存失败 task，TTL 后续复用同一异常，不发新 RPC。E05 L165-177、E06 L294-300 识别了此区别；但 E06 L298 又把未确认直接称真实 context 必残留，仍需缩小到“容量归属被保留”。',
'5. **受限枚举探针的混杂条件。** E01 L116/L211 的探针源码可见，但 `/tmp/probe/Probe.java:33-36,84-86` 让 root 永久 alive 且 destroy 不改状态。它不能隔离证明单纯枚举失败使已死亡根也永不确认。`OwnedProcess.java:206` 对已死 parent 早退，E06 L213 已主动补充这一限制。',
'6. **父提交归属。** E02 L93-96 将共享 terminate/hook budget 叫“本次引入”，但父提交 `ManagedProcessRunner.java:145-147` 已共享；本次扩大到正常完成路径。E03 L172 把沙箱硬失败分支称新增也不对；E02 L100/L209 与 E07 L26/L223 已辨明其父提交既有。',
'7. **定位和平台证据。** E07 L75/L137 的 OwnedProcess 锚点 L603、L720、L806 均超出现文件257行。E07 L146 把 0–4ms 放在 Linux 实测语境，但 L248 是 Mac OS X，L276 又声明未运行 Linux。E01 L229/E03 L133 称每 PID 双读；源码164-168只有匹配本组 PID 才第二读。',
'8. **其它强因果应加条件。** E06 L268“未确认必端口占用”缺监听进程仍活条件；L325“startWithRetry 每次必失败”忽略下一次 stopProcess 可能确认；L531“生产不会触发 PROCESS_GROUP_UNAVAILABLE”超出仅验证 setsid/bash 存在的范围。E07 L237“未改文件所以无因果”不等于父提交A/B；E06 L426称 HTTP API 跳快照与父提交一致，与父提交调用链不符。',
'','## 逐报告证据与内部一致性','']
for r in reports.values():
    eid=r['report_id']; md += [f'### {eid}（{len(r["claims"])} 项）','',f'身份：{r["visible_identity"]}。方法：{r["method_citation"]}。',
      f'交叉阅读披露：**{r["cross_report_disclosure"]["status"]}**（{r["cross_report_disclosure"]["citation"]}）。{r["cross_report_disclosure"]["note"]}','']
    if r['artifacts']:
        md += ['| 证据路径 | 当前可见 | 报告引用 |','|---|---|---|']
        for a in r['artifacts']:md.append(f'| `{a["path_as_reported"]}` | {"是" if a["currently_exists"] else "未找到"} | {a["report_citation"]} |')
        md.append('')
    else:md+=['未提供具体可检查的探针/原始日志路径；报告内摘录和静态断言仍按各自证据等级保留。','']
    md += [f'- {s}' for s in r['report_level_audit']]+['']
    md += ['| 主张 | 原始级别 | 报告行号 | 审计提示 |','|---|---|---|---|']
    for c in r['claims']:
        audit='；'.join(c['audit_notes']) or ('实质正向保证；未在本审计重跑测试' if c['kind']=='positive_assurance' else '中性提取；未在本审计重跑测试')
        md.append(f'| {c["original_id"]}：{c["title"]} | {c["original_severity"]} | L{c["report_line_start"]}–L{c["report_line_end"]} | {audit.replace("|"," / ")} |')
    md.append('')
md += ['## 时间、成本与配置的可证边界','',
'七份报告均不提供足以统一比较的审查总运行时间、token 用量、费用账单或完整模型配置。报告中的单次 pytest/Maven 耗时、主机软件版本、并行方法只能原样记录，不可把它们当作总审查耗时或成本。E06 说明三路并行（L8），E03/E01/E05/E07 多处说明探针和隔离方法，但均不足以还原模型版本、reasoning、采样、上下文、工具调用重试配置。',
'','不同报告的失败数不应直接互相判假：套件筛选、跳过、负载、运行轮次和隔离副本不同。特别是“隔离通过”只能支持负载/时序敏感线索；未构造 A/B 条件时不充分证明失败根因。已在每份条目记录内部计数冲突与测试口径变化。',
'',f'结构化主张总数：{out["claim_count"]}。数量不是质量分数，不应以条目多寡排名。']
(BASE/'evidence/claims/claim_audit.md').write_text('\n'.join(md)+'\n')
print(json.dumps({'claim_count':out['claim_count'],'reports':{k:len(v['claims']) for k,v in reports.items()}},ensure_ascii=False))
