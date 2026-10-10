import { useEffect, useRef } from 'react';
import { useConfigStore } from '@/store/configStore';

/** The skin owns only decoration. It never captures input or modifies conversation state. */
export function SpiderFxLayer() {
    const enabled = useConfigStore(state => state.theme.mode === 'spider');
    const host = useRef<HTMLDivElement>(null);
    useEffect(() => {
        if (!enabled || !host.current) return;
        const element = host.current;
        let cancelled = false;
        let dispose: (() => void) | undefined;
        void import('./spider/renderer').then(({ createSpiderScene }) => {
            if (cancelled) return;
            try { dispose = createSpiderScene(element); }
            catch { element.dataset.state = 'fallback'; }
        }).catch(() => { if (!cancelled) element.dataset.state = 'fallback'; });
        return () => { cancelled = true; dispose?.(); };
    }, [enabled]);
    if (!enabled) return null;
    return <div ref={host} className="spider-fx-layer" data-testid="spider-fx-layer" aria-hidden="true">
        <div className="spider-ambient-halo" />
    </div>;
}
