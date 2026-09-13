/**
 * e2e Store Bridge（仅开发环境，DEV 门控）
 *
 * 背景：vite dev 的 HMR 会给模块追加 `?t=<timestamp>`。应用在热更后通过
 * `/src/store/x.ts?t=...` 拿到"新实例"，而 e2e 里 `import('/src/store/x.ts')`
 * （无 ?t=）会再生成一个模块实例——两个 zustand store 互不相通，
 * 导致测试注入的状态进不了应用（TC-APOS2-036 即此问题）。
 *
 * 做法：把应用正在使用的 zustand store 单例挂到 `window.__e2eStores`，
 * 测试经 page.evaluate 直接读写同一实例，与模块 URL 是否带 ?t= 无关。
 * 仅开发环境生效；生产构建 import.meta.env.DEV=false，零影响。
 */
import { useActivityStore } from '@/store/activityStore';
import { useFeatureFlagStore } from '@/store/featureFlagStore';
import { useSessionStore } from '@/store/sessionStore';

declare global {
    interface Window {
        __e2eStores?: {
            activityStore: typeof useActivityStore;
            featureFlagStore: typeof useFeatureFlagStore;
            sessionStore: typeof useSessionStore;
        };
    }
}

function installE2eStoreBridge(): void {
    window.__e2eStores = {
        activityStore: useActivityStore,
        featureFlagStore: useFeatureFlagStore,
        sessionStore: useSessionStore,
    };
}

if (import.meta.env.DEV) {
    installE2eStoreBridge();
    // store 模块热更后重挂，保证桥始终指向当前实例
    if (import.meta.hot) {
        import.meta.hot.accept(
            ['@/store/activityStore', '@/store/featureFlagStore', '@/store/sessionStore'],
            installE2eStoreBridge,
        );
    }
}
