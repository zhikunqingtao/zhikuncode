import type { TurnPublication } from '@/store/selectors/turnPublications';
import ExternalResourceRenderer from '../renderers/ExternalResourceRenderer';
import SitePublicationRenderer from '../renderers/SitePublicationRenderer';

export function TurnPublications({ cards, targets }: {
    cards: TurnPublication[];
    targets: ReadonlyMap<string, string>;
}) {
    if (!cards.length) return null;
    return (
        <section aria-label="发布成果" className="min-w-0 space-y-2 px-3 py-3 sm:px-4">
            {cards.map(card => (
                <div key={card.toolUseId} id={targets.get(card.toolUseId)} tabIndex={-1}
                    className="min-w-0 scroll-mt-4 break-words">
                    {card.kind === 'download'
                        ? <ExternalResourceRenderer resource={card.resource} />
                        : <SitePublicationRenderer publication={card.publication} />}
                </div>
            ))}
        </section>
    );
}
