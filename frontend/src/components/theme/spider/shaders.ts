import * as THREE from 'three';
import { WEB_CONTACT_COUNT, WEB_CONTACT_DAMPING, WEB_CONTACT_LIFETIME, WEB_TENSION_COUNT } from './webDynamics';

const vertex = `varying vec2 vUv;void main(){vUv=uv;gl_Position=vec4(position.xy,0.,1.);}`;

export function createWebMaterial() {
    return new THREE.ShaderMaterial({
        transparent: true, depthTest: false, depthWrite: false,
        uniforms: {
            time: { value: 0 }, resolution: { value: new THREE.Vector2(1280,720) },
            pulse: { value: 0 }, body: { value: new THREE.Vector2(.5,.5) },
            // Left/top screen UV, strength, elapsed seconds. A negative age disables a contact.
            contacts: { value: Array.from({ length: WEB_CONTACT_COUNT }, () => new THREE.Vector4(0, 0, 0, -1)) },
            // Up to three pairs of UV endpoints; min(A.z, B.z) is the current pull strength.
            tensionA: { value: Array.from({ length: WEB_TENSION_COUNT }, () => new THREE.Vector3(0, 0, 0)) },
            tensionB: { value: Array.from({ length: WEB_TENSION_COUNT }, () => new THREE.Vector3(0, 0, 0)) },
        },
        vertexShader: vertex,
        fragmentShader: `
          varying vec2 vUv;uniform float time,pulse;uniform vec2 resolution,body;
          uniform vec4 contacts[${WEB_CONTACT_COUNT}];
          uniform vec3 tensionA[${WEB_TENSION_COUNT}],tensionB[${WEB_TENSION_COUNT}];
          const float PI=3.14159265;

          float strand(float d,float width){
            float aa=clamp(fwidth(d),.55,1.15);
            return 1.-smoothstep(max(0.,width-aa*.5),width+aa*.5,d);
          }

          // A logarithmic capture spiral with a slight sag between the load-bearing spokes.
          // Geometry is stationary until a footfall or pull displaces it.
          vec2 silk(vec2 p,vec2 origin,float rotation,float spokeCount){
            vec2 q=p-origin*resolution;
            float r=max(length(q),.1),a=atan(q.y,q.x)+rotation;
            float angle=a+sin(r*.004+rotation)*.022;
            float spokePhase=angle*spokeCount*.5;
            float radialDistance=abs(sin(spokePhase))*r/(spokeCount*.5);
            float sector=fract(angle*spokeCount/(2.*PI));
            float sag=sin(sector*PI);
            float phase=log(1.+r/200.)*21.5+sag*sag*.115+sin(a*3.+rotation)*.04;
            float ringDistance=abs(fract(phase)-.5)*(200.+r)/21.5;
            float core=max(strand(radialDistance,.32),strand(ringDistance,.23)*.76);
            float veil=max(strand(radialDistance,1.15),strand(ringDistance,.85)*.7);
            float reach=exp(-r/max(260.,min(resolution.x,resolution.y)*.88));
            return vec2(core,veil)*reach;
          }

          float contactEnergy(float age,float strength){
            return clamp(strength,0.,1.)*exp(-age*${WEB_CONTACT_DAMPING.toFixed(1)})
              *(1.-smoothstep(${(WEB_CONTACT_LIFETIME * .75).toFixed(2)},${WEB_CONTACT_LIFETIME.toFixed(1)},age));
          }
          void main(){
            vec2 uv=vec2(vUv.x,1.-vUv.y);
            vec2 px=uv*resolution,warp=vec2(0.);
            float energy=0.,pullSilk=0.;
            for(int i=0;i<${WEB_CONTACT_COUNT};i++){
              vec4 contact=contacts[i];
              if(contact.z<=0.||contact.w<0.||contact.w>=${WEB_CONTACT_LIFETIME.toFixed(1)})continue;
              vec2 delta=px-contact.xy*resolution;
              float d=length(delta),age=contact.w;
              float local=exp(-d/135.)*(1.-smoothstep(180.,300.,d));
              float arrival=age-d/220.;
              float envelope=contactEnergy(age,contact.z);
              // The travelling front moves the strands themselves; it never draws a free ring.
              if(arrival>=0.){
                float vibration=sin(arrival*31.)*exp(-arrival*4.6);
                warp+=delta/max(d,1.)*vibration*local*envelope*8.;
              }
              float front=(d-age*220.)/27.;
              float travelling=exp(-front*front);
              float impact=exp(-d/28.)*exp(-age*7.);
              energy+=envelope*local*(travelling*.95+impact*.6);
            }
            for(int i=0;i<${WEB_TENSION_COUNT};i++){
              float strength=clamp(min(tensionA[i].z,tensionB[i].z),0.,1.);
              if(strength<=0.)continue;
              vec2 a=tensionA[i].xy*resolution,b=tensionB[i].xy*resolution,ab=b-a;
              float len=max(length(ab),1.);
              vec2 direction=ab/len,normal=vec2(-direction.y,direction.x);
              float along=clamp(dot(px-a,ab)/max(dot(ab,ab),1.),0.,1.);
              vec2 offset=px-(a+ab*along);
              float d=length(offset),falloff=exp(-d/43.)*(1.-smoothstep(90.,160.,d));
              float span=sin(along*PI);
              float tremor=sin(time*26.-along*len*.065)*span;
              // A is the attachment, B the pulling endpoint. Neighboring strands stretch with it.
              warp-=direction*min(len*.075,18.)*strength*falloff*(.3+.7*span);
              warp+=normal*tremor*strength*falloff*1.7;
              float filamentDistance=abs(dot(offset,normal)-tremor*.7);
              float ends=smoothstep(0.,.035,along)*(1.-smoothstep(.965,1.,along));
              float packet=pow(.5+.5*cos(along*len*.055-time*7.),8.);
              pullSilk+=strand(filamentDistance,.27)*ends*strength*(.28+.72*packet);
              energy+=strength*falloff*(.12+.7*packet);
            }
            vec2 silkField=silk(px+warp,vec2(-.045,.08),.13,28.);
            silkField+=silk(px+warp,vec2(1.04,.88),.43,25.)*.78;
            silkField+=silk(px+warp,vec2(.78,-.19),1.1,19.)*.24;
            float edge=min(min(uv.x,1.-uv.x),min(uv.y,1.-uv.y));
            float border=1.-smoothstep(.018,.34,edge);
            float bodyDistance=length(px-body*resolution);
            energy+=clamp(pulse,0.,1.)*exp(-bodyDistance/62.)*(1.-smoothstep(80.,165.,bodyDistance))*.65;
            energy=min(energy,1.8);
            vec3 color=mix(vec3(.28,.66,.78),vec3(.54,.32,.72),smoothstep(.12,.95,uv.x));
            color=mix(color,vec3(.58,.9,1.),min(energy*.6,.72));
            float alpha=silkField.x*(.012+border*.145+energy*.34)
              +silkField.y*border*.006+pullSilk*.18;
            gl_FragColor=vec4(color,clamp(alpha,0.,.36));
          }`,
    });
}

