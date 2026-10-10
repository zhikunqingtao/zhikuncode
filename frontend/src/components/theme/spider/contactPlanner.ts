export interface ContactPoint { x: number; y: number; }
export interface ContactArea { left: number; right: number; top: number; bottom: number; }

const CONTACT_OFFSET = 4;
const BODY_HEIGHT = 60;
const CONTACT_REACH = 145;

/**
 * Find a local, reachable body center instead of escaping to a distant screen corner.
 * Coordinates are CSS pixels. The caller supplies its permissible body-center area and
 * an already-padded exclusion predicate. The 60px body elevation consumes leg reach.
 * No available contact is a normal null result; this function never moves the anchor.
 */
export function planContactBodyPoint(
    anchorRect: ContactArea,
    preferred: ContactPoint,
    areaRect: ContactArea,
    scale: number,
    blocked: (x: number, y: number) => boolean,
): ContactPoint | null {
    if (![anchorRect.left, anchorRect.right, anchorRect.top, anchorRect.bottom,
        areaRect.left, areaRect.right, areaRect.top, areaRect.bottom,
        preferred.x, preferred.y, scale].every(Number.isFinite)
        || scale <= 0 || anchorRect.right <= anchorRect.left || anchorRect.bottom <= anchorRect.top
        || areaRect.right <= areaRect.left || areaRect.bottom <= areaRect.top) return null;

    const reach = CONTACT_REACH * scale;
    if (reach <= BODY_HEIGHT) return null;
    // Leave a small reserve for body bobbing and screen-to-world perspective conversion.
    const radius = Math.sqrt(reach * reach - BODY_HEIGHT * BODY_HEIGHT) - 6 * scale;
    if (radius <= 0) return null;
    const centerX = (anchorRect.left + anchorRect.right) / 2;
    const centerY = (anchorRect.top + anchorRect.bottom) / 2;
    const contacts = [anchorRect.left - CONTACT_OFFSET, anchorRect.right + CONTACT_OFFSET];
    const valid = (point: ContactPoint): boolean => {
        if (point.x < areaRect.left || point.x > areaRect.right
            || point.y < areaRect.top || point.y > areaRect.bottom) return false;
        // Match the renderer's grip-side decision rather than whichever circle produced it.
        const contactX = point.x > centerX ? contacts[1] : contacts[0];
        if (Math.hypot(point.x - contactX, point.y - centerY) > radius + 1e-7) return false;
        return !blocked(point.x, point.y);
    };
    if (valid(preferred)) return { ...preferred };

    let best: ContactPoint | null = null;
    let bestScore = Infinity;
    const consider = (x: number, y: number) => {
        const point = {
            x: Math.max(areaRect.left, Math.min(areaRect.right, x)),
            y: Math.max(areaRect.top, Math.min(areaRect.bottom, y)),
        };
        if (!valid(point)) return;
        const score = (point.x - preferred.x) ** 2 + (point.y - preferred.y) ** 2;
        if (score < bestScore) { best = point; bestScore = score; }
    };

    consider(preferred.x, preferred.y);
    // Both sides matter: the preferred side may be entirely occupied by another toolbar.
    for (const contactX of contacts) {
        consider(contactX, centerY);
        const preferredAngle = Math.atan2(preferred.y - centerY, preferred.x - contactX);
        for (const fraction of [1, 0.85, 0.7, 0.55, 0.4, 0.25, 0.1]) {
            for (let index = 0; index < 24; index++) {
                const angle = preferredAngle + index * Math.PI / 12;
                consider(contactX + Math.cos(angle) * radius * fraction,
                    centerY + Math.sin(angle) * radius * fraction);
            }
        }
    }
    return best;
}
