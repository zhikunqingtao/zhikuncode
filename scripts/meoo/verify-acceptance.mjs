// Explicit local / anonymous remote acceptance; writes evidence outside the publication root.
import { chromium } from '../../frontend/node_modules/playwright/index.mjs';
import fs from 'node:fs/promises';
import path from 'node:path';
import http from 'node:http';
import crypto from 'node:crypto';
import { spawn } from 'node:child_process';
import assert from 'node:assert/strict';
const [kind, inputRoot, output, remoteUrl] = process.argv.slice(2);
assert(['campus', 'node'].includes(kind) && inputRoot && output, 'usage: campus|node ROOT OUTPUT [HTTPS_URL]');
const root = await fs.realpath(inputRoot);
const out = path.resolve(output);
assert(!out.startsWith(root + path.sep), 'Evidence must be outside publication root');
await fs.mkdir(out, { recursive: true });
const sha = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
async function snapshot() {
  const files=[];
  async function walk(dir) {
    for(const e of await fs.readdir(dir,{withFileTypes:true})) {
      if(e.isSymbolicLink()) throw new Error('Do not verify symlinks');
      const full=path.join(dir,e.name);
      if(e.isDirectory()) await walk(full);
      else { const bytes=await fs.readFile(full); files.push({path:path.relative(root,full),size:bytes.length,sha256:sha(bytes)}); }
    }
  }
  await walk(root); files.sort((a,b)=>a.path<b.path?-1:1);
  return {files,sha256:sha(files.map(f=>`${f.path}\0${f.size}\0${f.sha256}\n`).join(''))};
}
const before=await snapshot();
let server, child, browser;
const checks=[];
try {
  let url=remoteUrl;
  if(url) { const u=new URL(url); assert(u.protocol==='https:' && /^[\w-]+\.meoo\.(?:fun|pub)$/.test(u.hostname), 'Official HTTPS sites only'); }
  else if(kind==='campus') {
    server=http.createServer(async (req,res)=>{
      try {
        const rel=decodeURIComponent(new URL(req.url,'http://localhost').pathname);
        const file=path.resolve(root,'.'+(rel==='/'?'/index.html':rel));
        if(!file.startsWith(root+path.sep)) {res.writeHead(403);return res.end();}
        const ext=path.extname(file);
        res.setHeader('Content-Type',({'.html':'text/html; charset=utf-8','.js':'application/javascript','.ttf':'font/ttf','.json':'application/json'}[ext]||'application/octet-stream'));
        res.end(await fs.readFile(file));
      } catch {res.writeHead(404);res.end();}
    });
    await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
    url=`http://127.0.0.1:${server.address().port}/`;
  } else {
    const probe=http.createServer(); await new Promise(resolve=>probe.listen(0,'127.0.0.1',resolve));
    const port=probe.address().port; await new Promise(resolve=>probe.close(resolve));
    child=spawn(process.execPath,['server.js'],{cwd:root,env:{PATH:process.env.PATH,PORT:String(port)},stdio:'ignore'});
    url=`http://127.0.0.1:${port}/`;
    for(let i=0;i<50;i++){try{if((await fetch(url)).ok)break;}catch{}await new Promise(r=>setTimeout(r,100));}
  }
  browser=await chromium.launch({channel:'chrome',headless:true,args:['--use-angle=swiftshader','--enable-unsafe-swiftshader','--ignore-gpu-blocklist']});
  const context=await browser.newContext({viewport:{width:1440,height:1000},acceptDownloads:true});
  const page=await context.newPage();
  const errors=[];page.on('pageerror',e=>errors.push(e.message));
  const response=await page.goto(url,{waitUntil:'load',timeout:60000});
  assert.equal(response.status(),200); checks.push('anonymous HTTP 200');
  if(kind==='campus') {
    const canonical=await page.evaluate(([expected,actual])=>{
      const normalize=html=>{
        const doc=new DOMParser().parseFromString(html,'text/html');
        doc.querySelectorAll('#meoo-brand-watermark,script#meoo-baxia-web-security,link[rel="icon"][href="/favicon.ico?v=0"]').forEach(n=>n.remove());
        const prune=n=>{for(const c of [...n.childNodes]) {if(c.nodeType===8 || c.nodeType===3 && !c.textContent.trim())c.remove();else prune(c);}};
        prune(doc);return doc.documentElement.outerHTML;
      };
      return normalize(expected)===normalize(actual);
    },[await fs.readFile(path.join(root,'index.html'),'utf8'),await response.text()]);
    assert(canonical,'Entry application HTML must match, excluding known Meoo decorations');checks.push('entry HTML matches snapshot after platform normalization');
    await page.waitForFunction(()=>window.__CAMPUS && document.getElementById('loading').classList.contains('done'),{timeout:90000});
    await page.evaluate(()=>document.fonts.load("24px 'NotoSansSC'"));
    assert(await page.evaluate(()=>document.fonts.check("24px 'NotoSansSC'"))); checks.push('Chinese font loaded');
    assert(await page.evaluate(()=>window.__CAMPUS.buildingObjs.length>0 && window.__CAMPUS.stats().triangles>0));checks.push('3D model loaded');
    const camera=await page.evaluate(()=>window.__CAMPUS.camera.position.toArray());
    await page.mouse.move(750,550);await page.mouse.down();await page.mouse.move(980,650,{steps:20});await page.mouse.up();
    await page.waitForTimeout(1000);
    assert.notDeepEqual(await page.evaluate(()=>window.__CAMPUS.camera.position.toArray()),camera);checks.push('drag rotation');
    const toggle=page.locator('#layerToggles input').first();const checked=await toggle.isChecked();
    await toggle.click();assert.equal(await toggle.isChecked(),!checked);await toggle.click();checks.push('layer toggle');
    for(const [button,extension,magic] of [['#btnShot','png','89504e47'],['#btnGLB','glb','676c5446']]) {
      const downloadPromise=page.waitForEvent('download',{timeout:180000});await page.locator(button).click();const download=await downloadPromise;
      const file=path.join(out,`export.${extension}`);await download.saveAs(file);const bytes=await fs.readFile(file);
      assert(bytes.length>1000);assert.equal(bytes.subarray(0,4).toString('hex'),magic);checks.push(`${extension.toUpperCase()} export ${bytes.length} bytes`);
    }
  } else {
    await page.waitForFunction(()=>document.querySelector('#health').textContent==='API 连接正常');
    const response=await context.request.get(new URL('/api/health',url).href);
    assert.equal(response.status(),200);assert.deepEqual(await response.json(),{ok:true,app:'zhikuncode-meoo-acceptance'});checks.push('homepage and live Node API');
  }
  assert.deepEqual(errors,[]);checks.push('no JavaScript page errors');
  await page.screenshot({path:path.join(out,'page.png'),fullPage:true});
  assert.equal((await snapshot()).sha256,before.sha256);
  const report={verdict:'verified',kind,root,runtime:kind==='campus'?'static':'image',url,anonymous:true,createdAt:new Date().toISOString(),...before,checks};
  await fs.writeFile(path.join(out,'report.json'),JSON.stringify(report,null,2));
  console.log(JSON.stringify({verdict:report.verdict,root,sha256:report.sha256,checks}));
} finally {await browser?.close();server?.close();child?.kill();}
