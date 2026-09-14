import { fireEvent, render, screen } from '@testing-library/react';
import { beforeAll, describe, expect, it } from 'vitest';
import ToolCallBlock from './ToolCallBlock';
import type { ToolCallState } from '@/types';

// jsdom 无 matchMedia 实现，CodeBlock 的 resolveTheme('system') 依赖它
beforeAll(() => {
    if (typeof window.matchMedia !== 'function') {
        window.matchMedia = ((query: string) => ({
            matches: false,
            media: query,
            onchange: null,
            addListener: () => {},
            removeListener: () => {},
            addEventListener: () => {},
            removeEventListener: () => {},
            dispatchEvent: () => false,
        })) as unknown as typeof window.matchMedia;
    }
});

function makeToolCall(overrides: Partial<ToolCallState> = {}): ToolCallState {
    return {
        toolName: 'FileReadTool',
        input: { file_path: 'src/App.tsx' },
        status: 'completed',
        startTime: 0,
        duration: 1200,
        result: { content: 'line-1', isError: false },
        ...overrides,
    };
}

/** 展开主卡 + 展开结果区（完成态默认二者均折叠） */
function expandCardAndResult(name: string | RegExp) {
    fireEvent.click(screen.getByRole('button', { name }));
    fireEvent.click(screen.getByRole('button', { name: /Result/ }));
}

describe('ToolCallBlock 主卡默认折叠行为', () => {
    it('完成态默认折叠，折叠 header 一行自解释（名称/主目标/状态/耗时）', () => {
        render(<ToolCallBlock toolUseId="t-1" toolCall={makeToolCall()} />);

        const header = screen.getByRole('button', { name: /FileReadTool/ });
        expect(header).toHaveAttribute('aria-expanded', 'false');
        // 折叠态不渲染 Input / Result 区
        expect(screen.queryByText('Result')).not.toBeInTheDocument();
        // header 自解释信息齐备
        expect(screen.getByText('src/App.tsx')).toBeInTheDocument();
        expect(screen.getByText('Completed')).toBeInTheDocument();
        expect(screen.getByText('1s')).toBeInTheDocument();
    });

    it('运行态默认展开', () => {
        render(<ToolCallBlock
            toolUseId="t-2"
            toolCall={makeToolCall({ status: 'running', duration: undefined })}
        />);
        expect(screen.getByRole('button', { name: /FileReadTool/ }))
            .toHaveAttribute('aria-expanded', 'true');
        expect(screen.getByText('Input')).toBeInTheDocument();
    });

    it('等待态默认展开', () => {
        render(<ToolCallBlock
            toolUseId="t-3"
            toolCall={makeToolCall({ status: 'pending', result: undefined, duration: undefined })}
        />);
        expect(screen.getByRole('button', { name: /FileReadTool/ }))
            .toHaveAttribute('aria-expanded', 'true');
    });

    it('错误态默认折叠', () => {
        render(<ToolCallBlock
            toolUseId="t-4"
            toolCall={makeToolCall({ status: 'error', result: { content: 'boom', isError: true } })}
        />);
        expect(screen.getByRole('button', { name: /FileReadTool/ }))
            .toHaveAttribute('aria-expanded', 'false');
    });

    it('显式传入 expanded 时受控优先（覆盖完成态默认折叠）', () => {
        render(<ToolCallBlock toolUseId="t-5" toolCall={makeToolCall()} expanded />);
        expect(screen.getByRole('button', { name: /FileReadTool/ }))
            .toHaveAttribute('aria-expanded', 'true');
        expect(screen.getByText('Input')).toBeInTheDocument();
    });

    it('显式传入 expanded={false} 时受控优先（覆盖运行态默认展开）', () => {
        render(<ToolCallBlock
            toolUseId="t-6"
            toolCall={makeToolCall({ status: 'running', duration: undefined })}
            expanded={false}
        />);
        expect(screen.getByRole('button', { name: /FileReadTool/ }))
            .toHaveAttribute('aria-expanded', 'false');
        expect(screen.queryByText('Input')).not.toBeInTheDocument();
    });

    it('Bash 类工具折叠 header 显示命令作为主目标', () => {
        render(<ToolCallBlock
            toolUseId="t-7"
            toolCall={makeToolCall({ toolName: 'Bash', input: { command: 'npm run typecheck' } })}
        />);
        expect(screen.getByText('npm run typecheck')).toBeInTheDocument();
    });
});

