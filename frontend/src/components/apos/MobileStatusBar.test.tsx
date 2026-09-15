import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { MobileStatusBar } from './MobileStatusBar';
import { useSessionStore } from '@/store/sessionStore';
import { useActivityStore } from '@/store/activityStore';
import { useSwarmStore } from '@/store/swarmStore';
import type { ActivityData } from '@/types/apos';

beforeEach(() => { useSessionStore.setState({ status: 'idle', sessionId: 'current-session' }); });
vi.mock('@/api/activityApi', () => ({ updateActivityDecision: vi.fn() }));
afterEach(() => { cleanup(); useActivityStore.setState({ activities: new Map() }); useSwarmStore.setState({ swarms: new Map(), activeSwarmId: null }); });
it('keeps current-session Activity details alongside live Swarm progress', () => {
  const activity: ActivityData = { id: 'current', sessionId: 'current-session', operationType: 'file_edit', summary: '当前会话待确认操作', changedFiles: [], status: 'running', timestamp: 1 };
  useSessionStore.setState({ sessionId: 'current-session' });
  useActivityStore.setState({ activities: new Map([['current', activity], ['other', { ...activity, id: 'other', sessionId: 'other-session', summary: '其他会话操作', timestamp: 2 }]]) });
  const worker = { workerId: 'w1', status: 'WORKING' as const, currentTask: '检查', toolCallCount: 1, tokenConsumed: 1, recentToolCalls: [], progressPercent: null, totalSteps: null, completedSteps: null, errorMessage: null, currentStepDescription: null, terminationReason: null };
  const swarm = { swarmId: 's1', teamName: '测试团队', phase: 'RUNNING' as const, activeWorkers: 1, totalWorkers: 1, completedTasks: 0, totalTasks: 1, workers: { w1: worker } };
  useSwarmStore.setState({ activeSwarmId: 's1', swarms: new Map([['s1', swarm]]) });
  render(<MobileStatusBar />);
  expect(screen.getByText('0/1 完成')).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '展开状态详情' }));
  expect(screen.getByText('当前会话待确认操作')).toBeInTheDocument();
  expect(screen.queryByText('其他会话操作')).not.toBeInTheDocument();
  act(() => useSwarmStore.setState({ swarms: new Map([['s1', { ...swarm, workers: { w1: { ...worker, status: 'TERMINATED', terminationReason: 'completed' } } }]]) }));
  expect(screen.getByText('1/1 完成')).toBeInTheDocument();
  act(() => useActivityStore.getState().updateActivity('current', { decision: 'approved' }));
  expect(screen.getByText(/已批准/)).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: /^批准$/ })).not.toBeInTheDocument();
});

it('空闲无活动时不渲染底栏，运行与待审批时重新显示', () => {
  const { container } = render(<MobileStatusBar />);
  expect(container).toBeEmptyDOMElement();
  act(() => useSessionStore.setState({ status: 'streaming' }));
  expect(screen.getByText('运行中')).toBeInTheDocument();
  act(() => useSessionStore.setState({ status: 'waiting_permission' }));
  expect(screen.getByText('待审批')).toBeInTheDocument();
  act(() => useSessionStore.setState({ status: 'idle' }));
  expect(container).toBeEmptyDOMElement();
});
