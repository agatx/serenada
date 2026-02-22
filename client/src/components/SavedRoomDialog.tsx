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

    useEffect(() => {
        if (isOpen) {
            setName(initialName);
            setTimeout(() => {
                if (mode === 'rename' && inputRef.current) {
                    inputRef.current.select();
                } else if (inputRef.current) {
                    inputRef.current.focus();
                }
            }, 10);
        }
    }, [isOpen, initialName, mode]);

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
            <div className="modal-content" onClick={e => e.stopPropagation()}>
                <div className="modal-header">
                    <h3>{safeTitle}</h3>
                    <button className="modal-close" onClick={onClose}>
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
                            <p>This will generate a shareable link that adds this room with this name for everyone who opens it.</p>
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
