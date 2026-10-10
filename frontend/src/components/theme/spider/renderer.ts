import * as THREE from 'three';
import { SpiderResourceScope } from './resourceScope';
import { planContactBodyPoint } from './contactPlanner';
import { SpiderRig } from './rig';
import { SpiderGait, type SpiderGaitResult } from './gait';
import { sampleLiftMotion, scheduleRelease, MIN_RELEASE_AGE, RETURN_DURATION } from './choreography';
import { addWebContact, advanceWebContacts } from './webDynamics';
import { SpiderAnchorRegistry } from './anchorRegistry';
import { SpiderExclusionMask } from './exclusionMask';
import { createSpiderRuntimeAdapter } from './runtimeAdapter';
import { createWebMaterial, createCompositeMaterial } from './shaders';
import type { SpiderAnchor, SpiderSignal, SpiderPhase } from './types';

const COLORS = ['#54e9f1', '#fa39c8', '#a78aff'];
const clamp = (v: number, min = 0, max = 1) => Math.max(min, Math.min(max, v));
const smooth = (a: number, b: number, value: number) => { const v = clamp((value-a)/(b-a)); return v*v*(3-2*v); };
interface Lift {
    anchor: SpiderAnchor; group: THREE.Group; mesh: THREE.Mesh<THREE.PlaneGeometry, THREE.MeshBasicMaterial>;
    marker: THREE.Mesh<THREE.RingGeometry, THREE.MeshBasicMaterial>; outline: THREE.Line<THREE.BufferGeometry, THREE.LineBasicMaterial>;
    tether: THREE.LineSegments<THREE.BufferGeometry, THREE.LineBasicMaterial>; grip: THREE.Vector3; sourceGrip: THREE.Vector3; approachFrom: THREE.Vector3;
    leg: number; width: number; height: number; gripX: number; start: number; duration: number; angle: number;
    phase: SpiderPhase | 'ambient'; releaseAt: number | null; zoom: number; color: string; frozen: boolean; tension: number; kicked: boolean;
}
interface Intent { anchor: SpiderAnchor; phase: SpiderPhase | 'ambient'; requested: number }

