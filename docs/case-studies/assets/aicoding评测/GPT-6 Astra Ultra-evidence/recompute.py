#!/usr/bin/env python3
"""Python 3 standard library only. Recompute all scores from published adjudications."""
import csv, hashlib, io, itertools, json, pathlib, re
from decimal import Decimal, ROUND_HALF_UP
from collections import Counter
from urllib.parse import quote
P=pathlib.Path(__file__).resolve().parent;ROOT=P.parents[4]
(P/'results').mkdir(exist_ok=True)
def read(n): return json.loads((P/n).read_text())
def dump(n,x): (P/n).write_text(json.dumps(x,ensure_ascii=False,indent=2)+'\n')
def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()
def round1(x):return str(x.quantize(Decimal('.1'), rounding=ROUND_HALF_UP))
def D(x):return Decimal(str(x))
def link(path,line=None):
 import os
 return quote(os.path.relpath(ROOT/path,P),safe='/')+(f'#L{line}' if line else '')
freeze=read('rules-freeze.json');assert sha(P/'rules.md')==freeze['sha256'],'Scoring rules changed'
for n,h in read('adjudication-freeze.json')['sha256'].items():assert sha(P/n)==h,f'Adjudication changed: {n}'
for f in read('manifest.json')['files']:assert sha(ROOT/f['path'])==f['sha256'],f"Frozen input changed: {f['path']}"
reports=read('claims/unified.json');catalog=read('defects.json');info={r['id']:r for r in read('reports.json')}
assert len(reports)==len(info)==17 and len({r['report_id'] for r in reports})==17
assert len({c['claim_id'] for r in reports for c in r['claims']})==sum(len(r['claims']) for r in reports)
bykey={b['key']:b for b in catalog};categories=['normal_function','boundary','release','semantic']
for r in reports:
 assert r['read_complete'] and set(r['decisions'])==set(categories)
 for v in r['decisions'].values():assert v['points'] in (0,5) and v['quote'] and v['reason']
 source_lines=(ROOT/info[r['report_id']]['path']).read_text().splitlines()
 for c in r['claims']:
  normalize=lambda s: re.sub(r'\s+','',s)
  assert normalize(c['quote']) in normalize('\n'.join(source_lines[c['lines'][0]-1:c['lines'][-1]])), 'Quote span mismatch: '+c['claim_id']
  for ref in c['code_refs']:
   assert (ROOT/ref['path']).is_file() and 1 <= ref['line'] <= len((ROOT/ref['path']).read_text().splitlines())
  assert c['quote'] and c['lines'][0]>0 and c['lines'][-1]<=info[r['report_id']]['lines']
  if c['score_eligible']:
   assert c['category']=='functional' and c['scope']=='new' and c['verdict'] in ['成立','不成立'] and c['severity'] in ['serious','moderate','minor']
   if c['verdict']=='成立':assert c['issue_key'] in bykey and c['credit'] in (0.5,1) and c['severity']==bykey[c['issue_key']]['severity']
   else:assert c['credit']==0

def contributions(r,weights):
 tpby={};fpby={};claimsby={}
 for c in r['claims']:
  if not c['score_eligible']:continue
  k=c['issue_key'];w=D(weights[c['severity']]);credit=D(c['credit'])
  if c['verdict']=='成立':
   tpby[k]=max(tpby.get(k,D(0)),w*credit)
   # A partial attribution error is one FP source per defect, not per sentence.
   if credit<1:fpby['partial:'+k]=max(fpby.get('partial:'+k,D(0)),w*(1-credit))
  else:fpby[k]=max(fpby.get(k,D(0)),w)
  claimsby.setdefault(k,[]).append(c['claim_id'])
 # A later explicit correction would have been adjudicated as withdrawn and excluded.
 tp=sum(tpby.values(),D(0));fp=sum(fpby.values(),D(0));total=sum((D(weights[b['severity']]) for b in catalog),D(0))
 assert D(0)<=tp<=total
 return tp,fp,total-tp,tpby,fpby,claimsby

def scenario(beta,weight_tuple,share):
 weights=dict(zip(['serious','moderate','minor'],weight_tuple));rows=[]
 for r in reports:
  tp,fp,fn,tpm,fpm,cm=contributions(r,weights);b=D(beta)**2
  f=(1+b)*tp/((1+b)*tp+b*fn+fp) if tp else D(0)
  ds=sum(D(v['points']) for v in r['decisions'].values());judge=ds*D(100-share)/20
  rows.append(dict(report_id=r['report_id'],label=info[r['report_id']]['label'],score=round1(D(share)*f+judge),defect_score=round1(D(share)*f),judgement_score=str(judge),TP=str(tp),FP=str(fp),FN=str(fn),precision=str(tp/(tp+fp) if tp+fp else D(0)),recall=str(tp/(tp+fn) if tp+fn else D(0)),F=str(f),hits={bykey[k]['id']:str(v/D(weights[bykey[k]['severity']])) for k,v in tpm.items()},errors={k:str(v) for k,v in fpm.items()},decisions={k:r['decisions'][k]['points'] for k in categories},excluded=sum(not c['score_eligible'] for c in r['claims']),uncertain=sum(c['verdict'] in ['证据不足','规范歧义'] for c in r['claims']),partial=sum(v=='0.5' for v in [str(x/D(weights[bykey[k]['severity']])) for k,x in tpm.items()]),verdict_counts=dict(Counter(c['verdict'] for c in r['claims']))))
 rows.sort(key=lambda r:(-D(r['score']),r['report_id']))
 for i,r in enumerate(rows):r['rank']=i+1 if not i or r['score']!=rows[i-1]['score'] else rows[i-1]['rank']
 return rows
