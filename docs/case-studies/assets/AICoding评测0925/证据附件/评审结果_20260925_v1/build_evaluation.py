from pathlib import Path
import json, datetime
R=Path(__file__).resolve().parent
M=json.loads((R/'manifest.json').read_text())
IDS=[f'E{i:02}' for i in range(1,9)]
# Discovery opportunities are deduplicated mechanisms, not counts of report paragraphs.
G=[
('G01',3,'启动预留窗口取消：最终已清理却沿用旧summary判FAILED',[1,0,0,0,1,0,0,0],'java J1；HEAD/parent coordinator确定性对照','E01 R1；E05 2.1；其余未识别。'),
('G02',4,'有限重试耗尽后无恢复驱动，容量/租约持续占用并可阻塞同会话',[.5,.5,1,.5,.5,1,.5,0],'java J2；retained可恢复hook探针','E03/E06追到同session新Run拒绝；E01/E02/E04/E05/E07发现容量/lease机制但未完成session注册影响链。误写完全无重试另计D2。'),
('G03',3,'异常型context.close缓存失败，TTL不重发RPC，需祖先确认释放',[.5,0,0,.5,1,1,.5,0],'python P4；probe_contracts','E05/E06区分失败latch与超时；E01/E04/E07只有容量保留后果；E03反称自动自愈；E02/E08未指出该恢复缺口。'),
('G04',2,'前台正常完成也清理已归属残留，既有cmd &用法变更',[1,1,1,1,1,1,1,1],'root/LinuxProbe；java J4边界','八份均识别正常完成清理/前台后台契约变化；平台、定级是否过强另裁决。'),
('G05',2,'普通会话容量由驱逐改拒绝，需承载用户可见错误',[1,.5,1,1,1,1,1,1],'python P3；parent/HEAD容量对照','E02只描述原子限容而未作为容量策略变化讨论；其余明确识别。拒绝有安全收益，不记为纯错误。'),
('G06',2,'新journey容量拒绝/外部关闭等错误缺少结构化映射',[0,0,.5,0,0,1,0,0],'python P3/P5；BrowserVerifier55','E06 H7/H11明确新容量/创建取消500；E03只发现499/504与watcher错误泛化，部分覆盖诊断机制；其余未建立相关链路。'),
('G07',3,'生产BaseHTTPMiddleware使真实disconnect轮询未及时观察断连',[0,0,0,0,0,0,0,0],'python GT-PY-02；root/disconnect_independent.log','独立发现，八份均未识别；不把E08的取消吞没当同一问题。'),
('G08',5,'真实Request CancelScope吞watcher取消，finally无界gather可挂起',[0,.5,0,0,0,0,0,1],'python GT-PY-01；root/watcher_*；真实Uvicorn A/B','E08完整识别根因；E02 P3-9指出gather无总时限但误以多资源慢清理解释，只计部分预警；其他未识别。'),
('G09',1,'非Linux快照无法保证重父化子进程全部退出，属剩余限制',[1,.5,0,0,.5,.5,1,1],'java J4；portable-mac/linux','E01补充S2、E07实测、E08明确旧局限；E02/E05/E06提平台限制但对假确认后果不足；其余未识别。'),
('G10',1,'持续inspection失败与有限确认预算的条件性可用性风险',[1,1,1,.5,1,1,.5,.5],'java J6；反例16ms恢复','识别机制得分；把单次枚举异常等同永远失败另扣准确性，不能由该机会得分推导严重度。'),
('G11',1,'terminate与hook竞争预算及新增首次hook前超时门控',[1,1,0,0,0,0,0,0],'parent cleanup360；HEAD380；DockerRuntimeService25','E01 S1/E02 P2-1均发现真实问题族，但归因各有不足；计发现，D2分别限定。'),
('G12',1,'新未确认分支缺通用truncated键，诊断字段不能代表清理阶段',[0,0,0,0,0,1,0,0],'java J3；BashTool414；ToolResult120','E06 H5唯一准确指出通用截断字段错配；不混同既有preview裁剪问题。'),
('G13',1,'未完成new_context分配/等待者无自身时限的可用性限制',[1,0,0,1,0,0,0,0],'browser_service await shield / pending.result_ready','E01 R4/E04 Q2明确；普通调用风险，journey外层有预算，父实现也有锁内等待，本项为剩余限制低权重。'),
('G14',1,'disconnect watcher异常完成被误分类为客户端断开499',[0,0,1,0,0,0,0,0],'python probe_contracts；journey47','E03 F6识别；需receive抛异常的条件，不代表常态断连。'),
('G15',1,'服务/DevServer保留状态缺运行期恢复与可观测性',[1,1,1,.5,.5,1,.5,0],'DevServerLauncher132；PythonProcessManager242','E01/E02/E03/E06论及恢复路径；其余只述保留状态及影响；不重复G02的Run会话后果。'),
('G16',1,'绝对deadline跨主机时钟偏差及部署前提',[.5,1,1,1,0,1,0,0],'BrowserVerifier44；journey35','E02/E03/E04/E06明确；E01只核对墙钟/部署兼容；本地同宿主非现实故障，故仅1。'),
]
# D2 units: important factual issue families and material safety assertions.
# Pure style/length/general advice and duplicate summary lines are not denominators.
# Each row is report reference, weight from claimed severity, credit, judgment.
U={
'E01':[
('R1',3,1,'启动summary误判已由真实coordinator+parent对照确认；概率未测。'),
('R2/行为',2,1,'正常完成清理及未确认返回失败均存在，有意契约变化。'),
('R2/CWD',2,0,'L169把跳过update推为shell状态未更新，方法实际仅日志；CWD由脚本原子保存。'),
('R3',2,.5,'持续保留/无周期回收成立；遗漏completeRun及取消回调重试，不能说正常Run无任何重试。'),
('R4',2,1,'容量拒绝和未完资源计费成立，说明淘汰也有风险。'),
('R5',2,.5,'持续inspection失败可不确认；原probe同时让root永久alive，不能证明仅枚举失败就永久不确认。'),
('R7',1,.5,'全/proc扫描存在；非每PID都二次读，第二读只限本组匹配项。'),
('R9',1,1,'两个真实浏览器测试受env门控，仓库CI未启用。'),
('R10',1,1,'废弃verifier旧ID/不关闭成立，已注明未用。'),
('R11',1,1,'50ms/sleep敏感有源码依据；发生率不作结论。'),
('S1',1,.5,'共享deadline既有，但HEAD新增首次hook前期限检查；旧runner首次会调hook，Docker内部另会过期早退。'),
('S2',1,1,'非Linux假确认为剩余局限，非新回归。'),
('S3',2,1,'沙箱未确认分支丢输出为真，父已有；仅新暴露部分不视作新代码。'),
('S4',1,1,'FAILED不被健康检查周期接管；显式stop/start有条件可恢复。'),
('C1/C2/C4/C5',1,1,'增量字段、Request注入、journey不自动重试、API幽灵快照修复均可静态闭环；合为契约一族防灌水。'),
('Q2',2,.5,'Browser清理大部保留取消语义；路由watcher/gather不保证有界， blanket可靠过强。'),
],
'E02':[
('P1-1/硬失败',3,1,'新分支确实如此；是否错误要区分安全策略（D3）。'),
('P1-1/CWD',3,0,'update为空日志，cwd/env丢失是错误因果。'),
('P1-2',3,1,'已主动补completeRun一次重试，持续失败容量占用成立。'),
('P2-1',2,.5,'预算共享既有，首次hook前期限门控新增；非全新Docker清理饥饿。'),
('P2-2',2,1,'沙箱丢输出属父已有，报告已更正。'),
('P2-3',2,1,'Linux硬依赖为真且明确默认镜像满足。'),
('P2-4',2,1,'该条限定/proc持续失败，原理成立；未把普通镜像实测等同受限环境。'),
('P2-5',2,1,'前台清理行为变更真实并指出is_background迁移。'),
('P2-6/P3-4',2,1,'Python FAILED与DevServer保留无运行期自动恢复成立；非只能重启进程。'),
('P3-2/P3-3',1,1,'旧verifier与未用import确实存在；合并。'),
('P3-5',1,.5,'4参构造器生成ID成立，但“实际只用于选择”过窄；公开兼容构造并不天然错误。'),
('P3-6/P3-7',1,1,'非Linux采样与/proc扫描开销真实，未测CPU严重度不放大。'),
('P3-8',1,1,'时钟偏差条件风险成立。'),
('P3-9',1,.5,'gather缺总时限真实；串行多资源/TTL保证并未证明watcher根因。'),
('T4',1,.5,'Linux限定测试薄真实；说非Linux真实路径无测试过强，runner/tree/服务现有测试会在mac运行。'),
('T5/T6',1,1,'env门禁及反射/私有mock测试耦合成立。'),
('A3/TTL',2,.5,'快照/ID契约成立；TTL不能自动重发已异常close，路由取消join也非总有界。'),
('A5/新增事件',1,0,'process_started/process_finished在父提交runner已存在，本次不是新增。'),
],
'E03':[
('F1/F1-chain',3,.5,'会话阻塞条件成立；probe永远false并直接调用runner，未验证文中终态coordinator；遗漏完成期重试。'),
('F2',3,.5,'持续/proc失败可未确认；每条/永不确认泛化过强，root退出可恢复。'),
('F3',2,1,'普通限容驱逐改拒绝真实。'),
('F4/硬失败',2,1,'退出0也失败为真，安全策略另评。'),
('F4/CWD',2,0,'方法空实现，目录/环境丢失错误。'),
('F4/沙箱新增',2,0,'沙箱分支父提交已存在。'),
('F5',2,.5,'跨主机偏差真实；保护失效不准，仍min(120,remaining)。正文有保留条件，故部分。'),
('F6',2,1,'异常watcher被分类499已probe支持；触发频率未知。'),
('F8-parse',1,1,'只捕NoSuchFileException，坏格式可抛异常；不声称真实/proc常返回截断。'),
('F8-perf',1,.5,'全扫描真实；每PID双读为错。'),
('F8-deps',1,1,'依赖真实且实测默认满足。'),
('F9',1,1,'bash包装可引入PWD/SHLVL，环境严格清空语义发生差异，报告已限定当前调用无实际影响。'),
('F10',1,.5,'setsid改变控制终端/组语义；docker stop只发PID1等，不能笼统说旧组会被连带。'),
('F11',1,1,'grace+5s改+2s属实。'),
('S1/S2',1,1,'FAILED健康检查不接管、同步stop预算12s属实。'),
('S5',2,1,'499/504在Java泛化为PYTHON_CALL_FAILED属实。'),
('S7',2,0,'Python异常close缓存任务，TTL不会发新RPC；“有界可自愈”错误。'),
('T2',2,1,'真实浏览器门禁未接CI属实。'),
('T6',2,.5,'旧测试/路径未改且单独重跑过支持既有可能；不是充分父提交A/B证明。'),
('A7',2,.5,'Request注入正常；join有界的强保证被真实watcher反例推翻。'),
('测试计数',1,.5,'开头87运行/6跳过，后文写88 passed/6 skipped，内部不一致。'),
],
'E04':[
('S1/硬失败',3,1,'新失败结果及前台残留清理真实。'),
('S1/CWD',3,0,'cwd/env丢失错误。'),
('S1/所有命令失败',3,.5,'需持续inspection错误，不是任意mac受限枚举。'),
('S2/Q1',3,.5,'无周期回收和计费成立；固定16容量不是无限增长，完成期有重试。'),
('S3',3,1,'容量策略变化真实，是否P1在D3另评。'),
('D1',3,1,'当前依赖满足且自定义缺失可失败；归类部署前提而非当前缺陷。'),
('D2',2,1,'跨机墙钟偏差风险成立。'),
('D3',1,1,'PowerShell未加对应terminationConfirmed失败分支属实。'),
('Q2',2,1,'普通同ID等待创建无自身timeout；journey另有外层预算。'),
('Q3',2,.5,'私有SDK耦合主要在测试；将context._impl_obj.request.dispose等说成生产依赖过宽。'),
('Q4',2,1,'stat字段和缺短字段显式检查属实。'),
('Q5',2,1,'install成功但清理未确认时失败属实。'),
('Q7',1,.5,'Java此调用仅读success为真；公开响应closed不可因此称全局死字段。'),
('T1',2,1,'fixture50ms与调度敏感性有证据；日志未独立认证在D4体现。'),
('A10',2,.5,'Browser cleanup有限等待大体成立；journey join不具总有界保证。'),
],
'E05':[
('R1',3,1,'启动summary误判独立确定性验证；原测试次数不证明频率。'),
('R2',3,1,'普通容量变化真实；高定级另在D3评。'),
('R3',3,.5,'清理变化真实；“仅Linux生效、mac不会”过强，已记录后代在mac也能清理。'),
('R4',2,.5,'持续枚举异常可能失败；root被杀后仍永不确认不成立。'),
('R5',2,1,'明确缓存失败latch且祖先关闭可恢复，边界正确。'),
('R6',2,1,'未确认从bool成功改抛异常、主要调用有捕获属实；未捕获调用是假设，已说明。'),
('R7',2,1,'startup守卫及先shutdown条件真实。'),
('R8',1,1,'Linux依赖成立、默认镜像满足。'),
('R9',1,1,'destroy仅根进程属实，定位为误用风险而非当前调用bug。'),
('R10/T2',1,1,'真实浏览器CI门禁缺失属实。'),
('T1',2,.5,'源码支持startup窗口，但单次父通过不足定量因果；摘要tree测试父失败与后文父通过矛盾。'),
('T3',1,.5,'本地Linux跳过真实；取消窗口是跨平台问题，并非只能Linux测试发现。'),
('A2/A5',2,.5,'生命周期整体改进真实；“deadline/disconnect有界”遗漏真实watcher。'),
],
'E06':[
('H1',3,.5,'容量/同会话后续拒绝成立；任一失败即永久、没有自动重试过强。'),
('H2',3,1,'正文明确修正root死亡后可确认，保留持续失败必要条件；摘要强定级在D3评。'),
('H3',3,1,'前台残留清理行为真实，是否破坏/安全策略在D3评。'),
('H4',2,.5,'保留无后台回收真实；未确认不必然代表监听进程仍活、端口占用。'),
('H5',2,1,'明确排除CWD假阳性，并发现新truncated字段错配；枚举metadata本来粗糙已区分。'),
('H6',2,.5,'失败latch/TTL不重发与容量占用真实；异常不证明真实context仍活，同进程shutdown/startup可恢复。'),
('H7',2,1,'journey新限容及HTTP错误映射缺口真实；存在额外watcher挂起不否定无结构化映射。'),
('H8',2,.5,'FAILED保留/root grace/stopvoid真实；“重试每次必失败”不成立，资源可迟恢复。'),
('H9',2,1,'两个opt-in真实浏览器测试CI不启用，负载敏感与私有耦合真实。'),
('H10',1,.5,'路由自身不记录该HTTPException属实；应用中间件会日志，不能泛称Python侧无日志。'),
('H11',1,1,'创建被外部close取消后HTTP500、资源已清理，实际ASGI复现；是分类问题。'),
('H12',1,1,'旧废弃verifierID/120s真实且不可达边界已说明。'),
('H13c',1,1,'unclosed_contexts值标签承担sessionID/诊断标签双义，静态可见。'),
('H13d',1,1,'fallback UUID截8hex，Java常规提供完整UUID，已作P3潜在限制。'),
('H13e',1,1,'600s总预算与新增余量关系属低级预算分析，不当作已复现故障。'),
('H13f',1,1,'/proc扫描及known集合开销存在，未将小推测算已测瓶颈。'),
('H14',1,1,'driver stop失败latch且祖先无法确认释放时startup受阻，需进程恢复/健康可观测。'),
('A19',2,0,'HTTP模式跳快照与父提交一致错误，父确会调用并创建幽灵资源。'),
('A20',2,.5,'验证setsid/bash仅排除此分支，不能保证所有PROCESS_GROUP_UNAVAILABLE（握手身份等）不发生。'),
('A9/取消安全',2,.5,'BrowserService取消保护大体有证据，整体journey无界watcher是边界反例。'),
],
'E07':[
('P1-1',3,.5,'容量变化/close记账真实；只能重启进程错误，可同进程shutdown/startup。'),
('P1-2',3,.5,'mac真实孤儿漏清理成立；列为P1回归缺父归因，实际是旧局限未闭合。'),
('P2-1',2,.5,'持久占用真实；完全无自动重试遗漏completeRun。'),
('P2-2',2,1,'Linux依赖及默认镜像大概率满足正确条件化。'),
('P2-3',2,.5,'mac轮询开销有效实验；Linux0-4ms被混入实测语境且无Linux运行。'),
('P2-4',2,1,'前台清理契约变化真实。'),
('P2-5',2,0,'跳日志方法不造成cwd/env陈旧。'),
('P2-6a',2,1,'真实浏览器测试在CI门控跳过属实。'),
('P2-6b',2,1,'50ms和sleep时序脆弱有源码依据，频率未认证。'),
('P2-6c/S1',2,.5,'未改测试/模块并非父提交对照，无因果的确定断言过强。'),
('S2',1,1,'close抛异常、路由已有捕获属实。'),
('A1/A2',1,1,'签名兼容、ID/strict修复、沙箱分支旧有可静态闭环。'),
],
'E08':[
('3.1/核心hang',3,1,'真实Request吞watcher取消机制成立，评审用实际容量路径及Uvicorn A/B独立复现。'),
('3.1/成功快路径归因',3,.5,'原脚本缺失；无await创建替身不等同真实创建成功路径。证明的是可行时序，真实可达触发由容量拒绝补证。'),
('摘要/全部测试无http_request',2,0,'源码143-145传SimpleNamespace，报告正文也承认；准确表述是没有真实Request/ASGI测试。'),
('3.2',2,.5,'正常完成清理/未确认失败属实；逃逸未被发现可假确认，不一定导致unconfirmed；未直接声称CWD丢失，不扣该误报。'),
('3.3',2,1,'容量策略改拒绝属实，并承认可能更安全。'),
('3.4',1,1,'明确非Linux为既有best-effort而非回归，校准正确。'),
('3.5',1,1,'废弃旧verifier与未用import真实。'),
('4.1/旧失败归因',2,.5,'未改模块+单独重跑仍失败不足证明父提交亦失败；仅支持疑似既有/环境。'),
('4.3',1,1,'报告保留间歇失败及后50次未复现，不据此作稳定故障结论。'),
]
}
# Interpretive rubric items have explicit anchors, not statistical measurement.
rubrics={
'E01':{
'D3':[(3,'R3遗漏完成期重试；S1对旧首次hook门控修正不足，S2明确非回归。'),(3,'R5模拟root永活混杂；Linux典型窗口缺测量。'),(3.5,'多以行为变更/中低风险表达，但R1“唯一”与局部强保证偏满。')],
'D4':[(4.5,'调用链和行号较完整，少量扫描/空方法核查失误。'),(4,'probe源、HEAD/parent文本报告现可见并已留存，未保留所有原始环境。'),(4,'有A/B、重跑与环境披露；混杂probe未隔离单因果。')],
'D5':[(3,'Bash前后台沙箱、服务、journey双模齐全。'),(2.5,'并发/归属/错误质量具体，但部分机制未经反证。'),(3,'CI门控、flaky、平台对照具体。'),(3,'CHANGELOG、旧ID文档、调用一致性具体。')],
'D6':[(2.5,'reaper/重算摘要合理；TTL后释放未知存活容量需补归属保障。'),(3.5,'定位与复现有用，少量占位classpath；重复发现已在评分合并。')]},
'E02':{
'D3':[(4,'主动修正沙箱旧分支与完成期重试；共预算归因仍混合。'),(3.5,'默认镜像与条件风险分开，部分平台测试断言过强。'),(3,'静态审查却“可安全保留main”过满；CWD误报被列P1。')],
'D4':[(4,'静态调用链/测试断言充分且可定位，存在空方法漏追。'),(0,'明确未执行构建或测试；不作造假扣分，但无动态验证信用。'),(3.5,'有两次自我修正及局限披露，缺动态反例/可执行探针。')],
'D5':[(3,'跨端、沙箱、完成期收敛覆盖广。'),(2.5,'并发、失败元数据、维护性分析具体。'),(2.5,'门禁/缺口具体，但非Linux无测试表述不准。'),(2.5,'依赖/文档/import/命名有分析。')],
'D6':[(2.5,'修正状态同步建议无效；资源回收有方向但必须确认归属。'),(3.5,'优先级/源码位置与冒烟清单可执行，静态方法说明清楚。')]},
'E03':{
'D3':[(2.5,'沙箱新增、完成期重试、Python自愈存在重要归因误差。'),(3,'Linux探针与本机边界清楚，永久锁死/inspection全失败放大。'),(3,'最高风险判断抓到条件可用性，但证据覆盖不支撑不可逆结论。')],
'D4':[(4,'注册链追踪深入，部分上层归因未被所引probe覆盖。'),(4.5,'Linux probe、registry repro、原始日志具实物，复核价值高。'),(3.5,'反复测flaky、披露没跑真实浏览器；无上层终态A/B。')],
'D5':[(3,'Java/Python/沙箱/会话全链广。'),(2.5,'并发和归属分析充分，Python恢复理解有误。'),(3,'实际Linux、CI门控与flaky重复试验具体。'),(3,'文档/运行参数/日志规范分析充分。')],
'D6':[(2,'强制unregister、rootdead即确认、照搬不存在的Python自愈不能直接实施。'),(3.5,'复现文件/修复优先级明确；计数与少数归因前后不一。')]},
'E04':{
'D3':[(3,'识别安全取舍和API快照修复，未追完整恢复链。'),(2.5,'全部命令失败/无限增长及部署P1较宽。'),(2.5,'提出多个P1同时称高度正确/可合并，放行依据不足。')],
'D4':[(3.5,'链路与行号可查，但重要被调方法漏读。'),(2,'有测试数字和flake经过，缺命令/原始输出路径，不等于虚假。'),(2.5,'有重跑/未跑真实浏览器披露，缺关键父提交实验。')],
'D5':[(2.5,'主要链路齐，cancel终态及failure恢复不足。'),(2.5,'并发/SDK/复杂度有具体点。'),(2,'测试陈述有价值，但CI仅建议确认，未给核查闭环。'),(2,'依赖/时钟/契约提及，规范审查较简略。')],
'D6':[(3,'多数指标/文档/安全取舍建议稳妥，错误CWD建议无效。'),(3,'报告紧凑定位清楚，复现实物缺少。')]},
'E05':{
'D3':[(3.5,'区分部分旧失败与新取消窗口，个别父失败前后矛盾。'),(2.5,'仅Linux生效及root已死仍不确认外推；close恢复边界准确。'),(3.5,'真实取消错误优先，行为变更高定级略重但注明产品意图。')],
'D4':[(4,'启动取消链、Pythonlatch与关键源码清楚。'),(2.5,'报告内输出/命令可重建，所指/tmp/zk-parent和独立probe源当前无可核证据。'),(3.5,'明确平台/没跑Linux、A/B自述和flake局限；单次测试归因仍不充分。')],
'D5':[(2.5,'取消/Python深入；同session注册阻塞与完整服务恢复弱。'),(3,'状态机/接口语义/取消边界分析具体。'),(3,'CI真实浏览器缺口、私有API、时序断言全面。'),(2.5,'CHANGELOG/粒度/依赖/契约有据。')],
'D6':[(2.5,'重算summary、祖先回收合理；枚举降级宣告确认/为后台写法豁免需设计条件。'),(3.5,'优先级与命令清楚，个别临时目录当前不存在。')]},
'E06':{
'D3':[(3,'排除了CWD误报；H1重试与API父行为仍误归因。'),(3.5,'H2主动收窄，明确Linux/本机；端口与重试必失败仍过强。'),(3,'H3/P1和任一失败永久影响较强，实际条件与P等级应拆分。')],
'D4':[(4.5,'调用链/排除清单/代码证据很广，有明确反证。'),(4.5,'执行证据scratchpad、命令与原始输出可见已留存；未做真实browser。'),(4,'有父提交失败复测、重跑、限制；关键H1仍仅静态并遗漏completion。')],
'D5':[(3,'覆盖功能链、错误路径、运维恢复广。'),(3,'能识别latch、通用截断键和空方法等细节。'),(3,'CI/用例语义/真实Request缺口分析具体。'),(3,'文档、观测、配置、类型和死代码全面。')],
'D6':[(2,'资源未知时释放容量/强制移除和Optional[Request]建议有实害，reaper/祖先回收方向可用。'),(3.5,'定位、优先级、原始证据清楚；少量摘要/正文强弱不一。')]},
'E07':{
'D3':[(2.5,'把剩余mac缺口作为高回归、无改动即无因果、漏completion。'),(3,'有mac真实子进程反例，Linux计时与mac证据混用。'),(2.5,'容量策略和旧平台限制定P1，修复建议未充分保留安全取舍。')],
'D4':[(3,'多数链路具体；OwnedProcess603/720/806超出257行，重要定位无效。'),(3.5,'macprobe源码可见，CPU/父Python对照自述；缺完整所有日志。'),(3,'诚实列Linux未测和负载，父归因不充分。')],
'D5':[(2.5,'平台/容量充分，启动summary与恢复误判漏掉。'),(2.5,'轮询/兼容语义/接口有具体讨论。'),(3,'Linux/真实浏览器门禁、flaky有实际分析。'),(2.5,'CHANGELOG和workflow具体。')],
'D6':[(2.5,'best-effort标签、集成测试合理；TTL强制回收/LRU条件不足。'),(2.5,'错误源码锚点影响核查；主要probe与命令可重建。')]},
'E08':{
'D3':[(3.5,'非Linux明确非回归；旧Java失败仅以未改文件归因不足。'),(3.5,'特定时序与多秒常态有区分；5/5替身快路径不能证明真实成功路径频率。'),(4,'核心hang与阻断建议匹配，Java“基本安全”缺关键取消/恢复覆盖。')],
'D4':[(4,'SDK取消链精确、调用范围广；真实创建让步未追到位。'),(3,'内联uvicorn/机制输出具价值，原jdelay/mech均缺，第三方只能重建；主审已独立证实机制。'),(3.5,'有delay对照、重复flake降级和平台边界；缺父提交实际HTTP/旧测试证据。')],
'D5':[(2.5,'深入Pythonwatcher，Java异常收敛和browserfailedclose恢复明显薄。'),(2.5,'SDK取消机制深入，状态机/维护性有具体点。'),(3,'真实ASGI覆盖缺口是关键，测试参数泛化错误D2单列。'),(2.5,'行为说明/废弃类/类型规范有覆盖。')],
'D6':[(2,'Optional[Request]会破坏路由注册；丢弃watcher/移除检测只是缓解不是完整资源修复。'),(3.5,'核心根因和复现操作清晰，原临时脚本未随报告保存。')]},
}
opps=[]
for gid,w,title,credits,evidence,why in G:
    opps.append(dict(id=gid,weight=w,title=title,credits=dict(zip(IDS,credits)),evidence=evidence,rationale=why))
