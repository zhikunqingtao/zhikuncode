import { StrictMode } from 'react';
import { act, cleanup, fireEvent, render } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useConfigStore } from '@/store/configStore';
import type { JellyFxConfig } from '@/types';
import { JellyFxLayer } from '../JellyFxLayer';
import { JellyTowerHero } from '../JellyTowerHero';
import { awakeCount, getSpring, isTicking, registeredCount, teardownAll } from '../jellySpring';

const REDUCED_MOTION = '(prefers-reduced-motion: reduce)';
let reducedMotion = false;
let mediaQueries: Map<string, MediaQueryList>;

function setFx(overrides: Partial<JellyFxConfig> = {}): void {
    act(() => useConfigStore.getState().setTheme({
        mode: 'jelly',
        jellyFx: { cinematic: true, motion: 'reduced', ...overrides },
    }));
}

function setReducedMotion(matches: boolean): void {
    act(() => {
        reducedMotion = matches;
        const event = new Event('change');
        Object.defineProperty(event, 'matches', { value: matches });
        mediaQueries.get(REDUCED_MOTION)?.dispatchEvent(event);
    });
}

async function flushBridgeScan(): Promise<void> {
    await act(async () => {
        // MutationObserver schedules a debounced scan after the React commit.
        await Promise.resolve();
        vi.advanceTimersByTime(180);
    });
}

function Fixture({ cardKey = 0, textareaKey = 0, layer = true, hero = false }: {
    cardKey?: number;
    textareaKey?: number;
    layer?: boolean;
    hero?: boolean;
}) {
    return (
        <>
            <main>
                <div className="turn-card"><section key={cardKey} data-testid="card">message</section></div>
                <div className="chat-composer-surface" data-testid="composer">
                    <textarea key={textareaKey} aria-label="消息" />
                    <button aria-label="发送消息">发送</button>
                    <button aria-label="发送运行中干预">干预</button>
                </div>
            </main>
            <aside className="app-sidebar"><button className="bg-accent2-strong">新建会话</button></aside>
            {layer && <JellyFxLayer />}
            {hero && <JellyTowerHero />}
        </>
    );
}

beforeEach(() => {
    vi.useFakeTimers();
    reducedMotion = false;
    mediaQueries = new Map();
    vi.stubGlobal('matchMedia', vi.fn((query: string) => {
        if (!mediaQueries.has(query)) {
            const media = new EventTarget() as MediaQueryList;
            Object.defineProperties(media, {
                matches: { get: () => query === REDUCED_MOTION ? reducedMotion : query === '(pointer: fine)' },
                media: { value: query },
            });
            mediaQueries.set(query, media);
        }
        return mediaQueries.get(query);
    }));
    setFx();
});

afterEach(() => {
    cleanup();
    teardownAll();
    act(() => useConfigStore.getState().resetTheme());
    vi.useRealTimers();
    vi.unstubAllGlobals();
});

