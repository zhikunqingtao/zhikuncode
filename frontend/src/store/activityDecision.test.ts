import { beforeEach, expect, it, vi } from 'vitest';
import { useActivityStore } from './activityStore';
import { updateActivityDecision } from '@/api/activityApi';
vi.mock('@/api/activityApi',()=>({updateActivityDecision:vi.fn()}));
const activity = {id:'a',sessionId:'s',operationType:'file_edit' as const,summary:'edit',status:'completed',timestamp:1,changedFiles:[]};
beforeEach(()=>{vi.resetAllMocks();useActivityStore.setState({activities:new Map([['a',activity]]),decisionRequests:new Map()});});
it('waits for confirmation and blocks duplicate submissions',async()=>{
 let resolve!:()=>void;
 vi.mocked(updateActivityDecision).mockImplementation(()=>new Promise<void>(r=>{resolve=r;}));
 const pending=useActivityStore.getState().submitDecision('a','approved');
 await useActivityStore.getState().submitDecision('a','rejected');
 expect(updateActivityDecision).toHaveBeenCalledTimes(1);
 expect(useActivityStore.getState().activities.get('a')?.decision).toBeUndefined();
 expect(useActivityStore.getState().decisionRequests.get('a')?.pending).toBe(true);
 resolve();await pending;
 expect(useActivityStore.getState().activities.get('a')?.decision).toBe('approved');
});
it('shows failure without false success and allows retry',async()=>{
 vi.mocked(updateActivityDecision).mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce();
 await useActivityStore.getState().submitDecision('a','rejected');
 expect(useActivityStore.getState().activities.get('a')?.decision).toBeUndefined();
 expect(useActivityStore.getState().decisionRequests.get('a')?.error).toBe('offline');
 await useActivityStore.getState().submitDecision('a','rejected');
 expect(useActivityStore.getState().activities.get('a')?.decision).toBe('rejected');
});
it('does not apply a response to an activity belonging to another session',async()=>{
 let resolve!:()=>void;vi.mocked(updateActivityDecision).mockImplementation(()=>new Promise<void>(r=>{resolve=r;}));
 const pending=useActivityStore.getState().submitDecision('a','approved');
 useActivityStore.setState({activities:new Map([['a',{...activity,sessionId:'other'}]])});resolve();await pending;
 expect(useActivityStore.getState().activities.get('a')?.decision).toBeUndefined();
});
