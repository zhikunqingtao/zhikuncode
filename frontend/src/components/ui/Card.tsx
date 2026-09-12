import { forwardRef } from 'react';
import { cva, type VariantProps } from 'class-variance-authority';
import { cn } from './cn';

/**
 * Card 基元：bg-surfacev2 + rounded-2xl + shadow-e2 + hairline 描边。
 * interactive：可点变体，hover 升 shadow-e3（§6.2 hover 升一档）。
 * selected：accent2-soft 底 + 左侧 2px accent 内嵌条（box-shadow inset 实现，保留 e2 外影）。
 */
const cardVariants = cva('bg-surfacev2 rounded-2xl shadow-e2 border border-hairline', {
    variants: {
        interactive: {
            true: 'cursor-pointer transition-surface duration-fast hover:shadow-e3',
        },
        selected: {
            true: 'bg-accent2-soft shadow-[inset_2px_0_0_0_var(--v2-accent),var(--v2-shadow-sm)]',
        },
    },
});

export interface CardProps
    extends React.HTMLAttributes<HTMLDivElement>,
        VariantProps<typeof cardVariants> {}

export const Card = forwardRef<HTMLDivElement, CardProps>(
    ({ className, interactive, selected, ...props }, ref) => (
        <div
            ref={ref}
            className={cn(cardVariants({ interactive, selected }), className)}
            {...props}
        />
    ),
);
Card.displayName = 'Card';

export { cardVariants };
