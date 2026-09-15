import type { ExternalResourceResult } from '@/types';

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
}

export function structuredResultSchema(
    metadata: Record<string, unknown> | undefined,
): string | null {
    if (!isRecord(metadata) || !isRecord(metadata.structuredResult)) return null;
    return typeof metadata.structuredResult.schema === 'string'
        ? metadata.structuredResult.schema
        : null;
}

/** Parse the allowlisted external-resource/v1 contract without trusting arbitrary tool metadata. */
export function parseExternalResourceResult(
    metadata: Record<string, unknown> | undefined,
): ExternalResourceResult | null {
    if (!isRecord(metadata)) return null;
    const raw = metadata.structuredResult;
    if (!isRecord(raw)
        || raw.schema !== 'external-resource/v1'
        || raw.kind !== 'download'
        || typeof raw.provider !== 'string'
        || typeof raw.url !== 'string'
        || typeof raw.label !== 'string'
        || typeof raw.size !== 'number'
        || typeof raw.sha256 !== 'string'
        || typeof raw.objectKey !== 'string'
        || typeof raw.mimeType !== 'string'
        || typeof raw.permanentlyPublic !== 'boolean'
        || typeof raw.downloadExpected !== 'boolean') {
        return null;
    }
    if (!raw.provider.trim() || !raw.label.trim() || !raw.objectKey.trim()
        || !raw.mimeType.trim() || !Number.isSafeInteger(raw.size) || raw.size < 0
        || !/^[a-f0-9]{64}$/i.test(raw.sha256)) {
        return null;
    }

    let parsed: URL;
    try {
        parsed = new URL(raw.url);
    } catch {
        return null;
    }
    if (parsed.protocol !== 'https:' || parsed.username || parsed.password || parsed.hash) {
        return null;
    }
    try {
        if (decodeURIComponent(parsed.pathname.replace(/^\//, '')) !== raw.objectKey) return null;
    } catch {
        return null;
    }

    return {
        schema: 'external-resource/v1',
        kind: 'download',
        provider: raw.provider,
        artifactId: typeof raw.artifactId === 'string' ? raw.artifactId : undefined,
        url: raw.url,
        label: raw.label,
        size: raw.size,
        sha256: raw.sha256,
        objectKey: raw.objectKey,
        mimeType: raw.mimeType,
        permanentlyPublic: raw.permanentlyPublic,
        downloadExpected: raw.downloadExpected,
    };
}

/** Only server-issued HTTPS Meoo site and project links can be rendered. */
export function parseSitePublicationResult(metadata: Record<string, unknown> | undefined): import('@/types').SitePublicationResult | null {
    if (!isRecord(metadata) || !isRecord(metadata.structuredResult)) return null;
    const r = metadata.structuredResult;
    if (r.schema !== 'site-publication/v1' || r.provider !== 'meoo'
        || typeof r.publicationId !== 'string' || !r.publicationId
        || typeof r.label !== 'string' || !r.label
        || !['static', 'image'].includes(String(r.runtime))
        || !['public_verified', 'deployed_unverified', 'failed', 'unknown'].includes(String(r.state))) return null;
    for (const key of ['projectId', 'projectUrl', 'version', 'url', 'errorCode', 'guidance']) {
        if (r[key] !== undefined && typeof r[key] !== 'string') return null;
    }
    if (r.projectId !== undefined && !/^[A-Za-z0-9_-]{1,128}$/.test(String(r.projectId))) return null;
    if (r.projectUrl !== undefined && (!r.projectId || r.projectUrl !== `https://meoo.com/chat/${r.projectId}`)) return null;
    if (r.url !== undefined) {
        try {
            const u = new URL(String(r.url));
            if (u.protocol !== 'https:' || !/^[a-z0-9-]+\.meoo\.(?:fun|pub)$/i.test(u.hostname)
                || u.port || u.username || u.password || u.search || u.hash || u.pathname !== '/') return null;
        } catch { return null; }
    }
    if (['public_verified', 'deployed_unverified'].includes(String(r.state)) && (!r.url || !r.projectId || !r.version)) return null;
    return {
        schema: 'site-publication/v1', provider: 'meoo', publicationId: r.publicationId, label: r.label,
        runtime: r.runtime as 'static' | 'image', state: r.state as import('@/types').SitePublicationResult['state'],
        projectId: r.projectId as string | undefined, projectUrl: r.projectUrl as string | undefined,
        version: r.version as string | undefined, url: r.url as string | undefined, errorCode: r.errorCode as string | undefined, guidance: r.guidance as string | undefined,
    };
}
