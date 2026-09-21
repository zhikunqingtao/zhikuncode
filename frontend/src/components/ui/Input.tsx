import { forwardRef } from 'react';
import { cva, type VariantProps } from 'class-variance-authority';
import { cn } from './cn';

/**
 * Input 基元：凹陷井（bg-sunken2 + shadow-pressed + rounded-[10px]）。
 * 内凹井（bg-sunken2 + shadow-pressed + hairline 边）；focus：accent 边 + 3px accent ring；error 变体换 err 边 + err ring。
 */
const inputVariants = cva(
    'w-full h-9 max-md:min-h-11 px-3 rounded-[10px] bg-sunken2 shadow-pressed border border-hairline text-sm text-t1 placeholder:text-t4 transition-surface duration-fast hover:border-[var(--v2-border-strong)] focus:outline-none focus:border-accent2 focus:ring-[3px] focus:ring-accent2-ring disabled:opacity-50 disabled:pointer-events-none',
    {
        variants: {
            error: {
                true: 'border-err text-t1 focus:border-err focus:ring-err',
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
