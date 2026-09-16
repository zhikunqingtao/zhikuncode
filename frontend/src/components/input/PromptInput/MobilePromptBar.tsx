/** 移动端常驻输入卡片：文本独占上方，附件、语音、密度和发送固定在底部。 */
import React, { useRef } from 'react';
import { ImagePlus, Paperclip, Camera } from 'lucide-react';
import type { FileReferenceCapability } from '@/types';
import type { usePromptState } from './usePromptState';
import PromptTextarea from './PromptTextarea';
import PromptSendButton from './PromptSendButton';
import { PromptAttachmentBar } from './PromptToolbar';
import { MobileComposerNavigation } from './MobileComposerNavigation';
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
const iconClass = 'flex h-11 w-11 shrink-0 items-center justify-center rounded-full text-t2 hover:bg-hover2 focus-visible:ring-2 focus-visible:ring-accent2 disabled:opacity-50';

const MobilePromptBar: React.FC<MobilePromptBarProps> = ({ state: s, runActive, compacting, disabled, simpleMode, fileReferenceCapability, onInterrupt }) => {
    const { promptAttachments: a, localFileReference: f } = s;
    const imageInputRef = useRef<HTMLInputElement>(null);
    const cameraInputRef = useRef<HTMLInputElement>(null);
    const hasAttachments = a.attachments.length > 0 || f.localFiles.length > 0 || f.publishedLocalFiles.length > 0;
    const busy = disabled || compacting || s.isSubmitting || a.isUploadingPaste || f.fileReferenceBusy;
    const attachmentDisabled = busy || runActive;
    const sendDisabled = busy || (!s.input.trim() && !hasAttachments);
    const pickImages = () => { imageInputRef.current?.click(); };
    const pickFile = () => { f.handleFileReferenceClick(); };


    return (
        <div data-testid="mobile-prompt-bar" className="flex min-h-0 min-w-0 flex-col rounded-[14px] border border-hairline bg-surfacev2 shadow-e2" style={{ minHeight: 'var(--mobile-composer-min-height, 0px)', maxHeight: 'calc(var(--viewport-height, 100dvh) * 0.382 - 2px)' }}>
            <input ref={imageInputRef} data-mobile-image-input type="file" accept="image/*" multiple className="hidden" disabled={attachmentDisabled}
                onChange={event => {
                    const files = Array.from(event.currentTarget.files ?? []);
                    event.currentTarget.value = '';
                    if (files.length) void a.handleFiles(files);
                }} />
            <input ref={cameraInputRef} data-mobile-camera-input type="file" accept="image/*" capture="environment" className="hidden" disabled={attachmentDisabled}
                onChange={event => {
                    const files = Array.from(event.currentTarget.files ?? []);
                    event.currentTarget.value = '';
                    if (files.length) void a.handleFiles(files);
                }} />
            <input ref={f.browserFileInputRef} data-local-file-reference-input type="file" className="hidden" disabled={attachmentDisabled}
                onChange={event => {
                    const file = event.currentTarget.files?.[0];
                    event.currentTarget.value = '';
                    if (file) void f.handleBrowserLocalFile(file);
                }} />
            <div data-testid="mobile-prompt-text-area" className="min-h-0 overflow-y-auto px-3 pt-3 pb-1">
                <PromptTextarea value={s.input} onValueChange={s.setInput} onCursorChange={s.syncCursorPos}
                    onAtQueryChange={s.handleAtQueryChange} onSlashIntent={s.handleSlashIntent} onKeyDown={s.handleKeyDown}
                    onPaste={a.handlePaste} textareaRef={s.textareaRef} compacting={compacting} runActive={runActive}
                    simpleMode={simpleMode} disabled={busy} variant="mobile" collapsed={false} />
                {hasAttachments && <PromptAttachmentBar attachments={a.attachments} imageCount={a.imageCount} maxImages={a.maxImages}
                    localFiles={f.localFiles} publishedLocalFiles={f.publishedLocalFiles} onRemoveAttachment={a.removeAttachment}
                    setLocalFiles={f.setLocalFiles} setPublishedLocalFiles={f.setPublishedLocalFiles} />}
            </div>
            <div data-testid="mobile-persistent-actions" className={`flex shrink-0 flex-wrap min-w-0 items-center px-2 py-1.5 ${runActive ? 'gap-0' : 'gap-1'}`}>
                <button type="button" aria-label={fileReferenceCapability?.mode === 'oss_upload' ? '上传本地文件' : '文件引用'} title="文件" disabled={attachmentDisabled} className={iconClass} onClick={pickFile}><Paperclip size={20} /></button>
                <button type="button" aria-label="图片附件" title="图片" disabled={attachmentDisabled} className={iconClass} onClick={pickImages}><ImagePlus size={20} /></button>
                <button type="button" aria-label="拍照" title="拍照" disabled={attachmentDisabled} className={iconClass} onClick={() => cameraInputRef.current?.click()}><Camera size={20} /></button>
                <button type="button" aria-label="命令" title="命令" disabled={busy} className="flex h-11 shrink-0 items-center gap-1 rounded-[10px] px-2 text-sm text-t2 hover:bg-hover2 focus-visible:ring-2 focus-visible:ring-accent2 disabled:opacity-50"
                    onClick={() => { s.setInput('/'); s.setShowCommands(true); requestAnimationFrame(() => s.textareaRef.current?.focus()); }}><span aria-hidden="true">/</span>命令</button>
                <VoiceInputButton compact onTranscript={s.handleVoiceTranscript} disabled={!s.asrAvailable || busy}
                    disabledReason={!s.asrAvailable ? '语音输入（服务暂不可用）' : undefined} />
                <span className="min-w-0 flex-1" />
                <PromptSendButton variant="mobile" runActive={runActive} sendDisabled={sendDisabled} stopDisabled={disabled || s.isSubmitting}
                    onSend={() => { void s.handleSubmit(); }} onInterrupt={onInterrupt} />
            </div>
            <MobileComposerNavigation />
        </div>
    );
};
export default React.memo(MobilePromptBar);
