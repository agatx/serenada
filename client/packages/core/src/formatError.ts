/** Safely extract a human-readable message from an unknown catch-block value. */
export function formatError(err: unknown): string {
    if (err instanceof Error) return err.message;
    return String(err);
}
