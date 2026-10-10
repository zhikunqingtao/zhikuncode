import { act, cleanup, render } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useConfigStore } from '@/store/configStore';
import { GalaxyFxLayer } from '../GalaxyFxLayer';

const mocks=vi.hoisted(()=>({start:vi.fn(),stop:vi.fn()}));
vi.mock('../galaxy/controller',()=>({createGalaxyController:mocks.start}));
afterEach(()=>{cleanup();useConfigStore.getState().setTheme({mode:'light'});vi.clearAllMocks();});
async function theme(mode:'galaxy'|'light'){await act(async()=>{useConfigStore.getState().setTheme({mode});await Promise.resolve()})}

describe('Galaxy skin graphics lifecycle',()=>{
    it('isolates input and tears down graphics on every theme exit',async()=>{
        mocks.start.mockImplementation(()=>mocks.stop);await theme('light');const view=render(<GalaxyFxLayer/>);
        expect(view.queryByTestId('galaxy-fx-layer')).toBeNull();
        for(let i=0;i<20;i++){
            await theme('galaxy');expect(view.getByTestId('galaxy-fx-layer')).toHaveAttribute('aria-hidden','true');
            await theme('light');expect(view.queryByTestId('galaxy-fx-layer')).toBeNull();
        }
        expect(mocks.start).toHaveBeenCalledTimes(20);expect(mocks.stop).toHaveBeenCalledTimes(20);
    });
    it('contains WebGL failure within decoration',async()=>{
        mocks.start.mockImplementation(()=>{throw new Error('WebGL unavailable')});await theme('galaxy');
        const view=render(<><button>发送消息</button><GalaxyFxLayer/></>);
        await act(async()=>{await Promise.resolve()});
        expect(view.getByTestId('galaxy-fx-layer')).toHaveAttribute('data-state','fallback');expect(view.getByRole('button')).toBeEnabled();
    });
});
