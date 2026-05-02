/**
 * TextBlock — Markdown 文本渲染组件
 *
 * SPEC: §8.2.4D 消息渲染管线
 * 使用 react-markdown 渲染 Markdown 内容，代码块使用 CodeBlock 语法高亮。
 * 支持流式更新 (streaming) 时附加闪烁光标。
 */

import React, { useMemo } from 'react';
import ReactMarkdown from 'react-markdown';
import type { Components } from 'react-markdown';
import CodeBlock from './CodeBlock';
import MermaidBlock from '../visualization/shared/MermaidBlock';

interface TextBlockProps {
    text: string;
    streaming?: boolean;
}

const TextBlock: React.FC<TextBlockProps> = ({ text, streaming = false }) => {
    const components: Components = useMemo(() => ({
        code({ className, children, ...props }) {
            const match = /language-(\w+)/.exec(className ?? '');
            const codeStr = String(children).replace(/\n$/, '');
            const lang = match?.[1];

            // Inline code
            if (!match && !codeStr.includes('\n')) {
                return (
                    <code
                        className="px-1.5 py-0.5 rounded bg-[var(--code-bg)] text-sm font-mono text-[var(--text-primary)]"
                        {...props}
                    >
                        {children}
                    </code>
                );
            }

            // Mermaid diagram
            if (lang === 'mermaid') {
                return <MermaidBlock code={codeStr} />;
            }

            // Fenced code block
            return <CodeBlock code={codeStr} language={lang} />;
        },
        // Headings
        h1: ({ children }) => <h1 className="text-2xl font-bold mt-6 mb-3">{children}</h1>,
        h2: ({ children }) => <h2 className="text-xl font-bold mt-5 mb-2">{children}</h2>,
        h3: ({ children }) => <h3 className="text-lg font-semibold mt-4 mb-2">{children}</h3>,
        // Paragraphs
        p: ({ children }) => <p className="my-2 leading-relaxed">{children}</p>,
        // Lists
        ul: ({ children }) => <ul className="list-disc pl-6 my-2 space-y-1">{children}</ul>,
        ol: ({ children }) => <ol className="list-decimal pl-6 my-2 space-y-1">{children}</ol>,
        li: ({ children }) => <li className="leading-relaxed">{children}</li>,
        // Blockquotes
        blockquote: ({ children }) => (
            <blockquote className="border-l-4 border-blue-500 pl-4 my-3 text-[var(--text-secondary)] italic">
                {children}
            </blockquote>
        ),
        // Links
        a: ({ href, children }) => (
            <a
                href={href}
                target="_blank"
                rel="noopener noreferrer"
                className="text-blue-400 hover:text-blue-300 underline"
            >
                {children}
            </a>
        ),
        // Tables
        table: ({ children }) => (
            <div className="overflow-x-auto my-3">
                <table className="min-w-full border-collapse border border-[var(--border)] text-sm">
                    {children}
                </table>
            </div>
        ),
        th: ({ children }) => (
            <th className="border border-[var(--border)] px-3 py-2 bg-[var(--bg-secondary)] text-left font-semibold">
                {children}
            </th>
        ),
        td: ({ children }) => (
            <td className="border border-[var(--border)] px-3 py-2">{children}</td>
        ),
        // Horizontal rule
        hr: () => <hr className="my-4 border-[var(--border)]" />,
        // Strong / Em
        strong: ({ children }) => <strong className="font-bold">{children}</strong>,
        em: ({ children }) => <em className="italic">{children}</em>,
    }), []);

    return (
        <div className="text-block max-w-none text-sm text-[var(--text-primary)] leading-relaxed">
            <ReactMarkdown components={components}>{text}</ReactMarkdown>
            {streaming && (
                <span className="inline-block w-2 h-4 ml-0.5 bg-blue-400 animate-pulse rounded-sm" />
            )}
        </div>
    );
};

export default React.memo(TextBlock);
