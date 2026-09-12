import type { Config } from 'tailwindcss';

export default {
    content: ['./index.html', './src/**/*.{js,ts,jsx,tsx}'],
    darkMode: 'class',
    theme: {
        screens: {
            'sm': '640px',
            'md': '768px',
            'lg': '1024px',
            'xl': '1280px',
            '2xl': '1536px',
        },
        extend: {
            colors: {
                surface: {
                    DEFAULT: 'var(--color-surface)',
                    elevated: 'var(--color-surface-elevated)',
                    sunken: 'var(--color-surface-sunken)',
                },
                primary: {
                    DEFAULT: 'var(--color-primary)',
                    hover: 'var(--color-primary-hover)',
                    active: 'var(--color-primary-active)',
                },
                accent: {
                    DEFAULT: 'var(--color-accent)',
                    muted: 'var(--color-accent-muted)',
                },
                danger: { DEFAULT: '#ef4444', muted: '#991b1b' },
                warning: { DEFAULT: '#eab308', muted: '#854d0e' },
                success: { DEFAULT: '#22c55e', muted: '#166534' },
                muted: 'var(--color-muted)',
                border: 'var(--color-border)',
                /* ===== v2 令牌映射（新增 key，不覆盖默认） ===== */
                app2: 'var(--v2-bg-app)',
                /* P1a 追加：--v2-bg-surface 独立 key（P0 未映射，为保零视觉变化不重映射旧 surface） */
                surfacev2: 'var(--v2-bg-surface)',
                surface2: 'var(--v2-bg-surface-2)',
                sunken2: 'var(--v2-bg-sunken)',
                hover2: 'var(--v2-bg-hover)',
                active2: 'var(--v2-bg-active)',
                t1: 'var(--v2-text-1)',
                t2: 'var(--v2-text-2)',
                t3: 'var(--v2-text-3)',
                t4: 'var(--v2-text-4)',
                hairline: 'var(--v2-border-hairline)',
                overlay2: 'var(--v2-overlay)',
                accent2: {
                    DEFAULT: 'var(--v2-accent)',
                    strong: 'var(--v2-accent-strong)',
                    hover: 'var(--v2-accent-hover)',
                    active: 'var(--v2-accent-active)',
                    soft: 'var(--v2-accent-soft)',
                    ring: 'var(--v2-accent-ring)',
                },
                ok: 'var(--v2-ok)',
                oksoft: 'var(--v2-ok-soft)',
                okstrong: 'var(--v2-ok-strong)',
                err: 'var(--v2-err)',
                errsoft: 'var(--v2-err-soft)',
                errstrong: 'var(--v2-err-strong)',
                warn: 'var(--v2-warn)',
                warnsoft: 'var(--v2-warn-soft)',
                warnstrong: 'var(--v2-warn-strong)',
            },
            borderRadius: {
                panel: 'var(--v2-r-panel)',
            },
            boxShadow: {
                e1: 'var(--v2-shadow-xs)',
                e2: 'var(--v2-shadow-sm)',
                e3: 'var(--v2-shadow-md)',
                e4: 'var(--v2-shadow-lg)',
                well: 'var(--v2-shadow-inset)',
                soft: 'var(--v2-shadow-soft)',
                'soft-sm': 'var(--v2-shadow-soft-sm)',
            },
            transitionProperty: {
                interactive: 'color, background-color, border-color, box-shadow, transform, opacity',
                surface: 'box-shadow, border-color, background-color',
                pop: 'opacity, transform',
            },
            transitionDuration: {
                fast: '120ms',
                base: '180ms',
                slow: '240ms',
                sheet: '320ms',
            },
            transitionTimingFunction: {
                soft: 'cubic-bezier(.2,.8,.2,1)',
                spring: 'cubic-bezier(.34,1.56,.64,1)',
            },
            fontFamily: {
                sans: ['Inter', 'system-ui', 'sans-serif'],
                mono: ["'JetBrains Mono'", "'Fira Code'", 'monospace'],
            },
            animation: {
                'shimmer': 'shimmer 2s linear infinite',
                'pulse-slow': 'pulse 3s ease-in-out infinite',
                'slide-up': 'slideUp 0.2s ease-out',
                /* v2 动效（slide-up 与上方现有 key 同值合并，不重复定义） */
                'fade-in': 'fadeIn .18s cubic-bezier(.2,.8,.2,1)',
                'scale-in': 'scaleIn .24s cubic-bezier(.2,.8,.2,1)',
                'sheet-up': 'sheetUp .32s cubic-bezier(.2,.8,.2,1)',
                'accent-pulse': 'accentPulse 1.6s ease-in-out infinite',
                'indeterminate': 'indeterminate 1.4s ease-in-out infinite',
            },
        },
    },
    plugins: [
        require('@tailwindcss/typography'),
    ],
} satisfies Config;
