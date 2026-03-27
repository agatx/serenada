import React, { useState, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { X } from 'lucide-react';
import { useToast } from '../contexts/ToastContext';

interface FeedbackDialogProps {
    isOpen: boolean;
    onClose: () => void;
}

const MAX_LENGTH = 2000;

export const FeedbackDialog: React.FC<FeedbackDialogProps> = ({ isOpen, onClose }) => {
    const { t, i18n } = useTranslation();
    const { showToast } = useToast();
    const [message, setMessage] = useState('');
    const [isSubmitting, setIsSubmitting] = useState(false);
    const textareaRef = React.useRef<HTMLTextAreaElement>(null);
    const dialogRef = React.useRef<HTMLDivElement>(null);
    const previousFocusedElementRef = React.useRef<HTMLElement | null>(null);
    const titleId = React.useId();

    useEffect(() => {
        if (!isOpen) return;

        previousFocusedElementRef.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
        const focusTimer = window.setTimeout(() => {
            textareaRef.current?.focus();
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
            ).filter((el) => !el.hasAttribute('disabled'));
            if (focusableElements.length === 0) {
                event.preventDefault();
                dialogRef.current.focus();
                return;
            }

            const first = focusableElements[0];
            const last = focusableElements[focusableElements.length - 1];

            if (event.shiftKey) {
                if (document.activeElement === first || !dialogRef.current.contains(document.activeElement)) {
                    event.preventDefault();
                    last.focus();
                }
                return;
            }

            if (document.activeElement === last) {
                event.preventDefault();
                first.focus();
            }
        };

        document.addEventListener('keydown', handleKeyDown);
        return () => {
            window.clearTimeout(focusTimer);
            document.removeEventListener('keydown', handleKeyDown);
            previousFocusedElementRef.current?.focus();
            previousFocusedElementRef.current = null;
        };
    }, [isOpen, onClose]);

    if (!isOpen) return null;

    const handleSubmit = async (e: React.FormEvent) => {
        e.preventDefault();
        const trimmed = message.trim();
        if (!trimmed || isSubmitting) return;

        setIsSubmitting(true);
        try {
            const resp = await fetch('/api/feedback', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    message: trimmed,
                    platform: 'web',
                    locale: i18n.language,
                    userAgent: navigator.userAgent,
                }),
            });

            if (resp.status === 429) {
                showToast('error', t('feedback_rate_limit'));
            } else if (!resp.ok) {
                showToast('error', t('feedback_error'));
            } else {
                showToast('success', t('feedback_success'));
                setMessage('');
                onClose();
            }
        } catch {
            showToast('error', t('feedback_error'));
        } finally {
            setIsSubmitting(false);
        }
    };

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
                    <h3 id={titleId}>{t('feedback_title')}</h3>
                    <button className="modal-close" onClick={onClose} aria-label={t('cancel')}>
                        <X size={20} />
                    </button>
                </div>

                <form onSubmit={handleSubmit} className="modal-body">
                    <div className="form-group" style={{ width: '100%' }}>
                        <textarea
                            ref={textareaRef}
                            value={message}
                            onChange={e => setMessage(e.target.value)}
                            placeholder={t('feedback_placeholder')}
                            maxLength={MAX_LENGTH}
                            rows={5}
                            style={{
                                background: 'var(--bg-color)',
                                border: '1px solid rgba(255, 255, 255, 0.2)',
                                color: 'var(--text-primary)',
                                padding: '0.75rem 1rem',
                                borderRadius: '8px',
                                fontSize: '1rem',
                                width: '100%',
                                boxSizing: 'border-box',
                                resize: 'vertical',
                                minHeight: '120px',
                                fontFamily: 'inherit',
                            }}
                        />
                        <p style={{ textAlign: 'right', margin: '4px 0 0' }}>
                            {message.length}/{MAX_LENGTH}
                        </p>
                    </div>

                    <div className="modal-footer">
                        <button type="button" className="btn-secondary" onClick={onClose}>
                            {t('cancel')}
                        </button>
                        <button type="submit" className="btn-primary" disabled={!message.trim() || isSubmitting}>
                            {t('feedback_submit')}
                        </button>
                    </div>
                </form>
            </div>
        </div>
    );
};
