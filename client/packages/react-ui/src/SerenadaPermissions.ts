export const SerenadaPermissions = {
    async request(capabilities: Array<'camera' | 'microphone'>): Promise<boolean> {
        try {
            const constraints: MediaStreamConstraints = {};
            if (capabilities.includes('camera')) constraints.video = true;
            if (capabilities.includes('microphone')) constraints.audio = true;
            const stream = await navigator.mediaDevices.getUserMedia(constraints);
            stream.getTracks().forEach(t => t.stop());
            return true;
        } catch {
            return false;
        }
    },

    async check(capability: 'camera' | 'microphone'): Promise<PermissionState> {
        try {
            const name = capability === 'camera' ? 'camera' : 'microphone';
            const result = await navigator.permissions.query({ name: name as PermissionName });
            return result.state;
        } catch {
            return 'prompt';
        }
    },
};