describe('ToolCallBlock 结果区折叠与截断', () => {
    it('结果区默认折叠，点击 Result 后展开', () => {
        render(<ToolCallBlock toolUseId="t-8" toolCall={makeToolCall()} />);
        fireEvent.click(screen.getByRole('button', { name: /FileReadTool/ }));

        // 主卡展开但结果区仍折叠
        expect(screen.getByText('Result')).toBeInTheDocument();
        expect(screen.queryByText('line-1')).not.toBeInTheDocument();

        fireEvent.click(screen.getByRole('button', { name: /Result/ }));
        expect(screen.getByText('line-1')).toBeInTheDocument();
    });

    it('文本类结果超过 30 行默认截断，可展开全部并再收起', () => {
        const lines = Array.from({ length: 50 }, (_, i) => `marker-line-${i + 1}`);
        render(<ToolCallBlock
            toolUseId="t-9"
            toolCall={makeToolCall({
                status: 'error',
                result: { content: lines.join('\n'), isError: true },
            })}
        />);
        expandCardAndResult(/FileReadTool/);

        // 默认只展示前 30 行
        expect(screen.getByText(/marker-line-30/)).toBeInTheDocument();
        expect(screen.queryByText(/marker-line-31/)).not.toBeInTheDocument();
        const expandAll = screen.getByRole('button', { name: '展开全部（共 50 行）' });

        // 展开全部
        fireEvent.click(expandAll);
        expect(screen.getByText(/marker-line-50/)).toBeInTheDocument();

        // 再收起
        fireEvent.click(screen.getByRole('button', { name: '收起' }));
        expect(screen.queryByText(/marker-line-31/)).not.toBeInTheDocument();
        expect(screen.getByRole('button', { name: '展开全部（共 50 行）' })).toBeInTheDocument();
    });

    it('30 行以内的结果不显示截断按钮', () => {
        const lines = Array.from({ length: 30 }, (_, i) => `short-line-${i + 1}`);
        render(<ToolCallBlock
            toolUseId="t-10"
            toolCall={makeToolCall({
                status: 'error',
                result: { content: lines.join('\n'), isError: true },
            })}
        />);
        expandCardAndResult(/FileReadTool/);

        expect(screen.getByText(/short-line-30/)).toBeInTheDocument();
        expect(screen.queryByRole('button', { name: /展开全部/ })).not.toBeInTheDocument();
    });

    it('默认渲染分支（CodeBlock）同样按 30 行截断', () => {
        const lines = Array.from({ length: 40 }, (_, i) => `code-line-${i + 1}`);
        const { container } = render(<ToolCallBlock
            toolUseId="t-11"
            toolCall={makeToolCall({ result: { content: lines.join('\n'), isError: false } })}
        />);
        expandCardAndResult(/FileReadTool/);

        expect(screen.getByRole('button', { name: '展开全部（共 40 行）' })).toBeInTheDocument();
        expect(container.textContent).toContain('code-line-30');
        expect(container.textContent).not.toContain('code-line-31');

        fireEvent.click(screen.getByRole('button', { name: '展开全部（共 40 行）' }));
        expect(container.textContent).toContain('code-line-40');
    });
});

describe('ToolCallBlock structured result renderer', () => {
    it('uses the exact URL returned by the tool for the download link', () => {
        const objectKey = 'zhikuncode-artifacts/session/artifact/report.html';
        const url = `https://zhikunshare.oss-cn-beijing.aliyuncs.com/${objectKey}?version=1`;

        render(<ToolCallBlock
            toolUseId="publish-1"
            toolCall={{
                toolName: 'PublishArtifact',
                input: { file_path: 'report.html' },
                status: 'completed',
                startTime: 1,
                duration: 10,
                result: {
                    content: '{"status":"published"}',
                    isError: false,
                    metadata: {
                        structuredResult: {
                            schema: 'external-resource/v1',
                            kind: 'download',
                            provider: 'oss',
                            artifactId: 'artifact-1',
                            url,
                            label: 'report.html',
                            size: 2048,
                            sha256: 'c'.repeat(64),
                            objectKey,
                            mimeType: 'text/html',
                            permanentlyPublic: true,
                            downloadExpected: true,
                        },
                    },
                },
            }}
        />);

        // 完成态默认折叠：先展开主卡与结果区
        expandCardAndResult(/PublishArtifact/);

        expect(screen.getByTestId('external-resource-card')).toBeInTheDocument();
        expect(screen.getByTestId('external-resource-download').getAttribute('href')).toBe(url);
        expect(screen.queryByText(url)).not.toBeInTheDocument();
    });
});
