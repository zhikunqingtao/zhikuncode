import { Vector3 } from 'three';

export interface SpiderGaitInput {
    time: number;
    dt: number;
    body: Vector3;
    scale: number;
    /** World Z rotation; zero points the thorax toward +Y. */
    heading?: number;
    occupied: ReadonlySet<number>;
    landingTargets?: readonly (Vector3 | null)[];
    /** Optional renderer-owned grips, so released feet can step from their actual final location. */
    occupiedTargets?: readonly (Vector3 | null)[];
}
export interface SpiderTouchdown { leg: number; point: Vector3; strength: number }
export interface SpiderGaitResult {
    targets: Vector3[];
    planted: boolean[];
    bodyBob: number;
    bodyRoll: number;
    touchdowns: SpiderTouchdown[];
}
interface Foot {
    position: Vector3;
    from: Vector3;
    to: Vector3;
    state: 'stance' | 'swing' | 'occupied';
    elapsed: number;
    duration: number;
    landedAt: number;
    stride: number;
}

const ORDER = [0, 5, 2, 7, 1, 4, 3, 6];
const STANCE_X = [96, 118, 118, 96];
const STANCE_Y = [84, 30, -30, -84];
const ease = (value: number) => value * value * (3 - 2 * value);
const cap = (value: number, lower: number, upper: number) => Math.max(lower, Math.min(upper, value));
const finite = (point: Vector3) => Number.isFinite(point.x) && Number.isFinite(point.y) && Number.isFinite(point.z);

/** Independent world-space footfalls. No DOM reads, timers, or graphics ownership. */
export class SpiderGait {
    private feet: Foot[] = [];
    private lastBody = new Vector3();
    private velocity = new Vector3();
    private requestedTravel = new Vector3();
    private measuredVelocity = new Vector3();
    private steppingVelocity = new Vector3();
    private requestedVelocity = new Vector3();
    private lead = new Vector3();
    private landingBody = new Vector3();
    private planned = Array.from({ length: 8 }, () => new Vector3());
    private constrainedPoint = new Vector3();
    private constraintLegs = new Uint8Array(8);
    private constraintRadiiSquared = new Float64Array(8);
    private constraintCount = 0;
    private nextLaunch = 0;
    private sequence = 0;
    private previousScale = 1;
    private bob = 0;
    private roll = 0;

    reset(): void {
        this.feet = [];
        this.velocity.set(0, 0, 0);
        this.requestedTravel.set(0, 0, 0);
        this.nextLaunch = 0;
        this.sequence = 0;
        this.bob = 0;
        this.roll = 0;
    }

    /** Advance only as far as the current load-bearing feet allow; stepping feet release their constraint. */
    constrainBody(current: Vector3, desired: Vector3, scale: number, occupied: ReadonlySet<number>, out = new Vector3()): Vector3 {
        this.requestedTravel.subVectors(desired, current);
        this.constraintCount = 0;
        for (let leg = 0; leg < this.feet.length; leg++) {
            const foot = this.feet[leg];
            if (foot.state !== 'stance' || occupied.has(leg)) continue;
            const radius = Math.max(166 * scale, foot.position.distanceTo(current));
            this.constraintLegs[this.constraintCount] = leg;
            this.constraintRadiiSquared[this.constraintCount++] = radius * radius;
        }
        if (this.validBody(desired)) return out.copy(desired);
        // current is always feasible, including a newly acquired anchor just outside the reserve.
        // The intersection of foot-reach spheres is convex, so binary search cannot skip a valid interval.
        let low = 0, high = 1;
        const point = this.constrainedPoint;
        for (let iteration = 0; iteration < 24; iteration++) {
            const fraction = (low + high) / 2;
            point.lerpVectors(current, desired, fraction);
            if (this.validBody(point)) low = fraction; else high = fraction;
        }
        return out.copy(point.lerpVectors(current, desired, low));
    }

    private validBody(point: Vector3): boolean {
        for (let index = 0; index < this.constraintCount; index++) {
            if (!(point.distanceToSquared(this.feet[this.constraintLegs[index]].position) <= this.constraintRadiiSquared[index] + 1e-8)) return false;
        }
        return true;
    }

