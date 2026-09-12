/**
 * MobilePromptBar — PromptInput 移动胶囊形态（§7.6 移动态 / §8.3.1-③）
 *
 * 收起态：[+ 圆形 40px] [输入胶囊 flex-1 h-46 rounded-full surfacev2 hairline e3] [发送 44px 圆形 accent2-strong]
 * 展开态（聚焦/多行/有附件）：上扩为 rounded-panel 卡片（max 40dvh），
 * 露出完整 PromptToolbar + 附件预览；形态切换 transition-[border-radius,max-height]
 * （§5.2 唯一例外）。
 * 快捷条：输入条上方横滑 chips（截图分析/选择文件/运行测试/命令面板）。
 *
 * 状态单源：提交/中断/附件/草稿全部来自 index.tsx 顶层 usePromptState，
 * 与桌面形态同源，本组件不复制任何状态逻辑。
 */

import React, { useCallback, useRef, useState } from 'react';
import {
    Camera,
    FileUp,
    FlaskConical,
    ImagePlus,
    Paperclip,
    Plus,
    SquareTerminal,
} from 'lucide-react';
import type { FileReferenceCapability } from '@/types';
import type { usePromptState } from './usePromptState';
import PromptTextarea from './PromptTextarea';
import PromptSendButton from './PromptSendButton';
import { PromptAttachmentBar, PromptToolbar } from './PromptToolbar';

type PromptState = ReturnType<typeof usePromptState>;

interface MobilePromptBarProps {
    state: PromptState;
    runActive: boolean;
    compacting: boolean;
    disabled: boolean;
    simpleMode: boolean;
    fileReferenceCapability: FileReferenceCapability | null;
    onInterrupt: () => void;
}

/** 快捷条 chip 配方（§7.6）：rounded-full bg-surfacev2 border-hairline shadow-e1 h-9 px-3.5 text-xs text-t2 */
const CHIP_CLASS = `flex h-9 shrink-0 items-center gap-1.5 rounded-full border border-hairline
    bg-surfacev2 px-3.5 text-xs text-t2 shadow-e1 transition-interactive duration-fast
    active:scale-95 disabled:opacity-50`;

/** 移动附件菜单项（触控命中 ≥44px） */
const MENU_ITEM_CLASS = `flex h-11 w-full items-center gap-2 px-3.5 text-left text-xs text-t2
    transition-interactive duration-fast hover:bg-hover2 active:scale-[.98] disabled:opacity-50`;