records=[]
for eid in IDS:
    selected=[]
    for n,(ref,w,c,reason) in enumerate(U[eid],1):
        selected.append(dict(id=f'{eid}-F{n:02}',report_reference=ref,weight=w,credit=c,verdict={1:'支持',.5:'部分支持',0:'反证'}[c],reason=reason))
    row=dict(id=eid,name=next(e['name'] for e in M['entries'] if e['id']==eid),fact_units=selected,rubrics=rubrics[eid])
    row['dimensions']={'D1':30*sum(g['weight']*g['credits'][eid] for g in opps)/sum(g['weight'] for g in opps),'D2':20*sum(u['weight']*u['credit'] for u in selected)/sum(u['weight'] for u in selected)}
    for key,items in row['rubrics'].items(): row['dimensions'][key]=sum(v for v,_ in items)
    row['fact_numerator']=sum(u['weight']*u['credit'] for u in selected)
    row['fact_denominator']=sum(u['weight'] for u in selected)
    row['total']=sum(row['dimensions'].values())
    records.append(row)
obj=dict(schema_version='1.0',scored_at=datetime.datetime.now().astimezone().isoformat(),scope='8个冻结产物同轮评价；不是模型/产品一般能力排行',weights={'D1':30,'D2':20,'D3':15,'D4':15,'D5':12,'D6':8},opportunities=opps,reports=records,notes=['D1发现与D2正确性分别计算：识别真实机制但夸大后果者可获得发现分，同时准确性只得部分分。','D2按重要去重事实族，不以250个提取段落为分母；纯风格/重复摘要移到D5/D6。抽取全文仍公开供质疑。','部分支持仅0.5，不代表发生概率；未能认证测试历史不当成造假，在D4反映。','D3-D6为带锚点的人工判断分，不是统计测量；分数小数来自计算方便，不表示高精度。'])
(R/'evaluation_data.json').write_text(json.dumps(obj,ensure_ascii=False,indent=2))
for i,r in enumerate(sorted(records,key=lambda r:-r['total']),1): print(i,r['id'],r['name'],round(r['total'],2),{k:round(v,2) for k,v in r['dimensions'].items()},f"D2={r['fact_numerator']}/{r['fact_denominator']}")
