import { createContext } from 'react';

/** Only cards actually mounted outside the process area suppress their nested copy. */
export const PublicationDisplayContext = createContext<ReadonlyMap<string, string>>(new Map());
