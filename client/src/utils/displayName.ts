const STORAGE_KEY = 'serenada_display_name';

export function getDisplayName(): string {
    try {
        return (localStorage.getItem(STORAGE_KEY) ?? '').trim();
    } catch {
        return '';
    }
}

export function setDisplayName(name: string): void {
    try {
        const trimmed = name.trim();
        if (trimmed) {
            localStorage.setItem(STORAGE_KEY, trimmed);
        } else {
            localStorage.removeItem(STORAGE_KEY);
        }
    } catch {
        // Ignore storage errors.
    }
}
