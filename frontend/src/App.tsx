import { useCallback, useEffect, useState, useMemo } from 'react';
import { AppLayout } from '@/components/layout';
import { MessageList } from '@/components/message';
import { PromptInput } from '@/components/input';
import { DialogManager } from '@/components/DialogManager';
import { useMessageStore } from '@/store/messageStore';
import { useSessionStore } from '@/store/sessionStore';
import { useConfigStore } from '@/store/configStore';
import { sendToServer, isWsConnected, sendSlashCommand } from '@/api/stompClient';
import { SkillDetailModal } from '@/components/skills/SkillDetailModal';
import type { SubmitEvent, Message, Command } from '@/types';

interface SkillItem {
  name: string;
  description: string;
  source: string;
}

function App() {
  const { messages, addMessage } = useMessageStore();
  const { createSession, status } = useSessionStore();
  const { loadConfig } = useConfigStore();

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
    const raw = command.startsWith('/') ? command.slice(1) : command;
    // 技能命令：/skill <name> → 打开详情弹窗
    if (raw.startsWith('skill ')) {
      const skillName = raw.slice(6).trim();
      if (skillName) {
        setSelectedSkill(skillName);
        return;
      }
    }

    // 添加系统消息到 UI
    addMessage({
      uuid: crypto.randomUUID(),
      type: 'system',
      content: `执行命令: /${raw}`,
      timestamp: Date.now(),
      subtype: 'command',
    } as Message);

    // 其他 slash 命令通过 STOMP 发送
    const parts = raw.split(/\s+/);
    sendSlashCommand(parts[0], parts.slice(1).join(' '));
  }, [addMessage]);

  // 执行技能
  const executeSkill = useCallback((skillName: string) => {
    sendSlashCommand('skill', skillName);
    setSelectedSkill(null);
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
              commands={allCommands}
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

      {/* Global Dialogs */}
      <DialogManager />
    </>
  );
}

export default App;
