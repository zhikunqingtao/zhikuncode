/** 移动端常驻输入卡片：文本独占上方，附件、语音、密度和发送固定在底部。 */
import React, { useCallback, useRef, useState } from 'react';
import { FlaskConical, ImagePlus, Paperclip, Plus, SquareTerminal, X } from 'lucide-react';
import type { FileReferenceCapability } from '@/types';
import type { usePromptState } from './usePromptState';
import { SheetShell } from '@/components/apos/MobileBottomSheet';
import PromptTextarea from './PromptTextarea';
import PromptSendButton from './PromptSendButton';
import { PromptAttachmentBar } from './PromptToolbar';
import { MobileDensitySwitch } from './DensitySwitch';
import VoiceInputButton from '../VoiceInputButton';

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
const menuClass = 'flex min-h-12 w-full items-center gap-3 rounded-xl px-4 py-3 text-left text-sm text-t2 hover:bg-hover2 disabled:opacity-50';
const iconClass = 'flex h-11 w-11 shrink-0 items-center justify-center rounded-full text-t2 hover:bg-hover2 focus-visible:ring-2 focus-visible:ring-accent2 disabled:opacity-50';

const MobilePromptBar: React.FC<MobilePromptBarProps> = ({ state: s, runActive, compacting, disabled, simpleMode, fileReferenceCapability, onInterrupt }) => {
    const { promptAttachments: a, localFileReference: f } = s;
    const imageInputRef = useRef<HTMLInputElement>(null);
    const [attachMenuOpen, setAttachMenuOpen] = useState(false);
    const closeMenu = useCallback(() => setAttachMenuOpen(false), []);
    const hasAttachments = a.attachments.length > 0 || f.localFiles.length > 0 || f.publishedLocalFiles.length > 0;
    const busy = disabled || compacting || s.isSubmitting || a.isUploadingPaste || f.fileReferenceBusy;
    const plusDisabled = busy || runActive;
    const sendDisabled = busy || (!s.input.trim() && !hasAttachments);
    const pickImages = () => { closeMenu(); imageInputRef.current?.click(); };
    const pickFile = () => { closeMenu(); f.handleFileReferenceClick(); };
    const fillPrompt = (text: string) => {
        closeMenu();
        s.setInput(text);
        if (text === '/') s.setShowCommands(true);
        requestAnimationFrame(() => s.textareaRef.current?.focus());
    };

    return (
        <div data-testid="mobile-prompt-bar" className="min-w-0 rounded-2xl border border-hairline bg-surfacev2 shadow-e2">
            <input ref={imageInputRef} data-mobile-image-input type="file" accept="image/*" multiple className="hidden" disabled={plusDisabled}
                onChange={event => {
                    const files = Array.from(event.currentTarget.files ?? []);
                    event.currentTarget.value = '';
                    if (files.length) void a.handleFiles(files);
                }} />
            <input ref={f.browserFileInputRef} data-local-file-reference-input type="file" className="hidden" disabled={plusDisabled}
                onChange={event => {
                    const file = event.currentTarget.files?.[0];
                    event.currentTarget.value = '';
                    if (file) void f.handleBrowserLocalFile(file);
                }} />
            <div data-testid="mobile-prompt-text-area" className="min-h-[60px] max-h-[35dvh] overflow-y-auto px-3 pt-3 pb-1">
                <PromptTextarea value={s.input} onValueChange={s.setInput} onCursorChange={s.syncCursorPos}
                    onAtQueryChange={s.handleAtQueryChange} onSlashIntent={s.handleSlashIntent} onKeyDown={s.handleKeyDown}
                    onPaste={a.handlePaste} textareaRef={s.textareaRef} compacting={compacting} runActive={runActive}
                    simpleMode={simpleMode} disabled={busy} variant="mobile" collapsed={false} />
                {hasAttachments && <PromptAttachmentBar attachments={a.attachments} imageCount={a.imageCount} maxImages={a.maxImages}
                    localFiles={f.localFiles} publishedLocalFiles={f.publishedLocalFiles} onRemoveAttachment={a.removeAttachment}
                    setLocalFiles={f.setLocalFiles} setPublishedLocalFiles={f.setPublishedLocalFiles} />}
            </div>
            <div data-testid="mobile-persistent-actions" className="flex min-w-0 items-center gap-1 px-2 py-1.5">
                <button type="button" aria-label="附件与工具" aria-haspopup="dialog" aria-expanded={attachMenuOpen} disabled={plusDisabled}
                    className={iconClass} onClick={() => setAttachMenuOpen(true)}><Plus size={20} /></button>
                <VoiceInputButton compact onTranscript={s.handleVoiceTranscript} disabled={!s.asrAvailable || busy}
                    disabledReason={!s.asrAvailable ? '语音输入（服务暂不可用）' : undefined} />
                <MobileDensitySwitch />
                <span className="min-w-0 flex-1" />
                <PromptSendButton variant="mobile" runActive={runActive} sendDisabled={sendDisabled} stopDisabled={disabled || s.isSubmitting}
                    onSend={() => { void s.handleSubmit(); }} onInterrupt={onInterrupt} />
            </div>
            <SheetShell isOpen={attachMenuOpen} onClose={closeMenu} ariaLabel="附件与工具" header={
                <div className="flex items-center justify-between px-4 pb-2"><h3 className="text-sm font-semibold text-t1">附件与工具</h3>
                    <button className={iconClass} aria-label="关闭附件与工具" onClick={closeMenu}><X size={18} /></button></div>
            }>
                <div className="px-2 pb-4">
                    <button type="button" className={menuClass} disabled={plusDisabled} onClick={pickImages}><ImagePlus size={20} />图片附件</button>
                    <button type="button" className={menuClass} disabled={plusDisabled} onClick={pickFile}><Paperclip size={20} />{fileReferenceCapability?.mode === 'oss_upload' ? '上传本地文件' : '文件引用'}</button>
                    <button type="button" className={menuClass} disabled={busy} onClick={() => fillPrompt('运行测试')}><FlaskConical size={20} />运行测试</button>
                    <button type="button" className={menuClass} disabled={plusDisabled} onClick={() => fillPrompt('/')}><SquareTerminal size={20} />命令面板</button>
                </div>
            </SheetShell>
        </div>
    );
};
export default React.memo(MobilePromptBar);
