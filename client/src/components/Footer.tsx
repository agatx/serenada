import React, { useEffect, useState } from 'react';
import { Github, Activity, Download } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { useToast } from '../contexts/ToastContext';

interface BeforeInstallPromptEvent extends Event {
    prompt: () => Promise<void>;
    userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>;
}

const Footer: React.FC = () => {
    const { t } = useTranslation();
    const { showToast } = useToast();
    const [deferredPrompt, setDeferredPrompt] = useState<BeforeInstallPromptEvent | null>(null);
    const [isStandalone, setIsStandalone] = useState(false);

    useEffect(() => {
        const handler = (e: Event) => {
            e.preventDefault();
            setDeferredPrompt(e as BeforeInstallPromptEvent);
        };

        window.addEventListener('beforeinstallprompt', handler);

        // Check if running in standalone mode (PWA)
        const mediaQuery = window.matchMedia('(display-mode: standalone)');
        setIsStandalone(mediaQuery.matches);

        const handleMediaQueryChange = (e: MediaQueryListEvent) => {
            setIsStandalone(e.matches);
        };

        try {
            mediaQuery.addEventListener('change', handleMediaQueryChange);
        } catch (e) {
            // Fallback for older browsers
            mediaQuery.addListener(handleMediaQueryChange);
        }

        return () => {
            window.removeEventListener('beforeinstallprompt', handler);
            try {
                mediaQuery.removeEventListener('change', handleMediaQueryChange);
            } catch (e) {
                mediaQuery.removeListener(handleMediaQueryChange);
            }
        };
    }, []);

    const handleInstall = async () => {
        if (deferredPrompt) {
            deferredPrompt.prompt();
            const { outcome } = await deferredPrompt.userChoice;
            if (outcome === 'accepted') {
                setDeferredPrompt(null);
            }
        } else {
            const isIOS = /iPad|iPhone|iPod/.test(navigator.userAgent) && !(window as any).MSStream;
            const isMac = /Macintosh/.test(navigator.userAgent);
            const isSafari = /Safari/.test(navigator.userAgent) && !/Chrome/.test(navigator.userAgent);

            if (isIOS) {
                showToast('info', t('install_ios_prompt'));
            } else if (isMac && isSafari) {
                showToast('info', t('install_mac_safari_prompt'));
            } else {
                showToast('info', t('install_not_supported'));
            }
        }
    };

    return (
        <footer className="footer">
            <nav className="footer-nav">
                <a
                    href="https://github.com/agatx/serenada"
                    target="_blank"
                    rel="noopener noreferrer"
                    className="footer-link"
                >
                    <Github className="icon" />
                    {t('footer_github')}
                </a>

                <a href="/device-check" className="footer-link">
                    <Activity className="icon" />
                    {t('footer_device_check')}
                </a>

                {!isStandalone && (
                    <button onClick={handleInstall} className="footer-link">
                        <Download className="icon" />
                        {t('footer_install')}
                    </button>
                )}
            </nav>

            <p style={{ color: 'var(--text-secondary)', fontSize: '0.8rem', opacity: 0.6 }}>
                &copy; {new Date().getFullYear()} Serenada. {t('benefit_opensource_title')}
            </p>
        </footer>
    );
};

export default Footer;
