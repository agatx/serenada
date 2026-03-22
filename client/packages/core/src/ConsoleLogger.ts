import type { SerenadaLogLevel, SerenadaLogger } from './types.js';

export class ConsoleSerenadaLogger implements SerenadaLogger {
    log(level: SerenadaLogLevel, tag: string, message: string): void {
        const formatted = `[${tag}] ${message}`;
        switch (level) {
            case 'debug': console.debug(formatted); break;
            case 'info': console.info(formatted); break;
            case 'warning': console.warn(formatted); break;
            case 'error': console.error(formatted); break;
        }
    }
}
