import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { SitePublicationRenderer } from './SitePublicationRenderer';
import type { SitePublicationResult } from '@/types';

const publication: SitePublicationResult = {
    schema: 'site-publication/v1', provider: 'meoo', publicationId: 'id', label: '3D 园区',
    runtime: 'static', state: 'public_verified', projectId: 'site', version: '1',
    url: 'https://site.meoo.fun', projectUrl: 'https://meoo.com/chat/site',
};
describe('SitePublicationRenderer', () => {
    it('opens and copies the server issued link', async () => {
        const writeText = vi.fn().mockResolvedValue(undefined);
        Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } });
        render(<SitePublicationRenderer publication={publication} />);
        expect(screen.getByRole('link', { name: '打开网站' })).toHaveAttribute('href', publication.url);
        fireEvent.click(screen.getByRole('button', { name: '复制链接' }));
        await waitFor(() => expect(writeText).toHaveBeenCalledWith(publication.url));
        expect(screen.getByRole('status')).toHaveTextContent('匿名公网访问已验证');
    });
    it('shows unknown result without claiming cancellation or public success', () => {
        render(<SitePublicationRenderer publication={{ ...publication, state: 'unknown', url: undefined }} />);
        expect(screen.getByRole('status')).toHaveTextContent('远端结果待确认');
        expect(screen.queryByRole('link', { name: '打开网站' })).toBeNull();
        expect(screen.getByRole('link', { name: '秒悟项目设置' })).toHaveAttribute('href', publication.projectUrl);
    });
    it('never shows retry guidance on a verified success card', () => {
        render(<SitePublicationRenderer publication={{ ...publication, guidance: '请检查秒悟项目状态，不要自动重发。' }} />);
        expect(screen.getByRole('status')).toHaveTextContent('匿名公网访问已验证');
        expect(screen.queryByText('请检查秒悟项目状态，不要自动重发。')).toBeNull();
    });
});
