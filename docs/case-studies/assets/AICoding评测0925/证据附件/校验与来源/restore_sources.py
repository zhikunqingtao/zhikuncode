"""Restore missing fixed-commit files from a local Git repository. Never overwrite evidence."""
from pathlib import Path,PurePosixPath
import argparse,gzip,json,subprocess,hashlib,os
ROOT=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--repository',type=Path,required=True,help='Local zhikuncode Git repository containing the fixed commits')
p.add_argument('--all',action='store_true',help='Also restore old documentation/media; default is backend and python-service only')
p.add_argument('--dry-run',action='store_true',help='Check commit availability and report planned counts without writing files')
a=p.parse_args();repo=a.repository.resolve()
with gzip.open(ROOT/'校验与来源/精简移除清单.jsonl.gz','rt',encoding='utf-8') as f:rows=[json.loads(line) for line in f]
work={};skipped=0
for r in rows:
 source=r.get('git_source')
 if not source:continue
 if not a.all and not source['path'].startswith(('backend/','python-service/')):continue
 rel=PurePosixPath(r['path'])
 if rel.is_absolute() or '..' in rel.parts:raise ValueError('Unsafe destination')
 dst=ROOT.joinpath(*rel.parts)
 if dst.exists():skipped+=1;continue
 spec=source['commit']+':'+source['path'];work.setdefault(spec,[]).append((dst,r))
commits={spec.split(':',1)[0] for spec in work}
modes={}
for commit in commits:
 subprocess.run(['git','-C',str(repo),'cat-file','-e',commit+'^{commit}'],check=True,capture_output=True)
 for record in subprocess.check_output(['git','-C',str(repo),'ls-tree','-rz',commit]).split(b'\0'):
  if not record:continue
  meta,path=record.split(b'\t',1);mode,kind,_=meta.decode().split()
  if kind=='blob':modes[commit+':'+path.decode()]=mode
count=sum(len(v) for v in work.values())
if a.dry_run:
 print(json.dumps({'mode':'dry-run','files_to_restore':count,'unique_blobs_by_path':len(work),'existing_files_skipped':skipped,'commits_verified':sorted(commits)},ensure_ascii=False));raise SystemExit(0)
proc=subprocess.Popen(['git','-C',str(repo),'cat-file','--batch'],stdin=subprocess.PIPE,stdout=subprocess.PIPE)
restored=0
try:
 for spec,targets in work.items():
  proc.stdin.write((spec+'\n').encode());proc.stdin.flush()
  header=proc.stdout.readline().decode().strip().split()
  if len(header)!=3 or header[1]!='blob':raise RuntimeError('Missing blob: '+spec)
  size=int(header[2]);data=proc.stdout.read(size)
  if len(data)!=size or proc.stdout.read(1)!=b'\n':raise RuntimeError('Incomplete git object')
  actual=hashlib.sha256(data).hexdigest()
  for dst,r in targets:
   expected=r.get('record',{}).get('original_sha256')
   if expected and actual!=expected:raise RuntimeError('Original hash mismatch: '+r['path'])
   dst.parent.mkdir(parents=True,exist_ok=True)
   # Exclusive creation also protects against a file created after the initial check.
   with dst.open('xb') as out:out.write(data)
   dst.chmod(0o755 if modes.get(spec)=='100755' else 0o644);restored+=1
finally:
 proc.stdin.close();proc.stdout.close();code=proc.wait()
 if code:raise RuntimeError('git cat-file failed')
print(json.dumps({'restored_files':restored,'existing_files_skipped':skipped,'note':'Only missing source files restored; no builds, network, installs, or evidence overwrites.'},ensure_ascii=False))
