import type { Message } from '@/types';

export interface MessageTextResult {
    messageId: string;
    text: string;
    timestamp: number;
}

function messageText(message: Message): string {
    if (message.type !== 'user' && message.type !== 'assistant') return '';
    return message.content
        .filter(block => block.type === 'text')
        .map(block => block.type === 'text' ? block.text : '')
        .join('\n')
        .trim();
}

export function firstUserRequirement(messages: Message[]): MessageTextResult | null {
    for (const message of messages) {
        if (message.type !== 'user') continue;
        const text = messageText(message);
        if (text) return { messageId: message.uuid, text, timestamp: message.timestamp };
    }
    return null;
}

function compactTitle(text: string, maxLength = 34): string {
    const firstLine = text
        .replace(/相关本地文件：[\s\S]*$/u, '')
        .split('\n')
        .map(line => line.replace(/^[-#>*\s]+/, '').trim())
        .find(Boolean) ?? '';
    if (firstLine.length <= maxLength) return firstLine;
    return `${firstLine.slice(0, maxLength).trimEnd()}…`;
}

export function taskTitle(
    sessionTitle: string | null | undefined,
    messages: Message[],
    workingDirectory?: string | null,
    goalPreview?: string | null,
): string {
    const persisted = sessionTitle?.trim();
    if (persisted) return persisted;
    const requirement = firstUserRequirement(messages)?.text;
    if (requirement) return compactTitle(requirement);
    if (goalPreview?.trim()) return compactTitle(goalPreview);
    const folder = workingDirectory?.split(/[\\/]/).filter(Boolean).at(-1);
    return folder ? `${folder} 任务` : '新任务';
}
