import React, { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Video, Zap, Shield, Lock, Smartphone, Code } from 'lucide-react';
import RecentCalls from '../components/RecentCalls';
import Footer from '../components/Footer';
import { getRecentCalls } from '../utils/callHistory';
import type { RecentCall } from '../utils/callHistory';
import { useSignaling } from '../contexts/SignalingContext';
import { useTranslation } from 'react-i18next';
import { useToast } from '../contexts/ToastContext';

const Home: React.FC = () => {
    const { t } = useTranslation();
    const { showToast } = useToast();
    const navigate = useNavigate();
    const [recentCalls, setRecentCalls] = useState<RecentCall[]>([]);
    const [isCreating, setIsCreating] = useState(false);
    const { watchRooms, roomStatuses, isConnected } = useSignaling();

    useEffect(() => {
        const calls = getRecentCalls();
        setRecentCalls(calls);

        if (calls.length > 0 && isConnected) {
            const rids = calls.map(c => c.roomId);
            watchRooms(rids);
        }
    }, [isConnected]);

    const startCall = async () => {
        if (isCreating) return;
        setIsCreating(true);
        try {
            let apiUrl = '/api/room-id';
            const wsUrl = import.meta.env.VITE_WS_URL;
            if (wsUrl) {
                const url = new URL(wsUrl);
                url.protocol = url.protocol === 'wss:' ? 'https:' : 'http:';
                url.pathname = '/api/room-id';
                url.search = '';
                url.hash = '';
                apiUrl = url.toString();
            }

            const res = await fetch(apiUrl, { method: 'POST' });
            if (!res.ok) {
                throw new Error(`Room ID request failed: ${res.status}`);
            }
            const data = await res.json();
            if (!data?.roomId) {
                throw new Error('Room ID missing from response');
            }
            navigate(`/call/${data.roomId}`);
        } catch (err) {
            console.error('Failed to create room', err);
            showToast('error', t('toast_room_create_error'));
        } finally {
            setIsCreating(false);
        }
    };

    const benefits = [
        { icon: <Zap className="benefit-icon" />, title: t('benefit_instant_title'), desc: t('benefit_instant_desc') },
        { icon: <Shield className="benefit-icon" />, title: t('benefit_privacy_title'), desc: t('benefit_privacy_desc') },
        { icon: <Lock className="benefit-icon" />, title: t('benefit_secure_title'), desc: t('benefit_secure_desc') },
        { icon: <Smartphone className="benefit-icon" />, title: t('benefit_universal_title'), desc: t('benefit_universal_desc') },
        { icon: <Code className="benefit-icon" />, title: t('benefit_opensource_title'), desc: t('benefit_opensource_desc') },
    ];

    return (
        <div className={`page-container ${recentCalls.length > 0 ? 'compact' : ''}`}>
            <div className="home-content center-content">
                <h1 className="title">{t('app_title')}</h1>
                <p className="subtitle">
                    {t('app_subtitle_1')} <br />
                    {t('app_subtitle_2')}
                </p>

                <button onClick={startCall} className="btn-primary btn-large" disabled={isCreating}>
                    <Video className="icon" />
                    {t('start_call')}
                </button>

                <RecentCalls calls={recentCalls} roomStatuses={roomStatuses} />
            </div>

            <div className="benefits-container">
                <div className="benefits-grid">
                    {benefits.map((b, i) => (
                        <div key={i} className="benefit-card">
                            {b.icon}
                            <div className="benefit-content">
                                <h3 className="benefit-title">{b.title}</h3>
                                <p className="benefit-desc">{b.desc}</p>
                            </div>
                        </div>
                    ))}
                </div>
            </div>

            <Footer />
        </div>
    );
};

export default Home;
