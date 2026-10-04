import { useState } from 'react';
import { createRoot } from 'react-dom/client';
import UserMessage from '../../src/components/message/UserMessage';
import CodeBlock from '../../src/components/message/CodeBlock';
import { applyAccent, DEFAULT_ACCENT_HEX } from '../../src/theme/accents';
import { useConfigStore } from '@/store/configStore';

// Only store adapters are replaced by the offline runner. All message renderers,
// Mermaid, Prism and CSS are the production implementations.
const prose = [
    '普通正文 [链接文字](https://example.invalid) 和 `inline code`。',
    '### 三级标题',
    '> 引用 [引用链接](https://example.invalid) 和 `quoted code`',
    '- 嵌套列表\n  - 第二层 `nested code`',
    '- [x] 已完成\n- [ ] 待完成',
].join('\n\n');
const table = '| [表头链接](https://example.invalid) | `表头代码` |\n| --- | --- |\n| 项目A [数据链接](https://example.invalid) | `cell code` |';
const source = (lines: number) => Array.from({ length: lines }, (_, i) =>
    i === 0 ? '// A readable explanation' : `const value${i} = "hello";`).join('\n');
const fence = (text: string, lang = 'javascript') => `\`\`\`${lang}\n${text}\n\`\`\``;
const image = document.createElement('canvas');
image.width = 160;
image.height = 100;
const imageContext = image.getContext('2d')!;
imageContext.fillStyle = '#778899';
imageContext.fillRect(0, 0, image.width, image.height);
const png = image.toDataURL('image/png').split(',')[1];
const messages = [
    ['prose', prose], ['table', table],
    ['short', fence(source(99))], ['long', fence(source(100))],
    ['diagram', fence('graph TD\nA[Start] --> B[Finish]', 'mermaid')],
    ['loading', fence('graph TD', 'mermaid')],
    ['error', fence('graph TD\nA[unterminated', 'mermaid')],
] as const;

function message(id: string, text: string) {
    return {
        uuid: id, type: 'user' as const, timestamp: 1791075600000,
        content: [{ type: 'text' as const, text }],
    };
}

function Fixture() {
    const mode = useConfigStore(s => s.theme.mode);
    const rich = useConfigStore(s => s.theme.inkHavocFx?.cinematic);
    const [expanded, setExpanded] = useState(false);
    // Remount the message examples when the theme changes so long-code toggles
    // and copy feedback from a previous case cannot mask a regression.
    return <main key={`${mode}-${rich}`} data-theme={mode} data-rich={String(rich)}>
        {messages.map(([id, text]) => <section id={id} key={id}>
            <UserMessage message={message(id, text)} />
        </section>)}
        <section id="mixed"><UserMessage message={{
            ...message('mixed', '图文消息'),
            content: [{ type: 'text', text: '图文消息' }, { type: 'image', base64Data: png, mediaType: 'image/png' }],
        }} /></section>
        <section id="disclosure"><UserMessage message={message('disclosure', prose)}
            disclosure={{ expanded, onToggle: () => setExpanded(value => !value) }} /></section>
        <section id="standalone-short"><CodeBlock code={source(99)} language="javascript" /></section>
        <section id="standalone-long"><CodeBlock code={source(100)} language="javascript" /></section>
    </main>;
}

const testWindow = window as unknown as {
    configureTheme: (mode: string, cinematic: boolean) => void;
    copiedText: string;
    copiedImages: number;
};
testWindow.copiedText = '';
testWindow.copiedImages = 0;
Object.defineProperty(navigator, 'clipboard', { configurable: true, value: {
    writeText: async (text: string) => { testWindow.copiedText = text; },
    write: async () => { testWindow.copiedImages += 1; },
} });
// The test never accesses the host clipboard, even on a secure browser origin.
Object.defineProperty(window, 'ClipboardItem', { configurable: true, value: class {
    constructor(public data: Record<string, Blob>) {}
} });
testWindow.configureTheme = (mode, cinematic) => {
    document.documentElement.className = [mode, cinematic ? 'fx-ink-rich' : '', 'motion-off'].join(' ');
    applyAccent(DEFAULT_ACCENT_HEX, mode as Parameters<typeof applyAccent>[1]);
    useConfigStore.setState({ theme: {
        mode: mode as Parameters<typeof applyAccent>[1], accentColor: DEFAULT_ACCENT_HEX,
        inkHavocFx: { cinematic, motion: 'off', retreat: false },
    } });
};
createRoot(document.getElementById('root')!).render(<Fixture />);
