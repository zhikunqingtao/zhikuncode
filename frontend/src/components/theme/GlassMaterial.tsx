import { useEffect, useId, useRef, useState, type CSSProperties } from 'react';
import { useConfigStore } from '@/store/configStore';
import { useMediaQuery } from '@/hooks/useMediaQuery';
import { lensMap } from './glassOptics';

export type GlassKind = 'navigation' | 'control' | 'overlay';

/** Decorative layers only: the host keeps its semantics, focus and event handlers. */
export function GlassMaterial({ kind = 'navigation', interactive = false }: {
    kind?: GlassKind;
    interactive?: boolean;
}) {
    const enabled = useConfigStore(s => s.theme.mode === 'glass');
    const reducedTransparency = useMediaQuery('(prefers-reduced-transparency: reduce)');
    const highContrast = useMediaQuery('(prefers-contrast: more)');
    const forcedColors = useMediaQuery('(forced-colors: active)');
    const reducedMotion = useMediaQuery('(prefers-reduced-motion: reduce)');
    const ref = useRef<HTMLSpanElement>(null);
    const id = `glass-${useId().replace(/:/g, '')}`;
    const [map, setMap] = useState('');
    const [size, setSize] = useState({ width: 1, height: 1 });
    const memory = (navigator as Navigator & { deviceMemory?: number }).deviceMemory;
    const reduced = reducedTransparency || highContrast || forcedColors || (memory !== undefined && memory < 4);
    // Chromium's SVG backdrop path is verified by the optical browser regression.
    // Other engines retain the complete CSS material without the displacement layer.
    const chromium = /Chrome\//.test(navigator.userAgent) && !/EdgiOS|CriOS/.test(navigator.userAgent);
    const refract = enabled && kind !== 'navigation' && !reduced && chromium;

    useEffect(() => {
        const host = ref.current?.parentElement;
        if (!enabled || !host || !refract) { setMap(''); return; }
        let frame = 0;
        const update = () => {
            cancelAnimationFrame(frame);
            frame = requestAnimationFrame(() => {
                const { width, height } = host.getBoundingClientRect();
                const radius = parseFloat(getComputedStyle(host).borderTopLeftRadius) || 0;
                if (width > 0 && height > 0) {
                    const nextSize = { width: Math.round(width), height: Math.round(height) };
                    setSize(nextSize);
                    setMap(lensMap(nextSize.width, nextSize.height, radius));
                }
            });
        };
        const observer = new ResizeObserver(update);
        observer.observe(host);
        update();
        return () => { observer.disconnect(); cancelAnimationFrame(frame); };
    }, [enabled, refract]);

    useEffect(() => {
        const material = ref.current;
        const host = material?.parentElement;
        if (!material || !host || !enabled || !interactive || reduced || reducedMotion) return;
        let frame = 0;
        const move = (event: PointerEvent) => {
            if (event.pointerType === 'touch') return;
            const rect = host.getBoundingClientRect();
            cancelAnimationFrame(frame);
            frame = requestAnimationFrame(() => {
                material.style.setProperty('--glass-pointer-x', `${event.clientX - rect.left}px`);
                material.style.setProperty('--glass-pointer-y', `${event.clientY - rect.top}px`);
            });
        };
        const leave = () => {
            cancelAnimationFrame(frame);
            material.style.removeProperty('--glass-pointer-x');
            material.style.removeProperty('--glass-pointer-y');
        };
        host.addEventListener('pointermove', move);
        host.addEventListener('pointerleave', leave);
        return () => { leave(); host.removeEventListener('pointermove', move); host.removeEventListener('pointerleave', leave); };
    }, [enabled, interactive, reduced, reducedMotion]);

    if (!enabled) return null;
    return (
        <span ref={ref} className="liquid-glass-material" data-kind={kind} data-reduced={reduced || undefined}
            data-refracting={Boolean(refract && map)} aria-hidden="true">
            {refract && map && <svg width="0" height="0" className="glass-filter-defs" focusable="false">
                <defs><filter id={id} filterUnits="userSpaceOnUse" primitiveUnits="userSpaceOnUse" x="0" y="0" width={size.width} height={size.height} colorInterpolationFilters="sRGB">
                    <feImage href={map} x="0" y="0" width={size.width} height={size.height} preserveAspectRatio="none" result="normals" />
                    {/* Sample inward at the rim so rounded corners never pull transparent out-of-bounds pixels. */}
                    <feDisplacementMap in="SourceGraphic" in2="normals" scale={kind === 'control' ? -14 : -22}
                        xChannelSelector="R" yChannelSelector="G" />
                </filter></defs>
            </svg>}
            {refract && map && <span className="glass-lens" style={{ backdropFilter: `url(#${id})`, WebkitBackdropFilter: `url(#${id})` } as CSSProperties} />}
            <span className="glass-scatter" />
            <span className="glass-light" />
        </span>
    );
}
