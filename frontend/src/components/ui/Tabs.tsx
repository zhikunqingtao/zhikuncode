import { forwardRef, useCallback, useId, useRef, useState } from 'react';
import { cn } from './cn';

/**
 * Tabs 基元（§6.2 / §10.2）：
 * - 容器 role="tablist"（sunken 槽），项 role="tab" + aria-selected，面板 role="tabpanel"
 * - 选中 = bg-surfacev2 + shadow-e1 + rounded-xl（容器 rounded-2xl 满足嵌套圆角规则）
 * - 键盘：←/→/↑/↓ 循环移动，Home/End 首尾；自动激活（焦点移动即选中）
 * - 受控（value + onValueChange）/ 非受控（defaultValue）
 * - aria-controls ↔ tabpanel 接线：凡携带 content 的项都会渲染对应 tabpanel
 *   （未选中者 hidden 常驻 DOM，保证 aria-controls 引用始终存在）；不带 content 的
 *   项（如 TurnToolbar 密度 segmented control，面板由外部自渲染）不输出 aria-controls，
 *   避免悬空引用。
 */
export interface TabItem {
    value: string;
    label: React.ReactNode;
    disabled?: boolean;
    /** 选中时渲染到 tabpanel 的内容 */
    content?: React.ReactNode;
}

export interface TabsProps
    extends Omit<React.HTMLAttributes<HTMLDivElement>, 'onChange' | 'defaultValue'> {
    items: TabItem[];
    value?: string;
    defaultValue?: string;
    onValueChange?: (value: string) => void;
}

export const Tabs = forwardRef<HTMLDivElement, TabsProps>(
    ({ className, items, value, defaultValue, onValueChange, ...props }, ref) => {
        const baseId = useId();
        const firstEnabled = items.find((i) => !i.disabled)?.value;
        const [inner, setInner] = useState(defaultValue ?? firstEnabled);
        const isControlled = value !== undefined;
        const selected = (isControlled ? value : inner) ?? firstEnabled;
        const tabRefs = useRef<Array<HTMLButtonElement | null>>([]);

        const select = useCallback(
            (v: string) => {
                if (!isControlled) setInner(v);
                onValueChange?.(v);
            },
            [isControlled, onValueChange],
        );

        const handleKeyDown = useCallback(
            (e: React.KeyboardEvent<HTMLDivElement>) => {
                const enabledIndexes = items
                    .map((item, idx) => ({ item, idx }))
                    .filter(({ item }) => !item.disabled)
                    .map(({ idx }) => idx);
                if (enabledIndexes.length === 0) return;

                const currentIdx = items.findIndex((i) => i.value === selected);
                const pos = Math.max(0, enabledIndexes.indexOf(currentIdx));
                let nextPos: number | null = null;

                switch (e.key) {
                    case 'ArrowRight':
                    case 'ArrowDown':
                        nextPos = (pos + 1) % enabledIndexes.length;
                        break;
                    case 'ArrowLeft':
                    case 'ArrowUp':
                        nextPos = (pos - 1 + enabledIndexes.length) % enabledIndexes.length;
                        break;
                    case 'Home':
                        nextPos = 0;
                        break;
                    case 'End':
                        nextPos = enabledIndexes.length - 1;
                        break;
                    default:
                        return;
                }
                e.preventDefault();
                const nextIdx = enabledIndexes[nextPos];
                select(items[nextIdx].value);
                tabRefs.current[nextIdx]?.focus();
            },
            [items, select, selected],
        );

        return (
            <div ref={ref} className={cn('w-full', className)} {...props}>
                <div
                    role="tablist"
                    onKeyDown={handleKeyDown}
                    className="inline-flex items-center gap-1 rounded-[14px] bg-sunken2 p-1 shadow-well"
                >
                    {items.map((item, idx) => {
                        const isSelected = item.value === selected;
                        return (
                            <button
                                key={item.value}
                                ref={(el) => {
                                    tabRefs.current[idx] = el;
                                }}
                                type="button"
                                role="tab"
                                id={`${baseId}-tab-${item.value}`}
                                aria-selected={isSelected}
                                aria-controls={
                                    item.content != null
                                        ? `${baseId}-panel-${item.value}`
                                        : undefined
                                }
                                tabIndex={isSelected ? 0 : -1}
                                disabled={item.disabled}
                                onClick={() => select(item.value)}
                                className={cn(
                                    'inline-flex h-8 items-center justify-center gap-1.5 rounded-xl px-3 text-sm font-medium transition-interactive duration-fast',
                                    'focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-accent2-ring',
                                    'disabled:opacity-50 disabled:pointer-events-none',
                                    isSelected
                                        ? 'bg-surfacev2 text-t1 shadow-e1'
                                        : 'text-t2 hover:text-t1',
                                )}
                            >
                                {item.label}
                            </button>
                        );
                    })}
                </div>
                {/* 每个携带 content 的项渲染对应 tabpanel（未选中 hidden 常驻，
                    保证 aria-controls 引用不悬空） */}
                {items.map((item) =>
                    item.content != null ? (
                        <div
                            key={item.value}
                            role="tabpanel"
                            id={`${baseId}-panel-${item.value}`}
                            aria-labelledby={`${baseId}-tab-${item.value}`}
                            hidden={item.value !== selected}
                            className="mt-3"
                        >
                            {item.content}
                        </div>
                    ) : null,
                )}
            </div>
        );
    },
);
Tabs.displayName = 'Tabs';
