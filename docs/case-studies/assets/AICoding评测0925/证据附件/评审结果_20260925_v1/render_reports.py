"""Render the same-round eight-report evaluation from auditable JSON judgments."""
from pathlib import Path
import json, re, datetime
P=Path(__file__).resolve().parent
D=json.loads((P/'evaluation_data.json').read_text());S=json.loads((P/'scores_computed.json').read_text());M=json.loads((P/'manifest.json').read_text())
C=json.loads((P/'evidence/claims/claims.json').read_text())
N={r['id']:r['name'] for r in D['reports']}; R={r['id']:r for r in S['rankings']}
def link(label,path,line=None):
    q=str(P/path) if not str(path).startswith('/') else str(path)
    return f'[{label}](<{q}{":"+str(line) if line else ""}>)'
def esc(s):return str(s).replace('|','\\|').replace('\n',' ')
def tab(headers,rows):
    return '| '+' | '.join(headers)+' |\n| '+' | '.join(['---']*len(headers))+' |\n'+''.join('| '+' | '.join(esc(v) for v in row)+' |\n' for row in rows)+'\n'
def snap(e,line=1,label=None):return link(label or f'{N[e]}原文 L{line}',f'inputs/{e}.md',line)
def cref(e,key):
    for report in C['reports']:
        if report['report_id']==e:
            found=next((x for x in report['claims'] if x['original_id']==key),None)
            if found:return snap(e,found['report_line_start'],f'{N[e]} {key}')
    return snap(e,label=f'{N[e]} {key}（全文）')
