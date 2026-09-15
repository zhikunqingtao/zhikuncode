/**
 * hooks/ 统一导出
 */
export {
    useMediaQuery,
    useIsMobile,
    useIsTablet,
    useIsDesktop,
    usePrefersReducedMotion,
} from './useMediaQuery';

export {
    useVirtualKeyboard,
    useScrollInputIntoView,
    useKeyboardScrollCompensation,
    useNetworkAwareConfig,
} from './useVirtualKeyboard';

export type { VirtualKeyboardState } from './useVirtualKeyboard';
