import { motion, useReducedMotion } from 'framer-motion';
import { useConfigStore } from '@/store/configStore';

/** One shared-layout highlight per segmented group; labels remain stationary. */
export function GlassSelection({ id }: { id: string }) {
    const enabled = useConfigStore(s => s.theme.mode === 'glass');
    const reducedMotion = useReducedMotion();
    if (!enabled) return null;
    return <motion.span aria-hidden="true" className="glass-selection" layoutId={id}
        transition={reducedMotion ? { duration: 0 } : { type: 'spring', stiffness: 480, damping: 38 }} />;
}
