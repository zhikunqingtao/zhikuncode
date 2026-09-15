import { forwardRef, useCallback, useState } from 'react';
import { cn } from './cn';

/**
 * Toggle 基元（§6.2 / §10.2）：
 * - 50×30 轨道：bg-sunken2 + shadow-well + rounded-full；ON = 轨道 bg-accent2
 * - 24px 白旋钮：shadow-soft-sm，spring 曲线位移（ease-spring）
 * - 原生 <button> + role="switch" + aria-checked；Space/Enter 天然可切
 * - 支持受控（checked + onCheckedChange）与非受控（defaultChecked）
 */
export interface ToggleProps
    extends Omit<React.ButtonHTMLAttributes<HTMLButtonElement>, 'onChange' | 'value'> {
    checked?: boolean;
    defaultChecked?: boolean;
    onCheckedChange?: (checked: boolean) => void;
}

export const Toggle = forwardRef<HTMLButtonElement, ToggleProps>(
    (
        { className, checked, defaultChecked = false, onCheckedChange, onClick, disabled, ...props },
        ref,
    ) => {
        const [inner, setInner] = useState(defaultChecked);
        const isControlled = checked !== undefined;
        const on = isControlled ? checked : inner;

        const handleClick = useCallback<React.MouseEventHandler<HTMLButtonElement>>(
            (e) => {
                onClick?.(e);
                if (e.defaultPrevented || disabled) return;
                const next = !on;
                if (!isControlled) setInner(next);
                onCheckedChange?.(next);
            },
            [disabled, isControlled, on, onCheckedChange, onClick],
        );

        return (
            <button
                ref={ref}
                type="button"
                role="switch"
                aria-checked={on}
                disabled={disabled}
                onClick={handleClick}
                className={cn(
                    'relative before:absolute before:inset-x-0 before:top-1/2 before:h-11 before:-translate-y-1/2 md:before:hidden inline-flex h-[30px] w-[50px] shrink-0 items-center rounded-full transition-colors duration-base',
                    'focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-accent2-ring',
                    'disabled:opacity-50 disabled:pointer-events-none',
                    on ? 'bg-accent2-strong' : 'bg-t3 shadow-well',
                    className,
                )}
                {...props}
            >
                <span
                    aria-hidden="true"
                    className={cn(
                        'pointer-events-none block h-6 w-6 rounded-full shadow-soft-sm',
                        'transition-transform duration-base ease-spring',
                        on ? 'translate-x-[23px] bg-white' : 'translate-x-[3px] bg-surfacev2',
                    )}
                />
            </button>
        );
    },
);
Toggle.displayName = 'Toggle';
