import React from 'react';
import { useNavigate } from 'react-router-dom';
import { Clock, Calendar } from 'lucide-react';
import type { RecentCall } from '../utils/callHistory';
import { useTranslation } from 'react-i18next';

interface RecentCallsProps {
    calls: RecentCall[];
    roomStatuses: Record<string, number>;
}

const RecentCalls: React.FC<RecentCallsProps> = ({ calls, roomStatuses }) => {
    const { t, i18n } = useTranslation();
    const navigate = useNavigate();

    const formatDuration = (seconds: number) => {
        if (seconds < 60) return `${seconds}s`;
        const mins = Math.floor(seconds / 60);
        const secs = seconds % 60;
        return `${mins}m ${secs}s`;
    };

    const formatDate = (timestamp: number) => {
        const date = new Date(timestamp);
        return date.toLocaleDateString(i18n.language, { month: 'short', day: 'numeric' });
    };

    const formatTime = (timestamp: number) => {
        const date = new Date(timestamp);
        return date.toLocaleTimeString(i18n.language, { hour: '2-digit', minute: '2-digit' });
    };

    const renderStatusDot = (roomId: string) => {
        const count = roomStatuses[roomId] || 0;
        if (count === 0) return null;

        const statusClass = count === 1 ? 'status-waiting' : 'status-full';
        const title = count === 1 ? t('someone_waiting') : t('room_full');

        return (
            <div className={`status-dot ${statusClass}`} title={title} />
        );
    };

    if (calls.length === 0) return null;

    return (
        <div className="recent-calls">
            <h3 className="recent-calls-label">{t('recent_calls')}</h3>
            <div className="recent-calls-table-container">
                <table className="recent-calls-table">
                    <thead>
                        <tr>
                            <th>{t('date_time')}</th>
                            <th className="text-right">{t('duration')}</th>
                        </tr>
                    </thead>
                    <tbody>
                        {calls.map((call, index) => (
                            <tr
                                key={`${call.roomId}-${index}`}
                                className="recent-call-row"
                                onClick={() => navigate(`/call/${call.roomId}`)}
                            >
                                <td>
                                    <div className="recent-call-date-cell">
                                        {renderStatusDot(call.roomId)}
                                        <Calendar size={14} className="recent-call-icon" />
                                        <span>{formatDate(call.startTime)} at {formatTime(call.startTime)}</span>
                                    </div>
                                </td>
                                <td className="text-right">
                                    <div className="recent-call-duration-cell">
                                        <Clock size={14} className="recent-call-icon" />
                                        <span>{formatDuration(call.duration)}</span>
                                    </div>
                                </td>
                            </tr>
                        ))}
                    </tbody>
                </table>
            </div>
        </div>
    );
};

export default RecentCalls;
