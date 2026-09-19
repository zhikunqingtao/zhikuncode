import { describe, expect, it } from 'vitest';
import type { ContentBlock, Message } from '@/types';
import { buildAsrContext } from './asrContext';

const user = (uuid: string, content: ContentBlock[], meta?: Record<string, unknown>): Message => ({
    type: 'user', uuid, timestamp: 1, content, ...(meta ? { meta } : {}),
});
const userText = (uuid: string, text: string): Message => user(uuid, [{ type: 'text', text }]);
const assistant = (uuid: string, content: ContentBlock[]): Message => ({
    type: 'assistant', uuid, timestamp: 1, content, stopReason: 'end_turn',
    usage: { inputTokens: 0, outputTokens: 0, cacheReadInputTokens: 0, cacheCreationInputTokens: 0 },
});
const assistantText = (uuid: string, text: string): Message => assistant(uuid, [{ type: 'text', text }]);

describe('buildAsrContext', () => {
    it('空 messages 或无合格轮时返回空字符串', () => {
        expect(buildAsrContext([])).toBe('');
        expect(buildAsrContext([userText('q', '只有问题没有回答')])).toBe('');
    });

    it('无 answer 的轮（含运行中）被跳过，继续往前取', () => {
        const messages = [
            userText('q1', '第一个问题'), assistantText('a1', '第一个回答'),
            userText('q2', '第二个问题'),
            assistant('work', [{ type: 'tool_use', toolUseId: 't1', toolName: 'Read', input: {} }]),
        ];
        expect(buildAsrContext(messages)).toBe('用户：第一个问题\n助手：第一个回答');
        // 回复为纯空白同样视为无 answer
        const blankAnswer = [
            userText('q1', '第一个问题'), assistantText('a1', '第一个回答'),
            userText('q2', '第二个问题'), assistantText('a2', '   '),
        ];
        expect(buildAsrContext(blankAnswer)).toBe('用户：第一个问题\n助手：第一个回答');
    });

    it('最多取最近 3 轮，按时间旧→新排列', () => {
        const messages = [
            userText('q1', '问题一'), assistantText('a1', '回答一'),
            userText('q2', '问题二'), assistantText('a2', '回答二'),
            userText('q3', '问题三'), assistantText('a3', '回答三'),
            userText('q4', '问题四'), assistantText('a4', '回答四'),
        ];
        expect(buildAsrContext(messages)).toBe(
            '用户：问题二\n助手：回答二\n\n用户：问题三\n助手：回答三\n\n用户：问题四\n助手：回答四',
        );
    });

    it('拼装格式：每轮 `用户：{query}\\n助手：{answer}`，轮之间空行分隔', () => {
        const messages = [
            userText('q1', '如何配置 Gradle'), assistantText('a1', '修改 build.gradle'),
            userText('q2', '报错怎么办'), assistantText('a2', '检查依赖版本'),
        ];
        expect(buildAsrContext(messages)).toBe(
            '用户：如何配置 Gradle\n助手：修改 build.gradle\n\n用户：报错怎么办\n助手：检查依赖版本',
        );
    });

    it('steering 干预消息与非 text 块不进入上下文', () => {
        const messages = [
            userText('q1', '介绍一下华为'),
            assistantText('a1', '华为是一家科技公司'),
            user('s1', [{ type: 'text', text: '顺便说说鸿蒙Next' }], { steering: true }),
            assistantText('a2', '鸿蒙Next是分布式操作系统'),
            user('q2', [
                { type: 'image', mediaType: 'image/png', url: 'data:image/png' },
                { type: 'text', text: '这张架构图说明什么' },
            ]),
            assistant('a3', [
                { type: 'image', mediaType: 'image/png', url: 'data:image/png' },
                { type: 'text', text: '图里是分层的微服务架构' },
            ]),
        ];
        const result = buildAsrContext(messages);
        expect(result).toBe(
            '用户：介绍一下华为\n助手：鸿蒙Next是分布式操作系统\n\n用户：这张架构图说明什么\n助手：图里是分层的微服务架构',
        );
        expect(result).not.toContain('顺便说说鸿蒙Next');
    });

    it('超 1500 字符时截断较早轮（保留前段）并优先保留最近轮', () => {
        const newestTurnText = `用户：第三问\n助手：${'回'.repeat(990)}`;
        const olderTurnText = `用户：第二问\n助手：${'答'.repeat(590)}`;
        expect(newestTurnText).toHaveLength(1000);
        expect(olderTurnText).toHaveLength(600);
        const messages = [
            userText('q1', '第一问'), assistantText('a1', '旧'.repeat(100)),
            userText('q2', '第二问'), assistantText('a2', '答'.repeat(590)),
            userText('q3', '第三问'), assistantText('a3', '回'.repeat(990)),
        ];
        const result = buildAsrContext(messages);
        // 累加：最新轮 1000 + 分隔符 2 → 次新轮预算 498（<600），截断保留前段；最早轮直接丢弃
        expect(result).toHaveLength(1500);
        expect(result.endsWith(newestTurnText)).toBe(true);
        expect(result.startsWith(olderTurnText.slice(0, 498))).toBe(true);
        expect(result).not.toContain('第一问');
    });

    it('剩余预算不足 20 字符时直接停止，不再拼接更早轮', () => {
        const newestTurnText = `用户：问\n助手：${'回'.repeat(1482)}`;
        expect(newestTurnText).toHaveLength(1490);
        const messages = [
            userText('q1', '第一问'), assistantText('a1', '第一回'),
            userText('q2', '问'), assistantText('a2', '回'.repeat(1482)),
        ];
        // 剩余预算 1500-1490-2=8 < 20 → 直接停止，更早轮不进入上下文
        expect(buildAsrContext(messages)).toBe(newestTurnText);
    });
});
