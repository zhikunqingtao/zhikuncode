import logo from '@/assets/logo.svg';

/** Decorative brand mark; the adjacent brand name supplies its accessible label. */
export function BrandLogo({ className = 'h-8 w-8' }: { className?: string }) {
    return <img src={logo} alt="" aria-hidden="true" draggable={false} className={`shrink-0 object-contain ${className}`} />;
}