def order(rows):return ' → '.join(x['name'] for x in rows)
mainname='AICoding八方评测_综合排名与证据审计.md'
appendix='评分明细_机会矩阵与事实裁决.md'
ledger='独立验证_真值与误报证据索引.md'
readme='复核说明_方法修订与重算.md'
main=f'''# AICoding 八方评测：综合排名与证据审计

评测日期：2026-09-25。opencode 已纳入同一轮，与其余七份使用统一口径；这是本次完整交付，不另出第二轮版本。

**本题综合得分最高的是 zhikuncode，其次是 DeepSeekHarness；opencode 提出了本轮价值最高的独有缺陷之一，综合排第三。** 第一、第二只差 {R['E06']['total']-R['E01']['total']:.2f} 分，第三、第四只差 {R['E08']['total']-R['E05']['total']:.2f} 分；末两份几乎同分。合理的条目裁决或权重变化可以交换部分顺序，不能据此宣布产品能力已经分出确定高下。

这里排名的是**这八份最终报告对这一个提交的审查质量**。同模型、同推理等级、同提示词按用户提供的实验设定记录；没有运行轨迹与配置证据，无法把差异全部归因于 AI Coding 产品，也无法评价速度、成本或日常编码能力。最可靠的比较单位是“发现了什么、说得是否准确、证据能否复核、建议是否安全”。

## 1. 统一评分结果

'''
main+=tab(['计算序位','产物前缀 / 参赛名称','总分 /100','发现 /30','准确 /20','归因 /15','证据 /15','覆盖 /12','建议交付 /8'],[[r['rank'],r['name'],f"**{r['total']:.1f}**",*[f"{v:.1f}" for v in r['dimensions'].values()]] for r in S['rankings']])
main+='''小数由公式产生，不代表测量精度；各维度四舍五入后相加可能与总分差0.1。这里的分数不是学校式及格分，也不是产品能力百分位。

- **综合领先组：zhikuncode、DeepSeekHarness。** 前者覆盖、证据和细节审查更完整；后者准确抓住启动取消终态问题。当前完整发现/部分发现的边界如果统一放宽，二者会交换顺序。
- **接近且各有专长：opencode、pi。** 前者对真实请求取消机制的发现突出、已裁决事实准确性最高；后者 Java 取消与 Python 关闭恢复的覆盖更均衡。发现优先的权重下 pi 会超过 opencode。
- **ZCode 的动态核验值得肯定，但几项关键因果与恢复判断拖累总分。** QoderIDE 的静态纠错与限定较好；强调准确与风险校准时，两者会换位。
- **QoderCN 与 TraceCodeCn 实质并列。** 基准只差约0.07分；TraceCodeCn 的平台探针有价值，QoderCN 的表达与建议相对紧凑。不能把7/8名当作可可靠区分的能力差距。

这些分组只是阅读辅助，不是统计聚类。没有任何一份完整识别了已验证机会集中的所有关键缺口，也没有一份达到“所有结论均可直接采纳”。

## 2. 评审如何做到可核查

本轮采用“源码先行的独立核验 + 主张逐项审计 + 统一评分 + 反方复核”，不以篇幅、问题数量、P1数量或测试通过数量排位。

1. 对八份报告建立只读快照和 SHA-256。前七份10:16冻结；opencode按用户补充指示10:27加入，在产生评分草案前完成纳入。
2. Java、Python核验代理先读目标与父提交，不先读参赛报告；另一代理独立提取报告中的250个审查主张。主审整合时再逐项比对。**250是抽取单元数，不是250个真实缺陷，也不是评分分母。**
3. 从真实代码、父提交、确定性探针、聚焦测试建立16项统一审查机会，权重合计32。未被任何报告找到但独立验证的问题也纳入，避免把多数共识当真值。
4. D2把重要主张归入固定事实族，每份每族最多一次，共76个“报告×事实族”裁决。重复摘要不重复计算；次要工程观察每份合计权重上限1，防止用很多小正确点稀释大错误。
5. 三次同侪复核纠正了分母粒度、漏检与误报混淆、已限定结论被过度扣分等问题；统一重算八份，不给特定选手保留例外。

**独立性边界：**主审看得到品牌；代理也不是独立人类评委，不能称为双盲或专家多数投票。六维权重在10:18固定；机会集与事实族是在读源码、报告和初步数值草案后逐渐确定。因而这是公开修订的事后审计，不是完整预注册实验。以下公布全部依据，允许针对原文、源码、探针条件或评分规则提出具体异议。

'''
main+=tab(['维度','分值','评价对象 / 计算方法'],[
['D1 有效发现','30','30 × Σ(机会权重×发现信用) /32；完整1，局部0.5，未发现0。合法行为变化/旧限制使用低权重。'],
['D2 事实准确','20','20 × Σ(事实族权重×支持信用) /Σ事实族权重；支持1、部分0.5、反证0；未知实验史不作反证。'],
['D3 因果与校准','15','父提交归因、平台与可达条件、严重度及发布结论，三项各5。'],
['D4 证据与验证','15','静态链路、可复核测试/探针、对照反证与局限披露，三项各5。'],
['D5 工程覆盖','12','功能链路、代码质量、测试与CI、规范性，四项各3。'],
['D6 建议与交付','8','对症且安全的建议、定位及交付便利，两项各4。']])
main+=f'''D2权重按报告对该族主要断言的风险强调归一为1/2/3；这是人工判断，另给出了事实族等权的敏感性结果。事实、归因、证据、建议是不同维度：例如“发现真实恢复缺口”可在D1得分，“声称完全没有重试”在D2受限，“建议丢弃未知资源的归属”另影响D6。**同一个技术错误不在D2拆成多个重复扣分项；没有发现缺陷本身不等于说错事实。**

全表见 {link('逐项评分明细',appendix)}；流程与变更记录见 {link('复核说明',readme)}。

## 3. 真正拉开差距的技术证据

### 3.1 opencode 的核心发现成立，但生产触发范围必须收窄

`journey.py` 收尾只对断连 watcher 调用一次 `cancel()`，随后无时限 `gather()`。真实 Starlette `Request.is_disconnected()` 内的预取消 AnyIO `CancelScope` 能在特定时序吞掉这次取消，watcher继续轮询，请求无法结束。

独立验证使用实际 FastAPI/Starlette/AnyIO 栈、生产 `main.app` 中间件，以及真实 Uvicorn loopback HTTP，替换的只是浏览器 I/O。**已确定的实际代码触发是容量满导致首次 driver await 前立即拒绝**：50ms工作预算后，到150ms请求仍未结束；第二次定向取消才排空。父提交相同容量情景返回200；成功控制请求也返回200。说明核心缺陷可达，不能外推成所有成功Journey必挂。

opencode所述原始5/5快速成功替身实验的临时脚本当前未保留，无法认证该历史和生产成功路径频率；这影响可复核性与边界表达，**不否定已独立证明的根因，也不构成造假判断**。因此G08给它完整发现信用。QoderIDE泛指无界gather值得肯定，但没有识别这个取消吞没机制，不能获得同等根因发现分。

证据：{link('Python独立底稿','evidence/python/ground_truth_python.md')}、{link('真实HTTP HEAD日志','evidence/python/probe_loopback_head.log')}、{link('父提交日志','evidence/python/probe_loopback_parent.log')}、{link('opencode边界裁决','evidence/python/E08_python_adjudication.md')}。

### 3.2 DeepSeekHarness 与 pi 找到了真实的 Java 取消终态回归

进程启动期间，新的active预留条目已注册，但Process尚未发布。此时取消得到未确认summary；随后实际工作在等待期内清理完成，coordinator仍沿用旧summary，把结果记为FAILED而不是CANCELLED。

独立测试用真实runner、registry、coordinator与可控启动时序验证：`quiescent=true`、`activeNow=0`，终态仍为`PROCESS_TERMINATION_UNCONFIRMED`；父提交相同窗口得到CANCELLED。**这是终态和诊断误判，测试没有证明最终进程泄漏。**其余六份未准确识别这一点。

证据：{link('Java J1及父提交对照','evidence/java/ground_truth_java.md')}、{link('HEAD探针日志','evidence/java/logs/lifecycle-maven.log')}、{link('parent日志','evidence/java/logs/parent-window-maven.log')}。

### 3.3 “保留所有权后的恢复缺口”成立，“一次失败就永久锁死”过强

Java有限清理重试都失败后，lease和容量可能保留；run进入终态后没有自动恢复驱动。即使底层资源后来可清理，同session新run仍可能被旧注册挡住。独立探针证明了这条完整链，ZCode、zhikuncode追得最深。

但 `RunTracker.completeRun` 确有完成期重试，coordinator也有callback与扫描；直接调用runner再次确认成功可以释放。正确结论是“**有限重试耗尽后的可达恢复不足**”，不能写成“没有任何重试、任何一次失败都不可逆、只能重启”。直接释放lease、强行unregister或只看root已退出就宣告整组清理成功，可能重新丢失资源归属，不能作为无条件修复。

Python的failed `context.close` 缓存也有安全动机：Playwright的内部latch会让失败后的第二次close变成假成功。TTL复用失败task，不会重发RPC；恢复缺口真实。但确认browser/driver祖先释放后，`BrowserService.shutdown()`再`startup()`可在同进程恢复。pi对此边界较准确；ZCode将其称为“有界、可自愈”与代码不符。

证据：{link('Java J2','evidence/java/ground_truth_java.md')}、{link('Python关闭/TTL/恢复日志','evidence/python/probe_contracts_head.log')}。

### 3.4 多份报告共用的 CWD 误报不能因票数多就变成事实

DeepSeekHarness、QoderIDE、ZCode、QoderCN、TraceCodeCn把跳过`updateStateFromSnapshot()`与工作目录或环境丢失联系起来。实际这个方法只记录debug日志；shell包装代码自己原子写入CWD跟踪文件，下一命令直接读取。独立探针不调用update仍能读到新目录，完整环境变量原本也不承诺跨命令保留。

zhikuncode明确排除了这个误报，应获准确性信用。opencode只说错误分支跳过该调用，**没有声称因此丢失CWD**，不能按别人的错误替它扣分。前台残留清理、工具失败分支和后台命令使用契约的变化仍是真实观察；不能因为CWD因果错误就整族归零。

### 3.5 新发现、旧限制和产品策略必须分开

'''
main+=tab(['主题','统一裁决','对排名的意义'],[
['普通浏览器容量由驱逐改拒绝','行为变化真实，也保护已有会话；缺少结构化拒绝/恢复提示才是具体缺口。','不能把安全取舍自动判P1；也不能因有意设计而忽略可用性。'],
['非Linux孤儿进程清理','macOS确实可出现确认成功但孤儿仍活；Linux同源探针能清理。该非Linux限制父实现已有。','TraceCodeCn探针有价值；E01/E08对旧限制的归因更合适。'],
['inspection异常','持续检查失败可导致未确认；root退出后检查可以恢复，独立反例16ms确认成功。','单次枚举异常不等于所有命令永久失败。'],
['terminate与hook共享deadline','共享预算已有；首次hook前超时门控新加；实际Docker hook自身旧有期限检查。','E01/E02各有部分正确，不能简单全算新回归或全算误报。'],
['新失败分支outputTruncated','Bash未确认分支缺通用truncated键，上层只读该键。','zhikuncode独有的准确字段契约发现；不要混同旧preview截断。'],
['Optional[Request]类型建议','当前FastAPI：Request=None路由注册成功；Optional[Request]=None抛FastAPIError。','zhikuncode与opencode同等扣建议安全分；原签名并非功能缺陷。']])
main+=f'''上述结论均有源码或探针依据，逐项入口见 {link('真值与误报证据索引',ledger)}。

### 3.6 八份都遗漏了生产中间件下的断连检测缺口

独立探针向相同ASGI输入发送真实`http.disconnect`：裸FastAPI很快返回499；生产`main.app`中间件栈虽已消费断连消息，轮询仍未观察到，直到工作deadline才504并关闭。未修改的`BaseHTTPMiddleware`与新增检测组合形成集成缺口。

这与opencode找到的“收尾取消被吞”是不同问题。父提交原本没有该检测，因此它属于新增修复未兑现，而不是旧行为被破坏。也不能称为永久浏览器泄漏，因为deadline仍能兜底。G07八份均为0，表明仅汇总报告共识不能得到完整审查基线。

## 4. 八份报告逐一评价

'''
profiles={
'E06':('zhikuncode：综合覆盖与可核查材料最完整',
'Java/Python/服务托管/错误路径/CI/规范性都有具体分析，追到同session注册阻塞、failed-close latch、容量拒绝与创建取消的500、通用truncated字段错配，并明确排除CWD误报。提交的scratchpad、命令与原始输出当前可核，已复制留存。',
'遗漏启动窗口旧summary和真实watcher取消机制；对完成期重试、API快照父行为、恢复是否必须重启、端口是否必占用有过强或错误判断。Optional[Request]建议会破坏路由注册。',
'优先把H1/H6这类异常后恢复问题写成精确状态机：有哪些有限重试、何时耗尽、谁还能恢复；保留当前广覆盖，减少“必然/永久/无回归”的概括。每项修复建议先做最小可执行验证。'),
'E01':('DeepSeekHarness：取消终态发现准确，广度和对照证据较好',
'准确指出新启动预留窗口导致终态误判，并提供可留存探针；覆盖前后台进程、沙箱、浏览器容量、创建等待、服务恢复和CI。补充材料正确限定非Linux旧缺口。',
'CWD因果误报、每PID双读stat、inspection模拟的永活root混杂，以及旧cleanup首次hook门控的归因仍不准确。没有发现真实Request收尾挂起与同session恢复完整影响链。报告披露补充部分参考了其他报告，因此不能把其最终内容完全视为独立首轮检出。',
'把补充检索与独立发现分别留痕；给探针加入反例控制，避免模拟同时引入多个故障。取消修复应重新核实最终状态，不把启动未发布直接当作已确认退出。'),
'E08':('opencode：关键独有发现最突出，整体覆盖仍有明显缺口',
'精确定位真实Request、AnyIO CancelScope与无界gather的组合，是高价值、已独立确证的发现。容量策略与非Linux旧限制表达相对克制；固定事实族口径下D2最高。',
'Java异常收敛、启动取消终态、failed-close恢复、错误映射等覆盖不足。把测试缺少真实Request写成没有测试传http_request，字面与现有fake Request测试不符；原复现脚本缺失降低可复核性。Optional[Request]建议有实际破坏性。',
'保留机制级深入分析；把脚本、依赖版本、替身点、生产可达路径和原始输出随报告交付。补齐Java关键失败链和祖先回收；修复watcher需验证请求/任务确实排空，简单放弃等待并不能证明无后台残留。'),
'E05':('pi：Java取消与Python关闭边界分析较均衡',
'识别真实启动取消终态问题；对failed-close latch、异常与pending关闭的区别、祖先shutdown后恢复理解较好。测试门禁、私有SDK和时序断言审查具体。',
'“仅Linux生效”夸大平台差异：macOS也能清理已记录后代。inspection退出确认外推过强，部分父测试结果前后矛盾；临时父目录和独立探针源当前不足以认证原运行历史。漏掉同session注册完整链与watcher根因。',
'保留清晰的恢复边界分析；增加实际父提交和跨平台对照，长期保存探针。区分Linux新保证与其他平台已有能力，避免将未在本机跑到的路径写成未实现。'),
'E03':('ZCode：动态复现投入高，关键恢复模型判断需修正',
'追到保留lease阻塞同session新run，留有Linux探针、registry复现与日志；识别watcher异常误分499，跨主机时钟讨论正文保留min120上限，应该得到相应信用。',
'将Python异常close描述为有界自动自愈，与缓存失败task机制相反；遗漏Java完成期重试，CWD和部分沙箱新增归因错误。root已死即确认、强行unregister等建议不足以维护归属。原probe并不覆盖完整coordinator终态，不能替其证明所有上层结论。',
'给每个探针标出实际覆盖到的最高调用层；先纠正Python恢复模型，再提出Java对齐方案。把“无法自动恢复”与“不可恢复”分开，修复验收同时检查可用性与资源归属。'),
'E02':('QoderIDE：静态核对和自我纠错较好，实证与有效发现偏少',
'坦承未执行构建测试；明确修正沙箱旧分支、补出完成期重试，父归因比多份报告谨慎。链路、契约和清理预算有具体审查，泛指gather无总时限属于正确预警，事实分已保留。',
'CWD仍被列为高优先级问题，非Linux无真实测试断言过强；未识别容量驱逐改拒绝、启动终态与watcher根因。无动态实物，D4动态子项0/5；其余静态和局限披露仍有分，绝非整维度判零或判造假。',
'用少量针对性探针验证最高优先级结论，特别先读完被调用方法的实际副作用。发布结论应与静态证据边界相称。'),
'E04':('QoderCN：紧凑覆盖主要变化，验证闭环相对薄',
'识别容量拒绝、普通创建等待无自身时限、保留所有权可用性风险、部署依赖和跨机时钟。建议整体较稳妥，报告相对紧凑。',
'CWD/env误报、固定许可下无限增长与无任何重试的外推，以及有界join概括均需修正。测试数字与重跑经历缺可定位原始输出；CI部分停留在建议确认，缺少核查闭环。',
'把最重要的三项结论配上父提交对照和最小复现，记录实际测试命令/输出；对容量上限、有限重试与失败保留的边界作定量核对。'),
'E07':('TraceCodeCn：macOS反例有实证价值，归因和定位拖累可信度',
'真实macOS孤儿进程反例揭示平台剩余局限，probe源码可核；对容量策略、CI门禁、短时序测试、平台差异有具体讨论。',
'把旧mac限制升为高回归，声称完全无重试/只能重启，CWD/env因果错误；以未改文件证明旧失败无因果不足。OwnedProcess的603/720/806等引用超过目标文件257行，直接影响复核。',
'保留平台实验优势，但每个结论增加父提交和同源Linux对照；自动检查行号存在、文件版本一致。建议恢复容量前说明如何确认祖先资源已释放，避免笼统TTL强制回收。')}
for r in S['rankings']:
 e=r['id']; title,strength,weakness,improve=profiles[e]
 main+=f"### {r['rank']}. {title}（{r['total']:.1f}）\n\n**主要价值：**{strength}\n\n**主要限制：**{weakness}\n\n**最有针对性的改进：**{improve}\n\n原始依据：{snap(e)}；详细分数与事实族裁决见评分明细中{e}节。\n\n"
