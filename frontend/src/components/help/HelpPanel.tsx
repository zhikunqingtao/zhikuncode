/**
 * HelpPanel — /help 命令结果美化渲染组件
 *
 * 按分组展示所有可用命令，支持折叠/展开
 */

import React, { useState } from 'react';
import { Terminal, ChevronDown, ChevronRight, HelpCircle } from 'lucide-react';

interface CommandItem {
    name: string;
    description: string;
    aliases: string[];
}

interface CommandGroup {
    title: string;
    titleZh: string;
    commands: CommandItem[];
}

interface HelpPanelProps {
    groups: CommandGroup[];
    total: number;
}

const groupColors: Record<string, { border: string; badge: string; badgeText: string }> = {
    'Local Commands':       { border: 'border-hairline',  badge: 'bg-accent2-soft',  badgeText: 'text-accent2-ink dark:text-t1' },
    'Interactive Commands': { border: 'border-hairline', badge: 'bg-accent2-soft', badgeText: 'text-accent2-ink dark:text-t1' },
    'Prompt Commands':      { border: 'border-hairline',  badge: 'bg-oksoft',  badgeText: 'text-ok' },
};

const defaultColor = { border: 'border-hairline', badge: 'bg-sunken2', badgeText: 'text-t2' };

export const HelpPanel: React.FC<HelpPanelProps> = ({ groups, total }) => {
    const [expandedGroups, setExpandedGroups] = useState<Set<string>>(
        new Set(groups.map(g => g.title))
    );

    const toggleGroup = (title: string) => {
        setExpandedGroups(prev => {
            const next = new Set(prev);
            if (next.has(title)) next.delete(title);
            else next.add(title);
            return next;
        });
    };

    return (
        <div className="rounded-[14px] border border-hairline bg-sunken2 overflow-hidden max-w-2xl">
            {/* Header */}
            <div className="px-4 py-3 border-b border-hairline flex items-center justify-between">
                <div className="flex items-center gap-2">
                    <HelpCircle size={16} className="text-accent2-ink" />
                    <span className="text-sm font-medium text-t2">可用命令</span>
                </div>
                <span className="text-[13px] text-t2">
                    共 {total} 个命令 · 输入 <code className="px-1.5 py-0.5 bg-sunken2 rounded text-t2 font-mono">/help &lt;命令名&gt;</code> 查看详情
                </span>
            </div>

            {/* Command Groups */}
            <div className="divide-y divide-hairline">
                {groups.map(group => {
                    const color = groupColors[group.title] ?? defaultColor;
                    const isExpanded = expandedGroups.has(group.title);

                    return (
                        <div key={group.title}>
                            {/* Group header */}
                            <button
                                onClick={() => toggleGroup(group.title)}
                                className="panel-control w-full flex items-center gap-2 px-4 py-2.5 hover:bg-sunken2 transition-colors text-left"
                            >
                                {isExpanded
                                    ? <ChevronDown size={14} className="text-t2" />
                                    : <ChevronRight size={14} className="text-t2" />
                                }
                                <span className={`text-[13px] font-medium px-2 py-0.5 rounded ${color.badge} ${color.badgeText}`}>
                                    {group.titleZh}
                                </span>
                                <span className="text-[13px] text-t2">
                                    {group.commands.length} 个命令
                                </span>
                            </button>

                            {/* Command list */}
                            {isExpanded && (
                                <div className="px-4 pb-2">
                                    <div className={`rounded-md border ${color.border} overflow-hidden`}>
                                        <table className="w-full text-[13px]">
                                            <tbody>
                                                {group.commands.map(cmd => (
                                                    <tr key={cmd.name} className="border-b border-hairline last:border-b-0 hover:bg-sunken2 transition-colors">
                                                        <td className="py-1.5 pl-3 pr-2 w-[180px]">
                                                            <div className="flex items-center gap-1.5">
                                                                <Terminal size={11} className="text-t2 flex-shrink-0" />
                                                                <code className="text-accent2-ink dark:text-t1 font-mono font-medium">
                                                                    /{cmd.name}
                                                                </code>
                                                            </div>
                                                            {cmd.aliases.length > 0 && (
                                                                <div className="text-t2 ml-4 mt-0.5">
                                                                    {cmd.aliases.join(', ')}
                                                                </div>
                                                            )}
                                                        </td>
                                                        <td className="py-1.5 pr-3 text-t2">
                                                            {cmd.description}
                                                        </td>
                                                    </tr>
                                                ))}
                                            </tbody>
                                        </table>
                                    </div>
                                </div>
                            )}
                        </div>
                    );
                })}
            </div>
        </div>
    );
};