const MobilePromptBar: React.FC<MobilePromptBarProps> = ({
    state: s,
    runActive,
    compacting,
    disabled,
    simpleMode,
    fileReferenceCapability,
    onInterrupt,
}) => {
    const { promptAttachments: a, localFileReference: f } = s;
    const rootRef = useRef<HTMLDivElement>(null);
    const imageInputRef = useRef<HTMLInputElement>(null);
    const [focused, setFocused] = useState(false);
    const [attachMenuOpen, setAttachMenuOpen] = useState(false);

    const hasAttachments = a.attachments.length > 0
        || f.localFiles.length > 0
        || f.publishedLocalFiles.length > 0;
    // 展开条件：聚焦 / 多行内容 / 存在附件（§7.6 聚焦/多行上扩）
    const expanded = focused || s.input.includes('\n') || hasAttachments;

    // 运行/压缩期间禁用附件与命令动作（与桌面工具栏隐藏语义一致）
    const actionsBlocked = runActive || compacting;
    const plusDisabled = disabled || actionsBlocked || s.isSubmitting
        || a.isUploadingPaste || f.fileReferenceBusy;
    const sendDisabled = disabled || compacting || s.isSubmitting
        || a.isUploadingPaste || f.fileReferenceBusy
        || (!s.input.trim() && a.attachments.length === 0
            && f.localFiles.length === 0
            && f.publishedLocalFiles.length === 0);

    /** 聚焦真实 textarea 并把光标移到末尾（不提交） */
    const focusTextarea = useCallback(() => {
        const el = s.textareaRef.current;
        if (!el) return;
        el.focus();
        const end = el.value.length;
        el.selectionStart = end;
        el.selectionEnd = end;
    }, [s.textareaRef]);

    /** 图片附件流程（截图分析 chip / + 菜单「图片附件」共用）→ usePromptAttachments.handleFiles */
    const pickImages = useCallback(() => {
        setAttachMenuOpen(false);
        imageInputRef.current?.click();
    }, []);

    /** 浏览器文件选择（选择文件 chip / + 菜单「文件引用」共用）→ useLocalFileReference */
    const pickFile = useCallback(() => {
        setAttachMenuOpen(false);
        f.handleFileReferenceClick();
    }, [f]);

    /** 运行测试 chip：填入「运行测试」并聚焦，不自动提交 */
    const fillRunTest = useCallback(() => {
        s.setInput('运行测试');
        focusTextarea();
    }, [s, focusTextarea]);

    /** 命令面板 chip：等价于桌面输入「/」→ 打开斜杠命令面板 */
    const openSlashPalette = useCallback(() => {
        if (actionsBlocked) return;
        s.setInput('/');
        s.setShowCommands(true);
        focusTextarea();
    }, [actionsBlocked, s, focusTextarea]);

    // 焦点跟踪：焦点移到条外才收起（relatedTarget 仍在 root 内视为未失焦，
    // 点发送/快捷条 chip 不会导致卡片中途塌缩）
    const handleBlurCapture = useCallback((e: React.FocusEvent<HTMLDivElement>) => {
        const next = e.relatedTarget as Node | null;
        if (next && rootRef.current?.contains(next)) return;
        setFocused(false);
    }, []);

    return (
        <div
            ref={rootRef}
            data-testid="mobile-prompt-bar"
            className="flex flex-col gap-2"
            onFocusCapture={() => setFocused(true)}
            onBlurCapture={handleBlurCapture}
        >
            {/* 隐藏图片选择器：截图分析 / +菜单「图片附件」共用入口 */}
            <input
                ref={imageInputRef}
                data-mobile-image-input
                type="file"
                accept="image/*"
                multiple
                className="hidden"
                onChange={e => {
                    const files = e.currentTarget.files
                        ? Array.from(e.currentTarget.files) : [];
                    e.currentTarget.value = '';
                    if (files.length > 0) void a.handleFiles(files);
                }}
            />

            {/* 快捷条（quick-actions）：横滑 chips，左缘留 20px 让位系统返回手势（§8.6） */}
            <div
                data-testid="mobile-quick-actions"
                className="flex gap-2 overflow-x-auto px-2 pb-0.5
                    [-ms-overflow-style:none] [scrollbar-width:none] [&::-webkit-scrollbar]:hidden"
            >
                <button
                    type="button"
                    data-testid="mobile-chip-screenshot"
                    className={CHIP_CLASS}
                    disabled={actionsBlocked}
                    onClick={pickImages}
                >
                    <Camera size={14} />
                    截图分析
                </button>
                <button
                    type="button"
                    data-testid="mobile-chip-file"
                    className={CHIP_CLASS}
                    disabled={actionsBlocked}
                    onClick={pickFile}
                >
                    <FileUp size={14} />
                    选择文件
                </button>
                <button
                    type="button"
                    data-testid="mobile-chip-run-test"
                    className={CHIP_CLASS}
                    disabled={compacting || s.isSubmitting}
                    onClick={fillRunTest}
                >
                    <FlaskConical size={14} />
                    运行测试
                </button>
                <button
                    type="button"
                    data-testid="mobile-chip-commands"
                    className={CHIP_CLASS}
                    disabled={actionsBlocked}
                    onClick={openSlashPalette}
                >
                    <SquareTerminal size={14} />
                    命令面板
                </button>
            </div>

            {/* 悬浮输入条：[+ 40px] [胶囊/卡片 flex-1] [发送 44px] */}
            <div className="flex items-end gap-2">
                {/* + 附件/文件动作 */}
                <div className="relative shrink-0">
                    <button
                        type="button"
                        aria-label="附件与文件"
                        aria-expanded={attachMenuOpen}
                        disabled={plusDisabled}
                        onClick={() => setAttachMenuOpen(v => !v)}
                        className="flex h-10 w-10 items-center justify-center rounded-full
                            border border-hairline bg-surfacev2 text-t2 shadow-e1
                            transition-interactive duration-fast active:scale-95
                            disabled:opacity-50"
                    >
                        <Plus size={18} />
                    </button>
                    {attachMenuOpen && (
                        <>
                            <button
                                type="button"
                                aria-hidden="true"
                                tabIndex={-1}
                                className="fixed inset-0 z-10 cursor-default"
                                onClick={() => setAttachMenuOpen(false)}
                            />
                            <div
                                role="menu"
                                aria-label="附件与文件"
                                className="absolute bottom-full left-0 z-20 mb-2 w-40
                                    overflow-hidden rounded-2xl border border-hairline
                                    bg-surfacev2 shadow-e3"
                            >
                                <button
                                    type="button"
                                    role="menuitem"
                                    className={MENU_ITEM_CLASS}
                                    disabled={actionsBlocked}
                                    onClick={pickImages}
                                >
                                    <ImagePlus size={15} />
                                    图片附件
                                </button>
                                <button
                                    type="button"
                                    role="menuitem"
                                    className={`${MENU_ITEM_CLASS} border-t border-hairline`}
                                    disabled={actionsBlocked}
                                    onClick={pickFile}
                                >
                                    <Paperclip size={15} />
                                    文件引用
                                </button>
                            </div>
                        </>
                    )}
                </div>

                {/* 胶囊 ⇄ 卡片（§5.2 唯一例外：transition-[border-radius,max-height]） */}
                <div
                    data-testid="mobile-prompt-capsule"
                    className={`flex w-full min-w-0 flex-1 flex-col overflow-hidden
                        border border-hairline bg-surfacev2 shadow-e3
                        transition-[border-radius,max-height] duration-base ease-soft
                        ${expanded
                            ? 'max-h-[40dvh] overflow-y-auto rounded-panel'
                            : 'max-h-[46px] rounded-full'}`}
                >
                    <div className="flex min-h-[46px] w-full shrink-0 items-center px-4">
                        <PromptTextarea
                            value={s.input}
                            onValueChange={s.setInput}
                            onCursorChange={s.syncCursorPos}
                            onAtQueryChange={s.handleAtQueryChange}
                            onSlashIntent={s.handleSlashIntent}
                            onKeyDown={s.handleKeyDown}
                            onPaste={a.handlePaste}
                            textareaRef={s.textareaRef}
                            compacting={compacting}
                            runActive={runActive}
                            simpleMode={simpleMode}
                            disabled={disabled || compacting || s.isSubmitting
                                || a.isUploadingPaste || f.isUploadingLocalFile}
                            variant="mobile"
                            collapsed={!expanded}
                        />
                    </div>
                    {hasAttachments && (
                        <div className="px-3 pb-1">
                            <PromptAttachmentBar
                                attachments={a.attachments}
                                imageCount={a.imageCount}
                                maxImages={a.maxImages}
                                localFiles={f.localFiles}
                                publishedLocalFiles={f.publishedLocalFiles}
                                onRemoveAttachment={a.removeAttachment}
                                setLocalFiles={f.setLocalFiles}
                                setPublishedLocalFiles={f.setPublishedLocalFiles}
                            />
                        </div>
                    )}
                    {/* 完整 PromptToolbar（触控命中经包装补足 ≥44×44）；
                        收起态被 max-h 裁切不可见但保持挂载（隐藏 file input 不卸载） */}
                    {!actionsBlocked && (
                        <div
                            className="flex shrink-0 items-center gap-1 border-t
                                border-hairline px-2 py-1
                                [&_button]:flex [&_button]:min-h-11 [&_button]:min-w-11
                                [&_button]:items-center [&_button]:justify-center
                                [&_button]:rounded-full [&_button]:text-t2
                                [&_button]:transition-interactive [&_button]:duration-fast
                                [&_button]:active:scale-95 [&_button]:hover:bg-hover2"
                        >
                            <PromptToolbar
                                runActive={runActive}
                                compacting={compacting}
                                disabled={disabled}
                                isSubmitting={s.isSubmitting}
                                isUploadingPaste={a.isUploadingPaste}
                                isUploadingLocalFile={f.isUploadingLocalFile}
                                fileReferenceBusy={f.fileReferenceBusy}
                                fileReferenceTitle={f.fileReferenceTitle}
                                fileReferenceCapability={fileReferenceCapability}
                                asrAvailable={s.asrAvailable}
                                maxImages={a.maxImages}
                                browserFileInputRef={f.browserFileInputRef}
                                onFileReferenceClick={f.handleFileReferenceClick}
                                onBrowserLocalFile={f.handleBrowserLocalFile}
                                onFiles={a.handleFiles}
                                onVoiceTranscript={s.handleVoiceTranscript}
                            />
                        </div>
                    )}
                </div>

                {/* 发送/停止（44px 圆形 accent2-strong） */}
                <PromptSendButton
                    variant="mobile"
                    runActive={runActive}
                    sendDisabled={sendDisabled}
                    stopDisabled={disabled || s.isSubmitting}
                    onSend={() => { void s.handleSubmit(); }}
                    onInterrupt={onInterrupt}
                />
            </div>
        </div>
    );
};

export default React.memo(MobilePromptBar);
