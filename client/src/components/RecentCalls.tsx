import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { BookmarkPlus, Clock, MoreVertical, Trash2 } from 'lucide-react';
import type { RecentCall } from '../utils/callHistory';
import { removeRecentCall } from '../utils/callHistory';
import type { SaveRoomResult, SavedRoom } from '../utils/savedRooms';
import { useTranslation } from 'react-i18next';
import { SavedRoomDialog } from './SavedRoomDialog';
import { useToast } from '../contexts/ToastContext';
import { saveRoom } from '../utils/savedRooms';

interface RecentCallsProps {
    calls: RecentCall[];
    roomStatuses: Record<string, number>;
    savedRooms: SavedRoom[];
    onCallUpdate?: () => void;
}

const RecentCalls: React.FC<RecentCallsProps> = ({ calls, roomStatuses, savedRooms, onCallUpdate }) => {
    const { t, i18n } = useTranslation();
    const navigate = useNavigate();
    const [activeMenu, setActiveMenu] = useState<string | null>(null);
    const [dialogOpen, setDialogOpen] = useState(false);
    const [selectedRoomId, setSelectedRoomId] = useState<string | null>(null);
    const { showToast } = useToast();

    React.useEffect(() => {
        const handleClickOutside = () => {
            setActiveMenu(null);
        };
        if (activeMenu) {
            document.addEventListener('click', handleClickOutside);
        }
        return () => {
            document.removeEventListener('click', handleClickOutside);
        };
    }, [activeMenu]);

    const showSaveRoomError = (result: SaveRoomResult) => {
        if (result === 'quota_exceeded') {
            showToast('error', t('toast_saved_rooms_storage_full') || 'Storage is full. Remove old rooms and try again.');
            return;
        }
        showToast('error', t('toast_saved_rooms_save_error') || 'Failed to save room.');
    };

    const handleMenuToggle = (e: React.MouseEvent, roomId: string) => {
        e.stopPropagation();
        setActiveMenu(activeMenu === roomId ? null : roomId);
    };

    const handleSaveClick = (e: React.MouseEvent, roomId: string) => {
        e.stopPropagation();
        setSelectedRoomId(roomId);
        setDialogOpen(true);
        setActiveMenu(null);
    };

    const handleDialogSave = async (newName: string) => {
        if (selectedRoomId) {
            const result = saveRoom({
                roomId: selectedRoomId,
                name: newName,
                createdAt: Date.now()
            });
            if (result !== 'ok') {
                showSaveRoomError(result);
                return;
            }
            showToast('success', t('saved_rooms_save_success') || 'Room saved successfully');
            try {
                if (navigator.clipboard?.writeText) {
                    const shareUrl = `${window.location.origin}/call/${selectedRoomId}?name=${encodeURIComponent(newName)}`;
                    await navigator.clipboard.writeText(shareUrl);
                }
            } catch (err) {
                console.warn('Failed to copy saved room link', err);
            }
            if (onCallUpdate) onCallUpdate();
        }
        setDialogOpen(false);
        setSelectedRoomId(null);
    };

    const handleRemoveClick = (e: React.MouseEvent, roomId: string) => {
        e.stopPropagation();
        removeRecentCall(roomId);
        if (onCallUpdate) onCallUpdate();
        setActiveMenu(null);
    };

    const formatDuration = (seconds: number) => {
        if (seconds < 120) {
            const mins = Math.floor(seconds / 60);
            const secs = seconds % 60;
            if (mins === 0) return `${secs}s`;
            return `${mins}m ${secs}s`;
        }
        const mins = Math.round(seconds / 60);
        return `${mins}m`;
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
            <h3 className="recent-calls-label">
                <Clock className="section-label-icon" />
                {t('recent_calls')}
            </h3>
            <div className="recent-calls-table-container">
                <table className="recent-calls-table">
                    <thead>
                        <tr>
                            <th style={{ width: '55%' }}>{t('date_time')}</th>
                            <th className="text-right">{t('duration')}</th>
                            <th style={{ width: '48px' }}></th>
                        </tr>
                    </thead>
                    <tbody>
                        {calls.map((call, index) => {
                            const matchingSavedRoom = savedRooms.find((room) => room.roomId === call.roomId);
                            return (
                                <tr
                                    key={`${call.roomId}-${index}`}
                                    className="recent-call-row"
                                    onClick={() => navigate(`/call/${call.roomId}`)}
                                >
                                    <td>
                                        <div className="recent-call-date-cell">
                                            {renderStatusDot(call.roomId)}
                                            <div className="recent-call-meta">
                                                <span>{formatDate(call.startTime)} at {formatTime(call.startTime)}</span>
                                                {matchingSavedRoom && (
                                                    <span className="recent-call-secondary">{matchingSavedRoom.name}</span>
                                                )}
                                            </div>
                                        </div>
                                    </td>
                                    <td className="text-right">
                                        <div className="recent-call-duration-cell">
                                            <span>{formatDuration(call.duration)}</span>
                                        </div>
                                    </td>
                                    <td>
                                        <div className="menu-container" style={{ position: 'relative' }}>
                                            <button
                                                className="btn-icon small"
                                                onClick={(e) => handleMenuToggle(e, call.roomId)}
                                                style={{ background: 'transparent', border: 'none', color: 'inherit', cursor: 'pointer', padding: '4px' }}
                                            >
                                                <MoreVertical size={16} />
                                            </button>

                                            {activeMenu === call.roomId && (
                                                <div className="dropdown-menu">
                                                    {!savedRooms.some(r => r.roomId === call.roomId) && (
                                                        <button onClick={(e) => handleSaveClick(e, call.roomId)}>
                                                            <BookmarkPlus size={14} /> {t('save_room') || 'Save'}
                                                        </button>
                                                    )}
                                                    <button className="danger" onClick={(e) => handleRemoveClick(e, call.roomId)}>
                                                        <Trash2 size={14} /> {t('remove') !== 'remove' ? t('remove') : 'Remove'}
                                                    </button>
                                                </div>
                                            )}
                                        </div>
                                    </td>
                                </tr>
                            );
                        })}
                    </tbody>
                </table>
            </div>

            {selectedRoomId && (
                <SavedRoomDialog
                    isOpen={dialogOpen}
                    mode="create"
                    roomId={selectedRoomId}
                    onClose={() => {
                        setDialogOpen(false);
                        setSelectedRoomId(null);
                    }}
                    onSave={handleDialogSave}
                />
            )}
        </div>
    );
};

export default RecentCalls;
