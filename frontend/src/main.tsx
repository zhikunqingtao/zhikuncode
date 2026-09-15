/// <reference types="vite/client" />
import { enableMapSet } from 'immer';
enableMapSet();

import React from 'react';
import ReactDOM from 'react-dom/client';
import App from './App.tsx';
import { ThemeProvider } from '@/components/theme/ThemeProvider';
import './styles/globals.css';
import './styles/interface-refinements.css';
/* e2e Store Bridge：DEV 门控，挂载 zustand 单例到 window.__e2eStores（见 src/dev/e2eStoreBridge.ts） */
import '@/dev/e2eStoreBridge';

/* P1a /design 画廊：仅 DEV 且路径命中时懒加载，生产构建静态消除（§6.4 零架构侵入） */
const isDesignGallery = import.meta.env.DEV && window.location.pathname === '/design';
const DesignGallery = isDesignGallery
    ? React.lazy(() => import('./design/DesignGallery'))
    : null;

ReactDOM.createRoot(document.getElementById('root')!).render(
    <React.StrictMode>
        {DesignGallery ? (
            <React.Suspense fallback={null}>
                <DesignGallery />
            </React.Suspense>
        ) : (
            <ThemeProvider>
                <App />
            </ThemeProvider>
        )}
    </React.StrictMode>,
);
