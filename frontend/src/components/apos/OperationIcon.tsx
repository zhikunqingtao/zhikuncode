import type { LucideIcon } from 'lucide-react';
import {
  FileEdit, FilePlus, Terminal, TestTube, GitCommit,
  RefreshCw, Package, Settings, Trash2, HelpCircle,
} from 'lucide-react';
import type { OperationType } from '@/types/apos';

interface OperationIconProps {
  type: OperationType;
  size?: number;
}

const OPERATION_ICON_MAP: Record<OperationType, { Icon: LucideIcon; color: string }> = {
  file_edit: { Icon: FileEdit, color: 'text-accent2-ink' },
  file_create: { Icon: FilePlus, color: 'text-ok' },
  command_execute: { Icon: Terminal, color: 'text-purple-400' },
  test_run: { Icon: TestTube, color: 'text-cyan-400' },
  git_commit: { Icon: GitCommit, color: 'text-warn' },
  refactor: { Icon: RefreshCw, color: 'text-indigo-400' },
  dependency: { Icon: Package, color: 'text-warn' },
  config_change: { Icon: Settings, color: 'text-t2' },
  delete: { Icon: Trash2, color: 'text-err' },
  unknown: { Icon: HelpCircle, color: 'text-t2' },
};

export function OperationIcon({ type, size = 16 }: OperationIconProps) {
  const config = OPERATION_ICON_MAP[type];

  return (
    <config.Icon
      size={size}
      className={`${config.color} flex-shrink-0`}
    />
  );
}
