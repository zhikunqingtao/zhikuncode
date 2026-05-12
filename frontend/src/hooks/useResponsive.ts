/**
 * useResponsive — 响应式断点 Hook
 * 用于组件内条件渲染（PC inline expand vs Mobile Bottom Sheet）
 */

import { useMediaQuery } from './useMediaQuery';

export function useResponsive() {
  const isDesktop = useMediaQuery('(min-width: 1024px)');
  const isTablet = useMediaQuery('(min-width: 768px) and (max-width: 1023px)');
  const isMobile = useMediaQuery('(max-width: 767px)');

  return { isDesktop, isTablet, isMobile };
}
