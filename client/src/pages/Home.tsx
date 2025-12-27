import React from 'react';
import { useNavigate } from 'react-router-dom';
import { v4 as uuidv4 } from 'uuid';
import { Video } from 'lucide-react';

const Home: React.FC = () => {
    const navigate = useNavigate();

    const startCall = () => {
        const roomId = uuidv4();
        navigate(`/call/${roomId}`);
    };

    return (
        <div className="page-container center-content">
            <div className="home-content">
                <h1 className="title">Connected</h1>
                <p className="subtitle">
                    Simple, instant video calls for everyone. <br />
                    No accounts, no downloads.
                </p>

                <button onClick={startCall} className="btn-primary btn-large">
                    <Video className="icon" />
                    Start Call
                </button>
            </div>
        </div>
    );
};

export default Home;
