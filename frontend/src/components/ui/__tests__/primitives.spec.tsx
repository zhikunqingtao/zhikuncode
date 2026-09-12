/**
 * P1a 基元冒烟测试（@testing-library/react + vitest）：
 * - 12 基元渲染不炸
 * - Toggle 点击翻转 aria-checked
 * - Tabs 点击 / 方向键 / Home / End 切换
 * - Dialog 打开 → Esc 关闭 → 焦点归还触发器
 * - Button loading 显示 Spinner 且禁用
 */
import { useRef, useState } from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import {
    Button,
    Card,
    Chip,
    Dialog,
    EmptyState,
    Input,
    Kbd,
    Progress,
    Spinner,
    Tabs,
    Textarea,
    Toggle,
} from '@/components/ui';

describe('primitives 渲染', () => {
    it('Button 渲染不炸（variants/sizes/disabled）', () => {
        render(
            <>
                <Button variant="primary">P</Button>
                <Button variant="secondary">S</Button>
                <Button variant="ghost">G</Button>
                <Button variant="danger">D</Button>
                <Button size="sm">sm</Button>
                <Button size="lg">lg</Button>
                <Button iconOnly aria-label="icon">
                    i
                </Button>
                <Button disabled>dis</Button>
            </>,
        );
        expect(screen.getByText('P')).toBeInTheDocument();
        expect(screen.getByText('dis')).toBeDisabled();
    });

    it('Card 渲染不炸（interactive/selected）', () => {
        render(
            <>
                <Card>静态</Card>
                <Card interactive>可点</Card>
                <Card selected>选中</Card>
            </>,
        );
        expect(screen.getByText('选中')).toBeInTheDocument();
    });

    it('Input / Textarea 渲染不炸（error/disabled）', () => {
        render(
            <>
                <Input placeholder="普通输入" />
                <Input error aria-label="错误输入" />
                <Input disabled placeholder="禁用输入" />
                <Textarea placeholder="多行" />
                <Textarea error aria-label="错误多行" />
            </>,
        );
        expect(screen.getByPlaceholderText('普通输入')).toBeInTheDocument();
        expect(screen.getByLabelText('错误输入')).toHaveAttribute('aria-invalid', 'true');
        expect(screen.getByPlaceholderText('禁用输入')).toBeDisabled();
        expect(screen.getByLabelText('错误多行')).toHaveAttribute('aria-invalid', 'true');
    });

    it('Chip 渲染不炸（5 variant + selected）', () => {
        render(
            <>
                <Chip variant="accent">a</Chip>
                <Chip variant="ok">o</Chip>
                <Chip variant="warn">w</Chip>
                <Chip variant="err">e</Chip>
                <Chip variant="neutral">n</Chip>
                <Chip selected>s</Chip>
            </>,
        );
        expect(screen.getByText('s')).toBeInTheDocument();
    });

    it('Progress 渲染不炸（determinate aria / indeterminate / 两端插槽）', () => {
        render(
            <>
                <Progress value={3} max={10} start={3} end={10} aria-label="进度A" />
                <Progress indeterminate aria-label="进度B" />
            </>,
        );
        const barA = screen.getByLabelText('进度A');
        expect(barA).toHaveAttribute('role', 'progressbar');
        expect(barA).toHaveAttribute('aria-valuenow', '3');
        expect(barA).toHaveAttribute('aria-valuemin', '0');
        expect(barA).toHaveAttribute('aria-valuemax', '10');
        expect(screen.getByText('3')).toBeInTheDocument();
        expect(screen.getByText('10')).toBeInTheDocument();

        const barB = screen.getByLabelText('进度B');
        expect(barB).not.toHaveAttribute('aria-valuenow');
        expect(barB).toHaveAttribute('aria-busy', 'true');
    });

    it('Tabs 渲染不炸（tablist/tab/tabpanel 角色齐全）', () => {
        render(
            <Tabs
                items={[
                    { value: 'a', label: 'A', content: <p>面板A</p> },
                    { value: 'b', label: 'B', content: <p>面板B</p> },
                ]}
            />,
        );
        expect(screen.getByRole('tablist')).toBeInTheDocument();
        expect(screen.getAllByRole('tab')).toHaveLength(2);
        expect(screen.getByRole('tabpanel')).toHaveTextContent('面板A');
    });

    it('Kbd 渲染不炸', () => {
        render(<Kbd>⌘K</Kbd>);
        expect(screen.getByText('⌘K')).toBeInTheDocument();
    });

    it('EmptyState 渲染不炸（hero / compact）', () => {
        render(
            <>
                <EmptyState
                    variant="hero"
                    badge={<span>徽章</span>}
                    title={
                        <>
                            今天想<b>构建</b>什么？
                        </>
                    }
                    description="副文案"
                    actions={<Button>动作</Button>}
                />
                <EmptyState variant="compact" title="空" description="说明" />
            </>,
        );
        expect(screen.getByText('徽章')).toBeInTheDocument();
        expect(screen.getByText('构建')).toBeInTheDocument();
        expect(screen.getByText('空')).toBeInTheDocument();
    });

    it('Spinner 渲染不炸（role=status + 默认 aria-label）', () => {
        render(
            <>
                <Spinner size="sm" />
                <Spinner size="md" />
                <Spinner size="lg" />
            </>,
        );
        const all = screen.getAllByRole('status');
        expect(all).toHaveLength(3);
        expect(all[0]).toHaveAttribute('aria-label', '加载中');
    });
});