/** One isolated graphics owner. All DOM reads stay outside React's streaming render tree. */
export function createSpiderScene(host: HTMLElement): () => void {
    const startup = new SpiderResourceScope();
    try { const dispose = constructScene(host, startup); startup.detach(); return dispose; }
    catch (error) { startup.dispose(); throw error; }
}
function constructScene(host: HTMLElement, startup: SpiderResourceScope): () => void {
    const renderer = new THREE.WebGLRenderer({ alpha: true, antialias: true, powerPreference: 'high-performance' });
    startup.defer(() => { renderer.dispose(); renderer.forceContextLoss(); });
    renderer.setClearColor(0x000000,0); renderer.autoClear = false;
    renderer.outputColorSpace = THREE.SRGBColorSpace;
    const canvas = renderer.domElement; canvas.setAttribute('aria-hidden','true'); host.appendChild(canvas); startup.defer(() => canvas.remove());
    const scene = new THREE.Scene(), camera = new THREE.PerspectiveCamera(38,1,1,8000);
    const rig = startup.track(new SpiderRig(scene), value => value.dispose());
    const registry = startup.track(new SpiderAnchorRegistry(), value => value.dispose());
    const baseTarget = startup.track(new THREE.WebGLRenderTarget(1,1,{depthBuffer:true}), value => value.dispose());
    const glowTarget = startup.track(new THREE.WebGLRenderTarget(1,1,{depthBuffer:true}), value => value.dispose());
    const web = startup.track(createWebMaterial(), value => value.dispose());
    const background = new THREE.Mesh(startup.track(new THREE.PlaneGeometry(2,2), value => value.dispose()),web); background.frustumCulled=false; background.renderOrder=-1000; scene.add(background);
    const exclusionMask = startup.track(new SpiderExclusionMask(), value => value.dispose());
    const composite = startup.track(createCompositeMaterial(baseTarget.texture,glowTarget.texture,exclusionMask.texture), value => value.dispose());
    const outputScene = new THREE.Scene(), outputCamera = new THREE.Camera();
    const quad = new THREE.Mesh(startup.track(new THREE.PlaneGeometry(2,2), value => value.dispose()),composite); quad.frustumCulled=false; outputScene.add(quad);
    const particleCount=72, particlePositions=new Float32Array(particleCount*3), particleColors=new Float32Array(particleCount*3);
    const particleGeo=startup.track(new THREE.BufferGeometry(), value => value.dispose()); particleGeo.setAttribute('position',new THREE.BufferAttribute(particlePositions,3).setUsage(THREE.DynamicDrawUsage));
    particleGeo.setAttribute('color',new THREE.BufferAttribute(particleColors,3).setUsage(THREE.DynamicDrawUsage));
    const particleMaterial=new THREE.PointsMaterial({size:1.7,vertexColors:true,transparent:true,opacity:.52,blending:THREE.AdditiveBlending,depthWrite:false,sizeAttenuation:false});
    startup.defer(() => particleMaterial.dispose());
    const particles=new THREE.Points(particleGeo,particleMaterial); particles.layers.enable(1); particles.frustumCulled=false; scene.add(particles);
    const gait=new SpiderGait();
    const gaitStep:SpiderGaitResult={targets:Array.from({length:8},()=>new THREE.Vector3()),planted:Array(8).fill(false),bodyBob:0,bodyRoll:0,touchdowns:[]};
    const body=new THREE.Vector3(), desiredBody=new THREE.Vector3(), posedBody=new THREE.Vector3();
    const sparks=Array.from({length:particleCount},()=>({position:new THREE.Vector3(),velocity:new THREE.Vector3(),age:2,color:new THREE.Color()}));
    let nextSpark=0,heading=0;
    const trailSteps=14,trailHistory=Array.from({length:8},()=>Array.from({length:trailSteps},()=>new THREE.Vector3()));
    const trailPositions=new Float32Array(8*(trailSteps-1)*6),trailColors=new Float32Array(trailPositions.length);
    const trailGeo=startup.track(new THREE.BufferGeometry(),value=>value.dispose());
    trailGeo.setAttribute('position',new THREE.BufferAttribute(trailPositions,3).setUsage(THREE.DynamicDrawUsage));trailGeo.setAttribute('color',new THREE.BufferAttribute(trailColors,3));
    // The trail gradient depends only on its leg and history index, never on time.
    // Upload it once; only positions change as the feet move.
    for(let leg=0;leg<8;leg++){
        const color=new THREE.Color(COLORS[leg%3]);
        for(let i=0;i<trailSteps-1;i++){
            const offset=(leg*(trailSteps-1)+i)*6,fade=(1-i/(trailSteps-1))**2;
            trailColors[offset]=color.r*fade*.8;trailColors[offset+1]=color.g*fade*.8;trailColors[offset+2]=color.b*fade*.8;
            trailColors[offset+3]=color.r*fade*.55;trailColors[offset+4]=color.g*fade*.55;trailColors[offset+5]=color.b*fade*.55;
        }
    }
    const trailMat=startup.track(new THREE.LineBasicMaterial({vertexColors:true,transparent:true,opacity:.44,blending:THREE.AdditiveBlending,depthWrite:false}),value=>value.dispose());
    const trails=new THREE.LineSegments(trailGeo,trailMat);trails.layers.enable(1);trails.frustumCulled=false;scene.add(trails);
    const descentGeo=startup.track(new THREE.BufferGeometry().setFromPoints([new THREE.Vector3(),new THREE.Vector3()]),value=>value.dispose());
    const descentMat=startup.track(new THREE.LineBasicMaterial({color:'#7dfaff',transparent:true,opacity:.55,depthWrite:false}),value=>value.dispose());
    const descent=new THREE.Line(descentGeo,descentMat);descent.layers.enable(1);descent.frustumCulled=false;scene.add(descent);
    let trailsReady=false;
    function impact(point:THREE.Vector3,strength:number,color='#64eefa') {
        const screen=project(point);addWebContact(web.uniforms.contacts.value,{x:screen.x/width,y:screen.y/height},strength);
        const count=Math.ceil(strength*9);
        for(let i=0;i<count;i++){
            const spark=sparks[nextSpark++%particleCount],angle=i*2.399+sim;
            spark.position.copy(point);spark.velocity.set(Math.cos(angle)*35,Math.sin(angle)*35,16+i*2).multiplyScalar(strength);
            spark.color.set(color);spark.age=0;
        }
    }
    const targets=Array.from({length:8},()=>new THREE.Vector3());
    const renderedTips=Array.from({length:8},()=>new THREE.Vector3());
    const landingPoints=Array.from({length:8},()=>new THREE.Vector3());
    const landingTargets:(THREE.Vector3|null)[]=Array(8).fill(null);
    const occupiedTargets:(THREE.Vector3|null)[]=Array(8).fill(null);
    const occupied=new Set<number>();
    const projection=new THREE.Vector3(),screenA=new THREE.Vector2(),screenB=new THREE.Vector2();
    const travel=new THREE.Vector3(),candidate=new THREE.Vector3(),constrained=new THREE.Vector3();
    const contactBody=new THREE.Vector3(),contactEdge=new THREE.Vector3(),threadTop=new THREE.Vector3();
    const liftDesired=new THREE.Vector3(),liftOffset=new THREE.Vector3(),liftReach=new THREE.Vector3(),liftCorrection=new THREE.Vector3();
    const liftCorner=new THREE.Vector3(),liftScreen=new THREE.Vector2(),markerWorld=new THREE.Vector3();
    const strandA=new THREE.Vector3(),strandB=new THREE.Vector3();
    const liftBox={minX:0,maxX:0,minY:0,maxY:0};
    const supportAnchors: (SpiderAnchor|null)[]=Array(8).fill(null);
    let width=0,height=0,distance=1000,scale=1,dpr=1,sim=0,last=0,raf=0,disposed=false,documentHidden=document.hidden,contextLost=false;
    let active:Lift[]=[],anchors:SpiderAnchor[]=[],intent:Intent|null=null,signals:SpiderSignal[]=[];
    let nextScan=0,nextSupport=0,nextGrab=1.2,lastDiagnostic=0,nextContactDiagnostic=0,frames=0,fps=0,pulse=0;
    let sessionId:string|null=null,waiting=false,disconnected=false,streamEnergy=0,grabs=0,realGrabs=0,lastSignal:SpiderPhase='reset';
    let maxTipError=0,maxSourceError=0,contactSamples=0,sourceSamples=0,footfalls=0,maxRibbonWidth=0,maxRibbonHeight=0,stanceSamples=0,maxStanceError=0,hasBody=false,recentIds:string[]=[];
    let pointer={x:-1000,y:-1000,active:false};
    let exclusions:DOMRect[]=[],maxActive=3;
    let contentElement:HTMLElement|null=null;
    const bounds = () => {
        if(!contentElement?.isConnected)contentElement=document.querySelector<HTMLElement>('.chat-content');
        const r=contentElement?.getBoundingClientRect();
        const base=r && r.width>100 && r.height>100 ? r : new DOMRect(30,75,width-60,height-180);
        const view=window.visualViewport;
        if(!view)return base;
        const left=Math.max(base.left,view.offsetLeft),top=Math.max(base.top,view.offsetTop);
        return new DOMRect(left,top,Math.max(80,Math.min(base.right,view.offsetLeft+view.width)-left),Math.max(100,Math.min(base.bottom,view.offsetTop+view.height)-top));
    };
    function world(x:number,y:number,z=0,out=new THREE.Vector3()) { const k=(distance-z)/distance; return out.set((x-width/2)*k,(height/2-y)*k,z); }
    function project(v:THREE.Vector3,out=new THREE.Vector2()) { projection.copy(v).project(camera); return out.set((projection.x*.5+.5)*width,(.5-projection.y*.5)*height); }
    function placeLift(a:Lift,zoom:number) {
        a.group.scale.setScalar(zoom);
        liftOffset.set(a.gripX,0,1).multiplyScalar(zoom).applyEuler(a.group.rotation);
        a.group.position.copy(a.grip).sub(liftOffset);a.group.updateMatrixWorld(true);
    }
    function liftExtent(a:Lift) {
        liftBox.minX=liftBox.minY=Infinity;liftBox.maxX=liftBox.maxY=-Infinity;
        for(let corner=0;corner<4;corner++){
            liftCorner.set(corner===0||corner===3?-a.width/2:a.width/2,corner<2?-a.height/2:a.height/2,0).applyMatrix4(a.group.matrixWorld);
            project(liftCorner,liftScreen);
            liftBox.minX=Math.min(liftBox.minX,liftScreen.x);liftBox.maxX=Math.max(liftBox.maxX,liftScreen.x);
            liftBox.minY=Math.min(liftBox.minY,liftScreen.y);liftBox.maxY=Math.max(liftBox.maxY,liftScreen.y);
        }
        return liftBox;
    }
    function avoided(x:number,y:number,padding=16) { return exclusions.some(r=>x>r.left-padding&&x<r.right+padding&&y>r.top-padding&&y<r.bottom+padding) || pointer.active&&Math.hypot(x-pointer.x,y-pointer.y)<88; }
    function destroy(a:Lift) {
        scene.remove(a.group,a.tether);
        a.group.traverse(obj=> { const o=obj as THREE.Mesh; o.geometry?.dispose(); const materials=Array.isArray(o.material)?o.material:[o.material]; for(const mat of materials){if(!mat)continue;(mat as THREE.MeshBasicMaterial).map?.dispose();mat.dispose();} });
        a.tether.geometry.dispose();a.tether.material.dispose();
    }
    function clearLifts() { active.forEach(destroy);active=[];intent=null;supportAnchors.fill(null);recentIds=[];gait.reset();trailsReady=false; }
    function clearAtmosphere() {
        sparks.forEach(spark=>spark.age=2);
        (web.uniforms.contacts.value as THREE.Vector4[]).forEach(contact=>contact.set(0,0,0,-1));
        (web.uniforms.tensionA.value as THREE.Vector3[]).forEach(tension=>tension.set(0,0,0));
        (web.uniforms.tensionB.value as THREE.Vector3[]).forEach(tension=>tension.set(0,0,0));
        pulse=0;streamEnergy=0;lastSignal='reset';trailsReady=false;
    }
    function resize() {
        width=innerWidth;height=innerHeight;dpr=Math.min(devicePixelRatio||1,2);
        distance=height/(2*Math.tan(THREE.MathUtils.degToRad(38/2)));
        camera.aspect=width/height;camera.position.set(0,0,distance);camera.far=distance+5000;camera.updateProjectionMatrix();camera.updateMatrixWorld();
        renderer.setPixelRatio(dpr);renderer.setSize(width,height);baseTarget.setSize(Math.round(width*dpr),Math.round(height*dpr));glowTarget.setSize(Math.round(width*dpr/2),Math.round(height*dpr/2));
        rig.resize(width,height,dpr);web.uniforms.resolution.value.set(width,height);composite.uniforms.resolution.value.set(width,height);composite.uniforms.texel.value.set(2/(width*dpr),2/(height*dpr));
        const touch=matchMedia('(pointer: coarse)').matches;
        const phone=width<768 || touch&&Math.min(width,height)<600,tablet=!phone&&(width<=1180||touch);
        scale=phone?.72:tablet?.88:1.12;maxActive=phone?1:tablet?2:3;
        clearLifts();hasBody=false;nextScan=0;nextSupport=0;
        maxRibbonWidth=0;maxRibbonHeight=0;contactSamples=0;sourceSamples=0;stanceSamples=0;maxTipError=0;maxSourceError=0;maxStanceError=0;
    }
    function choose(toolUseId?:string) {
        const p=project(body);
        const available=anchors.filter(a=> !active.some(l=>l.anchor.id===a.id)&&!avoided(a.rect.left+a.rect.width/2,a.rect.top+a.rect.height/2)&&(!toolUseId||a.toolUseId===toolUseId));
        const fresh=available.filter(a=>!recentIds.includes(a.id));const pool=fresh.length?fresh:available;
        pool.sort((a,b)=>Math.hypot(a.rect.left+a.rect.width/2-p.x,a.rect.top-p.y)-Math.hypot(b.rect.left+b.rect.width/2-p.x,b.rect.top-p.y));
        return pool[Math.min(pool.length-1,toolUseId?0:Math.floor(sim)%Math.min(3,pool.length))]||null;
    }
    function makeLift(anchor:SpiderAnchor,phase:SpiderPhase|'ambient') {
        if(active.length>=maxActive)return;
        const p=project(body),left=anchor.rect.left+anchor.rect.width/2<p.x;
        const legPool=left?[0,2,1,3]:[5,7,4,6];const leg=legPool.find(n=>!active.some(a=>a.leg===n));if(leg===undefined)return;
        const pad=4,padY=2,w=anchor.rect.width+pad*2,h=anchor.rect.height+padY*2;
        const bitmap=document.createElement('canvas');bitmap.width=Math.ceil(w*3);bitmap.height=Math.ceil(h*3);
        const ctx=bitmap.getContext('2d');if(!ctx)return;ctx.scale(3,3);
        const color=phase==='failed'?'#ff6488':phase==='cancelled'?'#ffd08e':COLORS[grabs%COLORS.length];
        const bare=grabs%3!==0;ctx.fillStyle=bare?'rgba(5,8,17,.96)':color;ctx.fillRect(0,0,w,h);
        for(const run of anchor.runs){ctx.font=run.font;ctx.fillStyle=bare?color:'#090811';ctx.textBaseline='middle';ctx.fillText(run.text,run.x+pad,run.y+padY+run.height*.51);}
        const texture=new THREE.CanvasTexture(bitmap);texture.colorSpace=THREE.SRGBColorSpace;texture.minFilter=THREE.LinearFilter;texture.generateMipmaps=false;
        const material=new THREE.MeshBasicMaterial({map:texture,transparent:true,side:THREE.DoubleSide,depthTest:true,depthWrite:true,opacity:0});
        const group=new THREE.Group(),mesh=new THREE.Mesh(new THREE.PlaneGeometry(w,h),material);group.add(mesh);
        const occluder=new THREE.Mesh(new THREE.PlaneGeometry(w,h),new THREE.MeshBasicMaterial({color:0x000000,side:THREE.DoubleSide}));occluder.layers.set(1);group.add(occluder);
        const border=[[-w/2,-h/2,.5],[w/2,-h/2,.5],[w/2,h/2,.5],[-w/2,h/2,.5],[-w/2,-h/2,.5]].map(v=>new THREE.Vector3(...v));
        const outline=new THREE.Line(new THREE.BufferGeometry().setFromPoints(border),new THREE.LineBasicMaterial({color,transparent:true,opacity:0,depthTest:true}));group.add(outline);
        const gripX=(leg<4?1:-1)*w/2;
        const marker=new THREE.Mesh(new THREE.RingGeometry(2.1,3.5,16),new THREE.MeshBasicMaterial({color:'#e0fbff',transparent:true,side:THREE.DoubleSide,depthTest:true}));marker.position.set(gripX,0,1);group.add(marker);
        const geo=new THREE.BufferGeometry();geo.setAttribute('position',new THREE.BufferAttribute(new Float32Array(12),3));
        const tether=new THREE.LineSegments(geo,new THREE.LineBasicMaterial({color,transparent:true,opacity:0,depthWrite:false}));scene.add(group,tether);
        const a:Lift={anchor,group,mesh,marker,outline,tether,leg,width:w,height:h,gripX,grip:rig.renderedTip(leg).clone(),approachFrom:rig.renderedTip(leg).clone(),sourceGrip:new THREE.Vector3(),start:sim,duration:phase==='ambient'?4.0:3.0,angle:(leg<4?-1:1)*(.38+(grabs%3)*.28),zoom:2.3+(grabs%3)*.3,phase,releaseAt:null,color,frozen:false,tension:0,kicked:false};
        if(phase==='succeeded'||phase==='failed'||phase==='cancelled')a.releaseAt=sim+MIN_RELEASE_AGE;
        active.push(a);recentIds.push(anchor.id);if(recentIds.length>12)recentIds.shift();grabs++;if(phase!=='ambient')realGrabs++;
    }
    function release(a:Lift,immediate=false) {
        a.releaseAt=scheduleRelease(sim,a.start,a.duration,a.releaseAt,immediate);
    }
    function signal(signal:SpiderSignal) {
        if(disposed)return;
        const terminal=(phase:SpiderPhase|'ambient')=>phase==='succeeded'||phase==='failed'||phase==='cancelled';
        if(signal.phase==='reset') {
            const switched=signal.sessionId!==sessionId||signal.resetScope==='binding';
            sessionId=signal.sessionId;registry.setSession(sessionId);
            if(switched){clearLifts();clearAtmosphere();signals=[];anchors=[];}
            else {
                // A local idle/commit can arrive in the same frame as a 2 ms tool result.
                // Preserve that result long enough for React to mount its final tool anchor.
                signals=signals.filter(s=>!!s.toolUseId&&terminal(s.phase)&&Date.now()-s.at<3000);
                if(intent&&terminal(intent.phase)&&intent.anchor.toolUseId&&sim-intent.requested<3){
                    const id=intent.anchor.toolUseId;
                    if(!signals.some(s=>s.toolUseId===id))signals.push({phase:intent.phase as SpiderPhase,sessionId,toolUseId:id,at:Date.now()-(sim-intent.requested)*1000});
                }
                intent=null;
                active.forEach(a=>{
                    if(terminal(a.phase)){
                        release(a);
                        const id=a.anchor.toolUseId;
                        if(id&&!signals.some(s=>s.toolUseId===id))signals.push({phase:a.phase as SpiderPhase,sessionId,toolUseId:id,at:Date.now()});
                    } else release(a,true);
                });
            }
            nextScan=0;nextGrab=sim+.8;waiting=false;disconnected=false;return;
        }
        if(signal.sessionId!==sessionId)return;
        if(signal.phase==='streaming'){streamEnergy=1;return;}
        if(signal.toolUseId){
            const previous=signals.find(s=>s.toolUseId===signal.toolUseId);
            const current=active.find(a=>a.anchor.toolUseId===signal.toolUseId);
            const planned=intent?.anchor.toolUseId===signal.toolUseId?intent.phase:null;
            if((previous&&terminal(previous.phase)||current&&terminal(current.phase)||planned&&terminal(planned))&&!terminal(signal.phase))return;
            // One pending semantic action per tool; parameter preparation never overwrites its result.
            signals=signals.filter(s=>s.toolUseId!==signal.toolUseId);
        }
        signals.push(signal);if(signals.length>24)signals=signals.slice(-24);
    }
    function consumeSignals() {
        const terminal=(phase:SpiderPhase|'ambient')=>phase==='succeeded'||phase==='failed'||phase==='cancelled';
        const retry:SpiderSignal[]=[];
        const queued=signals.splice(0);
        for(const event of queued) {
            if(event.sessionId!==sessionId||Date.now()-event.at>=3000)continue;
            lastSignal=event.phase;
            if(event.phase==='disconnected'){disconnected=true;clearLifts();clearAtmosphere();lastSignal='disconnected';retry.length=0;break;}
            disconnected=false;
            if(event.phase==='waiting'){
                waiting=true;active.forEach(a=>release(a,!terminal(a.phase)));
                if(intent&&!terminal(intent.phase))intent=null;
                continue;
            }
            if(event.phase==='cancelled'&&!event.toolUseId){
                waiting=false;
                active.forEach(a=>{if(terminal(a.phase))release(a);else{a.phase='cancelled';release(a,true);}});
                if(intent&&!terminal(intent.phase))intent=null;
                for(let i=retry.length-1;i>=0;i--)if(!terminal(retry[i].phase))retry.splice(i,1);
                continue;
            }
            if(event.phase==='complete'){
                waiting=false;pulse=1;
                active.forEach(a=>release(a,!terminal(a.phase)));
                if(intent&&!terminal(intent.phase))intent=null;
                for(let i=retry.length-1;i>=0;i--)if(!terminal(retry[i].phase))retry.splice(i,1);
                continue;
            }
            if(!event.toolUseId){
                if(event.phase==='failed'){
                    waiting=false;pulse=1;
                    active.forEach(a=>release(a,!terminal(a.phase)));
                    if(intent&&!terminal(intent.phase))intent=null;
                }
                else if(event.phase==='preparing'){waiting=false;streamEnergy=Math.max(streamEnergy,.45);}
                // Global activity is not evidence that an arbitrary text node is executing.
                continue;
            }
            if(waiting&&!terminal(event.phase)){retry.push(event);continue;}
            if(event.phase==='executing')waiting=false;
            let current=active.find(a=>a.anchor.toolUseId===event.toolUseId);
            if(current&&!registry.measure(current.anchor)){
                destroy(current);active.splice(active.indexOf(current),1);current=undefined;
            }
            if(current){
                if(event.phase==='preparing')continue;
                if(terminal(current.phase)&&!terminal(event.phase))continue;
                if(current.phase==='ambient')realGrabs++;
                current.phase=event.phase;
                if(terminal(event.phase)){release(current);pulse=1;}
                continue;
            }
            if(intent&&intent.anchor.toolUseId!==event.toolUseId&&intent.phase!=='ambient'&&intent.phase!=='preparing'){
                retry.push(event);continue;
            }
            const a=choose(event.toolUseId);
            if(!a){retry.push(event);continue;}
            // Keep the original deadline while waiting for streaming/virtualized DOM to appear.
            const requested=sim-Math.max(0,Date.now()-event.at)/1000;
            if(event.phase==='preparing'){
                if(!intent||intent.phase==='ambient'||intent.phase==='preparing')intent={anchor:a,phase:'preparing',requested};
                continue;
            }
            if(event.phase==='executing'||terminal(event.phase)){
                if(active.length>=maxActive){const ambient=active.findIndex(l=>l.phase==='ambient');if(ambient>=0){destroy(active[ambient]);active.splice(ambient,1);}}
                if(active.length>=maxActive){retry.push(event);continue;}
                intent={anchor:a,phase:event.phase,requested};pulse=1;
            }
        }
        signals=[...retry,...signals].slice(-24);
    }
    function updateLifts() {
        const toRemove:Lift[]=[];
        for(const a of active) {
            const measured=registry.measure(a.anchor);
            if(!measured){toRemove.push(a);continue;}
            a.anchor=measured;
            const rect=measured.rect;
            if(avoided(rect.left+rect.width/2,rect.top+rect.height/2,4)){toRemove.push(a);continue;}
            const age=sim-a.start;
            const motion=sampleLiftMotion(age,a.releaseAt===null?a.duration-RETURN_DURATION:a.releaseAt-a.start);
            const releasing=motion.returning,lift=motion.elevation;
            a.tension=motion.tension;
            let zoom=1+(a.zoom-1)*Math.min(1,lift);
            const sourceX=(a.leg<4?rect.right+4:rect.left-4),sourceY=rect.top+rect.height/2;
            world(sourceX,sourceY,1,a.sourceGrip);
            const side=a.leg<4?-1:1;
            liftDesired.set(body.x+side*(58+(a.leg%4)*8)*scale,body.y+((a.leg%4)-1.5)*39*scale,body.z+82+Math.sin(age*2+a.leg)*9);
            if(!a.kicked&&age>=.46){a.kicked=true;impact(a.sourceGrip,.95,a.color);}
            a.grip.copy(a.sourceGrip).lerp(liftDesired,lift);
            a.group.rotation.set(lift*(.18+Math.sin(age*3+a.leg)*.2),motion.flip+lift*(a.phase==='failed'?.5:Math.sin(age*2.4)*.22),lift*(a.angle+Math.sin(age*4)*Math.exp(-Math.max(0,age-.7)*1.8)*.16));
            placeLift(a,zoom);
            let box=liftExtent(a);
            // Uniform 3D scale, measured at all four projected corners, never squash the glyphs.
            const maxWidth=Math.min(460,width*.72),maxHeight=Math.min(260,height*.36);
            const fit=Math.min(1,maxWidth/Math.max(1,box.maxX-box.minX),maxHeight/Math.max(1,box.maxY-box.minY));
            if(fit<1&&lift>.05){zoom*=fit;placeLift(a,zoom);box=liftExtent(a);}
            const dx=box.minX<12?12-box.minX:box.maxX>width-12?width-12-box.maxX:0;
            const dy=box.minY<65?65-box.minY:box.maxY>height-85?height-85-box.maxY:0;
            if(lift>.2&&(dx||dy)){
                liftCorrection.set(dx,-dy,0).multiplyScalar((distance-a.grip.z)/distance);
                a.group.position.add(liftCorrection);a.grip.add(liftCorrection);
            }
            // Screen-fit correction must never pull a contact beyond the fixed bone lengths.
            liftReach.copy(a.grip).sub(body);const limit=170*scale;
            if(liftReach.length()>limit){
                if(lift<.15){toRemove.push(a);continue;}
                liftReach.setLength(limit).add(body);
                a.group.position.add(liftCorrection.copy(liftReach).sub(a.grip));a.grip.copy(liftReach);
            }
            // The reach correction changes depth, so enforce the projected cap at its final position.
            placeLift(a,zoom);
            for(let pass=0;pass<3;pass++){
                box=liftExtent(a);
                const finalFit=Math.min(1,maxWidth/Math.max(1,box.maxX-box.minX),maxHeight/Math.max(1,box.maxY-box.minY));
                if(finalFit>=1||lift<=.05)break;
                zoom*=finalFit*.998;placeLift(a,zoom);
            }
            box=liftExtent(a);
            maxRibbonWidth=Math.max(maxRibbonWidth,box.maxX-box.minX);maxRibbonHeight=Math.max(maxRibbonHeight,box.maxY-box.minY);
            a.mesh.material.opacity=motion.opacity*.98;
            a.outline.material.opacity=(.48+motion.tension*.4)*motion.opacity;a.marker.material.opacity=motion.opacity;
            a.marker.material.color.set(a.phase==='failed'?'#ff5678':a.phase==='cancelled'?'#ffd08e':'#eeffff');
            if(a.phase==='failed')a.mesh.material.color.set('#ff7098');
            const contact=motion.contact*(a.phase==='failed'?1-smooth(.4,.95,releasing):1);
            if(age<.17){targets[a.leg].lerpVectors(a.approachFrom,a.grip,contact);targets[a.leg].z+=Math.sin(contact*Math.PI)*14*scale;}else targets[a.leg].lerp(a.grip,contact);
            const positions=a.tether.geometry.attributes.position as THREE.BufferAttribute;
            const broken=a.phase==='failed'?smooth(.1,.55,age)*.35:0;
            strandA.copy(a.sourceGrip).lerp(a.grip,.5-broken);strandB.copy(a.sourceGrip).lerp(a.grip,.5+broken);
            positions.setXYZ(0,a.sourceGrip.x,a.sourceGrip.y,a.sourceGrip.z);positions.setXYZ(1,strandA.x,strandA.y,strandA.z);
            positions.setXYZ(2,strandB.x,strandB.y,strandB.z);positions.setXYZ(3,a.grip.x,a.grip.y,a.grip.z);
            positions.needsUpdate=true;a.tether.material.opacity=Math.max(lift*.4,motion.tension*.6)*(a.phase==='failed'?.5:1);
            a.frozen=contact>.999;
            if(motion.finished){impact(a.sourceGrip,.38,a.color);toRemove.push(a);}
        }
        for(const a of toRemove){destroy(a);active.splice(active.indexOf(a),1);}
    }
    function draw(now:number) {
        raf=0;if(disposed||documentHidden||contextLost)return;
        const dt=last?Math.min((now-last)/1000,.045):.016;last=now;sim+=dt;
        advanceWebContacts(web.uniforms.contacts.value,dt);
        if(sim>=nextScan){
            nextScan=sim+.35;anchors=registry.sample();exclusions=registry.exclusionRects();
            exclusionMask.update(width,height,exclusions);
        }
        consumeSignals();
        const area=bounds();
        if(intent){const a=registry.measure(intent.anchor);if(!a||sim-intent.requested>3){intent=null;}else intent.anchor=a;}
        const bp=project(body);
        const selection=getSelection(),selected=Boolean(selection&&!selection.isCollapsed);
        if(sim>1.8&&!intent&&sim>=nextGrab&&active.length<maxActive&&!waiting&&!disconnected&&!selected){const a=choose();if(a)intent={anchor:a,phase:'ambient',requested:sim};nextGrab=sim+1.05+Math.random()*.6;}
        let x=area.left+area.width*(.52+Math.sin(sim*.22)*.2),y=area.top+area.height*(.47+Math.cos(sim*.3)*.17);
        if(intent){const r=intent.anchor.rect;const side=r.left+r.width/2<bp.x?1:-1;x=clamp(r.left+r.width/2+side*85*scale,area.left+70,area.right-70);y=clamp(r.top+r.height/2+45,area.top+70,area.bottom-60);}
        if(active.length&&!intent){const a=active[0].anchor.rect;x=clamp(a.left+a.width/2+95*scale,area.left+75,area.right-75);y=clamp(a.top+a.height/2+32,area.top+75,area.bottom-60);}
        if(pointer.active&&Math.hypot(x-pointer.x,y-pointer.y)<140){x+=x<pointer.x?-125:125;y-=35;}
        const bodyMargin=134*scale+10;
        x=clamp(x,bodyMargin,width-bodyMargin);y=clamp(y,75,height-80);
        const contactAnchor=intent?.anchor || active[0]?.anchor;
        if(contactAnchor){
            const r=contactAnchor.rect;
            const point=planContactBodyPoint(r,{x,y},{left:Math.max(area.left+20,bodyMargin),right:Math.min(area.right-20,width-bodyMargin),top:area.top+30,bottom:area.bottom-20},scale,(cx,cy)=>{
                const edge=cx>r.left+r.width/2?r.right+4:r.left-4;
                return avoided(cx,cy,6)||world(cx,cy,60,contactBody).distanceTo(world(edge,r.top+r.height/2,1,contactEdge))>145*scale;
            });
            // A protected control can block one side of a tool row; approach from another side.
            // If every nearby point is protected, wait locally and let that intent expire.
            if(point){x=point.x;y=point.y;}else{x=bp.x;y=bp.y;}
        }else if(avoided(x,y)){const safeY=clamp(area.top+area.height*.42,90,height-100);x=clamp(area.right-100,bodyMargin,width-bodyMargin);y=safeY;}
        const entrance=smooth(0,1.8,sim);
        y=(1-entrance)*(-150)+entrance*y;
        world(x,y,60,desiredBody);
        occupied.clear();for(const a of active)occupied.add(a.leg);
        if(!hasBody){body.copy(desiredBody);hasBody=true;}else {
            travel.copy(desiredBody).sub(body).multiplyScalar(1-Math.exp(-dt*(intent?5:1.9)));
            if(entrance>=1)travel.clampLength(0,dt*scale*(intent?66:44));
            candidate.copy(body).add(travel);
            body.copy(entrance<1?candidate:gait.constrainBody(body,candidate,scale,occupied,constrained));
            const desiredHeading=clamp(-travel.x*1.3,-.32,.32);
            heading+=(desiredHeading-heading)*(1-Math.exp(-dt*3));
        }
        if(intent&&intent.phase!=='preparing'){
            const r=intent.anchor.rect,bodyScreen=project(body),gx=bodyScreen.x>r.left+r.width/2?r.right+4:r.left-4;
            if(world(gx,r.top+r.height/2,1,contactEdge).distanceTo(body)<164*scale){makeLift(intent.anchor,intent.phase);intent=null;}
        }
        if(sim>=nextSupport){
            nextSupport=sim+.85;
            for(let i=0;i<8;i++){
                const side=i<4?-1:1,row=i%4;const desired=project(new THREE.Vector3(body.x+side*118*scale,body.y+(1.5-row)*66*scale,1));
                const best=anchors.filter(a=>!active.some(l=>l.anchor.id===a.id)&&!avoided(a.rect.left+a.rect.width/2,a.rect.top+a.rect.height/2)).sort((a,b)=>Math.hypot(a.rect.left-desired.x,a.rect.top-desired.y)-Math.hypot(b.rect.left-desired.x,b.rect.top-desired.y))[0];
                supportAnchors[i]=best&&Math.hypot(best.rect.left-desired.x,best.rect.top-desired.y)<65*scale?best:null;
            }
        }
        for(let i=0;i<8;i++){
            const anchor=supportAnchors[i];
            const measured=anchor&&registry.measure(anchor);
            landingTargets[i]=measured?world(i<4?measured.rect.right:measured.rect.left,measured.rect.top+measured.rect.height/2,1,landingPoints[i]):null;
        }
        occupied.clear();for(const a of active)occupied.add(a.leg);
        for(let leg=0;leg<8;leg++)occupiedTargets[leg]=occupied.has(leg)?targets[leg]:null;
        if(entrance<1)gait.reset();
        const step=gait.update({time:sim,dt,body,scale,heading,occupied,landingTargets,
            occupiedTargets},gaitStep);
        step.targets.forEach((target,i)=>targets[i].copy(target));
        for(const foot of step.touchdowns){impact(foot.point,foot.strength*.55);footfalls++;}
        updateLifts();
        let force=0,lean=0;
        for(const a of active){const m=sampleLiftMotion(sim-a.start,a.releaseAt===null?a.duration-RETURN_DURATION:a.releaseAt-a.start);force+=m.tension*6-m.recoil*8;lean+=(a.leg<4?1:-1)*(m.tension*.11-m.recoil*.09);}
        posedBody.copy(body);posedBody.z+=step.bodyBob+force*scale;
        rig.update({time:sim,body:posedBody,targets,scale,heading,roll:step.bodyRoll+lean,pitch:force*.008,
            energy:waiting?.1:.5+streamEnergy*.5+pulse*.35,hue:waiting?.10:lastSignal==='failed'?.04:0});
        // One read of each actual rendered bone endpoint serves both trails and diagnostics.
        for(let leg=0;leg<8;leg++)rig.renderedTip(leg,renderedTips[leg]);
        const sampleContact=sim>=nextContactDiagnostic;
        if(sampleContact)nextContactDiagnostic=sim+.1;
        if(sampleContact&&entrance>=1)for(let leg=0;leg<8;leg++)if(step.planted[leg]&&!occupied.has(leg)){
            const actual=project(renderedTips[leg],screenA),planted=project(step.targets[leg],screenB);
            stanceSamples++;maxStanceError=Math.max(maxStanceError,Math.hypot(actual.x-planted.x,actual.y-planted.y));
        }
        for(const a of active)if(a.frozen&&(sampleContact||sim-a.start>.18&&sim-a.start<.28)){
            // Independent evidence: the final rendered bone endpoint versus the transformed mesh edge.
            const tip=project(renderedTips[a.leg],screenA),grip=project(a.marker.getWorldPosition(markerWorld),screenB);
            contactSamples++;maxTipError=Math.max(maxTipError,Math.hypot(tip.x-grip.x,tip.y-grip.y));
            if(sim-a.start>.18&&sim-a.start<.28){
                const r=a.anchor.range.getBoundingClientRect();
                const edgeX=a.leg<4?r.right+4:r.left-4;
                sourceSamples++;maxSourceError=Math.max(maxSourceError,Math.hypot(tip.x-edgeX,tip.y-(r.top+r.height/2)));
            }
        }
        const sparkDrag=Math.exp(-dt*3);
        for(let i=0;i<particleCount;i++){
            const spark=sparks[i];spark.age+=dt;spark.position.addScaledVector(spark.velocity,dt);spark.velocity.multiplyScalar(sparkDrag);
            const offset=i*3;spark.position.toArray(particlePositions,offset);
            const fade=Math.max(0,1-spark.age/.62)**2;
            particleColors[offset]=spark.color.r*fade;particleColors[offset+1]=spark.color.g*fade;particleColors[offset+2]=spark.color.b*fade;
        }
        particleGeo.attributes.position.needsUpdate=true;particleGeo.attributes.color.needsUpdate=true;
        particles.material.opacity=.72;
        for(let leg=0;leg<8;leg++){
            const history=trailHistory[leg],tip=renderedTips[leg];
            if(!trailsReady)history.forEach(p=>p.copy(tip));
            for(let i=trailSteps-1;i>0;i--)history[i].copy(history[i-1]);history[0].copy(tip);
            for(let i=0;i<trailSteps-1;i++){
                const offset=(leg*(trailSteps-1)+i)*6;
                history[i].toArray(trailPositions,offset);history[i+1].toArray(trailPositions,offset+3);
            }
        }
        world(project(body,screenA).x,-20,-20,threadTop);const threadPositions=descentGeo.attributes.position as THREE.BufferAttribute;
        threadPositions.setXYZ(0,threadTop.x,threadTop.y,threadTop.z);threadPositions.setXYZ(1,body.x,body.y-16*scale,body.z);
        threadPositions.needsUpdate=true;descentMat.opacity=.6*(1-smooth(1.6,2.4,sim));
        trailsReady=true;trailGeo.attributes.position.needsUpdate=true;
        const tensionA=web.uniforms.tensionA.value as THREE.Vector3[],tensionB=web.uniforms.tensionB.value as THREE.Vector3[];
        tensionA.forEach(v=>v.set(0,0,0));tensionB.forEach(v=>v.set(0,0,0));
        active.slice(0,3).forEach((a,i)=>{
            const source=project(a.sourceGrip,screenA),grip=project(a.grip,screenB);
            const strength=Math.min(1,a.sourceGrip.distanceTo(a.grip)/(100*scale)+a.tension*.4)*(a.phase==='failed'?.25:1);
            tensionA[i].set(source.x/width,source.y/height,strength);tensionB[i].set(grip.x/width,grip.y/height,strength);
        });
        web.uniforms.time.value=sim;web.uniforms.pulse.value=pulse;const screenBody=project(body);web.uniforms.body.value.set(screenBody.x/width,screenBody.y/height);
        composite.uniforms.pointer.value.set(pointer.x,pointer.y,pointer.active?1:0);
        renderer.setRenderTarget(baseTarget);renderer.clear();camera.layers.set(0);renderer.render(scene,camera);
        renderer.setRenderTarget(glowTarget);renderer.clear();camera.layers.set(1);renderer.render(scene,camera);camera.layers.set(0);
        renderer.setRenderTarget(null);renderer.clear();renderer.render(outputScene,outputCamera);
        pulse=Math.max(0,pulse-dt*.6);streamEnergy=Math.max(0,streamEnergy-dt*.5);frames++;
        if(now-lastDiagnostic>1000){fps=Math.round(frames*1000/(now-lastDiagnostic));frames=0;lastDiagnostic=now;
            host.dataset.diagnostics=JSON.stringify({viewport:{width,height,dpr},fps,grabs,realGrabs,active:active.length,anchors:anchors.length,phase:lastSignal,sessionId,waiting,disconnected,maxActive,pending:intent?{phase:intent.phase,age:+(sim-intent.requested).toFixed(2)}:null,queued:signals.length,contactSamples,sourceSamples,stanceSamples,maxStanceError:+maxStanceError.toFixed(3),footfalls,airborne:step.planted.filter((p,i)=>!p&&!occupied.has(i)).length,maxRibbonWidth:+maxRibbonWidth.toFixed(1),maxRibbonHeight:+maxRibbonHeight.toFixed(1),maxTipError:+maxTipError.toFixed(3),maxSourceError:+maxSourceError.toFixed(3),textures:renderer.info.memory.textures,geometries:renderer.info.memory.geometries,body:screenBody,source:'live-ui',running:true});
        }
        scheduleFrame();
    }
    function frame(now:number) {try{registry.beginFrame();draw(now);}catch{host.dataset.state='fallback';dispose();}finally{registry.endFrame();}}
    function onPointer(event:PointerEvent){if(event.pointerType==='mouse')pointer={x:event.clientX,y:event.clientY,active:true};}
    function onPointerOut(event:PointerEvent){if(!event.relatedTarget)pointer.active=false;}
    function onSelection(){const selection=getSelection();if(selection&&!selection.isCollapsed)clearLifts();nextScan=0;}
    function scheduleFrame(){if(!disposed&&!documentHidden&&!contextLost&&!raf)raf=requestAnimationFrame(frame);}
    function onVisibility(){documentHidden=document.hidden;if(documentHidden){cancelAnimationFrame(raf);raf=0;}else {last=0;nextScan=0;scheduleFrame();}}
    function onContextLost(event:Event){event.preventDefault();contextLost=true;cancelAnimationFrame(raf);raf=0;clearLifts();host.dataset.state='fallback';}
    function onContextRestored(){if(disposed)return;contextLost=false;last=0;resize();host.dataset.state='ready';scheduleFrame();}
    function onLayout(){nextScan=0;nextSupport=0;gait.reset();trailsReady=false;}
    const stopRuntime=startup.track(createSpiderRuntimeAdapter(signal), value => value.dispose());
    const observer=startup.track(new ResizeObserver(onLayout), value => value.disconnect());const root=document.querySelector('#root');if(root)observer.observe(root);
    startup.defer(removeListeners);
    window.addEventListener('resize',resize);window.visualViewport?.addEventListener('resize',resize);
    window.addEventListener('pointermove',onPointer,{passive:true});window.addEventListener('pointerout',onPointerOut,{passive:true});
    window.addEventListener('scroll',onLayout,true);document.addEventListener('selectionchange',onSelection);document.addEventListener('visibilitychange',onVisibility);
    canvas.addEventListener('webglcontextlost',onContextLost);canvas.addEventListener('webglcontextrestored',onContextRestored);
    resize();host.dataset.state='ready';lastDiagnostic=performance.now();scheduleFrame();
    function removeListeners(){
        window.removeEventListener('resize',resize);window.visualViewport?.removeEventListener('resize',resize);window.removeEventListener('pointermove',onPointer);window.removeEventListener('pointerout',onPointerOut);window.removeEventListener('scroll',onLayout,true);document.removeEventListener('selectionchange',onSelection);document.removeEventListener('visibilitychange',onVisibility);
        canvas.removeEventListener('webglcontextlost',onContextLost);canvas.removeEventListener('webglcontextrestored',onContextRestored);
    }
    function dispose(){
        if(disposed)return;disposed=true;cancelAnimationFrame(raf);stopRuntime.dispose();registry.dispose();observer.disconnect();clearLifts();rig.dispose();
        particleGeo.dispose();particleMaterial.dispose();trailGeo.dispose();trailMat.dispose();descentGeo.dispose();descentMat.dispose();background.geometry.dispose();web.dispose();quad.geometry.dispose();composite.dispose();exclusionMask.dispose();baseTarget.dispose();glowTarget.dispose();
        removeListeners();renderer.dispose();renderer.forceContextLoss();canvas.remove();host.dataset.diagnostics=JSON.stringify({running:false,active:0,textures:0});
    }
    return dispose;
}
