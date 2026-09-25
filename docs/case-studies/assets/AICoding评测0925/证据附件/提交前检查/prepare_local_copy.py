from pathlib import Path
import os, shutil, json, hashlib, re, collections, datetime, urllib.parse
SRC=Path('/Users/guoqingtao/Desktop/AICoding评测0925')
AUDIT=Path(__file__).resolve().parent
REPO=Path('/Users/guoqingtao/Desktop/dev/code/zhikuncode')
DST=REPO/'docs/case-studies/assets/AICoding评测0925'
BASE='评审结果_20260925_v1'
records=[];excluded=[];mapping={};missing=[]
def renamed(rel):return str(rel).replace('Users/guoqingtao/','Users/reviewer/')
def digest(b):return hashlib.sha256(b).hexdigest()
def copy_tree(src,dest):
 for p in sorted(src.rglob('*')):
  rel=p.relative_to(src)
  if '.git' in rel.parts or p.name=='.DS_Store':
   if p.is_file():excluded.append({'source':str(p).replace(str(SRC),'EVALUATION').replace(str(REPO),'SOURCE_REPO').replace('/Users/guoqingtao','USER_HOME'),'reason':'Git内部元数据或macOS目录元数据；非评测内容'})
   continue
  q=dest/renamed(rel)
  if p.is_symlink():
   excluded.append({'source':str(p).replace('/Users/guoqingtao','USER_HOME'),'reason':'符号链接，未跟随目录外内容'});continue
  if p.is_dir():q.mkdir(parents=True,exist_ok=True);continue
  if not p.is_file():continue
  q.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(p,q)
  records.append({'origin':str(p),'path':q.relative_to(DST).as_posix(),'original_sha256':digest(p.read_bytes()),'original_bytes':p.stat().st_size})
  mapping[str(p)]=q
DST.mkdir(parents=True,exist_ok=False)
copy_tree(SRC,DST)
# Additional process directories named by the reports; retain their full contents.
roots=['/tmp/probe','/tmp/probe3','/tmp/oprobe','/tmp/zc-parent','/tmp/zc-verify','/tmp/zc_base','/tmp/zc_linux','/tmp/zc_repro','/tmp/zhikun_verify']
for root in roots:
 p=Path(root);dest=DST/'过程补充文件/tmp'/p.name
 if p.is_dir():copy_tree(p,dest);mapping[str(p)]=dest
for root in ['/tmp/zc_cls_results.txt','/tmp/zc_cp.txt','/tmp/zc_java_full.log','/tmp/zc_java_tests.log','/tmp/zc_py_tests.log']:
 p=Path(root)
 if p.is_file():
  q=DST/'过程补充文件/tmp'/p.name;q.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(p,q);mapping[str(p)]=q
  records.append({'origin':str(p),'path':q.relative_to(DST).as_posix(),'original_sha256':digest(p.read_bytes()),'original_bytes':p.stat().st_size})
scratch=REPO/'backend/.zhikun/scratchpad/824e9ba4-7e97-4ead-9c30-7db8e486c098'
copy_tree(scratch,DST/'过程补充文件/scratchpad/824e9ba4-7e97-4ead-9c30-7db8e486c098');mapping[str(scratch)]=DST/'过程补充文件/scratchpad/824e9ba4-7e97-4ead-9c30-7db8e486c098'
# Include all audit artifacts except the duplicate preview log (original and its redacted derivative already present).
copy_tree(AUDIT,DST/'提交检查')
# Build directories and formerly absolute evidence references are all available locally.
index=json.loads((SRC/BASE/'evidence/claims/submitted_evidence/index.json').read_text())
for e in index['entries']:
 if e.get('preserved_path'):
  mapping[e['original_path']]=DST/renamed(Path(e['preserved_path']).relative_to(SRC))
for root in ['/tmp/jdelay.py','/tmp/mech.py','/tmp/zk-parent','/tmp/zk-parent/backend','/tmp/zhikun_parent','/tmp/zhikun_parent/backend']:
 if not Path(root).exists():missing.append(root)
