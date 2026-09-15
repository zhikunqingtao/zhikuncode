import { describe, expect, it } from 'vitest';
import { parseExternalResourceResult } from './structuredToolResult';

function metadata(overrides: Record<string, unknown> = {}) {
    const objectKey = 'zhikuncode-artifacts/session/artifact/report.html';
    return {
        structuredResult: {
            schema: 'external-resource/v1',
            kind: 'download',
            provider: 'oss',
            artifactId: 'artifact-1',
            url: `https://zhikunshare.oss-cn-beijing.aliyuncs.com/${objectKey}`,
            label: 'report.html',
            size: 2048,
            sha256: 'b'.repeat(64),
            objectKey,
            mimeType: 'text/html',
            permanentlyPublic: true,
            downloadExpected: true,
            ...overrides,
        },
    };
}

describe('parseExternalResourceResult', () => {
    it('preserves the authoritative URL byte-for-byte', () => {
        const url = 'https://zhikunshare.oss-cn-beijing.aliyuncs.com/a%20b/report.html?version=1';
        const result = parseExternalResourceResult(metadata({
            url,
            objectKey: 'a b/report.html',
        }));

        expect(result?.url).toBe(url);
    });

    it('rejects non-HTTPS and object-key mismatches', () => {
        expect(parseExternalResourceResult(metadata({
            url: 'http://zhikunshare.oss-cn-beijing.aliyuncs.com/a',
            objectKey: 'a',
        }))).toBeNull();
        expect(parseExternalResourceResult(metadata({ objectKey: 'different/file.html' }))).toBeNull();
    });
});

import { parseSitePublicationResult } from './structuredToolResult';
const site = (overrides: Record<string, unknown> = {}) => ({ structuredResult: {
    schema: 'site-publication/v1', provider: 'meoo', publicationId: 'id', label: 'Site',
    runtime: 'image', state: 'public_verified', projectId: 'demo', projectUrl: 'https://meoo.com/chat/demo',
    version: '1', url: 'https://demo.meoo.fun', ...overrides,
} });
describe('parseSitePublicationResult', () => {
    it('accepts official meoo.pub responses after serialization and rejects lookalike domains', () => {
        expect(parseSitePublicationResult(JSON.parse(JSON.stringify(site({url:'https://demo.meoo.pub'}))))?.url).toBe('https://demo.meoo.pub');
        expect(parseSitePublicationResult(site({url:'https://demo.meoo.pub.evil.test'}))).toBeNull();
    });
    it('round-trips persisted publication metadata', () => {
        expect(parseSitePublicationResult(JSON.parse(JSON.stringify(site())))?.url).toBe('https://demo.meoo.fun');
    });
    it.each(['http://demo.meoo.fun', 'https://127.0.0.1', 'https://demo.meoo.fun.evil.test', 'https://user@demo.meoo.fun', 'javascript:alert(1)', 'https://demo.meoo.fun/?token=secret'])('rejects unsafe URL %s', url => {
        expect(parseSitePublicationResult(site({ url }))).toBeNull();
    });
    it('rejects fabricated project URLs or missing successful URL', () => {
        expect(parseSitePublicationResult(site({ projectUrl: 'https://evil.test' }))).toBeNull();
        expect(parseSitePublicationResult(site({ url: undefined }))).toBeNull();
        expect(parseSitePublicationResult(site({ state: 'unknown', url: undefined }))).not.toBeNull();
    });
});
