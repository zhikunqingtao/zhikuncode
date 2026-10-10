import { describe, expect, it } from 'vitest';
import * as THREE from 'three';
import { SpiderRig } from './rig';

function geometrySegments(rig: SpiderRig) {
    const line = rig.group.children.find(child => 'geometry' in child && (child as THREE.Mesh).geometry.hasAttribute('instanceStart')) as THREE.Mesh;
    const start = line.geometry.getAttribute('instanceStart');
    const end = line.geometry.getAttribute('instanceEnd');
    return (index: number) => [new THREE.Vector3(start.getX(index), start.getY(index), start.getZ(index)),
        new THREE.Vector3(end.getX(index), end.getY(index), end.getZ(index))];
}

describe('Spider articulated contact', () => {
    it('keeps all eight feet on reachable moving grips without resizing bones', () => {
        const scene=new THREE.Scene(),rig=new SpiderRig(scene),body=new THREE.Vector3(10,20,55);
        for(let frame=0;frame<240;frame++){
            const t=frame/30;
            const targets=Array.from({length:8},(_,i)=>new THREE.Vector3(body.x+(i<4?-1:1)*(105+Math.sin(t+i)*14),body.y+(1.5-i%4)*48,8+Math.sin(t+i)*8));
            rig.update({time:t,body,targets,scale:1,energy:1});
            rig.tips.forEach((tip,i)=>expect(tip.distanceTo(targets[i])).toBeLessThan(.12));
        }
        rig.dispose();expect(scene.children).toHaveLength(0);rig.dispose();
    });
    it('cannot grow a leg to chase an unreachable DOM target',()=>{
        const scene=new THREE.Scene(),rig=new SpiderRig(scene),body=new THREE.Vector3();
        rig.update({time:1,body,targets:Array.from({length:8},()=>new THREE.Vector3(2000,-1500,0)),scale:1,energy:1});
        rig.tips.forEach(tip=>{expect(tip.length()).toBeLessThan(210);expect(tip.toArray().every(Number.isFinite)).toBe(true)});rig.dispose();
    });
    it('reuses a caller output while reading transformed uploaded geometry independently of requested tips', () => {
        const scene = new THREE.Scene(), rig = new SpiderRig(scene);
        rig.update({ time: 0, body: new THREE.Vector3(0, 0, 60),
            targets: Array.from({ length: 8 }, (_, leg) => new THREE.Vector3((leg < 4 ? -1 : 1) * 90, 12, 1)), scale: 1, energy: 1 });
        rig.group.position.set(8, -17, 6);
        rig.group.rotation.set(.2, -.3, .4);
        rig.group.scale.set(1.2, .8, 1.1);
        scene.updateMatrixWorld(true);
        const line = rig.group.children[0] as THREE.Mesh;
        const endpoint = line.geometry.getAttribute('instanceEnd');
        // Deliberately differ from the solver's tips to prove this reads the actual render buffer.
        endpoint.setXYZ(5, 21, 34, 55);
        const expected = new THREE.Vector3(21, 34, 55).applyMatrix4(rig.group.matrixWorld);
        const output = new THREE.Vector3();
        expect(rig.renderedTip(1, output)).toBe(output);
        expect(output.distanceTo(expected)).toBeLessThan(1e-10);
        expect(output.distanceTo(rig.tips[1])).toBeGreaterThan(1);
        const first = rig.renderedTip(1), second = rig.renderedTip(1);
        expect(first).not.toBe(second);
        expect(first.equals(second)).toBe(true);
        output.set(900, 900, 900);
        expect(rig.renderedTip(1).equals(first)).toBe(true);
        rig.dispose();
    });
    it('keeps uploaded geometry within 0.1 CSS px for seeded browser-like poses at every device scale', () => {
        let seed = 0x29f8ace1;
        const random = () => { seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0; return seed / 0x100000000; };
        const scene = new THREE.Scene(), rig = new SpiderRig(scene), segments = geometrySegments(rig);
        let maxPixels = 0, maxBoneError = 0;
        for (const [width, height, scale] of [[390, 844, .72], [1024, 768, .88], [1728, 1117, 1.12]]) {
            const camera = new THREE.PerspectiveCamera(38, width / height, 1, 8000);
            const cameraZ = height / (2 * Math.tan(THREE.MathUtils.degToRad(19)));
            camera.position.z = cameraZ; camera.updateMatrixWorld();
            const screen = (position: THREE.Vector3) => {
                const point = position.clone().project(camera);
                return new THREE.Vector2((point.x * .5 + .5) * width, (.5 - point.y * .5) * height);
            };
            for (let frame = 0; frame < 500; frame++) {
                const body = new THREE.Vector3((random() - .5) * width * .7, (random() - .5) * height * .5, 45 + random() * 100);
                const targets = Array.from({ length: 8 }, () => {
                    const azimuth = random() * Math.PI * 2, cosine = random() * 2 - 1;
                    const sine = Math.sqrt(1 - cosine * cosine);
                    // Include exact zero and maximum radius, and arbitrary 3D folding directions.
                    const radius = (frame % 10 === 0 ? 0 : frame % 10 === 1 ? 175 : random() * 175) * scale;
                    return body.clone().add(new THREE.Vector3(Math.cos(azimuth) * sine, Math.sin(azimuth) * sine, cosine).multiplyScalar(radius));
                });
                rig.update({ time: frame / 30, body, targets, scale, energy: 1.5,
                    heading: random() * Math.PI * 2, roll: (random() - .5) * .3, pitch: (random() - .5) * .25 });
                for (let leg = 0; leg < 8; leg++) {
                    maxPixels = Math.max(maxPixels, screen(rig.renderedTip(leg)).distanceTo(screen(targets[leg])));
                    for (let bone = 0; bone < 3; bone++) {
                        const [start, end] = segments(leg * 3 + bone);
                        maxBoneError = Math.max(maxBoneError, Math.abs(start.distanceTo(end) - [40, 64, 86][bone] * scale));
                    }
                }
            }
        }
        expect(maxPixels).toBeLessThan(.1);
        expect(maxBoneError).toBeLessThan(.002);
        rig.dispose();
    });
    it('solves exact hip coincidence, fully straight legs and pole-axis singularities without invalid geometry', () => {
        const rig = new SpiderRig(new THREE.Scene()), segments = geometrySegments(rig);
        const body = new THREE.Vector3(), scale = 1;
        for (const distance of [0, .000001, 22, 40, 104, 150, 189.99, 190]) {
            for (const axis of [new THREE.Vector3(0, 0, 1), new THREE.Vector3(1, 0, 0), new THREE.Vector3(0, 1, 0)]) {
                const targets = Array.from({ length: 8 }, (_, leg) => new THREE.Vector3((leg < 4 ? -1 : 1) * 7, 13 - leg % 4 * 8, 0).addScaledVector(axis, distance));
                rig.update({ time: 0, body, targets, scale, energy: 1 });
                for (let leg = 0; leg < 8; leg++) {
                    expect(rig.renderedTip(leg).distanceTo(targets[leg])).toBeLessThan(.001);
                    for (let bone = 0; bone < 3; bone++) {
                        const [start, end] = segments(leg * 3 + bone);
                        expect(start.toArray().concat(end.toArray()).every(Number.isFinite)).toBe(true);
                        expect(start.distanceTo(end)).toBeCloseTo([40, 64, 86][bone], 3);
                    }
                }
            }
        }
        rig.dispose();
    });
});
