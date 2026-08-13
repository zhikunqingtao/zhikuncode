import { create } from 'zustand';
import {
    WORKBENCH_DEFAULT_VIEW_KEY,
    WORKBENCH_SESSION_VIEW_PREFIX,
    readDefaultWorkbenchView,
    readSessionWorkbenchView,
    readWorkbenchEnabled,
    writeStorageValue,
    type WorkbenchViewMode,
} from '@/utils/workbenchFeature';

interface WorkbenchViewState {
    enabled: boolean;
    activeSessionId: string | null;
    defaultView: WorkbenchViewMode;
    viewMode: WorkbenchViewMode;
    pendingMessageId: string | null;
    setActiveSession: (sessionId: string | null) => void;
    setViewMode: (mode: WorkbenchViewMode) => void;
    setDefaultView: (mode: WorkbenchViewMode) => void;
    openMessageInDevelopment: (messageId: string) => void;
    consumePendingMessage: () => void;
}

const initialDefault = readDefaultWorkbenchView();

export const useWorkbenchViewStore = create<WorkbenchViewState>((set, get) => ({
    enabled: readWorkbenchEnabled(),
    activeSessionId: null,
    defaultView: initialDefault,
    viewMode: initialDefault,
    pendingMessageId: null,

    setActiveSession: (sessionId) => set(state => {
        if (state.activeSessionId === sessionId) return state;
        return {
            activeSessionId: sessionId,
            viewMode: readSessionWorkbenchView(sessionId, state.defaultView),
            pendingMessageId: null,
        };
    }),

    setViewMode: (mode) => {
        const sessionId = get().activeSessionId;
        if (sessionId) {
            writeStorageValue(
                `${WORKBENCH_SESSION_VIEW_PREFIX}${sessionId}`,
                mode,
            );
        }
        set({ viewMode: mode });
    },

    setDefaultView: (mode) => {
        writeStorageValue(WORKBENCH_DEFAULT_VIEW_KEY, mode);
        set({ defaultView: mode });
    },

    openMessageInDevelopment: (messageId) => {
        const sessionId = get().activeSessionId;
        if (sessionId) {
            writeStorageValue(
                `${WORKBENCH_SESSION_VIEW_PREFIX}${sessionId}`,
                'development',
            );
        }
        set({ viewMode: 'development', pendingMessageId: messageId });
    },

    consumePendingMessage: () => set({ pendingMessageId: null }),
}));
