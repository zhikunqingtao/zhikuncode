import { forwardRef } from 'react';
import { cva, type VariantProps } from 'class-variance-authority';
import { cn } from './cn';

/**
 * EmptyState 基元（§7.1 空态 Hero 配方）：
 * - hero：徽章位（badge）+ 大标题（clamp 字阶、300 细字重，<b> = 650 + accent 关键词）
 *   + 副文案 + 动作区，垂直居中、max-width 560px
 * - compact：图标 + 标题 + 说明
 */
const emptyStateVariants = cva('flex flex-col items-center justify-center text-center', {
    variants: {
        variant: {
            hero: 'mx-auto w-full max-w-[560px] gap-4 px-6 py-12',
            compact: 'gap-2 px-4 py-8',
        },
    },
    defaultVariants: {
        variant: 'hero',
    },
});

export interface EmptyStateProps
    extends Omit<React.HTMLAttributes<HTMLDivElement>, 'title'>,
        VariantProps<typeof emptyStateVariants> {
    /** hero：顶部徽章位（如 accent-soft 胶囊 + 呼吸点） */
    badge?: React.ReactNode;
    /** hero 大标题 / compact 标题；hero 内联 <b> 自动渲染为 650 + accent */
    title: React.ReactNode;
    /** hero 副文案 / compact 说明 */
    description?: React.ReactNode;
    /** hero 动作区（按钮/快捷 chips） */
    actions?: React.ReactNode;
    /** compact 图标位 */
    icon?: React.ReactNode;
}

export const EmptyState = forwardRef<HTMLDivElement, EmptyStateProps>(
    ({ className, variant, badge, title, description, actions, icon, ...props }, ref) => {
        if (variant === 'compact') {
            return (
                <div
                    ref={ref}
                    className={cn(emptyStateVariants({ variant }), className)}
                    {...props}
                >
                    {icon !== undefined && (
                        <div className="flex h-10 w-10 items-center justify-center rounded-2xl bg-sunken2 text-t3 [&_svg]:h-5 [&_svg]:w-5">
                            {icon}
                        </div>
                    )}
                    <p className="text-sm font-medium text-t1">{title}</p>
                    {description !== undefined && (
                        <p className="max-w-[320px] text-xs leading-normal text-t3">{description}</p>
                    )}
                    {actions !== undefined && (
                        <div className="mt-1 flex items-center gap-2">{actions}</div>
                    )}
                </div>
            );
        }

        return (
            <div ref={ref} className={cn(emptyStateVariants({ variant }), className)} {...props}>
                {badge !== undefined && <div>{badge}</div>}
                <h1 className="text-[clamp(34px,5.4vw,50px)] font-light leading-[1.15] tracking-[-0.02em] text-t1 [&_b]:font-semibold [&_b]:text-accent2">
                    {title}
                </h1>
                {description !== undefined && (
                    <p className="max-w-[420px] text-sm leading-[1.7] text-t3">{description}</p>
                )}
                {actions !== undefined && (
                    <div className="mt-2 flex flex-wrap items-center justify-center gap-2">
                        {actions}
                    </div>
                )}
            </div>
        );
    },
);
EmptyState.displayName = 'EmptyState';

export { emptyStateVariants };