describe('Toggle 交互', () => {
    it('点击翻转 aria-checked（非受控）', () => {
        render(<Toggle aria-label="开关" />);
        const t = screen.getByRole('switch');
        expect(t).toHaveAttribute('aria-checked', 'false');
        fireEvent.click(t);
        expect(t).toHaveAttribute('aria-checked', 'true');
        fireEvent.click(t);
        expect(t).toHaveAttribute('aria-checked', 'false');
    });

    it('受控模式回调 onCheckedChange', () => {
        const onChange = vi.fn();
        render(<Toggle checked={false} onCheckedChange={onChange} aria-label="受控" />);
        fireEvent.click(screen.getByRole('switch'));
        expect(onChange).toHaveBeenCalledWith(true);
        /* 受控：父组件未更新 checked 时保持原值 */
        expect(screen.getByRole('switch')).toHaveAttribute('aria-checked', 'false');
    });
});

describe('Tabs 交互', () => {
    const items = [
        { value: 'a', label: '甲', content: <p>内容甲</p> },
        { value: 'b', label: '乙', content: <p>内容乙</p> },
        { value: 'c', label: '丙', content: <p>内容丙</p> },
    ];

    it('点击切换选中与面板', () => {
        render(<Tabs items={items} />);
        fireEvent.click(screen.getByRole('tab', { name: '乙' }));
        expect(screen.getByRole('tab', { name: '乙' })).toHaveAttribute('aria-selected', 'true');
        expect(screen.getByRole('tab', { name: '甲' })).toHaveAttribute('aria-selected', 'false');
        expect(screen.getByRole('tabpanel')).toHaveTextContent('内容乙');
    });

    it('方向键 / Home / End 导航（自动激活 + 焦点跟随）', () => {
        render(<Tabs items={items} />);
        const tablist = screen.getByRole('tablist');

        fireEvent.keyDown(tablist, { key: 'ArrowRight' });
        expect(screen.getByRole('tab', { name: '乙' })).toHaveAttribute('aria-selected', 'true');
        expect(document.activeElement).toBe(screen.getByRole('tab', { name: '乙' }));

        fireEvent.keyDown(tablist, { key: 'ArrowLeft' });
        expect(screen.getByRole('tab', { name: '甲' })).toHaveAttribute('aria-selected', 'true');

        fireEvent.keyDown(tablist, { key: 'End' });
        expect(screen.getByRole('tab', { name: '丙' })).toHaveAttribute('aria-selected', 'true');

        fireEvent.keyDown(tablist, { key: 'Home' });
        expect(screen.getByRole('tab', { name: '甲' })).toHaveAttribute('aria-selected', 'true');

        /* 循环：首页再向左 → 末位 */
        fireEvent.keyDown(tablist, { key: 'ArrowLeft' });
        expect(screen.getByRole('tab', { name: '丙' })).toHaveAttribute('aria-selected', 'true');
    });

    it('跳过禁用项', () => {
        render(
            <Tabs
                items={[
                    { value: 'a', label: 'A', content: null },
                    { value: 'b', label: 'B', disabled: true, content: null },
                    { value: 'c', label: 'C', content: null },
                ]}
            />,
        );
        fireEvent.keyDown(screen.getByRole('tablist'), { key: 'ArrowRight' });
        expect(screen.getByRole('tab', { name: 'C' })).toHaveAttribute('aria-selected', 'true');
    });
});

