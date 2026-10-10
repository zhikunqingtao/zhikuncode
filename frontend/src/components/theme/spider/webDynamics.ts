export const WEB_CONTACT_COUNT = 8;
export const WEB_TENSION_COUNT = 3;
export const WEB_CONTACT_LIFETIME = 2.4;
export const WEB_CONTACT_DAMPING = 1.8;

/** Compatible with Vector4 without depending on THREE. UV uses the screen's top-left origin. */
export interface WebContact { x: number; y: number; z: number; w: number; }

/** Energy remaining in a footfall. Negative age represents an unused slot. */
export function contactEnvelope(ageSeconds: number, strength = 1): number {
    if (!Number.isFinite(ageSeconds) || !Number.isFinite(strength)
        || ageSeconds < 0 || ageSeconds >= WEB_CONTACT_LIFETIME) return 0;
    const tail = Math.max(0, Math.min(1, (ageSeconds - WEB_CONTACT_LIFETIME * .75) / (WEB_CONTACT_LIFETIME * .25)));
    return Math.max(0, Math.min(1, strength)) * Math.exp(-ageSeconds * WEB_CONTACT_DAMPING)
        * (1 - tail * tail * (3 - 2 * tail));
}

/** Advance existing vectors in place; elapsed wall time can expire contacts after a pause. */
export function advanceWebContacts(contacts: WebContact[], deltaSeconds: number): void {
    if (!Number.isFinite(deltaSeconds) || deltaSeconds <= 0) return;
    for (let index = 0; index < Math.min(contacts.length, WEB_CONTACT_COUNT); index++) {
        const contact = contacts[index];
        if (contact.z <= 0 || contact.w < 0) continue;
        contact.w += deltaSeconds;
        if (!Number.isFinite(contact.w) || contact.w >= WEB_CONTACT_LIFETIME) {
            contact.z = 0;
            contact.w = -1;
        }
    }
}

/** Reuse an empty slot, then the oldest impulse. Off-screen UVs remain physically off screen. */
export function addWebContact(contacts: WebContact[], position: { x: number; y: number }, strength = 1): number {
    if (!Number.isFinite(position.x) || !Number.isFinite(position.y)
        || !Number.isFinite(strength) || strength <= 0) return -1;
    const count = Math.min(contacts.length, WEB_CONTACT_COUNT);
    if (count === 0) return -1;
    let index = 0;
    for (let candidate = 0; candidate < count; candidate++) {
        const contact = contacts[candidate];
        if (contact.z <= 0 || contact.w < 0 || !Number.isFinite(contact.w)
            || contact.w >= WEB_CONTACT_LIFETIME) {
            index = candidate;
            break;
        }
        if (contact.w > contacts[index].w) index = candidate;
    }
    const contact = contacts[index];
    contact.x = position.x;
    contact.y = position.y;
    contact.z = Math.min(1, strength);
    contact.w = 0;
    return index;
}
