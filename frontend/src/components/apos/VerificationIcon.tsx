import type { LucideIcon } from 'lucide-react';
import { CheckCircle, XCircle, AlertTriangle, Loader2, MinusCircle } from 'lucide-react';
import type { VerificationStatus } from '@/types/apos';

interface VerificationIconProps {
  status: VerificationStatus;
  size?: number;
}

const STATUS_MAP: Record<
  VerificationStatus,
  { Icon: LucideIcon; color: string; animate?: boolean }
> = {
  all_pass: { Icon: CheckCircle, color: 'text-green-400' },
  has_error: { Icon: XCircle, color: 'text-red-400' },
  has_warning: { Icon: AlertTriangle, color: 'text-yellow-400' },
  pending: { Icon: Loader2, color: 'text-blue-400', animate: true },
  skipped: { Icon: MinusCircle, color: 'text-gray-500' },
  failed: { Icon: XCircle, color: 'text-orange-400' },
};

export function VerificationIcon({ status, size = 16 }: VerificationIconProps) {
  const config = STATUS_MAP[status];

  return (
    <config.Icon
      size={size}
      className={`${config.color} ${config.animate ? 'animate-spin' : ''} flex-shrink-0`}
    />
  );
}