describe('Dialog 交互（§10.7-④ 焦点归还链）', () => {
    function Harness() {
        const [open, setOpen] = useState(false);
        return (
            <>
                <button type="button" onClick={() => setOpen(true)}>
                    打开
                </button>
                <Dialog open={open} onOpenChange={setOpen} title="标题">
                    <p>内容</p>
                </Dialog>
            </>
        );
    }

    it('打开 → 焦点移入 → Esc 关闭 → 焦点归还触发器', () => {
        render(<Harness />);
        const trigger = screen.getByText('打开');
        trigger.focus();
        fireEvent.click(trigger);

        const dialog = screen.getByRole('dialog');
        expect(dialog).toBeInTheDocument();
        expect(dialog).toHaveAttribute('aria-modal', 'true');
        /* 打开后焦点移入对话框内部（首个可交互元素 = 关闭按钮） */
        expect(dialog.contains(document.activeElement)).toBe(true);

        fireEvent.keyDown(document, { key: 'Escape' });
        expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
        expect(document.activeElement).toBe(trigger);
    });

    it('关闭按钮与遮罩走同一 close handler', () => {
        const onOpenChange = vi.fn();
        function Controlled() {
            const [open, setOpen] = useState(true);
            return (
                <Dialog
                    open={open}
                    onOpenChange={(v) => {
                        onOpenChange(v);
                        setOpen(v);
                    }}
                    title="T"
                >
                    <p>x</p>
                </Dialog>
            );
        }
        const { rerender } = render(<Controlled />);
        fireEvent.click(screen.getByLabelText('关闭'));
        expect(onOpenChange).toHaveBeenCalledWith(false);
        expect(screen.queryByRole('dialog')).not.toBeInTheDocument();

        onOpenChange.mockClear();
        rerender(<Controlled />);
        /* 重新打开后点遮罩（面板外层容器自身 mousedown） */
        render(
            <Dialog open onOpenChange={onOpenChange} title="遮罩测试">
                <p>y</p>
            </Dialog>,
        );
        const dialogEl = screen.getByRole('dialog', { name: '遮罩测试' });
        const overlayContainer = dialogEl.parentElement!;
        fireEvent.mouseDown(overlayContainer);
        expect(onOpenChange).toHaveBeenCalledWith(false);
    });

    it('显式 triggerRef 卸载后归还其父容器（禁止归还 body）', () => {
        function UnmountHarness() {
            const [open, setOpen] = useState(false);
            const [gone, setGone] = useState(false);
            const triggerRef = useRef<HTMLButtonElement>(null);
            return (
                <div data-testid="parent">
                    {!gone && (
                        <button
                            ref={triggerRef}
                            type="button"
                            onClick={() => {
                                setOpen(true);
                                setGone(true); /* 触发器立即卸载 */
                            }}
                        >
                            自毁触发器
                        </button>
                    )}
                    <Dialog open={open} onOpenChange={setOpen} triggerRef={triggerRef} title="归还">
                        <p>z</p>
                    </Dialog>
                </div>
            );
        }
        render(<UnmountHarness />);
        fireEvent.click(screen.getByText('自毁触发器'));
        expect(screen.getByRole('dialog')).toBeInTheDocument();

        fireEvent.keyDown(document, { key: 'Escape' });
        const parent = screen.getByTestId('parent');
        expect(document.activeElement).toBe(parent);
        expect(document.activeElement).not.toBe(document.body);
    });
});

describe('Button loading 态', () => {
    it('loading 显示 Spinner 且禁用 + aria-busy', () => {
        render(
            <Button variant="primary" loading>
                提交
            </Button>,
        );
        const btn = screen.getByRole('button');
        expect(btn).toBeDisabled();
        expect(btn).toHaveAttribute('aria-busy', 'true');
        expect(screen.getByRole('status')).toBeInTheDocument();
        /* children 被 Spinner 替换 */
        expect(btn).not.toHaveTextContent('提交');
    });
});
