/**
 * TextBlock — Markdown 文本渲染组件
 *
 * SPEC: §8.2.4D 消息渲染管线
 * 使用 react-markdown 渲染 Markdown 内容，代码块使用 CodeBlock 语法高亮。
 * 支持流式更新 (streaming) 时附加闪烁光标。
 */

import React, { useEffect, useMemo, useState } from 'react';
import ReactMarkdown, { defaultUrlTransform } from 'react-markdown';
import type { Components, UrlTransform } from 'react-markdown';
import remarkGfm from 'remark-gfm';
import type { ElementContent } from 'hast';
import CodeBlock from './CodeBlock';
import ImageBlock from './ImageBlock';
import MermaidBlock from '../visualization/shared/MermaidBlock';
import { useSessionStore } from '@/store/sessionStore';

interface TextBlockProps {
    text: string;
    streaming?: boolean;
}

/**
 * 判定 src 是否可由浏览器直接加载（远程 / 内联资源）。
 * 其余形式（file://、本地绝对路径、相对路径）一律视为工作区文件，
 * 必须经后端 preview 端点鉴权加载，任何时刻不得直接交给 <img>。
 */
function isBrowserLoadableSrc(src: string): boolean {
    return /^(https?:|data:|blob:)/i.test(src) || src.startsWith('//');
}

/**
 * 从 img src 解析出可提交给 preview 端点的工作区路径。
 * mdast → hast 转换会对非 ASCII 字符做百分号编码，需先解码，
 * 否则拼查询参数时二次编码，后端会按字面 %xx 文件名查找导致 404。
 * 相对路径原样提交，后端会基于工作区根目录解析。
 */
function toWorkspacePath(src: string): string {
    let path = src;
    if (path.startsWith('file://')) {
        try {
            return decodeURIComponent(new URL(path).pathname);
        } catch {
            path = path.slice('file://'.length);
        }
    }
    try {
        return decodeURIComponent(path);
    } catch {
        // 含非法 % 序列（如文件名自带 %）时按原样提交
        return path;
    }
}

/**
 * Markdown 图片渲染：工作区文件（file:// / 绝对路径 / 相对路径）
 * 通过后端 preview 端点（需 X-Session-Id 鉴权）以 fetch + objectURL
 * 方式加载（与 FilePreviewDialog 一致）；http(s)/data:/blob: 直接交给 ImageBlock。
 * fetch 完成前只渲染 loading 骨架，原始本地路径永不作为 <img> src。
 */
const MarkdownImage: React.FC<{ src?: string; alt?: string }> = ({ src, alt }) => {
    const sessionId = useSessionStore(s => s.sessionId);
    const browserLoadable = !!src && isBrowserLoadableSrc(src);
    const workspacePath = src && !browserLoadable ? toWorkspacePath(src) : null;
    const [objectUrl, setObjectUrl] = useState<string | null>(null);
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        if (!workspacePath || !sessionId) return;
        const controller = new AbortController();
        let url: string | null = null;
        setObjectUrl(null);
        setError(null);
        void fetch(
            `/api/sessions/${encodeURIComponent(sessionId)}/files/preview?path=${encodeURIComponent(workspacePath)}`,
            { headers: { 'X-Session-Id': sessionId }, signal: controller.signal },
        )
            .then(async response => {
                if (!response.ok) throw new Error(`HTTP ${response.status}`);
                const blob = await response.blob();
                if (controller.signal.aborted) return;
                url = URL.createObjectURL(blob);
                setObjectUrl(url);
            })
            .catch(fetchError => {
                if (!controller.signal.aborted) {
                    setError(fetchError instanceof Error ? fetchError.message : String(fetchError));
                }
            });
        // 卸载 / 路径变化时 revoke objectURL，避免虚拟滚动反复挂载卸载时 Blob 泄漏
        return () => {
            controller.abort();
            if (url) URL.revokeObjectURL(url);
        };
    }, [workspacePath, sessionId]);

    if (!src) return null;

    // http(s):// 与 data:image/... 等浏览器可直接加载的 src：直接渲染（含 lightbox）
    if (browserLoadable) {
        return <ImageBlock src={src} alt={alt} />;
    }

    if (error || !sessionId) {
        const forbidden = error?.includes('403');
        return (
            <span className="my-2 inline-flex items-center rounded-lg border border-amber-500/30 bg-amber-500/5 px-3 py-2 text-sm text-amber-400">
                {forbidden ? '图片不在当前工作区范围内' : `图片加载失败 (${error ?? '无会话'})`}
                {workspacePath && (
                    <span className="ml-2 text-xs text-[var(--text-muted)]">{workspacePath}</span>
                )}
            </span>
        );
    }

    if (!objectUrl) {
        return (
            <span className="my-2 inline-block h-32 w-48 animate-pulse rounded-lg border border-[var(--border)] bg-[var(--bg-secondary)]" />
        );
    }

    return <ImageBlock src={objectUrl} alt={alt} />;
};

/**
 * URL 安全过滤：仅对 img src 放行 file:// 协议（交由 MarkdownImage 处理），
 * 其余场景沿用 react-markdown 的 defaultUrlTransform（本地绝对路径 `/...`
 * 无协议前缀，默认即放行）。不整体禁用安全过滤。
 */
const urlTransform: UrlTransform = (url, key) => {
    if (key === 'src' && url.startsWith('file://')) return url;
    return defaultUrlTransform(url);
};

/**
 * 递归判定 hast 子树中是否含 img 元素（覆盖链接包裹图片等
 * p > a > img 嵌套结构，仅查直接子节点会漏检）。
 */
function containsImageElement(children: ElementContent[]): boolean {
    return children.some(child =>
        child.type === 'element'
        && (child.tagName === 'img' || containsImageElement(child.children)),
    );
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
        // Paragraphs：含图片的段落改用 <div> 输出，避免 MarkdownImage/ImageBlock
        // 的块级容器造成 p > div 非法嵌套（validateDOMNesting 告警）
        p: ({ node, children }) => {
            const hasImage = node ? containsImageElement(node.children) : false;
            if (hasImage) {
                return <div className="my-2 leading-relaxed">{children}</div>;
            }
            return <p className="my-2 leading-relaxed">{children}</p>;
        },
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
        // Images: 本地绝对路径 / file:// 走后端 preview 端点，其余默认渲染
        img: ({ src, alt }) => <MarkdownImage src={src} alt={alt} />,
    }), []);

    return (
        <div className="text-block max-w-none text-sm text-[var(--text-primary)] leading-relaxed">
            <ReactMarkdown remarkPlugins={[remarkGfm]} components={components} urlTransform={urlTransform}>{text}</ReactMarkdown>
            {streaming && (
                <span className="inline-block w-2 h-4 ml-0.5 bg-blue-400 animate-pulse rounded-sm" />
            )}
        </div>
    );
};

export default React.memo(TextBlock);
