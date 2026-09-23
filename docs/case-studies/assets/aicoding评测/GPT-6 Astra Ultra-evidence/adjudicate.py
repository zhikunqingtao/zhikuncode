#!/usr/bin/env python3
"""Apply disclosed cross-review corrections; no score calculation occurs here."""
import json, pathlib, hashlib, datetime, subprocess
P=pathlib.Path(__file__).resolve().parent
ROOT=P.parents[4]
def read(n):return json.loads((P/n).read_text())
def write(n,v):(P/n).write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n')
reports=sorted(sum([read('claims/group-'+g+'.json') for g in 'abc'],[]),key=lambda r:r['report_id'])
claims={c['claim_id']:c for r in reports for c in r['claims']}
changes=[]
def change(cid,reason,**kw):
 c=claims[cid];before={k:c.get(k) for k in kw};c.update(kw);c['reason']+='；统一复核：'+reason
 changes.append(dict(claim_id=cid,before=before,after=kw,reason=reason))
def decision(rid,key,points,reason):
 r=next(r for r in reports if r['report_id']==rid);d=r['decisions'][key];changes.append(dict(report_id=rid,decision=key,before=d.copy(),after=dict(points=points,reason=reason)));d.update(points=points,reason=reason)
for cid,verdict,reason in [
 ('R02-C07','证据不足','缺乏epoch变化后仍preparing且无worker的可达路径；明确条件风险不罚FP。'),
 ('R05-C11','范围外','当前cancel返回Operation；报告明确假设204/空体，属未采用协议的防御建议。'),
 ('R05-C29','范围外','明确假设异常旧DB绕过UNIQUE，不是支持内正常运行态。'),
]:change(cid,reason,category='conditional',verdict=verdict,severity=None,credit=None)
change('R05-C17','聚合sourceId空是事实，完整evidence链仍在；单行归属的规范含义不清，不罚资料丢失FP。',category='engineering',verdict='规范歧义',severity=None,credit=None)
change('R10-C02','坏绑定fail-closed有规范依据，不能与受支持的小预算错误通道混同。',category='conditional',verdict='规范歧义',severity=None,credit=None)
change('R11-C02','固定4096字符pieces/8192 batch及hash不依赖模型；与真实1536阈值问题触发不同，明确机制已证伪。',category='functional',verdict='不成立',severity='moderate',credit=0,verification='static')
for cid in ['R02-C02','R05-C01','R13-C15','R03-C02','R11-C03','R14-C04']:
 change(cid,'合成原件不变，启用Jackson现有ALLOW_SINGLE_QUOTES后已可解析，恢复仍在读raw前被缺ref挡住；提高物化上限的正控制能恢复。仅确认这类关联协议缺口，损坏资料允许暂停，不采用永久/所有恢复失效的泛化。',category='functional',verdict='成立',severity='moderate',credit=1,verification='reproduced')
change('R02-C20','有效WebP在当前macOS/JDK环境有.webp后缀时MIME通过，a_hash无扩展副本被拒；默认ImageIO无WebP reader。确认此环境和副本路径，不证明所有平台永远拒绝，也未证明有后缀即完成视觉注入。',verdict='成立',severity='moderate',credit=1,verification='reproduced')
for cid in ['R14-C14','R17-C08']:
 change(cid,'同R02/R05/R13：真实tiny预算触发正确，但QueryEngine捕获project异常并返回error，REST控制器正常ResponseEntity.ok；不是异常穿透/必然500。按同规则半命中并半份归因错误。',credit=0.5,verification='reproduced')
change('R14-C42','真实约12MiB PNG获工具成功image_ref后未注入。最早实际限制是1,500,000 Base64字符预算，另有10MiB文件上限，均低于工具20MiB。报告指出真实阈值不兼容，未声称10MiB检查先执行，完整命中。',verification='reproduced')
for cid in ['R03-C07','R05-C33','R13-C30']:
 change(cid,'统一按其具体指控的可证明后果计轻微：错误分类/状态展示，未声称资料毁损；不沿用作者P级。',severity='minor')
for cid in ['R06-C13','R08-C05']:
 change(cid,'概括性UI符合保证只保留事实裁定，漏报由FN体现，不加整体判断FP。',severity=None,credit=None)
