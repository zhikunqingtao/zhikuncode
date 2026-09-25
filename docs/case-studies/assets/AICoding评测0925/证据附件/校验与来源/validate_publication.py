"""Read-only checks of the frozen copy; writes only publication_validation.json."""
from pathlib import Path
import json,hashlib,re,urllib.parse,zipfile,io,tempfile,shutil,subprocess,sys,datetime
META=Path(__file__).resolve().parent;P=META.parents[1];B=P/'证据附件/评审结果_20260925_v1'
def digest(p):
 h=hashlib.sha256()
 with p.open('rb') as f:
  while True:
   b=f.read(1048576)
   if not b:break
   h.update(b)
 return h.hexdigest()
m=json.loads((META/'publication_manifest.json').read_text());fail=[];md=0
for r in m['files']:
 p=P/r['path']
 if not p.is_file():fail.append({'path':r['path'],'reason':'missing'});continue
 h=digest(p)
 if h!=r['published_sha256']:fail.append({'path':r['path'],'reason':'published hash mismatch'})
 if r['markdown_frozen']:
  md+=1
  if h!=r['original_sha256']:fail.append({'path':r['path'],'reason':'frozen Markdown changed'})
broken=[];links=0
navigation=[P/'README.md',P/'证据附件/README.md',P/'证据附件/证据阅读指南.md']
navigation += list((P/'证据附件/完整流程录屏').glob('*.md'))
navigation += [p for p in (P/'证据附件/整理说明').glob('*.md') if not p.name.startswith('评审汇总_')]
for doc in navigation:
 for url in re.findall(r'\]\(([^)]+)\)',doc.read_text()):
  if url.startswith(('http:','https:','mailto:')):continue
  links+=1;t=urllib.parse.unquote(url.split('#',1)[0].split('?',1)[0])
  if not (doc.parent/t).exists():broken.append({'document':str(doc.relative_to(P)),'target':t})
refs=json.loads((META/'reference_map.json').read_text())
for r in refs['resolved']:
 for key in ['document','target']:
  if not (P/r[key]).exists():broken.append({'document':r['document'],'target':r[key],'kind':'reference_map'})
layout=json.loads((META/'layout_manifest.json').read_text())
for r in layout['files']:
 target=P/r['after_path']
 if not target.is_file():fail.append({'path':r['after_path'],'reason':'layout file missing'})
 elif r['frozen_markdown'] and digest(target)!=r['before_sha256']:fail.append({'path':r['after_path'],'reason':'frozen Markdown changed'})
with tempfile.TemporaryDirectory(prefix='aicoding_score_check_') as tmp:
 t=Path(tmp)
 for name in ['evaluation_data.json','recompute_scores.py']:shutil.copy2(B/name,t/name)
 subprocess.run([sys.executable,str(t/'recompute_scores.py')],check=True,capture_output=True,text=True)
 calculated=json.loads((t/'scores_computed.json').read_text());frozen=json.loads((B/'scores_computed.json').read_text())
 score_match=calculated==frozen
usage=json.loads((META/'usage_redaction_manifest.json').read_text())
with zipfile.ZipFile(P/usage['archive']) as z:
 zip_ok=z.testzip() is None and all(hashlib.sha256(z.read(e['entry'])).hexdigest()==e['published_sha256'] for e in usage['entries'])
csv_matches=all(digest(p)==digest(p.parents[2]/p.name) for p in (P/'证据附件/用量数据/展开副本').rglob('*.csv'))
result={'layout_original_files_preserved':len(layout['files']),'top_level_entries':len(list(P.iterdir())),'late_csv_copies_match_redacted_canonical':csv_matches,'checked_at':datetime.datetime.now().astimezone().isoformat(),'copied_files':len(m['files']),'copied_bytes':sum(r['published_bytes'] for r in m['files']),'frozen_markdown_count':md,'all_preexisting_markdown_byte_identical':not any(x['reason']=='frozen Markdown changed' for x in fail),'manifest_failures':fail,'new_navigation_links_checked':links,'broken_new_navigation_links':broken,'unresolved_original_references':json.loads((META/'reference_map.json').read_text())['unresolved'],'missing_originals':m['missing_originals'],'score_recomputation_matches_frozen':score_match,'usage_zip_readable_and_hashes_match':zip_ok,'video_status':'Compressed full-process recording included, SHA verified; original 1.82 GB recording remains excluded; content scope attributed to provider','video_privacy_cleared_for_public_release':False,'git_commit_or_push_executed':True,'git_publication_scope':'Video published in acf22f86897d78fd745181d5e71b11225ee4476e; full package and bilingual README publication authorized subsequently; this validation is a pre-publication snapshot'}
(META/'publication_validation.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
print(json.dumps(result,ensure_ascii=False))
assert not fail and not broken and score_match and zip_ok and csv_matches
