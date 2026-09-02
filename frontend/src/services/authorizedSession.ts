import { useConfigStore } from '@/store/configStore';
import { useProjectStore } from '@/store/projectStore';
import { useSessionStore } from '@/store/sessionStore';

let pendingCreation: Promise<string | null> | null = null;

export const NEW_AUTHORIZED_SESSION_EVENT =
    'zhikuncode:new-authorized-session';

export function dispatchNewAuthorizedSessionRequest(): void {
    window.dispatchEvent(new Event(NEW_AUTHORIZED_SESSION_EVENT));
}

/**
 * Opens the persistent Project authorization chooser and creates one Session
 * bound to the selected authorization. Concurrent callers share the same
 * chooser and Session POST so a double submit cannot create two Sessions.
 */
export function requestAuthorizedSession(): Promise<string | null> {
    if (pendingCreation) return pendingCreation;

    pendingCreation = (async () => {
        const project = await useProjectStore.getState().requestSelection();
        if (!project) return null;

        const defaultModel = useConfigStore.getState().defaultModel
            ?? 'qwen3.8-max-0902';
        return useSessionStore.getState().createSession(
            project.id,
            defaultModel,
        );
    })().finally(() => {
        pendingCreation = null;
    });

    return pendingCreation;
}
