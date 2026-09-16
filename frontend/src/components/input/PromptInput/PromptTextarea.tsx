/**
 * PromptTextarea — 受控 textarea + 自适应高度 + @文件补全触发
 *
 * §8.3.1 PromptInput 拆分：从原 PromptInput.tsx 纯搬运（零行为变化）。
 * 仅负责输入框呈现与输入事件采集；状态与快捷键逻辑均在 usePromptState。
 */

import React, { useEffect } from 'react';

interface PromptTextareaProps {
    value: string;
    onValueChange: (text: string) => void;
    onCursorChange: (pos: number | null) => void;
    onAtQueryChange: (query: string | null) => void;
    onSlashIntent: (startsWithSlash: boolean) => void;
    onKeyDown: (e: React.KeyboardEvent<HTMLTextAreaElement>) => void;
    onPaste: (e: React.ClipboardEvent<HTMLTextAreaElement>) => void;
    textareaRef: React.RefObject<HTMLTextAreaElement>;
    compacting: boolean;
    runActive: boolean;
    simpleMode: boolean;
    disabled: boolean;
    /** §8.3.1-③ 形态变体：mobile 去掉自带边框/背景（由胶囊容器承担）；默认 desktop 零回归 */
    variant?: 'desktop' | 'mobile';
    /** 仅 mobile 变体使用：收起态锁定单行高度预览（点击聚焦后展开） */
    collapsed?: boolean;
}

const PromptTextarea: React.FC<PromptTextareaProps> = ({
    value,
    onValueChange,
    onCursorChange,
    onAtQueryChange,
    onSlashIntent,
    onKeyDown,
    onPaste,
    textareaRef,
    compacting,
    runActive,
    simpleMode,
    disabled,
    variant = 'desktop',
    collapsed = false,
}) => {
    const isMobileVariant = variant === 'mobile';

    // Re-measure on text, width and visible viewport changes (including mobile keyboards).
    useEffect(() => {
        const el = textareaRef.current;
        if (!el) return;
        const resize = () => {
            const lineHeight = parseFloat(getComputedStyle(el).lineHeight) || 24;
            if (isMobileVariant && collapsed) {
                el.style.height = `${Math.ceil(lineHeight + 4)}px`;
                return;
            }
            const visibleHeight = window.visualViewport?.height ?? window.innerHeight;
            const panel = el.closest<HTMLElement>('[data-testid="mobile-prompt-bar"]');
            const controlsHeight = panel
                ? Array.from(panel.querySelectorAll('[data-testid="mobile-persistent-actions"], .mobile-composer-navigation'))
                    .reduce((height, control) => height + control.getBoundingClientRect().height, 0)
                : 0;
            // Keep one editable line and wrapped controls visible; attachments still scroll.
            if (isMobileVariant && panel) {
                panel.style.setProperty('--mobile-composer-min-height', `${controlsHeight + Math.ceil(lineHeight + 4) + 20}px`);
            }
            // 38.2% caps the whole mobile panel, including controls and padding.
            const maxHeight = isMobileVariant
                ? Math.max(lineHeight + 4, visibleHeight * 0.382 - controlsHeight - 20)
                : 200;
            el.style.height = 'auto';
            el.style.height = `${Math.min(el.scrollHeight, maxHeight)}px`;
        };
        resize();
        let width = el.getBoundingClientRect().width;
        const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(() => {
            const nextWidth = el.getBoundingClientRect().width;
            if (nextWidth !== width) { width = nextWidth; resize(); }
        });
        observer?.observe(el);
        const controlsObserver = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(resize);
        if (isMobileVariant) el.closest('[data-testid="mobile-prompt-bar"]')
            ?.querySelectorAll('[data-testid="mobile-persistent-actions"], .mobile-composer-navigation')
            .forEach(control => controlsObserver?.observe(control));
        window.addEventListener('resize', resize);
        window.visualViewport?.addEventListener('resize', resize);
        return () => {
            observer?.disconnect();
            controlsObserver?.disconnect();
            window.removeEventListener('resize', resize);
            window.visualViewport?.removeEventListener('resize', resize);
        };
    }, [value, textareaRef, isMobileVariant, collapsed]);

    return (
        <textarea
            ref={textareaRef}
            value={value}
            onChange={e => {
                const text = e.target.value;
                const cursor = e.target.selectionStart || 0;
                onCursorChange(e.target.selectionStart ?? null);
                onValueChange(text);

                // 检测 @ 触发
                const textBeforeCursor = text.slice(0, cursor);
                const atMatch = textBeforeCursor.match(/@([\w./\-]*)$/);
                if (atMatch) {
                    onAtQueryChange(atMatch[1]);
                } else {
                    onAtQueryChange(null);
                }

                // 检测 / 命令触发
                onSlashIntent(text.startsWith('/'));
            }}
            onKeyDown={onKeyDown}
            onPaste={onPaste}
            onSelect={() => {
                onCursorChange(textareaRef.current?.selectionStart ?? null);
            }}
            onBlur={() => {
                onCursorChange(textareaRef.current?.selectionStart ?? null);
            }}
            placeholder={
                compacting
                    ? '正在压缩上下文，请稍候…'
                    : runActive
                    ? '输入补充指令，将在本次操作完成后执行…'
                    : simpleMode
                    ? '描述你希望完成或继续修改的事情…'
                    : isMobileVariant ? '输入消息…'
                    : `输入消息…（/ 查看命令，${navigator.platform.includes('Mac') ? '⌘' : 'Ctrl+'}K 打开命令面板）`
            }
            disabled={disabled}
            aria-label="输入消息"
            aria-multiline="true"
            className={
                isMobileVariant
                    ? `w-full flex-none resize-none bg-transparent py-0.5 text-sm text-t1
                       placeholder-t4 focus:outline-none
                       disabled:opacity-50${collapsed ? ' overflow-y-hidden' : ''}`
                    : `flex-1 resize-none rounded-xl bg-sunken2 shadow-well
                       px-3 py-2 text-sm text-t1 placeholder-t4
                       transition-surface duration-fast
                       focus:outline-none focus:ring-[3px] focus:ring-accent2-ring
                       disabled:opacity-50`
            }
            rows={1}
            autoFocus={!isMobileVariant}
        />
    );
};

export default PromptTextarea;