main+='''## 5. 排名能承受多大程度的规则变化

### 5.1 六维权重敏感性

以下是在基准裁决不变时的重加权；属于事后压力检查，不是额外样本。

'''
scenario_w={'基准':'30/20/15/15/12/8','发现优先':'40/20/10/15/10/5','准确与校准优先':'20/30/20/10/12/8','验证优先':'25/20/10/25/12/8','建议安全优先':'25/25/15/10/10/15'}
main+=tab(['方案','D1/D2/D3/D4/D5/D6权重','得分顺序'],[[k,scenario_w[k],order(v)] for k,v in S['scenarios'].items()])
main+='六维权重分别取基准的0.8、1、1.2倍，再归一到100，枚举729种组合，得到：\n\n'
main+=tab(['报告','729组合中的序位范围'],[[r['name'],'—'.join(map(str,S['weight_grid']['range'][r['id']]))] for r in S['rankings']])
main+='''这说明在这些特定权重附近、且条目裁决不变时，头两名顺序保持。但它**不代表729场实验、不代表胜率或置信区间**。更重要的条目解释敏感性如下。

### 5.2 条目解释与分母敏感性

'''
main+=tab(['统一改动','得分顺序'],[[k,order(v)] for k,v in S['judgment_scenarios'].items()])
main+='''将全部D1半档统一当作完整发现，会使DeepSeekHarness超过zhikuncode；这不是建议采用该宽松规则，而是明确说明第一名依赖“完整发现必须追到影响链”的判断。将挂起问题G08权重从5降到3，会使pi超过opencode。D2事实族等权、或去掉八份均漏的G07，基准顺序不变。

另外，D3—D6各人工子项统一上下浮动0.5分、在其合法范围内截断，分数包络明显重叠，见评分明细。这个压力包络也不是统计置信区间。**因此可以给出透明的计算排序，不能给出无条件、不可争辩的能力座次。**

## 6. 已验证什么，仍不能评价什么

本次评审实际完成：Java独立HEAD探针4/4、父提交取消对照1/1；macOS与Linux真实子进程探针；Linux前台残留清理及输出/退出码控制；Python聚焦测试65/65；真实Request/生产中间件/loopback HTTP对照；错误契约、TTL与祖先恢复、FastAPI类型注解反例。每一类都明确替身位置和环境。

没有运行真实Chromium、完整浏览器容器收敛、所有恶劣SDK故障、真实PID复用压力、生产负载或完整业务E2E；也没有把测试通过视为“正常功能链路绝无影响”的证明。评审没有复跑所有参赛方声称的全量测试；缺少原始脚本或日志只影响可复核性，不能由此认定其没有运行。共享机器并发负载下的时序测试、不同测试选择和重跑次数，也不能直接拿来判某方造假或更强。

'''
main+=tab(['想比较的能力','本轮可得结论'],[
['发现、准确性、归因、证据、工程覆盖、建议','可按公开规则评价这八份产物；存在人工裁决与机会集不完备性。'],
['过程独立性','E01披露参考其他报告；其余无完整轨迹，不能认证完全独立，也不能因措辞相同推断抄袭。'],
['模型/推理/系统提示配置一致性','用户提供一致设定；产物不能复验后台配置、工具权限、上下文或重试次数。'],
['速度、token、费用、工具效率','缺统一日志，不排名；文件时间戳不是准确耗时。'],
['是否曾修改被审仓库','本次评审采用只读源码和隔离副本；缺历史完整快照，不能认证参赛运行全程无修改。'],
['总体编程、架构、前端、安全能力','单个公开commit审查任务不足以外推，不给品牌综合实力结论。']])
main+=f'''如果后续要评“产品普遍能力”，建议使用至少20个不同类型的私有任务，每产品每任务重复3—5次；固定代码/依赖/权限/上下文与时间token预算，隔离目录、禁止交叉读报告，留存配置和工具轨迹。用人工确认的真实缺陷与注入缺陷建立隐藏标注，匿名评报告，并按任务而不是报告内句子做统计。这里的数量是设计建议，不是本轮结果已经具有的证据。

## 7. 交付与复核入口

- {link('机会矩阵、76项事实族裁决、人工锚点和敏感性分数',appendix)}
- {link('真值与误报证据索引',ledger)}
- {link('方法修订、争议处理和重算说明',readme)}
- {link('冻结输入及SHA-256清单','manifest.json')}
- {link('可重算的评分输入','evaluation_data.json')}；{link('计算结果','scores_computed.json')}；{link('标准库重算脚本','recompute_scores.py')}
- {link('250项原文抽取','evidence/claims/claims.json')}；{link('提交证据可见性审计','evidence/claims/claim_audit.md')}
- {link('最终输入与仓库完整性检查','integrity_check.json')}

被审对象：[目标commit](https://github.com/zhikunqingtao/zhikuncode/commit/0db4b0498f6cf886f862fe293883256b1c52e049)；父提交`e78d6867f6fcd9172500b4c2cc85d4c4d927ec34`。目标改动24个文件，+2870/−400。所有新增评审文件位于本评测子目录；本次评审未修改zhikuncode代码。
'''
(P/mainname).write_text(main)
# detailed score appendix
out='''# 评分明细：机会矩阵、事实裁决与人工锚点

这是最终评分的可审计明细。JSON与重算脚本为数值依据；原始报告快照为主张依据；独立探针与目标/父源码为技术依据。人工判断仍可被新证据纠正。事实族内部合并多个相关主张时：核心技术结论完整成立给1，含实质正确机制又含错误因果/保证给0.5，核心被反证给0。0.5不是错误概率，也不代表一半字句准确。

## A. 身份与输入

'''
out+=tab(['ID','参赛名称','冻结文件','SHA-256'],[[e['id'],e['name'],link('输入快照',e['snapshot']),e['sha256']] for e in M['entries']])
out+='''## B. D1统一机会集

信用：1=完整识别，0.5=识别机制/后果但未闭合本项要求的关键链，0=未发现。类别中含行为变化、剩余限制与实现缺口，不能把16项理解为16个新增bug。权重5用于可越过工作预算的请求挂起；4用于异常恢复后仍阻塞后续会话；3用于取消终态、关闭恢复和断连修复缺口；2用于用户可见契约；1用于较小诊断或已有条件性边界。权重由实际影响判断，未测发生率。独有发现不因只有一方指出而降权。

'''
out+=tab(['机会','权重','经核验的发现/审查机会',*N.keys()],[[g['id'],g['weight'],g['title'],*[g['credits'][e] for e in N]] for g in D['opportunities']])
for g in D['opportunities']:
 out+=f"**{g['id']}：**{g['rationale']} 证据索引：{g['evidence']}。\n\n"
