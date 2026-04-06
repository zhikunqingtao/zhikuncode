/**
 * CodeBlock — 代码语法高亮组件
 *
 * SPEC: §8.2.4D CodeBlockProps
 * 高亮策略 (v1.48.0):
 * - 短代码 (<100行): PrismJS 实时高亮 (react-syntax-highlighter)
 * - 长代码 (≥100行): 默认纯 <pre>，用户可点击手动触发高亮
 */

import React, { useCallback, useMemo, useState } from 'react';
import { Prism as SyntaxHighlighter } from 'react-syntax-highlighter';
import { oneDark } from 'react-syntax-highlighter/dist/esm/styles/prism';
import { Copy, Check } from 'lucide-react';

interface CodeBlockProps {
    code: string;
    language?: string;
    fileName?: string;
    showLineNumbers?: boolean;
    highlightLines?: number[];
    maxHeight?: number;
    copyable?: boolean;
}

const LONG_CODE_THRESHOLD = 100;

const CodeBlock: React.FC<CodeBlockProps> = ({
    code,
    language,
    fileName,
    showLineNumbers = true,
    highlightLines,
    maxHeight = 500,
    copyable = true,
}) => {
    const [copied, setCopied] = useState(false);
    const [forceHighlight, setForceHighlight] = useState(false);

    const resolvedLang = useMemo(
        () => language ?? inferLanguage(fileName) ?? 'text',
        [language, fileName],
    );

    const lineCount = useMemo(() => code.split('\n').length, [code]);
    const isLong = lineCount >= LONG_CODE_THRESHOLD;
    const shouldHighlight = !isLong || forceHighlight;

    const handleCopy = useCallback(async () => {
        await navigator.clipboard.writeText(code);
        setCopied(true);
        setTimeout(() => setCopied(false), 2000);
    }, [code]);

    const lineProps = useMemo(() => {
        if (!highlightLines || highlightLines.length === 0) return undefined;
        const set = new Set(highlightLines);
        return (lineNumber: number) => ({
            style: set.has(lineNumber)
                ? { backgroundColor: 'rgba(255,255,0,0.1)', display: 'block' as const, width: '100%' as const }
                : { display: 'block' as const, width: '100%' as const },
        });
    }, [highlightLines]);

    return (
        <div className="code-block relative rounded-lg border border-gray-700 bg-gray-900 overflow-hidden">
            {/* Header */}
            <div className="flex items-center justify-between px-4 py-2 bg-gray-800 border-b border-gray-700 text-xs text-gray-400">
                <span>{fileName ?? resolvedLang}</span>
                <div className="flex items-center gap-2">
                    {isLong && !forceHighlight && (
                        <button
                            onClick={() => setForceHighlight(true)}
                            className="hover:text-gray-200 transition-colors"
                        >
                            Enable highlighting
                        </button>
                    )}
                    {copyable && (
                        <button
                            onClick={handleCopy}
                            className="hover:text-gray-200 transition-colors"
                            aria-label="Copy code"
                        >
                            {copied ? <Check size={14} /> : <Copy size={14} />}
                        </button>
                    )}
                </div>
            </div>

            {/* Code content */}
            <div style={{ maxHeight, overflowY: 'auto' }}>
                {shouldHighlight ? (
                    <SyntaxHighlighter
                        language={resolvedLang}
                        style={oneDark}
                        showLineNumbers={showLineNumbers}
                        wrapLines
                        lineProps={lineProps}
                        customStyle={{
                            margin: 0,
                            padding: '1rem',
                            background: 'transparent',
                            fontSize: '13px',
                        }}
                    >
                        {code}
                    </SyntaxHighlighter>
                ) : (
                    <pre className="p-4 text-sm text-gray-300 overflow-x-auto whitespace-pre">
                        {code}
                    </pre>
                )}
            </div>
        </div>
    );
};

/** Infer language from file extension */
function inferLanguage(fileName?: string): string | undefined {
    if (!fileName) return undefined;
    const ext = fileName.split('.').pop()?.toLowerCase();
    const map: Record<string, string> = {
        ts: 'typescript', tsx: 'tsx', js: 'javascript', jsx: 'jsx',
        py: 'python', java: 'java', rs: 'rust', go: 'go',
        rb: 'ruby', sh: 'bash', zsh: 'bash', bash: 'bash',
        json: 'json', yaml: 'yaml', yml: 'yaml', toml: 'toml',
        md: 'markdown', css: 'css', scss: 'scss', html: 'html',
        xml: 'xml', sql: 'sql', kt: 'kotlin', swift: 'swift',
        c: 'c', cpp: 'cpp', h: 'c', hpp: 'cpp',
        dockerfile: 'dockerfile', makefile: 'makefile',
    };
    return ext ? map[ext] : undefined;
}

export default React.memo(CodeBlock);
