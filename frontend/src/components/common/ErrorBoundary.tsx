/**
 * ErrorBoundary — 全局错误边界
 * SPEC: §8.2.6a.11
 * Class component — React 要求 Error Boundary 必须是 class component
 */

import { Component, ReactNode, ErrorInfo } from 'react';

interface Props {
    children: ReactNode;
    fallback?: ReactNode;
}

interface State {
    hasError: boolean;
    error: Error | null;
}

export class ErrorBoundary extends Component<Props, State> {
    state: State = { hasError: false, error: null };

    static getDerivedStateFromError(error: Error): State {
        return { hasError: true, error };
    }

    componentDidCatch(error: Error, info: ErrorInfo) {
        console.error('ErrorBoundary:', error, info);
    }

    render() {
        if (this.state.hasError) {
            return this.props.fallback || (
                <div className="flex flex-col items-center justify-center h-screen gap-4">
                    <h2 className="text-lg font-semibold">Something went wrong</h2>
                    <p className="text-sm text-[var(--text-secondary)]">
                        {this.state.error?.message}
                    </p>
                    <button
                        onClick={() => this.setState({ hasError: false, error: null })}
                        className="px-4 py-2 rounded bg-[var(--accent)] text-white text-sm"
                    >
                        Try Again
                    </button>
                </div>
            );
        }
        return this.props.children;
    }
}
