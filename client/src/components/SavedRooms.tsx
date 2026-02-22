import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Bookmark, MoreVertical, Edit2, Trash2, Share2 } from 'lucide-react';
import type { SavedRoom } from '../utils/savedRooms';
import { removeRoom, saveRoom } from '../utils/savedRooms';
import { useTranslation } from 'react-i18next';
import { SavedRoomDialog } from './SavedRoomDialog';
import { useToast } from '../contexts/ToastContext';

interface SavedRoomsProps {
    rooms: SavedRoom[];
    roomStatuses: Record<string, number>;
    onRoomUpdate: () => void;
}

const SavedRooms: React.FC<SavedRoomsProps> = ({ rooms, roomStatuses, onRoomUpdate }) => {
    const { t, i18n } = useTranslation();
    const navigate = useNavigate();
    const { showToast } = useToast();

    const [dialogOpen, setDialogOpen] = useState(false);
    const [dialogMode, setDialogMode] = useState<'create' | 'rename'>('create');
    const [selectedRoom, setSelectedRoom] = useState<SavedRoom | null>(null);
    const [activeMenu, setActiveMenu] = useState<string | null>(null);
    const [isCreatingRoom, setIsCreatingRoom] = useState(false);

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

    const formatDate = (timestamp: number) => {
        const date = new Date(timestamp);
        return date.toLocaleDateString(i18n.language, { month: 'short', day: 'numeric' });
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

    const handleMenuToggle = (e: React.MouseEvent, roomId: string) => {
        e.stopPropagation();
        setActiveMenu(activeMenu === roomId ? null : roomId);
    };

    const handleJoin = (roomId: string) => {
        navigate(`/call/${roomId}`);
    };

    const handleRenameClick = (e: React.MouseEvent, room: SavedRoom) => {
        e.stopPropagation();
        setSelectedRoom(room);
        setDialogMode('rename');
        setDialogOpen(true);
        setActiveMenu(null);
    };

    const createRoomId = async (): Promise<string> => {
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
        return data.roomId;
    };

    const handleCreateClick = async () => {
        if (isCreatingRoom) return;
        setIsCreatingRoom(true);
        try {
            const roomId = await createRoomId();
            setSelectedRoom({
                roomId,
                name: '',
                createdAt: Date.now()
            });
            setDialogMode('create');
            setDialogOpen(true);
        } catch (err) {
            console.error('Failed to create room for saving', err);
            showToast('error', t('toast_room_create_error'));
        } finally {
            setIsCreatingRoom(false);
        }
    };

    const handleRemoveClick = (e: React.MouseEvent, roomId: string) => {
        e.stopPropagation();
        removeRoom(roomId);
        onRoomUpdate();
        setActiveMenu(null);
        showToast('success', 'Room removed');
    };

    const handleShareClick = (e: React.MouseEvent, room: SavedRoom) => {
        e.stopPropagation();
        const shareUrl = `${window.location.origin}/call/${room.roomId}?name=${encodeURIComponent(room.name)}`;
        navigator.clipboard.writeText(shareUrl);
        showToast('success', t('toast_link_copied') || 'Link copied to clipboard');
        setActiveMenu(null);
    };

    const handleDialogSave = async (newName: string) => {
        if (!selectedRoom) {
            setDialogOpen(false);
            return;
        }

        if (dialogMode === 'rename') {
            saveRoom({ ...selectedRoom, name: newName });
            onRoomUpdate();
            showToast('success', 'Room renamed');
        } else {
            saveRoom({
                roomId: selectedRoom.roomId,
                name: newName,
                createdAt: Date.now()
            });
            onRoomUpdate();
            showToast('success', t('saved_rooms_save_success') || 'Room saved successfully');
            try {
                if (navigator.clipboard?.writeText) {
                    const shareUrl = `${window.location.origin}/call/${selectedRoom.roomId}?name=${encodeURIComponent(newName)}`;
                    await navigator.clipboard.writeText(shareUrl);
                }
            } catch (err) {
                console.warn('Failed to copy saved room link', err);
            }
        }

        setDialogOpen(false);
        setSelectedRoom(null);
    };

    const handleDialogClose = () => {
        setDialogOpen(false);
        setSelectedRoom(null);
    };

    return (
        <div className="recent-calls saved-rooms">
            <div className="saved-rooms-header">
                <h3 className="recent-calls-label">
                    <Bookmark className="section-label-icon" />
                    {rooms.length > 0 ? (t('saved_rooms_title') || 'Saved Rooms') : (t('saved_rooms_empty_title') || 'NO SAVED ROOMS')}
                </h3>
                <button className="saved-rooms-create-link" onClick={() => { void handleCreateClick(); }} disabled={isCreatingRoom}>
                    + {t('create') !== 'create' ? t('create') : 'Create'}
                </button>
            </div>

            {rooms.length > 0 && (
                <div className="recent-calls-table-container">
                    <table className="recent-calls-table">
                        <thead>
                            <tr>
                                <th style={{ width: '55%' }}>{t('saved_rooms_name_label') || 'Name'}</th>
                                <th>{t('saved_rooms_last_joined') || 'Last Joined'}</th>
                                <th style={{ width: '48px' }}></th>
                            </tr>
                        </thead>
                        <tbody>
                            {rooms.map((room) => (
                                <tr
                                    key={room.roomId}
                                    className="recent-call-row"
                                    onClick={() => handleJoin(room.roomId)}
                                >
                                    <td>
                                        <div className="recent-call-date-cell">
                                            {renderStatusDot(room.roomId)}
                                            <span className="room-name">{room.name}</span>
                                        </div>
                                    </td>
                                    <td>
                                        <div className="recent-call-duration-cell" style={{ opacity: 0.8 }}>
                                            {room.lastJoinedAt ? (
                                                <span>{formatDate(room.lastJoinedAt)}</span>
                                            ) : (
                                                <span style={{ fontStyle: 'italic' }}>{t('saved_rooms_never_joined') || 'Never'}</span>
                                            )}
                                        </div>
                                    </td>
                                    <td>
                                        <div className="menu-container" style={{ position: 'relative' }}>
                                            <button
                                                className="btn-icon small"
                                                onClick={(e) => handleMenuToggle(e, room.roomId)}
                                                style={{ background: 'transparent', border: 'none', color: 'inherit', cursor: 'pointer', padding: '4px' }}
                                            >
                                                <MoreVertical size={16} />
                                            </button>

                                            {activeMenu === room.roomId && (
                                                <div className="dropdown-menu">
                                                    <button onClick={(e) => handleShareClick(e, room)}>
                                                        <Share2 size={14} /> Share
                                                    </button>
                                                    <button onClick={(e) => handleRenameClick(e, room)}>
                                                        <Edit2 size={14} /> Rename
                                                    </button>
                                                    <button className="danger" onClick={(e) => handleRemoveClick(e, room.roomId)}>
                                                        <Trash2 size={14} /> Remove
                                                    </button>
                                                </div>
                                            )}
                                        </div>
                                    </td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                </div>
            )}

            {selectedRoom && (
                <SavedRoomDialog
                    isOpen={dialogOpen}
                    mode={dialogMode}
                    initialName={dialogMode === 'rename' ? selectedRoom.name : ''}
                    roomId={selectedRoom.roomId}
                    onClose={handleDialogClose}
                    onSave={handleDialogSave}
                />
            )}
        </div>
    );
};

export default SavedRooms;
