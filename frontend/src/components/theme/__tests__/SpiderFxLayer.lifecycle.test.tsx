import { act, cleanup, render } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useConfigStore } from '@/store/configStore';
import { SpiderFxLayer } from '../SpiderFxLayer';

const mocks=vi.hoisted(()=>({start:vi.fn(),stop:vi.fn()}));
vi.mock('../spider/renderer',()=>({createSpiderScene:mocks.start}));
afterEach(()=>{cleanup();useConfigStore.getState().setTheme({mode:'light'});vi.clearAllMocks();});
async function theme(mode:'spider'|'light'){await act(async()=>{useConfigStore.getState().setTheme({mode});await Promise.resolve()})}

describe('Spider skin graphics lifecycle',()=>{
    it('isolates input and tears down graphics on every theme exit',async()=>{
        mocks.start.mockImplementation(()=>mocks.stop);await theme('light');const view=render(<SpiderFxLayer/>);
        expect(view.queryByTestId('spider-fx-layer')).toBeNull();
        for(let i=0;i<20;i++){
            await theme('spider');expect(view.getByTestId('spider-fx-layer')).toHaveAttribute('aria-hidden','true');
            await theme('light');expect(view.queryByTestId('spider-fx-layer')).toBeNull();
        }
        expect(mocks.start).toHaveBeenCalledTimes(20);expect(mocks.stop).toHaveBeenCalledTimes(20);
    });
    it('contains WebGL failure within decoration',async()=>{
        mocks.start.mockImplementation(()=>{throw new Error('WebGL unavailable')});await theme('spider');
        const view=render(<><button>发送消息</button><SpiderFxLayer/></>);
        await act(async()=>{await Promise.resolve()});
        expect(view.getByTestId('spider-fx-layer')).toHaveAttribute('data-state','fallback');expect(view.getByRole('button')).toBeEnabled();
    });
});
