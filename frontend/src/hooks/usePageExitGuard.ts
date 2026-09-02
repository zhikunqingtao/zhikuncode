import { useEffect } from 'react';

/**
 * Ask the browser to confirm hard page exits such as closing, reloading, or
 * navigating away. Browsers intentionally control the dialog copy.
 */
export function usePageExitGuard() {
  useEffect(() => {
    const handleBeforeUnload = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      // Required by Chrome/Safari and retained for older browser compatibility.
      event.returnValue = true;
    };

    window.addEventListener('beforeunload', handleBeforeUnload);
    return () => window.removeEventListener('beforeunload', handleBeforeUnload);
  }, []);
}
