const clamp = (value: number) => Math.max(0, Math.min(1, value));
const smooth = (a: number, b: number, value: number) => {
    const t = clamp((value - a) / (b - a));
    return t * t * (3 - 2 * t);
};

/** Mechanical timing is independent of a tool's immediately updated business state. */
export const MIN_RELEASE_AGE = 1.55;
export const RETURN_DURATION = .78;
export function terminalReleaseAt(now: number, started: number): number {
    return Math.max(now + .35, started + MIN_RELEASE_AGE);
}

/** A repeated lifecycle event may shorten a return deadline, never rewind it. */
export function scheduleRelease(now: number, started: number, duration: number, previous: number | null, immediate = false): number {
    const natural = started + duration - RETURN_DURATION;
    return Math.min(previous ?? natural, natural, immediate ? now : terminalReleaseAt(now, started));
}

export function sampleLiftMotion(age: number, releaseAge: number) {
    const returning = smooth(releaseAge, releaseAge + RETURN_DURATION, age);
    const pull = smooth(.42, .70, age);
    const settleTime = Math.max(0, age - .70);
    // A restrained overshoot supplies weight without ever stretching the skeleton.
    const rebound = Math.sin(settleTime * 15) * Math.exp(-settleTime * 4.2) * .095;
    const elevation = (pull + rebound) * (1 - returning);
    const tension = smooth(.20, .42, age) * (1 - smooth(.46, .72, age));
    const flip = smooth(.64, 1.48, age) * Math.PI * 2;
    return {
        returning,
        elevation,
        tension,
        flip,
        recoil: Math.sin(Math.PI * smooth(.42, .82, age)) * (1 - returning),
        contact: smooth(0, .17, age),
        opacity: smooth(.04, .16, age) * (1 - smooth(.88, 1, returning)),
        finished: age >= releaseAge + RETURN_DURATION,
    };
}
