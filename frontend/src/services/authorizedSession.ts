import { useConfigStore } from '@/store/configStore';
import { useModelStore } from '@/store/modelStore';
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

        const currentModels = useModelStore.getState();
        if (!currentModels.loaded) {
            await currentModels.fetchModels();
        }
        const refreshedModels = useModelStore.getState();
        const configuredDefault = useConfigStore.getState().defaultModel;
        const defaultModel = refreshedModels.models.some(
            model => model.id === configuredDefault)
            ? configuredDefault
            : (refreshedModels.defaultModel ?? '');
        return useSessionStore.getState().createSession(
            project.id,
            defaultModel,
        );
    })().finally(() => {
        pendingCreation = null;
    });

    return pendingCreation;
}
