import { describe, expect, it } from 'vitest';
import { MIN_RELEASE_AGE, RETURN_DURATION, sampleLiftMotion, terminalReleaseAt, scheduleRelease } from './choreography';

describe('spider force choreography', () => {
    it('locks the source through contact and anticipation before pulling', () => {
        for (let t = .18; t <= .42; t += .01) {
            const motion = sampleLiftMotion(t, 3);
            expect(motion.contact).toBe(1);
            expect(motion.elevation).toBe(0);
            expect(motion.flip).toBe(0);
        }
        expect(sampleLiftMotion(.39, 3).tension).toBeGreaterThan(.9);
        expect(sampleLiftMotion(.72, 3).elevation).toBeGreaterThan(1);
    });
    it('finishes the flip before even an immediate result begins its return', () => {
        const releaseAge = terminalReleaseAt(20.01, 20) - 20;
        expect(releaseAge).toBeCloseTo(MIN_RELEASE_AGE);
        expect(sampleLiftMotion(releaseAge, releaseAge).flip).toBe(Math.PI * 2);
        expect(sampleLiftMotion(releaseAge + .5, releaseAge).flip).toBe(Math.PI * 2);
    });
    it('allows late results to finish without a fixed lifespan cutting off the return', () => {
        const releaseAge = terminalReleaseAt(29, 20) - 20;
        expect(sampleLiftMotion(9.4, releaseAge).finished).toBe(false);
        const done = sampleLiftMotion(releaseAge + RETURN_DURATION, releaseAge);
        expect(done.finished).toBe(true);
        expect(done.elevation).toBe(0);
        expect(done.opacity).toBe(0);
    });
    it('cannot rewind a return on repeated complete/reset/result events', () => {
        const early = scheduleRelease(.1, 0, 3, null);
        expect(early).toBe(MIN_RELEASE_AGE);
        expect(scheduleRelease(1.9, 0, 3, early)).toBe(early);
        expect(scheduleRelease(2.5, 0, 3, null)).toBe(3 - RETURN_DURATION);
        const immediate = scheduleRelease(.5, 0, 3, null, true);
        expect(scheduleRelease(1, 0, 3, immediate, true)).toBe(immediate);
    });
    it('keeps all mechanical tracks continuous at phase boundaries', () => {
        for (const t of [.17, .2, .42, .64, .7, .82, 1.48, 3, 3.78]) {
            const before = sampleLiftMotion(t - 1e-6, 3), after = sampleLiftMotion(t + 1e-6, 3);
            for (const key of ['elevation', 'tension', 'flip', 'opacity', 'recoil'] as const) {
                expect(Math.abs(after[key] - before[key])).toBeLessThan(.0001);
            }
        }
    });
});
