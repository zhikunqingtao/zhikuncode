from pathlib import Path
from collections import Counter,defaultdict
import hashlib,json,re,datetime
root=Path('/Users/guoqingtao/Desktop/AICoding评测0925'); out=Path(__file__).parent
mirrors=['评审结果_20260925_v1/evidence/java/source-head/','评审结果_20260925_v1/evidence/java/source-parent/','评审结果_20260925_v1/evidence/python/source/','评审结果_20260925_v1/evidence/python/parent/']
patterns={
'private_key':re.compile(rb'-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----'),
'github_token':re.compile(rb'\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,})\b'),
'aws_access_id':re.compile(rb'\b(?:AKIA|ASIA)[A-Z0-9]{16}\b'),
'api_key_shape':re.compile(rb'\bsk-(?:proj-)?[A-Za-z0-9_-]{20,}\b'),
'slack_token':re.compile(rb'\bxox[baprs]-[A-Za-z0-9-]{20,}\b'),
'jwt_shape':re.compile(rb'\beyJ[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}\b'),
'url_credentials':re.compile(rb'https?://[^\s/:]{1,50}:[^\s/@]{5,100}@'),
}
rows=[];flags=[];categories=defaultdict(lambda:{'files':0,'bytes':0});links=[]
for p in sorted(root.rglob('*')):
 rel=p.relative_to(root).as_posix()
 if p.is_symlink():links.append({'path':rel,'target':str(p.readlink())});continue
 if not p.is_file():continue
 data=p.read_bytes();parts=p.parts
 if p.name=='.DS_Store':cat='系统元数据'
 elif any(rel.startswith(m) for m in mirrors):cat='可重建源码镜像（含其构建树）'
 elif p.suffix in ('.class','.pyc') or '__pycache__' in parts:cat='独立编译缓存'
 elif '/tmp/' in rel and '/submitted_evidence/' not in rel:cat='探针临时状态'
 else:cat='报告与实质评审材料'
 categories[cat]['files']+=1;categories[cat]['bytes']+=len(data)
 r={'path':rel,'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest(),'category':cat};rows.append(r)
 if b'\x00' in data[:8192]:continue
 for kind,pat in patterns.items():
  for match in pat.finditer(data):
   flags.append({'path':rel,'line':data.count(b'\n',0,match.start())+1,'kind':kind,'match_length':len(match.group()),'fingerprint':hashlib.sha256(match.group()).hexdigest()[:12],'is_mirror':cat.startswith('可重建')})
summary={'at':datetime.datetime.now().astimezone().isoformat(),'root':str(root),'file_count':len(rows),'total_bytes':sum(x['bytes'] for x in rows),'largest_bytes':max(x['bytes'] for x in rows),'categories':dict(categories),'symlinks':links,'scanner_note':'启发式模式扫描；匹配不是已确认凭证，未匹配不代表绝无秘密。输出不含候选秘密原值。','secret_pattern_hits':flags}
(out/'全部文件清单_SHA256.json').write_text(json.dumps(rows,ensure_ascii=False,indent=2))
(out/'目录扫描结果.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2))
print(json.dumps({k:v for k,v in summary.items() if k not in ['secret_pattern_hits','symlinks']},ensure_ascii=False,indent=2))
print('PATTERN_HITS',len(flags),'NONMIRROR',[x for x in flags if not x['is_mirror']][:30])
print('SYMLINKS',len(links))
print('MIRROR_UNIQUE_PATTERNS',Counter(x['kind'] for x in flags if x['is_mirror']))
