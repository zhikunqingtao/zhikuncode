import { useCallback, useEffect } from 'react';
import { AppLayout } from '@/components/layout';
import { MessageList } from '@/components/message';
import { PromptInput } from '@/components/input';
import { DialogManager } from '@/components/DialogManager';
import { useMessageStore } from '@/store/messageStore';
import { useSessionStore } from '@/store/sessionStore';
import { useConfigStore } from '@/store/configStore';
import { sendToServer, isWsConnected } from '@/hooks/useWebSocket';
import type { SubmitEvent, Message } from '@/types';

function App() {
  const { messages, addMessage } = useMessageStore();
  const { createSession, status } = useSessionStore();
  const { loadConfig } = useConfigStore();

  // 加载配置
  useEffect(() => {
    loadConfig();
  }, [loadConfig]);

  // 发送消息
  const handleSubmit = useCallback(async (event: SubmitEvent) => {
    console.log('[App] handleSubmit:', event.text);

    // 添加用户消息到 store
    addMessage({
      uuid: crypto.randomUUID(),
      type: 'user',
      content: [{ type: 'text', text: event.text }],
      timestamp: Date.now(),
    });

    // 创建会话（如果没有）
    let currentSessionId = useSessionStore.getState().sessionId;
    if (!currentSessionId) {
      try {
        const defaultModel = useConfigStore.getState().defaultModel ?? 'claude-sonnet-4-20250514';
        await createSession('.', defaultModel);
        currentSessionId = useSessionStore.getState().sessionId;
        console.log('[App] Session created:', currentSessionId);
      } catch (error) {
        console.error('[App] Failed to create session:', error);
        addMessage({
          uuid: crypto.randomUUID(),
          type: 'system',
          content: '连接服务器失败，请检查后端服务是否正常运行。',
          timestamp: Date.now(),
          subtype: 'error',
          errorCode: 'CONNECTION_ERROR',
        } as Message);
        useSessionStore.getState().setStatus('idle');
        return;
      }
    }

    // 等待 WebSocket 连接就绪
    if (!isWsConnected()) {
      console.warn('[App] WebSocket not connected, waiting...');
      await new Promise<void>((resolve) => {
        const check = setInterval(() => {
          if (isWsConnected()) { clearInterval(check); resolve(); }
        }, 100);
        // 最多等 3 秒
        setTimeout(() => { clearInterval(check); resolve(); }, 3000);
      });
    }

    // 绑定 WS session（确保后端 principal ↔ sessionId 映射）
    if (currentSessionId) {
      console.log('[App] Binding session:', currentSessionId);
      sendToServer('/app/bind-session', { sessionId: currentSessionId });
    }

    // 通过 STOMP 发送用户消息到后端
    console.log('[App] Sending user message via STOMP');
    sendToServer('/app/chat', {
      text: event.text,
      attachments: [],
      references: [],
    });
  }, [addMessage, createSession]);

  // 处理命令
  const handleSlashCommand = useCallback((command: string) => {
    console.log('Command:', command);
  }, []);

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
          {/* Message List */}
          <div className="flex-1 overflow-hidden">
            {messages.length === 0 ? (
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

          {/* Input */}
          <div className="border-t border-[var(--border)] p-4 bg-[var(--bg-secondary)]">
            <PromptInput
              onSubmit={handleSubmit}
              onSlashCommand={handleSlashCommand}
              onInterrupt={handleInterrupt}
              disabled={false}
              isLoading={status === 'streaming'}
              permissionMode="read_write"
              messages={messages}
              commands={[]}
            />
          </div>
        </div>
      </AppLayout>
      
      {/* Global Dialogs */}
      <DialogManager />
    </>
  );
}

export default App;
