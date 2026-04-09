/**
 * StreamingText — 独立流式文本渲染组件
 * SPEC: §4.2 流式渲染性能优化
 *
 * 仅订阅 streamingStore，避免其他消息组件不必要的重渲染。
 */

import React from 'react';
import { useStreamingText } from '@/hooks/useStreamingText';

export const StreamingText: React.FC = () => {
    const text = useStreamingText();
    if (!text) return null;
    return (
        <div className="animate-pulse-subtle">
            <pre className="whitespace-pre-wrap text-sm">{text}</pre>
            <span className="inline-block w-2 h-4 bg-blue-500 animate-pulse ml-0.5" />
        </div>
    );
};
