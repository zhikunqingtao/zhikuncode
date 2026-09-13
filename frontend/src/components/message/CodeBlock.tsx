/**
 * CodeBlock — 代码语法高亮组件
 *
 * SPEC: §8.2.4D CodeBlockProps
 * 高亮策略 (v1.48.0):
 * - 短代码 (<100行): PrismJS 实时高亮 (react-syntax-highlighter)
 * - 长代码 (≥100行): 默认纯 <pre>，用户可点击手动触发高亮
 *
 * §7.2 代码块：bg-sunken2 + rounded-xl + border-hairline；
 * 头行（三圆点 + 文件名 + 复制 ghost 钮）；
 * 正文 JetBrains Mono 12.5px / 行高 1.7，横向滚动。
 * 语法色走 §4.2 design-tokens 语法表（zkSyntax 从 MONACO_ZK_THEMES 派生，
 * 主题感知；不新增/修改任何色值）。
 */

import React, { useCallback, useMemo, useState } from 'react';
import { Prism as SyntaxHighlighter } from 'react-syntax-highlighter';
import { Copy, Check } from 'lucide-react';
import { useConfigStore } from '@/store/configStore';
import { resolveTheme } from '@/styles/design-tokens';
import { ZK_SYNTAX_STYLES } from '@/styles/zkSyntax';

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

/** §3.8 Code 档位：JetBrains Mono 栈 */
const CODE_FONT_FAMILY = "'JetBrains Mono', ui-monospace, Menlo, monospace";

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
    // 主题感知语法表（§4.2）：configStore 订阅保证主题切换即时重渲染
    const themeMode = useConfigStore(s => s.theme.mode);
    const syntaxStyle = ZK_SYNTAX_STYLES[resolveTheme(themeMode)];

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
                ? { backgroundColor: 'var(--v2-warn-soft)', display: 'block' as const, width: '100%' as const }
                : { display: 'block' as const, width: '100%' as const },
        });
    }, [highlightLines]);

    return (
        <div className="code-block relative rounded-xl border border-hairline bg-sunken2 overflow-hidden">
            {/* Header：三圆点 + 文件名 + 复制 ghost 钮 */}
            <div className="flex items-center gap-2 border-b border-hairline px-3 py-2">
                <span className="flex shrink-0 items-center gap-1.5" aria-hidden="true">
                    <span className="h-2.5 w-2.5 rounded-full bg-err opacity-70" />
                    <span className="h-2.5 w-2.5 rounded-full bg-warn opacity-70" />
                    <span className="h-2.5 w-2.5 rounded-full bg-ok opacity-70" />
                </span>
                <span className="min-w-0 flex-1 truncate font-mono text-[11px] text-t3">
                    {fileName ?? resolvedLang}
                </span>
                <span className="flex shrink-0 items-center gap-1">
                    {isLong && !forceHighlight && (
                        <button
                            onClick={() => setForceHighlight(true)}
                            className="rounded-md px-1.5 py-1 text-[11px] text-t4 transition-colors duration-fast hover:bg-hover2 hover:text-t1"
                        >
                            Enable highlighting
                        </button>
                    )}
                    {copyable && (
                        <button
                            onClick={handleCopy}
                            className="rounded-md p-1 text-t4 transition-colors duration-fast hover:bg-hover2 hover:text-t1"
                            aria-label="Copy code"
                            title={copied ? '已复制' : '复制'}
                        >
                            {copied ? <Check size={14} className="text-ok" /> : <Copy size={14} />}
                        </button>
                    )}
                </span>
            </div>

            {/* Code content：JetBrains Mono 12.5px / 1.7，横向滚动 */}
            <div style={{ maxHeight, overflowY: 'auto' }}>
                {shouldHighlight ? (
                    <SyntaxHighlighter
                        language={resolvedLang}
                        style={syntaxStyle}
                        showLineNumbers={showLineNumbers}
                        wrapLines
                        lineProps={lineProps}
                        customStyle={{
                            margin: 0,
                            padding: '12px 14px',
                            background: 'transparent',
                            fontSize: '12.5px',
                            lineHeight: 1.7,
                            overflowX: 'auto',
                        }}
                        codeTagProps={{
                            style: { fontFamily: CODE_FONT_FAMILY },
                        }}
                    >
                        {code}
                    </SyntaxHighlighter>
                ) : (
                    <pre
                        className="px-3.5 py-3 text-[12.5px] leading-[1.7] text-t1 overflow-x-auto whitespace-pre"
                        style={{ fontFamily: CODE_FONT_FAMILY }}
                    >
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
