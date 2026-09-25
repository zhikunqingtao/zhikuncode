"""Recompute all rankings from explicit evidence judgments; Python standard library only.
Usage: python3 recompute_scores.py [evaluation_data.json]
This program never changes the reviewed repository or the submitted reports.
"""
from pathlib import Path
import json, sys, itertools
P=Path(sys.argv[1]) if len(sys.argv)>1 else Path(__file__).with_name('evaluation_data.json')
D=json.loads(P.read_text()); W=D['weights']; O=D['opportunities']
scenarios={
 '基准':list(W.values()),
 '发现优先':[40,20,10,15,10,5],
 '准确与校准优先':[20,30,20,10,12,8],
 '验证优先':[25,20,10,25,12,8],
 '建议安全优先':[25,25,15,10,10,15],
}
reports=[]
for r in D['reports']:
 ds={'D1':W['D1']*sum(g['weight']*g['credits'][r['id']] for g in O)/sum(g['weight'] for g in O)}
 units=[u for u in r['fact_units'] if u.get('credit') is not None]
 den=sum(u['weight'] for u in units)
 ds['D2']=W['D2']*sum(u['weight']*u['credit'] for u in units)/den
 for k,items in r['rubrics'].items(): ds[k]=sum(x[0] for x in items)
 assert all(0<=v<=W[k] for k,v in ds.items())
 reports.append(dict(id=r['id'],name=r['name'],dimensions=ds,total=sum(ds.values()),accuracy_denominator=den,accuracy_numerator=sum(u['weight']*u['credit'] for u in units)))
order=sorted(reports,key=lambda r:(-r['total'],r['id']))
for rank,r in enumerate(order,1): r['rank']=rank
sensitivity={}
for name,weights in scenarios.items():
 assert sum(weights)==100
 rows=[dict(id=r['id'],name=r['name'],score=sum(r['dimensions'][k]/W[k]*w for k,w in zip(W,weights))) for r in reports]
 sensitivity[name]=sorted(rows,key=lambda x:(-x['score'],x['id']))
# Deterministic 3^6 grid around weights, normalized to 100. Not a distribution of product performance.
rank_ranges={r['id']:[8,1] for r in reports}
win_counts={r['id']:0 for r in reports}
for factors in itertools.product([.8,1,1.2],repeat=6):
 weighted=[W[k]*v for k,v in zip(W,factors)]; scale=100/sum(weighted)
 scored=sorted([(sum(r['dimensions'][k]/W[k]*v*scale for k,v in zip(W,weighted)),r['id']) for r in reports],reverse=True)
 for i,(_,eid) in enumerate(scored,1):
  rank_ranges[eid][0]=min(rank_ranges[eid][0],i); rank_ranges[eid][1]=max(rank_ranges[eid][1],i)
 win_counts[scored[0][1]]+=1
# Explicit, not statistical, sensitivity to subjective rubric anchors +/-0.5 per sub-item.
# Only display a score-change envelope, not a confidence interval or random probability.
judgment_bounds={}
for r in D['reports']:
 low=high=0
 for k,items in r['rubrics'].items():
  cap=5 if k in ('D3','D4') else 3 if k=='D5' else 4
  low+=sum(max(0,s-.5) for s,_ in items); high+=sum(min(cap,s+.5) for s,_ in items)
 exact=next(t for t in reports if t['id']==r['id'])
 judgment_bounds[r['id']]=[exact['dimensions']['D1']+exact['dimensions']['D2']+low,exact['dimensions']['D1']+exact['dimensions']['D2']+high]
# These are post-adjudication stress checks, not alternate official scores.
# Apply every changed rule uniformly to all eight reports.
judgment_scenarios={}
for scenario in ['所有D1半档改零','所有D1半档改满','G08影响权重5改3','G08影响权重5改7','D2事实族等权','去除八方均漏G07']:
 rows=[]
 for r in D['reports']:
  current=next(t for t in reports if t['id']==r['id'])
  value=current['total']
  if scenario=='D2事实族等权':
   units=[u for u in r['fact_units'] if u.get('credit') is not None]
   value+=20*sum(u['credit'] for u in units)/len(units)-current['dimensions']['D2']
  else:
   num=den=0
   for g in O:
    if scenario=='去除八方均漏G07' and g['id']=='G07': continue
    weight=g['weight']; credit=g['credits'][r['id']]
    if scenario=='所有D1半档改零' and credit==.5: credit=0
    if scenario=='所有D1半档改满' and credit==.5: credit=1
    if g['id']=='G08' and scenario=='G08影响权重5改3': weight=3
    if g['id']=='G08' and scenario=='G08影响权重5改7': weight=7
    num+=weight*credit; den+=weight
   value+=30*num/den-current['dimensions']['D1']
  rows.append(dict(id=r['id'],name=r['name'],score=value))
 judgment_scenarios[scenario]=sorted(rows,key=lambda x:(-x['score'],x['id']))
result={'rankings':order,'scenarios':sensitivity,'weight_grid':{'count':729,'range':rank_ranges,'first_count':win_counts,'interpretation':'只表示这些指定权重组合下的位置，绝非胜率或统计置信度'},'subjective_anchor_envelope':judgment_bounds,'judgment_scenarios':judgment_scenarios}
P.with_name('scores_computed.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
for r in order: print(f"{r['rank']} {r['name']} {r['total']:.2f} {r['dimensions']}")
print('Scenarios:')
for n,rs in sensitivity.items(): print(n,' > '.join(r['name'] for r in rs))
print('Weight grid rank ranges:',rank_ranges)