    /** Omit out for an independent snapshot; pass caller-owned storage to reuse per-frame output buffers. */
    update({ time, dt, body, scale, heading = 0, occupied, landingTargets, occupiedTargets }: SpiderGaitInput, out?: SpiderGaitResult): SpiderGaitResult {
        const elapsed = cap(dt, 0, .1);
        if (this.feet.length && (Math.abs(scale - this.previousScale) > .01
            || this.lastBody.distanceTo(body) > 190 * scale)) this.reset();
        if (this.feet.length && elapsed > 0) {
            const velocity = this.measuredVelocity.subVectors(body, this.lastBody).divideScalar(elapsed);
            velocity.z = 0;
            this.velocity.lerp(velocity, 1 - Math.exp(-elapsed * 6));
        }
        const steppingVelocity = this.steppingVelocity.copy(this.velocity);
        const requestedVelocity = this.requestedVelocity.copy(this.requestedTravel);
        if (elapsed > 0) requestedVelocity.divideScalar(elapsed); else requestedVelocity.set(0, 0, 0);
        requestedVelocity.z = 0;
        // A body waiting for support still intends to move: plant ahead of that intention,
        // instead of repeatedly stepping in place as the measured velocity drops to zero.
        if (requestedVelocity.lengthSq() > steppingVelocity.lengthSq()) steppingVelocity.copy(requestedVelocity);
        this.requestedTravel.set(0, 0, 0);
        const speed = steppingVelocity.length();
        const c = Math.cos(heading), s = Math.sin(heading);
        const planned = this.planned;
        const lead = this.lead.copy(steppingVelocity).multiplyScalar(.24).clampLength(0, 18 * scale);
        const landingBody = this.landingBody.copy(body).addScaledVector(steppingVelocity, .48);
        for (let leg = 0; leg < 8; leg++) {
            const side = leg < 4 ? -1 : 1, row = leg % 4;
            // Keep a real stance reserve, especially on phones where the 60px body height
            // consumes a larger fraction of the scaled leg reach.
            const x = side * STANCE_X[row] * scale;
            const y = STANCE_Y[row] * scale;
            const point = planned[leg].set(body.x + x * c - y * s, body.y + x * s + y * c, 1);
            point.add(lead);
            const candidate = landingTargets?.[leg];
            // Snapshot the selected text edge only when a step starts; stance never follows DOM motion.
            if (candidate && finite(candidate)
                && candidate.distanceTo(body) < 156 * scale
                && candidate.distanceTo(landingBody) < 166 * scale) point.copy(candidate);
        }
        if (!this.feet.length) {
            this.feet = planned.map((position, leg) => ({ position: position.clone(), from: position.clone(), to: position.clone(),
                state: occupied.has(leg) ? 'occupied' : 'stance', elapsed: 0, duration: .38,
                landedAt: time - ORDER.indexOf(leg) * .19, stride: 0 }));
            this.nextLaunch = time + .24;
        }
        this.previousScale = scale;
        this.lastBody.copy(body);
        const result = out ?? { targets: [], planted: [], bodyBob: 0, bodyRoll: 0, touchdowns: [] };
        const touchdowns = result.touchdowns;
        touchdowns.length = 0;
        let moving = 0;
        for (let leg = 0; leg < 8; leg++) {
            const foot = this.feet[leg];
            if (occupied.has(leg)) {
                foot.state = 'occupied';
                const grip = occupiedTargets?.[leg];
                if (grip && finite(grip)) foot.position.copy(grip);
                continue;
            }
            if (foot.state === 'occupied') {
                // A released grabbing leg returns via its own next step, without borrowing support legs.
                foot.state = 'stance'; foot.landedAt = time - 4;
            }
            if (foot.state !== 'swing') continue;
            foot.elapsed += elapsed;
            const progress = cap(foot.elapsed / foot.duration, 0, 1);
            foot.position.lerpVectors(foot.from, foot.to, ease(progress));
            // A short toe lift, clear aerial arc, then a slower settle at the selected edge.
            const arc = Math.sin(Math.PI * progress) ** 1.25;
            foot.position.z += arc * (12 + Math.min(foot.stride / scale * .18, 18)) * scale;
            if (progress >= 1) {
                foot.position.copy(foot.to); foot.state = 'stance'; foot.landedAt = time;
                touchdowns.push({ leg, point: foot.position.clone(), strength: cap(.3 + foot.stride / (100 * scale), .3, 1) });
            } else moving++;
        }
        if (moving < 2 && time >= this.nextLaunch) {
            let candidateLeg = -1, candidateScore = -Infinity;
            for (let rank = 0; rank < ORDER.length; rank++) {
                const leg = ORDER[(this.sequence + rank) % ORDER.length];
                const foot = this.feet[leg];
                if (occupied.has(leg) || foot.state !== 'stance' || time - foot.landedAt < .16) continue;
                const drift = foot.position.distanceTo(planned[leg]);
                const extension = foot.position.distanceTo(body) / scale;
                const idleDue = time - foot.landedAt > 2.2 + (leg % 4) * .18;
                if (drift < 27 * scale && extension < 164 && !idleDue) continue;
                const score = Math.max(0, extension - 153) * 4 + drift / scale + (idleDue ? 18 : 0) - rank * 2;
                // Keep the first leg on equal scores, matching the original stable priority order.
                if (score > candidateScore) { candidateLeg = leg; candidateScore = score; }
            }
            if (candidateLeg >= 0) {
                const foot = this.feet[candidateLeg];
                foot.from.copy(foot.position); foot.to.copy(planned[candidateLeg]);
                foot.stride = foot.from.distanceTo(foot.to); foot.elapsed = 0; foot.state = 'swing';
                foot.duration = cap(.43 - speed / scale * .001, .28, .43) + (candidateLeg % 2) * .035;
                this.sequence = (ORDER.indexOf(candidateLeg) + 1) % ORDER.length;
                this.nextLaunch = time + .105;
            }
        }
        let load = 0, sway = 0;
        for (let leg = 0; leg < 8; leg++) {
            const foot = this.feet[leg];
            if (foot.state !== 'swing') continue;
            const lift = Math.sin(Math.PI * cap(foot.elapsed / foot.duration, 0, 1));
            load += lift; sway += (leg < 4 ? 1 : -1) * lift;
        }
        const settle = 1 - Math.exp(-elapsed * 10);
        this.bob += ((Math.sin(time * 1.9) * .35 - load * 1.8) * scale - this.bob) * settle;
        this.roll += (sway * .024 - this.roll) * settle;
        for (let leg = 0; leg < 8; leg++) {
            (result.targets[leg] ??= new Vector3()).copy(this.feet[leg].position);
            result.planted[leg] = this.feet[leg].state === 'stance';
        }
        result.targets.length = 8; result.planted.length = 8;
        result.bodyBob = this.bob; result.bodyRoll = this.roll;
        return result;
    }
}
