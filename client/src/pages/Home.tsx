import React, { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { v4 as uuidv4 } from 'uuid';
import { Video } from 'lucide-react';
import RecentCalls from '../components/RecentCalls';
import { getRecentCalls } from '../utils/callHistory';
import type { RecentCall } from '../utils/callHistory';
import { useSignaling } from '../contexts/SignalingContext';
import { useTranslation } from 'react-i18next';

const Home: React.FC = () => {
    const { t } = useTranslation();
    const navigate = useNavigate();
    const [recentCalls, setRecentCalls] = useState<RecentCall[]>([]);
    const { watchRooms, roomStatuses, isConnected } = useSignaling();

    useEffect(() => {
        const calls = getRecentCalls();
        setRecentCalls(calls);

        if (calls.length > 0 && isConnected) {
            const rids = calls.map(c => c.roomId);
            watchRooms(rids);
        }
    }, [isConnected]);

    const startCall = () => {
        const roomId = uuidv4();
        navigate(`/call/${roomId}`);
    };

    return (
        <div className={`page-container center-content ${recentCalls.length > 0 ? 'compact' : ''}`}>
            <div className="home-content">
                <h1 className="title">{t('app_title')}</h1>
                <p className="subtitle">
                    {t('app_subtitle_1')} <br />
                    {t('app_subtitle_2')}
                </p>

                <button onClick={startCall} className="btn-primary btn-large">
                    <Video className="icon" />
                    {t('start_call')}
                </button>

                <RecentCalls calls={recentCalls} roomStatuses={roomStatuses} />
            </div>
        </div>
    );
};

export default Home;
