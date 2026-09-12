import { describe, it, expect } from 'vitest';
import { stripInternalMarkers } from './internalMarkers';

describe('stripInternalMarkers', () => {
    it('剥离单个旧格式前缀标记', () => {
        expect(stripInternalMarkers('[skeleton] 这是内容')).toBe('这是内容');
        expect(stripInternalMarkers('[final] 最终回复')).toBe('最终回复');
    });

    it('剥离新格式前缀标记', () => {
        expect(stripInternalMarkers('[content compressed by system] 摘要内容')).toBe('摘要内容');
        expect(stripInternalMarkers('[content truncated by system] 被截断的内容')).toBe('被截断的内容');
        expect(stripInternalMarkers('[collapsed] 内容')).toBe('内容');
        expect(stripInternalMarkers('[summary-collapsed] 内容')).toBe('内容');
        expect(stripInternalMarkers('[tool result cleared — superseded] 内容')).toBe('内容');
    });

    it('剥离连续多个前缀标记', () => {
        expect(stripInternalMarkers('[skeleton] [collapsed] 内容')).toBe('内容');
        expect(stripInternalMarkers('[final][skeleton] 内容')).toBe('内容');
    });

    it('大小写不敏感', () => {
        expect(stripInternalMarkers('[SKELETON] 内容')).toBe('内容');
        expect(stripInternalMarkers('[Content Compressed By System] 内容')).toBe('内容');
    });

    it('多行模式：剥离每一行行首的标记', () => {
        const input = '[skeleton] 第一行\n[collapsed] 第二行\n正常第三行';
        expect(stripInternalMarkers(input)).toBe('第一行\n第二行\n正常第三行');
    });

    it('行中标记不受前缀正则影响', () => {
        expect(stripInternalMarkers('前文 [skeleton] 后文')).toBe('前文 [skeleton] 后文');
    });

    it('剥离结尾的截断后缀标记', () => {
        expect(stripInternalMarkers('内容主体... [collapsed: 500 chars]')).toBe('内容主体');
        expect(stripInternalMarkers('内容主体... [content truncated by system]')).toBe('内容主体');
        expect(stripInternalMarkers('内容... [summary-collapsed: 12 chars] ')).toBe('内容');
    });

    it('剥离连续多个后缀标记', () => {
        expect(stripInternalMarkers('内容... [collapsed: 10 chars]... [content truncated by system]')).toBe('内容');
    });

    it('同时剥离前缀与后缀', () => {
        expect(stripInternalMarkers('[skeleton] 内容... [collapsed: 99 chars]')).toBe('内容');
    });

    it('正常文本不受影响', () => {
        expect(stripInternalMarkers('普通回复文本')).toBe('普通回复文本');
        expect(stripInternalMarkers('数组 [1, 2, 3] 是合法的')).toBe('数组 [1, 2, 3] 是合法的');
        expect(stripInternalMarkers('[TODO] 这不是内部标记')).toBe('[TODO] 这不是内部标记');
        expect(stripInternalMarkers('省略号结尾...')).toBe('省略号结尾...');
    });

    it('空输入安全返回原值', () => {
        expect(stripInternalMarkers('')).toBe('');
        expect(stripInternalMarkers(undefined as unknown as string)).toBe(undefined);
        expect(stripInternalMarkers(null as unknown as string)).toBe(null);
    });

    it('纯标记输入剥离后为空字符串', () => {
        expect(stripInternalMarkers('[skeleton]')).toBe('');
        expect(stripInternalMarkers('... [collapsed: 100 chars]')).toBe('');
    });
});
