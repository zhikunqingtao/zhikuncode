import { useEffect, useRef } from 'react';
import {
    PROMPT_DRAFT_FALLBACK_KEY,
    resolvePromptDraftKey,
    usePromptDraftStore,
} from '@/store/promptDraftStore';

/** All composer hooks share the same first-session migration contract. */
export function usePromptDraftKey(sessionId?: string | null): string {
    const key = resolvePromptDraftKey(sessionId);
    const previousKey = useRef(key);
    useEffect(() => {
        if (previousKey.current === PROMPT_DRAFT_FALLBACK_KEY
                && key !== PROMPT_DRAFT_FALLBACK_KEY) {
            usePromptDraftStore.getState().migrateFallbackTo(key);
        }
        previousKey.current = key;
    }, [key]);
    return key;
}
