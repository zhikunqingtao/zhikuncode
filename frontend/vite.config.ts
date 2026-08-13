/// <reference types="vitest" />
import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'path';

export default defineConfig(({ mode }) => {
    const env = loadEnv(mode, process.cwd(), '');

    return {
        plugins: [react()],
        
        define: {
            global: 'globalThis',
        },

        resolve: {
            alias: {
                '@': path.resolve(__dirname, './src'),
                '@components': path.resolve(__dirname, './src/components'),
                '@store': path.resolve(__dirname, './src/store'),
                '@api': path.resolve(__dirname, './src/api'),
                '@pages': path.resolve(__dirname, './src/pages'),
                '@hooks': path.resolve(__dirname, './src/hooks'),
                '@utils': path.resolve(__dirname, './src/utils'),
                '@types': path.resolve(__dirname, './src/types'),
            },
        },

        server: {
            port: 5173,
            strictPort: true,
            // The dev proxy targets localhost services whose security model
            // trusts loopback peers. Exposing this proxy would make remote
            // requests indistinguishable from local ones at the backend.
            host: '127.0.0.1',
            proxy: {
                // Session-aware project search is implemented by the Java
                // backend. Keep this more specific route before the Python
                // file-processing prefix below.
                '/api/files/search': {
                    target: env.VITE_API_URL || 'http://localhost:8080',
                    changeOrigin: true,
                    secure: false,
                },
                '/api/sessions': {
                    target: env.VITE_API_URL || 'http://localhost:8080',
                    changeOrigin: true,
                    secure: false,
                },
                '/api/workbench': {
                    target: env.VITE_API_URL || 'http://localhost:8080',
                    changeOrigin: true,
                    secure: false,
                },
                '/api/git': {
                    target: env.VITE_PYTHON_URL || 'http://127.0.0.1:8000',
                    changeOrigin: true,
                    secure: false,
                },
                '/api/files': {
                    target: env.VITE_PYTHON_URL || 'http://127.0.0.1:8000',
                    changeOrigin: true,
                    secure: false,
                },
                '/api/code-quality': {
                    target: env.VITE_PYTHON_URL || 'http://127.0.0.1:8000',
                    changeOrigin: true,
                    secure: false,
                },
                '/api/analysis': {
                    target: env.VITE_PYTHON_URL || 'http://127.0.0.1:8000',
                    changeOrigin: true,
                    secure: false,
                },
                '/api': {
                    target: env.VITE_API_URL || 'http://localhost:8080',
                    changeOrigin: true,
                    secure: false,
                },
                '/ws': {
                    target: env.VITE_API_URL || 'http://localhost:8080',
                    changeOrigin: true,
                    secure: false,
                    ws: true,  // 启用 WebSocket 升级代理，SockJS 原生 WS 传输需要
                },
            },
        },

        build: {
            outDir: 'dist',
            sourcemap: mode === 'development',
            rollupOptions: {
                output: {
                    manualChunks: {
                        'react-vendor': ['react', 'react-dom', 'react-router-dom'],
                        'editor': ['monaco-editor'],
                        'terminal': ['@xterm/xterm', '@xterm/addon-fit'],
                        'markdown': ['react-markdown', 'react-syntax-highlighter'],
                        'ui': ['zustand', 'immer', 'react-virtuoso'],
                    },
                },
            },
            chunkSizeWarningLimit: 1000,
        },

        envPrefix: 'VITE_',

        test: {
            globals: true,
            environment: 'jsdom',
            setupFiles: './src/test-setup.ts',
            include: [
                'src/**/*.{test,spec}.{ts,tsx}',
                'tests/**/*.{test,spec}.{ts,tsx}',
            ],
        },
    };
});
