import { useEffect, useRef, type RefObject } from 'react';

function isVisible(element: HTMLElement) {
  for (let node: HTMLElement | null = element; node; node = node.parentElement) {
    const style = getComputedStyle(node);
    if (node.hidden || node.hasAttribute('inert') || style.display === 'none' || style.visibility === 'hidden') return false;
  }
  return true;
}

let locks = 0;
let originalOverflow = '';
export function lockModalScroll() {
  if (locks++ === 0) originalOverflow = document.body.style.overflow;
  document.body.style.overflow = 'hidden';
}
export function unlockModalScroll() {
  if (locks > 0 && --locks === 0) document.body.style.overflow = originalOverflow;
}
export function isTopModal(panel: HTMLElement | null): boolean {
  if (!panel) return false;
  const dialogs = [...document.querySelectorAll<HTMLElement>('[role="dialog"][aria-modal="true"],[role="alertdialog"][aria-modal="true"]')]
    .filter(isVisible);
  const rank = (el: HTMLElement) => {
    let z = 0;
    for (let node: HTMLElement | null = el; node; node = node.parentElement) {
      z = Math.max(z, Number.parseInt(getComputedStyle(node).zIndex, 10) || 0);
    }
    return z;
  };
  dialogs.sort((a, b) => rank(a) - rank(b));
  const top = dialogs.at(-1);
  return !top || top === panel || top.contains(panel);
}

export function useModalBehavior(open: boolean, panel: RefObject<HTMLElement>, onClose: () => void, handleEscape = true) {
  const close = useRef(onClose);
  close.current = onClose;
  useEffect(() => {
    if (!open) return;
    const previous = document.activeElement as HTMLElement | null;
    lockModalScroll();
    const raf = requestAnimationFrame(() => panel.current?.focus());
    const handler = (event: KeyboardEvent) => {
      if (!isTopModal(panel.current)) return;
      if (handleEscape && event.key === 'Escape') { event.preventDefault(); event.stopImmediatePropagation(); close.current(); }
      if (event.key !== 'Tab' || !panel.current) return;
      const elements = [...panel.current.querySelectorAll<HTMLElement>('button:not([disabled]),a[href],input:not([disabled]),select:not([disabled]),textarea:not([disabled]),[tabindex="0"]')]
        .filter(isVisible);
      const first = elements[0], last = elements.at(-1);
      if (!first) { event.preventDefault(); panel.current.focus(); return; }
      if (event.shiftKey && (document.activeElement === first || !elements.includes(document.activeElement as HTMLElement))) {
        event.preventDefault(); last?.focus();
      } else if (!event.shiftKey && (document.activeElement === last || !panel.current.contains(document.activeElement))) {
        event.preventDefault(); first.focus();
      }
    };
    document.addEventListener('keydown', handler);
    return () => {
      cancelAnimationFrame(raf);
      document.removeEventListener('keydown', handler);
      unlockModalScroll();
      if (previous?.isConnected) previous.focus();
    };
  }, [open, panel, handleEscape]);
}