decision('R13','release',5,'原文597明确现在还不能发，602要求真实语义验收；允许阶段提交不扣分，缺陷漏审不在发布维重复罚。')
decision('R17','boundary',5,'全文38—54门控与64全局迁移说明公共改动；与R03/R10同标准。零影响措辞过满，不当作绝对零风险证明。')
for rid in ['R04','R09','R10','R16']:
 decision(rid,'release',0,'未明确回答当前能否作为功能完成版合入发布；允许提交/推送和披露语义未验收本身并非错误，不据此捏造赞成正式发布。')
decision('R12','release',0,'原文234将语义验收是否属于发布标准条件化，未明确作出当前完成版暂不可发布结论。')
for rid in ['R08','R17']:
 decision(rid,'release',0,'明确建议发布/达到发布标准，与尚存确认缺陷和未完成真实语义验收矛盾。')
change('R09-C03','日志记paused与持久cancelled不一致，但只是诊断措辞，未改变功能状态；工程项不作产品缺陷。',category='engineering',severity=None,credit=None)
change('R03-C28','基线b98已对所有终态停止查询，删除E后旧入口打开失败的用户后果已存在；新targetAvailable首验/重开还改善，不计本次新增回归。',scope='retained',severity=None,credit=None)
aliases={
 'blocked-gap-ref-protocol':'blocked-gap-recovery-protocol',
 'recovered-raw-classification-bypass':'recovery-archive-fields',
 'blocked-gap-record-association':'blocked-gap-recovery-protocol',
 'terminal-pending-hides-active':'terminal-cache-blocks-active',
 'polling-disables-controls':'polling-blocks-controls',
 'handoff-small-budget-error-channel':'handoff-small-budget-error',
 'handoff-budget-error-mapping':'handoff-small-budget-error',
 'handoff-authorization-error-classification':'handoff-error-code-flattening',
 'handoff-budget-error-code':'handoff-error-code-flattening',
 'cancelled-source-lock-display':'nonpreparing-source-locks',
 'source-lock-status-gate':'nonpreparing-source-locks',
 'merge-notification-key-dedup':'duplicate-merge-toast',
}
# Tuple: severity, observable boundary, local reproduction source (None = static only).
meta={
 'recovery-archive-fields':('serious','恢复投影将thinking/供应商私有字段送入提取；与首次过滤不一致。','MergeReviewReproTest.java'),
 'legacy-tool-result-omission':('serious','顶层toolUseResult未入版本哈希/文字索引；原checkpoint容器仍保留，不能称原件彻底丢失。','MergeReviewReproTest.java'),
 'snapshot-intermediate-symlink':('serious','历史文件引用的中间目录symlink穿过允许的来源目录边界，复制合成外部文件。','RankingProtocolReproTest.java'),
 'blocked-gap-recovery-protocol':('moderate','已修正解析配置仍被gap/ref关联校验阻止；不等于所有坏原件应可恢复。','RankingProtocolReproTest.java'),
 'noncritical-nonutf8-attachment-blocks-merge':('moderate','已保存非关键GBK日志原件已复制，仍因不能UTF8解析阻断合并。','RankingProtocolReproTest.java'),
 'model-switch-orphaned-aggregate-unit':('moderate','token估算变化越过1536阈值时遗留既有pending聚合单元；换回条件可恢复。','RankingProtocolReproTest.java'),
 'terminal-cache-blocks-active':('moderate','本地旧终态遮蔽服务端新active；关闭旧结果可绕过。','sessionMergeReviewRepro.test.ts'),
 'polling-blocks-controls':('moderate','轮询复用submitting禁用恢复/取消，程序cancel也等待GET结束；不表示后端永久不能取消。','rankingProtocolRepro.test.ts'),
 'handoff-small-budget-error':('moderate','E最小入口超预算走通用QueryResult.error，未走容量错误通道；不是必然HTTP500。','RankingQueryReproTest.java'),
 'invalid-external-reference-path':('moderate','历史外部引用含NUL时Path.of抛错，中止封存而不是记外部缺口。','RankingProtocolReproTest.java'),
 'webp-handoff-asset':('moderate','有效WebP封存到无扩展副本后，在已测环境失去MIME识别。','RankingAssetReproTest.java'),
 'handoff-image-size-mismatch':('moderate','约12MiB合法PNG获成功image_ref但视觉未注入；tool/injector上限不一致。','RankingAssetReproTest.java'),
 'handoff-error-code-flattening':('minor','授权/读取支路把预算错误码包成INVALID_RESOURCE等泛码；read入口中断反例正常重抛。','RankingReadReproTest.java'),
 'copied-file-counted-missing':('minor','已复制托管文件还被计外部缺口；资料本体存在。','MergeReviewReproTest.java'),
 'nonpreparing-source-locks':('minor','cancelled/paused短窗口真实锁仍在但selector隐藏；后端gate保护仍在。','rankingProtocolRepro.test.ts'),
 'terminal-target-availability-stale':('minor','已校验终态不刷新，外部删除E后targetAvailable陈旧；重新打开可刷新，实际打开会核验。',None),
 'search-snippet-surrogate-boundary':('minor','搜索摘录UTF16 substring可能切断合法emoji；原文不变。','RankingReadReproTest.java'),
 'embedded-copy-quota-error-code':('minor','内嵌图片复制配额报通用准备失败；普通文件相同配额有明确COPY_INCOMPLETE。','RankingReadReproTest.java'),
 'merge-model-unavailable-classification':('minor','操作保存后模型能力配置不可用，worker重新select抛中文异常被压成通用准备失败。',None),
 'merge-warning-details-unavailable':('minor','操作warnings仅总数，合并面板无法展示缺口明细；包中原记录仍在。',None),
 'control-html-error-message':('minor','控制接口非JSON错误响应显示JSON解析异常，掩盖可操作错误信息。',None),
 'cancel-publish-conflict-code':('minor','读取cancel状态后publish抢先完成，CAS失败被映射STALE而非ALREADY_COMPLETED；原子性仍在。',None),
 'cancel-log-says-paused':('minor','已取消worker捕获异常后仍记Merge paused日志，实际持久状态保持cancelled。',None),
 'duplicate-merge-toast':('minor','5秒通知仍在期间，新增paused→resume→paused可推送同key通知两份。','rankingProtocolRepro.test.ts'),
}
# Notification scope requires newly reachable v2 path, not merely unchanged push helper.
change('R05-C27','旧push助手未改，但v2新增恢复后再暂停让同一operation同key再次通知可达；与R12/R14/R16统一。',verdict='成立',scope='new',severity='minor',credit=1)
for c in claims.values():
 c['original_issue_key']=c['issue_key']; c['issue_key']=aliases.get(c['issue_key'],c['issue_key'])
 if c['category']=='functional' and c['verdict']=='成立' and c['scope']=='new':
  sev,boundary,repro=meta[c['issue_key']]
  if c['severity']!=sev:changes.append(dict(claim_id=c['claim_id'],before=dict(severity=c['severity']),after=dict(severity=sev),reason='按统一缺陷后果与恢复条件评级，见defects.json。'))
  c['severity']=sev;c['verified_boundary']=boundary
  c['evidence']=['reproductions/'+repro] if repro else ['代码静态核验：见code_refs']
 else:c['evidence']=['代码静态核验及原文：见code_refs/lines']
 c['score_eligible']=c['category']=='functional' and c['scope']=='new' and c['verdict'] in ['成立','不成立'] and c['severity'] in ['serious','moderate','minor']
