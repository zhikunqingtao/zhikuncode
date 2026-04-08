/**
 * OutputStyleSelector — 输出样式切换组件 (compact/verbose/raw)。
 *
 * SPEC: §8.3 ConfigStore outputStyle
 * 允许用户在不同输出详细度之间切换:
 * - compact: 精简输出，折叠工具细节
 * - verbose: 详细输出，展开所有工具调用
 * - raw: 原始 JSON，用于调试
 */

import React, { useCallback } from 'react';
import { useConfigStore } from '@/store/configStore';

interface OutputStyle {
    name: string;
    label: string;
    description: string;
}

const DEFAULT_STYLES: OutputStyle[] = [
    { name: 'compact', label: 'Compact', description: 'Concise output, tool details collapsed' },
    { name: 'verbose', label: 'Verbose', description: 'Detailed output, all tool calls expanded' },
    { name: 'raw', label: 'Raw', description: 'Raw JSON output for debugging' },
];

const OutputStyleSelector: React.FC = () => {
    const activeStyleName = useConfigStore(s => s.outputStyle.activeStyleName);
    const availableStyles = useConfigStore(s => s.outputStyle.availableStyles);
    const setActiveOutputStyle = useConfigStore(s => s.setActiveOutputStyle);

    const styles = availableStyles.length > 0
        ? availableStyles.map(s => ({ name: s.name, label: s.name, description: s.description ?? '' }))
        : DEFAULT_STYLES;

    const handleSelect = useCallback((name: string) => {
        setActiveOutputStyle(activeStyleName === name ? null : name);
    }, [activeStyleName, setActiveOutputStyle]);

    return (
        <div className="output-style-selector flex items-center gap-1">
            {styles.map(style => (
                <button
                    key={style.name}
                    onClick={() => handleSelect(style.name)}
                    title={style.description}
                    className={`
                        px-2 py-0.5 text-xs rounded transition-colors
                        ${activeStyleName === style.name
                            ? 'bg-blue-600/30 text-blue-300 border border-blue-500/50'
                            : 'text-gray-500 hover:text-gray-300 hover:bg-gray-700/30 border border-transparent'
                        }
                    `}
                >
                    {style.label}
                </button>
            ))}
        </div>
    );
};

export default React.memo(OutputStyleSelector);
