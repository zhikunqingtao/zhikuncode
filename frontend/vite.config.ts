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
            host: true,
            proxy: {
                '/api': {
                    target: env.VITE_API_URL || 'http://localhost:8080',
                    changeOrigin: true,
                    secure: false,
                },
                '/ws': {
                    target: env.VITE_API_URL || 'http://localhost:8080',
                    changeOrigin: true,
                    secure: false,
                },
                '/ws/**': {
                    target: env.VITE_API_URL || 'http://localhost:8080',
                    changeOrigin: true,
                    secure: false,
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
            include: ['src/**/*.{test,spec}.{ts,tsx}'],
        },
    };
});
