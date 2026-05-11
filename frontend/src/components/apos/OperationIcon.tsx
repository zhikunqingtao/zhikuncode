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
  file_edit: { Icon: FileEdit, color: 'text-blue-400' },
  file_create: { Icon: FilePlus, color: 'text-green-400' },
  command_execute: { Icon: Terminal, color: 'text-purple-400' },
  test_run: { Icon: TestTube, color: 'text-cyan-400' },
  git_commit: { Icon: GitCommit, color: 'text-orange-400' },
  refactor: { Icon: RefreshCw, color: 'text-indigo-400' },
  dependency: { Icon: Package, color: 'text-yellow-400' },
  config_change: { Icon: Settings, color: 'text-gray-400' },
  delete: { Icon: Trash2, color: 'text-red-400' },
  unknown: { Icon: HelpCircle, color: 'text-gray-500' },
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
