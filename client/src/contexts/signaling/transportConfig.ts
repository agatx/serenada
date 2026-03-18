import type { TransportKind } from '@serenada/core';
import { parseTransportOrder } from '@serenada/core';

export { parseTransportOrder } from '@serenada/core';

export const getConfiguredTransportOrder = (): TransportKind[] => {
    const raw = import.meta.env.TRANSPORTS || import.meta.env.VITE_TRANSPORTS;
    return parseTransportOrder(raw);
};