describe('JellyFxLayer · React 绑定生命周期', () => {
    it('不在发送/干预/新建按钮本体绑定按压 spring', () => {
        const view = render(<Fixture />);
        for (const button of view.getAllByRole('button')) {
            fireEvent.pointerDown(button);
            act(() => vi.advanceTimersByTime(500));
            expect(getSpring(button)).toBeNull();
            expect(button.style.transform).toBe('');
        }
    });

    it('重复 key 替换后释放旧卡片和监听，注册规模保持稳定，新卡片仍响应悬停', async () => {
        const view = render(<Fixture />);
        const initialCount = registeredCount();
        for (let key = 1; key <= 4; key++) {
            const oldCard = view.getByTestId('card');
            const oldSpring = getSpring(oldCard)!;
            view.rerender(<Fixture cardKey={key} />);
            await flushBridgeScan();
            expect(getSpring(oldCard)).toBeNull();
            expect(registeredCount()).toBe(initialCount);
            fireEvent.pointerEnter(oldCard);
            expect(oldSpring.ty.t).toBe(0);
            const card = view.getByTestId('card');
            fireEvent.pointerEnter(card);
            act(() => vi.advanceTimersByTime(64));
            expect(card.style.transform).not.toBe('');
        }
    });

    it('保留输入容器而替换 textarea 时，旧 focus 监听释放，新输入框正常绑定', async () => {
        const view = render(<Fixture />);
        const composer = view.getByTestId('composer');
        const oldTextarea = view.getByRole('textbox');
        const oldSpring = getSpring(composer)!;
        view.rerender(<Fixture textareaKey={1} />);
        await flushBridgeScan();
        expect(getSpring(composer)).not.toBe(oldSpring);
        fireEvent.focus(oldTextarea);
        expect(oldSpring.sx.t).toBe(1);
        fireEvent.focus(view.getByRole('textbox'));
        act(() => vi.advanceTimersByTime(64));
        expect(composer.style.transform).not.toBe('');
        expect(registeredCount()).toBe(2);
    });

    it('卸载桥接只释放自己的资源，Hero 保持运行', () => {
        setFx({ motion: 'full' });
        const view = render(<Fixture hero />);
        const tower = view.container.querySelector<HTMLElement>('.jelly-tower')!;
        const towerSpring = getSpring(tower);
        const oldCard = view.getByTestId('card');
        view.rerender(<Fixture layer={false} hero />);
        expect(getSpring(oldCard)).toBeNull();
        expect(getSpring(tower)).toBe(towerSpring);
        expect(registeredCount()).toBe(4);
        act(() => vi.advanceTimersByTime(64));
        expect(tower.style.transform).not.toBe('');
        expect(isTicking()).toBe(true);
    });

    it('卸载 Hero 不会取消桥接事件', () => {
        const view = render(<Fixture hero />);
        const card = view.getByTestId('card');
        const cardSpring = getSpring(card);
        view.rerender(<Fixture />);
        expect(getSpring(card)).toBe(cardSpring);
        fireEvent.pointerEnter(card);
        act(() => vi.advanceTimersByTime(64));
        expect(card.style.transform).not.toBe('');
        expect(registeredCount()).toBe(2);
    });

    it('StrictMode 重挂与完全卸载不保留 spring 或事件监听', () => {
        const view = render(<StrictMode><Fixture hero /></StrictMode>);
        expect(registeredCount()).toBe(6);
        const card = view.getByTestId('card');
        const spring = getSpring(card)!;
        view.unmount();
        fireEvent.pointerEnter(card);
        expect(spring.ty.t).toBe(0);
        expect(registeredCount()).toBe(0);
        expect(awakeCount()).toBe(0);
        expect(isTicking()).toBe(false);
    });
});

describe('JellyFxLayer / JellyTowerHero · 动效门控', () => {
    it('reduced 入场和悬停途中切 off，立即复位；重新开启后恢复事件反馈', () => {
        const view = render(<Fixture hero />);
        const tower = view.container.querySelector<HTMLElement>('.jelly-tower')!;
        const card = view.getByTestId('card');
        fireEvent.pointerEnter(card);
        act(() => vi.advanceTimersByTime(64));
        expect(tower.style.transform).not.toBe('');
        expect(card.style.transform).not.toBe('');
        setFx({ motion: 'off' });
        expect(tower.style.transform).toBe('');
        expect(tower.style.transformOrigin).toBe('');
        expect(card.style.transform).toBe('');
        expect(registeredCount()).toBe(0);
        expect(isTicking()).toBe(false);
        act(() => vi.advanceTimersByTime(1000));
        expect(tower.style.transform).toBe('');
        expect(view.container.querySelector('.jelly-hero')).toHaveClass('lit');
        setFx({ motion: 'reduced' });
        fireEvent.pointerEnter(card);
        act(() => vi.advanceTimersByTime(64));
        expect(card.style.transform).not.toBe('');
        expect(registeredCount()).toBe(6);
    });

    it.each(['off', 'system'] as const)('%s 关闭运行中的装饰/Hero/视差；恢复 full 后重新运行', (stopMode) => {
        setFx({ motion: 'full' });
        const view = render(<Fixture hero />);
        const tower = view.container.querySelector<HTMLElement>('.jelly-tower')!;
        const highlight = view.container.querySelector<HTMLElement>('.jelly-mousse-hl')!;
        const wash = view.container.querySelector<HTMLElement>('.jelly-wash')!;
        fireEvent(window, new MouseEvent('pointermove', { clientX: window.innerWidth }));
        act(() => vi.advanceTimersByTime(64));
        expect(tower.style.transform).not.toBe('');
        expect(highlight.style.transform).not.toBe('');
        expect(wash.style.transform).not.toBe('');
        if (stopMode === 'system') setReducedMotion(true);
        else setFx({ motion: 'off' });
        expect(registeredCount()).toBe(0);
        expect(awakeCount()).toBe(0);
        expect(isTicking()).toBe(false);
        for (const element of [tower, highlight, wash]) expect(element.style.transform).toBe('');
        fireEvent(window, new MouseEvent('pointermove', { clientX: 0 }));
        act(() => vi.advanceTimersByTime(10000));
        for (const element of [tower, highlight, wash]) expect(element.style.transform).toBe('');
        if (stopMode === 'system') setReducedMotion(false);
        else setFx({ motion: 'full' });
        act(() => vi.advanceTimersByTime(64));
        expect(tower.style.transform).not.toBe('');
        expect(wash.style.transform).not.toBe('');
        expect(isTicking()).toBe(true);
    });
});
