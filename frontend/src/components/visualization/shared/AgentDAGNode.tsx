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
  pending:   'bg-slate-100 dark:bg-slate-800 border-slate-300 dark:border-slate-600',
  running:   'bg-blue-50 dark:bg-blue-950 border-blue-400 dark:border-blue-500',
  completed: 'bg-green-50 dark:bg-green-950 border-green-400 dark:border-green-500',
  failed:    'bg-red-50 dark:bg-red-950 border-red-400 dark:border-red-500',
};

function StatusIcon({ status }: { status: string }) {
  switch (status) {
    case 'running':
      return <Loader2 className="w-4 h-4 text-blue-500 animate-spin" />;
    case 'completed':
      return <CheckCircle2 className="w-4 h-4 text-green-500" />;
    case 'failed':
      return <XCircle className="w-4 h-4 text-red-500" />;
    default:
      return <Clock className="w-4 h-4 text-slate-400" />;
  }
}

function ElapsedTimer({ startTime }: { startTime?: number }) {
  const [elapsed, setElapsed] = useState('');

  useEffect(() => {
    if (!startTime) return;
    const update = () => {
      const seconds = Math.floor((Date.now() - startTime) / 1000);
      if (seconds < 60) setElapsed(`${seconds}s`);
      else setElapsed(`${Math.floor(seconds / 60)}m ${seconds % 60}s`);
    };
    update();
    const id = setInterval(update, 1000);
    return () => clearInterval(id);
  }, [startTime]);

  if (!startTime || !elapsed) return null;
  return <span className="text-[10px] text-gray-400 dark:text-gray-500 font-mono">{elapsed}</span>;
}

function AgentDAGNodeComponent({ data }: NodeProps) {
  const nodeData = data as unknown as AgentDAGNodeData;
  const { agentName, agentType, description, status, progress, startTime } = nodeData;
  const borderClass = statusStyles[status] || statusStyles.pending;
  const pulseClass = status === 'running' ? 'animate-pulse' : '';

  return (
    <div
      className={`w-[220px] min-h-[80px] rounded-lg border-2 shadow-md px-3 py-2.5 ${borderClass} ${pulseClass}`}
    >
      <Handle type="target" position={Position.Top} className="!bg-gray-400 !w-2 !h-2" />

      {/* Header: name + status */}
      <div className="flex items-center justify-between gap-1.5 mb-1">
        <span className="text-sm font-semibold text-gray-800 dark:text-gray-200 truncate">
          {agentName}
        </span>
        <StatusIcon status={status} />
      </div>

      {/* Agent type */}
      <span className="inline-block text-[10px] px-1.5 py-0.5 rounded bg-gray-200/70 dark:bg-gray-700/70 text-gray-600 dark:text-gray-400 mb-1">
        {agentType}
      </span>

      {/* Description */}
      <p className="text-xs text-gray-500 dark:text-gray-400 truncate leading-tight">
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
