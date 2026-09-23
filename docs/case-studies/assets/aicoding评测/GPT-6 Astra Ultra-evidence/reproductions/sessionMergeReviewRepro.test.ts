import { afterEach, expect, it, vi } from 'vitest';
afterEach(() => { localStorage.clear(); vi.restoreAllMocks(); vi.unstubAllGlobals(); });
it('discovers the authoritative active operation after restoring an older completed result', async () => {
 localStorage.clear(); vi.resetModules();
 const request={ sourceSessionIds:['A','B'],primarySessionId:'A',title:'E',model:'m' };
 const completed={ operationId:'old',targetSessionId:'E',status:'completed',stage:'completed',request,result:{},canCancel:false,lockedSourceSessionIds:[] };
 const paused={...completed,operationId:'new',targetSessionId:'F',status:'paused',stage:'extracting',canCancel:true,canResume:true};
 localStorage.setItem('session-merge-pending-v2:old-key',JSON.stringify({key:'old-key',request,operation:completed}));
 const fetchMock=vi.fn().mockImplementation(async (url:string)=>({ok:true,json:async()=>url.endsWith('/active')?paused:completed}));
 vi.stubGlobal('fetch',fetchMock);
 const {useSessionMergeStore:store}=await import('./sessionMergeStore');
 await store.getState().refresh();
 await store.getState().refresh();
 expect(fetchMock).toHaveBeenCalledWith('/api/session-merges/active',expect.anything());
 expect(store.getState().pending?.operation?.operationId).toBe('new');
});
