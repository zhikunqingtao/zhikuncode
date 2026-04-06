/**
 * TaskStore — 任务状态管理
 * SPEC: §8.3 Store #6
 * 持久化: 否
 */

import { create } from 'zustand';
import { immer } from 'zustand/middleware/immer';
import { subscribeWithSelector } from 'zustand/middleware';
import type { TaskState } from '@/types';

export interface TaskStoreState {
    tasks: Map<string, TaskState>;
    foregroundedTaskId: string | null;
    viewingAgentTaskId: string | null;
    agentNameRegistry: Map<string, string>;

    addTask: (task: TaskState) => void;
    updateTask: (taskId: string, update: Partial<TaskState>) => void;
    removeTask: (taskId: string) => void;
    clearTasks: () => void;
    addAgentTask: (data: { taskId: string; agentName: string; agentType: string }) => void;
    updateAgentTask: (taskId: string, progress: unknown) => void;
    completeAgentTask: (taskId: string, result: unknown) => void;
    setForegroundedTask: (taskId: string | null) => void;
    setViewingAgentTask: (taskId: string | null) => void;
}

export const useTaskStore = create<TaskStoreState>()(
    subscribeWithSelector(immer((set) => ({
        tasks: new Map(),
        foregroundedTaskId: null,
        viewingAgentTaskId: null,
        agentNameRegistry: new Map(),

        addTask: (task) => set(d => { d.tasks.set(task.taskId, task); }),
        updateTask: (id, upd) => set(d => {
            const t = d.tasks.get(id);
            if (t) Object.assign(t, upd);
        }),
        removeTask: (id) => set(d => { d.tasks.delete(id); }),
        clearTasks: () => set(d => {
            d.tasks.clear();
            d.foregroundedTaskId = null;
            d.viewingAgentTaskId = null;
        }),
        addAgentTask: (data) => set(d => {
            d.tasks.set(data.taskId, {
                taskId: data.taskId,
                status: 'running',
                agentName: data.agentName,
                agentType: data.agentType,
                createdAt: Date.now(),
            });
            d.agentNameRegistry.set(data.taskId, data.agentName);
        }),
        updateAgentTask: (id, progress) => set(d => {
            const t = d.tasks.get(id);
            if (t) t.progress = progress;
        }),
        completeAgentTask: (id, result) => set(d => {
            const t = d.tasks.get(id);
            if (t) { t.status = 'completed'; t.result = result; }
        }),
        setForegroundedTask: (id) => set(d => { d.foregroundedTaskId = id; }),
        setViewingAgentTask: (id) => set(d => { d.viewingAgentTaskId = id; }),
    })))
);
