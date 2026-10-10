import { describe, expect, it } from 'vitest';
import { Scene, Vector3 } from 'three';
import { SpiderGait, type SpiderGaitResult } from './gait';
import { SpiderRig } from './rig';

describe('SpiderGait world-space contacts', () => {
    it('holds planted feet still while the body and candidate DOM targets move', () => {
        const gait = new SpiderGait(), occupied = new Set<number>(), body = new Vector3(0, 0, 60);
        const initial = gait.update({ time: 0, dt: 1 / 60, body, scale: 1, occupied });
        const landingTargets = initial.targets.map(point => point.clone().add(new Vector3(8, 4, 0)));
        const next = gait.update({ time: .1, dt: .1, body: new Vector3(7, 3, 60), scale: 1, occupied, landingTargets });
        expect(next.planted.every(Boolean)).toBe(true);
        next.targets.forEach((target, leg) => expect(target.equals(initial.targets[leg])).toBe(true));
        // Returned snapshots cannot alter the gait's internal world anchors.
        initial.targets[0].set(900, 900, 900);
        expect(gait.update({ time: .15, dt: .05, body, scale: 1, occupied }).targets[0].x).not.toBe(900);
    });

    it('staggers independent steps, never raises over two ordinary legs, and lands with a pause', () => {
        const gait = new SpiderGait(), occupied = new Set<number>();
        let previous = gait.update({ time: 0, dt: 1 / 60, body: new Vector3(0, 0, 60), scale: 1, occupied });
        const lastLand = new Map<number, number>(), touched = new Set<number>();
        let aerialSamples = 0, asymmetricSamples = 0;
        for (let frame = 1; frame < 1200; frame++) {
            const time = frame / 60;
            const body = new Vector3(Math.sin(time * .4) * 80, Math.cos(time * .3) * 40 - 40, 60);
            const result = gait.update({ time, dt: 1 / 60, body, scale: 1, heading: Math.sin(time * .17) * .3, occupied });
            expect(result.planted.filter(value => !value).length).toBeLessThanOrEqual(2);
            for (let leg = 0; leg < 8; leg++) {
                if (previous.planted[leg] && result.planted[leg]) expect(result.targets[leg].distanceTo(previous.targets[leg])).toBe(0);
                if (previous.planted[leg] && !result.planted[leg] && lastLand.has(leg)) expect(time - lastLand.get(leg)!).toBeGreaterThanOrEqual(.16);
                if (!result.planted[leg] && result.targets[leg].z > 5) aerialSamples++;
                expect(result.targets[leg].toArray().every(Number.isFinite)).toBe(true);
            }
            for (const event of result.touchdowns) {
                touched.add(event.leg); lastLand.set(event.leg, time);
                expect(result.planted[event.leg]).toBe(true);
                expect(event.point.distanceTo(result.targets[event.leg])).toBe(0);
                expect(event.point.z).toBe(1);
            }
            if (Math.abs(result.bodyRoll) > .002) asymmetricSamples++;
            previous = result;
        }
        expect(touched.size).toBe(8);
        expect(aerialSamples).toBeGreaterThan(200);
        expect(asymmetricSamples).toBeGreaterThan(50);
    });

    it('never steps renderer-owned grabbing legs and resumes from the final grip', () => {
        const gait = new SpiderGait(), body = new Vector3(0, 0, 60), occupied = new Set([0, 5, 7]);
        const grips = Array.from({ length: 8 }, (_, leg) => occupied.has(leg) ? new Vector3(20 + leg * 4, 30, 95) : null);
        for (let frame = 0; frame < 240; frame++) {
            const result = gait.update({ time: frame / 60, dt: 1 / 60, body, scale: 1, occupied, occupiedTargets: grips });
            expect(result.planted.filter((value, leg) => !value && !occupied.has(leg)).length).toBeLessThanOrEqual(2);
            for (const leg of occupied) {
                expect(result.planted[leg]).toBe(false);
                expect(result.targets[leg].equals(grips[leg]!)).toBe(true);
                expect(result.touchdowns.some(event => event.leg === leg)).toBe(false);
            }
        }
        const released = gait.update({ time: 4, dt: 1 / 60, body, scale: 1, occupied: new Set(), occupiedTargets: grips });
        for (const leg of occupied) expect(released.targets[leg].equals(grips[leg]!)).toBe(true);
    });

    it('reinitializes contacts explicitly on reset, device scale changes and teleports', () => {
        const gait = new SpiderGait(), occupied = new Set<number>();
        gait.update({ time: 5, dt: 1 / 60, body: new Vector3(), scale: 1, occupied });
        gait.reset();
        const reset = gait.update({ time: 0, dt: 1 / 60, body: new Vector3(500, 100, 60), scale: .72, occupied });
        expect(reset.planted.every(Boolean)).toBe(true);
        expect(reset.touchdowns).toHaveLength(0);
        reset.targets.forEach(target => expect(target.distanceTo(new Vector3(500, 100, 60))).toBeLessThan(160));
    });

    it('preserves gait results and snapshot isolation when the renderer reuses all output buffers', () => {
        const snapshots = new SpiderGait(), reused = new SpiderGait();
        const output: SpiderGaitResult = { targets: Array.from({ length: 8 }, () => new Vector3()),
            planted: Array(8).fill(false), bodyBob: 0, bodyRoll: 0, touchdowns: [] };
        const targets = [...output.targets], targetArray = output.targets, planted = output.planted, touchdowns = output.touchdowns;
        const constrained = new Vector3(), body = new Vector3(0, 0, 60);
        let eventCount = 0;
        let previousSnapshot: SpiderGaitResult | undefined;
        for (let frame = 0; frame < 720; frame++) {
            const scale = frame < 360 ? 1 : .72;
            const time = frame / 60, dt = frame % 73 ? 1 / 60 : 0;
            const occupied = new Set(frame % 120 < 30 ? [0, 7] : []);
            const desired = body.clone().add(new Vector3(scale, Math.sin(time) * .2, 0));
            const expectedBody = snapshots.constrainBody(body, desired, scale, occupied);
            expect(reused.constrainBody(body, desired, scale, occupied, constrained)).toBe(constrained);
            expect(constrained.equals(expectedBody)).toBe(true);
            body.copy(constrained);
            const grips = Array.from({ length: 8 }, (_, leg) => occupied.has(leg) ? body.clone().add(new Vector3(20, 30, 10)) : null);
            const input = { time, dt, body, scale, occupied, occupiedTargets: grips };
            const expected = snapshots.update(input);
            const actual = reused.update(input, output);
            expect(actual).toBe(output);
            expect(actual).toEqual(expected);
            expect(actual.targets).toBe(targetArray);
            expect(actual.planted).toBe(planted);
            expect(actual.touchdowns).toBe(touchdowns);
            expect(previousSnapshot?.targets).not.toBe(expected.targets);
            for (let leg = 0; leg < 8; leg++) expect(actual.targets[leg]).toBe(targets[leg]);
            eventCount += actual.touchdowns.length;
            previousSnapshot = expected;
            // Caller-owned outputs are copies; neither controls internal foot anchors.
            output.targets[0].set(1e6, 1e6, 1e6);
            for (const event of output.touchdowns) event.point.set(1e6, 1e6, 1e6);
        }
        expect(eventCount).toBeGreaterThan(10);
    });

    it('keeps real rendered stance feet fixed under continuous PC and phone movement without deadlocking', () => {
        for (const scale of [.72, 1.12]) for (const speed of [44, 66]) {
            const gait = new SpiderGait(), rig = new SpiderRig(new Scene()), occupied = new Set<number>();
            let body = new Vector3(0, 0, 60);
            let previous = gait.update({ time: 0, dt: 1 / 60, body, scale, occupied });
            let footfalls = 0, maxError = 0;
            for (let frame = 1; frame <= 900; frame++) {
                const time = frame / 60;
                const desired = body.clone().add(new Vector3(speed * scale / 60, Math.sin(time * .7) * .2 * scale, 0));
                body = gait.constrainBody(body, desired, scale, occupied);
                const step = gait.update({ time, dt: 1 / 60, body, scale, heading: Math.sin(time * .4) * .12, occupied });
                const posed = body.clone(); posed.z += step.bodyBob;
                rig.update({ time, body: posed, targets: step.targets, scale, heading: Math.sin(time * .4) * .12,
                    roll: step.bodyRoll + Math.sin(time) * .04, pitch: Math.cos(time * .8) * .035, energy: .6 });
                for (let leg = 0; leg < 8; leg++) if (step.planted[leg]) {
                    maxError = Math.max(maxError, rig.renderedTip(leg).distanceTo(step.targets[leg]));
                    if (previous.planted[leg]) expect(step.targets[leg].equals(previous.targets[leg])).toBe(true);
                }
                footfalls += step.touchdowns.length;
                previous = step;
            }
            expect(maxError).toBeLessThan(.01);
            expect(footfalls).toBeGreaterThan(25);
            // The most stretched foot is scheduled next, releasing the body constraint rather than stalling forever.
            expect(body.x).toBeGreaterThan(speed * scale * 15 * .45);
            rig.dispose();
        }
    });
});
