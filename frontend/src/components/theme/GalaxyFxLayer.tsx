import { useEffect, useRef } from 'react';
import { useConfigStore } from '@/store/configStore';

/** The skin cannot capture input, authorize a tool, or write conversation state. */
export function GalaxyFxLayer() {
    const enabled = useConfigStore(state => state.theme.mode === 'galaxy');
    const host = useRef<HTMLDivElement>(null);
    useEffect(() => {
        if (!enabled || !host.current) return;
        const element = host.current;
        let cancelled = false;
        let dispose: (() => void) | undefined;
        void import('./galaxy/controller').then(({ createGalaxyController }) => {
            if (cancelled) return;
            try { dispose = createGalaxyController(element); }
            catch { element.dataset.state = 'fallback'; }
        }).catch(() => { if (!cancelled) element.dataset.state = 'fallback'; });
        return () => { cancelled = true; dispose?.(); };
    }, [enabled]);
    return enabled ? <div ref={host} className="galaxy-fx-layer" data-testid="galaxy-fx-layer" aria-hidden="true" /> : null;
}
