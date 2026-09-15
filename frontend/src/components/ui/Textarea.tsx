import { forwardRef } from 'react';
import { cva, type VariantProps } from 'class-variance-authority';
import { cn } from './cn';

/**
 * Textarea 基元：与 Input 同配方（凹陷井 + 3px accent focus ring），多行自适应由消费方控制。
 */
const textareaVariants = cva(
    'w-full min-h-[80px] px-3 py-2 rounded-[10px] bg-sunken2 shadow-well border border-transparent text-sm text-t1 placeholder:text-t4 leading-relaxed transition-surface duration-fast focus:outline-none focus:ring-[3px] focus:ring-accent2-ring disabled:opacity-50 disabled:pointer-events-none',
    {
        variants: {
            error: {
                true: 'border-err text-t1 focus:ring-err',
            },
        },
    },
);

export interface TextareaProps
    extends React.TextareaHTMLAttributes<HTMLTextAreaElement>,
        VariantProps<typeof textareaVariants> {}

export const Textarea = forwardRef<HTMLTextAreaElement, TextareaProps>(
    ({ className, error, ...props }, ref) => (
        <textarea
            ref={ref}
            className={cn(textareaVariants({ error }), className)}
            aria-invalid={error || undefined}
            {...props}
        />
    ),
);
Textarea.displayName = 'Textarea';

export { textareaVariants };
