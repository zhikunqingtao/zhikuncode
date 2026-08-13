// @vitest-environment node

import { describe, expect, it } from 'vitest';
import { readFile } from 'node:fs/promises';
import viteConfig from '../vite.config';

describe('Local development service security', () => {
    it('binds the localhost-trusting proxy to loopback only', async () => {
        const config = await viteConfig({
            command: 'serve',
            mode: 'development',
            isSsrBuild: false,
            isPreview: false,
        });

        expect(config.server?.host).toBe('127.0.0.1');
        const proxy = config.server?.proxy;
        expect(proxy?.['/api/files/search']).toMatchObject({
            target: 'http://localhost:8080',
        });
        for (const route of [
            '/api/git',
            '/api/files',
            '/api/code-quality',
            '/api/analysis',
        ]) {
            expect(proxy?.[route]).toMatchObject({
                target: 'http://127.0.0.1:8000',
            });
        }
    });

    it('binds the internal Python service to loopback only', async () => {
        const startScript = await readFile(
            new URL('../../start.sh', import.meta.url),
            'utf8',
        );

        expect(startScript).toMatch(
            /uvicorn src\.main:app \\\s+--host 127\.0\.0\.1/,
        );
        expect(startScript).not.toContain('--host 0.0.0.0');
    });
});