head=DST/BASE/'evidence/java/source-head'
repo_files={str(p.relative_to(head)):p for p in head.rglob('*') if p.is_file()}
byname=collections.defaultdict(list)
for rel,p in repo_files.items():
 if '/target/' not in '/'+rel:byname[p.name].append(p)
core_mds=[];link_events=[];unresolved=[];redactions=[]
for r in records:
 q=DST/r['path']
 if q.suffix=='.md' and not any(x in q.relative_to(DST).parts for x in ['source-head','source-parent','source','parent']) and '过程补充文件/tmp/zc-' not in r['path'] and '/tmp/zc_base/' not in r['path']:core_mds.append(q)
missing_page=DST/'缺失过程文件说明.md'
def resolve(raw,doc):
 raw=urllib.parse.unquote(raw.strip('<>'))
 if raw.startswith(('http:','https:','mailto:','data:','javascript:','app:','codex:')):return None
 anchor=''; line=None
 if '#' in raw:raw,anchor=raw.split('#',1)
 m=re.search(r':(?:L)?(\d+)(?:[-–—](?:L)?(\d+))?$',raw)
 if m:line=int(m.group(1));anchor='L'+m.group(1)+(('-L'+m.group(2)) if m.group(2) else '');raw=raw[:m.start()]
 if raw in missing:
  return missing_page,'missing-'+hashlib.sha256(raw.encode()).hexdigest()[:10]
 target=mapping.get(raw)
 if target is None and raw.startswith(str(SRC)):
  target=DST/renamed(raw[len(str(SRC)):].lstrip('/'))
 if target is None and raw.startswith(str(AUDIT)):
  target=DST/'提交检查'/raw[len(str(AUDIT)):].lstrip('/')
 if target is None and raw.startswith(str(REPO)):
  target=head/raw[len(str(REPO)):].lstrip('/')
 if target is None:
  for old,new in sorted(mapping.items(),key=lambda x:-len(x[0])):
   if raw.startswith(old+'/') and new.is_dir():target=new/raw[len(old)+1:];break
 if target is None:
  rel=(doc.parent/raw).resolve()
  if rel.exists() and (rel==DST or DST in rel.parents):target=rel
 if target is None and raw in repo_files:target=repo_files[raw]
 if target is None and raw.startswith('backend/.zhikun/scratchpad/'):
  a=raw.split('/scratchpad/',1)[1];candidate=DST/'过程补充文件/scratchpad'/a
  if candidate.exists():target=candidate
 if target is None and '/' not in raw and re.search(r'\.(java|py|yml|yaml|xml|md|json|ts|tsx)$',raw):
  hits=byname.get(raw,[])
  if len(hits)==1:target=hits[0]
 if target is None or not target.exists():return None
 if line and target.is_file():
  try:
   count=len(target.read_text().splitlines())
   if line>count:anchor='';unresolved.append({'document':doc.relative_to(DST).as_posix(),'reference':raw+':'+str(line),'status':'原报告行号超出文件范围，链接指向文件本身，未改报告断言'})
  except (UnicodeError,OSError):pass
 return target,anchor

