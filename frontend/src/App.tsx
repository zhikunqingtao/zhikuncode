import { useCallback, useEffect, useState, useMemo, useRef } from 'react';
import { AppLayout } from '@/components/layout';
import { MessageList } from '@/components/message';
import { JourneyVerifyPanel } from '@/components/verify/JourneyVerifyPanel';
import { PromptInput } from '@/components/input';
import { DialogManager } from '@/components/DialogManager';
import { useMessageStore } from '@/store/messageStore';
import { useSessionStore } from '@/store/sessionStore';
import { useConfigStore } from '@/store/configStore';
import { sendToServer, sendRunInput, sendSlashCommand } from '@/api/stompClient';
import { SkillDetailModal } from '@/components/skills/SkillDetailModal';
import { MobileApprovalSheet } from '@/components/verify/MobileApprovalSheet';
import type { SubmitEvent, Message, Command, PastePublishResult } from '@/types';
import { generateUUID } from '@/utils/uuid';
import { publishPastedImages as publishPastedImagesCore } from '@/utils/pasteImagePublisher';
import { useAPOSInitialization } from '@/hooks/useAPOSInitialization';
import { useActivityStore } from '@/store/activityStore';
import { useNotificationStore } from '@/store/notificationStore';
import { ProjectSelectionDialog } from '@/components/project/ProjectSelectionDialog';
import {
  NEW_AUTHORIZED_SESSION_EVENT,
  requestAuthorizedSession,
} from '@/services/authorizedSession';
import {
  activateSessionCandidate,
  getPendingSessionActivation,
} from '@/services/sessionActivation';
import { SimpleWorkbench } from '@/components/workbench/SimpleWorkbench';
import { useWorkbenchViewStore } from '@/store/workbenchViewStore';
import { useJourneyVerifyStore } from '@/store/journeyVerifyStore';
import { usePageExitGuard } from '@/hooks/usePageExitGuard';

interface SkillItem {
  name: string;
  description: string;
  source: string;
}

