# -*- coding: utf-8 -*-
import json, re, hashlib, shutil, datetime
from pathlib import Path
base=Path('/Users/guoqingtao/Desktop/AICoding评测0925/评审结果_20260925_v1')
outdir=base/'evidence/claims'; src=base/'inputs/E08.md'
lines=src.read_text().splitlines(); data=json.loads((outdir/'claims.json').read_text())
r={'report_id':'E08','path':str(src),'sha256':hashlib.sha256(src.read_bytes()).hexdigest(),
   'visible_identity':'标题及 L6 明示 opencode','method_citation':'E08.md:L24-L34,L297-L321',
   'cross_report_disclosure':{'status':'无交叉阅读披露','citation':'E08.md:L6,L24-L34,L335','note':'声明独立阅读、测试与复现；未明确说明是否读过其他工具报告。报告名可见，非双盲。'},'claims':[]}
rows=[
('F1',59,148,'真实 Request CancelScope 吞外部取消令 finally gather 请求挂起','阻断级 / 高危','finding'),
('F2',152,165,'前台正常退出仍可能清理未确认而报告失败','中危 / 行为变更','finding'),
('F3',167,177,'浏览器满载由淘汰最旧变为硬拒绝','中危 / 行为变更','finding'),
('F4',179,181,'非 Linux 不提供进程组归属保证但非回归','低危 / 非 Linux 降级','finding'),
('F5',183,185,'废弃 UserJourneyVerifier 与旧资源命名耦合','低危 / 死代码','finding'),
('F6',187,189,'Request=None 类型标注不严谨、功能无影响','低危 / 健壮性','finding'),
('T1',136,140,'断连 watcher 缺真实 Request/ASGI 覆盖','未分级','finding'),
('T2',230,254,'生命周期用例出现间歇失败但50次未再现','需关注','finding'),
('T3',205,206,'未改模块单跑失败即判既有且非本次因果','未分级','finding'),
('Q1',271,271,'browser 多状态并发与 shield 理解维护成本高','未分级','finding'),
('Q2',272,273,'平台契约需明确且行为变化未在提交中声明','未分级','finding'),
('Q3',276,278,'提交 why 导向但缺行为兼容性说明','未分级','finding'),
('A1',17,19,'Java 正常链路基本安全且修改测试通过','未分级','positive_assurance'),
('A2',129,129,'正常多秒级 browser journey 通常安全','未分级','positive_assurance'),
('A3',265,265,'Java PID/归属/租约/CAS 重试逻辑严谨','未分级','positive_assurance'),
('A4',266,267,'Python 并发边界建模细致、回归测试针对性好','未分级','positive_assurance'),
('A5',330,330,'Java 进程验证链路基本安全','未分级','positive_assurance'),
]
notes={
'F1':['报告附录 E08 L311 明确构造“仅 mock browser_service 的 app（jdelay.py）”；这证明使用真实HTTP与uvicorn不等于使用真实 main.app 全中间件。现存 main.py:104-105 有 HTTP middleware，报告未展示其是否包含在探针中。需由主审核验真实中间件下机制适用性。',
      'L108-115 是 curl -m 4 超时返回 HTTP000 的报告内记录；单凭有限4秒观察不足以证明数学意义永久挂死。报告另称任务栈与取消机制，但源/原始日志目前不可见，不能在本提取中认证。',
      'L131 缓存页面/截图极快完成与 L105 无任何await的 mocked创建并非同一执行条件；真实 Playwright RPC 通常含异步让步，不能仅凭5/5人工时序样本外推生产发生率。该问题是否实际成立留待主审新复现裁定。'],
'F2':['L164只陈述updateStateFromSnapshot被跳过，未像部分报告进一步声称cwd/env必丢失；不应给它套用其它报告的错误因果。L156的setsid逃逸若根本未被发现，也可能返回确认假阳性而非未确认，应限定是否已被known追踪。'],
'F6':['修复建议 Optional[Request] 本身没有运行验证；FastAPI 对 Request 参数有特殊注入规则，不能只凭静态类型风格保证修改方案可直接使用。现状“运行功能无影响”是报告保证，待主审统一判定。'],
'T1':['内部矛盾：E08 L19称没有一条测试传http_request，L137称全部单参；L138随即承认SimpleNamespace假对象。源码 test_journey_lifecycle.py:143-145确实以第二参数传入http_request。准确缺口是没有真实Request/ASGI栈覆盖，不能说从不创建watcher。'],
'T2':['报告诚实承认后续50次未稳定复现（L254）；不能把这些失败直接归因为F1或唯一已证根因，原始日志未命名路径。'],
'T3':['未改文件与HEAD单独运行仍失败不足以完成父提交A/B因果排除。报告未给该失败在父提交执行结果；“既有/非本次引入”应降为线索。'],
'A1':['L19“没有一条测试真正传入http_request”与本报告L138及仓库测试矛盾。OwnedProcessTest的L215“15/15（6 skipped）”应读作15 collected、9 executed，不能称15全部执行通过。'],
'A2':['此保证没有真实 main.app 各中间件的运行证据；与F1随机时间窗本身也只能做条件概率描述，而非可靠无回归保证。'],
}
for cid,a,b,title,sev,kind in rows:
    ls=lines[a-1:b];excerpt='\n'.join(ls)
    nonempty=[x.strip() for x in ls if x.strip() and not x.startswith('```')]
    quote=next((x for x in nonempty if not x.startswith('#')),nonempty[0])
    category='positive_assurance' if kind=='positive_assurance' else ('test_coverage_or_quality_observation' if cid.startswith('T') else 'documentation_maintainability_observability_suggestion' if cid.startswith('Q') or cid in ('F5','F6') else 'defect_or_behavior_change_candidate_requires_adjudication')
    r['claims'].append({'claim_id':f'E08:{cid}','original_id':cid,'kind':kind,'title':title,'original_severity':sev,
      'report_citation':f'E08.md:L{a}-L{b}','report_line_start':a,'report_line_end':b,'short_original_quote':quote[:240],
      'full_original_excerpt':excerpt,'source_locations_as_reported':list(dict.fromkeys(re.findall(r'`([^`\n]*(?:\.java|\.py|\.yml|\.md|\.html)[^`\n]*)`',excerpt))),
      'test_claim_in_excerpt':[x.strip() for x in ls if re.search(r'实测|探针|复现|测试|passed|failed|skipped|Tests run|通过|Test\b',x)],
      'evidence_visibility_ref':'E08.artifacts','cross_report_disclosure_ref':'E08.cross_report_disclosure','audit_notes':notes.get(cid,[]),
      'audit_status':'未运行测试；仅中性提取与静态一致性审计。主审正在另行复现真实 main.app 行为。','scoring_category':category,
      'scoring_note':'提取项不是已裁定缺陷；不可直接以全部项或重复子项作为缺陷得分分母。'})
