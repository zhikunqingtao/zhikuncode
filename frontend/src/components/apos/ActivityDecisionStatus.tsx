import { useActivityStore } from '@/store/activityStore';
export function ActivityDecisionStatus({ id }: { id: string }) {
    const request = useActivityStore(s => s.decisionRequests.get(id));
    if (!request) return null;
    return <div role={request.error ? 'alert' : 'status'} className={`px-3 py-2 text-[13px] ${request.error ? 'text-err' : 'text-t2'}`}>{request.pending ? '正在提交，请稍候…' : `${request.error}。请重试批准或拒绝。`}</div>;
}
