"""Publishable local copy: freeze every pre-existing Markdown; redact non-Markdown evidence only."""
from pathlib import Path
import shutil,os,re,json,hashlib,datetime,collections,urllib.parse,subprocess
HOME=Path.home(); SRC=HOME/'Desktop/AICoding评测0925';AUDIT=Path(__file__).resolve().parent
REPO=HOME/'Desktop/dev/code/zhikuncode';DST=REPO/'docs/case-studies/assets/AICoding评测0925';BASE='评审结果_20260925_v1'
# The destination was created solely by this task's interrupted preparatory pass.
if DST.exists():shutil.rmtree(DST)
DST.mkdir(parents=True)
records=[];excluded=[];map_exact={};map_dirs={str(SRC):DST,str(AUDIT):DST/'提交前检查'}
def sha(b):return hashlib.sha256(b).hexdigest()
def public_origin(p):
 s=str(p)
 for a,b in [(str(SRC),'EVALUATION'),(str(AUDIT),'PUBLICATION_AUDIT'),(str(REPO),'SOURCE_REPO'),(str(HOME),'USER_HOME')]:s=s.replace(a,b)
 return s

def copy_file(p,q):
 q.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(p,q)
 records.append({'source':public_origin(p),'path':q.relative_to(DST).as_posix(),'original_sha256':sha(p.read_bytes()),'original_bytes':p.stat().st_size,'markdown_frozen':p.suffix.lower()=='.md'})
 map_exact[str(p)]=q

def copy_tree(a,b):
 for p in sorted(a.rglob('*')):
  rel=p.relative_to(a)
  if '.git' in rel.parts or p.name=='.DS_Store':
   if p.is_file():excluded.append({'source':public_origin(p),'reason':'Git内部元数据或.DS_Store；不属于评测内容'})
   continue
  if p.is_symlink():excluded.append({'source':public_origin(p),'reason':'符号链接，不跟随目录外目标'});continue
  if p.is_dir():(b/rel).mkdir(parents=True,exist_ok=True)
  elif p.is_file():copy_file(p,b/rel)
copy_tree(SRC,DST)
for root in ['/tmp/probe','/tmp/probe3','/tmp/oprobe','/tmp/zc-parent','/tmp/zc-verify','/tmp/zc_base','/tmp/zc_linux','/tmp/zc_repro','/tmp/zhikun_verify']:
 p=Path(root)
 if p.is_dir():q=DST/'过程补充文件/tmp'/p.name;copy_tree(p,q);map_dirs[root]=q
for root in ['/tmp/zc_cls_results.txt','/tmp/zc_cp.txt','/tmp/zc_java_full.log','/tmp/zc_java_tests.log','/tmp/zc_py_tests.log']:
 p=Path(root)
 if p.is_file():copy_file(p,DST/'过程补充文件/tmp'/p.name)
scratch=REPO/'backend/.zhikun/scratchpad/824e9ba4-7e97-4ead-9c30-7db8e486c098'
if scratch.is_dir():
 q=DST/'过程补充文件/scratchpad'/scratch.name;copy_tree(scratch,q);map_dirs[str(scratch)]=q
copy_tree(AUDIT,DST/'提交前检查')
index=json.loads((SRC/BASE/'evidence/claims/submitted_evidence/index.json').read_text())
for e in index['entries']:
 if 'preserved_path' in e:map_exact[e['original_path']]=DST/Path(e['preserved_path']).relative_to(SRC)
