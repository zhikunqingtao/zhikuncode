import { describe, expect, it } from 'vitest';
import { getPermissionModeColor, getPermissionModeLabel } from '@/components/layout/StatusBar';
import { PERMISSION_MODES } from '@/types';

describe('StatusBar permission mode presentation', () => {
    it('defines a non-empty label and color for every supported mode', () => {
        for (const mode of PERMISSION_MODES) {
            expect(getPermissionModeLabel(mode)).not.toBe('');
            expect(getPermissionModeColor(mode)).toMatch(/^text-/);
        }
    });

    it('presents AUTO_APPROVE as a warning-style full access mode', () => {
        expect(getPermissionModeLabel('auto_approve')).toBe('完全访问权限');
        expect(getPermissionModeColor('auto_approve')).toBe('text-orange-500');
    });
});
