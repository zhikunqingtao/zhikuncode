import { forwardRef } from 'react';
import { cva, type VariantProps } from 'class-variance-authority';
import { cn } from './cn';

/**
 * Progress 基元（§6.2 / §10.2）：
 * - 轨道 h-2.5：bg-sunken2 + shadow-well + rounded-full
 * - 填充：accent2 渐变；宽度变化走 transform scaleX（§5.2 禁 width 过渡）
 * - determinate：role="progressbar" + aria-valuenow/-min/-max
 * - indeterminate：省略 valuenow + aria-busy，填充 animate-indeterminate
 * - start / end：两端数值插槽（tabular-nums）
 */
const progressFillVariants = cva('h-full w-full rounded-full bg-gradient-to-r from-accent2 to-accent2-strong', {
    variants: {
        indeterminate: {
            true: 'origin-left animate-indeterminate',
            false: 'origin-left transition-transform duration-base ease-in-out',
        },
    },
});

export interface ProgressProps
    extends Omit<React.HTMLAttributes<HTMLDivElement>, 'children'>,
        VariantProps<typeof progressFillVariants> {
    /** determinate 当前值；不传或 indeterminate=true 时为不定态 */
    value?: number;
    min?: number;
    max?: number;
    /** 左端插槽（如 "3"） */
    start?: React.ReactNode;
    /** 右端插槽（如 "10"） */
    end?: React.ReactNode;
    /** 屏幕阅读器标签 */
    'aria-label'?: string;
}

export const Progress = forwardRef<HTMLDivElement, ProgressProps>(
    (
        {
            className,
            value,
            min = 0,
            max = 100,
            indeterminate = false,
            start,
            end,
            'aria-label': ariaLabel,
            ...props
        },
        ref,
    ) => {
        const isIndeterminate = indeterminate || value === undefined;
        const ratio = isIndeterminate ? 1 : Math.min(1, Math.max(0, ((value ?? 0) - min) / (max - min || 1)));

        const track = (
            <div
                ref={ref}
                role="progressbar"
                aria-label={ariaLabel}
                aria-valuemin={isIndeterminate ? undefined : min}
                aria-valuemax={isIndeterminate ? undefined : max}
                aria-valuenow={isIndeterminate ? undefined : value}
                aria-busy={isIndeterminate || undefined}
                className={cn(
                    'relative h-2.5 flex-1 overflow-hidden rounded-full bg-sunken2 shadow-well',
                    className,
                )}
                {...props}
            >
                <div
                    className={cn(progressFillVariants({ indeterminate: isIndeterminate }))}
                    style={isIndeterminate ? undefined : { transform: `scaleX(${ratio})` }}
                />
            </div>
        );

        if (start === undefined && end === undefined) return track;

        return (
            <div className="flex w-full items-center gap-2">
                {start !== undefined && (
                    <span className="shrink-0 text-xs tabular-nums text-t3">{start}</span>
                )}
                {track}
                {end !== undefined && (
                    <span className="shrink-0 text-xs tabular-nums text-t3">{end}</span>
                )}
            </div>
        );
    },
);
Progress.displayName = 'Progress';

export { progressFillVariants };
