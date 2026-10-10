import * as THREE from 'three';
import { LineSegments2 } from 'three/addons/lines/LineSegments2.js';
import { LineSegmentsGeometry } from 'three/addons/lines/LineSegmentsGeometry.js';
import { LineMaterial } from 'three/addons/lines/LineMaterial.js';

interface Pose {
    time: number; body: THREE.Vector3; targets: THREE.Vector3[]; scale: number; energy: number; hue?: number;
    /** Heading zero faces +Y; roll tilts about the longitudinal Y axis, pitch about X. */
    heading?: number; roll?: number; pitch?: number;
}
// Keep the first 24 segments as the eight three-bone legs, so renderedTip remains independent.
const SHELL_EDGES = [[0, 1], [1, 2], [2, 3], [3, 4], [4, 5], [5, 0], [0, 6], [2, 6], [4, 6],
    [7, 8], [8, 9], [9, 10], [10, 11], [11, 12], [12, 13], [13, 14], [14, 7],
    [7, 15], [9, 15], [11, 15], [13, 15], [3, 7]];
const SHELL_OFFSETS = [[0, 13, 3], [-6, 8, 0], [-5, -1, 0], [0, -5, 2], [5, -1, 0], [6, 8, 0], [0, 7, 8],
    [0, -9, 2], [-7, -11, 0], [-9, -18, 0], [-6, -25, 0], [0, -28, 1], [6, -25, 0], [9, -18, 0], [7, -11, 0], [0, -18, 8]];
const SEGMENTS = 24 + SHELL_EDGES.length + 16;
/** Fixed proportions: movement belongs to the body, never to infinitely stretching bones. */
export class SpiderRig {
    readonly group = new THREE.Group();
    readonly tips = Array.from({ length: 8 }, () => new THREE.Vector3());
    readonly reach = 190;
    private readonly positions = new Float32Array(SEGMENTS * 6);
    private readonly colors = new Float32Array(SEGMENTS * 6);
    private readonly points = new Float32Array(28 * 3);
    private readonly pointColors = new Float32Array(28 * 3);
    private readonly pointSizes = new Float32Array(28);
    private readonly geometry = new LineSegmentsGeometry();
    private readonly pointsGeometry = new THREE.BufferGeometry();
    private readonly core = new LineMaterial({ vertexColors: true, linewidth: 1.08, transparent: true, opacity: .98, depthTest: true, depthWrite: true });
    private readonly glow = new LineMaterial({ vertexColors: true, linewidth: 3.1, transparent: true, opacity: .07, blending: THREE.AdditiveBlending, depthTest: true, depthWrite: false });
    private readonly pointMaterial = new THREE.ShaderMaterial({
        transparent: true, depthTest: true, depthWrite: false,
        uniforms: { pixelRatio: { value: 1 } },
        vertexShader: `attribute vec3 color; attribute float size; varying vec3 vColor; uniform float pixelRatio;
          void main(){vColor=color;gl_Position=projectionMatrix*modelViewMatrix*vec4(position,1.);gl_PointSize=size*pixelRatio;}`,
        fragmentShader: `varying vec3 vColor;void main(){float d=length(gl_PointCoord-.5);float a=1.-smoothstep(.16,.5,d);if(a<.02)discard;gl_FragColor=vec4(vColor,a*.78);#include <colorspace_fragment>
}`.replace(';#include', ';\n#include'),
    });
    private readonly joints = Array.from({ length: 8 }, () => Array.from({ length: 4 }, () => new THREE.Vector3()));
    private readonly shell = SHELL_OFFSETS.map(() => new THREE.Vector3());
    private readonly orientation = new THREE.Quaternion();
    private readonly euler = new THREE.Euler(0, 0, 0, 'ZXY');
    private readonly color = new THREE.Color();
    private readonly delta = new THREE.Vector3();
    private readonly offset = new THREE.Vector3();
    private readonly preferred = new THREE.Vector3();
    private readonly bendAxis = new THREE.Vector3();
    private readonly bendPole = new THREE.Vector3();
    private readonly clawBack = new THREE.Vector3();
    private readonly clawCross = new THREE.Vector3();
    private readonly claw = new THREE.Vector3();
    private disposed = false;

