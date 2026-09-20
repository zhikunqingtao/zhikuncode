import type { ExternalResourceResult, Message, SitePublicationResult, ToolCallState, ToolResult } from '@/types';
import { parseExternalResourceResult, parseSitePublicationResult } from '@/utils/structuredToolResult';

export type TurnPublication = { toolUseId: string } & (
    { kind: 'download'; resource: ExternalResourceResult }
    | { kind: 'site'; publication: SitePublicationResult }
);

/** Derive cards from server-issued results; never infer links from assistant text. */
export function projectTurnPublications(
    messages: Message[],
    live?: Map<string, ToolCallState>,
    includeUncommittedLive = false,
): TurnPublication[] {
    const results = new Map<string, ToolResult | undefined>();
    for (const message of messages) {
        if (message.type !== 'assistant' && message.type !== 'user') continue;
        for (const block of message.content) {
            if (block.type === 'tool_use' && message.type === 'assistant') {
                results.set(block.toolUseId, block.result ?? results.get(block.toolUseId));
            } else if (block.type === 'tool_result') results.set(block.toolUseId, block);
        }
    }
    for (const [id, tc] of live ?? []) {
        // Uncommitted calls belong only to the turn containing the streaming message.
        if (!results.has(id) && !includeUncommittedLive) continue;
        if (tc.status === 'running' || tc.status === 'pending' || tc.status === 'permission_needed') {
            results.delete(id);
        } else if (tc.result) results.set(id, tc.result);
    }
    const cards: TurnPublication[] = [];
    for (const [toolUseId, result] of results) {
        if (!result || result.metadata?.executionStatus === 'cancelled') continue;
        const publication = parseSitePublicationResult(result.metadata);
        if (publication) {
            cards.push({ toolUseId, kind: 'site', publication });
            continue;
        }
        const resource = !result.isError && parseExternalResourceResult(result.metadata);
        if (resource && resource.provider === 'oss') cards.push({ toolUseId, kind: 'download', resource });
    }
    return cards;
}
