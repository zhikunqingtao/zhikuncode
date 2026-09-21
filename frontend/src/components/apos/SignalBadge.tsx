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
  auto_approve: { color: 'text-ok', bgColor: 'bg-oksoft', Icon: CheckCircle2, label: '自动放行' },
  review_recommended: { color: 'text-warn', bgColor: 'bg-warnsoft', Icon: Eye, label: '建议审查' },
  manual_required: { color: 'text-accent2-ink', bgColor: 'bg-accent2-soft', Icon: Hand, label: '需手动处理' },
  blocked: { color: 'text-err', bgColor: 'bg-errsoft', Icon: XCircle, label: '已阻止' },
  loading: { color: 'text-t2', bgColor: 'bg-sunken2', Icon: Loader2, label: '验证中' },
  unavailable: { color: 'text-t2', bgColor: 'bg-sunken2', Icon: XCircle, label: '不可用' },
};

const FALLBACK_CONFIG = {
  color: 'text-t2',
  bgColor: 'bg-sunken2',
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
      className={`inline-flex items-center gap-1 rounded-full ${padding} ${config.bgColor} ${config.color} ${isUnavailable ? 'border border-dashed border-hairline' : ''}`}
      title={tooltipText}
    >
      <config.Icon
        size={iconSize}
        className={isLoading ? 'animate-spin' : ''}
      />
      {size === 'md' && (
        <span className="text-[13px] font-medium">{config.label}</span>
      )}
    </span>
  );
}