function App() {
  usePageExitGuard();

  const { messages, addMessage } = useMessageStore();
  const { status, sessionId } = useSessionStore();
  const workbenchEnabled = useWorkbenchViewStore(s => s.enabled);
  const viewMode = useWorkbenchViewStore(s => s.viewMode);
  const { loadConfig } = useConfigStore();
  const sessionReadinessRef = useRef<Promise<string | null> | null>(null);
  const newSessionRequestRef = useRef<Promise<string | null> | null>(null);

  // APOS 数据流转链路初始化
  useAPOSInitialization();

  // 同步 sessionId 到 activityStore
  useEffect(() => {
    const unsubscribe = useSessionStore.subscribe(
      (state) => state.sessionId,
      (sessionId, prevSessionId) => {
        if (sessionId) {
          // 仅当会话真正切换时清理 UI 状态（不清空 activities）
          if (prevSessionId && prevSessionId !== sessionId) {
            useActivityStore.getState().clearForNewSession();
            useJourneyVerifyStore.getState().reset();
          }
          useActivityStore.getState().setCurrentSessionId(sessionId);
          useWorkbenchViewStore.getState().setActiveSession(sessionId);
        } else {
          useActivityStore.getState().clearAll();
          useJourneyVerifyStore.getState().reset();
          useWorkbenchViewStore.getState().setActiveSession(null);
        }
      }
    );
    // 初始化时如果已有 sessionId，立即同步（不清空 activities，防止与 handleSessionRestore 竞态）
    const currentId = useSessionStore.getState().sessionId;
    if (currentId) {
      useActivityStore.getState().setCurrentSessionId(currentId);
    }
    useWorkbenchViewStore.getState().setActiveSession(currentId);
    return () => { unsubscribe(); };
  }, []);

  // 技能列表
  const [skills, setSkills] = useState<SkillItem[]>([]);
  const [selectedSkill, setSelectedSkill] = useState<string | null>(null);

  // 加载配置
  useEffect(() => {
    loadConfig();
  }, [loadConfig]);

  // 动态加载技能列表
  useEffect(() => {
    fetch('/api/skills')
      .then(r => r.json())
      .then((data: SkillItem[]) => setSkills(data))
      .catch(() => {});
  }, []);

  // 内置命令
  const builtinCommands: Command[] = useMemo(() => [
    { name: 'help', description: '显示帮助信息', group: 'Commands' },
    { name: 'clear', description: '清除对话记录', group: 'Commands' },
    { name: 'compact', description: '压缩对话上下文', group: 'Commands' },
    { name: 'model', description: '切换 AI 模型', group: 'Commands' },
  ], []);

  // 将技能转换为 Command 格式
  const allCommands: Command[] = useMemo(() => {
    const skillCommands: Command[] = skills.map(s => ({
      name: `skill ${s.name}`,
      description: s.description,
      group: 'Skills',
      hidden: false,
    }));
    return [...builtinCommands, ...skillCommands];
  }, [builtinCommands, skills]);

  const addSessionError = useCallback((content: string) => {
    addMessage({
      uuid: generateUUID(),
      type: 'system',
      content,
      timestamp: Date.now(),
      subtype: 'error',
      errorCode: 'SESSION_PREPARE_ERROR',
    } as Message);
  }, [addMessage]);

  const ensureSessionReady = useCallback((): Promise<string | null> => {
    if (sessionReadinessRef.current) {
      return sessionReadinessRef.current;
    }
    const operation = (async () => {
      // A folder selection/new Session request is an explicit user intent.
      // Wait for it instead of falling back to the still-committed old Session.
      if (newSessionRequestRef.current) {
        return newSessionRequestRef.current;
      }
      const pendingActivation = getPendingSessionActivation();
      if (pendingActivation) {
        const result = await pendingActivation;
        return result.status === 'activated' ? result.sessionId : null;
      }
      let sessionId = useSessionStore.getState().sessionId;
      if (!sessionId) {
        sessionId = await requestAuthorizedSession();
      }
      if (!sessionId) return null;
      const activation = await activateSessionCandidate(sessionId);
      if (activation.status === 'activated') return sessionId;
      if (activation.status === 'superseded') return null;
      throw activation.error;
    })();
    const tracked = operation.finally(() => {
      if (sessionReadinessRef.current === tracked) {
        sessionReadinessRef.current = null;
      }
    });
    sessionReadinessRef.current = tracked;
    return tracked;
  }, []);

  // 粘贴图片发布：核心逻辑抽取到 utils/pasteImagePublisher 以便单测锁定契约，
  // 此处仅做薄封装注入 ensureSessionReady。
  const publishPastedImages = useCallback(
    (files: File[]): Promise<PastePublishResult> => publishPastedImagesCore(files, ensureSessionReady),
    [ensureSessionReady],
  );

  // 发送消息
  const handleSubmit = useCallback(async (event: SubmitEvent) => {
    let currentSessionId: string | null;
    try {
      currentSessionId = await ensureSessionReady();
    } catch (error) {
      console.error('[App] Failed to prepare authorized session:', error);
      addSessionError(error instanceof Error
        ? `无法准备授权会话：${error.message}`
        : '无法准备授权会话，请检查服务后重试。');
      return false;
    }
    if (!currentSessionId) return false;

    const currentStatus = useSessionStore.getState().status;
    if (currentStatus === 'streaming' || currentStatus === 'waiting_permission') {
      if (event.attachments && event.attachments.length > 0) {
        useNotificationStore.getState().addNotification({
          key: 'run-input-attachments',
          level: 'warning',
          message: '运行中干预暂不支持附件，请先移除附件',
          timeout: 5000,
        });
        return false;
      }
      const interventionText = event.text?.trim();
      if (!interventionText) return false;
      if (!sendRunInput(generateUUID(), interventionText)) {
        addSessionError('运行中指令未发送，请检查 WebSocket 连接后重试。');
        return false;
      }
      return true;
    }
    if (currentStatus === 'compacting') return false;

    // 在 bind/restore 完成后再添加用户消息，确保不被恢复流程清除。
    const contentBlocks: any[] = [];
    if (event.text) {
      contentBlocks.push({ type: 'text', text: event.text });
    }
    if (event.attachments && event.attachments.length > 0) {
      for (const att of event.attachments) {
        if (att.type === 'image' && (att.base64Data || att.url)) {
          contentBlocks.push({
            type: 'image',
            mediaType: att.mediaType || 'image/png',
            base64Data: att.base64Data,
            url: att.url,
          });
        }
      }
    }
    if (contentBlocks.length === 0) {
      contentBlocks.push({ type: 'text', text: '' });
    }
    // 通过 STOMP 发送用户消息到后端。
    const sent = sendToServer('/app/chat', {
      text: event.text,
      attachments: event.attachments || [],
      references: [],
    });
    if (!sent) {
      addSessionError('消息未发送，请检查 WebSocket 连接后重试。');
      return false;
    }

    useSessionStore.getState().setStatus('streaming');
    addMessage({
      uuid: generateUUID(),
      type: 'user',
      content: contentBlocks,
      timestamp: Date.now(),
    });
    return true;
  }, [addMessage, addSessionError, ensureSessionReady]);

  const rejectCommandWhileBusy = useCallback((): boolean => {
    if (useSessionStore.getState().status === 'idle') return false;
    const notifications = useNotificationStore.getState();
    notifications.removeNotification('command-blocked-while-running');
    notifications.addNotification({
      key: 'command-blocked-while-running',
      level: 'warning',
      message: '当前任务正在运行；请直接输入干预信息，或停止任务后再执行命令',
      timeout: 5000,
    });
    return true;
  }, []);

  // 处理命令
  const handleSlashCommand = useCallback(async (command: string) => {
    if (rejectCommandWhileBusy()) return false;
    const raw = command.startsWith('/') ? command.slice(1) : command;
    // 技能命令：/skill <name> → 打开详情弹窗
    if (raw.startsWith('skill ')) {
      const skillName = raw.slice(6).trim();
      if (skillName) {
        setSelectedSkill(skillName);
        return true;
      }
    }

    try {
      const sessionId = await ensureSessionReady();
      if (!sessionId) return false;
    } catch (error) {
      addSessionError(error instanceof Error
        ? `无法执行命令：${error.message}`
        : '无法执行命令，请检查服务后重试。');
      return false;
    }

    const parts = raw.split(/\s+/);
    if (!sendSlashCommand(parts[0], parts.slice(1).join(' '))) {
      addSessionError('命令未发送，请检查 WebSocket 连接后重试。');
      return false;
    }

    // 服务端已受理后再添加系统消息到 UI。
    addMessage({
      uuid: generateUUID(),
      type: 'system',
      content: `执行命令: /${raw}`,
      timestamp: Date.now(),
      subtype: 'command',
    } as Message);

    return true;
  }, [addMessage, addSessionError, ensureSessionReady, rejectCommandWhileBusy]);

  // 执行技能
  const executeSkill = useCallback(async (skillName: string, userInput: string) => {
    if (rejectCommandWhileBusy()) return;
    try {
      const sessionId = await ensureSessionReady();
      if (!sessionId) return;
    } catch (error) {
      addSessionError(error instanceof Error
        ? `无法执行技能：${error.message}`
        : '无法执行技能，请检查服务后重试。');
      return;
    }
    const args = userInput ? `${skillName} ${userInput}` : skillName;
    if (!sendSlashCommand('skill', args)) {
      addSessionError('技能命令未发送，请检查 WebSocket 连接后重试。');
      return;
    }
    setSelectedSkill(null);
  }, [addSessionError, ensureSessionReady, rejectCommandWhileBusy]);

  const startNewAuthorizedSession = useCallback(
    (): Promise<string | null> => {
      if (newSessionRequestRef.current) {
        return newSessionRequestRef.current;
      }
      const operation = (async () => {
        try {
          const newSessionId = await requestAuthorizedSession();
          if (!newSessionId) return null;
          const activation = await activateSessionCandidate(newSessionId);
          if (activation.status === 'superseded') return null;
          if (activation.status === 'failed') throw activation.error;
          window.dispatchEvent(new Event('session-list-updated'));
          return activation.sessionId;
        } catch (error) {
          console.error('[App] Failed to create authorized session:', error);
          addSessionError(error instanceof Error
            ? `新建授权会话失败：${error.message}`
            : '新建授权会话失败，请重试。');
          return null;
        }
      })();
      const tracked = operation.finally(() => {
        if (newSessionRequestRef.current === tracked) {
          newSessionRequestRef.current = null;
        }
      });
      newSessionRequestRef.current = tracked;
      return tracked;
    }, [addSessionError]);

  useEffect(() => {
    const handler = () => { void startNewAuthorizedSession(); };
    window.addEventListener(NEW_AUTHORIZED_SESSION_EVENT, handler);
    return () => window.removeEventListener(
      NEW_AUTHORIZED_SESSION_EVENT,
      handler,
    );
  }, [startNewAuthorizedSession]);

  // 中断请求
  const handleInterrupt = useCallback(() => {
    // 通过 store 中断前端状态
    useSessionStore.getState().abort();
    // 发送 WebSocket 中断消息到后端
    sendToServer('/app/interrupt', { isSubmitInterrupt: false });
  }, []);

  return (
    <>
      <AppLayout>
        <div className="h-full flex flex-col">
          <div className="flex-1 overflow-hidden">
            {workbenchEnabled && viewMode === 'simple' ? (
              <SimpleWorkbench sessionId={sessionId} messages={messages} status={status} />
            ) : messages.length === 0 ? (
                <div className="h-full flex items-center justify-center text-[var(--text-muted)]">
                  <div className="text-center">
                    <div className="text-4xl mb-3">💬</div>
                    <div className="text-lg font-medium text-[var(--text-primary)]">开始对话</div>
                    <div className="text-sm mt-2">
                      输入消息或按 <kbd className="px-2 py-0.5 bg-[var(--bg-secondary)] rounded">/</kbd> 查看命令
                    </div>
                  </div>
                </div>
              ) : (
                <MessageList />
              )}
          </div>

          {(!workbenchEnabled || viewMode === 'development') && <JourneyVerifyPanel />}

          {/* Input */}
          <div className="border-t border-[var(--border)] p-4 bg-[var(--bg-secondary)]">
            <PromptInput
              onSubmit={handleSubmit}
              onSlashCommand={handleSlashCommand}
              onInterrupt={handleInterrupt}
              onPasteImages={publishPastedImages}
              disabled={false}
              runActive={status === 'streaming' || status === 'waiting_permission'}
              compacting={status === 'compacting'}
              permissionMode="read_write"
              messages={messages}
              commands={allCommands}
              simpleMode={workbenchEnabled && viewMode === 'simple'}
            />
          </div>
        </div>
      </AppLayout>
      
      {/* Skill Detail Modal */}
      {selectedSkill && (
        <SkillDetailModal
          skillName={selectedSkill}
          onClose={() => setSelectedSkill(null)}
          onExecute={executeSkill}
        />
      )}

      {/* RV-4 Mobile Approval Sheet — 验证注意通知浮层 */}
      <MobileApprovalSheet />

      {/* Global Dialogs */}
      <DialogManager />
      <ProjectSelectionDialog />
    </>
  );
}

export default App;