r['testing_claims']=[{'citation':f'E08.md:L{a}-L{b}','original_excerpt':'\n'.join(lines[a-1:b])} for a,b in [(28,34),(103,126),(197,258),(299,321)]]
r['artifacts']=[];newindex=[]
for name,line in [('/tmp/jdelay.py',311),('/tmp/mech.py',122)]:
    p=Path(name);a={'path_as_reported':name,'resolved_path':name,'kind':'probe_source','report_citation':f'E08.md:L{line}',
        'currently_exists':p.exists(),'readable_file':p.is_file(),'size_bytes':p.stat().st_size if p.is_file() else None,
        'visibility_interpretation':'当前可见；未通过执行重现认证来源' if p.exists() else '当前未找到；不能据此推断当时未执行或捏造'}
    entry={'report_id':'E08','original_path':name,'report_citation':a['report_citation']}
    if p.is_file():
        dst=outdir/'submitted_evidence/E08'/str(p).lstrip('/');dst.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(p,dst)
        sha=hashlib.sha256(dst.read_bytes()).hexdigest();a.update(preserved_copy=str(dst),sha256=sha)
        entry.update(preserved_path=str(dst),sha256=sha,bytes=dst.stat().st_size,kind='probe_source')
    else:entry['status']='not_currently_visible'
    r['artifacts'].append(a);newindex.append(entry)
