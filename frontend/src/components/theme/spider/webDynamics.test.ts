import { describe, expect, it } from 'vitest';
import { addWebContact, advanceWebContacts, contactEnvelope, WEB_CONTACT_LIFETIME, type WebContact } from './webDynamics';

const slot = (age = -1, strength = 0): WebContact => ({ x: 0, y: 0, z: strength, w: age });

describe('web contact dynamics', () => {
    it('decays a local impulse monotonically to zero without a late brightness jump', () => {
        let previous = contactEnvelope(0);
        expect(previous).toBe(1);
        for (let age = .02; age <= WEB_CONTACT_LIFETIME; age += .02) {
            const energy = contactEnvelope(age);
            expect(energy).toBeGreaterThanOrEqual(0);
            expect(energy).toBeLessThanOrEqual(previous);
            previous = energy;
        }
        expect(contactEnvelope(WEB_CONTACT_LIFETIME - .001)).toBeLessThan(.00001);
        expect(contactEnvelope(WEB_CONTACT_LIFETIME)).toBe(0);
        expect(contactEnvelope(.5, .4)).toBeCloseTo(contactEnvelope(.5) * .4);
    });

    it('ignores disabled or invalid impulses and bounds input strength', () => {
        expect(contactEnvelope(-1)).toBe(0);
        expect(contactEnvelope(NaN)).toBe(0);
        expect(contactEnvelope(0, Infinity)).toBe(0);
        expect(contactEnvelope(0, -1)).toBe(0);
        expect(contactEnvelope(0, 5)).toBe(1);
    });

    it('ages vectors in place, expires after a pause, and keeps unused slots inactive', () => {
        const contacts = [slot(0, 1), slot(1.5, .5), slot()];
        const original = contacts[0];
        advanceWebContacts(contacts, .2);
        expect(contacts[0]).toBe(original);
        expect(contacts.map(contact => contact.w)).toEqual([.2, 1.7, -1]);
        advanceWebContacts(contacts, 10);
        expect(contacts.every(contact => contact.z === 0 && contact.w === -1)).toBe(true);
        advanceWebContacts(contacts, NaN);
        advanceWebContacts(contacts, -1);
        expect(contacts.every(contact => contact.w === -1)).toBe(true);
    });

    it('reuses an empty slot before evicting the oldest footfall and preserves offscreen positions', () => {
        const contacts = [slot(.4, 1), slot(), slot(.8, .8)];
        const original = contacts[1];
        expect(addWebContact(contacts, { x: -.02, y: .3 }, .6)).toBe(1);
        expect(contacts[1]).toBe(original);
        expect(contacts[1]).toEqual({ x: -.02, y: .3, z: .6, w: 0 });
        expect(addWebContact(contacts, { x: .5, y: .6 }, 2)).toBe(2);
        expect(contacts[2]).toEqual({ x: .5, y: .6, z: 1, w: 0 });
        expect(contacts[0].w).toBe(.4);
    });

    it('does not overwrite live contacts for invalid or zero-strength signals', () => {
        const contacts = [slot(.4, 1)];
        const before = { ...contacts[0] };
        expect(addWebContact([], { x: 0, y: 0 })).toBe(-1);
        expect(addWebContact(contacts, { x: NaN, y: 0 })).toBe(-1);
        expect(addWebContact(contacts, { x: 0, y: 0 }, 0)).toBe(-1);
        expect(contacts[0]).toEqual(before);
    });
});
