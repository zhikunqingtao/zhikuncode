/**
 * useResponsive — 响应式断点 Hook（§8.1 唯一标准口径）
 * <768px mobile / 768–1023px compact / ≥1024px desktop
 * 用于组件内条件渲染（PC inline expand vs Mobile Bottom Sheet）
 */

import { useEffect, useState } from 'react';
import { useMediaQuery } from './useMediaQuery';

export function useResponsive() {
  const isDesktop = useMediaQuery('(min-width: 1024px)');
  const isTablet = useMediaQuery('(min-width: 768px) and (max-width: 1023px)');
  const isMobile = useMediaQuery('(max-width: 767px)');

  return { isDesktop, isTablet, isMobile };
}

/**
 * useViewportWidth — 视口像素宽度 Hook（§8.1 职责分离）
 * 断点判定走 useResponsive；需要像素值的场景（如拖拽上限计算）使用本 Hook。
 * 实现：一次读取 + resize 监听 + rAF 节流。
 */
export function useViewportWidth(): number {
  const [width, setWidth] = useState(() =>
    typeof window === 'undefined' ? 0 : window.innerWidth,
  );

  useEffect(() => {
    let rafId = 0;
    const handleResize = () => {
      cancelAnimationFrame(rafId);
      rafId = requestAnimationFrame(() => setWidth(window.innerWidth));
    };
    handleResize();
    window.addEventListener('resize', handleResize);
    return () => {
      cancelAnimationFrame(rafId);
      window.removeEventListener('resize', handleResize);
    };
  }, []);

  return width;
}