    constructor(scene: THREE.Scene) {
        this.group.name = 'spider-articulated-rig';
        this.geometry.setPositions(this.positions); this.geometry.setColors(this.colors);
        for (const material of [this.glow, this.core]) {
            const line = new LineSegments2(this.geometry, material); line.frustumCulled = false; if (material === this.glow) line.layers.set(1); this.group.add(line);
        }
        this.pointsGeometry.setAttribute('position', new THREE.BufferAttribute(this.points, 3));
        this.pointsGeometry.setAttribute('color', new THREE.BufferAttribute(this.pointColors, 3));
        this.pointsGeometry.setAttribute('size', new THREE.BufferAttribute(this.pointSizes, 1));
        const points = new THREE.Points(this.pointsGeometry, this.pointMaterial); points.frustumCulled = false; points.layers.enable(1); this.group.add(points);
        scene.add(this.group);
    }
    resize(w: number, h: number, dpr: number) {
        this.core.resolution.set(w, h); this.glow.resolution.set(w, h); this.pointMaterial.uniforms.pixelRatio.value = dpr;
    }
    update({ time, body, targets, scale, energy, hue = 0, heading = 0, roll = 0, pitch = 0 }: Pose) {
        if (this.disposed) return;
        let segment = 0, point = 0;
        const yaw = heading + Math.sin(time * .43) * .035;
        this.orientation.setFromEuler(this.euler.set(pitch, roll, yaw));
        const first = 40 * scale, middle = 64 * scale, last = 86 * scale, reach = this.reach * scale;
        for (let i = 0; i < 8; i++) {
            const p = this.joints[i], side = i < 4 ? -1 : 1, row = i % 4;
            const hip = this.local(p[0], side * 7, 13 - row * 8, 0, scale).add(body);
            const target = p[3].copy(targets[i]);
            this.delta.subVectors(target, hip);
            if (this.delta.length() > reach) target.copy(hip).addScaledVector(this.delta.normalize(), reach);
            this.delta.subVectors(target, hip);
            p[1].copy(hip).addScaledVector(this.delta, .25).add(this.local(this.offset, side * 31, (1.5 - row) * 10, 18, scale));
            p[2].copy(hip).addScaledVector(this.delta, .65).add(this.local(this.offset, side * 13, (row - 1.5) * 5, 30 + Math.sin(time + i) * energy * 3, scale));
            const distance = hip.distanceTo(target);
            const preferred = this.preferred.copy(p[1]).sub(hip).normalize().multiplyScalar(first).add(hip);
            // Choose a remaining two-bone span inside both triangles' feasible intervals.
            // Unlike capped FABRIK iterations this also solves nearly straight/folded legs exactly.
            const minSpan = Math.max(Math.abs(distance - first), Math.abs(middle - last));
            const maxSpan = Math.min(distance + first, middle + last);
            const span = Math.max(minSpan, Math.min(maxSpan, preferred.distanceTo(target)));
            this.bendPoint(hip, target, first, span, p[1], p[1]);
            this.bendPoint(p[1], target, middle, last, p[2], p[2]);
            this.tips[i].copy(p[3]);
            const h = .49 + row * .105 + (side > 0 ? .05 : 0);
            for (let j = 0; j < 3; j++) {
                this.putLine(segment++, p[j], p[j + 1], h + j * .025, .48 + j * .025, hue);
                this.putPoint(point++, p[j + 1], h, j === 2 ? 2.1 : 2.55, hue);
            }
        }
        for (let i = 0; i < SHELL_OFFSETS.length; i++) {
            const [x, y, z] = SHELL_OFFSETS[i];
            this.local(this.shell[i], x, y, z, scale).add(body);
        }
        for (const [a, b] of SHELL_EDGES) this.putLine(segment++, this.shell[a], this.shell[b], a < 7 ? .51 : .81, a < 7 ? .57 : .50, hue);
        // Small split claws read as precise contact tools without changing any IK bone endpoint.
        for (let leg = 0; leg < 8; leg++) {
            const tip = this.joints[leg][3], back = this.clawBack.copy(this.joints[leg][2]).sub(tip).normalize();
            const cross = this.clawCross.set(-back.y, back.x, 0);
            if (cross.lengthSq() < .00001) cross.set(1, 0, 0); else cross.normalize();
            for (let side = -1; side <= 1; side += 2) {
                const claw = this.claw.copy(tip).addScaledVector(back, 4 * scale).addScaledVector(cross, side * 1.5 * scale);
                this.putLine(segment++, tip, claw, .52 + (leg % 4) * .10, .53, hue);
            }
        }
        this.putPoint(point++, this.local(this.offset, -2.3, 12, 4, scale).add(body), .51, 2.4, hue);
        this.putPoint(point++, this.local(this.offset, 2.3, 12, 4, scale).add(body), .51, 2.4, hue);
        this.putPoint(point++, this.shell[6], .53, 2.3, hue); this.putPoint(point, this.shell[15], .83, 2.3, hue);
        (this.geometry.attributes.instanceStart as THREE.InterleavedBufferAttribute).data.needsUpdate = true;
        (this.geometry.attributes.instanceColorStart as THREE.InterleavedBufferAttribute).data.needsUpdate = true;
        this.pointsGeometry.attributes.position.needsUpdate = true; this.pointsGeometry.attributes.color.needsUpdate = true;
        this.pointsGeometry.attributes.size.needsUpdate = true;
        this.glow.opacity = .045 + Math.min(2, energy) * .025;
    }
    /** Read the uploaded line geometry, independently of the solver's requested contact. */
    renderedTip(leg: number, out = new THREE.Vector3()) {
        const endpoint = this.geometry.attributes.instanceEnd;
        const index = leg * 3 + 2;
        return out.set(endpoint.getX(index), endpoint.getY(index), endpoint.getZ(index)).applyMatrix4(this.group.matrixWorld);
    }
    private local(out: THREE.Vector3, x: number, y: number, z: number, scale: number) {
        return out.set(x, y, z).multiplyScalar(scale).applyQuaternion(this.orientation);
    }
    private putLine(segment: number, a: THREE.Vector3, b: THREE.Vector3, h: number, brightness: number, hue: number) {
        this.color.setHSL((h + hue + 1) % 1, .93, brightness);
        const offset = segment * 6;
        a.toArray(this.positions, offset); b.toArray(this.positions, offset + 3);
        this.color.toArray(this.colors, offset); this.color.toArray(this.colors, offset + 3);
    }
    private putPoint(point: number, v: THREE.Vector3, h: number, size: number, hue: number) {
        this.pointSizes[point] = size;
        const offset = point * 3;
        v.toArray(this.points, offset);
        this.color.setHSL((h + hue + 1) % 1, .94, .59).toArray(this.pointColors, offset);
    }
    /** Sphere intersection circle, oriented toward the existing outward/upward knee preference. */
    private bendPoint(origin: THREE.Vector3, target: THREE.Vector3, first: number, second: number,
        preferred: THREE.Vector3, out: THREE.Vector3) {
        const axis = this.bendAxis.subVectors(target, origin), distance = axis.length();
        const pole = this.bendPole.subVectors(preferred, origin);
        if (distance < 1e-8) {
            if (pole.lengthSq() < 1e-12) pole.set(0, 0, 1);
            out.copy(origin).addScaledVector(pole.normalize(), first);
            return;
        }
        axis.divideScalar(distance);
        pole.addScaledVector(axis, -pole.dot(axis));
        if (pole.lengthSq() < 1e-12) {
            pole.set(0, 0, 1).addScaledVector(axis, -axis.z);
            if (pole.lengthSq() < 1e-12) pole.set(0, 1, 0).addScaledVector(axis, -axis.y);
        }
        pole.normalize();
        const along = Math.max(-first, Math.min(first, (distance * distance + first * first - second * second) / (2 * distance)));
        const perpendicular = Math.sqrt(Math.max(0, first * first - along * along));
        out.copy(origin).addScaledVector(axis, along).addScaledVector(pole, perpendicular);
    }
    dispose() {
        if (this.disposed) return; this.disposed = true;
        this.group.removeFromParent(); this.geometry.dispose(); this.pointsGeometry.dispose(); this.core.dispose(); this.glow.dispose(); this.pointMaterial.dispose();
    }
}
