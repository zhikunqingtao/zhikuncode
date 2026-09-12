import { forwardRef } from 'react';
import { cva, type VariantProps } from 'class-variance-authority';
import { cn } from './cn';

/**
 * Kbd 基元：11px mono，bg-surfacev2 + hairline 描边 + rounded-md（6px），
 * 下沿 2px 内阴影（inset -2px，取 hairline 令牌）。
 */
const kbdVariants = cva(
    'inline-flex h-5 min-w-5 items-center justify-center rounded-md border border-hairline bg-surfacev2 px-1.5 font-mono text-[11px] leading-none text-t2 shadow-[inset_0_-2px_0_0_var(--v2-border-hairline)]',
);

export interface KbdProps
    extends React.HTMLAttributes<HTMLElement>,
        VariantProps<typeof kbdVariants> {}

export const Kbd = forwardRef<HTMLElement, KbdProps>(({ className, ...props }, ref) => (
    <kbd ref={ref} className={cn(kbdVariants(), className)} {...props} />
));
Kbd.displayName = 'Kbd';

export { kbdVariants };
