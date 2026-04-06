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
            },
            fontFamily: {
                sans: ['Inter', 'system-ui', 'sans-serif'],
                mono: ["'JetBrains Mono'", "'Fira Code'", 'monospace'],
            },
            animation: {
                'shimmer': 'shimmer 2s linear infinite',
                'pulse-slow': 'pulse 3s ease-in-out infinite',
                'slide-up': 'slideUp 0.2s ease-out',
            },
        },
    },
    plugins: [
        require('@tailwindcss/typography'),
    ],
} satisfies Config;
