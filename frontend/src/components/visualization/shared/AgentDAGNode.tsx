/**
 * AgentDAGNode — React Flow 自定义 Agent 节点组件
 * 显示 Agent 名称、类型、任务摘要、实时计时器和状态图标
 */

import { memo, useState, useEffect } from 'react';
import { Handle, Position } from '@xyflow/react';
import type { NodeProps } from '@xyflow/react';
import { Loader2, CheckCircle2, XCircle, Clock } from 'lucide-react';

export interface AgentDAGNodeData {
  agentName: string;
  agentType: string;
  description: string;
  status: 'pending' | 'running' | 'completed' | 'failed';
  progress?: string;
  result?: string;
  startTime?: number;
  [key: string]: unknown;
}

const statusStyles: Record<string, string> = {
  pending: 'bg-surfacev2 border-border-hairline',
  running: 'bg-surfacev2 border-accent2',
  completed: 'bg-surfacev2 border-ok',
  failed: 'bg-surfacev2 border-err',
};

function StatusIcon({ status }: { status: string }) {
  switch (status) {
    case 'running':
      return <Loader2 className="w-4 h-4 text-accent2-ink animate-spin" />;
    case 'completed':
      return <CheckCircle2 className="w-4 h-4 text-ok" />;
    case 'failed':
      return <XCircle className="w-4 h-4 text-err" />;
    default:
      return <Clock className="w-4 h-4 text-t2" />;
  }
}

function ElapsedTimer({ startTime }: { startTime?: number }) {
  const [elapsed, setElapsed] = useState('');

  useEffect(() => {
    if (!Number.isFinite(startTime) || !startTime || startTime > Date.now()) { setElapsed(''); return; }
    const update = () => {
      const seconds = Math.floor((Date.now() - startTime) / 1000);
      if (seconds < 60) setElapsed(`${seconds}s`);
      else setElapsed(`${Math.floor(seconds / 60)}m ${seconds % 60}s`);
    };
    update();
    const id = setInterval(update, 1000);
    return () => clearInterval(id);
  }, [startTime]);

  if (!Number.isFinite(startTime) || !startTime || startTime > Date.now() || !elapsed) return null;
  return <span className="text-[13px] text-t2 font-mono">{elapsed}</span>;
}

function AgentDAGNodeComponent({ data }: NodeProps) {
  const nodeData = data as unknown as AgentDAGNodeData;
  const { agentName, agentType, description, status, progress, startTime } = nodeData;
  const borderClass = statusStyles[status] || statusStyles.pending;


  return (
    <div
      className={`w-[220px] min-h-[128px] rounded-[14px] border-2 shadow-md px-3 py-2.5 ${borderClass} `}
    >
      <Handle type="target" position={Position.Top} className="!bg-gray-400 !w-2 !h-2" />

      {/* Header: name + status */}
      <div className="flex items-center justify-between gap-1.5 mb-1">
        <span className="text-sm font-semibold text-t1 truncate">
          {agentName}
        </span>
        <StatusIcon status={status} />
      </div>

      {/* Agent type */}
      <span className="inline-block text-[13px] px-1.5 py-0.5 rounded bg-sunken2 text-t2 mb-1">
        {agentType}
      </span>

      {/* Description */}
      <p className="text-[13px] text-t2 truncate leading-tight">
        {progress || description}
      </p>

      {/* Timer for running */}
      {status === 'running' && (
        <div className="mt-1">
          <ElapsedTimer startTime={startTime} />
        </div>
      )}

      <Handle type="source" position={Position.Bottom} className="!bg-gray-400 !w-2 !h-2" />
    </div>
  );
}

export const AgentDAGNode = memo(AgentDAGNodeComponent);