# Redaction strictly excludes Markdown, as explicitly directed by the user.
known=[]
log=(SRC/BASE/'evidence/claims/submitted_evidence/E03/tmp/zc_java_full.log').read_text()
known+=re.findall(r'Using generated security password:[REDACTED_TEST_PASSWORD]
for line in log.splitlines():
 if 'Mobile access' in line or 'Remote control' in line:known+=re.findall(r'https?://[^\s\x1b]+',line)
emails=set()
for p in [SRC/'DeepSeekHarness_zhikuncode_commit-0db4b04_独立审查报告.md',SRC/BASE/'evidence/root/commit.diff']:
 emails.update(re.findall(r'[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}',p.read_text()))
redactions=[];binary_sensitive=[]
for r in records:
 q=DST/r['path'];b=q.read_bytes()
 if r['markdown_frozen']:continue
 try:s=b.decode('utf-8')
 except UnicodeError:
  if any(v.encode() in b for v in known):binary_sensitive.append(r['path'])
  continue
 if '\x00' in s:continue
 old=s;count=collections.Counter()
 for value in known:
  if value in s:count['已知运行时凭据或完整访问URL']+=s.count(value);s=s.replace(value,'[REDACTED_RUNTIME_CREDENTIAL]')
 s,n=re.subn(r'(Using generated security password:[REDACTED_TEST_PASSWORD]
 lines=s.splitlines(keepends=True)
 for i,line in enumerate(lines):
  if ('Mobile access' in line or 'Remote control' in line) and ('token=' in line or 'access_token=' in line):
   lines[i],n=re.subn(r'https?://[^\s\x1b]+','[REDACTED_LOCAL_ACCESS_URL]',line);count['其他本地访问token链接']+=n
 s=''.join(lines)
 for email in emails:
  if email in s:count['作者邮箱（Markdown除外）']+=s.count(email);s=s.replace(email,'redacted@example.invalid')
 # Do not alter application source/test semantics or original scripts. For textual runtime logs/indexes,
 # redact local usernames while preserving the full file and original hash in the publication manifest.
 if q.suffix.lower() in ['.log','.txt','.xml','.json','.diff']:
  if str(HOME) in s:count['运行环境用户名路径（Markdown除外）']+=s.count(str(HOME));s=s.replace(str(HOME),'/Users/reviewer')
 if s!=old:q.write_text(s);redactions.append({'path':r['path'],'changes':{k:v for k,v in count.items() if v}})
for r in records:
 b=(DST/r['path']).read_bytes();r['published_sha256']=sha(b);r['published_bytes']=len(b);r['byte_identical']=r['published_sha256']==r['original_sha256']
 assert not r['markdown_frozen'] or r['byte_identical'],r['path']
# A separate navigation layer; never rewrite the frozen documents.
head=DST/BASE/'evidence/java/source-head';map_dirs[str(REPO)]=head
repo_files={p.relative_to(head).as_posix():p for p in head.rglob('*') if p.is_file()}
byname=collections.defaultdict(list)
for rel,p in repo_files.items():
 if '/target/' not in '/'+rel:byname[p.name].append(p)
missing_roots=['/tmp/jdelay.py','/tmp/mech.py','/tmp/zk-parent','/tmp/zhikun_parent']
missing=[x for x in missing_roots if not Path(x).exists()]
missing_doc=DST/'缺失过程文件说明.md'
missing_doc.write_text('# 缺失过程文件说明\n\n原报告与原有Markdown逐字冻结。下列原文件/目录在收录时已不存在；此页不是原件，不能当作原实验源码。\n\n'+''.join(f'## `{x}`\n\n当前无法取得；相关原始执行历史不因此被认证，也不因缺失被认定造假。\n\n' for x in missing)+f'评审的独立验证仍可打开：[watcher探针]({urllib.parse.quote(BASE)}/evidence/python/probe_watcher_cancel.py)、[真实HTTP探针]({urllib.parse.quote(BASE)}/evidence/python/probe_loopback.py)、[opencode边界裁决]({urllib.parse.quote(BASE)}/evidence/python/E08_python_adjudication.md)。\n')

def href(p,anchor=''):
 return urllib.parse.quote(p.relative_to(DST).as_posix(),safe='/._-')+('#'+anchor if anchor else '')
def clean_ref(v):
 v=urllib.parse.unquote(v.strip('<>')).rstrip('，。；）')
 anchor='';m=re.search(r':(?:L)?(\d+)(?:[-–—](?:L)?(\d+))?$',v)
 if m:anchor='L'+m.group(1)+(('-L'+m.group(2)) if m.group(2) else '');v=v[:m.start()]
 elif '#' in v:v,anchor=v.split('#',1)
 return v,anchor
prefixes=sorted(map_dirs.items(),key=lambda x:-len(x[0]))
def resolve(v,doc):
 v,anchor=clean_ref(v)
 if v.startswith(('http:','https:','mailto:','data:','app:','codex:')):return None
 if v in map_exact:return map_exact[v],anchor,'已收录'
 for old,new in prefixes:
  if v==old or v.startswith(old+'/'):
   t=new/v[len(old):].lstrip('/')
   if t.exists():return t,anchor,'已收录'
 for x in missing:
  if v==x or v.startswith(x+'/'):return missing_doc,'','原件缺失，打开说明'
 if v in repo_files:return repo_files[v],anchor,'固定目标提交源码'
 if v.startswith('backend/.zhikun/scratchpad/'):
  t=DST/'过程补充文件/scratchpad'/v.split('/scratchpad/',1)[1]
  if t.exists():return t,anchor,'已收录'
 if '/' not in v and len(byname.get(v,[]))==1:return byname[v][0],anchor,'固定目标提交源码'
 t=(doc.parent/v).resolve()
 if t.exists() and (t==DST or DST in t.parents):return t,anchor,'已收录'
 return None
original_docs=[]
for r in records:
 q=DST/r['path'];parts=q.relative_to(DST).parts
 if q.suffix=='.md' and not any(x in parts for x in ['source-head','source-parent','source','parent']) and not any('/tmp/'+x+'/' in r['path'] for x in ['zc-parent','zc-verify','zc_base']):original_docs.append(q)
nav='# 冻结文档与过程文件导航\n\n旧Markdown不作任何修改。这里为原有绝对路径、代码文件和过程文件提供可点击的本地/仓库相对链接；旧文档中的原链接保留其历史状态。\n\n'
refs=[];unresolved=[]
for doc in original_docs:
 s=doc.read_text();tokens=set(re.findall(r'`([^`\n]+)`',s))
 tokens.update(re.findall(r'\]\((<[^>\n]+>|[^)\n]+)\)',s))
 tokens.update(re.findall(r'/tmp/[A-Za-z0-9_.@/+-]+',s))
 rows=[]
 for token in sorted(tokens):
  if len(token)>450 or '\n' in token:continue
  result=resolve(token,doc)
  if result:
   target,anchor,state=result
   if anchor.startswith('L') and target.is_file():
    try:
     n=int(re.match(r'L(\d+)',anchor).group(1))
     if n>len(target.read_text().splitlines()):anchor='';state+='；原行号超出文件范围，保留原文并链接文件'
    except (UnicodeError,AttributeError):pass
   rows.append((token,target,anchor,state));refs.append({'document':doc.relative_to(DST).as_posix(),'original_reference':token,'target':target.relative_to(DST).as_posix(),'anchor':anchor,'status':state})
  elif token.startswith('/tmp/') or token.startswith(str(SRC)) or token.startswith(str(REPO)):
   unresolved.append({'document':doc.relative_to(DST).as_posix(),'reference':token,'status':'路径不存在、命令片段或未能自动解析；不伪造原件'})
 if rows:
  nav+=f'## [{doc.relative_to(DST).as_posix()}]({href(doc)})\n\n| 原文引用 | 可打开的收录文件 | 状态 |\n|---|---|---|\n'
  for token,target,anchor,state in rows:
   escaped=token.replace("|",chr(92)+"|")
   nav+=f'| `{escaped}` | [打开]({href(target,anchor)}) | {state} |\n'
  nav+='\n'
nav+='## 外部过程目录\n\n'
for old,new in prefixes:
 if old.startswith('/tmp/') or '/scratchpad/' in old:nav+=f'- `{old}` → [已收录目录]({href(new)})\n'
(DST/'冻结文档与过程文件导航.md').write_text(nav)
(DST/'reference_map.json').write_text(json.dumps({'resolved':refs,'unresolved':unresolved},ensure_ascii=False,indent=2))
manifest={'prepared_at':datetime.datetime.now().astimezone().isoformat(),'policy':'全部旧Markdown字节冻结；仅非Markdown副本脱敏；不commit/push','files':records,'excluded_metadata':excluded,'missing_originals':missing}
(DST/'publication_manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2))
(DST/'redaction_manifest.json').write_text(json.dumps({'policy':'所有旧Markdown不脱敏、不改路径，按用户明确要求保留；只处理非Markdown派生副本；原值不写记录','files':redactions,'binary_sensitive_candidates':binary_sensitive},ensure_ascii=False,indent=2))
readme=f'''# AICoding评测0925：冻结材料包

本目录是原评测材料和报告引用过程文件的完整本地副本。按用户最后要求，**全部已有Markdown逐字冻结，原文、分数、引用及个人路径均未改动**。仅新增本说明、导航和校验记录；敏感日志等非Markdown使用脱敏副本。没有执行Git commit或push。

- [综合评测原文]({urllib.parse.quote(BASE)}/AICoding八方评测_综合排名与证据审计.md)
- [八方结果入口原文](评审汇总_八方评测结果入口.md)
- [冻结文档与过程文件导航](冻结文档与过程文件导航.md)：旧链接不改，请从这里打开收录附件。
- [缺失原件说明](缺失过程文件说明.md)：不存在的原文件未伪造。
- [原件/副本SHA和全文件清单](publication_manifest.json)
- [非Markdown脱敏记录](redaction_manifest.json)
- [本次校验结果](publication_validation.json)

## 收录范围

原评测目录全部实质文件，包括八份报告、冻结inputs、最终和历史评分、主审/反方底稿、全部探针与日志、Java/Python源码镜像、已有构建输出、失败实验与测试结果。额外收录当前可取得的`/tmp/probe`、`probe3`、`oprobe`、`zc-parent`、`zc-verify`、`zc_base`、`zc_linux`、`zc_repro`、`zhikun_verify`及具名日志，还有被引用的scratchpad目录。提交前检查材料也完整保留。

只排除`.DS_Store`和`.git`内部元数据，不以文件属于tmp/target/scratchpad为理由丢弃证据。完整源码镜像较大，这是遵照“全部加入”的选择。此前检查文件提出的精简或修改Markdown建议是历史意见，**本次采用这里明确的冻结方案**。

## 冻结与脱敏边界

旧Markdown中作者邮箱、本机路径和旧链接按用户要求原样保留；它们不会因为复制就自动变成有效的GitHub链接。新增导航把能解析的文件映射到本包，保持旧文档作为原始证据。原文错误行号不偷偷修正，导航会注明并打开对应文件。镜像内部的旧项目文档是提交快照，不承诺其中所有历史外链仍在线。

非Markdown运行日志中的生成密码和含token访问链接已遮盖；保留完整日志、行顺序及原始/派生SHA。日志/JSON/XML等的本机用户名路径和作者邮箱可被去标识；原文件始终留在原目录。原manifest中的SHA继续表示冻结原件；发布副本应以本目录publication_manifest为准。

## 复算与实验

只重算最终评分（Python标准库）：

```sh
python3 评审结果_20260925_v1/recompute_scores.py
```

该脚本会重写评分结果JSON，若仅核查冻结材料，应在临时副本上运行。历史render/build/finish脚本保留审计用途，可能含原作者机器路径；不要直接运行它们改写冻结Markdown。源码与全部probe均已附带，实验复跑可在复制出去的临时目录按原复现索引执行，并将新日志与旧日志分开。

本地打开文件不依赖原`/tmp`目录。个别原始过程文件已消失，导航到缺失说明，不将独立复验冒称原实验。
'''
(DST/'README.md').write_text(readme)
# Only validate new portable navigation; frozen originals are intentionally untouched.
broken=[];links=0
for name in ['README.md','冻结文档与过程文件导航.md','缺失过程文件说明.md']:
 doc=DST/name
 for raw in re.findall(r'\]\(([^)]+)\)',doc.read_text()):
  if raw.startswith(('https:','http:','mailto:')):continue
  target=urllib.parse.unquote(raw.split('#',1)[0]);links+=1
  if target=='publication_validation.json':continue
  if not (doc.parent/target).exists():broken.append({'document':name,'target':target})
checks={'checked_at':datetime.datetime.now().astimezone().isoformat(),'copied_files':len(records),'copied_bytes':sum(r['published_bytes'] for r in records),'frozen_markdown_count':sum(r['markdown_frozen'] for r in records),'all_preexisting_markdown_byte_identical':all(r['byte_identical'] for r in records if r['markdown_frozen']),'new_navigation_links_checked':links,'broken_new_navigation_links':broken,'unresolved_original_reference_count':len(unresolved),'missing_originals':missing,'non_markdown_redacted_files':len(redactions),'binary_sensitive_candidates':binary_sensitive,'git_commit_or_push_executed':False}
(DST/'publication_validation.json').write_text(json.dumps(checks,ensure_ascii=False,indent=2))
print(json.dumps(checks,ensure_ascii=False))
