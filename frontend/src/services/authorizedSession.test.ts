import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useConfigStore } from '@/store/configStore';
import { useModelStore } from '@/store/modelStore';
import { useProjectStore, type Project } from '@/store/projectStore';
import { useSessionStore } from '@/store/sessionStore';
import { requestAuthorizedSession } from './authorizedSession';

const project: Project = {
    id: 'project-1',
    name: 'Demo',
    workspaceRoot: '/workspace/demo',
    createdAt: '2026-07-30T00:00:00Z',
};

const originalRequestSelection =
    useProjectStore.getState().requestSelection;
const originalCreateSession = useSessionStore.getState().createSession;
const originalFetchModels = useModelStore.getState().fetchModels;

const model = (id: string) => ({
    id,
    displayName: id,
    supportsImages: false,
    maxImages: 0,
});

describe('requestAuthorizedSession', () => {
    beforeEach(() => {
        useConfigStore.setState({ defaultModel: 'model-default' });
        useProjectStore.setState({
            requestSelection: originalRequestSelection,
        });
        useSessionStore.setState({
            sessionId: null,
            createSession: originalCreateSession,
        });
        useModelStore.setState({
            models: [model('model-default')],
            defaultModel: 'model-default',
            loaded: true,
            loading: false,
            error: null,
            fetchModels: originalFetchModels,
        });
    });

    afterEach(() => {
        useProjectStore.setState({
            requestSelection: originalRequestSelection,
        });
        useSessionStore.setState({
            sessionId: null,
            createSession: originalCreateSession,
        });
        useModelStore.setState({
            models: [],
            defaultModel: null,
            loaded: false,
            loading: false,
            error: null,
            fetchModels: originalFetchModels,
        });
        vi.unstubAllGlobals();
        vi.restoreAllMocks();
    });

    it('creates a Session only after a Project is selected', async () => {
        const requestSelection = vi.fn().mockResolvedValue(project);
        const createSession = vi.fn()
            .mockResolvedValue('session-created');
        useProjectStore.setState({ requestSelection });
        useSessionStore.setState({ createSession });

        await expect(requestAuthorizedSession())
            .resolves.toBe('session-created');

        expect(requestSelection).toHaveBeenCalledTimes(1);
        expect(createSession).toHaveBeenCalledWith(
            project.id,
            'model-default',
        );
    });

    it('does not create a Session when folder selection is canceled', async () => {
        const requestSelection = vi.fn().mockResolvedValue(null);
        const createSession = vi.fn();
        useProjectStore.setState({ requestSelection });
        useSessionStore.setState({ createSession });

        await expect(requestAuthorizedSession()).resolves.toBeNull();

        expect(createSession).not.toHaveBeenCalled();
    });

    it('uses the provider default when persisted config names a stale model', async () => {
        useConfigStore.setState({ defaultModel: 'removed-model' });
        useModelStore.setState({
            models: [model('provider-default')],
            defaultModel: 'provider-default',
            loaded: true,
        });
        useProjectStore.setState({
            requestSelection: vi.fn().mockResolvedValue(project),
        });
        const createSession = vi.fn().mockResolvedValue('session-created');
        useSessionStore.setState({ createSession });

        await expect(requestAuthorizedSession()).resolves.toBe('session-created');

        expect(createSession).toHaveBeenCalledWith(project.id, 'provider-default');
    });

    it('loads the provider model contract before creating a cold-start Session', async () => {
        useConfigStore.setState({ defaultModel: 'removed-model' });
        const fetchModels = vi.fn(async () => {
            useModelStore.setState({
                models: [model('provider-default')],
                defaultModel: 'provider-default',
                loaded: true,
                loading: false,
                error: null,
            });
        });
        useModelStore.setState({
            models: [],
            defaultModel: null,
            loaded: false,
            loading: false,
            error: null,
            fetchModels,
        });
        useProjectStore.setState({
            requestSelection: vi.fn().mockResolvedValue(project),
        });
        const createSession = vi.fn().mockResolvedValue('session-created');
        useSessionStore.setState({ createSession });

        await expect(requestAuthorizedSession()).resolves.toBe('session-created');

        expect(fetchModels).toHaveBeenCalledTimes(1);
        expect(createSession).toHaveBeenCalledWith(project.id, 'provider-default');
    });

    it('waits for an in-flight model load before creating a Session', async () => {
        let resolveModels!: (response: Response) => void;
        vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>(resolve => {
            resolveModels = resolve;
        })));
        useModelStore.setState({
            models: [],
            defaultModel: null,
            loaded: false,
            loading: false,
            error: null,
            fetchModels: originalFetchModels,
        });
        useProjectStore.setState({
            requestSelection: vi.fn().mockResolvedValue(project),
        });
        const createSession = vi.fn().mockResolvedValue('session-created');
        useSessionStore.setState({ createSession });

        const headerLoad = useModelStore.getState().fetchModels();
        const creation = requestAuthorizedSession();
        await Promise.resolve();
        expect(createSession).not.toHaveBeenCalled();

        resolveModels({
            ok: true,
            json: async () => ({
                models: [model('provider-default')],
                defaultModel: 'provider-default',
            }),
        } as Response);

        await expect(Promise.all([headerLoad, creation])).resolves.toEqual([
            undefined,
            'session-created',
        ]);
        expect(createSession).toHaveBeenCalledWith(project.id, 'provider-default');
    });

    it('shares one authorization and Session request across double sends', async () => {
        let resolveSelection!: (selection: Project | null) => void;
        const selection = new Promise<Project | null>(resolve => {
            resolveSelection = resolve;
        });
        const requestSelection = vi.fn(() => selection);
        const createSession = vi.fn()
            .mockResolvedValue('session-created');
        useProjectStore.setState({ requestSelection });
        useSessionStore.setState({ createSession });

        const first = requestAuthorizedSession();
        const second = requestAuthorizedSession();

        expect(second).toBe(first);
        expect(requestSelection).toHaveBeenCalledTimes(1);
        resolveSelection(project);
        await expect(Promise.all([first, second])).resolves.toEqual([
            'session-created',
            'session-created',
        ]);
        expect(createSession).toHaveBeenCalledTimes(1);
    });
});
