import React, { useState, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { X } from 'lucide-react';

interface SavedRoomDialogProps {
    isOpen: boolean;
    onClose: () => void;
    mode: 'create' | 'rename';
    initialName?: string;
    roomId?: string; // used for shareable link
    onSave: (name: string) => void;
    onCopyLink?: () => void;
}

export const SavedRoomDialog: React.FC<SavedRoomDialogProps> = ({
    isOpen,
    onClose,
    mode,
    initialName = '',
    roomId,
    onSave,
}) => {
    const { t } = useTranslation();
    const [name, setName] = useState(initialName);
    const inputRef = React.useRef<HTMLInputElement>(null);
    const dialogRef = React.useRef<HTMLDivElement>(null);
    const previousFocusedElementRef = React.useRef<HTMLElement | null>(null);
    const titleId = React.useId();

    useEffect(() => {
        if (!isOpen) return;

        previousFocusedElementRef.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
        const focusTimer = window.setTimeout(() => {
            if (mode === 'rename' && inputRef.current) {
                inputRef.current.select();
            } else if (inputRef.current) {
                inputRef.current.focus();
            } else if (dialogRef.current) {
                dialogRef.current.focus();
            }
        }, 10);

        const handleKeyDown = (event: KeyboardEvent) => {
            if (event.key === 'Escape') {
                event.preventDefault();
                onClose();
                return;
            }
            if (event.key !== 'Tab' || !dialogRef.current) return;

            const focusableElements = Array.from(
                dialogRef.current.querySelectorAll<HTMLElement>(
                    'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])'
                )
            ).filter((element) => !element.hasAttribute('disabled'));
            if (focusableElements.length === 0) {
                event.preventDefault();
                dialogRef.current.focus();
                return;
            }

            const firstElement = focusableElements[0];
            const lastElement = focusableElements[focusableElements.length - 1];

            if (event.shiftKey) {
                if (document.activeElement === firstElement || !dialogRef.current.contains(document.activeElement)) {
                    event.preventDefault();
                    lastElement.focus();
                }
                return;
            }

            if (document.activeElement === lastElement) {
                event.preventDefault();
                firstElement.focus();
            }
        };

        document.addEventListener('keydown', handleKeyDown);
        return () => {
            window.clearTimeout(focusTimer);
            document.removeEventListener('keydown', handleKeyDown);
            previousFocusedElementRef.current?.focus();
            previousFocusedElementRef.current = null;
        };
    }, [isOpen, mode, onClose]);

    if (!isOpen) return null;

    const handleSubmit = (e: React.FormEvent) => {
        e.preventDefault();
        const trimmed = name.trim();
        if (trimmed) {
            onSave(trimmed);
        }
    };

    const isCreate = mode === 'create';
    const title = isCreate ? t('saved_rooms_dialog_title_create') : t('saved_rooms_dialog_title_rename');
    const actionLabel = t('save'); // We always show "Save" now per user request.

    // Fallback translation keys if they don't exist in i18n
    const safeTitle = title !== 'saved_rooms_dialog_title_create' && title !== 'saved_rooms_dialog_title_rename'
        ? title : (isCreate ? 'Save Room' : 'Rename Room');
    const safeAction = actionLabel !== 'save' ? actionLabel : 'Save';

    return (
        <div className="modal-overlay" onClick={onClose}>
            <div
                ref={dialogRef}
                className="modal-content"
                role="dialog"
                aria-modal="true"
                aria-labelledby={titleId}
                tabIndex={-1}
                onClick={e => e.stopPropagation()}
            >
                <div className="modal-header">
                    <h3 id={titleId}>{safeTitle}</h3>
                    <button className="modal-close" onClick={onClose} aria-label={t('cancel') !== 'cancel' ? t('cancel') : 'Cancel'}>
                        <X size={20} />
                    </button>
                </div>

                <form onSubmit={handleSubmit} className="modal-body">
                    <div className="form-group" style={{ width: '100%' }}>
                        <input
                            ref={inputRef}
                            id="roomName"
                            type="text"
                            value={name}
                            onChange={e => setName(e.target.value)}
                            placeholder={t('saved_rooms_name_placeholder') !== 'saved_rooms_name_placeholder' ? t('saved_rooms_name_placeholder') : 'E.g., Weekly Sync'}
                            maxLength={120}
                        />
                    </div>

                    {isCreate && roomId && name.trim() && (
                        <div className="form-group helper-text">
                            <p>
                                {t('saved_rooms_helper_text') !== 'saved_rooms_helper_text'
                                    ? t('saved_rooms_helper_text')
                                    : 'This will generate a shareable link that adds this room with this name for everyone who opens it.'}
                            </p>
                        </div>
                    )}

                    <div className="modal-footer">
                        <button type="button" className="btn-secondary" onClick={onClose}>
                            {t('cancel') !== 'cancel' ? t('cancel') : 'Cancel'}
                        </button>
                        <button type="submit" className="btn-primary" disabled={!name.trim()}>
                            {safeAction}
                        </button>
                    </div>
                </form>
            </div>
        </div>
    );
};