/** Only the wire/particle layer enters the glow buffer; text is never blurred. */
export function createCompositeMaterial(base: THREE.Texture, glow: THREE.Texture, exclusionMask: THREE.Texture) {
    return new THREE.ShaderMaterial({
        depthTest: false, depthWrite: false, blending: THREE.NoBlending,
        uniforms: {
            base: { value: base }, glow: { value: glow }, texel: { value: new THREE.Vector2(1/1280,1/720) },
            resolution: { value: new THREE.Vector2(1280,720) }, exclusionMask: { value: exclusionMask },
            pointer: { value: new THREE.Vector3(-1000,-1000,0) },
        },
        vertexShader: vertex,
        fragmentShader: `
          varying vec2 vUv;uniform sampler2D base,glow,exclusionMask;uniform vec2 texel,resolution;uniform vec3 pointer;
          void main(){
            vec4 c=texture2D(base,vUv);vec3 g=texture2D(glow,vUv).rgb*.3;
            g+=(texture2D(glow,vUv+texel*vec2(1.25,0.)).rgb+texture2D(glow,vUv-texel*vec2(1.25,0.)).rgb)*.13;
            g+=(texture2D(glow,vUv+texel*vec2(0.,1.25)).rgb+texture2D(glow,vUv-texel*vec2(0.,1.25)).rgb)*.13;
            g+=(texture2D(glow,vUv+texel*vec2(1.25,.75)).rgb+texture2D(glow,vUv-texel*vec2(1.25,.75)).rgb)*.035;
            float ga=max(g.r,max(g.g,g.b))*.62;float a=max(c.a,ga);vec3 rgb=c.rgb+g*.42;
            vec2 px=vec2(vUv.x,1.-vUv.y)*resolution;float mask=1.-texture2D(exclusionMask,vUv).a;
            if(pointer.z>0.)mask*=smoothstep(36.,86.,distance(px,pointer.xy));
            gl_FragColor=vec4(rgb,a);
            #include <colorspace_fragment>
            // The canvas uses premultiplied alpha. Mask RGB as well as alpha,
            // after output conversion, so excluded controls receive no residual glow.
            gl_FragColor*=mask;
          }`,
    });
}