def href(target,anchor,doc):return urllib.parse.quote(os.path.relpath(target,doc.parent),safe='/._-')+('#'+anchor if anchor else '')
# Gather exact sensitive values from confirmed original log; never write them to metadata.
known=[]
original=(SRC/BASE/'evidence/claims/submitted_evidence/E03/tmp/zc_java_full.log').read_text()
known+=re.findall(r'Using generated security password:[REDACTED_TEST_PASSWORD]
for line in original.splitlines():
 if 'Mobile access' in line or 'Remote control' in line:
  known+=re.findall(r'https?://[^\s\x1b]+',line)
# Personal email addresses identified in commit/report; preserve deliberately fake fixtures.
private_emails=set()
for p in [SRC/'DeepSeekHarness_zhikuncode_commit-0db4b04_独立审查报告.md',SRC/BASE/'evidence/root/commit.diff']:
 private_emails.update(re.findall(r'[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}',p.read_text()))
for r in records:
 q=DST/r['path'];b=q.read_bytes()
 try:s=b.decode('utf-8')
 except UnicodeError:continue
 if '\x00' in s:continue
 original_s=s;counts=collections.Counter()
 # Actual generated passwords/remote-access URLs, including repeated copies/new process files.
 for value in known:
  if value in s:counts['已确认临时密码或访问链接']+=s.count(value);s=s.replace(value,'[REDACTED_RUNTIME_CREDENTIAL]')
 s,n=re.subn(r'(Using generated security password:[REDACTED_TEST_PASSWORD]
 # Additional real startup log links (not arbitrary test fixture token strings).
 lines=s.splitlines(keepends=True)
 for i,line in enumerate(lines):
  if ('Mobile access' in line or 'Remote control' in line) and ('token=' in line or 'access_token=' in line):
   lines[i],n=re.subn(r'https?://[^\s\x1b]+','[REDACTED_LOCAL_ACCESS_URL]',line);counts['带访问token的本地启动链接']+=n
 s=''.join(lines)
 for email in private_emails:
  if email in s:counts['作者邮箱']+=s.count(email);s=s.replace(email,'redacted@example.invalid')
 if q in core_mds:
  # Existing links first; preserve visible labels and original line numbering.
  def repl_link(m):
   label,url=m.group(1),m.group(2)
   result=resolve(url,q)
   if result:
    target,anchor=result;link_events.append({'document':r['path'],'target':target.relative_to(DST).as_posix(),'kind':'existing_link'});return '['+label+']('+href(target,anchor,q)+')'
   if url.startswith('/') and not url.startswith('//'):unresolved.append({'document':r['path'],'reference':url,'status':'无法解析原链接'})
   return m.group(0)
  s=re.sub(r'\[([^\]\n]*)\]\((<[^>\n]+>|[^)\n]+)\)',repl_link,s)
  # Turn named process/source files in inline code into clickable file links (not commands).
  def repl_inline(m):
   raw=m.group(1)
   if len(raw)>420 or '\n' in raw or ' ' in raw or raw.startswith(('http','@')):return m.group(0)
   if not any(x in raw for x in ['.java','.py','.md','.log','.txt','.json','.xml','.yml','.yaml','/tmp/','scratchpad/']):return m.group(0)
   result=resolve(raw,q)
   if result:
    target,anchor=result;link_events.append({'document':r['path'],'target':target.relative_to(DST).as_posix(),'kind':'inline_reference'});return '['+raw+']('+href(target,anchor,q)+')'
   return m.group(0)
  # Keep fenced scripts/code intact.
  chunks=re.split(r'(^```[^\n]*\n[\s\S]*?^```[^\n]*$)',s,flags=re.M)
  for i in range(0,len(chunks),2):chunks[i]=re.sub(r'(?<!`)`([^`]+)`(?!`)',repl_inline,chunks[i])
  s=''.join(chunks)
 # Redact home/name paths in archival text and script history; presentation links already portable.
 for old,new in [(str(SRC),'EVALUATION_ROOT'),(str(AUDIT),'PUBLICATION_AUDIT'),(str(REPO),'SOURCE_REPO'),('/Users/guoqingtao','/home/reviewer'),('Users/guoqingtao/','Users/reviewer/')]:
  if old in s:counts['本机路径去标识']+=s.count(old);s=s.replace(old,new)
 if s!=original_s:q.write_text(s)
 if any(counts.values()):redactions.append({'path':r['path'],'changes':{k:v for k,v in counts.items() if v}})
# Self-contained missing reference page: absence must not be misrepresented as original evidence.
missing_page.write_text('# 缺失过程文件说明\n\n下列原路径在收录时已不存在。此页只说明缺失，不是原文件的替代或重建。原报告相关叙述完整保留；实际仍可核验的独立探针另附。\n\n'+''.join(f'<a id="missing-{hashlib.sha256(x.encode()).hexdigest()[:10]}"></a>\n\n## `{x}`\n\n原文件/目录当前不可取得。\n\n' for x in missing)+f'独立watcher验证：[探针]({urllib.parse.quote(BASE)}/evidence/python/probe_watcher_cancel.py)、[真实HTTP探针]({urllib.parse.quote(BASE)}/evidence/python/probe_loopback.py)、[边界裁决]({urllib.parse.quote(BASE)}/evidence/python/E08_python_adjudication.md)。\n')
# Fix serialized file locations in evaluator indexes to root-relative public paths, retaining original hashes.
for rel in [f'{BASE}/manifest.json',f'{BASE}/evidence/claims/submitted_evidence/index.json']:
 p=DST/rel;obj=json.loads(p.read_text())
 for e in obj.get('entries',[]):
  for key in ['source','snapshot','preserved_path']:
   v=e.get(key)
   if not isinstance(v,str):continue
   if v.startswith('EVALUATION_ROOT/'):
    e[key]=renamed(v[len('EVALUATION_ROOT/'):])
  e['hash_scope']='sha256及bytes保留冻结原件含义；发布副本以publication_manifest.json为准'
 obj['publication_note']='这是脱敏/路径适配副本；原始哈希不代表当前副本字节。路径相对本资料根目录。'
 p.write_text(json.dumps(obj,ensure_ascii=False,indent=2))
# Add a relative-link source/evidence navigation index.
nav='# 文档与过程文件导航\n\n全部原材料已复制，新增收录的过程目录也在本资料内。源码镜像和构建目录保留，不作精简。\n\n## 八份原报告\n\n'
for p in sorted(DST.glob('*.md')):
 nav+=f'- [{p.name}]({urllib.parse.quote(p.name)})\n'
nav+='\n## 外部过程文件与目录\n\n'
for old,new in sorted(mapping.items()):
 if old.startswith('/tmp/') or '/scratchpad/' in old:
  if new.exists():nav+=f'- `{old.replace(str(REPO),"SOURCE_REPO")}` → [{new.relative_to(DST).as_posix()}]({urllib.parse.quote(new.relative_to(DST).as_posix())})\n'
nav+='\n## 缺失原件\n\n[查看缺失说明](缺失过程文件说明.md)。缺失原件未伪造，报告已有的不确定性保持。\n'
(DST/'文档与过程文件导航.md').write_text(nav)
# Publication metadata hashes are separate from originals.
for r in records:
 r['origin']=r['origin'].replace(str(SRC),'EVALUATION').replace(str(AUDIT),'PUBLICATION_AUDIT').replace(str(REPO),'SOURCE_REPO').replace('/Users/guoqingtao','USER_HOME')
 r['published_sha256']=digest((DST/r['path']).read_bytes());r['published_bytes']=(DST/r['path']).stat().st_size;r['modified_for_publication']=r['published_sha256']!=r['original_sha256']
(DST/'publication_manifest.json').write_text(json.dumps({'prepared_at':datetime.datetime.now().astimezone().isoformat(),'scope':'完整评测目录+被引用且当前可取得的过程目录+提交检查；未commit/push','files':records,'excluded_metadata':excluded,'missing_originals':missing},ensure_ascii=False,indent=2))
(DST/'redaction_manifest.json').write_text(json.dumps({'policy':'仅发布副本；原件SHA单独保留；不输出凭据原值；评分不变','files':redactions},ensure_ascii=False,indent=2))
(DST/'reference_migration.json').write_text(json.dumps({'converted_references':link_events,'unresolved_or_invalid_original_references':unresolved},ensure_ascii=False,indent=2))
print(json.dumps({'destination':str(DST),'copied_files':len(records),'redacted_files':len(redactions),'converted_links':len(link_events),'unresolved':len(unresolved),'missing_originals':missing,'excluded_metadata':len(excluded)},ensure_ascii=False))