keys=[k for k in meta if k not in ['terminal-target-availability-stale','cancel-log-says-paused']]; defects=[]
for i,k in enumerate(keys,1):
 matches=[c for c in claims.values() if c['score_eligible'] and c['verdict']=='成立' and c['issue_key']==k]
 assert matches,k
 s,b,e=meta[k]; refs={ (ref['path'],ref['line']) for c in matches for ref in c['code_refs'] }
 defects.append(dict(id='D%02d'%i,key=k,severity=s,boundary=b,title=matches[0]['title'],claims=[c['claim_id'] for c in matches],code_refs=[dict(path=p,line=l) for p,l in sorted(refs)],evidence=['reproductions/'+e] if e else ['static'],verification='reproduced' if e else 'static'))
for defect in defects:
 if defect['key']=='handoff-small-budget-error':defect['title']='合并入口预算不足未走容量错误通道'
 if defect['key']=='invalid-external-reference-path':defect['title']='非法外部引用路径中止整个封存'
write('claims/adjudication-changes.json',changes);write('claims/unified.json',reports);write('defects.json',defects)
write('adjudication-freeze.json',dict(frozen_at_utc=datetime.datetime.now(datetime.timezone.utc).isoformat(),note='首次算分前冻结；后续事实修正另留记录，不更改计分规则。',sha256={n:hashlib.sha256((P/n).read_bytes()).hexdigest() for n in ['claims/unified.json','defects.json','claims/adjudication-changes.json']}))
print('Unified',len(reports),'reports,',len(claims),'claims,',len(defects),'defects; no ranking calculated.')
