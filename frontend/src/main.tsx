/// <reference types="vite/client" />
import { enableMapSet } from 'immer';
enableMapSet();

import React from 'react';
import ReactDOM from 'react-dom/client';
import App from './App.tsx';
import { ThemeProvider } from '@/components/theme/ThemeProvider';
import './styles/globals.css';

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
