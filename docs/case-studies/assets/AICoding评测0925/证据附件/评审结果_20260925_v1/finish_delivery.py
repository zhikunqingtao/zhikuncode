from pathlib import Path
import json,hashlib,subprocess,datetime,re
P=Path(__file__).resolve().parent
repo=Path('/Users/guoqingtao/Desktop/dev/code/zhikuncode')
def L(label,p,line=None):return f'[{label}](<{P/p}{":"+str(line) if line else ""}>)'
def table(h,rows):return '| '+' | '.join(h)+' |\n| '+' | '.join('---' for _ in h)+' |\n'+''.join('| '+' | '.join(str(x).replace('|','\\|').replace('\n',' ') for x in row)+' |\n' for row in rows)+'\n'
main='AICoding八方评测_综合排名与证据审计.md'
ledger='''# 独立验证：真值与误报证据索引

核验对象：目标0db4b0498f6cf886f862fe293883256b1c52e049及父提交e78d6867f6fcd9172500b4c2cc85d4c4d927ec34。此文件将技术裁决与证据连接；探针通过说明对应断言成立，不说明完整系统没有缺陷。复现代码在隔离副本执行，未修改被审仓库代码。

'''
rows=[
('G01 新取消终态误判','真实runner/registry/coordinator，latch固定启动窗口；HEAD清理后仍FAILED，父提交CANCELLED。证明终态误判，不证明资源遗留。','evidence/java/ground_truth_java.md','evidence/java/logs/lifecycle-maven.log'),
('G02 恢复后无自动驱动','真实runner/registry与可恢复hook。有限重试失败后同session拒绝；直接runner重试可恢复。条件性恢复缺口。','evidence/java/probes/GroundTruthLifecycleProbeTest.executed.java','evidence/java/logs/lifecycle-maven.log'),
('G03 异常close、TTL与祖先恢复','底层close只一次，TTL复查失败task；确认祖先关闭后同进程shutdown/startup恢复。模拟浏览器I/O，不代表全部SDK故障。','evidence/python/probe_contracts.py','evidence/python/probe_contracts_head.log'),
('G04 前台残留清理','Linux真实shell/sleep；普通ProcessBuilder正常返回基线不清理，新OwnedProcess清理成功，exit=3、stdout/stderr保持。这个基线不是完整父runner测试。','evidence/root/LinuxProbe.java','evidence/root/linux_probe.log'),
('G05/G06 容量与错误映射','HEAD普通不驱逐、journey限容；父journey无界分配。映射探针用被动watcher隔离挂起，不能把其500直接称原路由所有时序的响应。','evidence/python/probe_contracts.py','evidence/python/probe_contracts_parent.log'),
('G06 外部close创建中请求','保留实际watcher与main中间件，close200、原journey500、context恰关闭一次。资源停止是修复，500分类是缺口。','evidence/python/probe_contracts.py','evidence/python/probe_external_actual.log'),
('G07 实际中间件断连检测','同一http.disconnect裸路由499；实际main中间件直到人工deadline504。是新增检测未兑现，不是父版曾有的保证被破坏。','evidence/python/probe_disconnect.py','evidence/root/disconnect_independent.log'),
('G08 watcher取消吞没','真实Request/AnyIO；满容量立即拒绝50ms预算，150ms未结束、watcher cancelling=1；第二次取消排空。','evidence/python/probe_watcher_cancel.py','evidence/root/watcher_head_independent.log'),
('G08 真实HTTP对照','Uvicorn loopback重复确认，替换浏览器I/O，不启动真实浏览器；父同容量情景200。','evidence/python/probe_loopback.py','evidence/python/probe_loopback_head.log'),
('G09 macOS剩余限制','同源真实父进程退出后孤儿，mac确认true而子仍活，Linux子退出。限定未事先留快照路径；父实现也有限制。','evidence/java/probes/OwnedProcessPortableProbe.java','evidence/java/logs/portable-mac.log'),
('G10 inspection反例','模拟活root枚举抛异常，destroy后root死；16ms后确认true，驳斥单次异常必永久失败。','evidence/java/probes/GroundTruthLifecycleProbeTest.executed.java','evidence/java/logs/lifecycle-maven.log'),
('G11 hook共享预算','父/目标源码对照：共预算旧有、首次hook前门控新加、Docker hook旧有超时检查。静态判断。','evidence/java/ground_truth_java.md','evidence/root/commit.diff'),
('G12 outputTruncated','新Bash失败分支缺通用truncated，上层只读此键；成功分支有OR映射。静态闭环，未实跑UI消费者。','evidence/java/ground_truth_java.md','evidence/root/commit.diff'),
('G13 创建等待限制','普通创建shield与result_ready缺自身时限；journey另有工作deadline。旧普通实现也有等待限制，低权重。','evidence/python/ground_truth_python.md','evidence/root/commit.diff'),
('G14 watcher异常误分499','人为令is_disconnected抛异常，确实499；仅证条件路径，不说明正常请求常见。','evidence/python/probe_contracts.py','evidence/python/probe_contracts_head.log'),
('G15/G16 服务恢复/时钟','保留句柄的恢复入口有限；跨机绝对时钟偏差为部署条件风险，Python仍有min120上限。静态依据。','evidence/java/ground_truth_java.md','evidence/python/ground_truth_python.md'),
('CWD误报反例','不调用update仍正确解析新CWD；方法只log，shell自己原子写文件。','evidence/java/probes/GroundTruthLifecycleProbeTest.executed.java','evidence/java/logs/lifecycle-maven.log'),
('Optional[Request]建议反例','相同FastAPI环境注册路由：Request=None成功，Optional[Request]=None立即FastAPIError。','evidence/root/probe_optional_request.py','evidence/root/optional_request.log')]
ledger+=table(['主题','验证/限制','源码或详细裁决','日志/对照'],[[a,b,L('代码/底稿',c),L('证据',d)] for a,b,c,d in rows])
ledger+='''## 测试与环境边界

Java使用JDK21，Linux容器为eclipse-temurin:21-jre-noble（日志为21.0.12），网络禁用且有init；只操作探针自己创建的进程。Python使用仓库已有3.11.15解释器、FastAPI0.115.6、Starlette0.41.3、AnyIO4.13.0、Playwright1.58.0；关键依赖与锁文件一致。运行记录见环境文件。

Java HEAD独立探针4项、父提交1项通过；Python生命周期/journey/publication聚焦测试65项通过。没有运行全部业务测试或真实Chromium。源码与可复现脚本保留；同一机器不同负载的耗时不能当发生概率。

'''
ledger+=table(['记录','入口'],[
['Java完整真值底稿',L('ground_truth_java.md','evidence/java/ground_truth_java.md')],
['Java复现步骤',L('reproduce.md','evidence/java/reproduce.md')],
['Java HEAD XML',L('4项测试XML','evidence/java/logs/surefire-head/TEST-com.aicodeassistant.tool.process.GroundTruthLifecycleProbeTest.xml')],
['父提交窗口日志',L('parent-window-maven.log','evidence/java/logs/parent-window-maven.log')],
['Python完整真值/复跑命令',L('ground_truth_python.md','evidence/python/ground_truth_python.md')],
['Python65测试',L('focused.junit.xml','evidence/python/focused.junit.xml')],
['Python环境',L('environment.json','evidence/python/environment.json')],
['opencode具体边界',L('E08_python_adjudication.md','evidence/python/E08_python_adjudication.md')],
['已保存参赛证据及SHA',L('submitted_evidence/index.json','evidence/claims/submitted_evidence/index.json')]])
ledger+='''## 评审自身试验的透明记录

首次Java probe有一处方法声明笔误，修正后成功；初始编译错误日志保留，不把评审脚本错误算项目缺陷。首次CWD测试用JDK系统临时目录，后来将唯一残留探针目录迁入证据目录并删除tracking文件；已执行源保留为`.executed.java`，便于辨别它与改成显式临时根的可复现源。两者业务断言相同，原仓库没有被修改。正式结论只来自成功运行与源代码，不隐去探索失败。

参赛报告指向的历史tmp文件有部分缺失，不能由此认定未运行。原物存在也只能证明当前可见，不足以认证整个执行历史。探针源、原输出和独立复验三者在本报告中分别标识。
'''
(P/'独立验证_真值与误报证据索引.md').write_text(ledger)
readme=f'''# 复核说明：方法修订、争议处理与重算

从 {L('主报告',main)} 开始。{L('评分明细','评分明细_机会矩阵与事实裁决.md')}给出每个计分项；{L('证据索引','独立验证_真值与误报证据索引.md')}解释测试条件。

## 一次完整八方评价

用户最后指示opencode已经产出、立即加入且不要第二轮版本。本轮八份在评分草案前全部快照，统一评价。文件夹中的`v1`是首次建目录时的内部名称，未产生另一个对外排名版本，也没有设置等待opencode的自动任务。

## 方法形成时间与修订

- 10:16:40冻结前七份；10:18:53记录六维权重30/20/15/15/12/8；10:27:46按用户指示冻结opencode。
- Java/Python核验者先读源码，报告提取者独立提取。主审看得到参赛名称，不宣称盲评；后续核验者按主题对照报告，均非独立人类评委。
- 初步评分草案采用124原子裁决。复核指出各报告拆分粒度不一，可能让低价值正确事实稀释重要错误。最终统一为12类事实主题、76个报告×事实族，F12次要工程合计权重1。
- 同时纠正：E02泛指无界gather不算发现CancelScope；E01核对时间字段不算发现跨主机时钟偏差；E02描述原子容量不算识别驱逐改拒绝；E05赞扬Java保留句柄不算发现Java服务恢复缺口；E03正文已保留min120，不按无限时长误报扣。
- 最后原句审计将E01/E02的F07恢复为完整事实信用：未发现watcher根因不得再作为事实错误。E06的F07改引L586整体预算“静态分析成立但未动态压测”，局部_finish_cleanup安全判断明确保留。
- D2不把缺原实验脚本当反证；E08未知5/5历史及正常成功路径频率排除，核心挂起给完整发现信用。E06/E08相同Optional[Request]建议按同口径计建议安全。
- 六维权重未随品牌和排名调节。机会权重、族归并和信用是事后证据裁决，不假称完整预注册。保留旧草案及复核文档只是审计历史，**旧分数不得与最终表混用**。

同侪记录：{L('Java草案复核','evidence/scoring_peer_java.md')}、{L('Python草案复核','evidence/scoring_peer_python.md')}、{L('最终原句复核','evidence/scoring_peer_final_claims.md')}。

## 重算当前最终结果

只需Python标准库，不安装软件、不访问网络、不运行项目：

```sh
python3 '{P}/recompute_scores.py'
```

脚本读取`evaluation_data.json`，重写本目录`scores_computed.json`并打印排名，绝不修改参赛报告或被审仓库。六维公式、5个权重方案、729权重组合、6项统一条目压力检查和人工锚点包络均由脚本计算。

重新生成主报告与评分明细：

```sh
python3 '{P}/render_reports.py'
```

`build_evaluation.py`与`normalize_families.py`保留方法开发历史；**正常复核不要单独运行build，它生成的是原子草案**。若确需从历史定义重建，须依次执行build、normalize、recompute；最终阅读应以当前JSON和主报告为准。原子草案未计分字段`atomic_audit_units`只供追踪。

真实代码探针复跑会创建隔离构建输出与短时进程，命令及清理要求分别在Java reproduce和Python底稿中；不是重算分数的必要步骤。目录中source-head/source-parent等是git archive副本，评审不在原仓库checkout、reset或写测试。

## 怎样提出可处理的异议

请指定报告快照ID、原文行号、裁决ID（如E06-F07或G02）、具体反证与建议口径。若是代码事实，给目标/父提交和可达触发；若是探针边界，指明替身与生产差异；若是权重偏好，直接使用公开JSON统一重算，不选择性只改某一选手。新增真缺陷加入机会集后须回看全部八份；更正重复事实须对全体统一归并。

单题、单次产物不能计算产品泛化置信区间；各维度分数是可复算判断，不是客观物理量。时间/token/费用/运行过程独立性、配置实际一致性均缺充分证据，不补造排行。

## 文件用途

- `inputs/`：八份冻结参赛报告。
- `manifest.json`：原路径、快照、SHA、纳入时间。
- `evaluation_data.json`：最终裁决与人工锚点；`scores_computed.json`：公式计算结果。
- `evidence/claims/`：250项原文抽取、证据存在性检查、20份保存的原附属材料。
- `evidence/java/`、`evidence/python/`：先行独立核验底稿、脚本、输出与隔离代码。
- `evidence/root/`：主审额外Linux控制、watcher/断连独立复跑和注解反例。
- `integrity_check.json`：最终输入一致性、Git状态、产物链接/算术校验。

本评审没有修改目标代码；参赛方历史运行是否曾改动ignored文件，仅凭最终报告无法认证。
'''
(P/'复核说明_方法修订与重算.md').write_text(readme)
method=P/'方法与规则_v1.md'
s=method.read_text()
if '最终发布前修订记录' not in s:
 s+='''\n## 最终发布前修订记录（不是完整预注册）\n\n原六维权重固定；初步数值草案后，经同侪复核将D2统一为12个事实族模板、76个报告×事实族，每份每族一次，次要工程总权重1。未知实验史不作反证。进一步原文复核纠正把漏检当误报的E01/E02 F07；E06 F07改引实际整体预算静态判断。D1对完整发现的边界统一收紧，改动对所有八份统一生效。124原子裁决仅保留为开发历史，不参与最终计分。\n\n机会集及事实裁决形成于源码/报告审阅之后，不是盲评或完全预注册实验；完整时间线、逐项改动与复核意见见《复核说明_方法修订与重算.md》。最终报告包含权重与条目解释敏感性，不能仅凭小分差作确定能力断言。\n'''
 method.write_text(s)
