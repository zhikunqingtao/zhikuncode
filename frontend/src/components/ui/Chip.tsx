import { forwardRef } from 'react';
import { cva, type VariantProps } from 'class-variance-authority';
import { cn } from './cn';

/**
 * Chip 基元（展示型标签）：rounded-full 胶囊。
 * variant：accent（soft 底 + accent 字）/ ok / warn / err / neutral；
 * selected：实底白字（accent2-strong），覆盖 variant 配色。
 * 注：accent 文字档取 strong 以满足 §10.1 对比度（soft 底上基准档不足 4.5:1）。
 */
const chipVariants = cva(
    'inline-flex items-center gap-1 rounded-full h-6 px-2.5 text-[13px] font-medium whitespace-nowrap select-none',
    {
        variants: {
            variant: {
                accent: 'bg-accent2-soft text-accent2-ink dark:text-accent2-ink',
                ok: 'bg-oksoft text-okstrong dark:text-ok',
                warn: 'bg-warnsoft text-warnstrong dark:text-warn',
                err: 'bg-errsoft text-errstrong dark:text-err',
                neutral: 'bg-sunken2 text-t2',
            },
            selected: {
                true: 'bg-accent2-strong text-white',
            },
        },
        defaultVariants: {
            variant: 'neutral',
        },
    },
);

export interface ChipProps
    extends React.HTMLAttributes<HTMLSpanElement>,
        VariantProps<typeof chipVariants> {}

export const Chip = forwardRef<HTMLSpanElement, ChipProps>(
    ({ className, variant, selected, ...props }, ref) => (
        <span
            ref={ref}
            className={cn(chipVariants({ variant, selected }), className)}
            {...props}
        />
    ),
);
Chip.displayName = 'Chip';

export { chipVariants };
