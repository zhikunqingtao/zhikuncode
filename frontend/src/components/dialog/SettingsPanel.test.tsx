import { fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { SettingsPanel } from '@/components/dialog/SettingsPanel';
import { KeyboardShortcutsDialog } from '@/components/dialog/KeyboardShortcutsDialog';
import { useConfigStore } from '@/store/configStore';

describe('Appearance settings and shortcut help', () => {
    beforeEach(() => {
        useConfigStore.setState({
            theme: { ...useConfigStore.getState().theme, mode: 'light' },
        });
    });

    it('applies theme changes and persists them without session controls', () => {
        render(<SettingsPanel onClose={vi.fn()} />);
        expect(screen.getByRole('dialog', { name: '外观设置' })).toBeInTheDocument();
        expect(screen.queryByRole('combobox')).not.toBeInTheDocument();
        expect(screen.queryByRole('button', { name: '跟随系统' })).not.toBeInTheDocument();
        fireEvent.click(screen.getByRole('button', { name: '液态玻璃' }));
        expect(useConfigStore.getState().theme.mode).toBe('glass');
        expect(screen.getByRole('button', { name: '液态玻璃' })).toHaveAttribute('aria-pressed', 'true');
        expect(JSON.parse(localStorage.getItem('ai-coder-config')!).state.theme.mode).toBe('glass');
    });

    it('closes appearance settings with Escape', () => {
        const onClose = vi.fn();
        render(<SettingsPanel onClose={onClose} />);
        fireEvent.keyDown(document, { key: 'Escape' });
        expect(onClose).toHaveBeenCalledOnce();
    });

    it('shows shortcut help independently and closes with Done', () => {
        const onClose = vi.fn();
        render(<KeyboardShortcutsDialog onClose={onClose} />);
        expect(screen.getByRole('dialog', { name: '快捷键帮助' })).toBeInTheDocument();
        expect(screen.getByText('输入框内中断生成（未选中文字时）')).toBeInTheDocument();
        expect(screen.queryByRole('group', { name: '主题' })).not.toBeInTheDocument();
        fireEvent.click(screen.getByRole('button', { name: '完成' }));
        expect(onClose).toHaveBeenCalledOnce();
    });
});