out+='D1所有报告分母均为32；同一根因下的重复措辞不增加信用。G06区分错误契约，G08区分不能完成请求；一个报告不会因发现其中一个自动获得另一个。G02与G03分别为Java与Python恢复协议，不能互相代用。\n\n'
out+='## C. D2事实族口径\n\n'
out+=tab(['族ID','固定主题'],[[k,v] for k,v in D['fact_family_labels'].items()])
out+='''每份只计算实际提出的主张，所以分母不同；未讨论某族的损失在D1/覆盖，不凭空填0降低准确率。每族权重由该报告主要断言的强调程度归一：高/中/低为3/2/1；同族重复P标题不累计权重；F12每份总权重1。等权敏感性另列。家族内的复合裁决有粗粒度局限，审稿人可以指出某个关键原句应归哪个族并要求统一重算。

未知原始运行历史不列技术反证。原子草案124项保留在`atomic_audit_units`作为历史记录，**不参与最终评分、不能与本节76项混用**；原文抽取250项也不是分母。

'''
anchors={'D3':['父提交/新旧因果','平台/触发可达条件','严重度和发布结论'],'D4':['静态证据与链路','可复核动态证据','对照反证与局限'],'D5':['功能链路','代码质量','测试/CI','规范性'],'D6':['建议安全且对症','定位与交付便利']}
for r in D['reports']:
 e=r['id'];score=R[e]
 out+=f"## {e}. {r['name']}\n\n{snap(e)}\n\n总分 **{score['total']:.4f}**；D1={score['dimensions']['D1']:.4f}；D2=20×{score['accuracy_numerator']:g}/{score['accuracy_denominator']:g}={score['dimensions']['D2']:.4f}。\n\n"
 out+=tab(['裁决ID','事实族','报告内定位','权重','支持','裁决依据'],[[u['id'],u['title'],u['report_reference'],u['weight'],u['credit'],u['reason']] for u in r['fact_units']])
 out+=tab(['维度/子项','得分 /上限','理由'],[[f'{k} {anchors[k][i]}',f'{v[0]:g}/{5 if k in ("D3","D4") else 3 if k=="D5" else 4}',v[1]] for k,items in r['rubrics'].items() for i,v in enumerate(items)])
 if r.get('unverified_claims'):
  out+='**未判定而非反证：**\n\n'+''.join(f"- {x['reference']}：{x['reason']}\n" for x in r['unverified_claims'])+'\n'
 out+='**报告原句定位索引：**以下抽取项用于查原文，不另行累计得分。\n\n'
 cr=next(x for x in C['reports'] if x['report_id']==e)
 out+=tab(['原文项','提取主题','冻结报告行号'],[[c['original_id'],c['title'],snap(e,c['report_line_start'],f"L{c['report_line_start']}–{c['report_line_end']}")] for c in cr['claims']])
out+='## D. 替代权重与条目压力检查\n\n'
for k,rows in {**S['scenarios'],**S['judgment_scenarios']}.items():
 out+=f'### {k}\n\n'+tab(['次序','报告','得分'],[[i,r['name'],f"{r['score']:.4f}"] for i,r in enumerate(rows,1)])
out+='### 人工锚点±0.5的压力包络\n\n逐一将D3—D6每个子项上下移动0.5，限制在0至该项满分；D1、D2不变。所有项同时朝一个方向移动是压力检查，并非误差分布；不把这些范围标为置信区间。\n\n'
out+=tab(['报告','最低','最高'],[[N[e],f'{v[0]:.2f}',f'{v[1]:.2f}'] for e,v in S['subjective_anchor_envelope'].items()])
out+=f"\n返回 {link('主报告',mainname)}。\n"
(P/appendix).write_text(out)
print('Rendered',mainname,len(main),appendix,len(out))