r['report_level_audit']=[
 '核心hang有详细文字机制与curl命令；但/tmp/jdelay.py和/tmp/mech.py当前均不存在，且未给原始uvicorn/curl/机制日志具体路径，需以新的独立复现认证。缺失不等于捏造。',
 '“真实HTTP/真实uvicorn/真实路由”不自动等价于生产main.app：L311明示重新构造app，未说明包含main.py中间件。',
 'L19/L137说没有任何传http_request测试，与L138及源码143-145矛盾；有效缺口是缺真实Starlette Request覆盖。',
 'Java全量失败归既有（L205-206）只凭未改模块和HEAD单跑失败，未呈现父提交运行对照。',
 'Python全量229通过、早期间歇失败、后续50次通过属于不同轮次；报告有区分，但未提供逐轮原始日志索引。',
 'E08有审查日期和平台/依赖版本宣称，没有审查总耗时、token/成本/模型详细配置，不可纳入统一速度成本结论。'
]
r['runtime_cost_config_audit']={'status':'不可据报告统一核算','detail':'L8-L9仅环境/版本，L7为日期；无总耗时、token/账单、模型版本/推理强度/采样/上下文等。5次curl与50次测试次数不是成本指标。'}
data['reports']=[x for x in data['reports'] if x['report_id']!='E08']+[r]
data['scope']='同轮只读审计 E01-E08；E01-E07结构化项保持不变；新增E08。静态核查直接引用源码/证据可见性，未运行任何测试。'
data['claim_count']=sum(len(x['claims']) for x in data['reports'])
(outdir/'claims.json').write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n')
ip=outdir/'submitted_evidence/index.json';idx=json.loads(ip.read_text());idx['entries']=[x for x in idx['entries'] if x['report_id']!='E08']+newindex
idx['updated_at']=datetime.datetime.now().astimezone().isoformat();ip.write_text(json.dumps(idx,ensure_ascii=False,indent=2)+'\n')
md=outdir/'claim_audit.md';text=md.read_text();text=text.replace('# 七份报告逐项主张与证据审计','# 同轮八份报告逐项主张与证据审计',1)
text+='\n\n## E08 同轮追加：opencode\n\nE01–E07 的逐项内容保持原样；本次按用户追加请求纳入 E08，仍为同一轮。E08 共提取17项，含6项主要发现、测试/因果/维护性项及5项正向保证。当前总数为 '+str(data['claim_count'])+'；条目数量不是有效缺陷分母或质量得分。\n\n'
text+='### 证据与方法\n\nE08 L59–148 详述 Request CancelScope 吞取消→finally gather挂起；L103–126 自报真实uvicorn、curl、5/5复现和机制实验。附录L311明示是重新构造仅mock browser_service的app；是否含真实main.app中间件没有显示。主审正在独立验证真实应用栈，提取审计不替代该实验。\n\n'
text+='`/tmp/jdelay.py`（E08 L311）和`/tmp/mech.py`（E08 L122/L321）当前均未找到；已在同一 submitted_evidence/index.json 标记状态，无文件可复制或计算SHA。没有给出原始日志路径。该事实仅限制可复核性，不构成捏造判断。\n\n'
text+='| 主张 | 原始级别 | 报告行号 | 审计提示 |\n|---|---|---|---|\n'
for c in r['claims']:
    audit='；'.join(c['audit_notes']) or '中性提取；未运行测试，待主审裁决'
    text+=f'| {c["original_id"]}：{c["title"]} | {c["original_severity"]} | L{c["report_line_start"]}–L{c["report_line_end"]} | {audit.replace("|"," / ")} |\n'
text+='\n'+ '\n'.join('- '+x for x in r['report_level_audit'])+'\n';md.write_text(text)
print(json.dumps({'E08_claims':len(r['claims']),'all_claims':data['claim_count'],'E08_artifacts':r['artifacts']},ensure_ascii=False))
