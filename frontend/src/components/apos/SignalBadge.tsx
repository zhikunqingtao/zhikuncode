import type { LucideIcon } from 'lucide-react';
import { CheckCircle2, Eye, Hand, XCircle, Loader2 } from 'lucide-react';
import type { Signal } from '@/types/apos';

interface SignalBadgeProps {
  signal: Signal | 'loading' | 'unavailable';
  size?: 'sm' | 'md';
  showTooltip?: boolean;
  reason?: string;
}

const SIGNAL_MAP: Record<
  Signal | 'loading' | 'unavailable',
  { color: string; bgColor: string; Icon: LucideIcon; label: string }
> = {
  auto_approve: { color: 'text-green-400', bgColor: 'bg-green-500/15', Icon: CheckCircle2, label: '自动放行' },
  review_recommended: { color: 'text-yellow-400', bgColor: 'bg-yellow-500/15', Icon: Eye, label: '建议审查' },
  manual_required: { color: 'text-blue-400', bgColor: 'bg-blue-500/15', Icon: Hand, label: '需手动处理' },
  blocked: { color: 'text-red-400', bgColor: 'bg-red-500/15', Icon: XCircle, label: '已阻止' },
  loading: { color: 'text-gray-400', bgColor: 'bg-gray-500/15', Icon: Loader2, label: '验证中' },
  unavailable: { color: 'text-gray-500', bgColor: 'bg-gray-500/10', Icon: XCircle, label: '不可用' },
};

const FALLBACK_CONFIG = {
  color: 'text-gray-500',
  bgColor: 'bg-gray-500/10',
  Icon: XCircle,
  label: '未知状态',
};

export function SignalBadge({ signal, size = 'sm', showTooltip = true, reason }: SignalBadgeProps) {
  const config = SIGNAL_MAP[signal] ?? FALLBACK_CONFIG;
  const iconSize = size === 'sm' ? 14 : 18;
  const padding = size === 'sm' ? 'px-1.5 py-0.5' : 'px-2 py-1';

  const isLoading = signal === 'loading';
  const isUnavailable = signal === 'unavailable';

  const tooltipText = showTooltip ? (reason || config.label) : undefined;

  return (
    <span
      className={`inline-flex items-center gap-1 rounded-full ${padding} ${config.bgColor} ${config.color} ${isUnavailable ? 'border border-dashed border-gray-400 dark:border-gray-600' : ''}`}
      title={tooltipText}
    >
      <config.Icon
        size={iconSize}
        className={isLoading ? 'animate-spin' : ''}
      />
      {size === 'md' && (
        <span className="text-xs font-medium">{config.label}</span>
      )}
    </span>
  );
}
