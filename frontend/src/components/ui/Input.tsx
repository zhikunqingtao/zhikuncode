import { forwardRef } from 'react';
import { cva, type VariantProps } from 'class-variance-authority';
import { cn } from './cn';

/**
 * Input 基元：凹陷井（bg-sunken2 + shadow-well + rounded-[10px]）。
 * focus：3px accent ring（§6.2 focus-visible）；error 变体换 err 边 + err ring。
 */
const inputVariants = cva(
    'w-full h-9 max-md:min-h-11 px-3 rounded-[10px] bg-sunken2 shadow-well border border-[var(--v2-text-3)] text-sm text-t1 placeholder:text-t4 transition-surface duration-fast focus:outline-none focus:ring-[3px] focus:ring-accent2-ring disabled:opacity-50 disabled:pointer-events-none',
    {
        variants: {
            error: {
                true: 'border-err text-t1 focus:ring-err',
            },
        },
    },
);

export interface InputProps
    extends Omit<React.InputHTMLAttributes<HTMLInputElement>, 'size'>,
        VariantProps<typeof inputVariants> {}

export const Input = forwardRef<HTMLInputElement, InputProps>(
    ({ className, error, ...props }, ref) => (
        <input
            ref={ref}
            className={cn(inputVariants({ error }), className)}
            aria-invalid={error || undefined}
            {...props}
        />
    ),
);
Input.displayName = 'Input';

export { inputVariants };