# Input/working tree integrity; compare only known files and tracked repository state.
m=json.loads((P/'manifest.json').read_text())
entries=[]
for e in m['entries']:
 def sha(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()
 entries.append({'id':e['id'],'snapshot_unchanged':sha(e['snapshot'])==e['sha256'],'original_unchanged_since_snapshot':sha(e['source'])==e['sha256'],'snapshot_sha256':sha(e['snapshot'])})
git=lambda *args:subprocess.check_output(['git','-C',str(repo),*args],text=True).strip()
d=json.loads((P/'evaluation_data.json').read_text()); scores=json.loads((P/'scores_computed.json').read_text())
independent=[]
for r in d['reports']:
 v=30*sum(g['weight']*g['credits'][r['id']] for g in d['opportunities'])/sum(g['weight'] for g in d['opportunities'])
 units=[u for u in r['fact_units'] if u['credit'] is not None]
 v+=20*sum(u['weight']*u['credit'] for u in units)/sum(u['weight'] for u in units)
 v+=sum(s for parts in r['rubrics'].values() for s,why in parts)
 expected=next(s['total'] for s in scores['rankings'] if s['id']==r['id'])
 independent.append({'id':r['id'],'absolute_difference':abs(v-expected)})
# Make file before link validation so its forward references can resolve.
result={'checked_at':datetime.datetime.now().astimezone().isoformat(),'inputs':entries,'repository_head':git('rev-parse','HEAD'),'git_status_after':git('status','--porcelain'),'git_status_before':m['git_status_before'],'notes':'Git状态覆盖tracked/untracked状态，不认证其他参与者历史操作或ignored文件。评审脚本未修改目标源码。','arithmetic':independent}
(P/'integrity_check.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
link_errors=[]
for f in [P/main,P/'评分明细_机会矩阵与事实裁决.md',P/'独立验证_真值与误报证据索引.md',P/'复核说明_方法修订与重算.md']:
 for path in re.findall(r'\]\(<(/[^>]+)>\)',f.read_text()):
  base=re.sub(r':\d+$','',path)
  if not Path(base).exists():link_errors.append({'file':f.name,'target':path})
result['broken_local_links']=link_errors
result['all_input_checks_passed']=all(e['snapshot_unchanged'] and e['original_unchanged_since_snapshot'] for e in entries)
result['tracked_repository_clean']=not result['git_status_after']
result['arithmetic_passed']=all(x['absolute_difference']<1e-10 for x in independent)
(P/'integrity_check.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
# A concise root entry avoids mixing evaluator output with original competitor reports.
entry=f'''# AICoding八方评测结果\n\n已将opencode与另外七份报告纳入同一轮。\n\n{L('打开完整评测报告',main)}\n\n'''
entry+=table(['计算序位','报告','总分'],[[r['rank'],r['name'],f"{r['total']:.1f}"] for r in scores['rankings']])
entry+='第一、第二及第三、第四分数接近；末两份实质并列。此表评价本次单题产物，完整依据、敏感性、快照和可重算脚本均见主报告。\n'
(P.parent/'评审汇总_八方评测结果入口.md').write_text(entry)
print(json.dumps({'input_checks':result['all_input_checks_passed'],'head':result['repository_head'],'git_clean':result['tracked_repository_clean'],'arithmetic':result['arithmetic_passed'],'broken_links':link_errors,'main_report':str(P/main)},ensure_ascii=False))
