export function playJoinChime(): void {
    try {
        const AudioContextClass = window.AudioContext ?? ((window as Window & { webkitAudioContext?: typeof AudioContext }).webkitAudioContext);
        if (!AudioContextClass) return;

        const ctx = new AudioContextClass();

        const playNote = (frequency: number, startTime: number, duration: number) => {
            const osc = ctx.createOscillator();
            const gain = ctx.createGain();

            osc.type = 'sine';
            osc.frequency.setValueAtTime(frequency, startTime);

            gain.gain.setValueAtTime(0, startTime);
            gain.gain.linearRampToValueAtTime(0.08, startTime + 0.03);
            gain.gain.exponentialRampToValueAtTime(0.001, startTime + duration);

            osc.connect(gain);
            gain.connect(ctx.destination);

            osc.start(startTime);
            osc.stop(startTime + duration);
        };

        const now = ctx.currentTime;
        playNote(329.63, now, 0.15);
        playNote(392.0, now + 0.07, 0.25);

        window.setTimeout(() => {
            if (ctx.state !== 'closed') {
                void ctx.close();
            }
        }, 1000);
    } catch (err) {
        console.warn('[SerenadaCallFlow] Failed to play join chime', err);
    }
}
