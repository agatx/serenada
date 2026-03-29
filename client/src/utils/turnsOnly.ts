export function parseTurnsOnly(search: string): boolean {
    const params = new URLSearchParams(search);
    const raw = params.get('turnsOnly');
    if (raw == null) {
        return false;
    }

    switch (raw.trim().toLowerCase()) {
        case '':
        case '0':
        case 'false':
        case 'no':
        case 'off':
            return false;
        default:
            return true;
    }
}
