import { forwardRef } from 'react';
import { cva, type VariantProps } from 'class-variance-authority';
import { cn } from './cn';
import { Spinner } from './Spinner';

/**
 * 指南 §6.3 cva 骨架（严格照抄 base / variant / size / iconOnly / defaultVariants）。
 * 唯一偏差：secondary 的 `bg-surface` 改为 `bg-surfacev2`
 * （--v2-bg-surface 在 P0 无 Tailwind key，P1a 增量追加 surfacev2 映射）。
 */
const buttonVariants = cva(
    'inline-flex items-center justify-center gap-2 rounded-xl font-medium select-none transition-interactive duration-fast ease-out focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-accent2-ring disabled:opacity-50 disabled:pointer-events-none active:scale-[.98]',
    {
        variants: {
            variant: {
                primary:
                    'bg-accent2-strong text-white shadow-e1 hover:bg-accent2-hover hover:shadow-e2',
                secondary:
                    'bg-surfacev2 text-t1 border border-hairline shadow-e1 hover:bg-hover2 hover:shadow-e2',
                ghost: 'text-t2 hover:bg-hover2 hover:text-t1',
                danger: 'bg-err text-white shadow-e1 hover:opacity-90',
            },
            size: {
                sm: 'h-8 px-3 text-xs',
                md: 'h-9 px-4 text-sm',
                /* 移动端命中区 ≥44px，桌面恢复 40px */
                lg: 'h-10 px-5 text-sm min-h-11 md:min-h-0',
            },
            iconOnly: {
                true: 'px-0 aspect-square',
            },
        },
        defaultVariants: {
            variant: 'secondary',
            size: 'md',
        },
    },
);

export interface ButtonProps
    extends React.ButtonHTMLAttributes<HTMLButtonElement>,
        VariantProps<typeof buttonVariants> {
    /** loading：Spinner 替换 children + 禁用 + aria-busy（§6.2 loading 态） */
    loading?: boolean;
}

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(
    (
        {
            className,
            variant,
            size,
            iconOnly,
            loading = false,
            disabled,
            children,
            type = 'button',
            ...props
        },
        ref,
    ) => (
        <button
            ref={ref}
            type={type}
            className={cn(buttonVariants({ variant, size, iconOnly }), className)}
            disabled={disabled || loading}
            aria-busy={loading || undefined}
            {...props}
        >
            {loading ? <Spinner size="sm" /> : children}
        </button>
    ),
);
Button.displayName = 'Button';

export { buttonVariants };