main=scenario(1,(4,2,1),80);dump('results/main.json',main)
scenarios=[]
for beta,wt,share in itertools.product((.5,1,2),((4,2,1),(3,2,1),(2,1,1)),(70,80,90)):
 scenarios.append(dict(id=f'beta={beta};weights={"-".join(map(str,wt))};defects={share}',beta=beta,weights=wt,defect_share=share,rows=scenario(beta,wt,share)))
assert len(scenarios)==27
dump('results/sensitivity.json',scenarios)
ranges={rid:dict(min_rank=min(r['rank'] for s in scenarios for r in s['rows'] if r['report_id']==rid),max_rank=max(r['rank'] for s in scenarios for r in s['rows'] if r['report_id']==rid)) for rid in info}
reversals=[]
for a,b in itertools.combinations(info,2):
 directions={}
 for s in scenarios:
  m={r['report_id']:D(r['score']) for r in s['rows']};delta=m[a]-m[b];direction=1 if delta>0 else -1 if delta<0 else 0;directions.setdefault(direction,[]).append(s['id'])
 if 1 in directions and -1 in directions:reversals.append(dict(a=a,b=b,a_above= directions[1],b_above=directions[-1],ties=directions.get(0,[])))
dump('results/stability.json',dict(rank_ranges=ranges,reversals=reversals,note='27个权重情景，不是抽样置信度。'))
for name,rows in [('main',main),('sensitivity',[dict(scenario=s['id'],**r) for s in scenarios for r in s['rows']])]:
 fields=(['scenario'] if name=='sensitivity' else [])+['rank','report_id','label','score','defect_score','judgement_score','TP','FP','FN','partial','uncertain','excluded']
 with (P/'results'/f'{name}.csv').open('w',newline='') as f:
  w=csv.DictWriter(f,fieldnames=fields,extrasaction='ignore');w.writeheader();w.writerows(rows)
# Full public traceability, including unscored claims. Quotes remain verbatim inside code blocks.
lines=['# 全量核验台账','', '原始三组逐条阅读记录保留；本文件由统一裁定生成。成立不等于入分：测试建议/整体判断等按规则排除。原文摘录内可能含原报告本机路径，仅作引文，点击定位使用每条上方仓库相对链接。','']
for r in reports:
 rid=r['report_id'];src=info[rid]['path'];lines +=[f'## {rid}', '',f'[完整原报告]({link(src)})','']
 for key,v in r['decisions'].items():lines +=[f'### {rid}-{key}', '',f"**判断分：{v['points']}/5**。[原文 L{v['lines'][0]}–L{v['lines'][-1]}]({link(src,v['lines'][0])})。{v['reason']}", '', '````text',v['quote'],'````','']
 for c in r['claims']:
  label=bykey[c['issue_key']]['id'] if c['issue_key'] in bykey else c['issue_key']
  lines +=[f"### {c['claim_id']}",'',f"**{c['title']}** — {c['verdict']} / {c['category']} / scope={c['scope']} / {'入分' if c['score_eligible'] else '不入分'}；统一问题：{label}；credit={c['credit']}。",'',f"[原文 L{c['lines'][0]}–L{c['lines'][-1]}]({link(src,c['lines'][0])})",'', '````text',c['quote'],'````','',f"触发：{c['trigger']}",f"预期：{c['expected']}",f"实际：{c['actual']}",'',f"判定理由：{c['reason']}",'']
  if c.get('verified_boundary'):lines +=['核实范围：'+c['verified_boundary'],'']
  lines +=['代码：'+'；'.join(f"[{x['path'].split('/')[-1]}:{x['line']}]({link(x['path'],x['line'])})" for x in c['code_refs']),'', '核验：'+'；'.join(f'[{e}]({quote(e,safe="/")})' if e.startswith('reproductions/') else e for e in c['evidence']), '']
(P/'claim-ledger.md').write_text('\n'.join(lines))
lines=['# 命中矩阵','', '1=完整命中；0.5=触发与后果正确但明确归因错误；—=未命中。错误指控另外计FP。','', '|缺陷|权重|'+ '|'.join(info)+'|','|---|---|'+ '|'.join(['---']*17)+'|']
mm={r['report_id']:r for r in main}
for b in catalog:lines.append('|'+b['id']+' '+b['boundary']+'|'+str(dict(serious=4,moderate=2,minor=1)[b['severity']])+'|'+'|'.join(mm[rid]['hits'].get(b['id'],'—') for rid in info)+'|')
(P/'results'/'hit-matrix.md').write_text('\n'.join(lines)+'\n')
print('Recomputed 17 reports / 27 scenarios; weighted defect total',main[0]['TP'], '+ FN',main[0]['FN'])
for r in main:print(r['rank'],r['report_id'],r['score'],r['TP'],r['FP'],r['FN'])
