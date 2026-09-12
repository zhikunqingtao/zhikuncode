import { forwardRef } from 'react';
import { cva, type VariantProps } from 'class-variance-authority';
import { cn } from './cn';

const spinnerVariants = cva(
    'inline-block shrink-0 rounded-full border-2 border-accent2-soft border-t-accent2 animate-spin',
    {
        variants: {
            size: {
                sm: 'h-3.5 w-3.5',
                md: 'h-5 w-5',
                lg: 'h-7 w-7',
            },
        },
        defaultVariants: {
            size: 'md',
        },
    },
);

export interface SpinnerProps
    extends React.HTMLAttributes<HTMLSpanElement>,
        VariantProps<typeof spinnerVariants> {
    /** 屏幕阅读器朗读文案，默认“加载中” */
    label?: string;
}

export const Spinner = forwardRef<HTMLSpanElement, SpinnerProps>(
    ({ className, size, label = '加载中', ...props }, ref) => (
        <span
            ref={ref}
            role="status"
            aria-label={label}
            className={cn(spinnerVariants({ size }), className)}
            {...props}
        />
    ),
);
Spinner.displayName = 'Spinner';

export { spinnerVariants };
