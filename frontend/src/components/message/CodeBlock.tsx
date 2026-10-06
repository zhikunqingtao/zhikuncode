/**
 * CodeBlock — 代码语法高亮组件
 *
 * SPEC: §8.2.4D CodeBlockProps
 * 高亮策略 (v1.48.0):
 * - 短代码 (<100行): PrismJS 实时高亮 (react-syntax-highlighter)
 * - 长代码 (≥100行): 默认纯 <pre>，用户可点击手动触发高亮
 *
 * §7.2 代码块：bg-sunken2 + rounded-xl + border-hairline；
 * 头行（文件名或语言 + 复制 ghost 钮）；
 * 正文系统等宽字体 13px（手机 14px）/ 行高 1.65，横向滚动。
 * 语法色由 zkSyntax 按主题提供：默认浅深色沿用 §4.2 语法表，
 * ink 双主题与 jelly（黑巧丝绒）使用针对代码块背景校准的专属色板。
 */

import React, { useCallback, useMemo, useState } from 'react';
import { Prism as SyntaxHighlighter } from 'react-syntax-highlighter';
import { Copy, Check } from 'lucide-react';
import { useConfigStore } from '@/store/configStore';
import { resolveZkSyntaxStyle } from '@/styles/zkSyntax';

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

/** 本地系统等宽字体，不依赖远程字体。 */
const CODE_FONT_FAMILY = "ui-monospace, SFMono-Regular, Menlo, Consolas, monospace";

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
    // 主题感知语法表（§4.2）：ink 双主题直达天宫色板，其余归一 light/dark；
    // configStore 订阅保证主题切换即时重渲染
    const themeMode = useConfigStore(s => s.theme.mode);
    const syntaxStyle = resolveZkSyntaxStyle(themeMode);
    // 深区代码块（ink 双主题 / jelly 黑巧丝绒）：块内使用本地色域，标题、控件和纯文本
    // 一并跟随代码块自身背景（黑巧底上不能继承外层深色文字，否则不可读）
    const deepCodeTheme = themeMode === 'ink-havoc' || themeMode === 'ink-havoc-night' || themeMode === 'jelly';
    const inkColors = deepCodeTheme ? {
        '--code-ink-text': syntaxStyle['pre[class*="language-"]'].color,
        '--code-ink-muted': syntaxStyle.comment.color,
        color: syntaxStyle['pre[class*="language-"]'].color,
    } as React.CSSProperties : undefined;
    const controlColors = deepCodeTheme
        ? 'text-[color:var(--code-ink-muted)] hover:text-[color:var(--code-ink-text)]'
        : 'text-t4 hover:text-t1';

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
        <div className="code-block relative rounded-[10px] border border-hairline bg-sunken2 overflow-hidden" style={inkColors}>
            {/* Header：文件名或语言 + 复制 */}
            <div className="flex items-center gap-2 border-b border-hairline px-3 py-1.5">
                <span className={`min-w-0 flex-1 truncate font-mono text-[13px] ${deepCodeTheme ? 'text-[color:var(--code-ink-muted)]' : 'text-t3'}`}>
                    {fileName ?? resolvedLang}
                </span>
                <span className="flex shrink-0 items-center gap-1">
                    {isLong && !forceHighlight && (
                        <button
                            onClick={() => setForceHighlight(true)}
                            className={`panel-control rounded-md px-1.5 py-1 text-[13px] transition-colors duration-fast hover:bg-hover2 ${controlColors}`}
                        >
                            Enable highlighting
                        </button>
                    )}
                    {copyable && (
                        <button
                            onClick={handleCopy}
                            className={`panel-control rounded-md p-1 transition-colors duration-fast hover:bg-hover2 ${controlColors}`}
                            aria-label="Copy code"
                            title={copied ? '已复制' : '复制'}
                        >
                            {copied ? <Check size={14} className="text-ok" style={deepCodeTheme ? { color: syntaxStyle.string.color } : undefined} /> : <Copy size={14} />}
                        </button>
                    )}
                </span>
            </div>

            {/* 共享响应式字号，保留横向滚动和长代码纯文本回退。 */}
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
                            fontSize: 'var(--code-font-size)',
                            fontFamily: CODE_FONT_FAMILY,
                            lineHeight: 'var(--code-line-height)',
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
                        className={`px-3.5 py-3 overflow-x-auto whitespace-pre ${deepCodeTheme ? 'text-[color:var(--code-ink-text)]' : 'text-t1'}`}
                        style={{ fontFamily: CODE_FONT_FAMILY, fontSize: 'var(--code-font-size)', lineHeight: 'var(--code-line-height)' }}
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
