import { create } from 'zustand';
import { immer } from 'zustand/middleware/immer';
import { subscribeWithSelector } from 'zustand/middleware';
import { APOS_FLAG_DEFAULTS, APOS_FLAG_DEPENDENCIES, type APOSFeatureFlags } from '@/types/apos';

interface FeatureFlagStoreState {
  flags: APOSFeatureFlags;

  setFlag: (key: keyof APOSFeatureFlags, value: boolean) => void;
  toggleFlag: (key: keyof APOSFeatureFlags) => void;
  resetToDefaults: () => void;
  getMissingDependencies: (key: keyof APOSFeatureFlags) => string[];
  getCascadeDisableTargets: (key: keyof APOSFeatureFlags) => string[];
}

/** Initialize flags from VITE_APOS_* environment variables */
function initializeFlags(): APOSFeatureFlags {
  const flags = { ...APOS_FLAG_DEFAULTS };
  try {
    const envKeys = Object.keys(flags) as (keyof APOSFeatureFlags)[];
    const env = (import.meta as unknown as { env?: Record<string, string | undefined> }).env;
    if (env) {
      envKeys.forEach(key => {
        const envValue = env[`VITE_${key}`];
        if (envValue !== undefined) {
          flags[key] = envValue === 'true';
        }
      });
    }
  } catch (err) {
    console.warn('[FeatureFlag] Failed to read env variables, using defaults:', err);
  }
  return flags;
}

export const useFeatureFlagStore = create<FeatureFlagStoreState>()(
  subscribeWithSelector(immer((set, get) => ({
    flags: initializeFlags(),

    setFlag: (key, value) => set(d => {
      if (value) {
        // Check dependencies before enabling
        const missing = get().getMissingDependencies(key);
        if (missing.length > 0) {
          console.warn(`Cannot enable ${key}: missing dependencies ${missing.join(', ')}`);
          return;
        }
      } else {
        // Cascade disable dependents
        const cascade = get().getCascadeDisableTargets(key);
        cascade.forEach(dep => { (d.flags as unknown as Record<string, boolean>)[dep] = false; });
      }
      d.flags[key] = value;
    }),

    toggleFlag: (key) => {
      const current = get().flags[key];
      get().setFlag(key, !current);
    },

    resetToDefaults: () => set(d => { d.flags = { ...APOS_FLAG_DEFAULTS }; }),

    getMissingDependencies: (key) => {
      const deps = APOS_FLAG_DEPENDENCIES[key] || [];
      const flags = get().flags;
      return deps.filter(dep => !(flags as unknown as Record<string, boolean>)[dep]);
    },

    getCascadeDisableTargets: (key) => {
      const targets: string[] = [];
      const flagKeys = Object.keys(APOS_FLAG_DEPENDENCIES);
      flagKeys.forEach(flagKey => {
        const deps = APOS_FLAG_DEPENDENCIES[flagKey];
        if (deps?.includes(key)) {
          targets.push(flagKey);
          // Recursive cascade
          const subTargets = get().getCascadeDisableTargets(flagKey as keyof APOSFeatureFlags);
          targets.push(...subTargets);
        }
      });
      return [...new Set(targets)];
    },
  })))
);
