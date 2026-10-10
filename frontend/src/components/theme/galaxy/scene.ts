import * as THREE from 'three';
import { MAX_GALAXY_ANCHORS } from './limits';
export type GalaxyPhase = 'idle' | 'search' | 'read' | 'compose' | 'approval' | 'error' | 'cancel' | 'complete' | 'launch' | 'edit';
export interface Bounds {
    x: number;
    y: number;
    w: number;
    h: number;
}
export interface GalaxyAnchor {
    id: string;
    x: number;
    y: number;
    kind: 'file' | 'tool' | 'result' | 'input';
}
export type FileState = 'hidden' | 'discovered' | 'reading' | 'read' | 'changed';
export interface GalaxyFile {
    id: string;
    path: string;
    label: string;
}
type TravelKind = 'launch' | 'read' | 'edit' | 'compose';
interface Travel {
    fileId?: string;
    targetId: string;
    kind?: TravelKind;
}
interface HeroJob extends Travel {
    id: number;
    kind: TravelKind;
    started: number;
    duration: number;
    source: THREE.Vector3;
    target: THREE.Vector3;
    center: THREE.Vector3;
    abortAt: number | null;
    abortKind: string | null;
    abortProgress: number;
    pauseAt: number | null;
}
interface FileEntry {
    id: string;
    path: string;
    labelText: string;
    state: FileState;
    stateAt?: number;
    local: THREE.Vector3;
    group: THREE.Group;
    dot: THREE.Mesh<THREE.IcosahedronGeometry, THREE.MeshBasicMaterial>;
    aura: THREE.Sprite;
    index: number;
}
/** Self-contained, procedurally generated galaxy. No remote textures or network requests. */
export function createGalaxy(canvas: HTMLCanvasElement, { onReady, onError }: {
    onReady?: () => void;
    onError?: (error: unknown) => void;
} = {}) {
    let renderer: THREE.WebGLRenderer;
    const noop = () => { };
    const unavailable = { setPhase: noop, setPaused: noop, setFocus: noop, setSession: noop, setFileState: noop, travel: noop, setReadingZones: noop, setAnchors: noop, setActiveAnchor: noop, setImmersive: noop, warp: noop, resize: noop, dispose: noop, getStats: () => ({ ready: false, frames: 0 }) };
    try {
        renderer = new THREE.WebGLRenderer({ canvas, antialias: false, alpha: false, powerPreference: 'high-performance' });
    }
    catch (error) {
        canvas.dataset.ready = 'false';
        onError?.(error);
        return unavailable;
    }
    const disposable: {
        dispose(): void;
    }[] = [];
    let releaseScene: (() => void) | undefined;
    try {
        renderer.outputColorSpace = THREE.SRGBColorSpace;
        renderer.toneMapping = THREE.NoToneMapping;
        renderer.info.autoReset = false;
        renderer.setClearColor(0x010208, 1);
        const scene = new THREE.Scene();
        const camera = new THREE.PerspectiveCamera(44, 1, 0.1, 600);
        camera.position.set(0, 0, 82);
        const galaxy = new THREE.Group();
        galaxy.rotation.set(0.96, -0.19, 0.44);
        scene.add(galaxy);
        let width = 1, height = 1, pixelRatio = 1, frames = 0, paused = false, disposed = false, contextAvailable = true, shaderFailed = false;
        renderer.debug.onShaderError = (gl, program, vertex, fragment) => {
            shaderFailed = true;
            canvas.dataset.ready = 'false';
            onError?.(new Error('Galaxy shader compilation failed: ' + (gl.getProgramInfoLog(program) || gl.getShaderInfoLog(fragment) || gl.getShaderInfoLog(vertex) || 'unknown error')));
        };
        let time = 0, last = performance.now(), raf = 0, phase: GalaxyPhase = 'idle', phaseEnergy = 0, phaseTarget = 0;
        let warpStarted = -100, completionStarted = -100, firstFrame = true, immersive = false;
        const layout = { x: 0, y: 0, scale: 1 };
        const pointer = new THREE.Vector2(), smoothedPointer = new THREE.Vector2();
        const focus = new THREE.Vector2(0.37, 0.55);
        const maxAnchors = MAX_GALAXY_ANCHORS, anchorList: GalaxyAnchor[] = [], anchorMap = new Map<string, GalaxyAnchor>();
        let anchorsConfigured = false, activeAnchorId: string | null = null, resolvedAnchorId: string | null = null, retainedAnchorId: string | null = null, phaseStarted = 0;
        const interactionUniforms = { uMode: { value: 0 }, uPhaseTime: { value: 0 }, uDomOpacity: { value: 0 } };
        const flowStart = new THREE.Vector3(), flowEnd = new THREE.Vector3(), vTemp = new THREE.Vector3();
        const globalUniforms = { uTime: { value: 0 }, uEnergy: { value: 0 }, uWarp: { value: 0 }, uDpr: { value: 1 }, uComplete: { value: 0 }, uFlareCenter: { value: new THREE.Vector3() }, uFlareStrength: { value: 0 }, uFlareColor: { value: new THREE.Color(0x348cff) }, uFlowColor: { value: new THREE.Color(0x348cff) }, uFlowAccent: { value: new THREE.Color(0xa765ff) } };
        let randomState = 0x65d14a;
        function random() { randomState = (Math.imul(randomState, 1664525) + 1013904223) >>> 0; return randomState / 4294967296; }
        function gaussian() { return Math.sqrt(-2 * Math.log(Math.max(1e-7, random()))) * Math.cos(6.2831853 * random()); }
        function material(parameters: THREE.ShaderMaterialParameters & {
            fragmentShader: string;
        }) {
            // ShaderMaterial must explicitly encode its linear-light output for the sRGB framebuffer.
            const end = parameters.fragmentShader.lastIndexOf('}');
            parameters.fragmentShader = parameters.fragmentShader.slice(0, end) + '\n#include <colorspace_fragment>\n' + parameters.fragmentShader.slice(end);
            const result = new THREE.ShaderMaterial(parameters);
            disposable.push(result);
            return result;
        }
        function geometry(attributes: Record<string, [
            Float32Array,
            number
        ]>) {
            const result = new THREE.BufferGeometry();
            for (const [key, [data, size]] of Object.entries(attributes))
                result.setAttribute(key, new THREE.BufferAttribute(data, size));
            disposable.push(result);
            return result;
        }
        const noise = `
    float hash(vec2 p) { p=fract(p*vec2(123.34,456.21)); p+=dot(p,p+45.32); return fract(p.x*p.y); }
    float noise2(vec2 p) { vec2 i=floor(p),f=fract(p); f=f*f*(3.-2.*f); return mix(mix(hash(i),hash(i+vec2(1,0)),f.x),mix(hash(i+vec2(0,1)),hash(i+vec2(1)),f.x),f.y); }
    float fbm(vec2 p) { float v=0.,a=.5; mat2 m=mat2(.8,.6,-.6,.8); for(int i=0;i<5;i++){v+=a*noise2(p);p=m*p*2.04+13.17;a*=.5;}return v; }
  `;
        const planeVertex = `varying vec2 vUv; void main(){vUv=uv;gl_Position=projectionMatrix*modelViewMatrix*vec4(position,1.);}`;
        // Distant, extremely restrained interstellar colour; the empty space stays genuinely dark.
        const skyGeometry = new THREE.PlaneGeometry(350, 250);
        disposable.push(skyGeometry);
        const skyMaterial = material({ vertexShader: planeVertex, fragmentShader: `
    varying vec2 vUv; uniform float uTime; ${noise}
    void main(){vec2 p=vUv*2.-1.; float n=fbm(p*3.8+vec2(uTime*.0006,0.));
      float band=exp(-pow((p.y-p.x*.16+.12)*3.4,2.));
      vec3 c=vec3(.0003,.0006,.002)+vec3(.0015,.004,.026)*band*pow(n,3.1);
      c+=vec3(.009,.0008,.030)*exp(-length(p-vec2(.34,.05))*4.)*n*.4;
      gl_FragColor=vec4(c,1.); }`, uniforms: globalUniforms, depthWrite: false, depthTest: false });
        const sky = new THREE.Mesh(skyGeometry, skyMaterial);
        sky.position.z = -95;
        sky.renderOrder = -100;
        scene.add(sky);
        // Sub-pixel stars are mixed with a small, deliberately sparse set of luminous coloured stars.
        const backgroundCount = 8400;
        const bgPositions = new Float32Array(backgroundCount * 3), bgColors = new Float32Array(backgroundCount * 3), bgSizes = new Float32Array(backgroundCount), bgSeeds = new Float32Array(backgroundCount);
        for (let i = 0; i < backgroundCount; i++) {
            bgPositions.set([(random() - .5) * 310, (random() - .5) * 210, -140 + random() * 172], i * 3);
            const warm = random();
            const c = warm < .06 ? [.96, .77, .89] : warm < .65 ? [.24, .54, 1] : [.57, .76, 1];
            const light = .12 + Math.pow(random(), 3) * .42;
            bgColors.set(c.map(x => x * light), i * 3);
            bgSizes[i] = .46 + Math.pow(random(), 9) * 3.1;
            bgSeeds[i] = random() * 6.283;
        }
        const backgroundMaterial = material({
            uniforms: globalUniforms, transparent: true, depthWrite: false, blending: THREE.AdditiveBlending,
            vertexShader: `attribute vec3 aColor; attribute float aSize,aSeed; varying vec3 vColor; varying float vSeed; uniform float uTime,uDpr,uWarp;
      void main(){vec3 p=position;p.z+=uWarp*7.;vec4 mv=modelViewMatrix*vec4(p,1.);gl_Position=projectionMatrix*mv;
      gl_PointSize=clamp(aSize*uDpr*(105./-mv.z),.7,8.);vColor=aColor;vSeed=aSeed;}`,
            fragmentShader: `varying vec3 vColor; varying float vSeed; uniform float uTime; void main(){vec2 p=gl_PointCoord-.5;float r=length(p)*2.;if(r>1.)discard;
      float core=exp(-r*r*9.);float twinkle=.86+.14*sin(uTime*.47+vSeed);gl_FragColor=vec4(vColor*twinkle,core*.75);}`
        });
        const background = new THREE.Points(geometry({ position: [bgPositions, 3], aColor: [bgColors, 3], aSize: [bgSizes, 1], aSeed: [bgSeeds, 1] }), backgroundMaterial);
        background.renderOrder = -80;
        background.frustumCulled = false;
        scene.add(background);
        const radius = 32;
        const diskGeometry = new THREE.PlaneGeometry(radius * 2.2, radius * 2.2);
        disposable.push(diskGeometry);
        const diskUniforms = { ...globalUniforms, uRadius: { value: radius } };
        const gasMaterial = material({
            uniforms: { ...diskUniforms, uLayer: { value: 0 } }, transparent: true, blending: THREE.AdditiveBlending, depthWrite: false, side: THREE.DoubleSide,
            vertexShader: planeVertex,
            fragmentShader: `varying vec2 vUv; uniform float uTime,uEnergy,uRadius,uLayer,uFlareStrength;uniform vec3 uFlareCenter,uFlareColor; ${noise}
      void main(){vec2 p=(vUv-.5)*uRadius*2.2;float r=length(p);float th=atan(p.y,p.x)-2.02*log(r*.42+.45);
        vec2 nP=vec2(r*.7,th*3.);float wisps=fbm(p*.58+vec2(uTime*.0008+uLayer*.24,uLayer*.33));
        float winding=th+(wisps-.5)*.65;
        float arm=pow(max(0.,.5+.5*cos(winding*2.)),3.5);
        float branch=pow(max(0.,.5+.5*cos(winding*4.+r*.14)),7.)*.42;
        float inner=exp(-r*.225);float envelope=(1.-smoothstep(24.,33.,r))*smoothstep(.3,4.,r);
        float fine=fbm(p*2.4);float gas=(arm+branch+.045)*envelope*(.024+wisps*.110)*(.34+fine*.92);
        // Hue belongs to a spatial band. Mix bounded hues instead of adding three washes.
        float outer=smoothstep(10.,23.,r);vec3 violet=vec3(.30,.008,.95);vec3 sapphire=vec3(.006,.17,1.);
        vec3 cold=mix(violet,sapphire,outer);cold=mix(cold,vec3(.012,.39,1.),outer*pow(fine,3.)*.48);
        float roseBand=pow(max(0.,.5+.5*cos((winding+.25)*2.)),10.)*envelope*smoothstep(4.,9.,r)*(1.-smoothstep(23.,30.,r));
        float roseSector=smoothstep(-.45,.45,sin(th+.6));
        float roseWeight=roseBand*(.52+roseSector*.48);
        vec3 rose=vec3(1.,.008,.24);vec3 color=mix(cold,rose,roseWeight*.92)*gas;
        color*=1.+roseWeight*.38;
        // A cooler pearl centre stays luminous without spreading beige over the violet arms.
        float innerHalo=exp(-pow((r-2.6)/1.45,2.));
        color+=vec3(.62,.008,.36)*innerHalo*(.034+fine*.012);
        color+=vec3(.88,.88,1.)*exp(-r*r/5.2)*.043+vec3(.95,.96,1.)*exp(-r*r/.82)*.235;
        float nursery=smoothstep(.59,.80,noise2(vec2(r*.63,th*4.)))*roseBand;
        color+=rose*nursery*.040;
        if(abs(uLayer)>.5)color*=.13*smoothstep(3.,7.,r);
        vec2 lightDelta=p-uFlareCenter.xy;float localLight=exp(-dot(lightDelta,lightDelta)/12.)*exp(-abs(uFlareCenter.z)*.075)*uFlareStrength;
        color+=uFlareColor*localLight*(.035+wisps*.11);
        gl_FragColor=vec4(color,1.); }`
        });
        const gas = new THREE.Mesh(diskGeometry, gasMaterial);
        gas.position.z = -.22;
        gas.renderOrder = 1;
        galaxy.add(gas);
        // 205,000 individually positioned stars, including a genuine 3-D central stellar bulge.
        const starCount = 205000;
        const positions = new Float32Array(starCount * 3), colors = new Float32Array(starCount * 3), sizes = new Float32Array(starCount), seeds = new Float32Array(starCount);
        function starPosition(): [
            number,
            number,
            number,
            number,
            boolean
        ] {
            const family = random();
            let r, angle, z;
            if (family < .235) {
                const x = gaussian() * (1.2 + random() * 1.3), y = gaussian() * (1.2 + random() * 1.3);
                return [x, y, gaussian() * 1.0, Math.hypot(x, y), true];
            }
            do {
                r = -7.1 * Math.log(Math.max(1e-8, random() * random()));
            } while (r > radius || r < .55);
            const arm = random() > .5 ? 0 : Math.PI;
            const drift = gaussian() * (.19 + .29 * r / radius);
            angle = arm + 2.02 * Math.log(r * .42 + .45) + drift;
            if (family > .9)
                angle = random() * Math.PI * 2;
            else if (family > .72)
                angle += Math.PI * .5;
            z = gaussian() * (.055 + .12 * Math.exp(-r * .08));
            return [Math.cos(angle) * r, Math.sin(angle) * r, z, r, false];
        }
        for (let i = 0; i < starCount; i++) {
            const [x, y, z, r, bulge] = starPosition();
            positions.set([x, y, z], i * 3);
            const mix = random();
            const outer = THREE.MathUtils.smoothstep(r, 7, 23), core = Math.exp(-r * r / 3.2);
            let c = [.32 * (1 - outer) + .006 * outer, .012 * (1 - outer) + .18 * outer, .95 + outer * .05];
            const innerRose = Math.exp(-Math.pow((r - 2.6) / 1.5, 2));
            c = c.map((v, j) => v * (1 - innerRose * .40) + [.92, .014, .42][j] * innerRose * .40);
            c = c.map((v, j) => v * (1 - core) + [.92, .94, 1.][j] * core);
            const winding = Math.atan2(y, x) - 2.02 * Math.log(r * .42 + .45);
            const rose = Math.pow(Math.max(0, .5 + .5 * Math.cos((winding + .25) * 2)), 10) * THREE.MathUtils.smoothstep(r, 5, 10) * (1 - THREE.MathUtils.smoothstep(r, 23, 30));
            if (mix < rose * .62)
                c = [1., .012, .30];
            if (mix > .978)
                c = [.66, .83, 1.];
            const light = (bulge ? .17 : .35) * (.32 + Math.pow(random(), 1.7) * .68);
            colors.set(c.map(v => v * light), i * 3);
            sizes[i] = .58 + Math.pow(random(), 6) * (bulge ? .84 : 1.6);
            seeds[i] = random() * Math.PI * 2;
        }
        const starsMaterial = material({
            uniforms: globalUniforms, transparent: true, blending: THREE.AdditiveBlending, depthWrite: false,
            vertexShader: `attribute vec3 aColor; attribute float aSize,aSeed; varying vec3 vColor; varying float vSeed;
      uniform float uTime,uEnergy,uDpr; void main(){vec3 p=position;
        vec4 mv=modelViewMatrix*vec4(p,1.);gl_Position=projectionMatrix*mv;gl_PointSize=clamp(aSize*uDpr*82./-mv.z,.75,5.5);
        vColor=aColor;vSeed=aSeed;}`,
            fragmentShader: `varying vec3 vColor; varying float vSeed; uniform float uTime; void main(){vec2 p=gl_PointCoord-.5;float d=dot(p,p)*4.;if(d>1.)discard;
      float a=exp(-d*5.7);gl_FragColor=vec4(vColor*(.93+.07*sin(uTime*.24+vSeed)),a*.82);}`
        });
        const stars = new THREE.Points(geometry({ position: [positions, 3], aColor: [colors, 3], aSize: [sizes, 1], aSeed: [seeds, 1] }), starsMaterial);
        stars.renderOrder = 2;
        stars.frustumCulled = false;
        galaxy.add(stars);
        // The dark lanes really occlude the diffuse star field, rather than painting more light onto it.
        const dustMaterial = material({
            uniforms: { ...diskUniforms, uLayer: { value: 0 } }, transparent: true, blending: THREE.NormalBlending, depthWrite: false, side: THREE.DoubleSide,
            vertexShader: planeVertex,
            fragmentShader: `varying vec2 vUv; uniform float uTime,uRadius,uLayer; ${noise}
      void main(){vec2 p=(vUv-.5)*uRadius*2.2;float r=length(p);float th=atan(p.y,p.x)-2.02*log(r*.42+.45);
        float large=fbm(p*.60+uLayer*.13);float grain=fbm(p*3.4+uLayer*.22);float turbulent=(large-.5)*.72;
        float phase=(th+turbulent)*2.;float lane=pow(max(0.,.5+.5*cos(phase+.53)),17.);
        float threads=pow(max(0.,.5+.5*cos(phase*2.-r*.14+.4)),24.)*.32;
        float fringe=smoothstep(1.2,3.5,r)*(1.-smoothstep(23.,32.,r));
        float cloud=clamp((large-.25)*1.8,0.,1.);
        float opacity=(lane+threads)*fringe*cloud*(.76+grain*.65);
        // Deep indigo structure separates the luminous arms without a muddy brown wash.
        vec3 dust=mix(vec3(.0004,.0007,.003),vec3(.003,.001,.011),grain*.5);
        gl_FragColor=vec4(dust,min(.76,opacity*.70)*mix(1.,.32,abs(uLayer))); }`
        });
        const dust = new THREE.Mesh(diskGeometry, dustMaterial);
        dust.position.z = .4;
        dust.renderOrder = 3;
        galaxy.add(dust);
        // Thin volumetric slices make the cloud/dust layers separate under a moving camera.
        for (const layer of [-1, 1]) {
            const cloudMaterial = gasMaterial.clone();
            cloudMaterial.uniforms = { ...globalUniforms, uRadius: diskUniforms.uRadius, uLayer: { value: layer } };
            disposable.push(cloudMaterial);
            const cloud = new THREE.Mesh(diskGeometry, cloudMaterial);
            cloud.position.z = layer * 1.30;
            cloud.rotation.z = layer * .011;
            cloud.scale.setScalar(1 + layer * .009);
            cloud.renderOrder = layer < 0 ? .8 : 3.3;
            galaxy.add(cloud);
        }
        const upperDustMaterial = dustMaterial.clone();
        upperDustMaterial.uniforms = { ...globalUniforms, uRadius: diskUniforms.uRadius, uLayer: { value: 1 } };
        disposable.push(upperDustMaterial);
        const upperDust = new THREE.Mesh(diskGeometry, upperDustMaterial);
        upperDust.position.z = 1.05;
        upperDust.renderOrder = 3.5;
        galaxy.add(upperDust);
        // Bright stellar clusters sit above the dark lanes: small cores with restrained diffraction.
        const glintCount = 1120;
        const glintPos = new Float32Array(glintCount * 3), glintCol = new Float32Array(glintCount * 3), glintSizes = new Float32Array(glintCount), glintSeeds = new Float32Array(glintCount);
        for (let i = 0; i < glintCount; i++) {
            const [x, y, z, r] = starPosition();
            glintPos.set([x, y, z + .45], i * 3);
            const core = Math.exp(-r * r / 8), winding = Math.atan2(y, x) - 2.02 * Math.log(r * .42 + .45);
            const rose = Math.pow(Math.max(0, .5 + .5 * Math.cos((winding + .25) * 2)), 8) * THREE.MathUtils.smoothstep(r, 5, 10);
            const pink = random() < rose * .55;
            glintCol.set(pink ? [1., .055, .42] : [.11 + core * .84, .47 + core * .31, 1 - core * .22], i * 3);
            glintSizes[i] = 2.2 + Math.pow(random(), 8) * 17;
            glintSeeds[i] = random() * Math.PI * 2;
        }
        const glintMaterial = material({
            uniforms: globalUniforms, transparent: true, blending: THREE.AdditiveBlending, depthWrite: false,
            vertexShader: `attribute vec3 aColor;attribute float aSize,aSeed;varying vec3 vColor;varying float vSeed;uniform float uTime,uDpr,uEnergy;
    void main(){vec3 p=position;vec4 mv=modelViewMatrix*vec4(p,1.);gl_Position=projectionMatrix*mv;
      gl_PointSize=aSize*uDpr*82./-mv.z;vColor=aColor;vSeed=aSeed;}`,
            fragmentShader: `varying vec3 vColor;varying float vSeed;uniform float uTime,uEnergy;
      void main(){vec2 p=(gl_PointCoord-.5)*2.;float r=length(p);if(r>1.)discard;
        float core=exp(-r*r*90.);float halo=exp(-r*r*10.)*.15;
        float rays=(exp(-abs(p.x)*70.)+exp(-abs(p.y)*70.))*pow(max(0.,1.-r),3.)*.15;
        float pulse=.80+.2*sin(uTime*.39+vSeed);gl_FragColor=vec4(vColor,(core+halo+rays)*pulse*.78);}`
        });
        const glints = new THREE.Points(geometry({ position: [glintPos, 3], aColor: [glintCol, 3], aSize: [glintSizes, 1], aSeed: [glintSeeds, 1] }), glintMaterial);
        glints.renderOrder = 4;
        glints.frustumCulled = false;
        galaxy.add(glints);
        // A handful of bright foreground stars establish depth without filling every empty pixel.
        const nearPos = new Float32Array(65 * 3), nearCol = new Float32Array(65 * 3), nearSize = new Float32Array(65), nearSeed = new Float32Array(65);
        for (let i = 0; i < 65; i++) {
            nearPos.set([(random() - .5) * 150, (random() - .5) * 95, 5 + random() * 15], i * 3);
            nearCol.set(random() < .14 ? [.94, .71, .92] : [.32, .72, 1], i * 3);
            nearSize[i] = 3 + Math.pow(random(), 4) * 22;
            nearSeed[i] = random() * 6.283;
        }
        const foreground = new THREE.Points(geometry({ position: [nearPos, 3], aColor: [nearCol, 3], aSize: [nearSize, 1], aSeed: [nearSeed, 1] }), glintMaterial);
        foreground.renderOrder = 5;
        foreground.frustumCulled = false;
        scene.add(foreground);
        // A task is represented by a directed, curved current of starlight, separate from the galaxy.
        const streamCount = 2100, streamPos = new Float32Array(streamCount * 3), streamSeed = new Float32Array(streamCount);
        for (let i = 0; i < streamCount; i++) {
            streamPos.set([random(), gaussian(), gaussian()], i * 3);
            streamSeed[i] = random();
        }
        const streamMaterial = material({
            uniforms: { ...globalUniforms, ...interactionUniforms, uStart: { value: flowStart }, uEnd: { value: flowEnd } }, transparent: true, blending: THREE.AdditiveBlending, depthWrite: false, depthTest: false,
            vertexShader: `attribute float aSeed;uniform float uTime,uEnergy,uDpr,uMode,uPhaseTime,uDomOpacity;uniform vec3 uStart,uEnd,uFlowColor,uFlowAccent;varying float vAlpha;varying vec3 vColor;
      void main(){float speed=uMode==4.?.012:uMode==1.?.075:uMode==2.?.17:uMode==3.?.10:uMode==9.?.22:.12;float t=fract(position.x+uTime*(speed+aSeed*speed*.32));
        if(uMode==2.||uMode==6.||uMode==7.)t=1.-t;float arc=sin(t*3.14159265);float span=distance(uStart,uEnd);
        vec3 p=mix(uStart,uEnd,t);p.y+=arc*min(7.,span*.19);p.z+=arc*3.;p.xy+=vec2(position.y,position.z)*arc*.14;
        if(uMode==4.){float theta=uTime*.23+aSeed*6.2831853;p=mix(p,uEnd+vec3(cos(theta),sin(theta),0.)*.7,.48);}
        vec4 mv=modelViewMatrix*vec4(p,1.);gl_Position=projectionMatrix*mv;gl_PointSize=(.65+aSeed*1.15)*uDpr*82./-mv.z;
        float continuity=1.;if(uMode==1.)continuity*=smoothstep(.2,.65,sin(t*24.-uTime*3.)*.5+.5);if(uMode==9.)continuity*=.4+.6*pow(sin(t*34.-uTime*9.)*.5+.5,2.);if(uMode==8.)continuity*=1.-smoothstep(.6,1.5,uPhaseTime);if(uMode==5.)continuity=step(-.10,sin(t*39.-uPhaseTime*3.))*exp(-uPhaseTime*.78);
        if(uMode==6.)continuity=1.-smoothstep(.3,2.1,uPhaseTime);
        if(uMode==7.)continuity=1.-smoothstep(.5,2.8,uPhaseTime);
        float direction=(uMode==2.||uMode==6.||uMode==7.)?-1.:1.;
        float packet=pow(.5+.5*cos(t*22.-uTime*direction*speed*22.),12.);
        vAlpha=pow(arc,.5)*uEnergy*uDomOpacity*(.10+aSeed*.22)*continuity*(.72+packet*1.3);
        vColor=mix(uFlowAccent,uFlowColor,smoothstep(.1,.85,t));vColor=mix(vColor,vec3(.80,.90,1.),packet*.23);
        if(uMode==4.)vColor=vec3(1.,.65,.22);if(uMode==5.)vColor=mix(vec3(.93,.025,.19),vec3(.35,.04,.76),t);}`,
            fragmentShader: `varying float vAlpha;varying vec3 vColor;void main(){float r=length(gl_PointCoord-.5)*2.;if(r>1.)discard;gl_FragColor=vec4(vColor,exp(-r*r*7.)*vAlpha);}`
        });
        const stream = new THREE.Points(geometry({ position: [streamPos, 3], aSeed: [streamSeed, 1] }), streamMaterial);
        stream.frustumCulled = false;
        stream.renderOrder = 8;
        scene.add(stream);
        // DOM-edge constellation nodes: world positions are rebuilt from the current camera each frame.
        // They are tiny light markers, never copies of, replacements for, or event targets over the DOM.
        const anchorPositions = new Float32Array(maxAnchors * 3), anchorWeights = new Float32Array(maxAnchors), anchorKinds = new Float32Array(maxAnchors);
        const anchorGeometry = geometry({ position: [anchorPositions, 3], aWeight: [anchorWeights, 1], aKind: [anchorKinds, 1] });
        anchorGeometry.setDrawRange(0, 0);
        const anchorMaterial = material({ uniforms: { ...globalUniforms, ...interactionUniforms }, transparent: true, blending: THREE.AdditiveBlending, depthWrite: false, depthTest: false,
            vertexShader: `attribute float aWeight,aKind;uniform float uDpr;varying float vWeight,vKind;
      void main(){gl_Position=projectionMatrix*modelViewMatrix*vec4(position,1.);gl_PointSize=(7.+aWeight*14.)*uDpr;vWeight=aWeight;vKind=aKind;}`,
            fragmentShader: `uniform float uTime,uMode,uDomOpacity;varying float vWeight,vKind;
      void main(){vec2 p=(gl_PointCoord-.5)*2.;float r=length(p);if(r>1.)discard;
        float core=exp(-r*r*65.);float halo=exp(-r*r*8.)*.12;float orbit=exp(-pow((r-.56)*30.,2.))*.08;
        if(uMode==4.)orbit*=1.8+.5*sin(atan(p.y,p.x)*3.-uTime*.55);
        vec3 c=mix(vec3(.24,.65,1.),vec3(.84,.88,1.),vKind);
        if(uMode==4.)c=vec3(1.,.63,.20);if(uMode==5.)c=vec3(.94,.12,.38);
        gl_FragColor=vec4(c,(core+halo+orbit)*vWeight*uDomOpacity);}` });
        const anchorNodes = new THREE.Points(anchorGeometry, anchorMaterial);
        anchorNodes.frustumCulled = false;
        anchorNodes.renderOrder = 10;
        scene.add(anchorNodes);
        // Completion draws a small ring of scattered grains back into the result's edge marker.
        const burstCount = 2400, burstPos = new Float32Array(burstCount * 3);
        for (let i = 0; i < burstCount; i++)
            burstPos.set([random() * Math.PI * 2, random(), gaussian()], i * 3);
        const burstMaterial = material({ uniforms: { ...globalUniforms, ...interactionUniforms, uEnd: { value: flowEnd } }, transparent: true, blending: THREE.AdditiveBlending, depthWrite: false, depthTest: false,
            vertexShader: `uniform float uComplete,uDpr,uDomOpacity;uniform vec3 uEnd;varying float vAlpha;
      void main(){float spread=(1.-uComplete)*3.4;vec3 p=uEnd+vec3(cos(position.x),sin(position.x),0.)*spread*(.9+position.y*.12);
        p.z+=position.z*uComplete*.5;vec4 mv=modelViewMatrix*vec4(p,1.);gl_Position=projectionMatrix*mv;gl_PointSize=(.6+position.y*1.1)*uDpr;
        vAlpha=sin(clamp(uComplete,0.,1.)*3.14159265)*(.07+position.y*.12)*uDomOpacity;}`,
            fragmentShader: `varying float vAlpha;void main(){float r=length(gl_PointCoord-.5)*2.;if(r>1.)discard;gl_FragColor=vec4(.80,.86,1.,exp(-r*r*6.)*vAlpha);}` });
        const burst = new THREE.Points(geometry({ position: [burstPos, 3] }), burstMaterial);
        burst.frustumCulled = false;
        burst.renderOrder = 9;
        scene.add(burst);
        const trailCount = 1300, trailPositions = new Float32Array(trailCount * 6), trailEnds = new Float32Array(trailCount * 2), trailColors = new Float32Array(trailCount * 6);
        for (let i = 0; i < trailCount; i++) {
            const a = random() * Math.PI * 2, r = 12 + random() * 140, z = -180 + random() * 220;
            const p = [Math.cos(a) * r, Math.sin(a) * r, z];
            trailPositions.set(p, i * 6);
            trailPositions.set(p, i * 6 + 3);
            trailEnds[i * 2] = 0;
            trailEnds[i * 2 + 1] = 1;
            trailColors.set([.34, .53, .83, .66, .78, 1], i * 6);
        }
        const trailMaterial = material({ uniforms: globalUniforms, transparent: true, blending: THREE.AdditiveBlending, depthWrite: false, depthTest: false,
            vertexShader: `attribute float aEnd;attribute vec3 aColor;uniform float uWarp;varying vec3 vColor;varying float vAlpha;
      void main(){vec3 p=position;p.z+=uWarp*35.;p.xy*=1.+aEnd*uWarp*.2;p.z+=aEnd*uWarp*14.;gl_Position=projectionMatrix*modelViewMatrix*vec4(p,1.);vColor=aColor;vAlpha=uWarp*(.06+aEnd*.12);}`,
            fragmentShader: `varying vec3 vColor;varying float vAlpha;void main(){gl_FragColor=vec4(vColor,vAlpha);}` });
        const trails = new THREE.LineSegments(geometry({ position: [trailPositions, 3], aEnd: [trailEnds, 1], aColor: [trailColors, 3] }), trailMaterial);
        trails.frustumCulled = false;
        trails.renderOrder = 7;
        scene.add(trails);
        // The galaxy is composited in linear HDR; reading masks affect scenery, not DOM content.
        const renderTarget = new THREE.WebGLRenderTarget(1, 1, { type: renderer.extensions.has('EXT_color_buffer_float') ? THREE.HalfFloatType : THREE.UnsignedByteType, depthBuffer: true });
        renderTarget.texture.colorSpace = THREE.LinearSRGBColorSpace;
        disposable.push(renderTarget);
        const zoneCount = 12, zoneRects = Array.from({ length: zoneCount }, () => new THREE.Vector4()), zoneGoals = Array.from({ length: zoneCount }, () => new THREE.Vector4());
        const zoneWeights = new Float32Array(zoneCount), zoneTargetWeights = new Float32Array(zoneCount);
        const maskUniforms = { uZones: { value: zoneRects }, uZoneWeights: { value: zoneWeights }, uResolution: { value: new THREE.Vector2(1, 1) } };
        const zoneShader = `uniform vec4 uZones[12];uniform float uZoneWeights[12];uniform vec2 uResolution;
    float readingMask(vec2 screen){float covered=0.;for(int i=0;i<12;i++){vec4 r=uZones[i];vec2 center=(r.xy+r.zw*.5)*uResolution;vec2 halfSize=r.zw*.5*uResolution;
      vec2 q=abs(screen-center)-halfSize;float d=length(max(q,0.))+min(max(q.x,q.y),0.);covered=max(covered,(1.-smoothstep(-10.,66.,d))*uZoneWeights[i]);}return covered;}`;
        const postScene = new THREE.Scene(), postCamera = new THREE.OrthographicCamera(-1, 1, 1, -1, 0, 1);
        const postGeometry = new THREE.PlaneGeometry(2, 2);
        disposable.push(postGeometry);
        const postMaterial = material({ uniforms: { ...maskUniforms, uSource: { value: renderTarget.texture }, uPixel: { value: new THREE.Vector2(1, 1) }, uMask: { value: 1 } }, depthTest: false, depthWrite: false,
            vertexShader: 'varying vec2 vUv;void main(){vUv=uv;gl_Position=vec4(position.xy,0.,1.);}',
            fragmentShader: `varying vec2 vUv;uniform sampler2D uSource;uniform vec2 uPixel;uniform float uMask;${zoneShader}
      vec3 high(vec2 uv){vec3 c=texture2D(uSource,uv).rgb;float l=max(c.r,max(c.g,c.b));return c*smoothstep(.7,1.8,l);}
      vec3 film(vec3 c){return clamp((c*(2.51*c+.03))/(c*(2.43*c+.59)+.14),0.,1.);}
      void main(){vec3 c=texture2D(uSource,vUv).rgb;
        vec3 bloom=high(vUv+uPixel*vec2(3,0))+high(vUv+uPixel*vec2(-3,0))+high(vUv+uPixel*vec2(0,3))+high(vUv+uPixel*vec2(0,-3));
        bloom+=high(vUv+uPixel*vec2(7,5))+high(vUv+uPixel*vec2(-7,5))+high(vUv+uPixel*vec2(7,-5))+high(vUv+uPixel*vec2(-7,-5));
        c=max(c-vec3(.0009),vec3(0.))*1.46+bloom*.019;
        float peak=max(c.r,max(c.g,c.b));vec3 hueMapped=c*(film(vec3(peak)).r/max(peak,.0001));
        c=mix(film(c),hueMapped,.48);float mask=readingMask(vec2(vUv.x,1.-vUv.y)*uResolution)*uMask;
        c*=mix(1.,.62,mask);gl_FragColor=vec4(c,1.);}` });
        postScene.add(new THREE.Mesh(postGeometry, postMaterial));
        // A separate depth pass lets the comet really disappear behind the stellar bulge and dust.
        const orbitalScene = new THREE.Scene();
        const depthMaterial = new THREE.MeshBasicMaterial({ colorWrite: false, depthWrite: true, depthTest: true, side: THREE.DoubleSide });
        disposable.push(depthMaterial);
        const coreDepthGeometry = new THREE.SphereGeometry(1, 36, 24);
        disposable.push(coreDepthGeometry);
        const coreDepth = new THREE.Mesh(coreDepthGeometry, depthMaterial);
        coreDepth.matrixAutoUpdate = false;
        coreDepth.renderOrder = -100;
        orbitalScene.add(coreDepth);
        const coreShape = new THREE.Matrix4().makeScale(3.6, 2.8, 2.2);
        const dustDepthPositions = [];
        for (let arm = 0; arm < 2; arm++)
            for (let i = 0; i < 35; i++) {
                const section = (r: number, side: number) => { const a = arm * Math.PI + 2.02 * Math.log(r * .42 + .45) - .265 + side * .042; return [Math.cos(a) * r, Math.sin(a) * r, .65]; };
                const r = 5 + i * .27, a = section(r, -1), b = section(r, 1), c = section(r + .27, -1), d = section(r + .27, 1);
                dustDepthPositions.push(...a, ...b, ...c, ...b, ...d, ...c);
            }
        const dustDepthGeometry = geometry({ position: [new Float32Array(dustDepthPositions), 3] });
        const dustDepth = new THREE.Mesh(dustDepthGeometry, depthMaterial);
        dustDepth.matrixAutoUpdate = false;
        dustDepth.renderOrder = -99;
        orbitalScene.add(dustDepth);
        function glowTexture() { const c = document.createElement('canvas'); c.width = c.height = 128; const ctx = c.getContext('2d')!; const g = ctx.createRadialGradient(64, 64, 0, 64, 64, 64); g.addColorStop(0, 'rgba(255,255,255,1)'); g.addColorStop(.10, 'rgba(255,255,255,.95)'); g.addColorStop(.28, 'rgba(164,218,255,.35)'); g.addColorStop(1, 'rgba(60,130,255,0)'); ctx.fillStyle = g; ctx.fillRect(0, 0, 128, 128); const tex = new THREE.CanvasTexture(c); tex.colorSpace = THREE.SRGBColorSpace; disposable.push(tex); return tex; }
        const stellarGlow = glowTexture(), fileEntries = new Map<string, FileEntry>();
        const nodeGeometry = new THREE.IcosahedronGeometry(.115, 1);
        disposable.push(nodeGeometry);
        const graphPositions = new Float32Array(32 * 6), graphGeometry = geometry({ position: [graphPositions, 3] });
        graphGeometry.setDrawRange(0, 0);
        const graphMaterial = new THREE.LineBasicMaterial({ color: 0x6c93db, transparent: true, opacity: .12, depthWrite: false, depthTest: true });
        disposable.push(graphMaterial);
        const graphLines = new THREE.LineSegments(graphGeometry, graphMaterial);
        graphLines.frustumCulled = false;
        orbitalScene.add(graphLines);
        let sessionKey = '', sessionAngle = 0, sessionAngleGoal = 0;
        let baseRotationZ = .44;
        // Rotate the disk in its own plane; keep the camera distance and inclination fixed.
        const rotationSpeed = Math.PI * 2 / 150;
        const nodeProjection = new THREE.Vector3();
        function stringHash(value: string) { let h = 2166136261; for (const char of String(value)) {
            h ^= char.charCodeAt(0);
            h = Math.imul(h, 16777619);
        } return h >>> 0; }
        function clearFiles() { for (const file of fileEntries.values()) {
            orbitalScene.remove(file.group);
            file.dot.material.dispose();
            file.aura.material.dispose();
        } fileEntries.clear(); graphGeometry.setDrawRange(0, 0); }
        function setSession(key: string, files: GalaxyFile[] = []) {
            const next = String(key ?? '');
            const same = next === sessionKey;
            if (!same) {
                sessionKey = next;
                sessionAngleGoal = ((stringHash(next) % 1000) / 1000 - .5) * .26;
                heroJob = null;
                heroGroup.visible = false;
                clearArrival();
                globalUniforms.uFlareStrength.value = 0;
                clearFiles();
            }
            const wanted = new Set(files.slice(0, 16).map(file => file.id));
            for (const [id, file] of fileEntries)
                if (!wanted.has(id)) {
                    orbitalScene.remove(file.group);
                    file.dot.material.dispose();
                    file.aura.material.dispose();
                    fileEntries.delete(id);
                }
            const unique = new Set<string>();
            let index = 0;
            for (const source of Array.isArray(files) ? files : []) {
                if (index >= 16 || !source || typeof source.id !== 'string' || unique.has(source.id))
                    continue;
                unique.add(source.id);
                if (fileEntries.has(source.id)) {
                    index++;
                    continue;
                }
                const path = String(source.path || source.label || source.id), label = String(source.label || path.split('/').pop() || source.id).slice(0, 28), hash = stringHash(next + '|' + path);
                const angle = (hash % 6283) / 1000 + ((hash % 100) / 100 - .5) * .20, r = 11.5 + (hash % 500) / 170;
                const local = new THREE.Vector3(Math.cos(angle) * r, Math.sin(angle) * r, .8 + index * .4), group = new THREE.Group();
                const dotMaterial = new THREE.MeshBasicMaterial({ color: 0x86c9ff, transparent: true, opacity: .85, depthTest: true, depthWrite: false });
                const dot = new THREE.Mesh(nodeGeometry, dotMaterial);
                group.add(dot);
                const auraMaterial = new THREE.SpriteMaterial({ map: stellarGlow, color: 0x4b97ff, transparent: true, opacity: .55, depthTest: true, depthWrite: false, blending: THREE.AdditiveBlending });
                const aura = new THREE.Sprite(auraMaterial);
                aura.scale.setScalar(1.9);
                group.add(aura);
                group.position.copy(local).applyMatrix4(galaxy.matrixWorld);
                orbitalScene.add(group);
                fileEntries.set(source.id, { id: source.id, path, labelText: label, state: 'discovered', local, group, dot, aura, index });
                index++;
            }
            canvas.dataset.session = sessionKey;
        }
        function setFileState(id: string, state: FileState) { const file = fileEntries.get(id); if (file && ['hidden', 'discovered', 'reading', 'read', 'changed'].includes(state)) {
            file.state = state;
            file.stateAt = time;
        } }
        function setReadingZones(zones: Bounds[]) { for (let i = 0; i < zoneCount; i++) {
            const r = Array.isArray(zones) ? zones[i] : null;
            const valid = r && [r.x, r.y, r.w, r.h].every(Number.isFinite) && r.w > 0 && r.h > 0;
            zoneTargetWeights[i] = valid ? 1 : 0;
            if (valid)
                zoneGoals[i].set(THREE.MathUtils.clamp(r.x, -1, 1), THREE.MathUtils.clamp(r.y, -1, 1), Math.min(2, r.w), Math.min(2, r.h));
        } }
        function maskAt(world: THREE.Vector3) {
            nodeProjection.copy(world).project(camera);
            const x = (nodeProjection.x + 1) * .5 * width, y = (1 - nodeProjection.y) * .5 * height;
            let covered = 0;
            for (let i = 0; i < zoneCount; i++) {
                const r = zoneRects[i], dx = Math.abs(x - (r.x + r.z * .5) * width) - r.z * .5 * width, dy = Math.abs(y - (r.y + r.w * .5) * height) - r.w * .5 * height, d = Math.hypot(Math.max(dx, 0), Math.max(dy, 0)) + Math.min(Math.max(dx, dy), 0);
                covered = Math.max(covered, (1 - THREE.MathUtils.smoothstep(d, -10, 66)) * zoneWeights[i]);
            }
            return immersive ? 0 : covered;
        }
        const heroGroup = new THREE.Group();
        orbitalScene.add(heroGroup);
        heroGroup.visible = false;
        const headMaterial = new THREE.SpriteMaterial({ map: stellarGlow, color: 0xb9ecff, transparent: true, opacity: 1, blending: THREE.AdditiveBlending, depthWrite: false, depthTest: true });
        disposable.push(headMaterial);
        const head = new THREE.Sprite(headMaterial);
        head.scale.setScalar(3.5);
        head.renderOrder = 3;
        heroGroup.add(head);
        const seedCount = 1250, heroPositions = new Float32Array(seedCount * 3), heroAlpha = new Float32Array(seedCount), heroSizes = new Float32Array(seedCount), heroLags = new Float32Array(seedCount), heroSeeds: {
            lag: number;
            a: number;
            b: number;
            seed: number;
        }[] = [];
        for (let i = 0; i < seedCount; i++) {
            const lag = Math.pow(random(), .75) * .29;
            heroSeeds.push({ lag, a: gaussian(), b: gaussian(), seed: random() });
            heroLags[i] = lag;
            heroSizes[i] = .7 + Math.pow(random(), 4) * 3.;
        }
        const heroGeometry = geometry({ position: [heroPositions, 3], aAlpha: [heroAlpha, 1], aSize: [heroSizes, 1], aLag: [heroLags, 1] });
        const heroColor = new THREE.Color(0x348cff), heroAccent = new THREE.Color(0xa345ff);
        const heroMaterial = material({ uniforms: { ...globalUniforms, ...maskUniforms, uColor: { value: heroColor }, uAccent: { value: heroAccent }, uMask: { value: 1 } }, transparent: true, depthWrite: false, depthTest: true, blending: THREE.AdditiveBlending,
            vertexShader: `attribute float aAlpha,aSize,aLag;uniform float uDpr,uTime;uniform vec3 uColor,uAccent;varying float vAlpha;varying vec3 vColor;void main(){vec4 mv=modelViewMatrix*vec4(position,1.);gl_Position=projectionMatrix*mv;gl_PointSize=aSize*uDpr*82./-mv.z;float packet=pow(.5+.5*cos(aLag*110.+uTime*8.),10.);vColor=mix(uColor,uAccent,smoothstep(.03,.27,aLag));vColor=mix(vColor,vec3(.75,.88,1.),packet*.17);vAlpha=aAlpha*(.65+packet*1.75);}`,
            fragmentShader: `uniform float uDpr,uMask;varying float vAlpha;varying vec3 vColor;${zoneShader}void main(){float r=length(gl_PointCoord-.5)*2.;if(r>1.)discard;vec2 p=vec2(gl_FragCoord.x/uDpr,uResolution.y-gl_FragCoord.y/uDpr);float mask=readingMask(p)*uMask;gl_FragColor=vec4(vColor,exp(-r*r*7.)*vAlpha*mix(1.,.17,mask));}` });
        const heroTail = new THREE.Points(heroGeometry, heroMaterial);
        heroTail.frustumCulled = false;
        heroTail.renderOrder = 2;
        heroGroup.add(heroTail);
        const ribbonCount = 110, ribbonPositions = new Float32Array(ribbonCount * 3), ribbonGeometry = geometry({ position: [ribbonPositions, 3] });
        const ribbonMaterial = new THREE.LineBasicMaterial({ color: 0xa4dcff, transparent: true, opacity: .28, depthWrite: false, depthTest: true });
        disposable.push(ribbonMaterial);
        const ribbon = new THREE.Line(ribbonGeometry, ribbonMaterial);
        ribbon.frustumCulled = false;
        ribbon.renderOrder = 1;
        heroGroup.add(ribbon);
        let heroJob: HeroJob | null = null, heroSequence = 0, heroProgress = 0, heroSegment = 'idle', heroFrontFrames = 0, heroBackFrames = 0, totalFrontFrames = 0, totalBackFrames = 0;
        // One reusable arrival field: every pulse remains bound to its live DOM/file destination.
        const arrivalCount = 280, arrivalPositions = new Float32Array(arrivalCount * 3);
        for (let i = 0; i < arrivalCount; i++)
            arrivalPositions.set([random() * Math.PI * 2, random(), random()], i * 3);
        const arrivalUniforms = { ...globalUniforms, ...maskUniforms, uCenter: { value: new THREE.Vector3() }, uProgress: { value: 0 }, uColor: { value: new THREE.Color() }, uAccent: { value: new THREE.Color() } };
        const arrivalMaterial = material({ uniforms: arrivalUniforms, transparent: true, depthWrite: false, depthTest: true, blending: THREE.AdditiveBlending,
            vertexShader: `uniform vec3 uCenter,uColor,uAccent;uniform float uProgress,uDpr;varying vec3 vColor;varying float vAlpha;
      void main(){float p=uProgress;float radius=.18+pow(p,.72)*(1.2+position.y*1.25);vec3 pos=uCenter+vec3(cos(position.x)*radius,sin(position.x)*radius*.64,sin(position.x)*p*.3);vec4 mv=modelViewMatrix*vec4(pos,1.);gl_Position=projectionMatrix*mv;gl_PointSize=(.8+position.z*1.8)*uDpr*82./-mv.z;vColor=mix(uColor,uAccent,position.y);vAlpha=sin(p*3.14159265)*pow(1.-p,.55)*(.35+position.z*.45);}`,
            fragmentShader: `uniform float uDpr;varying vec3 vColor;varying float vAlpha;${zoneShader}void main(){float r=length(gl_PointCoord-.5)*2.;if(r>1.)discard;float mask=readingMask(vec2(gl_FragCoord.x/uDpr,uResolution.y-gl_FragCoord.y/uDpr));gl_FragColor=vec4(vColor,exp(-r*r*7.)*vAlpha*mix(1.,.20,mask));}` });
        const arrival = new THREE.Points(geometry({ position: [arrivalPositions, 3] }), arrivalMaterial);
        arrival.frustumCulled = false;
        arrival.renderOrder = 4;
        arrival.visible = false;
        orbitalScene.add(arrival);
        let arrivalJob: (Travel & {
            started: number;
        }) | null = null, arrivalSequence = 0;
        function clearArrival() { arrivalJob = null; arrival.visible = false; }
        function updateArrival() {
            if (!arrivalJob)
                return;
            const job = arrivalJob, target = anchorMap.get(job.targetId), file = fileEntries.get(job.fileId ?? ''), p = (time - job.started) / .9;
            if (immersive || p >= 1 || ['idle', 'approval', 'error', 'cancel'].includes(phase) || !target || (job.kind === 'launch' && (!file || file.state === 'hidden'))) {
                clearArrival();
                return;
            }
            if (job.kind === 'launch')
                arrivalUniforms.uCenter.value.copy(file!.group.position);
            else
                normalizedToWorld(target.x, target.y, arrivalUniforms.uCenter.value);
            arrivalUniforms.uProgress.value = p;
            arrival.visible = true;
        }
        const heroPosition = new THREE.Vector3(), curveA = new THREE.Vector3(), curveB = new THREE.Vector3(), curveC = new THREE.Vector3(), curveD = new THREE.Vector3(), heroSample = new THREE.Vector3();
        function smooth(t: number) { t = THREE.MathUtils.clamp(t, 0, 1); return t * t * t * (t * (t * 6 - 15) + 10); }
        function bezier(a: THREE.Vector3, b: THREE.Vector3, c: THREE.Vector3, d: THREE.Vector3, t: number, out: THREE.Vector3) { const q = 1 - t; return out.set(q * q * q * a.x + 3 * q * q * t * b.x + 3 * q * t * t * c.x + t * t * t * d.x, q * q * q * a.y + 3 * q * q * t * b.y + 3 * q * t * t * c.y + t * t * t * d.y, q * q * q * a.z + 3 * q * q * t * b.z + 3 * q * t * t * c.z + t * t * t * d.z); }
        function orbitAt(job: HeroJob, t: number, out: THREE.Vector3) {
            const a = Math.PI * .15 - Math.PI * 1.80 * smooth(t), r = 10.5 + Math.sin(t * Math.PI) * 4.5, rotation = -.28 + sessionAngle;
            const x = Math.cos(a) * r, y = Math.sin(a) * r * .15;
            return out.set(job.center.x + x * Math.cos(rotation) - y * Math.sin(rotation), job.center.y + x * Math.sin(rotation) + y * Math.cos(rotation), job.center.z + Math.sin(a) * r * 1.85);
        }
        function travelPath(job: HeroJob, p: number, out: THREE.Vector3) {
            if (p < .18) {
                orbitAt(job, 0, curveD);
                curveA.copy(job.source);
                curveB.copy(curveA);
                curveB.y += 2;
                curveB.z += 5;
                curveC.copy(curveD);
                curveC.x -= 3;
                curveC.y += 1;
                curveC.z += 3;
                return bezier(curveA, curveB, curveC, curveD, smooth(p / .18), out);
            }
            if (p < .80)
                return orbitAt(job, (p - .18) / .62, out);
            orbitAt(job, 1, curveA);
            curveB.copy(curveA);
            curveB.x += 5;
            curveB.y += 2;
            curveB.z += 10;
            curveC.copy(job.target);
            curveC.y += 2.8;
            curveC.z += 7;
            return bezier(curveA, curveB, curveC, job.target, smooth((p - .80) / .20), out);
        }
        function travel({ fileId, targetId, kind = 'read' }: Travel) {
            if (disposed || immersive)
                return;
            const file = fileEntries.get(fileId ?? ''), target = anchorMap.get(targetId);
            if (!target)
                return;
            const resolvedKind = ['launch', 'read', 'edit', 'compose'].includes(kind) ? kind : 'read';
            if (resolvedKind === 'launch' && !file)
                return;
            const source = new THREE.Vector3(), destination = new THREE.Vector3();
            if (resolvedKind === 'launch') {
                normalizedToWorld(target.x, target.y, source);
                destination.copy(file!.group.position);
            }
            else {
                source.copy(file ? file.group.position : flowStart);
                normalizedToWorld(target.x, target.y, destination);
            }
            heroJob = { id: ++heroSequence, fileId, targetId, kind: resolvedKind, started: time, duration: 3.35, source, target: destination, center: flowStart.clone(), abortAt: null, abortKind: null, abortProgress: 0, pauseAt: null };
            heroColor.set(kind === 'edit' ? 0xff358b : kind === 'compose' ? 0x9a57ff : 0x348cff);
            heroAccent.set(kind === 'edit' ? 0xa33fff : kind === 'compose' ? 0xff45b5 : 0x9851ff);
            headMaterial.color.set(kind === 'edit' ? 0xffbddc : kind === 'compose' ? 0xddd0ff : 0xbdeaff);
            ribbonMaterial.color.copy(heroColor);
            globalUniforms.uFlareColor.value.copy(heroColor);
            heroGroup.visible = true;
            heroFrontFrames = heroBackFrames = 0;
            canvas.dataset.heroSequence = String(heroSequence);
        }
        function updateOrbit(dt: number) {
            for (let i = 0; i < zoneCount; i++) {
                zoneRects[i].lerp(zoneGoals[i], Math.min(1, dt * 7));
                zoneWeights[i] += (zoneTargetWeights[i] - zoneWeights[i]) * Math.min(1, dt * 4.5);
            }
            postMaterial.uniforms.uMask.value = immersive ? 0 : 1;
            heroMaterial.uniforms.uMask.value = immersive ? 0 : 1;
            coreDepth.matrix.copy(galaxy.matrixWorld).multiply(coreShape);
            dustDepth.matrix.copy(galaxy.matrixWorld);
            const visibleFiles = [];
            for (const file of fileEntries.values()) {
                file.group.position.copy(file.local).applyMatrix4(galaxy.matrixWorld);
                file.group.visible = file.state !== 'hidden' && !immersive;
                if (!file.group.visible)
                    continue;
                visibleFiles.push(file);
                const active = file.state === 'reading', changed = file.state === 'changed';
                const fade = 1 - maskAt(file.group.position) * .65, base = phase === 'idle' || phase === 'complete' ? .40 : .72;
                file.dot.material.color.set(changed ? 0xff87bb : active ? 0xd1f9ff : file.state === 'read' ? 0xae9fff : 0x7cc4ff);
                file.dot.material.opacity = (active ? 1 : base) * fade;
                file.aura.material.opacity = (active ? .65 + .16 * Math.sin(time * 5) : base * .52) * fade;
                file.aura.scale.setScalar(active ? 2.3 : 1.65);
            }
            let gi = 0;
            for (let i = 1; i < visibleFiles.length && gi < 32; i++) {
                const a = visibleFiles[i - 1].group.position, b = visibleFiles[i].group.position;
                const offset = gi++ * 6;
                graphPositions[offset] = a.x; graphPositions[offset + 1] = a.y; graphPositions[offset + 2] = a.z;
                graphPositions[offset + 3] = b.x; graphPositions[offset + 4] = b.y; graphPositions[offset + 5] = b.z;
            }
            graphGeometry.setDrawRange(0, gi * 2);
            graphGeometry.attributes.position.needsUpdate = true;
            graphLines.visible = !immersive;
            graphMaterial.opacity = phase === 'idle' || phase === 'complete' ? .07 : .13;
            updateArrival();
            if (!heroJob || immersive) {
                heroGroup.visible = false;
                globalUniforms.uFlareStrength.value *= Math.exp(-dt * 6);
                return;
            }
            const job = heroJob, target = anchorMap.get(job.targetId), file = fileEntries.get(job.fileId ?? '');
            if (!target || (job.kind === 'launch' && !file)) {
                heroJob = null;
                heroGroup.visible = false;
                return;
            }
            // Launch leaves the actual input DOM and docks at its file's spatial node. Other actions
            // leave the file node and dock at the DOM. Reproject both ends after layout/camera changes.
            if (job.kind === 'launch') {
                normalizedToWorld(target.x, target.y, job.source);
                job.target.copy(file!.group.position);
            }
            else {
                if (file)
                    job.source.copy(file!.group.position);
                normalizedToWorld(target.x, target.y, job.target);
            }
            job.center.copy(flowStart);
            let p = THREE.MathUtils.clamp(((job.pauseAt ?? time) - job.started) / job.duration, 0, 1);
            let abortFade = 1;
            if (job.abortAt !== null) {
                const abortProgress = THREE.MathUtils.clamp((time - job.abortAt) / (job.abortKind === 'cancel' ? .9 : .65), 0, 1);
                abortFade = 1 - abortProgress;
                p = job.abortKind === 'cancel' ? job.abortProgress * (1 - smooth(abortProgress)) : job.abortProgress;
                if (!abortFade) {
                    heroJob = null;
                    heroGroup.visible = false;
                    return;
                }
            }
            heroProgress = p;
            heroSegment = job.pauseAt !== null ? 'holding' : job.abortKind === 'cancel' ? 'retracting' : job.abortKind === 'error' ? 'fragmenting' : p < .18 ? 'capture' : p < .80 ? 'orbit' : 'dock';
            travelPath(job, p, heroPosition);
            head.position.copy(heroPosition);
            const areaMask = maskAt(heroPosition);
            const docking = THREE.MathUtils.smoothstep(p, .80, .99);
            headMaterial.opacity = (1 - THREE.MathUtils.smoothstep(p, .985, 1)) * abortFade * (1 - areaMask * .8);
            head.scale.setScalar((3.0 + Math.sin(p * Math.PI) * 1.7) * (1 - docking * .45));
            for (let i = 0; i < seedCount; i++) {
                const seed = heroSeeds[i], t = job.abortKind === 'cancel' ? Math.min(job.abortProgress, p + seed.lag) : p - seed.lag * (1 - docking * .72);
                travelPath(job, Math.max(0, t), heroSample);
                const spread = (.035 + seed.lag * 2.2) * (1 - docking * .72) + (job.abortKind === 'error' ? (1 - abortFade) * 1.7 : 0);
                heroPositions[i * 3] = heroSample.x + seed.a * spread;
                heroPositions[i * 3 + 1] = heroSample.y + seed.b * spread;
                heroPositions[i * 3 + 2] = heroSample.z + seed.a * seed.b * spread * .35;
                heroAlpha[i] = t < 0 ? 0 : (.18 + seed.seed * .38) * (1 - seed.lag / .36) * abortFade * (1 - THREE.MathUtils.smoothstep(p, .985, 1));
            }
            for (let i = 0; i < ribbonCount; i++) {
                travelPath(job, Math.max(0, p - i / (ribbonCount - 1) * .18), heroSample);
                ribbonPositions.set([heroSample.x, heroSample.y, heroSample.z], i * 3);
            }
            ribbonMaterial.opacity = .34 * abortFade * (1 - areaMask * .88) * (1 - THREE.MathUtils.smoothstep(p, .92, 1));
            heroGeometry.attributes.position.needsUpdate = true;
            heroGeometry.attributes.aAlpha.needsUpdate = true;
            ribbonGeometry.attributes.position.needsUpdate = true;
            globalUniforms.uFlareCenter.value.copy(heroPosition);
            galaxy.worldToLocal(globalUniforms.uFlareCenter.value);
            globalUniforms.uFlareStrength.value = Math.sin(p * Math.PI) * .9 * abortFade;
            if (heroPosition.z > job.center.z) {
                heroFrontFrames++;
                totalFrontFrames++;
            }
            else {
                heroBackFrames++;
                totalBackFrames++;
            }
            if (p >= 1) {
                if (!job.abortKind && !['approval', 'error', 'cancel', 'idle'].includes(phase)) {
                    arrivalJob = { targetId: job.targetId, fileId: job.fileId, kind: job.kind, started: time };
                    arrivalUniforms.uColor.value.copy(heroColor);
                    arrivalUniforms.uAccent.value.copy(heroAccent);
                    arrivalSequence++;
                }
                heroJob = null;
                heroGroup.visible = false;
                heroSegment = 'arrived';
            }
        }
        const totalParticles = backgroundCount + starCount + glintCount + 65 + streamCount + burstCount + maxAnchors + seedCount + arrivalCount;
        canvas.dataset.particleCount = String(totalParticles);
        canvas.dataset.ready = 'false';
        canvas.dataset.frame = '0';
        canvas.style.pointerEvents = 'none';
        function normalizedToWorld(x: number, y: number, result: THREE.Vector3) {
            vTemp.set(x * 2 - 1, 1 - y * 2, .5).unproject(camera).sub(camera.position).normalize();
            return result.copy(camera.position).addScaledVector(vTemp, (3 - camera.position.z) / vTemp.z);
        }
        const anchorWorld = new THREE.Vector3();
        function updateFocus() {
            camera.updateMatrixWorld();
            galaxy.updateMatrixWorld();
            flowStart.copy(galaxy.position);
            flowStart.z += 1;
            let target = activeAnchorId ? anchorMap.get(activeAnchorId) : null;
            const elapsed = time - phaseStarted;
            if (!activeAnchorId) {
                if (phase === 'search') {
                    const files = anchorList.filter(anchor => anchor.kind === 'file');
                    const candidates = files.length ? files : anchorList.filter(anchor => anchor.id === 'tool-search');
                    target = candidates[Math.floor(elapsed / .85) % Math.max(1, candidates.length)];
                }
                else if (phase === 'cancel')
                    target = anchorMap.get(retainedAnchorId ?? '');
                else if (phase === 'compose' || phase === 'complete')
                    target = anchorList.find(anchor => anchor.kind === 'result');
            }
            resolvedAnchorId = target?.id ?? null;
            if (target && phase !== 'cancel')
                retainedAnchorId = target.id;
            if (target)
                normalizedToWorld(target.x, target.y, flowEnd);
            else if (!anchorsConfigured)
                normalizedToWorld(focus.x, focus.y, flowEnd);
            const visible = !immersive && phase !== 'idle' && !(phase === 'cancel' && elapsed >= 2.1) && !(phase === 'complete' && elapsed >= 3.3) && (Boolean(target) || !anchorsConfigured);
            interactionUniforms.uDomOpacity.value = visible ? 1 : 0;
            // Zero-alpha shaders still consume vertex/fragment work; omit inactive effects entirely.
            stream.visible = visible;
            anchorNodes.visible = visible && anchorList.length > 0;
            burst.visible = visible && globalUniforms.uComplete.value > 0;
            trails.visible = globalUniforms.uWarp.value > 0;
            interactionUniforms.uPhaseTime.value = elapsed;
            for (let i = 0; i < anchorList.length; i++) {
                const anchor = anchorList[i];
                normalizedToWorld(anchor.x, anchor.y, anchorWorld);
                const offset = i * 3;
                anchorPositions[offset] = anchorWorld.x; anchorPositions[offset + 1] = anchorWorld.y; anchorPositions[offset + 2] = anchorWorld.z;
                const isActive = anchor.id === resolvedAnchorId;
                let weight = phase === 'idle' ? 0 : isActive ? .88 : phase === 'search' && anchor.kind === 'file' ? .23 : 0;
                if (phase === 'cancel')
                    weight *= 1 - THREE.MathUtils.smoothstep(elapsed, .4, 2.1);
                if (phase === 'complete')
                    weight *= 1 - THREE.MathUtils.smoothstep(elapsed, 1.5, 3.3);
                anchorWeights[i] = weight;
                anchorKinds[i] = anchor.kind === 'file' ? .1 : anchor.kind === 'result' ? .65 : 1;
            }
            anchorGeometry.attributes.position.needsUpdate = true;
            anchorGeometry.attributes.aWeight.needsUpdate = true;
            anchorGeometry.attributes.aKind.needsUpdate = true;
        }
        function resize() {
            if (disposed)
                return;
            const rect = canvas.getBoundingClientRect();
            width = Math.max(1, rect.width || innerWidth);
            height = Math.max(1, rect.height || innerHeight);
            pixelRatio = Math.min(devicePixelRatio || 1, 2);
            renderer.setPixelRatio(pixelRatio);
            renderer.setSize(width, height, false);
            renderTarget.setSize(Math.round(width * pixelRatio), Math.round(height * pixelRatio));
            maskUniforms.uResolution.value.set(width, height);
            postMaterial.uniforms.uPixel.value.set(1 / (width * pixelRatio), 1 / (height * pixelRatio));
            camera.aspect = width / height;
            camera.updateProjectionMatrix();
            const halfHeight = Math.tan(THREE.MathUtils.degToRad(camera.fov / 2)) * camera.position.z;
            const compact = width < 600;
            layout.x = compact || immersive ? 0 : halfHeight * camera.aspect * .10;
            layout.y = immersive ? halfHeight * .035 : compact ? halfHeight * .17 : halfHeight * .12;
            baseRotationZ = compact ? .48 : .44;
            galaxy.rotation.set(compact ? .87 : .96, -.19, baseRotationZ + time * rotationSpeed);
            layout.scale = 1.65;
            if (immersive || compact) {
                // Fit the complete tilted disk using perspective projection, including its near edge.
                // An orthographic width estimate crops the near spiral arm on tall and narrow screens.
                const edge = new THREE.Vector3();
                let low = .08, high = 1.45;
                for (let iteration = 0; iteration < 14; iteration++) {
                    const scale = (low + high) / 2;
                    let fits = true;
                    for (let sample = 0; sample < 64; sample++) {
                        const angle = sample * Math.PI * 2 / 64;
                        edge.set(Math.cos(angle) * 33, Math.sin(angle) * 33, 0).applyEuler(galaxy.rotation).multiplyScalar(scale);
                        const perspective = 82 / (82 - edge.z);
                        const nx = edge.x * perspective / (halfHeight * camera.aspect);
                        const ny = (edge.y + layout.y) * perspective / halfHeight;
                        if (Math.abs(nx) > (compact ? .94 : .88) || Math.abs(ny) > .80) {
                            fits = false;
                            break;
                        }
                    }
                    if (fits)
                        low = scale;
                    else
                        high = scale;
                }
                layout.scale = low;
            }
            if (firstFrame) {
                galaxy.position.x = layout.x;
                galaxy.position.y = layout.y;
                galaxy.scale.setScalar(layout.scale);
            }
            globalUniforms.uDpr.value = pixelRatio;
            updateFocus();
        }
        function onPointer(event: PointerEvent) { pointer.set((event.clientX / width - .5) * 2, (event.clientY / height - .5) * 2); }
        function onLeave() { pointer.set(0, 0); }
        function onContextLost(event: Event) { event.preventDefault(); contextAvailable = false; cancelAnimationFrame(raf); canvas.dataset.ready = 'false'; onError?.(new Error('WebGL context lost')); }
        function onContextRestored() { if (disposed)
            return; contextAvailable = true; shaderFailed = false; firstFrame = true; canvas.dataset.ready = 'false'; last = performance.now(); if (!paused)
            raf = requestAnimationFrame(tick); }
        function tick(now: number) {
            if (disposed || paused || !contextAvailable)
                return;
            const dt = Math.min((now - last) / 1000, .08);
            last = now;
            time += dt;
            const layoutEase = Math.min(1, dt * 2.2);
            galaxy.position.x += (layout.x - galaxy.position.x) * layoutEase;
            galaxy.position.y += (layout.y - galaxy.position.y) * layoutEase;
            galaxy.scale.setScalar(galaxy.scale.x + (layout.scale - galaxy.scale.x) * layoutEase);
            smoothedPointer.lerp(pointer, Math.min(1, dt * 1.6));
            phaseEnergy += (phaseTarget - phaseEnergy) * Math.min(1, dt * 1.65);
            camera.position.set(smoothedPointer.x * .85, -smoothedPointer.y * .6, 82);
            camera.lookAt(0, 0, 0);
            sessionAngle += (sessionAngleGoal - sessionAngle) * Math.min(1, dt * 1.8);
            const warpT = time - warpStarted;
            const warpAmount = warpT >= 0 && warpT < 2.4 ? Math.pow(Math.sin(warpT / 2.4 * Math.PI), 2) : 0;
            const completeT = time - completionStarted;
            globalUniforms.uTime.value = time;
            globalUniforms.uEnergy.value = phaseEnergy;
            globalUniforms.uWarp.value = warpAmount;
            globalUniforms.uComplete.value = completeT >= 0 && completeT < 3 ? completeT / 3 : 0;
            galaxy.rotation.z = baseRotationZ + time * rotationSpeed;
            updateFocus();
            updateOrbit(dt);
            try {
                renderer.info.reset();
                renderer.setRenderTarget(renderTarget);
                renderer.autoClear = true;
                renderer.render(scene, camera);
                renderer.setRenderTarget(null);
                renderer.render(postScene, postCamera);
                renderer.autoClear = false;
                renderer.clearDepth();
                renderer.render(orbitalScene, camera);
                renderer.autoClear = true;
            }
            catch (error) {
                onError?.(error);
                setPaused(true);
                return;
            }
            if (shaderFailed) {
                setPaused(true);
                return;
            }
            frames++;
            if (frames % 15 === 0) {
                canvas.dataset.frame = String(frames);
                canvas.dataset.rotation = String(galaxy.rotation.z);
                canvas.dataset.cameraDistance = String(camera.position.z);
                publishAnchorProjection();
            }
            if (firstFrame) {
                firstFrame = false;
                canvas.dataset.ready = 'true';
                canvas.dataset.frame = String(frames);
                canvas.dataset.rotation = String(galaxy.rotation.z);
                canvas.dataset.cameraDistance = String(camera.position.z);
                onReady?.();
            }
            raf = requestAnimationFrame(tick);
        }
        function setPaused(value: boolean) { paused = Boolean(value); cancelAnimationFrame(raf); if (!paused && !disposed && contextAvailable) {
            last = performance.now();
            raf = requestAnimationFrame(tick);
        } }
        function setPhase(value: GalaxyPhase) {
            const modes = { idle: 0, search: 1, read: 2, compose: 3, approval: 4, error: 5, cancel: 6, complete: 7, launch: 8, edit: 9 };
            const next = Object.hasOwn(modes, value) ? value : 'idle';
            const changed = next !== phase;
            const previous = phase;
            if (changed)
                phaseStarted = time;
            phase = next;
            const flowColors = { idle: [0x348cff, 0x9851ff], launch: [0x438fff, 0x9b49ff], search: [0x378fff, 0x6841ff], read: [0x399cff, 0x9451ff], edit: [0xff358b, 0xaa3aff], compose: [0x9d53ff, 0xf23fac], approval: [0xffc66e, 0xe09735], error: [0xff3474, 0xa52888], cancel: [0x3971bf, 0x5c46ba], complete: [0x738dff, 0xdb55ff] };
            globalUniforms.uFlowColor.value.set(flowColors[phase][0]);
            globalUniforms.uFlowAccent.value.set(flowColors[phase][1]);
            if (changed && ['idle', 'approval', 'error', 'cancel'].includes(phase))
                clearArrival();
            if (phase === 'idle') {
                heroJob = null;
                heroGroup.visible = false;
            }
            if (changed && heroJob && phase === 'approval') {
                heroJob.pauseAt = time;
                headMaterial.color.set(0xffdda0);
            }
            if (changed && heroJob && previous === 'approval' && phase !== 'approval' && heroJob.pauseAt !== null) {
                heroJob.started += time - heroJob.pauseAt;
                heroJob.pauseAt = null;
                headMaterial.color.set(heroJob.kind === 'edit' ? 0xffbed8 : heroJob.kind === 'compose' ? 0xd7caff : 0xd0f2ff);
            }
            phaseTarget = ({ idle: 0, search: .47, read: .75, compose: .9, approval: .32, error: .52, cancel: 0, complete: .25, launch: .8, edit: .84 })[phase];
            interactionUniforms.uMode.value = modes[phase];
            canvas.dataset.phase = phase;
            if (changed)
                completionStarted = phase === 'complete' ? time : -100;
            if (changed && heroJob && (phase === 'cancel' || phase === 'error')) {
                heroJob.abortAt = time;
                heroJob.abortKind = phase;
                heroJob.abortProgress = THREE.MathUtils.clamp((time - heroJob.started) / heroJob.duration, 0, 1);
                heroColor.set(phase === 'error' ? 0xff3474 : 0x3971bf);
                heroAccent.copy(globalUniforms.uFlowAccent.value);
                globalUniforms.uFlareColor.value.copy(heroColor);
            }
            updateFocus();
        }
        function setFocus(x: number, y: number) { if (Number.isFinite(x) && Number.isFinite(y)) {
            focus.set(THREE.MathUtils.clamp(x, 0, 1), THREE.MathUtils.clamp(y, 0, 1));
            updateFocus();
        } }
        function setAnchors(items: GalaxyAnchor[]) {
            anchorsConfigured = true;
            anchorList.length = 0;
            anchorMap.clear();
            for (const item of Array.isArray(items) ? items : []) {
                if (anchorList.length >= maxAnchors)
                    break;
                if (!item || typeof item.id !== 'string' || !item.id || anchorMap.has(item.id) || !Number.isFinite(item.x) || !Number.isFinite(item.y) || item.x < 0 || item.x > 1 || item.y < 0 || item.y > 1)
                    continue;
                const anchor: GalaxyAnchor = { id: item.id, x: item.x, y: item.y, kind: ['file', 'tool', 'result', 'input'].includes(item.kind) ? item.kind : 'file' };
                anchorList.push(anchor);
                anchorMap.set(anchor.id, anchor);
            }
            anchorGeometry.setDrawRange(0, anchorList.length);
            updateFocus();
        }
        function setActiveAnchor(id: string | null) { activeAnchorId = typeof id === 'string' && id ? id : null; updateFocus(); }
        function setImmersive(value: boolean) { immersive = Boolean(value); if (immersive) {
            heroJob = null;
            heroGroup.visible = false;
            clearArrival();
        } resize(); }
        const publishedPoint = new THREE.Vector3();
        function publishAnchorProjection() {
            canvas.dataset.arrivalActive = String(Boolean(arrivalJob));
            canvas.dataset.arrivalSequence = String(arrivalSequence);
            canvas.dataset.flowColor = '#' + globalUniforms.uFlowColor.value.getHexString();
            canvas.dataset.flareStrength = globalUniforms.uFlareStrength.value.toFixed(3);
            canvas.dataset.totalFrontFrames = String(totalFrontFrames);
            canvas.dataset.totalBackFrames = String(totalBackFrames);
            canvas.dataset.heroActive = String(Boolean(heroJob) && !immersive);
            canvas.dataset.heroProgress = heroProgress.toFixed(3);
            canvas.dataset.heroSegment = heroSegment;
            canvas.dataset.heroFrontFrames = String(heroFrontFrames);
            canvas.dataset.heroBackFrames = String(heroBackFrames);
            canvas.dataset.constellationCount = String(fileEntries.size);
            canvas.dataset.activeAnchor = resolvedAnchorId || '';
            canvas.dataset.connectionsVisible = String(interactionUniforms.uDomOpacity.value > 0);
            publishedPoint.copy(flowStart).project(camera);
            canvas.dataset.coreX = ((publishedPoint.x + 1) * width * .5).toFixed(3);
            canvas.dataset.coreY = ((1 - publishedPoint.y) * height * .5).toFixed(3);
            const index = anchorList.findIndex(anchor => anchor.id === resolvedAnchorId);
            if (index < 0) {
                for (const key of ['anchorX', 'anchorY', 'anchorTargetX', 'anchorTargetY', 'anchorErrorPx'])
                    canvas.dataset[key] = '';
                return;
            }
            // This samples the actual uploaded point geometry after rendering, not the target vector.
            publishedPoint.fromBufferAttribute(anchorGeometry.attributes.position, index).applyMatrix4(anchorNodes.matrixWorld).project(camera);
            const x = (publishedPoint.x + 1) * width * .5, y = (1 - publishedPoint.y) * height * .5;
            const tx = anchorList[index].x * width, ty = anchorList[index].y * height;
            canvas.dataset.anchorX = x.toFixed(3);
            canvas.dataset.anchorY = y.toFixed(3);
            canvas.dataset.anchorTargetX = tx.toFixed(3);
            canvas.dataset.anchorTargetY = ty.toFixed(3);
            canvas.dataset.anchorErrorPx = Math.hypot(x - tx, y - ty).toFixed(6);
        }
        function getStats() {
            const constellation = Array.from(fileEntries.values(), file => { nodeProjection.copy(file!.group.position).project(camera); return { id: file.id, label: file.labelText, state: file.state, x: (nodeProjection.x + 1) * width * .5, y: (1 - nodeProjection.y) * height * .5, visible: file.group.visible, labelBounds: null }; });
            let maxAnchorProjectionErrorPx = 0;
            const projected = new THREE.Vector3();
            for (let i = 0; i < anchorList.length; i++) {
                // Verify the actual Float32 geometry position, using the render object's world transform.
                projected.fromBufferAttribute(anchorGeometry.attributes.position, i).applyMatrix4(anchorNodes.matrixWorld).project(camera);
                const actualX = (projected.x + 1) * width * .5, actualY = (1 - projected.y) * height * .5;
                maxAnchorProjectionErrorPx = Math.max(maxAnchorProjectionErrorPx, Math.hypot(actualX - anchorList[i].x * width, actualY - anchorList[i].y * height));
            }
            return { ready: !firstFrame && !disposed && contextAvailable && !shaderFailed, frames, phase, particles: totalParticles, width, height, pixelRatio, drawCalls: renderer.info.render.calls, geometries: renderer.info.memory.geometries, textures: renderer.info.memory.textures, anchors: anchorList.length, activeAnchor: resolvedAnchorId, connectionsVisible: interactionUniforms.uDomOpacity.value > 0, immersive, maxAnchorProjectionErrorPx, session: sessionKey, constellation, readingZones: zoneTargetWeights.reduce((n, v) => n + (v > 0 ? 1 : 0), 0), hero: { active: Boolean(heroJob), sequence: heroSequence, kind: heroJob?.kind ?? null, progress: heroProgress, segment: heroSegment, frontFrames: heroFrontFrames, backFrames: heroBackFrames, world: heroPosition.toArray() } };
        }
        function warp() { warpStarted = time; }
        function dispose() { if (disposed)
            return; disposed = true; cancelAnimationFrame(raf); window.removeEventListener('resize', resize); window.removeEventListener('pointermove', onPointer); window.removeEventListener('blur', onLeave); canvas.removeEventListener('webglcontextlost', onContextLost); canvas.removeEventListener('webglcontextrestored', onContextRestored); clearFiles(); for (const item of new Set(disposable))
            item.dispose(); scene.clear(); orbitalScene.clear(); postScene.clear(); renderer.dispose(); renderer.forceContextLoss(); canvas.dataset.ready = 'false'; canvas.dataset.disposed = 'true'; }
        releaseScene = dispose;
        window.addEventListener('resize', resize, { passive: true });
        window.addEventListener('pointermove', onPointer, { passive: true });
        window.addEventListener('blur', onLeave);
        canvas.addEventListener('webglcontextlost', onContextLost);
        canvas.addEventListener('webglcontextrestored', onContextRestored);
        resize();
        setPhase('idle');
        raf = requestAnimationFrame(tick);
        return { setPhase, setPaused, setFocus, setSession, setFileState, travel, setReadingZones, setAnchors, setActiveAnchor, setImmersive, warp, resize, dispose, getStats };
    }
    catch (error) {
        if (releaseScene)
            releaseScene();
        else {
            for (const resource of new Set(disposable))
                resource.dispose();
            renderer.dispose();
            renderer.forceContextLoss();
        }
        onError?.(error);
        return unavailable;
    }
}
