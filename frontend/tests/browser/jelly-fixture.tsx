import { useState } from 'react';
import { createRoot } from 'react-dom/client';
import PromptSendButton from '../../src/components/input/PromptInput/PromptSendButton';
import UserMessage from '../../src/components/message/UserMessage';
import { ActivityCardL3 } from '../../src/components/apos/ActivityCardL3';
import { JellyFxLayer } from '../../src/components/theme/JellyFxLayer';
import { JellyTowerHero } from '../../src/components/theme/JellyTowerHero';
import { awakeCount, registeredCount, isTicking, getSpring } from '../../src/components/theme/jellySpring';
import { applyAccent, DEFAULT_ACCENT_HEX } from '../../src/theme/accents';
import { useConfigStore } from '@/store/configStore';
import type { ActivityData, RiskAssessment } from '../../src/types/apos';

// All component implementations are real; the runner replaces only store adapters.
const canvas = document.createElement('canvas');
canvas.width = 160; canvas.height = 100;
canvas.getContext('2d')!.fillRect(0, 0, 160, 100);
const png = canvas.toDataURL('image/png').split(',')[1];
const activity: ActivityData = {
    id: 'synthetic-diff', operationType: 'file_edit', summary: 'Synthetic file change',
    status: 'completed', timestamp: 1791075600000, decision: 'approved',
    changedFiles: [{ filePath: 'synthetic.ts', additions: 1, deletions: 1, changeType: 'modified',
        diffContent: '  unchanged line\n- removed line\n+ added line' }],
};
const assessment: RiskAssessment = {
    deterministic: {
        typeCheck: { passed: true, errorCount: 0, details: 'Synthetic TypeScript log: no errors' },
        lint: { passed: true, errorCount: 0, warningCount: 0 },
        tests: { passed: true, passedCount: 1, failedCount: 0 },
    },
    heuristic: { affectedApiCount: 0, indirectImpactCount: 0, potentialImpactCount: 0,
        hasHighConfidenceImpact: false, truncated: false, filesAffected: [] },
    signal: 'green', reason: 'Synthetic test only',
};
const fixture = {
    sent: 0, copiedImages: 0,
    configure(motion: 'full' | 'reduced' | 'off') {
        document.documentElement.className = `jelly fx-jelly-rich motion-${motion}`;
        applyAccent(DEFAULT_ACCENT_HEX, 'jelly');
        useConfigStore.setState({ theme: { mode: 'jelly', accentColor: DEFAULT_ACCENT_HEX,
            jellyFx: { cinematic: true, motion } } });
    },
    engine: () => ({ awake: awakeCount(), registered: registeredCount(), ticking: isTicking(),
        heroRegistered: !!getSpring(document.querySelector('.jelly-tower') as HTMLElement) }),
};
Object.assign(window, { jellyFixture: fixture });
// Never access host clipboard or persistence, even on a secure origin.
Object.defineProperty(navigator, 'clipboard', { configurable: true, value: {
    writeText: async () => {}, write: async () => { fixture.copiedImages += 1; },
} });
Object.defineProperty(window, 'ClipboardItem', { configurable: true, value: class {
    constructor(public data: Record<string, Blob>) {}
} });

function Fixture() {
    const motion = useConfigStore(s => s.theme.jellyFx?.motion);
    const [version, setVersion] = useState(0);
    const [showActivity, setShowActivity] = useState(false);
    return <main data-motion={motion}>
        <JellyFxLayer />
        <div className="chat-composer-surface m-4 p-4 flex items-center gap-4">
            <textarea aria-label="Synthetic input" className="w-24" />
            {(['desktop', 'mobile'] as const).map(variant => <div key={variant} data-send={variant}>
                <PromptSendButton runActive={false} sendDisabled={false} stopDisabled={false}
                    variant={variant} onSend={() => { fixture.sent += 1; }} onInterrupt={() => {}} />
            </div>)}
        </div>
        <section id="image"><UserMessage key={version} message={{ uuid: `synthetic-${version}`, type: 'user',
            timestamp: 1791075600000, content: [{ type: 'image', base64Data: png, mediaType: 'image/png' }],
        }} /></section>
        <button id="replace-message" onClick={() => setVersion(value => value + 1)}>Replace synthetic message</button>
        <button id="open-activity" onClick={() => setShowActivity(true)}>Inspect synthetic activity</button>
        <JellyTowerHero />
        {showActivity && <ActivityCardL3 activity={activity} assessment={assessment}
            onClose={() => setShowActivity(false)} onApprove={() => {}} onReject={() => {}} />}
    </main>;
}
fixture.configure('full');
createRoot(document.getElementById('root')!).render(<Fixture />);
