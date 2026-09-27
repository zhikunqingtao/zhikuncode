import { useEffect } from 'react';
import { useSkillStore } from '@/store/skillStore';

/** One subscription per app, shared by commands, details and the management page. */
export function useSkillSync() {
  const loadSkills = useSkillStore(state => state.loadSkills);
  useEffect(() => {
    void loadSkills();
    const refresh = () => {
      if (document.visibilityState === 'visible') void loadSkills({ background: true });
    };
    const timer = window.setInterval(refresh, 5000);
    window.addEventListener('focus', refresh);
    document.addEventListener('visibilitychange', refresh);
    return () => {
      window.clearInterval(timer);
      window.removeEventListener('focus', refresh);
      document.removeEventListener('visibilitychange', refresh);
    };
  }, [loadSkills]);
}
